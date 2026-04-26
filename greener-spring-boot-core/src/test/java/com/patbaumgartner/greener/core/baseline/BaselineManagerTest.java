package com.patbaumgartner.greener.core.baseline;

import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineManagerTest {

	private final BaselineManager manager = new BaselineManager();

	@Test
	void saveAndLoadBaseline_roundTrip(@TempDir Path tmp) throws IOException {
		EnergyReport report = new EnergyReport("run-123", Instant.parse("2024-01-15T10:30:00Z"), 60L, List
			.of(new EnergyMeasurement("petclinic [app]", 150.0), new EnergyMeasurement("system [total cpu]", 400.0)),
				550.0);

		Path baselineFile = tmp.resolve("energy-baseline.json");
		manager.saveBaseline(report, "abc123", "main", baselineFile);

		Optional<EnergyBaseline> loaded = manager.loadBaseline(baselineFile);

		assertThat(loaded).isPresent();
		EnergyBaseline b = loaded.get();
		assertThat(b.commitSha()).isEqualTo("abc123");
		assertThat(b.branch()).isEqualTo("main");
		assertThat(b.version()).isEqualTo(EnergyBaseline.CURRENT_VERSION);
		assertThat(b.report().runId()).isEqualTo("run-123");
		assertThat(b.report().totalEnergyJoules()).isEqualTo(550.0);
		assertThat(b.report().measurements()).hasSize(2);
	}

	@Test
	void loadBaseline_v10WithoutStatistics_remainsCompatible(@TempDir Path tmp) throws IOException {
		// Simulate a baseline written by an older 0.1.x release: v1.0 schema,
		// no totalEnergyStats field on the embedded report.
		String v10Json = """
				{
				  "version" : "1.0",
				  "createdAt" : "2024-01-15T10:30:00Z",
				  "commitSha" : "abc",
				  "branch" : "main",
				  "report" : {
				    "runId" : "old-run",
				    "timestamp" : "2024-01-15T10:30:00Z",
				    "durationSeconds" : 60,
				    "measurements" : [
				      { "methodName" : "petclinic [app]", "energyJoules" : 100.0 }
				    ],
				    "totalEnergyJoules" : 100.0
				  }
				}
				""";
		Path baselineFile = tmp.resolve("v10-baseline.json");
		Files.writeString(baselineFile, v10Json);

		Optional<EnergyBaseline> loaded = manager.loadBaseline(baselineFile);

		assertThat(loaded).isPresent();
		EnergyBaseline b = loaded.get();
		assertThat(b.version()).isEqualTo("1.0");
		assertThat(b.report().totalEnergyJoules()).isEqualTo(100.0);
		// Missing stats field is normalised to Statistics.empty().
		assertThat(b.report().totalEnergyStats().n()).isZero();
		assertThat(b.report().totalEnergyStats().hasVariance()).isFalse();
	}

	@Test
	void loadBaseline_unknownFutureField_doesNotFail(@TempDir Path tmp) throws IOException {
		// Forward compatibility: a future schema field must not crash today's reader.
		String futureJson = """
				{
				  "version" : "1.2",
				  "createdAt" : "2024-01-15T10:30:00Z",
				  "futureField" : "should be ignored",
				  "report" : {
				    "runId" : "future",
				    "timestamp" : "2024-01-15T10:30:00Z",
				    "durationSeconds" : 60,
				    "measurements" : [],
				    "totalEnergyJoules" : 0.0,
				    "extraField" : 42
				  }
				}
				""";
		Path baselineFile = tmp.resolve("future.json");
		Files.writeString(baselineFile, futureJson);

		Optional<EnergyBaseline> loaded = manager.loadBaseline(baselineFile);

		assertThat(loaded).isPresent();
		assertThat(loaded.get().version()).isEqualTo("1.2");
	}

	@Test
	void loadBaseline_missingFile_returnsEmpty(@TempDir Path tmp) throws IOException {
		Optional<EnergyBaseline> result = manager.loadBaseline(tmp.resolve("missing.json"));

		assertThat(result).isEmpty();
	}

	@Test
	void saveBaseline_createsParentDirectories(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("r", Instant.now(), 60L, List.of());
		Path nested = tmp.resolve("deep/nested/path/baseline.json");

		manager.saveBaseline(report, nested);

		assertThat(nested).exists();
	}

	// ---- discoverLatestReport ----

	@Test
	void discoverLatestReport_findsReportInSubdirectory(@TempDir Path tmp) throws IOException {
		Path toolDir = tmp.resolve("oha");
		Files.createDirectories(toolDir);
		EnergyReport report = EnergyReport.of("r1", Instant.now(), 60L, List.of());
		manager.saveBaseline(report, toolDir.resolve("latest-energy-report.json"));

		Optional<Path> result = manager.discoverLatestReport(tmp);

		assertThat(result).isPresent();
		assertThat(result.get().getFileName().toString()).isEqualTo("latest-energy-report.json");
		assertThat(result.get().getParent().getFileName().toString()).isEqualTo("oha");
	}

	@Test
	void discoverLatestReport_returnsEmptyForNullDir() throws IOException {
		assertThat(manager.discoverLatestReport(null)).isEmpty();
	}

	@Test
	void discoverLatestReport_returnsEmptyForNonExistentDir(@TempDir Path tmp) throws IOException {
		assertThat(manager.discoverLatestReport(tmp.resolve("nonexistent"))).isEmpty();
	}

	@Test
	void discoverLatestReport_returnsEmptyWhenNoReportsExist(@TempDir Path tmp) throws IOException {
		Files.createDirectories(tmp.resolve("oha"));
		assertThat(manager.discoverLatestReport(tmp)).isEmpty();
	}

	@Test
	void discoverLatestReport_returnsMostRecentWhenMultipleExist(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("r1", Instant.now(), 60L, List.of());

		Path olderDir = tmp.resolve("wrk");
		Files.createDirectories(olderDir);
		manager.saveBaseline(report, olderDir.resolve("latest-energy-report.json"));

		// Ensure a time gap so the file modification times differ
		Path newerDir = tmp.resolve("oha");
		Files.createDirectories(newerDir);
		Path newerReport = newerDir.resolve("latest-energy-report.json");
		manager.saveBaseline(report, newerReport);
		// Touch the newer file to ensure it has a later modification time
		Files.setLastModifiedTime(newerReport,
				java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));

		Optional<Path> result = manager.discoverLatestReport(tmp);

		assertThat(result).isPresent();
		assertThat(result.get().getParent().getFileName().toString()).isEqualTo("oha");
	}

	// ---- resolveLatestReport ----

	@Test
	void resolveLatestReport_prefersExplicitFile(@TempDir Path tmp) throws IOException {
		EnergyReport explicit = EnergyReport.of("explicit", Instant.now(), 60L,
				List.of(new EnergyMeasurement("app", 42.0)));
		Path explicitFile = tmp.resolve("explicit-report.json");
		manager.saveBaseline(explicit, explicitFile);

		EnergyReport other = EnergyReport.of("other", Instant.now(), 60L, List.of(new EnergyMeasurement("app", 99.0)));
		Path baselineFile = tmp.resolve("energy-baseline.json");
		manager.saveBaseline(other, baselineFile);

		Optional<EnergyReport> result = manager.resolveLatestReport(explicitFile, null, baselineFile);

		assertThat(result).isPresent();
		assertThat(result.get().totalEnergyJoules()).isEqualTo(42.0);
	}

	@Test
	void resolveLatestReport_discoversFromReportDir(@TempDir Path tmp) throws IOException {
		Path toolDir = tmp.resolve("reports/oha");
		Files.createDirectories(toolDir);
		EnergyReport report = EnergyReport.of("r1", Instant.now(), 60L, List.of(new EnergyMeasurement("app", 55.0)));
		manager.saveBaseline(report, toolDir.resolve("latest-energy-report.json"));

		Path baselineFile = tmp.resolve("energy-baseline.json");

		Optional<EnergyReport> result = manager.resolveLatestReport(null, tmp.resolve("reports"), baselineFile);

		assertThat(result).isPresent();
		assertThat(result.get().totalEnergyJoules()).isEqualTo(55.0);
	}

	@Test
	void resolveLatestReport_fallsBackToBaseline(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("r1", Instant.now(), 60L, List.of(new EnergyMeasurement("app", 77.0)));
		Path baselineFile = tmp.resolve("energy-baseline.json");
		manager.saveBaseline(report, baselineFile);

		Optional<EnergyReport> result = manager.resolveLatestReport(null, null, baselineFile);

		assertThat(result).isPresent();
		assertThat(result.get().totalEnergyJoules()).isEqualTo(77.0);
	}

	@Test
	void resolveLatestReport_returnsEmptyWhenNothingAvailable(@TempDir Path tmp) throws IOException {
		Path baselineFile = tmp.resolve("nonexistent.json");

		Optional<EnergyReport> result = manager.resolveLatestReport(null, null, baselineFile);

		assertThat(result).isEmpty();
	}

}
