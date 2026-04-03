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
 * <h2>Expected directory structure</h2> <pre>
 * joularjx-results/
 *   {appName}-{PID}-{timestamp}/
 *     app/total/methods/
 *       *.csv   ← total energy per method (filtered by filter-method-names)
 *     all/total/methods/
 *       *.csv   ← total energy for ALL methods
 * </pre>
 *
 * <h2>CSV format</h2> <pre>
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
	 * all-methods results ({@code all/total/methods/}).
	 * @param joularJxResultsRoot the root {@code joularjx-results} directory
	 * @param runId a logical identifier for this run
	 * @param durationSeconds how long the measurement ran
	 * @return an {@link EnergyReport} aggregated from all found CSV files
	 */
	public EnergyReport readResults(Path joularJxResultsRoot, String runId, long durationSeconds) throws IOException {

		if (!Files.isDirectory(joularJxResultsRoot)) {
			LOG.warning("JoularJX results directory does not exist: " + joularJxResultsRoot);
			return EnergyReport.of(runId, Instant.now(), durationSeconds, List.of());
		}

		Path runDir = findLatestRunDirectory(joularJxResultsRoot);
		if (runDir == null) {
			LOG.warning("No JoularJX run directory found under: " + joularJxResultsRoot);
			return EnergyReport.of(runId, Instant.now(), durationSeconds, List.of());
		}

		LOG.info("Reading JoularJX results from: " + runDir);

		// Prefer filtered 'app' results; fall back to 'all'
		Path methodsDir = runDir.resolve("app/total/methods");
		if (!Files.isDirectory(methodsDir)) {
			methodsDir = runDir.resolve("all/total/methods");
		}

		List<EnergyMeasurement> measurements = new ArrayList<>();

		if (Files.isDirectory(methodsDir)) {
			try (Stream<Path> csvFiles = Files.list(methodsDir)) {
				csvFiles.filter(p -> p.toString().endsWith(".csv")).forEach(csv -> {
					try {
						measurements.addAll(parseCsv(csv));
					}
					catch (IOException e) {
						LOG.warning("Failed to parse CSV file " + csv + ": " + e.getMessage());
					}
				});
			}
		}
		else {
			LOG.warning("JoularJX methods directory not found under: " + runDir);
		}

		LOG.info("Read " + measurements.size() + " method measurements from JoularJX results");
		return EnergyReport.of(runId, Instant.now(), durationSeconds, measurements);
	}

	/**
	 * Parses a single JoularJX total-energy CSV file.
	 *
	 * <p>
	 * Supports the standard two-column format: {@code methodName,energyJoules}.
	 */
	List<EnergyMeasurement> parseCsv(Path csvFile) throws IOException {
		List<String> lines = Files.readAllLines(csvFile);
		List<EnergyMeasurement> measurements = new ArrayList<>();

		for (String line : lines) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] parts = line.split(",", 2);
			if (parts.length == 2) {
				try {
					String method = parts[0].strip();
					double energy = Double.parseDouble(parts[1].strip());
					if (!method.isBlank() && energy >= 0) {
						measurements.add(new EnergyMeasurement(method, energy));
					}
				}
				catch (NumberFormatException e) {
					LOG.fine("Skipping non-numeric line in " + csvFile + ": " + line);
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

}
