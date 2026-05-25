package com.patbaumgartner.greener.core.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Probes the Joular Core binary to discover which power component delivers non-zero
 * readings.
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
	 * Probes the Joular Core binary to determine which power component delivers non-zero
	 * readings. On systems where CPU-specific RAPL is unavailable (e.g. Windows without
	 * the PP0 RAPL domain), CPU power is always zero. In that case this method detects
	 * that GPU power (which typically equals the total system power when CPU-specific
	 * RAPL is missing) is available and returns {@code "gpu"} as a fallback.
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
		LOG.warning("Joular Core probe: neither CPU nor GPU power available");
		return "cpu";
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
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOG.fine(() -> "Joular Core power probe interrupted for component '" + component + "'");
			return false;
		}
		catch (IOException e) {
			LOG.fine(() -> "Joular Core power probe failed for component '" + component + "': " + e.getMessage());
			return false;
		}
	}

}
