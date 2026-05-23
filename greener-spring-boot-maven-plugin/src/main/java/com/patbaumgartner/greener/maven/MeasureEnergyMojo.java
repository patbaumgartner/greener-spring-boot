package com.patbaumgartner.greener.maven;

import com.patbaumgartner.greener.core.config.AppArgsBuilder;
import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.downloader.JoularCoreDownloader;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.MeasurementConfig;
import com.patbaumgartner.greener.core.model.MeasurementResult;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import com.patbaumgartner.greener.core.orchestrator.MeasurementOrchestrator;
import com.patbaumgartner.greener.core.runner.ApplicationRunner;
import com.patbaumgartner.greener.core.runner.JoularCoreRunner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Measures the energy consumption of a Spring Boot application using
 * <a href="https://github.com/joular/joularcore">Joular Core</a>, compares the result
 * against a stored baseline, and generates a report.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li>Resolve (or auto-download) the Joular Core binary.</li>
 * <li>Start the Spring Boot fat-jar.</li>
 * <li>Wait for the application health-check endpoint.</li>
 * <li>Start Joular Core monitoring (by PID).</li>
 * <li>Run the configurable training workload (warmup + measurement).</li>
 * <li>Stop Joular Core.</li>
 * <li>Stop the Spring Boot application.</li>
 * <li>Read Joular Core CSV output and build an
 * {@link com.patbaumgartner.greener.core.model.EnergyReport EnergyReport}.</li>
 * <li>Compare against baseline; generate console + HTML reports.</li>
 * <li>Optionally fail the build if energy regressed beyond {@code threshold}.</li>
 * </ol>
 *
 * <h2>Minimal configuration example</h2>
 *
 * <p>
 * An external workload tool is <b>required</b> &mdash; set either
 * {@code externalTrainingCommand} (inline) or {@code externalTrainingScriptFile} (path to
 * a shell script). The plugin will fail at runtime if neither is configured.
 * </p>
 *
 * <pre>{@code
 * <plugin>
 *   <groupId>com.patbaumgartner</groupId>
 *   <artifactId>greener-spring-boot-maven-plugin</artifactId>
 *   <version>0.2.0-SNAPSHOT</version>
 *   <configuration>
 *     <!-- REQUIRED – one of externalTrainingCommand / externalTrainingScriptFile -->
 *     <externalTrainingCommand>oha -n 500 -c 10 ${APP_URL}/actuator/health</externalTrainingCommand>
 *   </configuration>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "measure", defaultPhase = LifecyclePhase.INTEGRATION_TEST, threadSafe = false)
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class MeasureEnergyMojo extends AbstractMojo {

	// ---- Application ----

	/**
	 * Path to the executable Spring Boot fat-jar to measure. When not set, the plugin
	 * auto-detects the fat-jar in {@code ${project.build.directory}}.
	 */
	@Parameter(property = "greener.springBootJar")
	private File springBootJar;

	/** Build output directory used for auto-detecting the Spring Boot fat-jar. */
	@Parameter(defaultValue = "${project.build.directory}", readonly = true)
	private File buildDirectory;

	/**
	 * Additional JVM arguments passed when starting the Spring Boot process.
	 *
	 * <p>
	 * Example:
	 *
	 * <pre>{@code
	 * <jvmArgs>
	 *   <jvmArg>-Xmx512m</jvmArg>
	 *   <jvmArg>-Duser.timezone=UTC</jvmArg>
	 * </jvmArgs>
	 * }</pre>
	 */
	@Parameter
	private List<String> jvmArgs;

	/**
	 * Additional application arguments passed to the Spring Boot process.
	 *
	 * <p>
	 * Example:
	 *
	 * <pre>{@code
	 * <appArgs>
	 *   <appArg>--server.port=8081</appArg>
	 *   <appArg>--spring.profiles.active=perf</appArg>
	 * </appArgs>
	 * }</pre>
	 *
	 * <p>
	 * The plugin always appends {@code --management.endpoint.health.probes.enabled=true}
	 * so readiness checks can work out of the box.
	 */
	@Parameter
	private List<String> appArgs;

	// ---- Joular Core ----

	/**
	 * Full path to the Joular Core binary. When left blank the plugin auto-downloads the
	 * correct binary for the current platform and caches it in
	 * {@code ~/.greener/cache/joularcore/}.
	 */
	@Parameter(property = "greener.joularCoreBinaryPath")
	private File joularCoreBinaryPath;

	/**
	 * Joular Core version to download when {@link #joularCoreBinaryPath} is not set. See
	 * https://github.com/joular/joularcore/releases for available versions.
	 */
	@Parameter(property = "greener.joularCoreVersion", defaultValue = JoularCoreConfig.DEFAULT_VERSION)
	private String joularCoreVersion;

	/**
	 * Hardware component monitored by Joular Core. Valid values: {@code cpu},
	 * {@code gpu}, {@code all}.
	 */
	@Parameter(property = "greener.joularCoreComponent", defaultValue = "cpu")
	private String joularCoreComponent;

	// ---- Joular Code Java (optional method-level monitoring) ----

	/**
	 * Path to the Joular Code Java agent jar. When set, the agent is attached to the
	 * Spring Boot JVM via {@code -javaagent:} for per-method energy monitoring.
	 */
	@Parameter(property = "greener.joularCodeJavaAgentPath")
	private File joularCodeJavaAgentPath;

	/**
	 * Path to the Joular Code Java {@code joularcodejava.properties} file. Used only when
	 * {@link #joularCodeJavaAgentPath} is also set.
	 */
	@Parameter(property = "greener.joularCodeJavaConfigPath")
	private File joularCodeJavaConfigPath;

	// ---- Training workload ----

	/** Base URL of the Spring Boot application. */
	@Parameter(property = "greener.baseUrl", defaultValue = "http://localhost:8080")
	private String baseUrl;

	/**
	 * Number of HTTP requests per second (passed as {@code RPS} environment variable).
	 */
	@Parameter(property = "greener.requestsPerSecond", defaultValue = "5")
	private int requestsPerSecond;

	/**
	 * Optional external command used as the training workload (e.g.
	 * {@code k6 run script.js}). The {@code APP_URL} environment variable is set to
	 * {@link #baseUrl}.
	 */
	@Parameter(property = "greener.externalTrainingCommand")
	private String externalTrainingCommand;

	/**
	 * Path to an external shell script file used as the training workload. Takes
	 * precedence over {@link #externalTrainingCommand} when set.
	 *
	 * <p>
	 * Available environment variables in the script: {@code APP_URL}, {@code APP_HOST},
	 * {@code APP_PORT}, {@code WARMUP_SECONDS}, {@code MEASURE_SECONDS},
	 * {@code TOTAL_SECONDS}, {@code RPS}.
	 *
	 * <p>
	 * Examples: {@code examples/workloads/oha/run.sh},
	 * {@code examples/workloads/wrk/run.sh}, {@code examples/workloads/gatling/run.sh}
	 */
	@Parameter(property = "greener.externalTrainingScriptFile")
	private File externalTrainingScriptFile;

	// ---- VM mode (for virtualised environments) ----

	/**
	 * Enable Joular Core VM mode.
	 *
	 * <p>
	 * In VM mode Joular Core cannot read RAPL counters directly and instead reads the
	 * VM's total power from a file written by the host — see {@link #vmPowerFilePath}.
	 * This flag passes {@code --vm} to the joularcore binary.
	 *
	 * <h2>Host-side setup</h2> The host must write the VM's instantaneous power (Watts)
	 * to {@link #vmPowerFilePath} once per second. A mock script is included for CI
	 * pipelines; see {@code .github/workflows/energy-baseline.yml} for an example.
	 */
	@Parameter(property = "greener.vmMode", defaultValue = "false")
	private boolean vmMode;

	/**
	 * Path to the VM power file that Joular Core reads when {@link #vmMode} is
	 * {@code true}. The file must contain a single floating-point number (e.g.
	 * {@code 45.2}).
	 */
	@Parameter(property = "greener.vmPowerFilePath")
	private File vmPowerFilePath;

	/**
	 * Warmup duration in seconds. The application runs under load during warmup so the
	 * JIT compiler warms up, but energy data from this phase is discarded.
	 */
	@Parameter(property = "greener.warmupDurationSeconds", defaultValue = "30")
	private int warmupDurationSeconds;

	/** Duration in seconds of the actual measurement window after warmup. */
	@Parameter(property = "greener.measureDurationSeconds", defaultValue = "60")
	private int measureDurationSeconds;

	/** Seconds to wait for the application health endpoint before aborting. */
	@Parameter(property = "greener.startupTimeoutSeconds", defaultValue = "120")
	private int startupTimeoutSeconds;

	/** Health-check path used to detect when the application is ready. */
	@Parameter(property = "greener.healthCheckPath", defaultValue = "/actuator/health/readiness")
	private String healthCheckPath;

	// ---- Baseline / reporting ----

	/**
	 * Path to the JSON baseline file. Defaults to
	 * {@code ${project.basedir}/energy-baseline.json}.
	 */
	@Parameter(property = "greener.baselineFile", defaultValue = "${project.basedir}/energy-baseline.json")
	private File baselineFile;

	/**
	 * Maximum allowed percentage increase in total energy before the build is failed. For
	 * example {@code 10} means a 10 % regression is tolerated.
	 */
	@Parameter(property = "greener.threshold", defaultValue = "10")
	private double threshold;

	/**
	 * Number of top energy-consuming methods to show in the HTML report.
	 */
	@Parameter(property = "greener.topN", defaultValue = "20")
	private int topN;

	/**
	 * Number of independent measurement iterations to run. With {@code iterations >= 2}
	 * the comparator gains run-to-run statistics and applies Welch's t-test + Cohen's d
	 * effect-size gating instead of a raw percentage threshold. Default {@code 5} for
	 * stable CI gates; use {@code 10} for paper-grade results, {@code 1} for a quick
	 * smoke test.
	 */
	@Parameter(property = "greener.iterations", defaultValue = "5")
	private int iterations;

	/**
	 * Regression metric. {@code ENERGY_PER_REQUEST} (default) normalises by successful
	 * requests so throughput improvements don't masquerade as energy regressions.
	 * {@code TOTAL_ENERGY} compares raw Joules; pick this only when request counts are
	 * unavailable or comparing two runs with identical workloads.
	 */
	@Parameter(property = "greener.regressionMetric", defaultValue = "ENERGY_PER_REQUEST")
	private com.patbaumgartner.greener.core.model.RegressionMetric regressionMetric;

	/**
	 * Optional idle-baseline window (seconds) measured before the workload. The mean idle
	 * power is subtracted from the workload energy to approximate the <em>marginal</em>
	 * energy attributable to the workload. Set to {@code 0} (default) to disable.
	 */
	@Parameter(property = "greener.idleProbeSeconds", defaultValue = "0")
	private int idleProbeSeconds;

	/**
	 * When {@code true}, the build is failed if energy consumption regressed beyond
	 * {@link #threshold}.
	 */
	@Parameter(property = "greener.failOnRegression", defaultValue = "false")
	private boolean failOnRegression;

	/**
	 * Directory where {@code greener-energy-report.html} is written. Defaults to
	 * {@code ${project.build.directory}/greener-reports}.
	 */
	@Parameter(property = "greener.reportOutputDir", defaultValue = "${project.build.directory}/greener-reports")
	private File reportOutputDir;

	/**
	 * When {@code true}, the measurement result is automatically promoted to the baseline
	 * after a successful run. This eliminates the need to call
	 * {@code greener:update-baseline} separately.
	 */
	@Parameter(property = "greener.autoUpdateBaseline", defaultValue = "false")
	private boolean autoUpdateBaseline;

	/**
	 * When {@code true}, the report output directory gets a timestamp suffix (e.g.
	 * {@code greener-reports-20260404-153012}) and a {@code latest} symlink is created
	 * pointing to the most recent run.
	 */
	@Parameter(property = "greener.timestampReports", defaultValue = "false")
	private boolean timestampReports;

	/**
	 * Git commit SHA recorded in the baseline (e.g. from {@code GITHUB_SHA}). If the
	 * environment variable is not present (e.g., during local execution), this defaults
	 * to null or empty string.
	 */
	@Parameter(property = "greener.commitSha", defaultValue = "${env.GITHUB_SHA}")
	private String commitSha;

	/**
	 * Branch name recorded in the baseline (e.g. from {@code GITHUB_REF_NAME}). If the
	 * environment variable is not present (e.g., during local execution), this defaults
	 * to null or empty string.
	 */
	@Parameter(property = "greener.branch", defaultValue = "${env.GITHUB_REF_NAME}")
	private String branch;

	/**
	 * When {@code true}, the plugin execution is skipped entirely.
	 */
	@Parameter(property = "greener.skip", defaultValue = "false")
	private boolean skip;

	// ---- Mojo entry point ----

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			logInfo("greener:measure skipped (greener.skip=true)");
			return;
		}

		validateConfiguration();

		try {
			executeInternal();
		}
		catch (MojoFailureException | MojoExecutionException e) {
			throw e;
		}
		catch (IOException | InterruptedException | RuntimeException e) {
			throw new MojoExecutionException("Energy measurement failed: " + e.getMessage(), e);
		}
	}

	private void logInfo(Supplier<String> messageSupplier) {
		if (getLog().isInfoEnabled()) {
			getLog().info(messageSupplier.get());
		}
	}

	private void logInfo(String message) {
		getLog().info(message);
	}

	private void executeInternal()
			throws IOException, InterruptedException, MojoFailureException, MojoExecutionException {
		MeasurementOrchestrator orchestrator = new MeasurementOrchestrator(this::logInfo, topN);

		// 1. Resolve Joular Core binary
		Path joularCoreBinary = resolveJoularCoreBinary();

		// 2. Build configs
		String toolName = resolveToolName();
		Path effectiveReportDir = resolveEffectiveReportDir();
		Path runDir = effectiveReportDir.resolve(toolName);
		Path workingDir = runDir.resolve("work");

		// 3. Start application
		ApplicationRunner appRunner = new ApplicationRunner();
		Process appProcess = startApplication(appRunner, workingDir);

		Path outputCsv = workingDir.resolve("joularcore-output.csv");

		WorkloadStats workloadStats = null; // NOPMD - required for definite assignment;
											// used after the try-finally
		com.patbaumgartner.greener.core.model.IteratedMeasurement iteratedMeasurement = null;
		double idleBaselineW = 0.0;

		try {
			// 4. Wait for startup
			waitForApplicationReady(appRunner, appProcess);

			// 5. Configure and start Joular Core
			JoularCoreConfig joularCoreConfig = createJoularCoreConfig(joularCoreBinary, appProcess.pid(), outputCsv);
			try (JoularCoreRunner joularCoreRunner = new JoularCoreRunner()) {
				startJoularCore(joularCoreRunner, joularCoreConfig, appProcess.pid());

				// 5a. Optional idle baseline (no workload)
				if (idleProbeSeconds > 0) {
					idleBaselineW = orchestrator.measureIdleBaselinePower(joularCoreRunner, joularCoreConfig, outputCsv,
							idleProbeSeconds, springBootJar.getName().replace(".jar", ""));
				}

				// 6. Execute workloads (iterated when iterations >= 2)
				final int multiIterationThreshold = 2;
				if (iterations >= multiIterationThreshold) {
					iteratedMeasurement = orchestrator.executeIteratedWorkloads(joularCoreRunner, joularCoreConfig,
							outputCsv, this::buildTrainingConfig, warmupDurationSeconds, measureDurationSeconds,
							iterations, springBootJar.getName().replace(".jar", ""), workingDir.resolve("iterations"));
					workloadStats = iteratedMeasurement.mergedWorkload();
				}
				else {
					workloadStats = orchestrator.executeWorkloads(joularCoreRunner, joularCoreConfig, outputCsv,
							this::buildTrainingConfig, warmupDurationSeconds, measureDurationSeconds);
				}
			}

		}
		finally {
			if (joularCodeJavaAgentPath != null) {
				appRunner.requestGracefulShutdown(baseUrl);
			}
			appRunner.stop(appProcess);
		}

		// 7. Process, compare, and report
		String appIdentifier = springBootJar.getName().replace(".jar", "");
		MeasurementConfig measurementConfig = new MeasurementConfig(outputCsv, measureDurationSeconds, appIdentifier,
				baselineFile.toPath(), effectiveReportDir, runDir, toolName, vmMode, threshold, autoUpdateBaseline,
				commitSha, branch, workingDir, joularCodeJavaAgentPath != null, iterations, regressionMetric,
				idleProbeSeconds);

		MeasurementResult result;
		final double zero = 0.0;
		if (iteratedMeasurement != null) {
			com.patbaumgartner.greener.core.model.EnergyReport report = iteratedMeasurement.representativeReport();
			if (idleBaselineW > zero) {
				report = orchestrator.subtractIdleBaseline(report, idleBaselineW);
			}
			result = orchestrator.processAndReport(measurementConfig, report, workloadStats);
		}
		else if (idleBaselineW > zero) {
			com.patbaumgartner.greener.core.model.EnergyReport report = orchestrator.processResults(outputCsv,
					measureDurationSeconds, appIdentifier);
			report = orchestrator.subtractIdleBaseline(report, idleBaselineW);
			result = orchestrator.processAndReport(measurementConfig, report, workloadStats);
		}
		else {
			result = orchestrator.processAndReport(measurementConfig, workloadStats);
		}

		// 8. Create latest symlink for timestamped reports
		if (timestampReports) {
			PluginDefaults.createLatestLink(effectiveReportDir, reportOutputDir.getName() + "-latest");
		}

		// 9. Fail build on regression
		handleRegression(result.comparison(), result.htmlReport());
	}

	private Path resolveEffectiveReportDir() throws IOException {
		Path effectiveReportDir = reportOutputDir.toPath();
		if (timestampReports) {
			return PluginDefaults.buildTimestampedDir(effectiveReportDir);
		}
		return effectiveReportDir;
	}

	private Process startApplication(ApplicationRunner appRunner, Path workingDir) throws IOException {
		logInfo(() -> "[greener] Starting application: " + springBootJar.getName());
		List<String> effectiveAppArgs = AppArgsBuilder.buildEffectiveAppArgs(appArgs, joularCodeJavaAgentPath != null,
				baseUrl);
		Path joularCodeJavaJar = joularCodeJavaAgentPath != null ? joularCodeJavaAgentPath.toPath() : null;
		Path joularCodeJavaConfig = joularCodeJavaConfigPath != null ? joularCodeJavaConfigPath.toPath() : null;
		Process appProcess = appRunner.start(springBootJar.toPath(), joularCodeJavaJar, joularCodeJavaConfig,
				workingDir, jvmArgs, effectiveAppArgs);
		logInfo(() -> "[greener] Application PID: " + appProcess.pid());
		return appProcess;
	}

	private void waitForApplicationReady(ApplicationRunner appRunner, Process appProcess)
			throws IOException, InterruptedException {
		logInfo("[greener] Waiting for health check ...");
		appRunner.waitForStartup(appProcess, baseUrl, healthCheckPath, startupTimeoutSeconds);
		logInfo("[greener] Application is ready");
	}

	private JoularCoreConfig createJoularCoreConfig(Path binary, long pid, Path outputCsv) {
		return new JoularCoreConfig().binaryPath(binary)
			.pid(pid)
			.component(joularCoreComponent)
			.outputCsvPath(outputCsv)
			.silent(true)
			.ringBuffer(joularCodeJavaAgentPath != null)
			.vmMode(vmMode)
			.vmPowerFilePath(vmPowerFilePath != null ? vmPowerFilePath.toPath() : null);
	}

	private void startJoularCore(JoularCoreRunner runner, JoularCoreConfig config, long pid) throws IOException {
		logInfo(() -> "[greener] Starting Joular Core (PID " + pid + ")");
		runner.start(config);
	}

	private void handleRegression(ComparisonResult comparison, Path htmlReport) throws MojoFailureException {
		if (failOnRegression && comparison.isFailed()) {
			throw new MojoFailureException(String.format(
					"Energy regression detected: %.2f%% increase exceeds threshold of %.1f%%. " + "See report at: %s",
					comparison.totalDeltaPercent(), threshold, htmlReport));
		}
	}

	// ---- Helpers ----

	/**
	 * Derives a tool name from the configured training script or command. Used to create
	 * a tool-specific subdirectory under {@link #reportOutputDir}.
	 */
	private String resolveToolName() {
		return PluginDefaults.resolveToolName(externalTrainingScriptFile, externalTrainingCommand);
	}

	private Path resolveJoularCoreBinary() throws IOException, InterruptedException, MojoExecutionException {
		if (joularCoreBinaryPath != null) {
			if (!joularCoreBinaryPath.exists()) {
				throw new MojoExecutionException(
						"Configured joularCoreBinaryPath does not exist: " + joularCoreBinaryPath
								+ ". Remove <joularCoreBinaryPath> to enable auto-download, or fix the path.");
			}
			logInfo(() -> "[greener] Using Joular Core binary: " + joularCoreBinaryPath);
			return joularCoreBinaryPath.toPath();
		}

		logInfo(() -> "[greener] Downloading Joular Core " + joularCoreVersion + " ...");
		JoularCoreDownloader downloader = new JoularCoreDownloader();
		return downloader.download(joularCoreVersion, JoularCoreDownloader.defaultCacheDir());
	}

	private TrainingConfig buildTrainingConfig() {
		TrainingConfig config = new TrainingConfig().baseUrl(baseUrl).requestsPerSecond(requestsPerSecond);

		if (externalTrainingScriptFile != null && externalTrainingScriptFile.exists()) {
			config.externalScriptFile(externalTrainingScriptFile.getAbsolutePath());
		}
		else if (externalTrainingCommand != null && !externalTrainingCommand.isBlank()) {
			config.externalCommand(externalTrainingCommand);
		}

		return config;
	}

	private void validateConfiguration() throws MojoExecutionException {
		if (springBootJar == null) {
			springBootJar = autoDetectSpringBootJar();
		}
		if (!springBootJar.exists()) {
			throw new MojoExecutionException("Spring Boot jar not found: " + springBootJar
					+ ". Run 'mvn package' first or set <springBootJar> explicitly.");
		}
		try {
			PluginDefaults.validateMeasureDuration(measureDurationSeconds);
			PluginDefaults.validateExternalScript(externalTrainingScriptFile);
		}
		catch (IllegalArgumentException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private File autoDetectSpringBootJar() throws MojoExecutionException {
		if (buildDirectory == null || !buildDirectory.isDirectory()) {
			throw new MojoExecutionException("springBootJar not configured and build directory not found: "
					+ buildDirectory + ". Set <springBootJar> explicitly.");
		}

		try {
			Optional<File> jar = PluginDefaults.autoDetectJar(buildDirectory.toPath());
			if (jar.isEmpty()) {
				throw new MojoExecutionException("springBootJar not configured and build directory not found: "
						+ buildDirectory + ". Set <springBootJar> explicitly.");
			}
			logInfo(() -> "[greener] Auto-detected Spring Boot jar: " + jar.get());
			return jar.get();
		}
		catch (IllegalStateException e) {
			throw new MojoExecutionException(
					e.getMessage() + ". Run 'mvn package' first or set <springBootJar> explicitly.", e);
		}
	}

}
