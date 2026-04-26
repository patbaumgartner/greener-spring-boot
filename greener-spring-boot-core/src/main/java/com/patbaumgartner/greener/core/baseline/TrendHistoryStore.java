package com.patbaumgartner.greener.core.baseline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.patbaumgartner.greener.core.model.TrendEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists a rolling history of {@link TrendEntry} records as a single JSON document so
 * that the HTML report can render a sparkline / line chart of energy over time. Entries
 * are sorted oldest-first; the file is automatically capped at the most recent
 * {@link #DEFAULT_MAX_ENTRIES} runs to prevent unbounded growth.
 *
 * <p>
 * The file format is a small, forward-compatible JSON object: <pre>{@code
 * {
 *   "version": 1,
 *   "entries": [
 *     { "timestamp": "...", "runId": "...", "totalEnergyJoules": 12.34, ... },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * Corrupted or unreadable history files are treated as empty so that a single bad write
 * does not break subsequent runs &mdash; the history is a convenience, not a source of
 * truth.
 */
public class TrendHistoryStore {

	/**
	 * Well-known suffix appended to the baseline file's stem to derive the trend file
	 * name. For example, baseline {@code energy-baseline.json} → trend file
	 * {@code energy-baseline-trend.json}.
	 */
	public static final String TREND_FILE_SUFFIX = "-trend.json";

	/**
	 * Returns the trend file Path that lives next to the given baseline file. Deriving
	 * the trend file from the baseline name ensures every distinct baseline (per branch,
	 * per workload tool, per environment) gets its own independent history rather than
	 * mixing into a single file.
	 * @param baselineFile the baseline file (must not be {@code null})
	 * @return the trend file path that sits next to the baseline
	 */
	public static Path trendFileFor(Path baselineFile) {
		Path parent = baselineFile.toAbsolutePath().getParent();
		String name = baselineFile.getFileName().toString();
		String stem = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
		return (parent != null ? parent : baselineFile.toAbsolutePath()).resolve(stem + TREND_FILE_SUFFIX);
	}

	/** Default maximum number of entries kept in the trend file. */
	public static final int DEFAULT_MAX_ENTRIES = 100;

	private static final Logger LOG = Logger.getLogger(TrendHistoryStore.class.getName());

	private static final int CURRENT_VERSION = 1;

	private static final int MIN_MAX_ENTRIES = 1;

	private final ObjectMapper objectMapper;

	public TrendHistoryStore() {
		this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	/**
	 * Loads the trend history from disk. Missing, empty, or corrupted files yield an
	 * empty list rather than an exception.
	 * @param file path to the history JSON file (may not exist yet)
	 * @return an immutable, oldest-first list of entries
	 */
	public List<TrendEntry> load(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return Collections.emptyList();
		}
		try {
			Map<String, Object> root = objectMapper.readValue(file.toFile(), new TypeReference<Map<String, Object>>() {
			});
			Object entriesNode = root.get("entries");
			if (entriesNode == null) {
				return Collections.emptyList();
			}
			List<TrendEntry> entries = objectMapper.convertValue(entriesNode, new TypeReference<List<TrendEntry>>() {
			});
			return entries == null ? Collections.emptyList() : Collections.unmodifiableList(entries);
		}
		catch (IOException | IllegalArgumentException e) {
			LOG.log(Level.WARNING, e, () -> "Failed to load trend history from " + file + "; treating as empty");
			return Collections.emptyList();
		}
	}

	/**
	 * Appends an entry to the history and writes the result back to disk, capping the
	 * file at {@link #DEFAULT_MAX_ENTRIES} most-recent entries.
	 * @param file target JSON file (created or overwritten)
	 * @param entry the new trend point to append
	 * @return the full list of entries written to disk (oldest-first)
	 */
	public List<TrendEntry> appendAndSave(Path file, TrendEntry entry) throws IOException {
		return appendAndSave(file, entry, DEFAULT_MAX_ENTRIES);
	}

	/**
	 * Appends an entry to the history and writes the result back to disk, capping the
	 * file at {@code maxEntries} most-recent entries.
	 */
	public List<TrendEntry> appendAndSave(Path file, TrendEntry entry, int maxEntries) throws IOException {
		if (file == null) {
			throw new IllegalArgumentException("file must not be null");
		}
		if (entry == null) {
			throw new IllegalArgumentException("entry must not be null");
		}
		if (maxEntries < MIN_MAX_ENTRIES) {
			throw new IllegalArgumentException("maxEntries must be >= 1");
		}

		List<TrendEntry> existing = new ArrayList<>(load(file));
		existing.add(entry);
		// Sort oldest-first by timestamp, fall back on insertion order on tie.
		existing.sort((a, b) -> a.timestamp().compareTo(b.timestamp()));
		// Cap to the most recent maxEntries.
		List<TrendEntry> capped = existing.size() <= maxEntries ? existing
				: new ArrayList<>(existing.subList(existing.size() - maxEntries, existing.size()));

		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Map<String, Object> root = new HashMap<>();
		root.put("version", CURRENT_VERSION);
		root.put("entries", capped);
		objectMapper.writeValue(file.toFile(), root);
		LOG.fine(() -> "Wrote trend history (" + capped.size() + " entries) to: " + file);
		return Collections.unmodifiableList(capped);
	}

}
