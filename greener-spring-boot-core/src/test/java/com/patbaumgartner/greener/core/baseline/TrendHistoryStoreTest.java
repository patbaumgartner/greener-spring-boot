package com.patbaumgartner.greener.core.baseline;

import com.patbaumgartner.greener.core.model.TrendEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendHistoryStoreTest {

	private final TrendHistoryStore store = new TrendHistoryStore();

	@Test
	void load_returnsEmpty_whenFileMissing(@TempDir Path tmp) {
		assertThat(store.load(tmp.resolve("missing.json"))).isEmpty();
	}

	@Test
	void load_returnsEmpty_whenFileCorrupt(@TempDir Path tmp) throws IOException {
		Path file = tmp.resolve("trend.json");
		Files.writeString(file, "{not valid json");
		assertThat(store.load(file)).isEmpty();
	}

	@Test
	void appendAndSave_roundTrip(@TempDir Path tmp) throws IOException {
		Path file = tmp.resolve("trend.json");
		TrendEntry e1 = new TrendEntry(Instant.parse("2025-01-01T00:00:00Z"), "abc123", 10.5, 0.123, "abc123", "main");
		TrendEntry e2 = new TrendEntry(Instant.parse("2025-01-02T00:00:00Z"), "def456", 11.0, 0.130, "def456", "main");

		store.appendAndSave(file, e1);
		List<TrendEntry> out = store.appendAndSave(file, e2);

		assertThat(file).exists();
		assertThat(out).hasSize(2);
		assertThat(out.get(0).runId()).isEqualTo("abc123");
		assertThat(out.get(1).runId()).isEqualTo("def456");

		List<TrendEntry> reloaded = store.load(file);
		assertThat(reloaded).hasSize(2);
		assertThat(reloaded.get(1).totalEnergyJoules()).isEqualTo(11.0);
		assertThat(reloaded.get(1).energyPerRequestMillijoules()).isEqualTo(0.130);
	}

	@Test
	void appendAndSave_capsAtMaxEntries(@TempDir Path tmp) throws IOException {
		Path file = tmp.resolve("trend.json");
		// Insert 5 entries with monotonically increasing timestamps but cap at 3.
		for (int i = 0; i < 5; i++) {
			TrendEntry e = new TrendEntry(Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i), "run-" + i, i + 1.0,
					null, null, null);
			store.appendAndSave(file, e, 3);
		}
		List<TrendEntry> out = store.load(file);
		assertThat(out).hasSize(3);
		// Oldest two ("run-0", "run-1") should be dropped.
		assertThat(out).extracting(TrendEntry::runId).containsExactly("run-2", "run-3", "run-4");
	}

	@Test
	void appendAndSave_sortsByTimestamp(@TempDir Path tmp) throws IOException {
		Path file = tmp.resolve("trend.json");
		TrendEntry later = new TrendEntry(Instant.parse("2025-01-10T00:00:00Z"), "later", 5.0, null, null, null);
		TrendEntry earlier = new TrendEntry(Instant.parse("2025-01-05T00:00:00Z"), "earlier", 4.0, null, null, null);
		store.appendAndSave(file, later);
		List<TrendEntry> out = store.appendAndSave(file, earlier);
		assertThat(out).extracting(TrendEntry::runId).containsExactly("earlier", "later");
	}

}
