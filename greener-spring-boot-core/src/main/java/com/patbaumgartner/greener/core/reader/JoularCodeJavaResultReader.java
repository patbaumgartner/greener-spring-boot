package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.MethodLevelReports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads the CSV result files produced by Joular Code Java and aggregates them into an
 * {@link EnergyReport} with per-branch energy data.
 *
 * <p>
 * Joular Code Java is an optional Java agent that provides method-level energy
 * granularity on top of Joular Core. When only Joular Core is used (process-level
 * monitoring), this reader is not invoked.
 *
 * <h2>Expected directory structure</h2>
 *
 * <pre>
 * joular-code-java-results/   ← configurable via {@code
 * results - path
 * } in joularcodejava.properties
 *   methods-power-app.csv     ← power and energy for application-filtered call branches
 *   methods-power-all.csv     ← power and energy for all observed call branches
 * </pre>
 *
 * <h2>CSV format</h2>
 *
 * <pre>
 * timestamp,branch,power_watts,energy_joules,interval_seconds
 * </pre>
 *
 * Where {@code branch} is a semicolon-separated call chain, e.g.:
 * {@code com.example.Main.main;com.example.Service.process}.
 */
public class JoularCodeJavaResultReader {

	private static final Logger LOG = Logger.getLogger(JoularCodeJavaResultReader.class.getName());

	private static final String HEADER_PREFIX = "timestamp";

	private static final int CSV_MIN_COLUMNS = 4;

	private static final int BRANCH_COLUMN = 1;

	private static final int ENERGY_JOULES_COLUMN = 3;

	/**
	 * Reads both filtered (app-only) and unfiltered (all methods) Joular Code Java
	 * results from the given results directory.
	 * @param resultsDir the {@code joular-code-java-results} directory
	 * @param runId a logical identifier for this run
	 * @param durationSeconds how long the measurement ran
	 * @return a {@link MethodLevelReports} containing both app and all reports
	 */
	public MethodLevelReports readAllResults(Path resultsDir, String runId, long durationSeconds) {
		if (!Files.isDirectory(resultsDir)) {
			LOG.warning(() -> "Joular Code Java results directory does not exist: " + resultsDir);
			return new MethodLevelReports(null, null);
		}

		LOG.info(() -> "Reading Joular Code Java results from: " + resultsDir);

		EnergyReport appReport = readCsvFile(resultsDir.resolve("methods-power-app.csv"), runId, durationSeconds);
		EnergyReport allReport = readCsvFile(resultsDir.resolve("methods-power-all.csv"), runId, durationSeconds);

		return new MethodLevelReports(appReport, allReport);
	}

	private EnergyReport readCsvFile(Path csvFile, String runId, long durationSeconds) {
		if (!Files.exists(csvFile)) {
			LOG.fine(() -> "Joular Code Java CSV file not found: " + csvFile);
			return null;
		}

		try {
			List<String> lines = Files.readAllLines(csvFile);
			List<EnergyMeasurement> measurements = parseCsvLines(lines, csvFile);

			LOG.info(() -> "Read " + measurements.size() + " branch measurements from " + csvFile);

			List<EnergyMeasurement> aggregated = aggregateByBranch(measurements);
			return EnergyReport.of(runId, Instant.now(), durationSeconds, aggregated);
		}
		catch (IOException e) {
			LOG.warning(() -> "Failed to read Joular Code Java CSV " + csvFile + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Parses lines from a Joular Code Java CSV file into energy measurements.
	 *
	 * <p>
	 * Skips the header row and any lines with fewer than {@value #CSV_MIN_COLUMNS}
	 * columns. The {@code branch} column (semicolon-separated call chain) is used as the
	 * method name and {@code energy_joules} as the energy value.
	 */
	List<EnergyMeasurement> parseCsvLines(List<String> lines, Path sourceFile) {
		List<EnergyMeasurement> measurements = new ArrayList<>();
		for (String line : lines) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith(HEADER_PREFIX)) {
				continue;
			}
			String[] parts = trimmed.split(",", -1);
			if (parts.length < CSV_MIN_COLUMNS) {
				continue;
			}
			try {
				String branch = parts[BRANCH_COLUMN].strip();
				double energy = Double.parseDouble(parts[ENERGY_JOULES_COLUMN].strip());
				if (!branch.isBlank() && energy >= 0) {
					measurements.add(new EnergyMeasurement(branch, energy));
				}
			}
			catch (NumberFormatException e) {
				LOG.fine(() -> "Skipping non-numeric line in " + sourceFile + ": " + trimmed);
			}
		}
		return measurements;
	}

	private List<EnergyMeasurement> aggregateByBranch(List<EnergyMeasurement> measurements) {
		Map<String, Double> energyByBranch = new LinkedHashMap<>();
		for (EnergyMeasurement m : measurements) {
			energyByBranch.merge(m.methodName(), m.energyJoules(), Double::sum);
		}
		if (energyByBranch.size() == measurements.size()) {
			return measurements;
		}
		return energyByBranch.entrySet().stream().map(e -> new EnergyMeasurement(e.getKey(), e.getValue())).toList();
	}

}
