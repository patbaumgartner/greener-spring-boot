package com.patbaumgartner.greener.core.runner;

import com.patbaumgartner.greener.core.config.JoularCoreConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of the <a href="https://github.com/joular/joularcore">Joular
 * Core</a> native binary that measures the power consumption of the monitored Spring Boot
 * application.
 *
 * <h2>How it works</h2>
 * <ol>
 * <li>After the Spring Boot application has started, call
 * {@link #start(JoularCoreConfig)}. Joular Core begins sampling power at 1-second
 * intervals and appends to a CSV file.</li>
 * <li>The training workload runs; Joular Core records power for each second.</li>
 * <li>Call {@link #stop()} to terminate Joular Core. The CSV file is then ready to be
 * parsed by {@link com.patbaumgartner.greener.core.reader.JoularCoreResultReader}.</li>
 * </ol>
 *
 * <p>
 * Joular Core monitors the application by PID ({@code -p}) or application name
 * ({@code -a}). Using PID is preferred because it avoids ambiguity when multiple Java
 * processes run on the same host.
 */
public class JoularCoreRunner implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(JoularCoreRunner.class.getName());

	private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 15;

	private int shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT_SECONDS;

	private Process joularCoreProcess;

	/**
	 * Sets the maximum number of seconds to wait for Joular Core to terminate gracefully
	 * before force-killing.
	 * @param seconds timeout in seconds (must be positive)
	 * @return this runner for fluent chaining
	 */
	public JoularCoreRunner shutdownTimeoutSeconds(int seconds) {
		this.shutdownTimeout = seconds;
		return this;
	}

	/**
	 * Starts Joular Core with the supplied configuration.
	 * @param config fully populated configuration; {@code binaryPath} must point to the
	 * joularcore binary and {@code outputCsvPath} must be set
	 * @throws IOException if the process cannot be started
	 */
	public void start(JoularCoreConfig config) throws IOException {
		if (config.getBinaryPath() == null || !Files.exists(config.getBinaryPath())) {
			throw new IOException("Joular Core binary not found at: " + config.getBinaryPath()
					+ " - please set joularCoreBinaryPath or enable auto-download.");
		}

		if (config.getOutputCsvPath() == null) {
			throw new IOException("JoularCoreConfig.outputCsvPath must not be null.");
		}

		// Ensure the output directory exists
		Files.createDirectories(config.getOutputCsvPath().getParent());

		List<String> command = config.buildCommand(config.getBinaryPath());
		LOG.fine(() -> "Joular Core command: " + String.join(" ", command));

		ProcessBuilder pb = new ProcessBuilder(command).inheritIO();

		// Set VM environment variables (VM_CPU_POWER_FILE, VM_CPU_POWER_FORMAT)
		Map<String, String> vmEnv = config.buildVmEnvironment();
		if (!vmEnv.isEmpty()) {
			pb.environment().putAll(vmEnv);
			LOG.fine(() -> "VM mode environment: " + vmEnv);
		}

		if (config.isSilent()) {
			// Redirect stderr to /dev/null equivalent when silent; stdout is the CSV
			// stream
			pb.redirectError(ProcessBuilder.Redirect.DISCARD);
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		}

		joularCoreProcess = pb.start();
		LOG.fine(() -> "Joular Core started (PID " + joularCoreProcess.pid() + ")");
	}

	/**
	 * Stops the Joular Core process and waits for it to exit.
	 *
	 * <p>
	 * The CSV file written during the run can now be read by
	 * {@link com.patbaumgartner.greener.core.reader.JoularCoreResultReader}.
	 */
	public void stop() throws InterruptedException {
		if (joularCoreProcess == null || !joularCoreProcess.isAlive()) {
			return;
		}

		LOG.fine(() -> "Stopping Joular Core (PID " + joularCoreProcess.pid() + ")");
		joularCoreProcess.destroy();

		boolean exited = joularCoreProcess.waitFor(shutdownTimeout, TimeUnit.SECONDS);
		if (!exited) {
			LOG.warning(() -> "Joular Core did not stop in " + shutdownTimeout + " s — force-killing");

			joularCoreProcess.destroyForcibly();
		}
		else {
			LOG.fine("Joular Core stopped");
		}
		joularCoreProcess = null; // NOPMD NullAssignment - intentional to prevent
									// double-stop
	}

	@Override
	public void close() {
		try {
			stop();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Returns {@code true} if Joular Core is currently running. */
	public boolean isRunning() {
		return joularCoreProcess != null && joularCoreProcess.isAlive();
	}

}
