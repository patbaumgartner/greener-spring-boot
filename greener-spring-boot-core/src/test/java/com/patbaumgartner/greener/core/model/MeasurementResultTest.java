package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeasurementResultTest {

	@Test
	void record_accessors_returnConstructorValues() {
		EnergyReport report = new EnergyReport("run-1", Instant.now(), 60L, List.of(), 10.0);
		ComparisonResult comparison = new ComparisonResult(ComparisonResult.ComparisonStatus.NO_BASELINE, 0.0, 0.0, 0.0,
				null, false, 0.0);
		WorkloadStats stats = WorkloadStats.external("oha", 60, 1000, 0);
		Path htmlReport = Path.of("/tmp/report.html");

		MeasurementResult result = new MeasurementResult(report, comparison, stats, null, htmlReport);

		assertThat(result.report()).isSameAs(report);
		assertThat(result.comparison()).isSameAs(comparison);
		assertThat(result.workloadStats()).isSameAs(stats);
		assertThat(result.methodLevelReports()).isNull();
		assertThat(result.htmlReport()).isEqualTo(htmlReport);
	}

	@Test
	void record_equality_basedonComponents() {
		EnergyReport report = new EnergyReport("run-1", Instant.now(), 60L, List.of(), 10.0);
		ComparisonResult comparison = new ComparisonResult(ComparisonResult.ComparisonStatus.NO_BASELINE, 0.0, 0.0, 0.0,
				null, false, 0.0);
		WorkloadStats stats = WorkloadStats.external("oha", 60, 1000, 0);
		Path htmlReport = Path.of("/tmp/report.html");

		MeasurementResult a = new MeasurementResult(report, comparison, stats, null, htmlReport);
		MeasurementResult b = new MeasurementResult(report, comparison, stats, null, htmlReport);

		assertThat(a).isEqualTo(b);
	}

}
