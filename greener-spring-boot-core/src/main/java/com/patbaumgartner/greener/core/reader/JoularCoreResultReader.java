package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.exception.EnergyMeasurementException;
import com.patbaumgartner.greener.core.exception.EnergyMeasurementException.Hint;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the CSV file produced by <a href="https://github.com/joular/joularcore">Joular
 * Core</a> and converts the power samples into an {@link EnergyReport}.
 *
 * <h2>Joular Core CSV format</h2> Joular Core writes one row per sample interval
 * (default: 1 second). A header row is required; column order may vary by version:
 *
 * <pre>
 * Timestamp,Total Power (W), CPU Power (W),GPU Power (W),CPU Usage (%),Process Power (W)
 * 1700000001,45.2,30.0,15.2,23.1,12.3
 * </pre>
 *
 * The reader detects the header automatically and maps columns by name (matching both
 * human-readable headers like {@code "CPU Power (W)"} and snake_case headers like
 * {@code "cpu_power"}).
 *
 * <h2>Energy calculation</h2> Energy (J) = Σ power_watts × sample_interval_seconds. Since
 * joularcore emits one row per second, sample_interval = 1 s, so: total_energy_J = Σ
 * pid_or_app_power.
 */
public class JoularCoreResultReader {

	private static final Logger LOG = Logger.getLogger(JoularCoreResultReader.class.getName());

	/**
	 * Default CSV column indices (zero-based) used as fallbacks when the header omits a
	 * column the reader expects:
	 * timestamp,cpu_power,gpu_power,total_power,cpu_usage,app_power.
	 */
	private static final int DEFAULT_COL_CPU_POWER = 1;

	private static final int DEFAULT_COL_GPU_POWER = 2;

	private static final int DEFAULT_COL_TOTAL_POWER = 3;

	private static final int DEFAULT_COL_PID_OR_APP_POWER = 5;

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
			throw new EnergyMeasurementException(Hint.EMPTY_OR_MISSING_CSV,
					String.join("\n", "Joular Core CSV file not found: " + csvFile, "  Likely causes:",
							"    \u2022 Joular Core failed to start (missing binary or unsupported OS).",
							"    \u2022 The Spring Boot application exited before any sample was written.",
							"    \u2022 The configured measureDurationSeconds is too short (< 1 s)."));
		}

		List<String> lines = Files.readAllLines(csvFile);
		List<PowerSample> samples = new ArrayList<>();
		ColumnMapping mapping = null;

		for (String line : lines) {
			String trimmedLine = line.strip();
			if (trimmedLine.isEmpty()) {
				continue;
			}
			if (isHeaderLine(trimmedLine)) {
				mapping = parseHeader(trimmedLine);
				continue;
			}
			if (mapping == null) {
				throw new EnergyMeasurementException(Hint.EMPTY_OR_MISSING_CSV,
						"Joular Core CSV is missing a header row: " + csvFile);
			}
			PowerSample sample = parseLine(trimmedLine, mapping);
			if (sample != null) {
				samples.add(sample);
			}
		}

		if (samples.isEmpty()) {
			throw new EnergyMeasurementException(Hint.EMPTY_OR_MISSING_CSV,
					String.join("\n", "Joular Core CSV contains no valid power samples: " + csvFile, "  Likely causes:",
							"    \u2022 RAPL is not readable (missing 'msr' kernel module on Linux,",
							"      or non-root access to /sys/class/powercap/intel-rapl:0/energy_uj).",
							"    \u2022 The CPU is not supported by Joular Core (e.g. Apple Silicon).",
							"    \u2022 Running inside a VM/container without --vm mode enabled."));
		}

		LOG.info(() -> "Read " + samples.size() + " power samples from Joular Core CSV: " + csvFile);

		// Energy = Σ power × Δt. Joular Core emits one row per second, so Δt = 1 s.
		double cpuEnergySum = samples.stream().mapToDouble(s -> s.cpuPower).sum();
		double totalAppEnergyJ = samples.stream().mapToDouble(s -> s.pidOrAppPower).sum();

		// Fallback: on some platforms (e.g. Windows with Scaphandre RAPL driver)
		// Joular Core only reports PKG power in "Total Power" while "CPU Power"
		// and "Process Power" stay 0. Use "Total Power" as system-level proxy.
		if (cpuEnergySum == 0) {
			double totalPowerEnergyJ = samples.stream().mapToDouble(s -> s.totalPower).sum();
			if (totalPowerEnergyJ > 0) {
				LOG.info(() -> "CPU Power is 0 — using Total Power as system-level proxy");
				cpuEnergySum = totalPowerEnergyJ;
			}
		}

		final double totalCpuEnergyJ = cpuEnergySum;
		double avgCpuPowerW = totalCpuEnergyJ / Math.max(samples.size(), 1);
		double avgAppPowerW = samples.stream().mapToDouble(s -> s.pidOrAppPower).average().orElse(0);

		LOG.info(() -> String.format("Energy summary - total CPU: %.2f J (avg %.2f W), app share: %.2f J (avg %.2f W)",
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

	/**
	 * Parses a CSV header line and returns a {@link ColumnMapping} based on the column
	 * names. Recognises both the human-readable Joular Core format (e.g.
	 * {@code "Total Power (W)"}) and snake_case headers (e.g. {@code "cpu_power"}).
	 */
	ColumnMapping parseHeader(String headerLine) {
		String[] cols = headerLine.split(",");
		int cpuPower = -1;
		int gpuPower = -1;
		int totalPower = -1;
		int pidOrAppPower = -1;

		for (int i = 0; i < cols.length; i++) {
			String col = cols[i].strip().toLowerCase(java.util.Locale.ROOT);
			if (col.contains("total power") || "total_power".equals(col)) {
				totalPower = i;
			}
			else if (col.contains("cpu power") || "cpu_power".equals(col)) {
				cpuPower = i;
			}
			else if (col.contains("gpu power") || "gpu_power".equals(col)) {
				gpuPower = i;
			}
			else if (col.contains("process power") || col.contains("app power") || "pid_or_app_power".equals(col)) {
				pidOrAppPower = i;
			}
		}

		// Apply default indices for any column not found in the header
		if (cpuPower < 0) {
			cpuPower = DEFAULT_COL_CPU_POWER;
		}
		if (gpuPower < 0) {
			gpuPower = DEFAULT_COL_GPU_POWER;
		}
		if (totalPower < 0) {
			totalPower = DEFAULT_COL_TOTAL_POWER;
		}
		if (pidOrAppPower < 0) {
			pidOrAppPower = DEFAULT_COL_PID_OR_APP_POWER;
		}

		return new ColumnMapping(cpuPower, gpuPower, totalPower, pidOrAppPower);
	}

	private PowerSample parseLine(String line, ColumnMapping mapping) {
		String[] cols = line.split(",");
		int minCols = Math.max(Math.max(mapping.cpuPower, mapping.gpuPower),
				Math.max(mapping.totalPower, mapping.pidOrAppPower)) + 1;

		if (cols.length < minCols) {
			// Try to extract what we can with fewer columns
			if (cols.length > Math.max(mapping.cpuPower, mapping.totalPower)) {
				try {
					double cpu = Double.parseDouble(cols[mapping.cpuPower].strip());
					double total = Double.parseDouble(cols[mapping.totalPower].strip());
					return new PowerSample(cpu, 0, total, 0);
				}
				catch (NumberFormatException e) {
					return null;
				}
			}
			LOG.fine(() -> "Skipping short CSV line: " + line);
			return null;
		}
		try {
			double cpu = Double.parseDouble(cols[mapping.cpuPower].strip());
			double gpu = Double.parseDouble(cols[mapping.gpuPower].strip());
			double total = Double.parseDouble(cols[mapping.totalPower].strip());
			double app = Double.parseDouble(cols[mapping.pidOrAppPower].strip());
			if (cpu < 0 || gpu < 0 || total < 0 || app < 0 || !Double.isFinite(cpu) || !Double.isFinite(gpu)
					|| !Double.isFinite(total) || !Double.isFinite(app)) {
				LOG.fine(() -> "Skipping CSV line with invalid power value: " + line);
				return null;
			}
			return new PowerSample(cpu, gpu, total, app);
		}
		catch (NumberFormatException e) {
			LOG.fine(() -> "Skipping malformed CSV line: " + line);
			return null;
		}
	}

	/** Immutable value object for a single Joular Core power reading. */
	private record PowerSample(double cpuPower, double gpuPower, double totalPower, double pidOrAppPower) {
	}

	/** Maps semantic column roles to actual CSV column indices. */
	record ColumnMapping(int cpuPower, int gpuPower, int totalPower, int pidOrAppPower) {
	}

}
