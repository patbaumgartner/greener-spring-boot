package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.downloader.JoularCoreDownloader;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.MethodLevelReports;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import com.patbaumgartner.greener.core.orchestrator.MeasurementOrchestrator;
import com.patbaumgartner.greener.core.runner.ApplicationRunner;
import com.patbaumgartner.greener.core.runner.JoularCoreRunner;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Gradle task that measures the energy consumption of a Spring Boot application using
 * <a href="https://www.noureddine.org/research/joular/joularcore">Joular Core</a>.
 *
 * <p>
 * This task mirrors the Maven {@code greener:measure} goal.
 */

@DisableCachingByDefault(because = "Energy measurement depends on live system state")
public abstract class MeasureEnergyTask extends DefaultTask {

	/**
	 * Gets the project layout.
	 * @return the project layout
	 */
	@Inject
	protected abstract ProjectLayout getLayout();

	/**
	 * Gets the provider factory.
	 * @return the provider factory
	 */
	@Inject
	protected abstract ProviderFactory getProviders();

	/**
	 * Path to the executable Spring Boot fat-jar. When omitted the task auto-detects a
	 * single jar in {@code build/libs/}.
	 * @return the Spring Boot jar property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getSpringBootJar();

	/**
	 * Additional JVM arguments passed when starting the Spring Boot process.
	 * @return the JVM arguments property
	 */
	@Input
	@org.gradle.api.tasks.Optional
	public abstract ListProperty<String> getJvmArgs();

	/**
	 * Additional application arguments passed to the Spring Boot process.
	 * @return the application arguments property
	 */
	@Input
	@org.gradle.api.tasks.Optional
	public abstract ListProperty<String> getAppArgs();

	/**
	 * Full path to the Joular Core binary (optional; auto-downloaded when absent).
	 * @return the Joular Core binary path property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getJoularCoreBinaryPath();

	/**
	 * Joular Core release version to download when {@link #getJoularCoreBinaryPath()} is
	 * not set. See <a href="https://github.com/joular/joularcore/releases">Joular Core
	 * releases</a> for available versions.
	 * @return the Joular Core version property
	 */
	@Input
	public abstract Property<String> getJoularCoreVersion();

	/**
	 * Hardware component to monitor: {@code cpu}, {@code gpu}, or {@code all}.
	 * @return the Joular Core component property
	 */
	@Input
	public abstract Property<String> getJoularCoreComponent();

	/**
	 * Path to the JoularJX Java agent jar. When set, the agent is attached to the Spring
	 * Boot JVM via {@code -javaagent:} for per-method energy monitoring.
	 * @return the JoularJX agent path property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getJoularJxAgentPath();

	/**
	 * Path to the JoularJX {@code config.properties} file. Used only when
	 * {@link #getJoularJxAgentPath()} is also set.
	 * @return the JoularJX config path property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getJoularJxConfigPath();

	/**
	 * Base URL of the Spring Boot application.
	 * @return the base URL property
	 */
	@Input
	public abstract Property<String> getBaseUrl();

	/**
	 * Number of HTTP requests per second (passed as {@code RPS} environment variable).
	 * @return the requests per second property
	 */
	@Input
	public abstract Property<Integer> getRequestsPerSecond();

	/**
	 * Optional external command used as the training workload (e.g.
	 * {@code k6 run script.js}). The {@code APP_URL} environment variable is set to
	 * {@link #getBaseUrl()}.
	 * @return the external training command property
	 */
	@Input
	@org.gradle.api.tasks.Optional
	public abstract Property<String> getExternalTrainingCommand();

	/**
	 * Path to an external shell script file used as the training workload. Takes
	 * precedence over {@link #getExternalTrainingCommand()} when set.
	 *
	 * <p>
	 * Available environment variables in the script: {@code APP_URL}, {@code APP_HOST},
	 * {@code APP_PORT}, {@code WARMUP_SECONDS}, {@code MEASURE_SECONDS},
	 * {@code TOTAL_SECONDS}, {@code RPS}.
	 *
	 * <p>
	 * See {@code examples/workloads/} for wrk, wrk2, oha, and Gatling examples.
	 * @return the external training script file property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getExternalTrainingScriptFile();

	/**
	 * Enable Joular Core VM mode.
	 *
	 * <p>
	 * In VM mode Joular Core cannot read RAPL counters directly and instead reads the
	 * VM's total power from a file written by the host - see
	 * {@link #getVmPowerFilePath()}. The host must write the VM's instantaneous power
	 * (Watts) to that file once per second.
	 * @return the VM mode property
	 */
	@Input
	public abstract Property<Boolean> getVmMode();

	/**
	 * Path to the VM power file that Joular Core reads when {@link #getVmMode()} is
	 * {@code true}. The file must contain a single floating-point number (e.g.
	 * {@code 45.2}).
	 * @return the VM power file path property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getVmPowerFilePath();

	/**
	 * Warmup duration in seconds. The application runs under load during warmup so the
	 * JIT compiler warms up, but energy data from this phase is discarded.
	 * @return the warmup duration property
	 */
	@Input
	public abstract Property<Integer> getWarmupDurationSeconds();

	/**
	 * Duration in seconds of the actual measurement window after warmup.
	 * @return the measure duration property
	 */
	@Input
	public abstract Property<Integer> getMeasureDurationSeconds();

	/**
	 * Seconds to wait for the application health endpoint before aborting.
	 * @return the startup timeout property
	 */
	@Input
	public abstract Property<Integer> getStartupTimeoutSeconds();

	/**
	 * Health-check path used to detect application readiness.
	 * @return the health check path property
	 */
	@Input
	public abstract Property<String> getHealthCheckPath();

	/**
	 * Path to the JSON baseline file.
	 * @return the baseline file property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getBaselineFile();

	/**
	 * Maximum allowed percentage increase in total energy before the build is failed. For
	 * example {@code 10} means a 10 % regression is tolerated.
	 * @return the threshold property
	 */
	@Input
	public abstract Property<Double> getThreshold();

	/**
	 * When {@code true}, the build is failed if energy consumption regressed beyond
	 * {@link #getThreshold()}.
	 * @return the fail on regression property
	 */
	@Input
	public abstract Property<Boolean> getFailOnRegression();

	/**
	 * Directory where the HTML report is written.
	 * @return the report output directory property
	 */
	@OutputDirectory
	@org.gradle.api.tasks.Optional
	public abstract DirectoryProperty getReportOutputDir();

	/**
	 * When {@code true}, the measurement result is automatically promoted to the baseline
	 * after a successful run. This eliminates the need to call
	 * {@code updateEnergyBaseline} separately.
	 * @return the auto-update baseline property
	 */
	@Input
	public abstract Property<Boolean> getAutoUpdateBaseline();

	/**
	 * Git commit SHA to record in the baseline when auto-updating.
	 * @return the commit SHA property
	 */
	@Input
	@org.gradle.api.tasks.Optional
	public abstract Property<String> getCommitSha();

	/**
	 * Branch name to record in the baseline when auto-updating.
	 * @return the branch property
	 */
	@Input
	@org.gradle.api.tasks.Optional
	public abstract Property<String> getBranch();

	/**
	 * When {@code true}, the report output directory gets a timestamp suffix (e.g.
	 * {@code greener-reports-20260404-153012}) and a {@code latest} symlink is created
	 * pointing to the most recent run.
	 * @return the timestamp reports property
	 */
	@Input
	public abstract Property<Boolean> getTimestampReports();

	/**
	 * When {@code true}, the task execution is skipped entirely.
	 * @return the skip property
	 */
	@Input
	public abstract Property<Boolean> getSkip();

	/**
	 * Runs the energy measurement workflow.
	 * @throws IOException if an I/O error occurs during measurement
	 * @throws InterruptedException if the measurement is interrupted
	 */
	@TaskAction
	public void measureEnergy() throws IOException, InterruptedException {
		if (getSkip().get()) {
			getLogger().lifecycle("[greener] measureEnergy skipped (skip=true)");
			return;
		}

		File springBootJarFile = resolveSpringBootJar();

		PluginDefaults.validateMeasureDuration(getMeasureDurationSeconds().get());

		// Resolve Joular Core binary
		Path joularCoreBinary = resolveJoularCoreBinary();

		MeasurementOrchestrator orchestrator = new MeasurementOrchestrator(msg -> getLogger().lifecycle(msg));

		int warmup = getWarmupDurationSeconds().get();
		int measure = getMeasureDurationSeconds().get();
		String baseUrl = getBaseUrl().get();

		Path reportDir = resolveEffectiveReportDir();
		String toolName = resolveToolName();
		Path runDir = reportDir.resolve(toolName);
		Path workingDir = runDir.resolve("work");

		// Start application
		ApplicationRunner appRunner = new ApplicationRunner();
		Process appProcess = startApplication(appRunner, springBootJarFile, joularCoreBinary, workingDir);

		Path outputCsv = workingDir.resolve("joularcore-output.csv");
		WorkloadStats workloadStats;
		try {
			waitForApplicationReady(appRunner, appProcess, baseUrl);

			JoularCoreConfig joularCoreConfig = createJoularCoreConfig(joularCoreBinary, appProcess.pid(), outputCsv);
			JoularCoreRunner joularCoreRunner = new JoularCoreRunner();
			startJoularCore(joularCoreRunner, joularCoreConfig, appProcess.pid());

			try {
				workloadStats = orchestrator.executeWorkloads(joularCoreRunner, joularCoreConfig, outputCsv,
						() -> buildTrainingConfig(baseUrl), warmup, measure);
			}
			finally {
				joularCoreRunner.stop();
			}

		}
		finally {
			if (getJoularJxAgentPath().isPresent()) {
				appRunner.requestGracefulShutdown(baseUrl);
			}
			appRunner.stop(appProcess);
		}

		// Process results, reports, and baseline
		String appId = springBootJarFile.getName().replace(".jar", "");
		EnergyReport report = orchestrator.processResults(outputCsv, measure, appId);

		Path baselinePath = resolveBaselinePath();
		ComparisonResult comparison = orchestrator.processBaselineComparison(report, baselinePath, runDir,
				getThreshold().get(), getAutoUpdateBaseline().get(), getCommitSha().getOrNull(),
				getBranch().getOrNull());

		// Read JoularJX method-level results (if available)
		MethodLevelReports methodLevelReports = getJoularJxAgentPath().isPresent()
				? orchestrator.readJoularJxMethodLevelReports(workingDir, measure) : null;

		Path htmlReport = orchestrator.generateFinalReports(report, comparison, workloadStats, toolName, reportDir,
				runDir, getVmMode().get(), methodLevelReports);

		// Create latest symlink for timestamped reports
		if (getTimestampReports().get()) {
			PluginDefaults.createLatestLink(reportDir, resolveReportDir().getFileName() + "-latest");
		}

		if (getFailOnRegression().get() && comparison.isFailed()) {
			throw new GradleException(String.format(
					"Energy regression detected: %.2f%% increase exceeds threshold of %.1f%%. " + "See report at: %s",
					comparison.totalDeltaPercent(), getThreshold().get(), htmlReport));
		}
	}

	private File resolveSpringBootJar() {
		File springBootJarFile;
		if (getSpringBootJar().isPresent()) {
			springBootJarFile = getSpringBootJar().get().getAsFile();
		}
		else {
			springBootJarFile = autoDetectSpringBootJar();
		}
		if (!springBootJarFile.exists()) {
			throw new GradleException(
					"Spring Boot jar not found: " + springBootJarFile + ". Build the jar first (e.g. with 'bootJar').");
		}
		return springBootJarFile;
	}

	private Path resolveEffectiveReportDir() {
		Path reportDir = resolveReportDir();
		if (getTimestampReports().get()) {
			reportDir = PluginDefaults.buildTimestampedDir(reportDir);
		}
		return reportDir;
	}

	private Process startApplication(ApplicationRunner appRunner, File springBootJarFile, Path joularCoreBinary,
			Path workingDir) throws IOException {
		getLogger().lifecycle("[greener] Starting application: " + springBootJarFile.getName());
		List<String> effectiveAppArgs = PluginDefaults.buildEffectiveAppArgs(getAppArgs().getOrNull(),
				getJoularJxAgentPath().isPresent(), getBaseUrl().get());
		List<String> effectiveJvmArgs = getJvmArgs().getOrNull();
		Path joularJxJar = getJoularJxAgentPath().isPresent() ? getJoularJxAgentPath().get().getAsFile().toPath()
				: null;
		Path joularJxConfig = getJoularJxConfigPath().isPresent() ? getJoularJxConfigPath().get().getAsFile().toPath()
				: null;
		joularJxConfig = PluginDefaults.ensureJoularCoreParameters(joularJxConfig, joularCoreBinary, workingDir);
		Process appProcess = appRunner.start(springBootJarFile.toPath(), joularJxJar, joularJxConfig, workingDir,
				effectiveJvmArgs, effectiveAppArgs);
		getLogger().lifecycle("[greener] Application PID: " + appProcess.pid());
		return appProcess;
	}

	private void waitForApplicationReady(ApplicationRunner appRunner, Process appProcess, String baseUrl)
			throws IOException, InterruptedException {
		getLogger().lifecycle("[greener] Waiting for health check ...");
		appRunner.waitForStartup(appProcess, baseUrl, getHealthCheckPath().get(), getStartupTimeoutSeconds().get());
		getLogger().lifecycle("[greener] Application is ready");
	}

	private JoularCoreConfig createJoularCoreConfig(Path joularCoreBinary, long pid, Path outputCsv) {
		File vmPowerFile = getVmPowerFilePath().isPresent() ? getVmPowerFilePath().get().getAsFile() : null;
		return new JoularCoreConfig().binaryPath(joularCoreBinary)
			.pid(pid)
			.component(getJoularCoreComponent().get())
			.outputCsvPath(outputCsv)
			.silent(true)
			.vmMode(getVmMode().get())
			.vmPowerFilePath(vmPowerFile != null ? vmPowerFile.toPath() : null);
	}

	private void startJoularCore(JoularCoreRunner runner, JoularCoreConfig config, long pid) throws IOException {
		getLogger().lifecycle("[greener] Starting Joular Core (PID " + pid + ")");
		runner.start(config);
	}

	/**
	 * Derives a tool name from the configured training script or command.
	 */
	private String resolveToolName() {
		File scriptFile = getExternalTrainingScriptFile().isPresent()
				? getExternalTrainingScriptFile().get().getAsFile() : null;
		String extCmd = getExternalTrainingCommand().getOrNull();
		return PluginDefaults.resolveToolName(scriptFile, extCmd);
	}

	private Path resolveJoularCoreBinary() throws IOException, InterruptedException {
		RegularFileProperty binaryProp = getJoularCoreBinaryPath();
		if (binaryProp.isPresent()) {
			File f = binaryProp.get().getAsFile();
			if (!f.exists()) {
				throw new GradleException("Configured joularCoreBinaryPath does not exist: " + f
						+ ". Remove joularCoreBinaryPath to enable auto-download, or fix the path.");
			}
			getLogger().lifecycle("[greener] Using Joular Core binary: " + f);
			return f.toPath();
		}
		String version = getJoularCoreVersion().get();
		getLogger().lifecycle("[greener] Downloading Joular Core " + version + " ...");
		return new JoularCoreDownloader().download(version, JoularCoreDownloader.defaultCacheDir());
	}

	private TrainingConfig buildTrainingConfig(String baseUrl) {
		TrainingConfig config = new TrainingConfig().baseUrl(baseUrl).requestsPerSecond(getRequestsPerSecond().get());

		if (getExternalTrainingScriptFile().isPresent()) {
			File scriptFile = getExternalTrainingScriptFile().get().getAsFile();
			PluginDefaults.validateExternalScript(scriptFile);
			config.externalScriptFile(scriptFile.getAbsolutePath());
		}
		else {
			String extCmd = getExternalTrainingCommand().getOrNull();
			if (extCmd != null && !extCmd.isBlank()) {
				config.externalCommand(extCmd);
			}
		}

		return config;
	}

	private Path resolveBaselinePath() {
		if (getBaselineFile().isPresent()) {
			return getBaselineFile().get().getAsFile().toPath();
		}
		return getLayout().getProjectDirectory().file("energy-baseline.json").getAsFile().toPath();
	}

	private Path resolveReportDir() {
		if (getReportOutputDir().isPresent()) {
			return getReportOutputDir().get().getAsFile().toPath();
		}
		return getLayout().getBuildDirectory().dir("greener-reports").get().getAsFile().toPath();
	}

	/**
	 * Auto-detects the Spring Boot fat-jar in {@code build/libs/}. Looks for a single
	 * executable jar, excluding sources, javadoc, and test jars.
	 */
	private File autoDetectSpringBootJar() {
		Path libsDir = getLayout().getBuildDirectory().dir("libs").get().getAsFile().toPath();

		try {
			Optional<File> jar = PluginDefaults.autoDetectJar(libsDir, "-plain.jar");
			if (jar.isEmpty()) {
				throw new GradleException("springBootJar not configured and build/libs/ not found: " + libsDir
						+ ". Set springBootJar explicitly or run 'bootJar' first.");
			}
			getLogger().lifecycle("[greener] Auto-detected Spring Boot jar: " + jar.get());
			return jar.get();
		}
		catch (IllegalStateException e) {
			throw new GradleException(e.getMessage() + ". Run 'bootJar' first or set springBootJar explicitly.", e);
		}
	}

}
