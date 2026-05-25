package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import com.patbaumgartner.greener.core.model.RegressionMetric;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.ProviderFactory;

/**
 * Gradle plugin entry point for <strong>greener-spring-boot</strong>.
 *
 * <p>
 * Registers the {@link GreenerExtension} DSL block and the three tasks:
 * <ul>
 * <li>{@code measureEnergy} — runs the Spring Boot application under
 * <a href="https://github.com/joular/joularcore">Joular Core</a> and compares the
 * measured energy against a baseline.</li>
 * <li>{@code updateEnergyBaseline} — promotes the most recent measurement result as the
 * new baseline.</li>
 * <li>{@code energyDoctor} — runs preflight environment checks and prints a PASS / WARN /
 * FAIL report with actionable hints.</li>
 * </ul>
 *
 * <h2>Minimal {@code build.gradle.kts} configuration</h2>
 *
 * <p>
 * An external workload tool is <b>required</b> &mdash; set either
 * {@code externalTrainingCommand} (inline) or {@code externalTrainingScriptFile} (path to
 * a shell script). The plugin will fail at runtime if neither is configured.
 * </p>
 *
 * <pre>{@code
 * plugins {
 *     id("com.patbaumgartner.greener-spring-boot") version "<version>"
 * }
 *
 * greener {
 *     // REQUIRED – one of externalTrainingCommand / externalTrainingScriptFile
 *     externalTrainingCommand.set("oha -n 500 -c 10 \${APP_URL}/actuator/health")
 * }
 * }</pre>
 */
public class GreenerPlugin implements Plugin<Project> {

	private static final String EXTENSION_NAME = "greener";

	private static final String TASK_GROUP = "greener";

	private static final String MEASURE_ENERGY_TASK_NAME = "measureEnergy";

	private static final String UPDATE_BASELINE_TASK_NAME = "updateEnergyBaseline";

	private static final String DOCTOR_TASK_NAME = "energyDoctor";

	@Override
	public void apply(Project project) {
		GreenerExtension extension = project.getExtensions().create(EXTENSION_NAME, GreenerExtension.class);

		// Sensible defaults for all optional properties
		extension.getJoularCoreVersion().convention(JoularCoreConfig.DEFAULT_VERSION);
		extension.getJoularCoreComponent().convention("cpu");
		extension.getVmMode().convention(false);
		extension.getBaseUrl().convention("http://localhost:8080");
		extension.getRequestsPerSecond().convention(5);
		extension.getWarmupDurationSeconds().convention(30);
		extension.getMeasureDurationSeconds().convention(60);
		extension.getStartupTimeoutSeconds().convention(120);
		extension.getHealthCheckPath().convention("/actuator/health/readiness");
		extension.getThreshold().convention(10.0);
		extension.getFailOnRegression().convention(false);
		extension.getAutoUpdateBaseline().convention(false);
		extension.getTimestampReports().convention(false);
		extension.getSkip().convention(false);
		extension.getTopN().convention(20);
		extension.getIterations().convention(5);
		extension.getRegressionMetric().convention(RegressionMetric.ENERGY_PER_REQUEST);
		extension.getIdleProbeSeconds().convention(0);

		// Apply useful project-level conventions down to the extension so tasks don't
		// need getProject()
		extension.getBaselineFile().convention(project.getLayout().getProjectDirectory().file("energy-baseline.json"));
		extension.getReportOutputDir().convention(project.getLayout().getBuildDirectory().dir("greener-reports"));

		// Default commitSha/branch from environment variables (same as Maven's
		// ${env.GITHUB_SHA})
		ProviderFactory providers = project.getProviders();
		extension.getCommitSha().convention(providers.environmentVariable("GITHUB_SHA"));
		extension.getBranch().convention(providers.environmentVariable("GITHUB_REF_NAME"));

		configureMeasureEnergyTask(project, extension);
		configureUpdateBaselineTask(project, extension);
		configureDoctorTask(project, extension);
	}

	private void configureDoctorTask(Project project, GreenerExtension extension) {
		project.getTasks().register(DOCTOR_TASK_NAME, EnergyDoctorTask.class, task -> {
			task.setGroup(TASK_GROUP);
			task.setDescription("Runs preflight environment checks (RAPL, msr, Joular Core, Joular Code Java, "
					+ "workload tool, jar auto-detect) and reports actionable diagnostics.");
			task.getJoularCoreBinaryPath().convention(extension.getJoularCoreBinaryPath());
			task.getJoularCodeJavaAgentPath().convention(extension.getJoularCodeJavaAgentPath());
			task.getFailOnError().convention(true);
			task.getProjectDir().convention(project.getProjectDir());
		});
	}

	private void configureMeasureEnergyTask(Project project, GreenerExtension extension) {
		project.getTasks().register(MEASURE_ENERGY_TASK_NAME, MeasureEnergyTask.class, task -> {
			task.setGroup(TASK_GROUP);
			task.setDescription("Measures energy consumption of the Spring Boot application using Joular Core "
					+ "and compares against the stored baseline.");
			task.getSpringBootJar().convention(extension.getSpringBootJar());
			task.getJvmArgs().convention(extension.getJvmArgs());
			task.getAppArgs().convention(extension.getAppArgs());
			task.getJoularCoreBinaryPath().convention(extension.getJoularCoreBinaryPath());
			task.getJoularCoreVersion().convention(extension.getJoularCoreVersion());
			task.getJoularCoreComponent().convention(extension.getJoularCoreComponent());
			task.getJoularCodeJavaAgentPath().convention(extension.getJoularCodeJavaAgentPath());
			task.getJoularCodeJavaConfigPath().convention(extension.getJoularCodeJavaConfigPath());
			task.getBaseUrl().convention(extension.getBaseUrl());
			task.getRequestsPerSecond().convention(extension.getRequestsPerSecond());
			task.getExternalTrainingCommand().convention(extension.getExternalTrainingCommand());
			task.getExternalTrainingScriptFile().convention(extension.getExternalTrainingScriptFile());
			task.getVmMode().convention(extension.getVmMode());
			task.getVmPowerFilePath().convention(extension.getVmPowerFilePath());
			task.getWarmupDurationSeconds().convention(extension.getWarmupDurationSeconds());
			task.getMeasureDurationSeconds().convention(extension.getMeasureDurationSeconds());
			task.getStartupTimeoutSeconds().convention(extension.getStartupTimeoutSeconds());
			task.getHealthCheckPath().convention(extension.getHealthCheckPath());
			task.getBaselineFile().convention(extension.getBaselineFile());
			task.getThreshold().convention(extension.getThreshold());
			task.getFailOnRegression().convention(extension.getFailOnRegression());
			task.getReportOutputDir().convention(extension.getReportOutputDir());
			task.getAutoUpdateBaseline().convention(extension.getAutoUpdateBaseline());
			task.getCommitSha().convention(extension.getCommitSha());
			task.getBranch().convention(extension.getBranch());
			task.getTimestampReports().convention(extension.getTimestampReports());
			task.getSkip().convention(extension.getSkip());
			task.getTopN().convention(extension.getTopN());
			task.getIterations().convention(extension.getIterations());
			task.getRegressionMetric().convention(extension.getRegressionMetric());
			task.getIdleProbeSeconds().convention(extension.getIdleProbeSeconds());
		});
	}

	private void configureUpdateBaselineTask(Project project, GreenerExtension extension) {
		project.getTasks().register(UPDATE_BASELINE_TASK_NAME, UpdateBaselineTask.class, task -> {
			task.setGroup(TASK_GROUP);
			task.setDescription(
					"Promotes the most recent energy measurement as the new baseline for " + "future comparisons.");
			task.getBaselineFile().convention(extension.getBaselineFile());
			task.getLatestReportFile().convention(extension.getLatestReportFile());
			task.getReportOutputDir().convention(extension.getReportOutputDir());
			task.getCommitSha().convention(extension.getCommitSha());
			task.getBranch().convention(extension.getBranch());
			task.getSkip().convention(extension.getSkip());
		});
	}

}
