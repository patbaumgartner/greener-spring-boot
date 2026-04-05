package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleReporterTest {

	private final ByteArrayOutputStream capture = new ByteArrayOutputStream();

	private final ConsoleReporter reporter = new ConsoleReporter(10, new PrintStream(capture));

	@Test
	void report_printsEnergyData() {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60,
				List.of(new EnergyMeasurement("app [app]", 25.0), new EnergyMeasurement("system [total cpu]", 100.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 125.0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison);
		String output = capture.toString();

		assertThat(output).contains("Energy Consumption Report");
		assertThat(output).contains("run-1");
		assertThat(output).contains("125.00 J");
	}

	@Test
	void report_includesWorkloadStats() {
		EnergyReport report = EnergyReport.of("run-2", Instant.now(), 60, List.of(new EnergyMeasurement("app", 50.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 50.0, 0, List.of(), false,
				10.0);

		WorkloadStats stats = WorkloadStats.external("oha", 1000, 5, 60);

		reporter.report(report, comparison, stats);
		String output = capture.toString();

		assertThat(output).contains("Use-Case Energy");
		assertThat(output).contains("oha");
		assertThat(output).contains("1000 total");
	}

	@Test
	void report_includesPowerSource() {
		EnergyReport report = EnergyReport.of("run-3", Instant.now(), 60, List.of());
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison, null, PowerSource.RAPL);
		String output = capture.toString();

		assertThat(output).contains("RAPL");
	}

	@Test
	void report_showsComparisonWhenBaselinePresent() {
		EnergyReport report = EnergyReport.of("run-4", Instant.now(), 60, List.of(new EnergyMeasurement("app", 120.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.REGRESSED, 100.0, 120.0, 20.0,
				List.of(new ComparisonResult.MethodComparison("app", 100.0, 120.0, 20.0)), true, 10.0);

		reporter.report(report, comparison);
		String output = capture.toString();

		assertThat(output).contains("Baseline comparison");
		assertThat(output).contains("REGRESSED");
	}

	@Test
	void report_showsNoDataHint_whenNoMeasurements() {
		EnergyReport report = EnergyReport.of("run-5", Instant.now(), 60, List.of());
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison);
		String output = capture.toString();

		assertThat(output).contains("No energy data recorded");
	}

	@Test
	void report_showsImprovedStatus() {
		EnergyReport report = EnergyReport.of("run-6", Instant.now(), 60, List.of(new EnergyMeasurement("app", 80.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.IMPROVED, 100.0, 80.0, -20.0, List.of(),
				false, 10.0);

		reporter.report(report, comparison);
		String output = capture.toString();

		assertThat(output).contains("IMPROVED");
	}

	@Test
	void report_showsUnchangedStatus() {
		EnergyReport report = EnergyReport.of("run-7", Instant.now(), 60, List.of(new EnergyMeasurement("app", 100.0)));

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.UNCHANGED, 100.0, 100.0, 0.0, List.of(),
				false, 10.0);

		reporter.report(report, comparison);
		String output = capture.toString();

		assertThat(output).contains("UNCHANGED");
	}

	@Test
	void report_workloadStatsWithoutRequestCounts() {
		EnergyReport report = EnergyReport.of("run-8", Instant.now(), 60, List.of(new EnergyMeasurement("app", 50.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 50.0, 0, List.of(), false,
				10.0);

		WorkloadStats stats = WorkloadStats.external("custom-tool", 60);

		reporter.report(report, comparison, stats);
		String output = capture.toString();

		assertThat(output).contains("N/A");
		assertThat(output).contains("Avg Power");
	}

	@Test
	void report_withNullComparison() {
		EnergyReport report = EnergyReport.of("run-9", Instant.now(), 60, List.of(new EnergyMeasurement("app", 10.0)));

		reporter.report(report, null);
		String output = capture.toString();

		assertThat(output).contains("Energy Consumption Report");
		assertThat(output).doesNotContain("Baseline comparison");
	}

	@Test
	void report_truncatesLongMethodNames() {
		String longName = "com.example.very.long.package.name.ClassName.methodName";
		EnergyReport report = EnergyReport.of("run-10", Instant.now(), 60,
				List.of(new EnergyMeasurement(longName, 42.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 42.0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison);
		String output = capture.toString();

		assertThat(output).contains("42.00");
	}

	@Test
	void report_twoArgOverload_worksWithoutWorkloadStats() {
		EnergyReport report = EnergyReport.of("run-11", Instant.now(), 60, List.of(new EnergyMeasurement("app", 10.0)));
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 10.0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison);
		String output = capture.toString();

		assertThat(output).doesNotContain("Use-Case Energy");
	}

	@Test
	void report_showsVmPowerSource() {
		EnergyReport report = EnergyReport.of("run-12", Instant.now(), 60, List.of());
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison, null, PowerSource.VM_FILE);
		String output = capture.toString();

		assertThat(output).contains("Power Source");
	}

	@Test
	void report_showsEstimatedPowerSource() {
		EnergyReport report = EnergyReport.of("run-13", Instant.now(), 60, List.of());
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison, null, PowerSource.ESTIMATED);
		String output = capture.toString();

		assertThat(output).contains("Power Source");
	}

	@Test
	void report_unknownPowerSource_doesNotShowIt() {
		EnergyReport report = EnergyReport.of("run-14", Instant.now(), 60, List.of());
		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 0, 0, List.of(), false,
				10.0);

		reporter.report(report, comparison, null, PowerSource.UNKNOWN);
		String output = capture.toString();

		assertThat(output).doesNotContain("Power Source");
	}

	@Test
	void defaultConstructor_usesDefaultTopN() {
		ConsoleReporter defaultReporter = new ConsoleReporter();
		assertThat(defaultReporter).isNotNull();
	}

}
