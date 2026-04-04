package com.patbaumgartner.greener.maven;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.baseline.RunEntryStore;
import com.patbaumgartner.greener.core.comparator.EnergyComparator;
import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.downloader.JoularCoreDownloader;
import com.patbaumgartner.greener.core.model.AggregatedRunEntry;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import com.patbaumgartner.greener.core.reader.JoularCoreResultReader;
import com.patbaumgartner.greener.core.reporter.ConsoleReporter;
import com.patbaumgartner.greener.core.reporter.HtmlReporter;
import com.patbaumgartner.greener.core.runner.ApplicationRunner;
import com.patbaumgartner.greener.core.runner.JoularCoreRunner;
import com.patbaumgartner.greener.core.runner.TrainingRunner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Measures the energy consumption of a Spring Boot application using
 * <a href="https://www.noureddine.org/research/joular/joularcore">Joular Core</a>,
 * compares the result against a stored baseline, and generates a report.
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
 * <li>Read Joular Core CSV output and build an {@link EnergyReport}.</li>
 * <li>Compare against baseline; generate console + HTML reports.</li>
 * <li>Optionally fail the build if energy regressed beyond {@code threshold}.</li>
 * </ol>
 *
 * <h2>Minimal configuration example</h2>
 *
 * <pre>{@code
 * <plugin>
 *   <groupId>com.patbaumgartner</groupId>
 *   <artifactId>greener-spring-boot-maven-plugin</artifactId>
 *   <version>0.2.0</version>
 *   <configuration>
 *     <!-- optional — auto-detected from ${project.build.directory} when omitted -->
 *     <springBootJar>${project.build.directory}/myapp.jar</springBootJar>
 *     <measureDurationSeconds>60</measureDurationSeconds>
 *   </configuration>
 *   <executions>
 *     <execution>
 *       <goals><goal>measure</goal></goals>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "measure", defaultPhase = LifecyclePhase.INTEGRATION_TEST, threadSafe = false)
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

	/** Additional JVM arguments passed when starting the Spring Boot process. */
	@Parameter
	private List<String> jvmArgs;

	/** Additional application arguments passed to the Spring Boot process. */
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
	 * When {@code true}, the plugin execution is skipped entirely.
	 */
	@Parameter(property = "greener.skip", defaultValue = "false")
	private boolean skip;

	// ---- Mojo entry point ----

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("greener:measure skipped (greener.skip=true)");
			return;
		}

		validateConfiguration();

		try {
			executeInternal();
		}
		catch (MojoFailureException e) {
			throw e;
		}
		catch (Exception e) {
			throw new MojoExecutionException("Energy measurement failed: " + e.getMessage(), e);
		}
	}

	private void executeInternal() throws Exception {
		// 1. Resolve Joular Core binary
		Path joularCoreBinary = resolveJoularCoreBinary();

		// 2. Build configs — each tool gets its own subdirectory
		String toolName = resolveToolName();
		Path runDir = reportOutputDir.toPath().resolve(toolName);
		Path workingDir = runDir.resolve("work");

		// 3. Start application
		ApplicationRunner appRunner = new ApplicationRunner();
		getLog().info("[greener] Starting application: " + springBootJar.getName());

		// Enable health probes so /actuator/health/readiness is available
		List<String> effectiveAppArgs = PluginDefaults.buildEffectiveAppArgs(appArgs);

		Process appProcess = appRunner.start(springBootJar.toPath(), null, null, workingDir, jvmArgs, effectiveAppArgs);
		getLog().info("[greener] Application PID: " + appProcess.pid());

		Path outputCsv = workingDir.resolve("joularcore-output.csv");
		WorkloadStats workloadStats = null;
		try {
			// 4. Wait for startup
			getLog().info("[greener] Waiting for health check ...");
			appRunner.waitForStartup(appProcess, baseUrl, healthCheckPath, startupTimeoutSeconds);
			getLog().info("[greener] Application is ready");

			// 5. Configure and start Joular Core
			JoularCoreConfig joularCoreConfig = new JoularCoreConfig().binaryPath(joularCoreBinary)
				.pid(appProcess.pid())
				.component(joularCoreComponent)
				.outputCsvPath(outputCsv)
				.silent(true)
				.vmMode(vmMode)
				.vmPowerFilePath(vmPowerFilePath != null ? vmPowerFilePath.toPath() : null);

			JoularCoreRunner joularCoreRunner = new JoularCoreRunner();
			getLog().info("[greener] Starting Joular Core (PID " + appProcess.pid() + ")");
			joularCoreRunner.start(joularCoreConfig);

			try {
				// 6. Warmup phase (skip energy recording)
				if (warmupDurationSeconds > 0) {
					getLog().info("[greener] Warmup phase: " + warmupDurationSeconds + " s ...");
					TrainingConfig warmupConfig = buildTrainingConfig().warmupDurationSeconds(warmupDurationSeconds)
						.measureDurationSeconds(0);
					new TrainingRunner().run(warmupConfig);

					// Discard warmup readings by deleting the CSV and restarting Joular
					// Core
					joularCoreRunner.stop();
					Files.deleteIfExists(outputCsv);
					joularCoreRunner.start(joularCoreConfig);
				}

				// 7. Measurement phase
				getLog().info("[greener] Measurement phase: " + measureDurationSeconds + " s ...");
				TrainingConfig measureConfig = buildTrainingConfig().warmupDurationSeconds(0)
					.measureDurationSeconds(measureDurationSeconds);
				workloadStats = new TrainingRunner().run(measureConfig);

			}
			finally {
				joularCoreRunner.stop();
			}

		}
		finally {
			appRunner.stop(appProcess);
		}

		// 8. Read results
		getLog().info("[greener] Processing results ...");
		String appIdentifier = springBootJar.getName().replace(".jar", "");
		JoularCoreResultReader resultReader = new JoularCoreResultReader();
		EnergyReport report = resultReader.readResults(outputCsv, PluginDefaults.buildRunId(), measureDurationSeconds,
				appIdentifier);

		// 9. Compare with baseline
		BaselineManager baselineManager = new BaselineManager();
		Optional<EnergyBaseline> baseline = baselineManager.loadBaseline(baselineFile.toPath());
		EnergyComparator comparator = new EnergyComparator();
		ComparisonResult comparison = comparator.compare(report, baseline, threshold);

		// 9b. Save latest report so that update-baseline can promote it
		Path latestReportPath = runDir.resolve("latest-energy-report.json");
		baselineManager.saveBaseline(report, null, null, latestReportPath);

		// 10. Report
		PowerSource powerSource = PluginDefaults.resolvePowerSource(vmMode);
		new ConsoleReporter().report(report, comparison, workloadStats, powerSource);
		HtmlReporter htmlReporter = new HtmlReporter();
		Path htmlReport = htmlReporter.generateReport(report, comparison, workloadStats, powerSource, runDir);
		getLog().info("[greener] HTML report: " + htmlReport);

		// 11. Save run entry for aggregation
		RunEntryStore runEntryStore = new RunEntryStore();
		AggregatedRunEntry runEntry = new AggregatedRunEntry(toolName, report, workloadStats, comparison);
		runEntryStore.save(runEntry, runDir.resolve(RunEntryStore.RUN_ENTRY_FILE));

		// 12. Generate aggregated report if multiple runs exist
		List<AggregatedRunEntry> allEntries = runEntryStore.loadAll(reportOutputDir.toPath());
		if (allEntries.size() > 1) {
			Path aggregatedReport = htmlReporter.generateAggregatedReport(allEntries, powerSource,
					reportOutputDir.toPath());
			getLog().info("[greener] Aggregated report (" + allEntries.size() + " runs): " + aggregatedReport);
		}

		// 13. Fail build on regression?
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
		if (externalTrainingScriptFile != null && externalTrainingScriptFile.exists()) {
			String fileName = externalTrainingScriptFile.getName();
			String parentDir = externalTrainingScriptFile.getParentFile() != null
					? externalTrainingScriptFile.getParentFile().getName() : null;
			return TrainingRunner.deriveToolName(fileName, parentDir);
		}
		if (externalTrainingCommand != null && !externalTrainingCommand.isBlank()) {
			return TrainingRunner.deriveToolName(externalTrainingCommand, null);
		}
		return "measurement";
	}

	private Path resolveJoularCoreBinary() throws Exception {
		if (joularCoreBinaryPath != null && joularCoreBinaryPath.exists()) {
			getLog().info("[greener] Using Joular Core binary: " + joularCoreBinaryPath);
			return joularCoreBinaryPath.toPath();
		}

		getLog().info("[greener] Downloading Joular Core " + joularCoreVersion + " ...");
		JoularCoreDownloader downloader = new JoularCoreDownloader();
		return downloader.download(joularCoreVersion, JoularCoreDownloader.defaultCacheDir());
	}

	private TrainingConfig buildTrainingConfig() {
		TrainingConfig config = new TrainingConfig().baseUrl(baseUrl)
			.requestsPerSecond(requestsPerSecond)
			.warmupDurationSeconds(warmupDurationSeconds)
			.measureDurationSeconds(measureDurationSeconds);

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
		if (measureDurationSeconds <= 0) {
			throw new MojoExecutionException("measureDurationSeconds must be > 0");
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
			getLog().info("[greener] Auto-detected Spring Boot jar: " + jar.get());
			return jar.get();
		}
		catch (IllegalStateException e) {
			throw new MojoExecutionException(
					e.getMessage() + ". Run 'mvn package' first or set <springBootJar> explicitly.");
		}
	}

}
