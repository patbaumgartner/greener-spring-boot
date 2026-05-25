package com.patbaumgartner.greener.core.baseline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Saves and loads {@link EnergyBaseline} to/from a JSON file on disk.
 *
 * <p>
 * The baseline file should be committed to source control or cached as a CI artifact so
 * that future runs can compare their energy results.
 */
public class BaselineManager {

	private static final Logger LOG = Logger.getLogger(BaselineManager.class.getName());

	private final ObjectMapper objectMapper;

	public BaselineManager() {
		this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(SerializationFeature.INDENT_OUTPUT);
	}

	/**
	 * Persists the given {@link EnergyReport} as the new baseline.
	 * @param report the energy report to persist
	 * @param baselineFile target JSON file (will be created or overwritten)
	 */
	public void saveBaseline(EnergyReport report, Path baselineFile) throws IOException {
		saveBaseline(report, null, null, baselineFile);
	}

	/**
	 * Persists the given {@link EnergyReport} as the new baseline with optional VCS
	 * metadata.
	 * @param report the energy report to persist
	 * @param commitSha optional git commit SHA (may be {@code null})
	 * @param branch optional branch name (may be {@code null})
	 * @param baselineFile target JSON file (will be created or overwritten)
	 */
	public void saveBaseline(EnergyReport report, String commitSha, String branch, Path baselineFile)
			throws IOException {
		saveBaseline(report, commitSha, branch, null, baselineFile);
	}

	/**
	 * Persists the given {@link EnergyReport} together with the workload statistics that
	 * produced it. Storing {@link WorkloadStats} alongside the baseline enables future
	 * runs to compare on energy-per-request rather than raw Joules.
	 * @param report the energy report to persist
	 * @param commitSha optional git commit SHA (may be {@code null})
	 * @param branch optional branch name (may be {@code null})
	 * @param workloadStats optional workload statistics (may be {@code null})
	 * @param baselineFile target JSON file (will be created or overwritten)
	 */
	public void saveBaseline(EnergyReport report, String commitSha, String branch, WorkloadStats workloadStats,
			Path baselineFile) throws IOException {
		EnergyBaseline baseline = EnergyBaseline.of(report, commitSha, branch, workloadStats);

		Path parent = baselineFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		objectMapper.writeValue(baselineFile.toFile(), baseline);
		LOG.info(() -> "Saved energy baseline to: " + baselineFile);
	}

	/**
	 * Loads a previously saved baseline from disk.
	 * @param baselineFile path to the JSON baseline file
	 * @return an {@link Optional} containing the baseline, or empty if the file does not
	 * exist
	 */
	public Optional<EnergyBaseline> loadBaseline(Path baselineFile) throws IOException {
		if (!Files.exists(baselineFile)) {
			LOG.info(() -> "No baseline file found at: " + baselineFile);
			return Optional.empty();
		}

		EnergyBaseline baseline = objectMapper.readValue(baselineFile.toFile(), EnergyBaseline.class);
		LOG.info(() -> {
			StringBuilder sb = new StringBuilder("Loaded energy baseline from: ").append(baselineFile)
				.append(" (created: ")
				.append(baseline.createdAt());
			if (baseline.branch() != null) {
				sb.append(", branch: ").append(baseline.branch());
			}
			sb.append(")");
			return sb.toString();
		});
		return Optional.of(baseline);
	}

	/**
	 * Resolves the latest energy report by trying three sources in order:
	 * <ol>
	 * <li>An explicit report file path ({@code latestReportFile})</li>
	 * <li>Auto-discovery from subdirectories of {@code reportDir}</li>
	 * <li>The existing baseline file</li>
	 * </ol>
	 * @param latestReportFile explicit report file path (may be {@code null})
	 * @param reportDir report output directory for auto-discovery (may be {@code null})
	 * @param baselineFile fallback baseline file path
	 * @return the resolved energy report, or empty if none could be found
	 */
	public Optional<EnergyReport> resolveLatestReport(Path latestReportFile, Path reportDir, Path baselineFile)
			throws IOException {
		if (latestReportFile != null) {
			return loadBaseline(latestReportFile).map(EnergyBaseline::report);
		}

		Optional<Path> discovered = discoverLatestReport(reportDir);
		if (discovered.isPresent()) {
			return loadBaseline(discovered.get()).map(EnergyBaseline::report);
		}

		return loadBaseline(baselineFile).map(EnergyBaseline::report);
	}

	/**
	 * Resolves the full {@link EnergyBaseline} (including workload statistics) by trying
	 * three sources in order: an explicit report file, auto-discovery from
	 * subdirectories, and finally the existing baseline file.
	 * @param latestReportFile explicit report file path (may be {@code null})
	 * @param reportDir report output directory for auto-discovery (may be {@code null})
	 * @param baselineFile fallback baseline file path
	 * @return the resolved baseline, or empty if none could be found
	 */
	public Optional<EnergyBaseline> resolveLatestBaseline(Path latestReportFile, Path reportDir, Path baselineFile)
			throws IOException {
		if (latestReportFile != null) {
			return loadBaseline(latestReportFile);
		}

		Optional<Path> discovered = discoverLatestReport(reportDir);
		if (discovered.isPresent()) {
			return loadBaseline(discovered.get());
		}

		return loadBaseline(baselineFile);
	}

	/**
	 * Scans immediate subdirectories of the given directory for
	 * {@code latest-energy-report.json} and returns the most recently modified match.
	 * @param reportDir root report output directory
	 * @return the path to the discovered report, or empty if none found
	 */
	public Optional<Path> discoverLatestReport(Path reportDir) throws IOException {
		if (reportDir == null || !Files.isDirectory(reportDir)) {
			return Optional.empty();
		}
		Path best = null;
		long bestModified = Long.MIN_VALUE;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportDir)) {
			for (Path child : stream) {
				if (!Files.isDirectory(child)) {
					continue;
				}
				Path candidate = child.resolve("latest-energy-report.json");
				if (Files.exists(candidate)) {
					long modified = Files.getLastModifiedTime(candidate).toMillis();
					if (modified > bestModified) {
						best = candidate;
						bestModified = modified;
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

}
