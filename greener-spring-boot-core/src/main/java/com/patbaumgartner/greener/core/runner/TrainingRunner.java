package com.patbaumgartner.greener.core.runner;

import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
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
 * <h2>Environment variables available to external scripts</h2>
 *
 * <pre>
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

	private static final int HTTP_SERVER_ERROR_THRESHOLD = 500;

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
			LOG.warning("No external workload configured - falling back to the built-in HTTP loader. "
					+ "For accurate, reproducible energy measurements configure "
					+ "'externalTrainingScriptFile' with one of the scripts from "
					+ "examples/workloads/ (oha, wrk, wrk2, k6, Gatling, Locust, ...).");
			return runBuiltInHttpLoader(config);
		}
	}

	// -------------------------------------------------------------------------

	@SuppressWarnings("PMD.CloseResource") // HttpClient and ExecutorService are not
											// AutoCloseable in Java 17
	private WorkloadStats runBuiltInHttpLoader(TrainingConfig config) throws InterruptedException {
		List<String> paths = config.getPaths();
		if (paths.isEmpty()) {
			LOG.warning("No training URL paths configured - skipping workload");
			return WorkloadStats.builtIn(0, 0, config.getTotalDurationSeconds());
		}

		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.executor(Executors.newFixedThreadPool(config.getConcurrency()))
			.build();

		int warmup = config.getWarmupDurationSeconds();
		int measure = config.getMeasureDurationSeconds();
		int total = warmup + measure;

		LOG.log(Level.INFO,
				() -> String.format(
						"HTTP training workload - %d paths, %d req/s, concurrency %d, %ds warmup + %ds measure",
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
				if (resp.statusCode() >= HTTP_SERVER_ERROR_THRESHOLD)
					errorCount.incrementAndGet();
			}).exceptionally(ex -> {
				errorCount.incrementAndGet();
				if (!(ex.getCause() instanceof ConnectException)) {
					LOG.log(Level.FINE, () -> "Request to " + url + " failed: " + ex.getMessage());
				}
				return null;
			});
		}, 0, intervalNs, TimeUnit.NANOSECONDS);

		// Progress logging every 10 s
		scheduler.scheduleAtFixedRate(
				() -> LOG.log(Level.INFO,
						() -> String.format("Training: %d req sent, %d errors", requestCount.get(), errorCount.get())),
				10, 10, TimeUnit.SECONDS);

		try {
			Thread.sleep((long) total * 1_000);
		}
		finally {
			scheduler.shutdownNow();
		}

		LOG.log(Level.INFO, () -> String.format("Training complete - %d requests, %d errors", requestCount.get(),
				errorCount.get()));

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
			// may fail on some systems; shell invocation below will still work
		}

		String toolName = deriveToolName(script.getFileName().toString(),
				script.getParent() != null ? script.getParent().getFileName().toString() : null);

		LOG.log(Level.INFO, () -> "Running external training script [" + toolName + "]: " + script);

		String[] shellCommand = resolveShellCommand(script.toAbsolutePath().toString());
		ProcessBuilder pb = new ProcessBuilder(shellCommand);
		pb.redirectErrorStream(true);
		populateEnvironment(pb, config);

		long start = System.currentTimeMillis();
		Process process = pb.start();
		String output = captureAndForwardOutput(process);
		int exitCode = process.waitFor();
		long elapsed = (System.currentTimeMillis() - start) / 1_000;

		if (exitCode != 0) {
			throw new IOException("Training script exited with code " + exitCode + ": " + script);
		}
		LOG.log(Level.INFO, () -> "Training script [" + toolName + "] completed in " + elapsed + " s");
		return buildExternalStats(toolName, output, elapsed);
	}

	private WorkloadStats runExternalCommand(TrainingConfig config, String command)
			throws IOException, InterruptedException {

		LOG.log(Level.INFO, () -> "Running external training command: " + command);

		String[] shellCommand = resolveShellCommand("-c", command);
		ProcessBuilder pb = new ProcessBuilder(shellCommand);
		pb.redirectErrorStream(true);
		populateEnvironment(pb, config);

		long start = System.currentTimeMillis();
		Process process = pb.start();
		String output = captureAndForwardOutput(process);
		int exitCode = process.waitFor();
		long elapsed = (System.currentTimeMillis() - start) / 1_000;

		if (exitCode != 0) {
			throw new IOException("External training command exited with code " + exitCode + ": " + command);
		}
		LOG.log(Level.INFO, () -> "External training command completed in " + elapsed + " s");
		String toolName = deriveToolName(command, null);
		return buildExternalStats(toolName, output, elapsed);
	}

	// -------------------------------------------------------------------------

	private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
		.toLowerCase(Locale.ENGLISH)
		.startsWith("win");

	/**
	 * Builds a shell command array appropriate for the current OS.
	 * <p>
	 * On Linux/macOS, scripts are invoked via {@code /bin/sh}. On Windows, the method
	 * locates {@code sh.exe} from Git for Windows (which is required by the project) so
	 * that POSIX shell scripts can be executed unchanged.
	 * @param args the arguments to pass after the shell executable
	 * @return a command array suitable for {@link ProcessBuilder}
	 */
	private String[] resolveShellCommand(String... args) throws IOException {
		String shell = IS_WINDOWS ? findWindowsShell() : "/bin/sh";
		String[] command = new String[args.length + 1];
		command[0] = shell;
		System.arraycopy(args, 0, command, 1, args.length);
		return command;
	}

	/**
	 * Locates {@code sh.exe} on Windows. Checks {@code PATH} first, then well-known Git
	 * for Windows installation directories.
	 */
	private String findWindowsShell() throws IOException {
		// 1. Check if sh is already on PATH
		try {
			Process probe = new ProcessBuilder("sh", "--version").redirectErrorStream(true).start();
			probe.getInputStream().readAllBytes();
			if (probe.waitFor() == 0) {
				return "sh";
			}
		}
		catch (IOException | InterruptedException ignored) {
			// sh not on PATH — continue searching
		}

		// 2. Check well-known Git for Windows locations
		String[] candidates = { System.getenv("ProgramFiles") + "\\Git\\bin\\sh.exe",
				System.getenv("ProgramFiles(x86)") + "\\Git\\bin\\sh.exe",
				System.getenv("LOCALAPPDATA") + "\\Programs\\Git\\bin\\sh.exe" };
		for (String candidate : candidates) {
			if (candidate != null && Files.isExecutable(Path.of(candidate))) {
				LOG.log(Level.INFO, () -> "Using Git Bash shell: " + candidate);
				return candidate;
			}
		}

		throw new IOException("Cannot find sh.exe on Windows. Git for Windows must be installed "
				+ "and on PATH, or installed in the default location.");
	}

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
	 * Reads all output from the process, forwarding each line to the logger and
	 * collecting it into a string for parsing.
	 */
	private String captureAndForwardOutput(Process process) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line = reader.readLine();
			while (line != null) {
				LOG.info(line);
				sb.append(line).append('\n');
				line = reader.readLine();
			}
		}
		return sb.toString();
	}

	private WorkloadStats buildExternalStats(String toolName, String output, long elapsed) {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse(toolName, output);
		if (parser.hasResults()) {
			LOG.log(Level.INFO, () -> String.format("Parsed %d total requests (%d failed) from %s output",
					parser.totalRequests(), parser.failedRequests(), toolName));
			return WorkloadStats.external(toolName, parser.totalRequests(), parser.failedRequests(), elapsed);
		}
		return WorkloadStats.external(toolName, elapsed);
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
		String combined = (fileName + " " + (parentDir != null ? parentDir : "")).toLowerCase(Locale.ENGLISH);
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
		if (parentDir != null && "ab".equalsIgnoreCase(parentDir))
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
