package com.patbaumgartner.greener.core.runner;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Launches and manages the lifecycle of the monitored Spring Boot application.
 *
 * <p>
 * The application is started as a child process. When using Joular Core for energy
 * monitoring, the JVM process ID is exposed via {@link Process#pid()} so that Joular Core
 * can monitor it directly.
 *
 * <p>
 * Optionally, the Joular Code Java agent can be attached for method-level granularity
 * (Joular Code Java itself will use Joular Core as its power reading backend when
 * configured to do so).
 */
public class ApplicationRunner {

	private static final Logger LOG = Logger.getLogger(ApplicationRunner.class.getName());

	private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 3;

	private final HttpClient httpClient;

	private final int requestTimeoutSeconds;

	public ApplicationRunner() {
		this(DEFAULT_REQUEST_TIMEOUT_SECONDS);
	}

	/**
	 * Creates an application runner with a custom per-request timeout for health-check
	 * polling. The overall startup timeout is controlled by
	 * {@link #waitForStartup(Process, String, String, int)}.
	 * @param requestTimeoutSeconds timeout in seconds for each individual HTTP request
	 */
	public ApplicationRunner(int requestTimeoutSeconds) {
		this.requestTimeoutSeconds = requestTimeoutSeconds;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(requestTimeoutSeconds)).build();
	}

	/**
	 * Starts the Spring Boot application.
	 * @param springBootJar path to the executable Spring Boot fat-jar
	 * @param joularCodeJavaJar optional path to the Joular Code Java agent jar
	 * ({@code null} to skip method-level monitoring)
	 * @param joularCodeJavaConfig optional path to the generated
	 * {@code joularcodejava.properties} for Joular Code Java (used only when
	 * {@code joularCodeJavaJar} is set)
	 * @param workingDir working directory for the process
	 * @param extraJvmArgs additional JVM arguments (e.g. {@code -Xmx512m})
	 * @param appArgs application arguments forwarded to Spring Boot
	 * @return the started {@link Process}
	 */
	public Process start(Path springBootJar, Path joularCodeJavaJar, Path joularCodeJavaConfig, Path workingDir,
			List<String> extraJvmArgs, List<String> appArgs) throws IOException {

		Files.createDirectories(workingDir);

		String javaExecutable = findCurrentJavaExecutable();

		List<String> command = new ArrayList<>();
		command.add(javaExecutable);

		if (joularCodeJavaJar != null && Files.exists(joularCodeJavaJar)) {
			command.add("-javaagent:" + joularCodeJavaJar.toAbsolutePath());
			LOG.info(() -> "Joular Code Java agent attached: " + joularCodeJavaJar);
		}

		if (joularCodeJavaConfig != null && Files.exists(joularCodeJavaConfig)) {
			command.add("-Djoularcodejava.properties=" + joularCodeJavaConfig.toAbsolutePath());
		}

		if (extraJvmArgs != null) {
			command.addAll(extraJvmArgs);
		}

		command.add("-jar");
		command.add(springBootJar.toAbsolutePath().toString());

		if (appArgs != null) {
			command.addAll(appArgs);
		}

		LOG.fine(() -> "Command: " + String.join(" ", command));

		ProcessBuilder pb = new ProcessBuilder(command).directory(workingDir.toFile())
			.redirectOutput(workingDir.resolve("app-stdout.log").toFile())
			.redirectError(workingDir.resolve("app-stderr.log").toFile());

		Process process = pb.start();
		LOG.fine(() -> "Application started with PID " + process.pid());
		return process;
	}

	/**
	 * Blocks until the application's health-check endpoint returns HTTP 2xx, or until
	 * {@code timeoutSeconds} elapses.
	 * @param process the application process to monitor for early exit
	 * @throws RuntimeException if the application does not become healthy in time or
	 * exits prematurely
	 */
	public void waitForStartup(Process process, String baseUrl, String healthPath, int timeoutSeconds)
			throws IOException, InterruptedException {

		String healthUrl = baseUrl + healthPath;
		LOG.fine(() -> "Polling health endpoint: " + healthUrl + " (timeout: " + timeoutSeconds + "s)");

		long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1_000;

		while (System.currentTimeMillis() < deadline) {
			if (!process.isAlive()) {
				throw new RuntimeException(
						"Application process (PID " + process.pid() + ") exited prematurely with code "
								+ process.exitValue() + ". Check app-stdout.log and app-stderr.log for details.");
			}
			try {
				HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(healthUrl))
					.timeout(Duration.ofSeconds(requestTimeoutSeconds))
					.GET()
					.build();

				HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() >= 200 && response.statusCode() < 300) {
					LOG.fine(() -> "Health check passed (HTTP " + response.statusCode() + ")");
					return;
				}
			}
			catch (ConnectException | HttpTimeoutException ignored) {
				// Expected during startup (connection refused or timed out, e.g. IPv6
				// probe on Windows) — keep polling
			}
			Thread.sleep(2_000);
		}

		throw new RuntimeException(
				"Application did not become healthy within " + timeoutSeconds + " seconds at " + healthUrl);
	}

	/**
	 * Gracefully stops the application process, waiting up to 30 s for a clean shutdown
	 * before forcibly killing it.
	 *
	 * <p>
	 * On Linux/macOS, {@link Process#destroy()} sends {@code SIGTERM} which triggers JVM
	 * shutdown hooks. On Windows, {@code Process.destroy()} calls
	 * {@code TerminateProcess} which kills immediately without running shutdown hooks. To
	 * allow agents like Joular Code Java to flush their results, this method uses
	 * {@code taskkill /PID} (without {@code /F}) on Windows first.
	 *
	 * <p>
	 * For better results on Windows, call {@link #requestGracefulShutdown(String)} before
	 * this method to trigger a Spring Boot Actuator shutdown that runs JVM shutdown
	 * hooks.
	 */
	public void stop(Process process) throws InterruptedException {
		if (process == null || !process.isAlive()) {
			return;
		}

		long pid = process.pid();
		LOG.fine(() -> "Stopping application (PID " + pid + ")");

		if (isWindows()) {
			try {
				// Use the well-known absolute path to avoid reading WINDIR from the
				// environment into a command argument.
				String taskkill = "C:\\Windows\\System32\\taskkill.exe";
				new ProcessBuilder(taskkill, "/PID", String.valueOf(pid)).redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.start()
					.waitFor(10, TimeUnit.SECONDS);
			}
			catch (IOException ex) {
				LOG.fine(() -> "taskkill failed, falling back to destroy: " + ex.getMessage());
				process.destroy();
			}
		}
		else {
			process.destroy();
		}

		boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		if (!exited) {
			LOG.warning("Application did not stop in 30 s — force-killing");
			process.destroyForcibly();
		}
		else {
			LOG.fine(() -> "Application stopped (exit code " + process.exitValue() + ")");
		}
	}

	/**
	 * Requests a graceful shutdown via the Spring Boot Actuator shutdown endpoint.
	 *
	 * <p>
	 * This is particularly useful on Windows where {@link Process#destroy()} and
	 * {@code taskkill} cannot trigger JVM shutdown hooks for console processes. When Java
	 * agents like Joular Code Java are attached, a graceful shutdown ensures their
	 * shutdown hooks run and result files are written.
	 *
	 * <p>
	 * Call this method <em>before</em> {@link #stop(Process)} to give the application
	 * time to shut down cleanly.
	 * @param baseUrl the application's base URL (e.g. {@code http://localhost:8080})
	 * @return {@code true} if the shutdown was accepted (HTTP 2xx), {@code false}
	 * otherwise
	 */
	public boolean requestGracefulShutdown(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return false;
		}
		String shutdownUrl = baseUrl + "/actuator/shutdown";
		LOG.fine(() -> "Requesting graceful shutdown via " + shutdownUrl);

		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(shutdownUrl))
				.timeout(Duration.ofSeconds(5))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();

			HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				LOG.info(() -> "Graceful shutdown requested via Actuator (HTTP " + response.statusCode() + "): "
						+ response.body());
				return true;
			}
			LOG.fine(() -> "Actuator shutdown returned HTTP " + response.statusCode() + ": " + response.body());
		}
		catch (ConnectException ex) {
			LOG.fine(() -> "Application already stopped or shutdown endpoint not available");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			LOG.fine(() -> "Actuator shutdown request interrupted: " + ex.getMessage());
		}
		catch (IOException ex) {
			LOG.fine(() -> "Actuator shutdown request failed: " + ex.getMessage());
		}
		return false;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/**
	 * Returns the path to the Java executable for the JVM currently running this plugin.
	 *
	 * <p>
	 * Uses {@link ProcessHandle#current()} to obtain the executable directly from the
	 * running process rather than constructing a path from the {@code java.home} system
	 * property, which avoids propagating a system-property value as an OS command.
	 * @return the absolute path to the current JVM executable
	 * @throws IOException if the JVM executable path cannot be determined
	 */
	private static String findCurrentJavaExecutable() throws IOException {
		return ProcessHandle.current()
			.info()
			.command()
			.filter(cmd -> !cmd.isBlank())
			.orElseThrow(() -> new IOException("Cannot determine the current JVM executable path via ProcessHandle. "
					+ "Ensure the JVM has permissions to query process information."));
	}

}
