package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Reads the CSV result files produced by JoularJX and aggregates them into an
 * {@link EnergyReport} with per-method energy data.
 *
 * <p>
 * JoularJX is an optional Java agent that provides method-level energy granularity on top
 * of Joular Core. When only Joular Core is used (process-level monitoring), this reader
 * is not invoked.
 *
 * <h2>Expected directory structure</h2>
 *
 * <pre>
 * joularjx-result/
 *   {appName}-{PID}-{timestamp}/
 *     app/total/methods/
 *       *.csv   ← total energy per method (filtered by filter-method-names)
 *     all/total/methods/
 *       *.csv   ← total energy for ALL methods
 * </pre>
 *
 * <h2>CSV format</h2>
 *
 * <pre>
 * fully.qualified.ClassName.methodName,energyInJoules
 * </pre>
 */
public class JoularJxResultReader {

	private static final Logger LOG = Logger.getLogger(JoularJxResultReader.class.getName());

	/**
	 * Reads JoularJX results from the given root directory.
	 *
	 * <p>
	 * Prefers filtered app results ({@code app/total/methods/}), then falls back to
	 * all-methods results ({@code all/total/methods/}). If no total data is available
	 * (e.g. because the JVM was killed without running shutdown hooks), falls back to
	 * runtime data ({@code app/runtime/methods/} or {@code all/runtime/methods/}).
	 * @param joularJxResultsRoot the root {@code joularjx-result} directory
	 * @param runId a logical identifier for this run
	 * @param durationSeconds how long the measurement ran
	 * @return an {@link EnergyReport} aggregated from all found CSV files
	 */
	public EnergyReport readResults(Path joularJxResultsRoot, String runId, long durationSeconds) throws IOException {

		if (!Files.isDirectory(joularJxResultsRoot)) {
			LOG.log(Level.WARNING, () -> "JoularJX results directory does not exist: " + joularJxResultsRoot);
			return EnergyReport.of(runId, Instant.now(), durationSeconds, List.of());
		}

		Path runDir = findLatestRunDirectory(joularJxResultsRoot);
		if (runDir == null) {
			LOG.log(Level.WARNING, () -> "No JoularJX run directory found under: " + joularJxResultsRoot);
			return EnergyReport.of(runId, Instant.now(), durationSeconds, List.of());
		}

		LOG.log(Level.INFO, () -> "Reading JoularJX results from: " + runDir);

		// Prefer filtered 'app' results; fall back to 'all'
		Path methodsDir = runDir.resolve("app/total/methods");
		if (!Files.isDirectory(methodsDir) || isEmptyDirectory(methodsDir)) {
			methodsDir = runDir.resolve("all/total/methods");
		}
		// Fall back to runtime data when total data is unavailable
		// (e.g. on Windows where process termination skips JVM shutdown hooks)
		if (!Files.isDirectory(methodsDir) || isEmptyDirectory(methodsDir)) {
			methodsDir = runDir.resolve("app/runtime/methods");
		}
		if (!Files.isDirectory(methodsDir) || isEmptyDirectory(methodsDir)) {
			methodsDir = runDir.resolve("all/runtime/methods");
		}

		List<EnergyMeasurement> measurements = new ArrayList<>();

		if (Files.isDirectory(methodsDir)) {
			try (Stream<Path> csvFiles = Files.list(methodsDir)) {
				csvFiles.filter(p -> p.toString().endsWith(".csv")).forEach(csv -> {
					try {
						measurements.addAll(parseCsv(csv));
					}
					catch (IOException e) {
						LOG.log(Level.WARNING, () -> "Failed to parse CSV file " + csv + ": " + e.getMessage());
					}
				});
			}
		}
		else {
			LOG.log(Level.WARNING, () -> "JoularJX methods directory not found under: " + runDir);
		}

		LOG.log(Level.INFO, () -> "Read " + measurements.size() + " method measurements from JoularJX results");

		// Aggregate by method name — runtime data may contain duplicate entries
		// (one per second) that need to be summed into totals
		List<EnergyMeasurement> aggregated = aggregateByMethod(measurements);

		return EnergyReport.of(runId, Instant.now(), durationSeconds, aggregated);
	}

	private List<EnergyMeasurement> aggregateByMethod(List<EnergyMeasurement> measurements) {
		Map<String, Double> energyByMethod = new LinkedHashMap<>();
		for (EnergyMeasurement m : measurements) {
			energyByMethod.merge(m.methodName(), m.energyJoules(), Double::sum);
		}
		if (energyByMethod.size() == measurements.size()) {
			return measurements;
		}
		return energyByMethod.entrySet().stream().map(e -> new EnergyMeasurement(e.getKey(), e.getValue())).toList();
	}

	private static final int CSV_COLUMN_COUNT = 2;

	/**
	 * Parses a single JoularJX CSV file into energy measurements.
	 *
	 * <p>
	 * Supports the standard two-column format: {@code methodName,energyJoules}.
	 */
	List<EnergyMeasurement> parseCsv(Path csvFile) throws IOException {
		List<String> lines = Files.readAllLines(csvFile);
		List<EnergyMeasurement> measurements = new ArrayList<>();

		for (String line : lines) {
			String trimmedLine = line.strip();
			if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
				continue;
			}
			String[] parts = trimmedLine.split(",", 2);
			if (parts.length == CSV_COLUMN_COUNT) {
				try {
					String method = parts[0].strip();
					double energy = Double.parseDouble(parts[1].strip());
					if (!method.isBlank() && energy >= 0) {
						measurements.add(new EnergyMeasurement(method, energy));
					}
				}
				catch (NumberFormatException e) {
					LOG.log(Level.FINE, () -> "Skipping non-numeric line in " + csvFile + ": " + trimmedLine);
				}
			}
		}
		return measurements;
	}

	private Path findLatestRunDirectory(Path resultsRoot) throws IOException {
		try (Stream<Path> dirs = Files.list(resultsRoot)) {
			return dirs.filter(Files::isDirectory).max((a, b) -> {
				try {
					return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
				}
				catch (IOException e) {
					return 0;
				}
			}).orElse(null);
		}
	}

	private boolean isEmptyDirectory(Path dir) {
		try (Stream<Path> entries = Files.list(dir)) {
			return entries.noneMatch(p -> p.toString().endsWith(".csv") && isNonEmptyFile(p));
		}
		catch (IOException ex) {
			return true;
		}
	}

	private static boolean isNonEmptyFile(Path file) {
		try {
			return Files.size(file) > 0;
		}
		catch (IOException ex) {
			return false;
		}
	}

}
