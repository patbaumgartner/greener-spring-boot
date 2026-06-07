package com.patbaumgartner.greener.core.runner;

import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes the configured training workload against the running Spring Boot application
 * and returns {@link WorkloadStats} describing what was exercised.
 *
 * <p>
 * The training run produces a repeatable, controlled load so that energy measurements
 * across runs are comparable. Two modes are supported, in order of precedence:
 * <ol>
 * <li><b>External script file</b> ({@link TrainingConfig#getExternalScriptFile()}) - runs
 * a shell script from the filesystem; ideal for wrk, wrk2, oha, Gatling, k6, etc.</li>
 * <li><b>Inline external command</b> ({@link TrainingConfig#getExternalCommand()}) - runs
 * a shell command string inline.</li>
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

	private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
		.toLowerCase(Locale.ENGLISH)
		.startsWith("win");

	/** Maximum number of characters to capture from process output (10 MB). */
	private static final int OUTPUT_BUFFER_CAP = 10 * 1024 * 1024;

	/**
	 * Runs the full training cycle and returns statistics about the workload.
	 *
	 * <p>
	 * One of {@code externalScriptFile} or {@code externalCommand} must be configured.
	 * Use one of the scripts from {@code examples/workloads/} (oha, wrk, k6, Gatling,
	 * etc.) for accurate, reproducible energy measurements.
	 * @param config training configuration
	 * @return {@link WorkloadStats} describing the executed workload
	 * @throws IOException on I/O error or when no workload is configured
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
			throw new IOException("No external workload configured. "
					+ "Set 'externalTrainingScriptFile' or 'externalTrainingCommand' "
					+ "with one of the scripts from examples/workloads/ "
					+ "(oha, wrk, wrk2, k6, Gatling, Locust, ...).");
		}
	}

	// -------------------------------------------------------------------------

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

		LOG.fine(() -> "Running external training script [" + toolName + "]: " + script);

		String[] shellCommand = resolveScriptCommand(script.toAbsolutePath().toString());
		ProcessBuilder pb = new ProcessBuilder(shellCommand);
		pb.redirectErrorStream(true);
		populateEnvironment(pb, config);

		long start = System.currentTimeMillis();
		Process process = pb.start();
		String output = captureAndForwardOutput(process);
		int exitCode;
		int timeout = config.getTimeoutSeconds();
		if (timeout > 0) {
			boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IOException("Training script timed out after " + timeout + " seconds: " + script);
			}
			exitCode = process.exitValue();
		}
		else {
			exitCode = process.waitFor();
		}
		long elapsed = (System.currentTimeMillis() - start) / 1_000;

		if (exitCode != 0) {
			throw new IOException("Training script exited with code " + exitCode + ": " + script);
		}
		LOG.fine(() -> "Training script [" + toolName + "] completed in " + elapsed + " s");
		return buildExternalStats(toolName, output, elapsed);
	}

	private WorkloadStats runExternalCommand(TrainingConfig config, String command)
			throws IOException, InterruptedException {

		LOG.fine(() -> "Running external training command: " + command);

		String[] shellCommand = resolveShellCommand("-c", command);
		ProcessBuilder pb = new ProcessBuilder(shellCommand);
		pb.redirectErrorStream(true);
		populateEnvironment(pb, config);

		long start = System.currentTimeMillis();
		Process process = pb.start();
		String output = captureAndForwardOutput(process);
		int exitCode;
		int timeout = config.getTimeoutSeconds();
		if (timeout > 0) {
			boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IOException("External training command timed out after " + timeout + " seconds: " + command);
			}
			exitCode = process.exitValue();
		}
		else {
			exitCode = process.waitFor();
		}
		long elapsed = (System.currentTimeMillis() - start) / 1_000;

		if (exitCode != 0) {
			throw new IOException("External training command exited with code " + exitCode + ": " + command);
		}
		LOG.fine(() -> "External training command completed in " + elapsed + " s");
		String toolName = deriveToolName(command, null);
		return buildExternalStats(toolName, output, elapsed);
	}

	// -------------------------------------------------------------------------

	/**
	 * Builds a command array for executing script files.
	 * <p>
	 * On Linux/macOS, scripts are executed directly so that the OS honours their shebang
	 * line (e.g. {@code #!/usr/bin/env bash}). On Windows, the method locates
	 * {@code sh.exe} from Git for Windows to run the script through a POSIX shell.
	 * @param scriptPath the absolute path of the script file to execute
	 * @return a command array suitable for {@link ProcessBuilder}
	 */
	private String[] resolveScriptCommand(String scriptPath) throws IOException {
		if (IS_WINDOWS) {
			return new String[] { findWindowsShell(), scriptPath };
		}
		return new String[] { scriptPath };
	}

	/**
	 * Builds a shell command array appropriate for the current OS.
	 * <p>
	 * On Linux/macOS, commands are invoked via {@code /bin/sh}. On Windows, the method
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
		// 1. Check PATH first using absolute path to 'where.exe' — handles Scoop,
		// Chocolatey, MSYS2, and custom Git installations without hardcoded sh.exe paths.
		try {
			Process where = new ProcessBuilder("C:\\Windows\\System32\\where.exe", "sh.exe").redirectErrorStream(true)
				.start();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(where.getInputStream(), StandardCharsets.UTF_8))) {
				String line = reader.readLine();
				if (line != null && !line.isBlank()) {
					try {
						Path candidate = Path.of(line.strip());
						// Exclude WSL bash — it runs in a separate Linux environment
						if (Files.isExecutable(candidate)
								&& !candidate.toString().toLowerCase(Locale.ENGLISH).contains("system32")) {
							LOG.fine(() -> "Using sh.exe from PATH: " + candidate);
							return candidate.toString();
						}
					}
					catch (java.nio.file.InvalidPathException ex) {
						LOG.fine(() -> "Ignoring non-path output from 'where': " + line);
					}
				}
			}
			where.waitFor(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// 2. Check well-known Git for Windows locations
		String[] gitShells = { "C:\\Program Files\\Git\\bin\\sh.exe", "C:\\Program Files (x86)\\Git\\bin\\sh.exe" };
		for (String shell : gitShells) {
			if (Files.isExecutable(Path.of(shell))) {
				return shell;
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
	 * Reads all output from the process and collects it into a string for parsing. Lines
	 * are logged at {@code FINE} level to avoid flooding the console with external tool
	 * output.
	 */
	private String captureAndForwardOutput(Process process) throws IOException {
		StringBuilder sb = new StringBuilder();
		boolean capped = false;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line = reader.readLine();
			while (line != null) {
				LOG.fine(line);
				if (!capped) {
					if (sb.length() + line.length() + 1 <= OUTPUT_BUFFER_CAP) {
						sb.append(line).append('\n');
					}
					else {
						capped = true;
						LOG.warning("Output capture buffer cap reached (" + OUTPUT_BUFFER_CAP
								+ " chars); further output will not be captured.");
					}
				}
				line = reader.readLine();
			}
		}
		return sb.toString();
	}

	private WorkloadStats buildExternalStats(String toolName, String output, long elapsed) {
		ExternalToolOutputParser parser = new ExternalToolOutputParser();
		parser.parse(toolName, output);
		if (parser.hasResults()) {
			LOG.fine(() -> String.format("Parsed %d total requests (%d failed) from %s output", parser.totalRequests(),
					parser.failedRequests(), toolName));
			return WorkloadStats.external(toolName, parser.totalRequests(), parser.failedRequests(), elapsed);
		}
		return WorkloadStats.external(toolName, elapsed);
	}

	/**
	 * Derives a human-readable tool name from the script file name or parent directory.
	 * Checks are ordered from most-specific to least-specific to avoid false positives
	 * (e.g. "wrk2" before "wrk"). Falls back to {@code "script"} when nothing
	 * recognisable is found.
	 * @param fileName script file name (e.g. {@code run.sh})
	 * @param parentDir parent directory name (e.g. {@code oha}, {@code wrk2})
	 * @return a short tool name such as {@code oha}, {@code wrk}, or {@code script}
	 */
	public static String deriveToolName(String fileName, String parentDir) {
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
		catch (IllegalArgumentException e) {
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
		catch (IllegalArgumentException e) {
			return "8080";
		}
	}

}
