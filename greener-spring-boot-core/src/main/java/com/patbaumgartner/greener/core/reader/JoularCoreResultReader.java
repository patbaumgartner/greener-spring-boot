package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Reads the CSV file produced by
 * <a href="https://www.noureddine.org/research/joular/joularcore">Joular Core</a> and
 * converts the power samples into an {@link EnergyReport}.
 *
 * <h2>Joular Core CSV format</h2> Joular Core writes one row per sample interval
 * (default: 1 second): <pre>
 * timestamp,cpu_power,gpu_power,total_power,cpu_usage,pid_or_app_power
 * 1700000001,45.2,0.0,45.2,23.1,12.3
 * 1700000002,47.8,0.0,47.8,25.4,14.1
 * ...
 * </pre> Columns:
 * <ul>
 * <li>{@code timestamp} — Unix epoch seconds</li>
 * <li>{@code cpu_power} — total CPU power in Watts</li>
 * <li>{@code gpu_power} — total GPU power in Watts (0 when not monitored)</li>
 * <li>{@code total_power} — cpu_power + gpu_power</li>
 * <li>{@code cpu_usage} — CPU utilisation percentage</li>
 * <li>{@code pid_or_app_power}— power attributed to the monitored PID / app (Watts)</li>
 * </ul>
 *
 * <h2>Energy calculation</h2> Energy (J) = Σ power_watts × sample_interval_seconds. Since
 * joularcore emits one row per second, sample_interval = 1 s, so: total_energy_J = Σ
 * pid_or_app_power.
 */
public class JoularCoreResultReader {

	private static final Logger LOG = Logger.getLogger(JoularCoreResultReader.class.getName());

	/**
	 * CSV column indices (zero-based). Joular Core may include a header row; the reader
	 * detects it automatically.
	 */
	private static final int COL_TIMESTAMP = 0;

	private static final int COL_CPU_POWER = 1;

	private static final int COL_GPU_POWER = 2;

	private static final int COL_TOTAL_POWER = 3;

	private static final int COL_CPU_USAGE = 4;

	private static final int COL_PID_OR_APP_POWER = 5;

	/**
	 * Parses the Joular Core CSV file and returns an {@link EnergyReport}.
	 *
	 * <p>
	 * The {@code appIdentifier} (PID or app name) is stored as the measurement's method
	 * name so downstream reporters can display it.
	 * @param csvFile path to the CSV file written by joularcore
	 * @param runId logical run identifier (e.g. git SHA or build number)
	 * @param durationSeconds how long the measurement window lasted
	 * @param appIdentifier the PID or application name that was monitored
	 * @return an {@link EnergyReport} containing process-level energy data
	 * @throws IOException if the CSV file cannot be read
	 */
	public EnergyReport readResults(Path csvFile, String runId, long durationSeconds, String appIdentifier)
			throws IOException {
		if (!Files.exists(csvFile)) {
			LOG.warning("Joular Core CSV file not found: " + csvFile);
			return EnergyReport.of(runId, Instant.now(), durationSeconds, List.of());
		}

		List<String> lines = Files.readAllLines(csvFile);
		List<PowerSample> samples = new ArrayList<>();

		for (String line : lines) {
			line = line.strip();
			if (line.isEmpty() || isHeaderLine(line)) {
				continue;
			}
			PowerSample sample = parseLine(line);
			if (sample != null) {
				samples.add(sample);
			}
		}

		if (samples.isEmpty()) {
			LOG.warning("No valid samples found in Joular Core CSV: " + csvFile);
			return EnergyReport.of(runId, Instant.now(), durationSeconds, List.of());
		}

		LOG.info("Read " + samples.size() + " power samples from Joular Core CSV: " + csvFile);

		// Energy = Σ power × Δt. Joular Core emits one row per second, so Δt = 1 s.
		double totalCpuEnergyJ = samples.stream().mapToDouble(s -> s.cpuPower).sum();
		double totalAppEnergyJ = samples.stream().mapToDouble(s -> s.pidOrAppPower).sum();
		double avgCpuPowerW = samples.stream().mapToDouble(s -> s.cpuPower).average().orElse(0);
		double avgAppPowerW = samples.stream().mapToDouble(s -> s.pidOrAppPower).average().orElse(0);

		LOG.info(String.format("Energy summary — total CPU: %.2f J (avg %.2f W), app share: %.2f J (avg %.2f W)",
				totalCpuEnergyJ, avgCpuPowerW, totalAppEnergyJ, avgAppPowerW));

		List<EnergyMeasurement> measurements = new ArrayList<>();

		// App / PID energy (primary measurement used for comparison)
		if (totalAppEnergyJ > 0) {
			measurements.add(new EnergyMeasurement(appIdentifier + " [app]", totalAppEnergyJ));
		}
		// Total CPU energy (informational)
		measurements.add(new EnergyMeasurement("system [total cpu]", totalCpuEnergyJ));

		return EnergyReport.of(runId, Instant.now(), durationSeconds, measurements);
	}

	/**
	 * Determines the effective "app energy" to use for baseline comparison. If the
	 * monitored app's power was recorded ({@code pid_or_app_power > 0}), that is used;
	 * otherwise the total CPU energy is used as a proxy.
	 */
	public double resolveComparisonEnergy(EnergyReport report, String appIdentifier) {
		return report.measurements()
			.stream()
			.filter(m -> m.methodName().startsWith(appIdentifier))
			.findFirst()
			.map(EnergyMeasurement::energyJoules)
			.orElse(report.totalEnergyJoules());
	}

	// -------------------------------------------------------------------------

	/**
	 * Returns {@code true} when the line looks like a header (non-numeric first column).
	 */
	private boolean isHeaderLine(String line) {
		String first = line.split(",")[0].strip();
		try {
			Long.parseLong(first);
			return false;
		}
		catch (NumberFormatException e) {
			return true;
		}
	}

	private PowerSample parseLine(String line) {
		String[] cols = line.split(",");
		if (cols.length < COL_PID_OR_APP_POWER + 1) {
			// Possibly a 4-column or 5-column variant; try to extract what we can
			if (cols.length >= COL_TOTAL_POWER + 1) {
				try {
					double cpuPower = Double.parseDouble(cols[COL_CPU_POWER].strip());
					double totalPower = Double.parseDouble(cols[COL_TOTAL_POWER].strip());
					return new PowerSample(cpuPower, 0, totalPower, 0);
				}
				catch (NumberFormatException e) {
					return null;
				}
			}
			LOG.fine("Skipping short CSV line: " + line);
			return null;
		}
		try {
			double cpuPower = Double.parseDouble(cols[COL_CPU_POWER].strip());
			double gpuPower = Double.parseDouble(cols[COL_GPU_POWER].strip());
			double totalPower = Double.parseDouble(cols[COL_TOTAL_POWER].strip());
			double pidOrAppPower = Double.parseDouble(cols[COL_PID_OR_APP_POWER].strip());
			return new PowerSample(cpuPower, gpuPower, totalPower, pidOrAppPower);
		}
		catch (NumberFormatException e) {
			LOG.fine("Skipping malformed CSV line: " + line);
			return null;
		}
	}

	/** Immutable value object for a single Joular Core power reading. */
	private record PowerSample(double cpuPower, double gpuPower, double totalPower, double pidOrAppPower) {
	}

}
