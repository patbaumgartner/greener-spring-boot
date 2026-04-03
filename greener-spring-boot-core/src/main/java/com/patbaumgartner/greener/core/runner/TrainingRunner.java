package com.patbaumgartner.greener.core.runner;

import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Executes the configured training workload against the running Spring Boot application
 * and returns {@link WorkloadStats} describing what was exercised.
 *
 * <p>
 * The training run produces a repeatable, controlled load so that energy measurements
 * across runs are comparable. Three modes are supported, in order of precedence:
 * <ol>
 * <li><b>External script file</b> ({@link TrainingConfig#getExternalScriptFile()}) — runs
 * a shell script from the filesystem; ideal for wrk, wrk2, oha, Gatling, k6, etc.</li>
 * <li><b>Inline external command</b> ({@link TrainingConfig#getExternalCommand()}) — runs
 * a shell command string inline.</li>
 * <li><b>Built-in HTTP loader</b> — sends GET requests at the configured rate using the
 * Java {@link HttpClient}; requires no external tools.</li>
 * </ol>
 *
 * <h2>Environment variables available to external scripts</h2> <pre>
 * APP_URL         http://localhost:8080
 * APP_HOST        localhost
 * APP_PORT        8080
 * WARMUP_SECONDS  30
 * MEASURE_SECONDS 60
 * TOTAL_SECONDS   90
 * RPS             5
 * </pre>
 */
public class TrainingRunner {

	private static final Logger LOG = Logger.getLogger(TrainingRunner.class.getName());

	/**
	 * Runs the full training cycle and returns statistics about the workload.
	 *
	 * <p>
	 * <strong>Recommendation</strong>: always provide an {@code externalScriptFile} or
	 * {@code externalCommand}. The built-in HTTP loader is a last-resort fallback that
	 * produces less reproducible load patterns and is <em>not recommended</em> for energy
	 * measurement. Configure one of the scripts from {@code examples/workloads/} instead.
	 * @param config training configuration
	 * @return {@link WorkloadStats} describing the executed workload
	 * @throws IOException on I/O error (external command mode)
	 * @throws InterruptedException if the thread is interrupted
	 */
	public WorkloadStats run(TrainingConfig config) throws IOException, InterruptedException {
		String scriptFile = config.getExternalScriptFile();
		String command = config.getExternalCommand();

		if (scriptFile != null && !scriptFile.isBlank()) {
			return runExternalScript(config, scriptFile);
		}
		else if (command != null && !command.isBlank()) {
			return runExternalCommand(config, command);
		}
		else {
			LOG.warning("No external workload configured — falling back to the built-in HTTP loader. "
					+ "For accurate, reproducible energy measurements configure "
					+ "'externalTrainingScriptFile' with one of the scripts from "
					+ "examples/workloads/ (oha, wrk, wrk2, k6, Gatling, Locust, …).");
			return runBuiltInHttpLoader(config);
		}
	}

	// -------------------------------------------------------------------------

	private WorkloadStats runBuiltInHttpLoader(TrainingConfig config) throws InterruptedException {
		List<String> paths = config.getPaths();
		if (paths.isEmpty()) {
			LOG.warning("No training URL paths configured — skipping workload");
			return WorkloadStats.builtIn(0, 0, config.getTotalDurationSeconds());
		}

		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.executor(Executors.newFixedThreadPool(config.getConcurrency()))
			.build();

		int warmup = config.getWarmupDurationSeconds();
		int measure = config.getMeasureDurationSeconds();
		int total = warmup + measure;

		LOG.info(String.format("HTTP training workload — %d paths, %d req/s, concurrency %d, %ds warmup + %ds measure",
				paths.size(), config.getRequestsPerSecond(), config.getConcurrency(), warmup, measure));

		AtomicLong requestCount = new AtomicLong();
		AtomicLong errorCount = new AtomicLong();

		long intervalNs = 1_000_000_000L / Math.max(1, config.getRequestsPerSecond());
		int[] pathIdx = { 0 };

		ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(config.getConcurrency() + 1);

		scheduler.scheduleAtFixedRate(() -> {
			String path = paths.get(pathIdx[0] % paths.size());
			pathIdx[0]++;
			String url = config.getBaseUrl() + path;

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();

			client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(resp -> {
				requestCount.incrementAndGet();
				if (resp.statusCode() >= 500)
					errorCount.incrementAndGet();
			}).exceptionally(ex -> {
				errorCount.incrementAndGet();
				if (!(ex.getCause() instanceof ConnectException)) {
					LOG.fine("Request to " + url + " failed: " + ex.getMessage());
				}
				return null;
			});
		}, 0, intervalNs, TimeUnit.NANOSECONDS);

		// Progress logging every 10 s
		scheduler.scheduleAtFixedRate(
				() -> LOG.info(String.format("Training: %d req sent, %d errors", requestCount.get(), errorCount.get())),
				10, 10, TimeUnit.SECONDS);

		try {
			Thread.sleep((long) total * 1_000);
		}
		finally {
			scheduler.shutdownNow();
		}

		LOG.info(String.format("Training complete — %d requests, %d errors", requestCount.get(), errorCount.get()));

		return WorkloadStats.builtIn(requestCount.get(), errorCount.get(), total);
	}

	private WorkloadStats runExternalScript(TrainingConfig config, String scriptFile)
			throws IOException, InterruptedException {

		Path script = Path.of(scriptFile);
		if (!Files.exists(script)) {
			throw new IOException("External training script not found: " + script.toAbsolutePath());
		}

		// Ensure the script is executable
		try {
			script.toFile().setExecutable(true);
		}
		catch (SecurityException ignored) {
			// may fail on some systems; /bin/sh invocation below will still work
		}

		String toolName = deriveToolName(script.getFileName().toString(),
				script.getParent() != null ? script.getParent().getFileName().toString() : null);

		LOG.info("Running external training script [" + toolName + "]: " + script);

		ProcessBuilder pb = new ProcessBuilder("/bin/sh", script.toAbsolutePath().toString()).inheritIO();
		populateEnvironment(pb, config);

		long start = System.currentTimeMillis();
		int exitCode = pb.start().waitFor();
		long elapsed = (System.currentTimeMillis() - start) / 1_000;

		if (exitCode != 0) {
			throw new IOException("Training script exited with code " + exitCode + ": " + script);
		}
		LOG.info("Training script [" + toolName + "] completed in " + elapsed + " s");
		return WorkloadStats.external(toolName, elapsed);
	}

	private WorkloadStats runExternalCommand(TrainingConfig config, String command)
			throws IOException, InterruptedException {

		LOG.info("Running external training command: " + command);

		ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command).inheritIO();
		populateEnvironment(pb, config);

		long start = System.currentTimeMillis();
		int exitCode = pb.start().waitFor();
		long elapsed = (System.currentTimeMillis() - start) / 1_000;

		if (exitCode != 0) {
			throw new IOException("External training command exited with code " + exitCode + ": " + command);
		}
		LOG.info("External training command completed in " + elapsed + " s");
		return WorkloadStats.external("custom", elapsed);
	}

	// -------------------------------------------------------------------------

	private void populateEnvironment(ProcessBuilder pb, TrainingConfig config) {
		String baseUrl = config.getBaseUrl();
		pb.environment().put("APP_URL", baseUrl);
		pb.environment().put("APP_HOST", extractHost(baseUrl));
		pb.environment().put("APP_PORT", extractPort(baseUrl));
		pb.environment().put("WARMUP_SECONDS", String.valueOf(config.getWarmupDurationSeconds()));
		pb.environment().put("MEASURE_SECONDS", String.valueOf(config.getMeasureDurationSeconds()));
		pb.environment().put("TOTAL_SECONDS", String.valueOf(config.getTotalDurationSeconds()));
		pb.environment().put("RPS", String.valueOf(config.getRequestsPerSecond()));
	}

	/**
	 * Derives a human-readable tool name from the script file name or parent directory.
	 * Checks are ordered from most-specific to least-specific to avoid false positives
	 * (e.g. "wrk2" before "wrk"). Falls back to {@code "script"} when nothing
	 * recognisable is found.
	 */
	private String deriveToolName(String fileName, String parentDir) {
		// Build a combined token: parent directory name (e.g. "oha", "wrk2") is the
		// most reliable signal; fall back to the file name itself.
		String combined = (fileName + " " + (parentDir != null ? parentDir : "")).toLowerCase();
		if (combined.contains("wrk2"))
			return "wrk2";
		if (combined.contains("wrk"))
			return "wrk";
		if (combined.contains("oha"))
			return "oha";
		if (combined.contains("gatling"))
			return "gatling";
		if (combined.contains("k6"))
			return "k6";
		if (combined.contains("jmeter"))
			return "jmeter";
		if (combined.contains("locust"))
			return "locust";
		if (combined.contains("bombardier"))
			return "bombardier";
		if (combined.contains("hyperfoil"))
			return "hyperfoil";
		// "ab" is only matched on an exact parent-directory token to avoid collisions
		// with unrelated path segments that happen to contain the substring "ab".
		if (parentDir != null && parentDir.equalsIgnoreCase("ab"))
			return "ab";
		return "script";
	}

	private String extractHost(String baseUrl) {
		try {
			URI uri = URI.create(baseUrl);
			return uri.getHost() != null ? uri.getHost() : "localhost";
		}
		catch (Exception e) {
			return "localhost";
		}
	}

	private String extractPort(String baseUrl) {
		try {
			URI uri = URI.create(baseUrl);
			int port = uri.getPort();
			if (port > 0)
				return String.valueOf(port);
			return "http".equals(uri.getScheme()) ? "80" : "443";
		}
		catch (Exception e) {
			return "8080";
		}
	}

}
