package com.patbaumgartner.greener.core.orchestrator;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.MeasurementConfig;
import com.patbaumgartner.greener.core.model.RegressionMetric;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import com.patbaumgartner.greener.core.baseline.TrendHistoryStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasurementOrchestratorTest {

	private final List<String> logMessages = new ArrayList<>();

	private final MeasurementOrchestrator orchestrator = new MeasurementOrchestrator(logMessages::add);

	private EnergyReport createTestReport() {
		return new EnergyReport("test-run", Instant.now(), 60L, List.of(), 10.0);
	}

	// ---- processBaselineComparison ----

	@Test
	void processBaselineComparison_noBaseline_returnsComparisonWithoutBaseline(@TempDir Path tempDir) throws Exception {
		EnergyReport report = createTestReport();
		Path runDir = tempDir.resolve("run");
		Files.createDirectories(runDir);

		ComparisonResult result = orchestrator.processBaselineComparison(report, null, runDir, 10.0, false, null, null,
				RegressionMetric.ENERGY_PER_REQUEST, null);

		assertThat(result).isNotNull();
		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.NO_BASELINE);
	}

	@Test
	void processBaselineComparison_nonExistentBaseline_returnsComparisonWithoutBaseline(@TempDir Path tempDir)
			throws Exception {
		EnergyReport report = createTestReport();
		Path runDir = tempDir.resolve("run");
		Files.createDirectories(runDir);
		Path baselinePath = tempDir.resolve("nonexistent-baseline.json");

		ComparisonResult result = orchestrator.processBaselineComparison(report, baselinePath, runDir, 10.0, false,
				null, null, RegressionMetric.ENERGY_PER_REQUEST, null);

		assertThat(result).isNotNull();
		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.NO_BASELINE);
	}

	@Test
	void processBaselineComparison_savesLatestReport(@TempDir Path tempDir) throws Exception {
		EnergyReport report = createTestReport();
		Path runDir = tempDir.resolve("run");
		Files.createDirectories(runDir);

		orchestrator.processBaselineComparison(report, null, runDir, 10.0, false, null, null,
				RegressionMetric.ENERGY_PER_REQUEST, null);

		Path latestReport = runDir.resolve("latest-energy-report.json");
		assertThat(latestReport).exists();
	}

	@Test
	void processBaselineComparison_autoUpdate_savesBaseline(@TempDir Path tempDir) throws Exception {
		EnergyReport report = createTestReport();
		Path runDir = tempDir.resolve("run");
		Files.createDirectories(runDir);
		Path baselinePath = tempDir.resolve("energy-baseline.json");

		orchestrator.processBaselineComparison(report, baselinePath, runDir, 10.0, true, "abc123", "main",
				RegressionMetric.ENERGY_PER_REQUEST, null);

		assertThat(baselinePath).exists();
		assertThat(logMessages).anyMatch(msg -> msg.contains("Energy baseline updated"));
	}

	@Test
	void processBaselineComparison_noAutoUpdate_doesNotSaveBaseline(@TempDir Path tempDir) throws Exception {
		EnergyReport report = createTestReport();
		Path runDir = tempDir.resolve("run");
		Files.createDirectories(runDir);
		Path baselinePath = tempDir.resolve("energy-baseline.json");

		orchestrator.processBaselineComparison(report, baselinePath, runDir, 10.0, false, null, null,
				RegressionMetric.ENERGY_PER_REQUEST, null);

		assertThat(baselinePath).doesNotExist();
	}

	@Test
	void processBaselineComparison_corruptBaseline_returnsNoBaseline(@TempDir Path tempDir) throws Exception {
		EnergyReport report = createTestReport();
		Path runDir = tempDir.resolve("run");
		Files.createDirectories(runDir);
		Path baselinePath = tempDir.resolve("energy-baseline.json");
		Files.writeString(baselinePath, "not valid json {{}}");

		ComparisonResult result = orchestrator.processBaselineComparison(report, baselinePath, runDir, 10.0, false,
				null, null, RegressionMetric.ENERGY_PER_REQUEST, null);

		assertThat(result).isNotNull();
		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.NO_BASELINE);
		assertThat(logMessages).anyMatch(msg -> msg.contains("Could not load baseline"));
	}

	// ---- processResults ----

	@Test
	void processResults_readsCsvAndBuildsReport(@TempDir Path tempDir) throws Exception {
		Path outputCsv = tempDir.resolve("joularcore-output.csv");
		Files.writeString(outputCsv, "timestamp,cpu_power,gpu_power,total_power,cpu_usage,pid_or_app_power\n"
				+ "1000000,50.0,0.0,50.0,25.0,10.0\n" + "1000001,55.0,0.0,55.0,30.0,12.0\n");

		EnergyReport report = orchestrator.processResults(outputCsv, 2, "test-app");

		assertThat(report).isNotNull();
		assertThat(report.durationSeconds()).isEqualTo(2);
		assertThat(report.totalEnergyJoules()).isGreaterThan(0);
		assertThat(logMessages).anyMatch(msg -> msg.contains("Processing results"));
	}

	@Test
	void processResults_missingCsv_throwsIOException(@TempDir Path tempDir) {
		Path outputCsv = tempDir.resolve("nonexistent.csv");

		assertThatThrownBy(() -> orchestrator.processResults(outputCsv, 60, "test-app")).isInstanceOf(IOException.class)
			.hasMessageContaining("not found");
	}

	// ---- generateFinalReports ----

	@Test
	void generateFinalReports_generatesHtmlReport(@TempDir Path tempDir) throws Exception {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 25.0), new EnergyMeasurement("system [total cpu]", 100.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 125.0, 0, List.of(), false,
				10.0);
		WorkloadStats workloadStats = WorkloadStats.external("oha", 1000, 5, 60);

		Path reportDir = tempDir.resolve("reports");
		Path runDir = reportDir.resolve("oha");
		Files.createDirectories(runDir);

		Path htmlReport = orchestrator.generateFinalReports(report, comparison, workloadStats, "oha", reportDir, runDir,
				false);

		assertThat(htmlReport).exists();
		assertThat(htmlReport.toString()).endsWith(".html");
		assertThat(logMessages).anyMatch(msg -> msg.contains("HTML report"));
	}

	@Test
	void generateFinalReports_savesRunEntry(@TempDir Path tempDir) throws Exception {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 25.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 25.0, 0, List.of(), false,
				10.0);
		WorkloadStats workloadStats = WorkloadStats.external("wrk", 500, 2, 60);

		Path reportDir = tempDir.resolve("reports");
		Path runDir = reportDir.resolve("wrk");
		Files.createDirectories(runDir);

		orchestrator.generateFinalReports(report, comparison, workloadStats, "wrk", reportDir, runDir, false);

		Path runEntryFile = runDir.resolve("greener-run-entry.json");
		assertThat(runEntryFile).exists();
	}

	@Test
	void generateFinalReports_multipleTools_generatesAggregatedReport(@TempDir Path tempDir) throws Exception {
		Path reportDir = tempDir.resolve("reports");

		// First tool run
		EnergyReport report1 = EnergyReport.of("run-1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 20.0)));
		ComparisonResult comparison1 = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 20.0, 0, List.of(), false,
				10.0);
		WorkloadStats stats1 = WorkloadStats.external("oha", 1000, 5, 60);
		Path runDir1 = reportDir.resolve("oha");
		Files.createDirectories(runDir1);
		orchestrator.generateFinalReports(report1, comparison1, stats1, "oha", reportDir, runDir1, false);

		// Second tool run
		logMessages.clear();
		EnergyReport report2 = EnergyReport.of("run-2", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 30.0)));
		ComparisonResult comparison2 = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 30.0, 0, List.of(), false,
				10.0);
		WorkloadStats stats2 = WorkloadStats.external("wrk", 500, 2, 60);
		Path runDir2 = reportDir.resolve("wrk");
		Files.createDirectories(runDir2);
		orchestrator.generateFinalReports(report2, comparison2, stats2, "wrk", reportDir, runDir2, false);

		assertThat(logMessages).anyMatch(msg -> msg.contains("Aggregated report"));
	}

	// ---- processAndReport: trend-history wiring ----

	@Test
	void processAndReport_writesTrendFileNextToBaseline_andAccumulatesAcrossRuns(@TempDir Path tempDir)
			throws Exception {
		Path reportDir = tempDir.resolve("reports");
		Path runDir = reportDir.resolve("oha");
		Files.createDirectories(runDir);
		Path baselinePath = tempDir.resolve("baselines").resolve("greener-energy-baseline.json");
		Path trendFile = TrendHistoryStore.trendFileFor(baselinePath);

		WorkloadStats stats = WorkloadStats.external("oha", 1000, 5, 60);

		MeasurementConfig cfg = new MeasurementConfig(null, 60, "app", baselinePath, reportDir, runDir, "oha", false,
				10.0, false, "abcdef0", "main", null, false, 1, RegressionMetric.ENERGY_PER_REQUEST, 0, 0L);

		EnergyReport r1 = EnergyReport.of("run-1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 25.0)));
		orchestrator.processAndReport(cfg, r1, stats);
		assertThat(trendFile).exists();

		EnergyReport r2 = EnergyReport.of("run-2", Instant.now().plusSeconds(1), 60,
				List.of(new EnergyMeasurement("app [app]", 27.0)));
		orchestrator.processAndReport(cfg, r2, stats);

		var entries = new TrendHistoryStore().load(trendFile);
		assertThat(entries).hasSize(2);
		assertThat(entries.get(0).totalEnergyJoules()).isEqualTo(25.0);
		assertThat(entries.get(1).totalEnergyJoules()).isEqualTo(27.0);
	}

	@Test
	void processAndReport_noBaselinePath_skipsTrendFile(@TempDir Path tempDir) throws Exception {
		Path reportDir = tempDir.resolve("reports");
		Path runDir = reportDir.resolve("oha");
		Files.createDirectories(runDir);

		MeasurementConfig cfg = new MeasurementConfig(null, 60, "app", null, reportDir, runDir, "oha", false, 10.0,
				false, null, null, null, false, 1, RegressionMetric.ENERGY_PER_REQUEST, 0, 0L);

		EnergyReport r = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app [app]", 25.0)));

		// must not throw, must not create any trend file under tempDir
		orchestrator.processAndReport(cfg, r, WorkloadStats.external("oha", 100, 1, 60));

		try (var stream = Files.walk(tempDir)) {
			assertThat(stream.filter(p -> p.getFileName().toString().endsWith(TrendHistoryStore.TREND_FILE_SUFFIX))
				.findAny()).isEmpty();
		}
	}

}
