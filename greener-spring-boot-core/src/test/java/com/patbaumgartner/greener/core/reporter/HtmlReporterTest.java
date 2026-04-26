package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.AggregatedRunEntry;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.MethodLevelReports;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReporterTest {

	private final HtmlReporter reporter = new HtmlReporter();

	@Test
	void generateReport_createsHtmlFile(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 25.0), new EnergyMeasurement("system [total cpu]", 100.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 125.0, 0, List.of(), false,
				10.0);

		Path htmlFile = reporter.generateReport(report, comparison, tmp);

		assertThat(htmlFile).exists();
		assertThat(htmlFile.getFileName().toString()).isEqualTo("greener-energy-report.html");

		String content = Files.readString(htmlFile);
		assertThat(content).contains("<!DOCTYPE html>");
		assertThat(content).contains("Greener Spring Boot");
		assertThat(content).contains("run-1");
	}

	@Test
	void generateReport_includesWorkloadStats(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-2", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 50.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 50.0, 0, List.of(), false,
				10.0);

		WorkloadStats stats = WorkloadStats.external("oha", 1000, 5, 60);

		Path htmlFile = reporter.generateReport(report, comparison, stats, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Workload Profile");
		assertThat(content).contains("1000");
	}

	@Test
	void generateReport_includesPowerSource(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-3", Instant.now(), 60, List.of());

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 0, 0, List.of(), false,
				10.0);

		Path htmlFile = reporter.generateReport(report, comparison, null, PowerSource.RAPL, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Measurement Assumptions");
		assertThat(content).contains("RAPL");
	}

	@Test
	void generateReport_includesComparisonCard(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-4", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 120.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.REGRESSED, 100.0, 120.0, 20.0,
				List.of(new ComparisonResult.MethodComparison("app [app]", 100.0, 120.0, 20.0)), true, 10.0);

		Path htmlFile = reporter.generateReport(report, comparison, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Baseline vs Current");
		assertThat(content).contains("Regressed");
	}

	@Test
	void generateReport_createsOutputDirectory(@TempDir Path tmp) throws IOException {
		Path nested = tmp.resolve("deep/nested/dir");
		EnergyReport report = EnergyReport.of("run", Instant.now(), 60, List.of());
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 0, 0, List.of(), false,
				10.0);

		Path htmlFile = reporter.generateReport(report, comparison, nested);

		assertThat(htmlFile).exists();
	}

	@Test
	void generateAggregatedReport_createsHtmlFile(@TempDir Path tmp) throws IOException {
		List<AggregatedRunEntry> runs = List.of(
				new AggregatedRunEntry("oha",
						EnergyReport.of("run-oha", Instant.now(), 60,
								List.of(new EnergyMeasurement("app [app]", 30.0))),
						WorkloadStats.external("oha", 5000, 10, 60), null),
				new AggregatedRunEntry("wrk",
						EnergyReport.of("run-wrk", Instant.now(), 60,
								List.of(new EnergyMeasurement("app [app]", 35.0))),
						WorkloadStats.external("wrk", 6000, 5, 60), null));

		Path htmlFile = reporter.generateAggregatedReport(runs, PowerSource.RAPL, tmp);

		assertThat(htmlFile).exists();
		assertThat(htmlFile.getFileName().toString()).isEqualTo("greener-aggregated-report.html");

		String content = Files.readString(htmlFile);
		assertThat(content).contains("<!DOCTYPE html>")
			.contains("Aggregated")
			.contains("oha")
			.contains("wrk")
			.contains("run-oha")
			.contains("run-wrk");
	}

	@Test
	void generateAggregatedReport_includesPerToolTable(@TempDir Path tmp) throws IOException {
		List<AggregatedRunEntry> runs = List.of(new AggregatedRunEntry("k6",
				EnergyReport.of("run-k6", Instant.now(), 60, List.of(new EnergyMeasurement("app [app]", 42.0))),
				WorkloadStats.external("k6", 3000, 0, 60), null));

		Path htmlFile = reporter.generateAggregatedReport(runs, null, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Per-Tool Results").contains("k6").contains("3,000");
	}

	@Test
	void generateAggregatedReport_includesComparisonBadge(@TempDir Path tmp) throws IOException {
		ComparisonResult improved = new ComparisonResult(ComparisonStatus.IMPROVED, 100.0, 80.0, -20.0, List.of(),
				false, 10.0);

		List<AggregatedRunEntry> runs = List.of(new AggregatedRunEntry("bombardier",
				EnergyReport.of("run-bomb", Instant.now(), 60, List.of(new EnergyMeasurement("app [app]", 80.0))),
				WorkloadStats.external("bombardier", 4000, 0, 60), improved));

		Path htmlFile = reporter.generateAggregatedReport(runs, PowerSource.ESTIMATED, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Improved").contains("Measurement Assumptions").contains("CPU utilisation");
	}

	@Test
	void generateAggregatedReport_showsSummaryMetrics(@TempDir Path tmp) throws IOException {
		List<AggregatedRunEntry> runs = List.of(
				new AggregatedRunEntry("oha",
						EnergyReport.of("r1", Instant.now(), 60, List.of(new EnergyMeasurement("app [app]", 10.0))),
						WorkloadStats.external("oha", 1000, 0, 60), null),
				new AggregatedRunEntry("wrk",
						EnergyReport.of("r2", Instant.now(), 60, List.of(new EnergyMeasurement("app [app]", 20.0))),
						WorkloadStats.external("wrk", 2000, 0, 60), null));

		Path htmlFile = reporter.generateAggregatedReport(runs, null, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Aggregated Summary").contains("30.00").contains("3,000");
	}

	@Test
	void generateAggregatedReport_includesDetailCards(@TempDir Path tmp) throws IOException {
		List<AggregatedRunEntry> runs = List.of(new AggregatedRunEntry("ab", EnergyReport.of("run-ab", Instant.now(),
				60,
				List.of(new EnergyMeasurement("app [app]", 15.0), new EnergyMeasurement("system [total cpu]", 50.0))),
				null, null));

		Path htmlFile = reporter.generateAggregatedReport(runs, null, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("ab — Run Details").contains("app [app]").contains("system [total cpu]");
	}

	@Test
	void generateAggregatedReport_includesFooter(@TempDir Path tmp) throws IOException {
		List<AggregatedRunEntry> runs = List
			.of(new AggregatedRunEntry("locust", EnergyReport.of("run-loc", Instant.now(), 60, List.of()), null, null));

		Path htmlFile = reporter.generateAggregatedReport(runs, null, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Patrick Baumgartner").contains("Greener Spring Boot");
	}

	@Test
	void generateReport_includesMethodLevelCard(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-ml", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 50.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 50.0, 0, List.of(), false,
				10.0);
		EnergyReport appReport = EnergyReport.of("run-ml", Instant.now(), 60,
				List.of(new EnergyMeasurement("com.example.MyService.doWork", 5.0)));
		EnergyReport allReport = EnergyReport.of("run-ml", Instant.now(), 60,
				List.of(new EnergyMeasurement("com.example.MyService.doWork", 5.0),
						new EnergyMeasurement("org.springframework.web.DispatcherServlet.doDispatch", 3.0)));
		MethodLevelReports methodReports = new MethodLevelReports(appReport, allReport);

		Path htmlFile = reporter.generateReport(report, comparison, null, PowerSource.RAPL, methodReports, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Method-Level Energy (JoularJX)");
		assertThat(content).contains("com.example.MyService.doWork");
		assertThat(content).contains("org.springframework.web.DispatcherServlet.doDispatch");
		assertThat(content).contains("Show App Only");
		assertThat(content).contains("toggleMethodFilter");
		assertThat(content).contains("app-method");
	}

	@Test
	void generateReport_omitsTrendChart_whenFewerThanTwoEntries(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-t", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 10.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 10.0, 0, List.of(), false,
				10.0);
		List<com.patbaumgartner.greener.core.model.TrendEntry> single = List
			.of(new com.patbaumgartner.greener.core.model.TrendEntry(Instant.parse("2025-01-01T00:00:00Z"), "only",
					10.0, null, null, null));

		Path htmlFile = reporter.generateReport(report, comparison, null, PowerSource.RAPL, null, single, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).doesNotContain("Energy Trend");
	}

	@Test
	void generateReport_includesTrendChart_whenTwoOrMoreEntries(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("run-t", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 12.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 12.0, 0, List.of(), false,
				10.0);
		List<com.patbaumgartner.greener.core.model.TrendEntry> history = List.of(
				new com.patbaumgartner.greener.core.model.TrendEntry(Instant.parse("2025-01-01T00:00:00Z"), "r1", 10.0,
						0.5, null, null),
				new com.patbaumgartner.greener.core.model.TrendEntry(Instant.parse("2025-01-02T00:00:00Z"), "r2", 11.0,
						0.55, null, null),
				new com.patbaumgartner.greener.core.model.TrendEntry(Instant.parse("2025-01-03T00:00:00Z"), "r3", 12.0,
						0.60, null, null));

		Path htmlFile = reporter.generateReport(report, comparison, null, PowerSource.RAPL, null, history, tmp);

		String content = Files.readString(htmlFile);
		assertThat(content).contains("Energy Trend (last 3 runs)");
		assertThat(content).contains("<polyline");
		// Both series should render (cyan total + magenta mJ/req).
		assertThat(content).contains("var(--cyan)").contains("var(--magenta)");
		// Tooltips include each runId.
		assertThat(content).contains("r1").contains("r2").contains("r3");
	}

}
