package com.patbaumgartner.greener.core.config;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.runner.TrainingRunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Shared utilities used by both Maven and Gradle plugins to resolve runtime context.
 */
public final class PluginDefaults {

	private static final Logger LOG = Logger.getLogger(PluginDefaults.class.getName());

	private static final int EXPECTED_JAR_COUNT = 1;

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private PluginDefaults() {
	}

	/**
	 * Builds a short run identifier from the environment. Uses the first 8 characters of
	 * {@code GITHUB_SHA} when available, otherwise falls back to the current timestamp.
	 */
	public static String buildRunId() {
		String sha = System.getenv("GITHUB_SHA");
		return sha != null && !sha.isBlank() ? sha.substring(0, Math.min(sha.length(), 8))
				: String.valueOf(System.currentTimeMillis());
	}

	/**
	 * Resolves the {@link PowerSource} from system properties, environment variables, or
	 * the VM mode flag, in that order of precedence.
	 * <ol>
	 * <li>System property {@code greener.powerSource}</li>
	 * <li>Environment variable {@code POWER_SOURCE}</li>
	 * <li>{@link PowerSource#detect(boolean)} based on {@code vmMode}</li>
	 * </ol>
	 */
	public static PowerSource resolvePowerSource(boolean vmMode) {
		String override = System.getProperty("greener.powerSource");
		if (override != null && !override.isBlank()) {
			return PowerSource.fromString(override);
		}
		String envOverride = System.getenv("POWER_SOURCE");
		if (envOverride != null && !envOverride.isBlank()) {
			return PowerSource.fromString(envOverride);
		}
		return PowerSource.detect(vmMode);
	}

	/**
	 * Normalises a user-supplied string by returning {@code null} for null, blank, or
	 * unresolved Maven property expressions (strings starting with {@code $&#123;}).
	 * @param value the raw input
	 * @return the trimmed value, or {@code null} if it should be treated as absent
	 */
	public static String normalise(String value) {
		return (value == null || value.isBlank() || value.startsWith("${")) ? null : value;
	}

	/**
	 * Builds the effective application arguments list by copying user-provided arguments
	 * and appending the Spring Boot health-probe enablement flag.
	 * @param userArgs optional user-supplied arguments (may be {@code null})
	 * @return a mutable list of effective arguments
	 */
	public static List<String> buildEffectiveAppArgs(List<String> userArgs) {
		return buildEffectiveAppArgs(userArgs, false);
	}

	/**
	 * Builds the effective application arguments list by copying user-provided arguments,
	 * appending the Spring Boot health-probe enablement flag, and optionally enabling the
	 * Actuator shutdown endpoint for graceful JVM shutdown on Windows.
	 * @param userArgs optional user-supplied arguments (may be {@code null})
	 * @param enableShutdownEndpoint whether to enable the Actuator shutdown endpoint
	 * (typically when JoularJX is attached for method-level monitoring)
	 * @return a mutable list of effective arguments
	 */
	public static List<String> buildEffectiveAppArgs(List<String> userArgs, boolean enableShutdownEndpoint) {
		return buildEffectiveAppArgs(userArgs, enableShutdownEndpoint, null);
	}

	/**
	 * Builds the effective application arguments list by copying user-provided arguments,
	 * appending the Spring Boot health-probe enablement flag, optionally enabling the
	 * Actuator shutdown endpoint, and auto-injecting {@code --server.port} from the
	 * {@code baseUrl} when not already specified in user args.
	 *
	 * <p>
	 * This ensures the Spring Boot application listens on the same port that the plugin
	 * uses for health checks and workload tools, enabling random port assignment for
	 * stable CI runs.
	 * @param userArgs optional user-supplied arguments (may be {@code null})
	 * @param enableShutdownEndpoint whether to enable the Actuator shutdown endpoint
	 * @param baseUrl the base URL (e.g. {@code http://localhost:9123}); when non-null and
	 * no {@code --server.port} is present in {@code userArgs}, the port is extracted and
	 * appended automatically
	 * @return a mutable list of effective arguments
	 */
	public static List<String> buildEffectiveAppArgs(List<String> userArgs, boolean enableShutdownEndpoint,
			String baseUrl) {
		List<String> effective = new ArrayList<>();
		if (userArgs != null) {
			effective.addAll(userArgs);
		}
		if (baseUrl != null && effective.stream().noneMatch(a -> a.startsWith("--server.port="))) {
			int port = extractPort(baseUrl);
			if (port > 0 && port != 8080) {
				effective.add("--server.port=" + port);
			}
		}
		effective.add("--management.endpoint.health.probes.enabled=true");
		if (enableShutdownEndpoint) {
			effective.add("--management.endpoint.shutdown.enabled=true");
			effective.add("--management.endpoints.web.exposure.include=health,shutdown");
		}
		return effective;
	}

	private static int extractPort(String baseUrl) {
		try {
			int port = URI.create(baseUrl).getPort();
			return port > 0 ? port : ("https".equals(URI.create(baseUrl).getScheme()) ? 443 : 80);
		}
		catch (IllegalArgumentException ex) {
			return -1;
		}
	}

	/**
	 * Auto-detects a single Spring Boot fat-jar in the given directory, excluding common
	 * non-executable jars (sources, javadoc, tests, plain, original).
	 * @param directory the directory to scan (e.g. {@code target/} or
	 * {@code build/libs/})
	 * @param additionalExclusions extra filename suffixes to exclude (e.g.
	 * {@code "-plain.jar"})
	 * @return the single detected jar wrapped in an {@link Optional}, or empty if the
	 * directory doesn't exist
	 * @throws IllegalStateException if zero or multiple jars are found
	 */
	public static Optional<File> autoDetectJar(Path directory, String... additionalExclusions) {
		File dir = directory.toFile();
		if (!dir.isDirectory()) {
			return Optional.empty();
		}

		File[] jars = dir.listFiles((d, name) -> {
			if (!name.endsWith(".jar")) {
				return false;
			}
			if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar") || name.endsWith("-tests.jar")
					|| name.contains(".original")) {
				return false;
			}
			for (String excl : additionalExclusions) {
				if (name.endsWith(excl)) {
					return false;
				}
			}
			return true;
		});

		if (jars == null || jars.length == 0) {
			throw new IllegalStateException("No jar found in " + dir);
		}
		if (jars.length > EXPECTED_JAR_COUNT) {
			throw new IllegalStateException("Multiple jars found in " + dir + ": "
					+ Arrays.stream(jars).map(File::getName).collect(Collectors.joining(", ")));
		}

		return Optional.of(jars[0]);
	}

	/**
	 * Formats baseline update summary lines for consistent output across both plugins.
	 * @param baselineFile the updated baseline file path
	 * @param commitSha the recorded commit SHA (may be {@code null})
	 * @param branch the recorded branch name (may be {@code null})
	 * @param totalEnergyJoules the total energy in joules
	 * @return list of formatted summary lines
	 */
	public static List<String> formatBaselineUpdateSummary(Path baselineFile, String commitSha, String branch,
			double totalEnergyJoules) {
		List<String> lines = new ArrayList<>();
		lines.add("Energy baseline updated: " + baselineFile);
		lines.add("  commit : " + (commitSha != null ? commitSha : "n/a"));
		lines.add("  branch : " + (branch != null ? branch : "n/a"));
		lines.add("  energy : " + String.format("%.2f J", totalEnergyJoules));
		return lines;
	}

	/**
	 * Normalises VCS metadata, saves the baseline, and logs a summary.
	 * @param manager the baseline manager
	 * @param report the energy report to save
	 * @param commitSha raw commit SHA (may be {@code null} or unresolved)
	 * @param branch raw branch name (may be {@code null} or unresolved)
	 * @param baselineFile target baseline file path
	 * @param logger callback for log output
	 */
	public static void saveAndLogBaseline(BaselineManager manager, EnergyReport report, String commitSha, String branch,
			Path baselineFile, Consumer<String> logger) throws IOException {
		String sha = normalise(commitSha);
		String br = normalise(branch);

		manager.saveBaseline(report, sha, br, baselineFile);

		for (String line : formatBaselineUpdateSummary(baselineFile, sha, br, report.totalEnergyJoules())) {
			logger.accept(line);
		}
	}

	/**
	 * Derives a tool name from the configured training script file or command string.
	 * Used to create a tool-specific subdirectory under the report output directory.
	 * @param scriptFile optional external training script file (may be {@code null})
	 * @param command optional external training command (may be {@code null})
	 * @return the derived tool name, or {@code "measurement"} as a fallback
	 */
	public static String resolveToolName(File scriptFile, String command) {
		if (scriptFile != null && scriptFile.exists()) {
			String fileName = scriptFile.getName();
			String parentDir = scriptFile.getParentFile() != null ? scriptFile.getParentFile().getName() : null;
			return TrainingRunner.deriveToolName(fileName, parentDir);
		}
		if (command != null && !command.isBlank()) {
			return TrainingRunner.deriveToolName(command, null);
		}
		return "measurement";
	}

	/**
	 * Appends a {@code yyyyMMdd-HHmmss} timestamp to the given base directory to create a
	 * unique run-specific report directory.
	 * @param baseDir the base report output directory
	 * @return a new path with the timestamp appended (e.g.
	 * {@code target/greener-reports-20260404-153012})
	 */
	public static Path buildTimestampedDir(Path baseDir) {
		String dirName = baseDir.getFileName().toString();
		String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
		return baseDir.resolveSibling(dirName + "-" + timestamp);
	}

	/**
	 * Creates or updates a {@code latest} symlink pointing to the given target directory.
	 * If the platform does not support symbolic links, a warning is logged and the
	 * symlink is skipped.
	 * @param targetDir the directory the symlink should point to
	 * @param linkName the name of the symlink (created as a sibling of {@code targetDir})
	 */
	public static void createLatestLink(Path targetDir, String linkName) {
		Path link = targetDir.resolveSibling(linkName);
		try {
			Files.deleteIfExists(link);
			Files.createSymbolicLink(link, targetDir);
		}
		catch (UnsupportedOperationException e) {
			LOG.log(Level.WARNING, "Symbolic links not supported on this platform. Skipping latest link.");
		}
		catch (IOException e) {
			LOG.log(Level.WARNING, () -> "Could not create latest symlink: " + e.getMessage());
		}
	}

	/**
	 * Validates that the measurement duration is positive.
	 * @param seconds the configured measurement duration
	 * @throws IllegalArgumentException if the duration is zero or negative
	 */
	public static void validateMeasureDuration(int seconds) {
		if (seconds <= 0) {
			throw new IllegalArgumentException("measureDurationSeconds must be > 0");
		}
	}

	/**
	 * Validates that the external training script file exists, if configured.
	 * @param scriptFile the configured script file (may be {@code null})
	 * @throws IllegalArgumentException if the file is non-null but does not exist
	 */
	public static void validateExternalScript(File scriptFile) {
		if (scriptFile != null && !scriptFile.exists()) {
			throw new IllegalArgumentException("Configured externalTrainingScriptFile does not exist: " + scriptFile);
		}
	}

	/**
	 * Ensures that the given JoularJX config file contains the
	 * {@code joular-core-parameters} property. If the property is missing, this method
	 * probes the Joular Core binary to determine which power component delivers non-zero
	 * readings, writes an augmented copy of the configuration to
	 * {@code joularjx-config-effective.properties} in the working directory, and returns
	 * the path to the new file. If the property is already set, the original path is
	 * returned unchanged.
	 * @param joularJxConfig path to the JoularJX config file (may be {@code null})
	 * @param joularCoreBinary path to the Joular Core binary
	 * @param workingDir working directory for writing the augmented config
	 * @return the effective config path, or {@code null} if the input was {@code null}
	 */
	public static Path ensureJoularCoreParameters(Path joularJxConfig, Path joularCoreBinary, Path workingDir) {
		if (joularJxConfig == null) {
			return null;
		}
		try {
			Properties props = new Properties();
			try (BufferedReader reader = Files.newBufferedReader(joularJxConfig)) {
				props.load(reader);
			}
			if (props.getProperty("joular-core-parameters") != null) {
				return joularJxConfig;
			}
			String parameters = resolveJoularCoreParameters(joularCoreBinary);
			props.setProperty("joular-core-parameters", parameters);
			LOG.info(() -> "Auto-detected joular-core-parameters=" + parameters);

			Files.createDirectories(workingDir);
			Path effectiveConfig = workingDir.resolve("joularjx-config-effective.properties");
			try (OutputStream out = Files.newOutputStream(effectiveConfig)) {
				props.store(out, "Augmented by greener-spring-boot with auto-detected joular-core-parameters");
			}
			return effectiveConfig;
		}
		catch (IOException e) {
			LOG.log(Level.WARNING, () -> "Could not augment JoularJX config: " + e.getMessage());
			return joularJxConfig;
		}
	}

	/**
	 * Probes the Joular Core binary to determine which power component delivers non-zero
	 * readings. JoularJX reads power from its internal Joular Core instance via stdout
	 * and distributes it across threads. On systems where CPU-specific RAPL is
	 * unavailable (e.g. Windows without the PP0 RAPL domain), CPU power is always zero
	 * and JoularJX method-level energy will be zero as well. In that case, this method
	 * detects that GPU power (which typically equals the total system power when
	 * CPU-specific RAPL is missing) is available and returns {@code "gpu"} as a fallback.
	 *
	 * <p>
	 * The returned value is suitable for use in a JoularJX {@code config.properties}
	 * file:
	 *
	 * <pre>
	 * joular-core-parameters=-c &lt;component&gt; -i
	 * </pre>
	 * @param joularCoreBinary path to the Joular Core binary
	 * @return {@code "cpu"} if CPU power is available, {@code "gpu"} if only GPU power is
	 * available, or {@code "cpu"} as a default if neither delivers non-zero readings
	 */
	public static String probeJoularCoreComponent(Path joularCoreBinary) {
		if (probeComponentHasPower(joularCoreBinary, "cpu")) {
			LOG.info("Joular Core probe: CPU power available");
			return "cpu";
		}
		if (probeComponentHasPower(joularCoreBinary, "gpu")) {
			LOG.warning("Joular Core probe: CPU power unavailable, falling back to GPU (total system power)");
			return "gpu";
		}
		LOG.warning("Joular Core probe: neither CPU nor GPU power available"
				+ " -- JoularJX method-level data may be zero");
		return "cpu";
	}

	/**
	 * Builds the {@code joular-core-parameters} value for a JoularJX
	 * {@code config.properties} file based on the result of
	 * {@link #probeJoularCoreComponent(Path)}.
	 * @param joularCoreBinary path to the Joular Core binary
	 * @return the parameter string (e.g. {@code "-c cpu -i"} or {@code "-c gpu -i"})
	 */
	public static String resolveJoularCoreParameters(Path joularCoreBinary) {
		return "-c " + probeJoularCoreComponent(joularCoreBinary) + " -i";
	}

	private static boolean probeComponentHasPower(Path binary, String component) {
		try {
			ProcessBuilder pb = new ProcessBuilder(binary.toAbsolutePath().toString(), "-c", component, "-i");
			pb.redirectErrorStream(false);
			Process process = pb.start();

			boolean hasPower = false;
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
				long deadline = System.currentTimeMillis() + 3_000;
				while (System.currentTimeMillis() < deadline) {
					if (reader.ready()) {
						String line = reader.readLine();
						if (line == null) {
							break;
						}
						try {
							double value = Double.parseDouble(line.trim());
							if (value > 0) {
								hasPower = true;
								break;
							}
						}
						catch (NumberFormatException ignored) {
							// Skip non-numeric lines (e.g. warnings)
						}
					}
					else {
						Thread.sleep(100);
					}
				}
			}

			process.destroyForcibly();
			process.waitFor(5, TimeUnit.SECONDS);
			return hasPower;
		}
		catch (IOException | InterruptedException e) {
			LOG.log(Level.FINE,
					() -> "Joular Core power probe failed for component '" + component + "': " + e.getMessage());
			return false;
		}
	}

}
