package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EnergyReportTest {

	private static final Instant NOW = Instant.now();

	@Test
	void of_derivesTotalFromMeasurements() {
		List<EnergyMeasurement> measurements = List.of(new EnergyMeasurement("a", 10.0),
				new EnergyMeasurement("b", 20.0));

		EnergyReport report = EnergyReport.of("run-1", NOW, 60, measurements);

		assertThat(report.totalEnergyJoules()).isEqualTo(30.0);
	}

	@Test
	void of_emptyMeasurements_zeroTotal() {
		EnergyReport report = EnergyReport.of("run-1", NOW, 60, List.of());

		assertThat(report.totalEnergyJoules()).isEqualTo(0.0);
		assertThat(report.measurements()).isEmpty();
	}

	@Test
	void constructor_rejectsNegativeEnergy() {
		assertThatIllegalArgumentException().isThrownBy(() -> new EnergyReport("run", NOW, 60, List.of(), -1.0));
	}

	@Test
	void constructor_nullMeasurements_treatedAsEmpty() {
		EnergyReport report = new EnergyReport("run", NOW, 60, null, 0.0);

		assertThat(report.measurements()).isEmpty();
	}

	@Test
	void measurements_areUnmodifiable() {
		EnergyReport report = EnergyReport.of("run", NOW, 60, List.of(new EnergyMeasurement("a", 5.0)));

		assertThat(report.measurements()).isUnmodifiable();
	}

	@Test
	void topMeasurements_returnsDescendingByEnergy() {
		List<EnergyMeasurement> measurements = List.of(new EnergyMeasurement("low", 1.0),
				new EnergyMeasurement("high", 30.0), new EnergyMeasurement("mid", 10.0));

		EnergyReport report = EnergyReport.of("run", NOW, 60, measurements);

		List<EnergyMeasurement> top = report.topMeasurements(2);

		assertThat(top).hasSize(2);
		assertThat(top.get(0).methodName()).isEqualTo("high");
		assertThat(top.get(1).methodName()).isEqualTo("mid");
	}

	@Test
	void topMeasurements_requestMoreThanAvailable_returnsAll() {
		EnergyReport report = EnergyReport.of("run", NOW, 60, List.of(new EnergyMeasurement("only", 5.0)));

		assertThat(report.topMeasurements(10)).hasSize(1);
	}

}
