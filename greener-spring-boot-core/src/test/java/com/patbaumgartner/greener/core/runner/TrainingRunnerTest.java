package com.patbaumgartner.greener.core.runner;

import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.exception.EnergyMeasurementException;
import com.patbaumgartner.greener.core.exception.EnergyMeasurementException.Hint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TrainingRunnerTest {

	private final TrainingRunner runner = new TrainingRunner();

	// ---- deriveToolName ----

	@ParameterizedTest
	@CsvSource({ "run.sh, oha, oha", "run.sh, wrk, wrk", "run.sh, wrk2, wrk2", "run.sh, k6, k6",
			"run.sh, gatling, gatling", "run.sh, locust, locust", "run.sh, bombardier, bombardier",
			"run.sh, jmeter, jmeter", "run.sh, ab, ab", "gatling-run.sh, scripts, gatling", "k6-script.js, load, k6",
			"oha-bench.sh, bench, oha", "unknown.sh, scripts, script" })
	void deriveToolName_recognisesKnownTools(String fileName, String parentDir, String expected) {
		assertThat(TrainingRunner.deriveToolName(fileName, parentDir)).isEqualTo(expected);
	}

	@Test
	void deriveToolName_nullParent_fallsBackToFileName() {
		assertThat(TrainingRunner.deriveToolName("oha-test.sh", null)).isEqualTo("oha");
	}

	@Test
	void deriveToolName_unknownFile_nullParent_returnsScript() {
		assertThat(TrainingRunner.deriveToolName("run.sh", null)).isEqualTo("script");
	}

	@Test
	void deriveToolName_wrk2TakesPrecedenceOverWrk() {
		// "wrk2" contains "wrk", so wrk2 must be checked first
		assertThat(TrainingRunner.deriveToolName("run.sh", "wrk2")).isEqualTo("wrk2");
	}

	@Test
	void deriveToolName_abRequiresExactParent() {
		// "ab" should only match on exact parent directory, not as a substring
		assertThat(TrainingRunner.deriveToolName("abstract.sh", "tools")).isEqualTo("script");
		assertThat(TrainingRunner.deriveToolName("run.sh", "ab")).isEqualTo("ab");
	}

	// ---- run validation ----

	@Test
	void run_noWorkloadConfigured_throwsIOException() {
		TrainingConfig config = new TrainingConfig().baseUrl("http://localhost:8080").requestsPerSecond(5);

		assertThatThrownBy(() -> runner.run(config)).isInstanceOf(IOException.class)
			.hasMessageContaining("No external workload configured");
	}

	@Test
	void run_scriptFileNotFound_throwsIOException() {
		TrainingConfig config = new TrainingConfig().baseUrl("http://localhost:8080")
			.externalScriptFile("/nonexistent/script.sh");

		assertThatThrownBy(() -> runner.run(config)).isInstanceOf(IOException.class).hasMessageContaining("not found");
	}

	@Test
	void run_scriptFile_executesSuccessfully(@TempDir Path tempDir) throws Exception {
		Path script = tempDir.resolve("test.sh");
		Files.writeString(script, "#!/bin/sh\necho 'done'\n");
		script.toFile().setExecutable(true);

		TrainingConfig config = new TrainingConfig().baseUrl("http://localhost:8080")
			.requestsPerSecond(1)
			.warmupDurationSeconds(0)
			.measureDurationSeconds(1)
			.externalScriptFile(script.toAbsolutePath().toString());

		var stats = runner.run(config);
		assertThat(stats).isNotNull();
		assertThat(stats.tool()).isEqualTo("script");
	}

	@Test
	void run_externalCommand_executesSuccessfully() throws Exception {
		TrainingConfig config = new TrainingConfig().baseUrl("http://localhost:8080")
			.requestsPerSecond(1)
			.warmupDurationSeconds(0)
			.measureDurationSeconds(1)
			.externalCommand("echo hello");

		var stats = runner.run(config);
		assertThat(stats).isNotNull();
	}

	@Test
	void run_failingCommand_throwsIOException() {
		TrainingConfig config = new TrainingConfig().baseUrl("http://localhost:8080").externalCommand("exit 1");

		assertThatThrownBy(() -> runner.run(config)).isInstanceOf(IOException.class)
			.hasMessageContaining("exited with code");
	}

	// ---- workload timeout ----

	@Test
	@DisabledOnOs(OS.WINDOWS)
	@Timeout(30)
	void run_workloadThatHangsHoldingStdoutOpen_isKilledAtTheTimeout() {
		// The workload keeps stdout open forever. Draining it inline would block until
		// the child exits, so the timeout could never fire - the bug this guards.
		TrainingConfig config = new TrainingConfig().externalCommand("echo working; sleep 600").timeoutSeconds(2);

		EnergyMeasurementException thrown = catchThrowableOfType(EnergyMeasurementException.class,
				() -> runner.run(config));

		assertThat(thrown).isNotNull();
		assertThat(thrown.hint()).isEqualTo(Hint.WORKLOAD_TIMEOUT);
		assertThat(thrown).hasMessageContaining("timed out after 2 seconds");
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	@Timeout(30)
	void run_workloadTimeout_killsDescendantsNotJustTheShell() throws Exception {
		Path marker = Files.createTempDirectory("greener-descendant").resolve("alive.txt");
		// The grandchild keeps touching a marker file. If only the `sh -c` wrapper were
		// killed it would survive the timeout and keep writing.
		TrainingConfig config = new TrainingConfig()
			.externalCommand("sh -c 'while true; do touch " + marker + "; sleep 0.2; done'")
			.timeoutSeconds(2);

		assertThatThrownBy(() -> runner.run(config)).isInstanceOf(EnergyMeasurementException.class);

		Files.deleteIfExists(marker);
		Thread.sleep(1_500);
		assertThat(Files.exists(marker)).as("descendant still running after timeout").isFalse();
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	@Timeout(30)
	void run_workloadFinishingBeforeTimeout_succeeds() throws Exception {
		TrainingConfig config = new TrainingConfig().externalCommand("echo quick").timeoutSeconds(20);

		assertThat(runner.run(config)).isNotNull();
	}

	@Test
	void run_failingCommand_carriesWorkloadFailedHint() {
		TrainingConfig config = new TrainingConfig().externalCommand("exit 3");

		EnergyMeasurementException thrown = catchThrowableOfType(EnergyMeasurementException.class,
				() -> runner.run(config));

		assertThat(thrown.hint()).isEqualTo(Hint.WORKLOAD_FAILED);
		assertThat(thrown).hasMessageContaining("exited with code 3");
	}

	@Test
	void run_missingScript_carriesWorkloadToolMissingHint() {
		TrainingConfig config = new TrainingConfig().externalScriptFile("/nonexistent/script.sh");

		EnergyMeasurementException thrown = catchThrowableOfType(EnergyMeasurementException.class,
				() -> runner.run(config));

		assertThat(thrown.hint()).isEqualTo(Hint.WORKLOAD_TOOL_MISSING);
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	@Timeout(30)
	void run_capturesOutputProducedBeforeCompletion() throws Exception {
		TrainingConfig config = new TrainingConfig()
			.externalCommand("echo 'Summary:'; echo '  Success rate: 100.00%'; echo '  Total: 500 responses'")
			.timeoutSeconds(20);

		assertThat(runner.run(config).tool()).isNotBlank();
	}

}
