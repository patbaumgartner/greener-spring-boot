package com.patbaumgartner.greener.core.baseline;

import com.patbaumgartner.greener.core.model.AggregatedRunEntry;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunEntryStoreTest {

	private final RunEntryStore store = new RunEntryStore();

	@Test
	void saveAndLoad_roundTrip(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-oha", Instant.parse("2025-01-15T10:30:00Z"), 60,
				List.of(new EnergyMeasurement("app [app]", 30.0)));
		WorkloadStats stats = WorkloadStats.external("oha", 5000, 10, 60);
		AggregatedRunEntry entry = new AggregatedRunEntry("oha", report, stats, null);

		Path file = tmp.resolve("greener-run-entry.json");
		store.save(entry, file);

		assertThat(file).exists();

		AggregatedRunEntry loaded = store.load(file);
		assertThat(loaded.tool()).isEqualTo("oha");
		assertThat(loaded.report().runId()).isEqualTo("run-oha");
		assertThat(loaded.report().totalEnergyJoules()).isEqualTo(30.0);
		assertThat(loaded.workloadStats()).isNotNull();
		assertThat(loaded.workloadStats().totalRequests()).isEqualTo(5000);
		assertThat(loaded.comparison()).isNull();
	}

	@Test
	void saveAndLoad_withComparison(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-wrk", Instant.parse("2025-02-01T08:00:00Z"), 60,
				List.of(new EnergyMeasurement("app [app]", 45.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.IMPROVED, 50.0, 45.0, -10.0, List.of(),
				false, 10.0);
		AggregatedRunEntry entry = new AggregatedRunEntry("wrk", report, null, comparison);

		Path file = tmp.resolve("greener-run-entry.json");
		store.save(entry, file);

		AggregatedRunEntry loaded = store.load(file);
		assertThat(loaded.tool()).isEqualTo("wrk");
		assertThat(loaded.workloadStats()).isNull();
		assertThat(loaded.comparison()).isNotNull();
		assertThat(loaded.comparison().overallStatus()).isEqualTo(ComparisonStatus.IMPROVED);
		assertThat(loaded.comparison().totalDeltaPercent()).isEqualTo(-10.0);
	}

	@Test
	void loadAll_scansSubdirectories(@TempDir Path tmp) throws IOException {
		EnergyReport report1 = EnergyReport.of("r1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 10.0)));
		EnergyReport report2 = EnergyReport.of("r2", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 20.0)));

		Path ohaDir = tmp.resolve("oha");
		Path wrkDir = tmp.resolve("wrk");
		Files.createDirectories(ohaDir);
		Files.createDirectories(wrkDir);

		store.save(new AggregatedRunEntry("oha", report1, WorkloadStats.external("oha", 1000, 0, 60), null),
				ohaDir.resolve(RunEntryStore.RUN_ENTRY_FILE));
		store.save(new AggregatedRunEntry("wrk", report2, WorkloadStats.external("wrk", 2000, 5, 60), null),
				wrkDir.resolve(RunEntryStore.RUN_ENTRY_FILE));

		List<AggregatedRunEntry> entries = store.loadAll(tmp);
		assertThat(entries).hasSize(2);
		assertThat(entries).extracting(AggregatedRunEntry::tool).containsExactlyInAnyOrder("oha", "wrk");
	}

	@Test
	void loadAll_returnsEmptyForNonExistentDirectory(@TempDir Path tmp) throws IOException {
		List<AggregatedRunEntry> entries = store.loadAll(tmp.resolve("does-not-exist"));
		assertThat(entries).isEmpty();
	}

	@Test
	void loadAll_skipsFilesInRootDirectory(@TempDir Path tmp) throws IOException {
		// A file directly in the root should not be loaded — only subdirectories
		EnergyReport report = EnergyReport.of("r1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 10.0)));
		store.save(new AggregatedRunEntry("oha", report, null, null), tmp.resolve(RunEntryStore.RUN_ENTRY_FILE));

		List<AggregatedRunEntry> entries = store.loadAll(tmp);
		assertThat(entries).isEmpty();
	}

	@Test
	void loadAll_skipsCorruptFiles(@TempDir Path tmp) throws IOException {
		Path toolDir = tmp.resolve("broken");
		Files.createDirectories(toolDir);
		Files.writeString(toolDir.resolve(RunEntryStore.RUN_ENTRY_FILE), "not valid json");

		List<AggregatedRunEntry> entries = store.loadAll(tmp);
		assertThat(entries).isEmpty();
	}

	@Test
	void save_createsParentDirectories(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("r1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 10.0)));
		Path nested = tmp.resolve("a").resolve("b").resolve("greener-run-entry.json");

		store.save(new AggregatedRunEntry("oha", report, null, null), nested);

		assertThat(nested).exists();
	}

}
