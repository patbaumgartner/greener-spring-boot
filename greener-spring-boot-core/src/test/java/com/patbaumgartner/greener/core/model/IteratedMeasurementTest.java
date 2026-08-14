package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IteratedMeasurementTest {

	private static EnergyReport report(String runId, double joules) {
		return EnergyReport.of(runId, Instant.EPOCH, 60, List.of(new EnergyMeasurement("app", joules)));
	}

	@Test
	void iterationsReflectsThePerIterationReportCount() {
		IteratedMeasurement measurement = new IteratedMeasurement(report("run-1", 10.0),
				List.of(report("run-1", 10.0), report("run-2", 12.0)), WorkloadStats.external("oha", 100, 5, 60));

		assertThat(measurement.iterations()).isEqualTo(2);
	}

	@Test
	void nullPerIterationReportsBecomesAnEmptyList() {
		IteratedMeasurement measurement = new IteratedMeasurement(report("run-1", 10.0), null, null);

		assertThat(measurement.perIterationReports()).isEmpty();
		assertThat(measurement.iterations()).isZero();
	}

	@Test
	void perIterationReportsIsUnmodifiable() {
		IteratedMeasurement measurement = new IteratedMeasurement(report("run-1", 10.0), List.of(report("run-1", 10.0)),
				null);

		assertThatThrownBy(() -> measurement.perIterationReports().add(report("run-2", 12.0)))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void perIterationReportsIsDefensivelyCopiedFromTheCallersList() {
		List<EnergyReport> mutable = new ArrayList<>(List.of(report("run-1", 10.0)));
		IteratedMeasurement measurement = new IteratedMeasurement(report("run-1", 10.0), mutable, null);

		mutable.add(report("run-2", 12.0));

		assertThat(measurement.perIterationReports()).hasSize(1);
		assertThat(measurement.iterations()).isEqualTo(1);
	}

	@Test
	void negativeMethodLevelStartTimestampIsClampedToZero() {
		IteratedMeasurement measurement = new IteratedMeasurement(report("run-1", 10.0), List.of(), null, -5L);

		assertThat(measurement.methodLevelStartTimestampMs()).isZero();
	}

	@Test
	void convenienceConstructorDefaultsTheWarmupTimestampToZero() {
		IteratedMeasurement measurement = new IteratedMeasurement(report("run-1", 10.0), List.of(), null);

		assertThat(measurement.methodLevelStartTimestampMs()).isZero();
	}

	@Test
	void methodLevelStartTimestampIsPreservedWhenPositive() {
		IteratedMeasurement measurement = new IteratedMeasurement(report("run-1", 10.0), List.of(), null, 1_700_000L);

		assertThat(measurement.methodLevelStartTimestampMs()).isEqualTo(1_700_000L);
	}

}
