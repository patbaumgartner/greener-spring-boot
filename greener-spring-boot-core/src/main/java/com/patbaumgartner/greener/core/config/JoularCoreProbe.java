package com.patbaumgartner.greener.core.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Probes the Joular Core binary to discover which power component delivers non-zero
 * readings and optionally augments a JoularJX configuration file with the detected
 * parameters.
 *
 * <p>
 * This class was extracted from {@link PluginDefaults} to keep the probing logic — which
 * launches subprocesses and performs I/O — separate from the simpler utility methods.
 */
public final class JoularCoreProbe {

	private static final Logger LOG = Logger.getLogger(JoularCoreProbe.class.getName());

	private JoularCoreProbe() {
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
			LOG.warning(() -> "Could not augment JoularJX config: " + e.getMessage());
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
			LOG.fine(() -> "Joular Core power probe failed for component '" + component + "': " + e.getMessage());
			return false;
		}
	}

}
