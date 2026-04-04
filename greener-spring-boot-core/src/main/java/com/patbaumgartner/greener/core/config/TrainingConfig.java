package com.patbaumgartner.greener.core.config;

/**
 * Configuration for the training workload that is executed against the running Spring
 * Boot application during the measurement phase.
 *
 * <p>
 * The training run produces a repeatable, controlled load so that energy measurements
 * across runs are comparable. An external command or script is required - for example k6,
 * wrk, oha, or a shell script. The {@code APP_URL} environment variable is set to the
 * application base URL.
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // fluent builder setters
public class TrainingConfig {

	/** Base URL of the application, e.g. {@code http://localhost:8080}. */
	private String baseUrl = "http://localhost:8080";

	/**
	 * Number of requests per second (passed as {@code RPS} environment variable).
	 */
	private int requestsPerSecond = 5;

	/**
	 * Warmup duration in seconds. During warmup the application runs under load (JIT
	 * warms up) but energy measurements are not captured. Use {@code 0} to skip.
	 */
	private int warmupDurationSeconds = 30;

	/**
	 * Measurement duration in seconds after warmup. Joular Core records power samples
	 * during this window; the accumulated energy is then compared to baseline.
	 */
	private int measureDurationSeconds = 60;

	/**
	 * Optional external command to use as the training workload. The application base URL
	 * is available as {@code APP_URL}, warmup seconds as {@code WARMUP_SECONDS}, measure
	 * seconds as {@code MEASURE_SECONDS}.
	 *
	 * <p>
	 * Example: {@code k6 run script.js}
	 */
	private String externalCommand = null;

	/**
	 * Optional path to an external shell script file used as the training workload. Takes
	 * precedence over {@link #externalCommand} when set.
	 *
	 * <p>
	 * The script receives the following environment variables:
	 * <ul>
	 * <li>{@code APP_URL} — base URL, e.g. {@code http://localhost:8080}</li>
	 * <li>{@code APP_HOST} — host only, e.g. {@code localhost}</li>
	 * <li>{@code APP_PORT} — port, e.g. {@code 8080}</li>
	 * <li>{@code WARMUP_SECONDS} — warmup duration</li>
	 * <li>{@code MEASURE_SECONDS}— measurement duration</li>
	 * <li>{@code TOTAL_SECONDS} — warmup + measurement</li>
	 * <li>{@code RPS} — target requests per second</li>
	 * </ul>
	 *
	 * <p>
	 * Example: {@code /path/to/examples/workloads/oha/run.sh}
	 */
	private String externalScriptFile = null;

	// ---- Builder-style setters ----

	/**
	 * Sets the base URL of the application. @param baseUrl e.g.
	 * {@code http://localhost:8080} @return this
	 */
	public TrainingConfig baseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
		return this;
	}

	/**
	 * Sets the target requests per second. @param requestsPerSecond target RPS @return
	 * this
	 */
	public TrainingConfig requestsPerSecond(int requestsPerSecond) {
		this.requestsPerSecond = requestsPerSecond;
		return this;
	}

	/**
	 * Sets the warmup duration; use {@code 0} to skip warmup. @param
	 * warmupDurationSeconds seconds @return this
	 */
	public TrainingConfig warmupDurationSeconds(int warmupDurationSeconds) {
		this.warmupDurationSeconds = warmupDurationSeconds;
		return this;
	}

	/**
	 * Sets the measurement duration after warmup. @param measureDurationSeconds
	 * seconds @return this
	 */
	public TrainingConfig measureDurationSeconds(int measureDurationSeconds) {
		this.measureDurationSeconds = measureDurationSeconds;
		return this;
	}

	/**
	 * Sets an inline external command for the workload. @param externalCommand shell
	 * command @return this
	 */
	public TrainingConfig externalCommand(String externalCommand) {
		this.externalCommand = externalCommand;
		return this;
	}

	/**
	 * Sets the path to an external shell script for the workload. @param
	 * externalScriptFile absolute path @return this
	 */
	public TrainingConfig externalScriptFile(String externalScriptFile) {
		this.externalScriptFile = externalScriptFile;
		return this;
	}

	// ---- Getters ----

	public String getBaseUrl() {
		return baseUrl;
	}

	public int getRequestsPerSecond() {
		return requestsPerSecond;
	}

	public int getWarmupDurationSeconds() {
		return warmupDurationSeconds;
	}

	public int getMeasureDurationSeconds() {
		return measureDurationSeconds;
	}

	public String getExternalCommand() {
		return externalCommand;
	}

	public String getExternalScriptFile() {
		return externalScriptFile;
	}

	/** Total training duration: warmup + measurement. */
	public int getTotalDurationSeconds() {
		return warmupDurationSeconds + measureDurationSeconds;
	}

}
