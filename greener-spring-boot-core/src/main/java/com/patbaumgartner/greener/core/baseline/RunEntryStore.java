package com.patbaumgartner.greener.core.baseline;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.patbaumgartner.greener.core.model.AggregatedRunEntry;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists and loads {@link AggregatedRunEntry} records as JSON files so that multiple
 * measurement runs can be collected into a single aggregated report.
 *
 * <p>
 * Each run is stored in its own tool-specific subdirectory as
 * {@code greener-run-entry.json}. After all runs complete, {@link #loadAll(Path)} scans
 * the parent directory for these files and returns the full list.
 */
public class RunEntryStore {

	private static final Logger LOG = Logger.getLogger(RunEntryStore.class.getName());

	/** Well-known file name used for persisted run entries. */
	public static final String RUN_ENTRY_FILE = "greener-run-entry.json";

	private final ObjectMapper objectMapper;

	public RunEntryStore() {
		this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	/**
	 * Persists a run entry to the given file.
	 * @param entry the run entry to save
	 * @param file target JSON file (created or overwritten)
	 */
	public void save(AggregatedRunEntry entry, Path file) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		objectMapper.writeValue(file.toFile(), entry);
		LOG.log(Level.INFO, () -> "Saved run entry [" + entry.tool() + "] to: " + file);
	}

	/**
	 * Loads a single run entry from a JSON file.
	 * @param file path to the JSON run entry file
	 * @return the deserialized run entry
	 */
	public AggregatedRunEntry load(Path file) throws IOException {
		return objectMapper.readValue(file.toFile(), AggregatedRunEntry.class);
	}

	/**
	 * Scans all immediate subdirectories of {@code directory} for
	 * {@value #RUN_ENTRY_FILE} files and loads them.
	 * @param directory the parent directory containing per-tool subdirectories
	 * @return list of all discovered run entries (may be empty)
	 */
	public List<AggregatedRunEntry> loadAll(Path directory) throws IOException {
		List<AggregatedRunEntry> entries = new ArrayList<>();
		if (!Files.isDirectory(directory)) {
			return entries;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			for (Path child : stream) {
				if (!Files.isDirectory(child)) {
					continue;
				}
				Path entryFile = child.resolve(RUN_ENTRY_FILE);
				if (Files.exists(entryFile)) {
					try {
						entries.add(load(entryFile));
					}
					catch (IOException e) {
						LOG.log(Level.WARNING,
								() -> "Failed to load run entry from " + entryFile + ": " + e.getMessage());
					}
				}
			}
		}
		LOG.log(Level.INFO, () -> "Loaded " + entries.size() + " run entries from: " + directory);
		return entries;
	}

}
