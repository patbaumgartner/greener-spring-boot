package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;

import org.gradle.api.tasks.OutputDirectory;

import java.util.List;

import javax.inject.Inject;

/**
 * DSL extension for the {@code greener} block in Gradle build scripts.
 *
 * <h2>All available options</h2>
 * 
 * <pre>{@code
 * greener {
 *     // Required
 *     springBootJar = file("build/libs/myapp.jar")
 *
 *     // Joular Core
 *     joularCoreBinaryPath = file("/usr/local/bin/joularcore")  // optional, auto-downloaded if absent
 *     joularCoreVersion    = "0.0.1-alpha-11"
 *     joularCoreComponent  = "cpu"   // "cpu" | "gpu" | "all"
 *
 *     // VM mode (virtualised environments — no direct RAPL access)
 *     vmMode         = true
 *     vmPowerFilePath = file("/tmp/vm-power.txt")  // host writes watts here every second
 *
 *     // Training workload
 *     baseUrl                    = "http://localhost:8080"
 *     trainingPaths              = listOf("/", "/owners", "/vets")
 *     requestsPerSecond          = 5
 *     externalTrainingScriptFile = file("examples/workloads/oha/run.sh")  // optional
 *     externalTrainingCommand    = "k6 run load-test.js"                  // optional fallback
 *     warmupDurationSeconds      = 30
 *     measureDurationSeconds     = 60
 *     startupTimeoutSeconds      = 120
 *     healthCheckPath            = "/actuator/health"
 *
 *     // Baseline & reporting
 *     baselineFile     = file("energy-baseline.json")
 *     threshold        = 10.0   // % regression allowed
 *     failOnRegression = false
 *     reportOutputDir  = file("build/greener-reports")
 * }
 * }</pre>
 */
public abstract class GreenerExtension {

    /**
     * Creates the extension and sets sensible defaults for all optional properties.
     */
    @Inject
    public GreenerExtension() {
        getApplicationPort().convention(8080);
        getJoularCoreVersion().convention(JoularCoreConfig.DEFAULT_VERSION);
        getJoularCoreComponent().convention("cpu");
        getVmMode().convention(false);
        getBaseUrl().convention("http://localhost:8080");
        getTrainingPaths().convention(List.of("/", "/actuator/health"));
        getRequestsPerSecond().convention(5);
        getWarmupDurationSeconds().convention(30);
        getMeasureDurationSeconds().convention(60);
        getStartupTimeoutSeconds().convention(120);
        getHealthCheckPath().convention("/actuator/health/readiness");
        getThreshold().convention(10.0);
        getFailOnRegression().convention(false);
    }

    /**
     * Path to the executable Spring Boot fat-jar.
     * 
     * @return the Spring Boot jar property
     */
    @InputFile
    public abstract RegularFileProperty getSpringBootJar();

    /**
     * HTTP port the Spring Boot application listens on.
     * 
     * @return the application port property
     */
    @Input
    public abstract Property<Integer> getApplicationPort();

    /**
     * Full path to the Joular Core binary (optional; auto-downloaded when absent).
     * 
     * @return the Joular Core binary path property
     */
    @InputFile
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getJoularCoreBinaryPath();

    /**
     * Joular Core release version to download when
     * {@link #getJoularCoreBinaryPath()} is unset.
     * 
     * @return the Joular Core version property
     */
    @Input
    public abstract Property<String> getJoularCoreVersion();

    /**
     * Hardware component to monitor: {@code cpu}, {@code gpu}, or {@code all}.
     * 
     * @return the Joular Core component property
     */
    @Input
    public abstract Property<String> getJoularCoreComponent();

    /**
     * Base URL of the Spring Boot application used by the built-in HTTP loader.
     * 
     * @return the base URL property
     */
    @Input
    public abstract Property<String> getBaseUrl();

    /**
     * Relative URL paths exercised during the training run.
     * 
     * @return the training paths property
     */
    @Input
    public abstract ListProperty<String> getTrainingPaths();

    /**
     * Requests per second issued during the training run.
     * 
     * @return the requests per second property
     */
    @Input
    public abstract Property<Integer> getRequestsPerSecond();

    /**
     * Optional external training command (e.g. {@code k6 run script.js}).
     * 
     * @return the external training command property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getExternalTrainingCommand();

    /**
     * Path to an external shell script file used as the training workload.
     * Takes precedence over {@link #getExternalTrainingCommand()}.
     * See {@code examples/workloads/} for wrk, wrk2, oha, and Gatling examples.
     * 
     * @return the external training script file property
     */
    @InputFile
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getExternalTrainingScriptFile();

    /**
     * Enable Joular Core VM mode — for virtualised environments where RAPL
     * counters are not directly accessible. The host must write the VM's
     * instantaneous power in Watts to {@link #getVmPowerFilePath()} every second.
     * 
     * @return the VM mode property
     */
    @Input
    public abstract Property<Boolean> getVmMode();

    /**
     * Path to the file that provides VM power readings when {@link #getVmMode()} is
     * {@code true}. Must contain a single float (e.g. {@code 45.2}).
     * 
     * @return the VM power file path property
     */
    @InputFile
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getVmPowerFilePath();

    /**
     * Warmup duration in seconds (energy from this phase is discarded).
     * 
     * @return the warmup duration property
     */
    @Input
    public abstract Property<Integer> getWarmupDurationSeconds();

    /**
     * Measurement duration in seconds.
     * 
     * @return the measure duration property
     */
    @Input
    public abstract Property<Integer> getMeasureDurationSeconds();

    /**
     * Seconds to wait for the application health endpoint before aborting.
     * 
     * @return the startup timeout property
     */
    @Input
    public abstract Property<Integer> getStartupTimeoutSeconds();

    /**
     * Health-check path used to detect when the application is ready.
     * 
     * @return the health check path property
     */
    @Input
    public abstract Property<String> getHealthCheckPath();

    /**
     * Path to the JSON baseline file.
     * 
     * @return the baseline file property
     */
    @org.gradle.api.tasks.InputFile
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getBaselineFile();

    /**
     * Maximum percentage energy increase before the build is failed.
     * 
     * @return the threshold property
     */
    @Input
    public abstract Property<Double> getThreshold();

    /**
     * Whether to fail the build on energy regression beyond
     * {@link #getThreshold()}.
     * 
     * @return the fail on regression property
     */
    @Input
    public abstract Property<Boolean> getFailOnRegression();

    /**
     * Directory where the HTML report is written.
     * 
     * @return the report output directory property
     */
    @OutputDirectory
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getReportOutputDir();
}
