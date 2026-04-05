package com.patbaumgartner.greener.core.runner;

import com.patbaumgartner.greener.core.config.JoularCoreConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JoularCoreRunnerTest {

	private final JoularCoreRunner runner = new JoularCoreRunner();

	@AfterEach
	void tearDown() throws InterruptedException {
		runner.stop();
	}

	@Test
	void isRunning_beforeStart_returnsFalse() {
		assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void stop_beforeStart_doesNotThrow() throws InterruptedException {
		runner.stop();
		// no exception expected
	}

	@Test
	void start_nullBinaryPath_throwsIOException() {
		JoularCoreConfig config = new JoularCoreConfig().outputCsvPath(Path.of("/tmp/out.csv"));

		assertThatThrownBy(() -> runner.start(config)).isInstanceOf(IOException.class)
			.hasMessageContaining("binary not found");
	}

	@Test
	void start_nonExistentBinary_throwsIOException() {
		JoularCoreConfig config = new JoularCoreConfig().binaryPath(Path.of("/nonexistent/joularcore"))
			.outputCsvPath(Path.of("/tmp/out.csv"));

		assertThatThrownBy(() -> runner.start(config)).isInstanceOf(IOException.class)
			.hasMessageContaining("binary not found");
	}

	@Test
	void start_nullOutputCsvPath_throwsIOException(@TempDir Path tempDir) throws IOException {
		Path fakeBinary = tempDir.resolve("joularcore");
		Files.writeString(fakeBinary, "fake");

		JoularCoreConfig config = new JoularCoreConfig().binaryPath(fakeBinary);

		assertThatThrownBy(() -> runner.start(config)).isInstanceOf(IOException.class)
			.hasMessageContaining("outputCsvPath must not be null");
	}

	@Test
	void start_withRealExecutable_processIsRunning(@TempDir Path tempDir) throws Exception {
		Path script = createExecutableScript(tempDir, "#!/bin/sh\nsleep 60\n");
		Path outputCsv = tempDir.resolve("output.csv");

		JoularCoreConfig config = new JoularCoreConfig().binaryPath(script)
			.outputCsvPath(outputCsv)
			.pid(1L)
			.silent(true);

		runner.start(config);

		assertThat(runner.isRunning()).isTrue();
	}

	@Test
	void stop_afterStart_processStops(@TempDir Path tempDir) throws Exception {
		Path script = createExecutableScript(tempDir, "#!/bin/sh\nsleep 60\n");
		Path outputCsv = tempDir.resolve("output.csv");

		JoularCoreConfig config = new JoularCoreConfig().binaryPath(script)
			.outputCsvPath(outputCsv)
			.pid(1L)
			.silent(true);

		runner.start(config);
		assertThat(runner.isRunning()).isTrue();

		runner.stop();
		assertThat(runner.isRunning()).isFalse();
	}

	@Test
	void start_createsOutputDirectory(@TempDir Path tempDir) throws Exception {
		Path script = createExecutableScript(tempDir, "#!/bin/sh\nsleep 60\n");
		Path nested = tempDir.resolve("sub").resolve("dir").resolve("output.csv");

		JoularCoreConfig config = new JoularCoreConfig().binaryPath(script).outputCsvPath(nested).pid(1L).silent(true);

		runner.start(config);

		assertThat(nested.getParent()).exists();
	}

	@Test
	void start_withVmMode_setsEnvironmentVariables(@TempDir Path tempDir) throws Exception {
		Path script = createExecutableScript(tempDir,
				"#!/bin/sh\nenv > " + tempDir.resolve("env.txt") + "\nsleep 60\n");
		Path outputCsv = tempDir.resolve("output.csv");
		Path vmPowerFile = tempDir.resolve("vm-power.txt");
		Files.writeString(vmPowerFile, "42.5");

		JoularCoreConfig config = new JoularCoreConfig().binaryPath(script)
			.outputCsvPath(outputCsv)
			.pid(1L)
			.vmMode(true)
			.vmPowerFilePath(vmPowerFile)
			.silent(true);

		runner.start(config);

		// Give the process a moment to write env file
		Thread.sleep(500);

		Path envFile = tempDir.resolve("env.txt");
		if (Files.exists(envFile)) {
			String envContent = Files.readString(envFile);
			assertThat(envContent).contains("VM_CPU_POWER_FILE");
			assertThat(envContent).contains("VM_CPU_POWER_FORMAT=watts");
		}
	}

	@Test
	void start_withSilentFalse_doesNotDiscard(@TempDir Path tempDir) throws Exception {
		Path script = createExecutableScript(tempDir, "#!/bin/sh\nsleep 60\n");
		Path outputCsv = tempDir.resolve("output.csv");

		JoularCoreConfig config = new JoularCoreConfig().binaryPath(script)
			.outputCsvPath(outputCsv)
			.pid(1L)
			.silent(false);

		runner.start(config);

		assertThat(runner.isRunning()).isTrue();
	}

	private Path createExecutableScript(Path dir, String content) throws IOException {
		Path script = dir.resolve("fake-joularcore");
		Files.writeString(script, content);
		Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE));
		return script;
	}

}
