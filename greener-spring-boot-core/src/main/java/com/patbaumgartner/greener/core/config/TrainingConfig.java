package com.patbaumgartner.greener.core.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the training workload that is executed against the running
 * Spring Boot application during the measurement phase.
 *
 * <p>The training run produces a repeatable, controlled load so that energy
 * measurements across runs are comparable.  Two modes are supported:
 * <ul>
 *   <li><b>Built-in HTTP loader</b> — sends configurable GET requests using the
 *       Java {@link java.net.http.HttpClient} at the configured rate.</li>
 *   <li><b>External command</b> — delegates to an external process (e.g. k6,
 *       wrk, a shell script).  The {@code APP_URL} environment variable is set
 *       to the application base URL.</li>
 * </ul>
 */
public class TrainingConfig {

    /** Base URL of the application, e.g. {@code http://localhost:8080}. */
    private String baseUrl = "http://localhost:8080";

    /**
     * Relative URL paths to GET during the training run.
     * Configure these to match the key endpoints of your application
     * (e.g. add {@code /owners}, {@code /vets} for Spring Petclinic).
     */
    private List<String> paths = new ArrayList<>(List.of("/", "/actuator/health"));

    /** Number of requests per second across all configured paths. */
    private int requestsPerSecond = 5;

    /** Maximum concurrent HTTP connections used during training. */
    private int concurrency = 2;

    /**
     * Warmup duration in seconds.  During warmup the application runs under load
     * (JIT warms up) but energy measurements are not captured.  Use {@code 0} to skip.
     */
    private int warmupDurationSeconds = 30;

    /**
     * Measurement duration in seconds after warmup.  Joular Core records power
     * samples during this window; the accumulated energy is then compared to baseline.
     */
    private int measureDurationSeconds = 60;

    /**
     * Optional external command to use as the training workload instead of the
     * built-in HTTP loader.  The application base URL is available as {@code APP_URL},
     * warmup seconds as {@code WARMUP_SECONDS}, measure seconds as {@code MEASURE_SECONDS}.
     *
     * <p>Example: {@code k6 run script.js}
     */
    private String externalCommand = null;

    /**
     * Optional path to an external shell script file used as the training workload.
     * Takes precedence over {@link #externalCommand} when set.
     *
     * <p>The script receives the following environment variables:
     * <ul>
     *   <li>{@code APP_URL}        — base URL, e.g. {@code http://localhost:8080}</li>
     *   <li>{@code APP_HOST}       — host only, e.g. {@code localhost}</li>
     *   <li>{@code APP_PORT}       — port, e.g. {@code 8080}</li>
     *   <li>{@code WARMUP_SECONDS} — warmup duration</li>
     *   <li>{@code MEASURE_SECONDS}— measurement duration</li>
     *   <li>{@code TOTAL_SECONDS}  — warmup + measurement</li>
     *   <li>{@code RPS}            — target requests per second</li>
     * </ul>
     *
     * <p>Example: {@code /path/to/examples/workloads/oha/run.sh}
     */
    private String externalScriptFile = null;

    /** Seconds to wait for the application to become healthy before aborting. */
    private int startupTimeoutSeconds = 120;

    /** Health-check path used to detect when the application is ready. */
    private String healthCheckPath = "/actuator/health";

    // ---- Builder-style setters ----

    public TrainingConfig baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public TrainingConfig paths(List<String> paths) {
        this.paths = new ArrayList<>(paths);
        return this;
    }

    public TrainingConfig requestsPerSecond(int requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
        return this;
    }

    public TrainingConfig concurrency(int concurrency) {
        this.concurrency = concurrency;
        return this;
    }

    public TrainingConfig warmupDurationSeconds(int warmupDurationSeconds) {
        this.warmupDurationSeconds = warmupDurationSeconds;
        return this;
    }

    public TrainingConfig measureDurationSeconds(int measureDurationSeconds) {
        this.measureDurationSeconds = measureDurationSeconds;
        return this;
    }

    public TrainingConfig externalCommand(String externalCommand) {
        this.externalCommand = externalCommand;
        return this;
    }

    public TrainingConfig externalScriptFile(String externalScriptFile) {
        this.externalScriptFile = externalScriptFile;
        return this;
    }

    public TrainingConfig startupTimeoutSeconds(int startupTimeoutSeconds) {
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        return this;
    }

    public TrainingConfig healthCheckPath(String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
        return this;
    }

    // ---- Getters ----

    public String getBaseUrl() { return baseUrl; }
    public List<String> getPaths() { return List.copyOf(paths); }
    public int getRequestsPerSecond() { return requestsPerSecond; }
    public int getConcurrency() { return concurrency; }
    public int getWarmupDurationSeconds() { return warmupDurationSeconds; }
    public int getMeasureDurationSeconds() { return measureDurationSeconds; }
    public String getExternalCommand() { return externalCommand; }
    public String getExternalScriptFile() { return externalScriptFile; }
    public int getStartupTimeoutSeconds() { return startupTimeoutSeconds; }
    public String getHealthCheckPath() { return healthCheckPath; }

    /** Total training duration: warmup + measurement. */
    public int getTotalDurationSeconds() {
        return warmupDurationSeconds + measureDurationSeconds;
    }
}
