package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/**
 * DSL extension for the {@code greener} block in Gradle build scripts.
 *
 * <h2>All available options</h2>
 *
 * <pre>{@code
 * greener {
 *     // Optional - auto-detected from build/libs/ when omitted
 *     springBootJar.set(file("build/libs/myapp.jar"))
 *     jvmArgs.set(listOf("-Xmx512m"))
 *     appArgs.set(listOf("--server.port=8080"))
 *
 *     // Joular Core
 *     joularCoreBinaryPath.set(file("/usr/local/bin/joularcore"))  // optional, auto-downloaded if absent
 *     joularCoreVersion.set("0.0.1-alpha-11")
 *     joularCoreComponent.set("cpu")   // "cpu" | "gpu" | "all"
 *
 *     // VM mode (virtualised environments - no direct RAPL access)
 *     vmMode.set(true)
 *     vmPowerFilePath.set(file("/tmp/vm-power.txt"))  // host writes watts here every second
 *
 *     // Training workload
 *     baseUrl.set("http://localhost:8080")
 *     requestsPerSecond.set(5)
 *     externalTrainingScriptFile.set(file("examples/workloads/oha/run.sh"))  // optional
 *     externalTrainingCommand.set("k6 run load-test.js")                  // alternative to script
 *     warmupDurationSeconds.set(30)
 *     measureDurationSeconds.set(60)
 *     startupTimeoutSeconds.set(120)
 *     healthCheckPath.set("/actuator/health/readiness")
 *
 *     // Baseline & reporting
 *     baselineFile.set(file("energy-baseline.json"))
 *     threshold.set(10.0)   // % regression allowed
 *     failOnRegression.set(false)
 *     reportOutputDir.set(file("build/greener-reports"))
 *     latestReportFile.set(file("build/greener-reports/oha/latest-energy-report.json"))
 *
 *     // Auto-update & timestamped reports
 *     autoUpdateBaseline.set(false)  // auto-promote measurement to baseline
 *     timestampReports.set(false)    // append timestamp, create latest symlink
 *     commitSha.set("...")           // git SHA recorded in baseline (auto-detected from GITHUB_SHA)
 *     branch.set("...")              // branch recorded in baseline (auto-detected from GITHUB_REF_NAME)
 *
 *     // Skip execution
 *     skip.set(false)
 * }
 * }</pre>
 */
@SuppressWarnings("PMD.ConstructorCallsOverridableMethod") // Gradle abstract property
															// convention pattern
public abstract class GreenerExtension {

	/**
	 * Creates the extension and sets sensible defaults for all optional properties.
	 */
	@Inject
	public GreenerExtension() {
		getJoularCoreVersion().convention(JoularCoreConfig.DEFAULT_VERSION);
		getJoularCoreComponent().convention("cpu");
		getVmMode().convention(false);
		getBaseUrl().convention("http://localhost:8080");
		getRequestsPerSecond().convention(5);
		getWarmupDurationSeconds().convention(30);
		getMeasureDurationSeconds().convention(60);
		getStartupTimeoutSeconds().convention(120);
		getHealthCheckPath().convention("/actuator/health/readiness");
		getThreshold().convention(10.0);
		getFailOnRegression().convention(false);
		getAutoUpdateBaseline().convention(false);
		getTimestampReports().convention(false);
		getSkip().convention(false);
	}

	/**
	 * Path to the executable Spring Boot fat-jar. When omitted the plugin auto-detects a
	 * single jar in {@code build/libs/}.
	 * @return the Spring Boot jar property
	 */
	public abstract RegularFileProperty getSpringBootJar();

	/**
	 * Additional JVM arguments passed when starting the Spring Boot process.
	 * @return the JVM arguments property
	 */
	public abstract ListProperty<String> getJvmArgs();

	/**
	 * Additional application arguments passed to the Spring Boot process.
	 * @return the application arguments property
	 */
	public abstract ListProperty<String> getAppArgs();

	/**
	 * Full path to the Joular Core binary (optional; auto-downloaded when absent).
	 * @return the Joular Core binary path property
	 */
	public abstract RegularFileProperty getJoularCoreBinaryPath();

	/**
	 * Joular Core release version to download when {@link #getJoularCoreBinaryPath()} is
	 * unset. See <a href="https://github.com/joular/joularcore/releases">Joular Core
	 * releases</a> for available versions.
	 * @return the Joular Core version property
	 */
	public abstract Property<String> getJoularCoreVersion();

	/**
	 * Hardware component to monitor: {@code cpu}, {@code gpu}, or {@code all}.
	 * @return the Joular Core component property
	 */
	public abstract Property<String> getJoularCoreComponent();

	/**
	 * Base URL of the Spring Boot application.
	 * @return the base URL property
	 */
	public abstract Property<String> getBaseUrl();

	/**
	 * Requests per second (passed as {@code RPS} environment variable).
	 * @return the requests per second property
	 */
	public abstract Property<Integer> getRequestsPerSecond();

	/**
	 * Optional external command used as the training workload (e.g.
	 * {@code k6 run script.js}). The {@code APP_URL} environment variable is set to
	 * {@link #getBaseUrl()}.
	 * @return the external training command property
	 */
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
	public abstract RegularFileProperty getExternalTrainingScriptFile();

	/**
	 * Enable Joular Core VM mode - for virtualised environments where RAPL counters are
	 * not directly accessible. The host must write the VM's instantaneous power in Watts
	 * to {@link #getVmPowerFilePath()} every second.
	 * @return the VM mode property
	 */
	public abstract Property<Boolean> getVmMode();

	/**
	 * Path to the file that provides VM power readings when {@link #getVmMode()} is
	 * {@code true}. Must contain a single float (e.g. {@code 45.2}).
	 * @return the VM power file path property
	 */
	public abstract RegularFileProperty getVmPowerFilePath();

	/**
	 * Warmup duration in seconds. The application runs under load during warmup so the
	 * JIT compiler warms up, but energy data from this phase is discarded.
	 * @return the warmup duration property
	 */
	public abstract Property<Integer> getWarmupDurationSeconds();

	/**
	 * Duration in seconds of the actual measurement window after warmup.
	 * @return the measure duration property
	 */
	public abstract Property<Integer> getMeasureDurationSeconds();

	/**
	 * Seconds to wait for the application health endpoint before aborting.
	 * @return the startup timeout property
	 */
	public abstract Property<Integer> getStartupTimeoutSeconds();

	/**
	 * Health-check path used to detect when the application is ready.
	 * @return the health check path property
	 */
	public abstract Property<String> getHealthCheckPath();

	/**
	 * Path to the JSON baseline file.
	 * @return the baseline file property
	 */
	public abstract RegularFileProperty getBaselineFile();

	/**
	 * Maximum allowed percentage increase in total energy before the build is failed. For
	 * example {@code 10} means a 10 % regression is tolerated.
	 * @return the threshold property
	 */
	public abstract Property<Double> getThreshold();

	/**
	 * Whether to fail the build on energy regression beyond {@link #getThreshold()}.
	 * @return the fail on regression property
	 */
	public abstract Property<Boolean> getFailOnRegression();

	/**
	 * Directory where the HTML report is written.
	 * @return the report output directory property
	 */
	public abstract DirectoryProperty getReportOutputDir();

	/**
	 * Explicit path to a report JSON file to promote during {@code updateEnergyBaseline}.
	 * When unset, the latest report is discovered automatically under
	 * {@link #getReportOutputDir()}.
	 * @return the latest report file property
	 */
	public abstract RegularFileProperty getLatestReportFile();

	/**
	 * When {@code true}, the measurement result is automatically promoted to the baseline
	 * after a successful run. Eliminates the need to call {@code updateEnergyBaseline}
	 * separately.
	 * @return the auto-update baseline property
	 */
	public abstract Property<Boolean> getAutoUpdateBaseline();

	/**
	 * Git commit SHA to record in the baseline when auto-updating.
	 * @return the commit SHA property
	 */
	public abstract Property<String> getCommitSha();

	/**
	 * Branch name to record in the baseline when auto-updating.
	 * @return the branch property
	 */
	public abstract Property<String> getBranch();

	/**
	 * When {@code true}, the report output directory gets a timestamp suffix and a
	 * {@code latest} symlink is created pointing to the most recent run.
	 * @return the timestamp reports property
	 */
	public abstract Property<Boolean> getTimestampReports();

	/**
	 * When {@code true}, plugin execution is skipped entirely.
	 * @return the skip property
	 */
	public abstract Property<Boolean> getSkip();

}
