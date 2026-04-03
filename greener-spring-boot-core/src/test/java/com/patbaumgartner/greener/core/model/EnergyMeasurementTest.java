package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EnergyMeasurementTest {

	@Test
	void constructor_validValues() {
		EnergyMeasurement m = new EnergyMeasurement("com.example.Service.run", 42.5);

		assertThat(m.methodName()).isEqualTo("com.example.Service.run");
		assertThat(m.energyJoules()).isEqualTo(42.5);
	}

	@Test
	void constructor_rejectsNullMethodName() {
		assertThatIllegalArgumentException().isThrownBy(() -> new EnergyMeasurement(null, 1.0));
	}

	@Test
	void constructor_rejectsBlankMethodName() {
		assertThatIllegalArgumentException().isThrownBy(() -> new EnergyMeasurement("  ", 1.0));
	}

	@Test
	void constructor_rejectsNegativeEnergy() {
		assertThatIllegalArgumentException().isThrownBy(() -> new EnergyMeasurement("method", -0.1));
	}

	@Test
	void constructor_zeroEnergyIsAllowed() {
		EnergyMeasurement m = new EnergyMeasurement("idle", 0.0);

		assertThat(m.energyJoules()).isEqualTo(0.0);
	}

	@Test
	void simpleMethodName_extractsLastSegment() {
		EnergyMeasurement m = new EnergyMeasurement("com.example.Service.doWork", 5.0);

		assertThat(m.simpleMethodName()).isEqualTo("doWork");
	}

	@Test
	void simpleMethodName_noDot_returnsFullName() {
		EnergyMeasurement m = new EnergyMeasurement("system [total cpu]", 100.0);

		assertThat(m.simpleMethodName()).isEqualTo("system [total cpu]");
	}

}
