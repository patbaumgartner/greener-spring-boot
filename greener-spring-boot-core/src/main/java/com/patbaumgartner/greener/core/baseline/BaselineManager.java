package com.patbaumgartner.greener.core.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;

import java.io.IOException;
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
		EnergyBaseline baseline = EnergyBaseline.of(report, commitSha, branch);

		Path parent = baselineFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		objectMapper.writeValue(baselineFile.toFile(), baseline);
		LOG.info("Saved energy baseline to: " + baselineFile);
	}

	/**
	 * Loads a previously saved baseline from disk.
	 * @param baselineFile path to the JSON baseline file
	 * @return an {@link Optional} containing the baseline, or empty if the file does not
	 * exist
	 */
	public Optional<EnergyBaseline> loadBaseline(Path baselineFile) throws IOException {
		if (!Files.exists(baselineFile)) {
			LOG.info("No baseline file found at: " + baselineFile);
			return Optional.empty();
		}

		EnergyBaseline baseline = objectMapper.readValue(baselineFile.toFile(), EnergyBaseline.class);
		LOG.info("Loaded energy baseline from: " + baselineFile + " (created: " + baseline.createdAt() + ", branch: "
				+ baseline.branch() + ")");
		return Optional.of(baseline);
	}

}
