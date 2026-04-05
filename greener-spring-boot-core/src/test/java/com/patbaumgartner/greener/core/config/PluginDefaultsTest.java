package com.patbaumgartner.greener.core.config;

import com.patbaumgartner.greener.core.model.PowerSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginDefaultsTest {

	@TempDir
	Path tempDir;

	// ---- normalise ----

	@Test
	void normaliseReturnsNullForNull() {
		assertThat(PluginDefaults.normalise(null)).isNull();
	}

	@Test
	void normaliseReturnsNullForBlank() {
		assertThat(PluginDefaults.normalise("   ")).isNull();
	}

	@Test
	void normaliseReturnsNullForEmpty() {
		assertThat(PluginDefaults.normalise("")).isNull();
	}

	@Test
	void normaliseReturnsNullForUnresolvedMavenProperty() {
		assertThat(PluginDefaults.normalise("${env.GITHUB_SHA}")).isNull();
	}

	@Test
	void normaliseReturnsValueForNonBlank() {
		assertThat(PluginDefaults.normalise("abc123")).isEqualTo("abc123");
	}

	// ---- buildEffectiveAppArgs ----

	@Test
	void buildEffectiveAppArgsWithNullInput() {
		List<String> result = PluginDefaults.buildEffectiveAppArgs(null);
		assertThat(result).containsExactly("--management.endpoint.health.probes.enabled=true");
	}

	@Test
	void buildEffectiveAppArgsWithEmptyInput() {
		List<String> result = PluginDefaults.buildEffectiveAppArgs(Collections.emptyList());
		assertThat(result).containsExactly("--management.endpoint.health.probes.enabled=true");
	}

	@Test
	void buildEffectiveAppArgsPreservesUserArgs() {
		List<String> result = PluginDefaults.buildEffectiveAppArgs(Arrays.asList("--server.port=9090", "--debug"));
		assertThat(result).containsExactly("--server.port=9090", "--debug",
				"--management.endpoint.health.probes.enabled=true");
	}

	// ---- autoDetectJar ----

	@Test
	void autoDetectJarReturnsEmptyForNonExistentDirectory() {
		Optional<File> result = PluginDefaults.autoDetectJar(tempDir.resolve("nonexistent"));
		assertThat(result).isEmpty();
	}

	@Test
	void autoDetectJarFindsSingleJar() throws Exception {
		Files.createFile(tempDir.resolve("myapp-1.0.jar"));
		Optional<File> result = PluginDefaults.autoDetectJar(tempDir);
		assertThat(result).isPresent();
		assertThat(result.get().getName()).isEqualTo("myapp-1.0.jar");
	}

	@Test
	void autoDetectJarExcludesSourcesAndJavadocJars() throws Exception {
		Files.createFile(tempDir.resolve("myapp-1.0.jar"));
		Files.createFile(tempDir.resolve("myapp-1.0-sources.jar"));
		Files.createFile(tempDir.resolve("myapp-1.0-javadoc.jar"));
		Files.createFile(tempDir.resolve("myapp-1.0-tests.jar"));

		Optional<File> result = PluginDefaults.autoDetectJar(tempDir);
		assertThat(result).isPresent();
		assertThat(result.get().getName()).isEqualTo("myapp-1.0.jar");
	}

	@Test
	void autoDetectJarExcludesOriginalJars() throws Exception {
		Files.createFile(tempDir.resolve("myapp-1.0.jar"));
		Files.createFile(tempDir.resolve("myapp-1.0.jar.original"));

		Optional<File> result = PluginDefaults.autoDetectJar(tempDir);
		assertThat(result).isPresent();
		assertThat(result.get().getName()).isEqualTo("myapp-1.0.jar");
	}

	@Test
	void autoDetectJarAppliesAdditionalExclusions() throws Exception {
		Files.createFile(tempDir.resolve("myapp-1.0.jar"));
		Files.createFile(tempDir.resolve("myapp-1.0-plain.jar"));

		Optional<File> result = PluginDefaults.autoDetectJar(tempDir, "-plain.jar");
		assertThat(result).isPresent();
		assertThat(result.get().getName()).isEqualTo("myapp-1.0.jar");
	}

	@Test
	void autoDetectJarThrowsForMultipleJars() throws Exception {
		Files.createFile(tempDir.resolve("app1.jar"));
		Files.createFile(tempDir.resolve("app2.jar"));

		assertThatThrownBy(() -> PluginDefaults.autoDetectJar(tempDir)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Multiple jars");
	}

	@Test
	void autoDetectJarThrowsForNoJars() throws Exception {
		// tempDir exists but has no jars
		assertThatThrownBy(() -> PluginDefaults.autoDetectJar(tempDir)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("No jar found");
	}

	// ---- formatBaselineUpdateSummary ----

	@Test
	void formatBaselineUpdateSummaryContainsAllFields() {
		Path baselineFile = Path.of("/project/energy-baseline.json");
		List<String> lines = PluginDefaults.formatBaselineUpdateSummary(baselineFile, "abc123", "main", 42.567);

		assertThat(lines).hasSize(4);
		assertThat(lines.get(0)).contains("Energy baseline updated");
		assertThat(lines.get(1)).contains("abc123");
		assertThat(lines.get(2)).contains("main");
		assertThat(lines.get(3)).contains("42.57 J");
	}

	@Test
	void formatBaselineUpdateSummaryHandlesNulls() {
		List<String> lines = PluginDefaults.formatBaselineUpdateSummary(Path.of("baseline.json"), null, null, 0.0);

		assertThat(lines).hasSize(4);
		assertThat(lines.get(1)).contains("n/a");
		assertThat(lines.get(2)).contains("n/a");
		assertThat(lines.get(3)).contains("0.00 J");
	}

	// ---- resolveToolName ----

	@Test
	void resolveToolNameFromScriptFile() throws Exception {
		Path scriptDir = tempDir.resolve("oha");
		Files.createDirectories(scriptDir);
		Path scriptFile = scriptDir.resolve("run.sh");
		Files.createFile(scriptFile);

		String result = PluginDefaults.resolveToolName(scriptFile.toFile(), null);
		assertThat(result).isEqualTo("oha");
	}

	@Test
	void resolveToolNameFromCommand() {
		String result = PluginDefaults.resolveToolName(null, "oha http://localhost:8080");
		assertThat(result).isEqualTo("oha");
	}

	@Test
	void resolveToolNameFallsBackToMeasurement() {
		assertThat(PluginDefaults.resolveToolName(null, null)).isEqualTo("measurement");
	}

	@Test
	void resolveToolNamePrefersScriptOverCommand() throws Exception {
		Path scriptDir = tempDir.resolve("wrk");
		Files.createDirectories(scriptDir);
		Path scriptFile = scriptDir.resolve("run.sh");
		Files.createFile(scriptFile);

		String result = PluginDefaults.resolveToolName(scriptFile.toFile(), "oha http://localhost:8080");
		assertThat(result).isEqualTo("wrk");
	}

	// ---- buildTimestampedDir ----

	@Test
	void buildTimestampedDirAppendsTimestamp() {
		Path base = tempDir.resolve("greener-reports");
		Path result = PluginDefaults.buildTimestampedDir(base);
		assertThat(result.getFileName().toString()).startsWith("greener-reports-");
		assertThat(result.getFileName().toString()).matches("greener-reports-\\d{8}-\\d{6}");
		assertThat(result.getParent()).isEqualTo(base.getParent());
	}

	// ---- createLatestLink ----

	@Test
	void createLatestLinkCreatesSymlink() throws Exception {
		Path targetDir = tempDir.resolve("reports-20260404-153012");
		Files.createDirectories(targetDir);

		PluginDefaults.createLatestLink(targetDir, "reports-latest");

		Path link = tempDir.resolve("reports-latest");
		assertThat(link).exists();
		assertThat(Files.isSymbolicLink(link)).isTrue();
		assertThat(Files.readSymbolicLink(link)).isEqualTo(targetDir);
	}

	@Test
	void createLatestLinkReplacesExistingLink() throws Exception {
		Path oldDir = tempDir.resolve("reports-old");
		Files.createDirectories(oldDir);
		Path newDir = tempDir.resolve("reports-new");
		Files.createDirectories(newDir);

		PluginDefaults.createLatestLink(oldDir, "reports-latest");
		PluginDefaults.createLatestLink(newDir, "reports-latest");

		Path link = tempDir.resolve("reports-latest");
		assertThat(Files.readSymbolicLink(link)).isEqualTo(newDir);
	}

	// ---- validateMeasureDuration ----

	@Test
	void validateMeasureDurationAcceptsPositiveValue() {
		PluginDefaults.validateMeasureDuration(30);
	}

	@Test
	void validateMeasureDurationRejectsZero() {
		assertThatThrownBy(() -> PluginDefaults.validateMeasureDuration(0)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("measureDurationSeconds must be > 0");
	}

	@Test
	void validateMeasureDurationRejectsNegative() {
		assertThatThrownBy(() -> PluginDefaults.validateMeasureDuration(-5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("measureDurationSeconds must be > 0");
	}

	// ---- validateExternalScript ----

	@Test
	void validateExternalScriptAcceptsNull() {
		PluginDefaults.validateExternalScript(null);
	}

	@Test
	void validateExternalScriptAcceptsExistingFile() throws Exception {
		File script = tempDir.resolve("run.sh").toFile();
		Files.createFile(script.toPath());
		PluginDefaults.validateExternalScript(script);
	}

	@Test
	void validateExternalScriptRejectsMissingFile() {
		File missing = tempDir.resolve("nonexistent.sh").toFile();
		assertThatThrownBy(() -> PluginDefaults.validateExternalScript(missing))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("externalTrainingScriptFile does not exist");
	}

	// ---- buildRunId ----

	@Test
	void buildRunId_returnsTimestampWhenNoGithubSha() {
		String runId = PluginDefaults.buildRunId();
		assertThat(runId).isNotNull().isNotBlank();
	}

	// ---- resolvePowerSource ----

	@Test
	void resolvePowerSource_vmModeTrue_returnsVmOrEstimated() {
		PowerSource source = PluginDefaults.resolvePowerSource(true);
		assertThat(source).isNotNull();
	}

	@Test
	void resolvePowerSource_vmModeFalse_returnsDetected() {
		PowerSource source = PluginDefaults.resolvePowerSource(false);
		assertThat(source).isNotNull();
	}

	// ---- resolveToolName edge cases ----

	@Test
	void resolveToolNameWithBlankCommand() {
		assertThat(PluginDefaults.resolveToolName(null, "   ")).isEqualTo("measurement");
	}

	@Test
	void resolveToolNameWithNonExistentScriptFallsToCommand() {
		File nonExistent = tempDir.resolve("does-not-exist.sh").toFile();
		String result = PluginDefaults.resolveToolName(nonExistent, "wrk http://localhost:8080");
		assertThat(result).isEqualTo("wrk");
	}

}
