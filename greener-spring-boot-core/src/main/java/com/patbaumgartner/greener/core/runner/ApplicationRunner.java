package com.patbaumgartner.greener.core.runner;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * Optionally, the JoularJX Java agent can be attached for method-level granularity
 * (JoularJX itself will use Joular Core as its power reading backend when configured to
 * do so).
 */
public class ApplicationRunner {

	private static final Logger LOG = Logger.getLogger(ApplicationRunner.class.getName());

	/**
	 * Starts the Spring Boot application.
	 * @param springBootJar path to the executable Spring Boot fat-jar
	 * @param joularJxJar optional path to the JoularJX Java agent jar ({@code null} to
	 * skip method-level monitoring)
	 * @param joularJxConfig optional path to the generated {@code config.properties} for
	 * JoularJX (used only when {@code joularJxJar} is set)
	 * @param workingDir working directory for the process
	 * @param extraJvmArgs additional JVM arguments (e.g. {@code -Xmx512m})
	 * @param appArgs application arguments forwarded to Spring Boot
	 * @return the started {@link Process}
	 */
	public Process start(Path springBootJar, Path joularJxJar, Path joularJxConfig, Path workingDir,
			List<String> extraJvmArgs, List<String> appArgs) throws IOException {

		Files.createDirectories(workingDir);

		String javaHome = System.getProperty("java.home");
		String javaExecutable = javaHome + "/bin/java";

		List<String> command = new ArrayList<>();
		command.add(javaExecutable);

		if (joularJxJar != null && Files.exists(joularJxJar)) {
			command.add("-javaagent:" + joularJxJar.toAbsolutePath());
			LOG.info("JoularJX agent attached: " + joularJxJar);
		}

		if (joularJxConfig != null && Files.exists(joularJxConfig)) {
			command.add("-Djoularjx.config=" + joularJxConfig.toAbsolutePath());
		}

		if (extraJvmArgs != null) {
			command.addAll(extraJvmArgs);
		}

		command.add("-jar");
		command.add(springBootJar.toAbsolutePath().toString());

		if (appArgs != null) {
			command.addAll(appArgs);
		}

		LOG.info("Starting application: " + String.join(" ", command));

		ProcessBuilder pb = new ProcessBuilder(command).directory(workingDir.toFile())
			.redirectOutput(workingDir.resolve("app-stdout.log").toFile())
			.redirectError(workingDir.resolve("app-stderr.log").toFile());

		Process process = pb.start();
		LOG.info("Application started with PID " + process.pid());
		return process;
	}

	/**
	 * Blocks until the application's health-check endpoint returns HTTP 2xx, or until
	 * {@code timeoutSeconds} elapses.
	 * @throws RuntimeException if the application does not become healthy in time
	 */
	public void waitForStartup(String baseUrl, String healthPath, int timeoutSeconds)
			throws IOException, InterruptedException {

		String healthUrl = baseUrl + healthPath;
		LOG.info("Waiting for application at: " + healthUrl + " (timeout: " + timeoutSeconds + "s)");

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

		long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1_000;

		while (System.currentTimeMillis() < deadline) {
			try {
				HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(healthUrl))
					.timeout(Duration.ofSeconds(3))
					.GET()
					.build();

				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

				if (response.statusCode() >= 200 && response.statusCode() < 300) {
					LOG.info("Application is ready (HTTP " + response.statusCode() + ")");
					return;
				}
			}
			catch (ConnectException ignored) {
				// Expected during startup — keep polling
			}
			Thread.sleep(2_000);
		}

		throw new RuntimeException(
				"Application did not become healthy within " + timeoutSeconds + " seconds at " + healthUrl);
	}

	/**
	 * Gracefully stops the application process (SIGTERM), waiting up to 30 s for a clean
	 * shutdown before forcibly killing it.
	 */
	public void stop(Process process) throws InterruptedException {
		if (process == null || !process.isAlive()) {
			return;
		}

		LOG.info("Stopping application (PID " + process.pid() + ") …");
		process.destroy();

		boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
		if (!exited) {
			LOG.warning("Application did not stop in 30 s — force-killing");
			process.destroyForcibly();
		}
		else {
			LOG.info("Application stopped (exit code " + process.exitValue() + ")");
		}
	}

}
