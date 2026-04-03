package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
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

		WorkloadStats stats = WorkloadStats.builtIn(1000, 5, 60);

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

}
