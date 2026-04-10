package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GreenerPluginTest {

	@Test
	void pluginRegistersExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		assertThat(project.getExtensions().findByName("greener")).isNotNull().isInstanceOf(GreenerExtension.class);
	}

	@Test
	void pluginRegistersMeasureEnergyTask() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		assertThat(project.getTasks().findByName("measureEnergy")).isNotNull().isInstanceOf(MeasureEnergyTask.class);
	}

	@Test
	void pluginRegistersUpdateBaselineTask() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		assertThat(project.getTasks().findByName("updateEnergyBaseline")).isNotNull()
			.isInstanceOf(UpdateBaselineTask.class);
	}

	@Test
	void tasksBelongToGreenerGroup() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		assertThat(project.getTasks().getByName("measureEnergy").getGroup()).isEqualTo("greener");
		assertThat(project.getTasks().getByName("updateEnergyBaseline").getGroup()).isEqualTo("greener");
	}

	@Test
	void extensionDefaultsAreSet() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);

		assertThat(ext.getJoularCoreComponent().get()).isEqualTo("cpu");
		assertThat(ext.getVmMode().get()).isFalse();
		assertThat(ext.getBaseUrl().get()).isEqualTo("http://localhost:8080");
		assertThat(ext.getRequestsPerSecond().get()).isEqualTo(5);
		assertThat(ext.getWarmupDurationSeconds().get()).isEqualTo(30);
		assertThat(ext.getMeasureDurationSeconds().get()).isEqualTo(60);
		assertThat(ext.getStartupTimeoutSeconds().get()).isEqualTo(120);
		assertThat(ext.getHealthCheckPath().get()).isEqualTo("/actuator/health/readiness");
		assertThat(ext.getThreshold().get()).isEqualTo(10.0);
		assertThat(ext.getFailOnRegression().get()).isFalse();
		assertThat(ext.getSkip().get()).isFalse();
	}

	@Test
	void measureTaskConventionsFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");

		assertThat(task.getJoularCoreComponent().get()).isEqualTo("cpu");
		assertThat(task.getVmMode().get()).isFalse();
		assertThat(task.getMeasureDurationSeconds().get()).isEqualTo(60);
		assertThat(task.getThreshold().get()).isEqualTo(10.0);
		assertThat(task.getFailOnRegression().get()).isFalse();
		assertThat(task.getSkip().get()).isFalse();
	}

	// ---- H4: Task-level tests ----

	@Test
	void vmModeConventionPropagatesFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getVmMode().set(true);

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getVmMode().get()).isTrue();
	}

	@Test
	void vmPowerFilePathConventionPropagatesFromExtension(@TempDir Path tempDir) throws IOException {
		Path vmFile = tempDir.resolve("vm-power.txt");
		Files.writeString(vmFile, "42.5");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getVmPowerFilePath().set(vmFile.toFile());

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getVmPowerFilePath().get().getAsFile()).isEqualTo(vmFile.toFile());
	}

	@Test
	void extensionOverridesPropagateThroughConventions() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getBaseUrl().set("http://localhost:9090");
		ext.getRequestsPerSecond().set(100);
		ext.getWarmupDurationSeconds().set(10);
		ext.getMeasureDurationSeconds().set(120);
		ext.getStartupTimeoutSeconds().set(300);
		ext.getHealthCheckPath().set("/health/live");
		ext.getThreshold().set(5.0);
		ext.getFailOnRegression().set(true);

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");

		assertThat(task.getBaseUrl().get()).isEqualTo("http://localhost:9090");
		assertThat(task.getRequestsPerSecond().get()).isEqualTo(100);
		assertThat(task.getWarmupDurationSeconds().get()).isEqualTo(10);
		assertThat(task.getMeasureDurationSeconds().get()).isEqualTo(120);
		assertThat(task.getStartupTimeoutSeconds().get()).isEqualTo(300);
		assertThat(task.getHealthCheckPath().get()).isEqualTo("/health/live");
		assertThat(task.getThreshold().get()).isEqualTo(5.0);
		assertThat(task.getFailOnRegression().get()).isTrue();
	}

	@Test
	void updateBaselineTaskConventionsFromExtension(@TempDir Path tempDir) throws IOException {
		Path baseline = tempDir.resolve("energy-baseline.json");
		Files.writeString(baseline, "{}");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getBaselineFile().set(baseline.toFile());

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		assertThat(task.getBaselineFile().get().getAsFile()).isEqualTo(baseline.toFile());
	}

	@Test
	void externalTrainingCommandPropagatesFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getExternalTrainingCommand().set("oha -n 1000 http://localhost:8080/");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getExternalTrainingCommand().get()).isEqualTo("oha -n 1000 http://localhost:8080/");
	}

	@Test
	void skipConventionPropagatesFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getSkip().set(true);

		MeasureEnergyTask measureTask = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(measureTask.getSkip().get()).isTrue();

		UpdateBaselineTask updateTask = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		assertThat(updateTask.getSkip().get()).isTrue();
	}

	@Test
	void externalTrainingScriptFilePropagatesFromExtension(@TempDir Path tempDir) throws IOException {
		Path script = tempDir.resolve("run.sh");
		Files.writeString(script, "#!/bin/sh\necho test");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getExternalTrainingScriptFile().set(script.toFile());

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getExternalTrainingScriptFile().get().getAsFile()).isEqualTo(script.toFile());
	}

	@Test
	void jvmAndAppArgsPropagateFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getJvmArgs().set(List.of("-Xmx512m", "-XX:+UseSerialGC"));
		ext.getAppArgs().set(List.of("--server.port=9091", "--spring.profiles.active=test"));

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getJvmArgs().get()).containsExactly("-Xmx512m", "-XX:+UseSerialGC");
		assertThat(task.getAppArgs().get()).containsExactly("--server.port=9091", "--spring.profiles.active=test");
	}

	@Test
	void updateBaselineLatestReportFileConventionPropagatesFromExtension(@TempDir Path tempDir) throws IOException {
		Path latestReport = tempDir.resolve("latest-energy-report.json");
		Files.writeString(latestReport, "{}");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getLatestReportFile().set(latestReport.toFile());

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		assertThat(task.getLatestReportFile().get().getAsFile()).isEqualTo(latestReport.toFile());
	}

	@Test
	void updateBaselineFailsWhenExplicitLatestReportFileMissing(@TempDir Path tempDir) {
		Path missingReport = tempDir.resolve("missing-latest-energy-report.json");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		task.getLatestReportFile().set(missingReport.toFile());

		assertThatThrownBy(task::updateBaseline).isInstanceOf(GradleException.class)
			.hasMessageContaining("latestReportFile does not exist");
	}

	@Test
	void resolveJoularCoreBinaryFailsWhenExplicitPathMissing(@TempDir Path tempDir) throws Exception {
		Path missingBinary = tempDir.resolve("missing-joularcore");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getJoularCoreBinaryPath().set(missingBinary.toFile());

		Method resolve = MeasureEnergyTask.class.getDeclaredMethod("resolveJoularCoreBinary");
		resolve.setAccessible(true);

		assertThatThrownBy(() -> resolve.invoke(task)).isInstanceOf(java.lang.reflect.InvocationTargetException.class)
			.cause()
			.isInstanceOf(GradleException.class)
			.hasMessageContaining("joularCoreBinaryPath does not exist");
	}

	@Test
	void buildTrainingConfigFailsWhenExplicitScriptPathMissing(@TempDir Path tempDir) throws Exception {
		Path missingScript = tempDir.resolve("missing-run.sh");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getExternalTrainingScriptFile().set(missingScript.toFile());

		Method buildConfig = MeasureEnergyTask.class.getDeclaredMethod("buildTrainingConfig", String.class);
		buildConfig.setAccessible(true);

		assertThatThrownBy(() -> buildConfig.invoke(task, "http://localhost:8080"))
			.isInstanceOf(java.lang.reflect.InvocationTargetException.class)
			.cause()
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("externalTrainingScriptFile does not exist");
	}

	// ---- MeasureEnergyTask skip ----

	@Test
	void measureEnergySkipsWhenSkipIsTrue() throws Exception {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getSkip().set(true);

		// Should not throw — just logs and returns
		task.measureEnergy();
	}

	@Test
	void measureEnergyFailsWhenJarNotFound(@TempDir Path tempDir) {
		Path missingJar = tempDir.resolve("missing-app.jar");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getSpringBootJar().set(missingJar.toFile());

		assertThatThrownBy(task::measureEnergy).isInstanceOf(GradleException.class)
			.hasMessageContaining("Spring Boot jar not found");
	}

	@Test
	void measureEnergyFailsWhenMeasureDurationIsZero(@TempDir Path tempDir) throws IOException {
		Path fakeJar = tempDir.resolve("app.jar");
		Files.createFile(fakeJar);

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getSpringBootJar().set(fakeJar.toFile());
		task.getMeasureDurationSeconds().set(0);

		assertThatThrownBy(task::measureEnergy).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("measureDurationSeconds must be > 0");
	}

	// ---- UpdateBaselineTask skip ----

	@Test
	void updateBaselineSkipsWhenSkipIsTrue() throws Exception {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		task.getSkip().set(true);

		// Should not throw — just logs and returns
		task.updateBaseline();
	}

	@Test
	void updateBaselineFromLatestReportFile(@TempDir Path tempDir) throws Exception {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app", 42.0)));
		Path latestReport = tempDir.resolve("latest-energy-report.json");
		new BaselineManager().saveBaseline(report, null, null, latestReport);

		Path baselineFile = tempDir.resolve("energy-baseline.json");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		task.getLatestReportFile().set(latestReport.toFile());
		task.getBaselineFile().set(baselineFile.toFile());

		task.updateBaseline();

		assertThat(baselineFile).exists();
		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().report().totalEnergyJoules()).isEqualTo(42.0);
	}

	@Test
	void updateBaselineFromReportOutputDir(@TempDir Path tempDir) throws Exception {
		Path reportDir = tempDir.resolve("greener-reports");
		Path toolDir = reportDir.resolve("oha");
		Files.createDirectories(toolDir);

		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app", 55.0)));
		new BaselineManager().saveBaseline(report, null, null, toolDir.resolve("latest-energy-report.json"));

		Path baselineFile = tempDir.resolve("energy-baseline.json");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		task.getReportOutputDir().set(reportDir.toFile());
		task.getBaselineFile().set(baselineFile.toFile());

		task.updateBaseline();

		assertThat(baselineFile).exists();
		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().report().totalEnergyJoules()).isEqualTo(55.0);
	}

	@Test
	void updateBaselineWithCommitShaAndBranch(@TempDir Path tempDir) throws Exception {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app", 30.0)));
		Path baselineFile = tempDir.resolve("energy-baseline.json");
		new BaselineManager().saveBaseline(report, null, null, baselineFile);

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		task.getBaselineFile().set(baselineFile.toFile());
		task.getCommitSha().set("abc123def456");
		task.getBranch().set("main");

		task.updateBaseline();

		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().commitSha()).isEqualTo("abc123def456");
		assertThat(baseline.get().branch()).isEqualTo("main");
	}

	@Test
	void updateBaselineWarnsWhenNoReportAvailable(@TempDir Path tempDir) throws Exception {
		Path baselineFile = tempDir.resolve("nonexistent-baseline.json");
		Path reportDir = tempDir.resolve("nonexistent-reports");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		UpdateBaselineTask task = (UpdateBaselineTask) project.getTasks().getByName("updateEnergyBaseline");
		task.getBaselineFile().set(baselineFile.toFile());
		task.getReportOutputDir().set(reportDir.toFile());

		// Should not throw — just warns and returns
		task.updateBaseline();

		assertThat(baselineFile).doesNotExist();
	}

	// ---- MeasureEnergyTask private method coverage ----

	@Test
	void resolveToolNameDelegatesToPluginDefaults(@TempDir Path tempDir) throws Exception {
		Path scriptDir = tempDir.resolve("oha");
		Files.createDirectories(scriptDir);
		Path scriptFile = scriptDir.resolve("run.sh");
		Files.writeString(scriptFile, "#!/bin/sh\necho test");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getExternalTrainingScriptFile().set(scriptFile.toFile());

		Method resolveToolName = MeasureEnergyTask.class.getDeclaredMethod("resolveToolName");
		resolveToolName.setAccessible(true);
		String toolName = (String) resolveToolName.invoke(task);

		assertThat(toolName).isEqualTo("oha");
	}

	@Test
	void resolveToolNameFromExternalCommand() throws Exception {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getExternalTrainingCommand().set("wrk -t2 -c10 http://localhost:8080");

		Method resolveToolName = MeasureEnergyTask.class.getDeclaredMethod("resolveToolName");
		resolveToolName.setAccessible(true);
		String toolName = (String) resolveToolName.invoke(task);

		assertThat(toolName).isEqualTo("wrk");
	}

	@Test
	void buildTrainingConfigWithExternalCommand() throws Exception {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getExternalTrainingCommand().set("oha http://localhost:8080");

		Method buildConfig = MeasureEnergyTask.class.getDeclaredMethod("buildTrainingConfig", String.class);
		buildConfig.setAccessible(true);
		Object config = buildConfig.invoke(task, "http://localhost:8080");

		assertThat(config).isNotNull();
	}

	@Test
	void autoDetectSpringBootJarFailsWhenNoLibsDir() throws Exception {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");

		Method autoDetect = MeasureEnergyTask.class.getDeclaredMethod("autoDetectSpringBootJar");
		autoDetect.setAccessible(true);

		assertThatThrownBy(() -> autoDetect.invoke(task))
			.isInstanceOf(java.lang.reflect.InvocationTargetException.class)
			.cause()
			.isInstanceOf(GradleException.class);
	}

	@Test
	void topNConventionPropagatesFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getTopN().set(50);

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getTopN().get()).isEqualTo(50);
	}

	@Test
	void measureEnergyFailsEarlyWhenExternalScriptMissing(@TempDir Path tempDir) throws IOException {
		Path fakeJar = tempDir.resolve("app.jar");
		Files.createFile(fakeJar);
		Path missingScript = tempDir.resolve("missing-run.sh");

		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		task.getSpringBootJar().set(fakeJar.toFile());
		task.getExternalTrainingScriptFile().set(missingScript.toFile());

		assertThatThrownBy(task::measureEnergy).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("externalTrainingScriptFile does not exist");
	}

	@Test
	void autoUpdateBaselineConventionPropagatesFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getAutoUpdateBaseline().set(true);

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getAutoUpdateBaseline().get()).isTrue();
	}

	@Test
	void timestampReportsConventionPropagatesFromExtension() {
		Project project = ProjectBuilder.builder().build();
		project.getPlugins().apply("com.patbaumgartner.greener-spring-boot");

		GreenerExtension ext = project.getExtensions().getByType(GreenerExtension.class);
		ext.getTimestampReports().set(true);

		MeasureEnergyTask task = (MeasureEnergyTask) project.getTasks().getByName("measureEnergy");
		assertThat(task.getTimestampReports().get()).isTrue();
	}

}
