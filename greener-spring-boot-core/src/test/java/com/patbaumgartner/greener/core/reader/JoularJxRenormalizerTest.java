package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.MethodLevelReports;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JoularJxRenormalizerTest {

	private static final Offset<Double> EPSILON = Offset.offset(1.0e-9);

	@Test
	void renormalize_overAttribution_scalesDown() {
		// Process energy is 100 J, JoularJX attributed 200 J across two methods.
		EnergyReport methodReport = report(
				List.of(new EnergyMeasurement("A", 120.0), new EnergyMeasurement("B", 80.0)));

		EnergyReport renormalised = JoularJxRenormalizer.renormalize(methodReport, 100.0);

		assertThat(renormalised.totalEnergyJoules()).isEqualTo(100.0, EPSILON);
		assertThat(renormalised.measurements().get(0).energyJoules()).isEqualTo(60.0, EPSILON);
		assertThat(renormalised.measurements().get(1).energyJoules()).isEqualTo(40.0, EPSILON);
		// Relative shares preserved.
		double aShare = renormalised.measurements().get(0).energyJoules() / renormalised.totalEnergyJoules();
		assertThat(aShare).isEqualTo(0.6, EPSILON);
	}

	@Test
	void renormalize_underAttribution_isLeftAlone() {
		// JoularJX saw only 50 J of a 100 J process — scaling up would invent energy.
		EnergyReport methodReport = report(List.of(new EnergyMeasurement("A", 30.0), new EnergyMeasurement("B", 20.0)));

		EnergyReport renormalised = JoularJxRenormalizer.renormalize(methodReport, 100.0);

		assertThat(renormalised).isSameAs(methodReport);
	}

	@Test
	void renormalize_nullOrZeroProcessEnergy_returnsInputUnchanged() {
		EnergyReport methodReport = report(List.of(new EnergyMeasurement("A", 10.0)));

		assertThat(JoularJxRenormalizer.renormalize(methodReport, 0.0)).isSameAs(methodReport);
		assertThat(JoularJxRenormalizer.renormalize(methodReport, -5.0)).isSameAs(methodReport);
		assertThat(JoularJxRenormalizer.renormalize((EnergyReport) null, 100.0)).isNull();
	}

	@Test
	void renormalize_emptyMeasurements_returnsInput() {
		EnergyReport methodReport = report(List.of());

		assertThat(JoularJxRenormalizer.renormalize(methodReport, 100.0)).isSameAs(methodReport);
	}

	@Test
	void factor_overAttribution_returnsScaleFactor() {
		EnergyReport methodReport = report(List.of(new EnergyMeasurement("A", 250.0)));

		assertThat(JoularJxRenormalizer.factor(methodReport, 100.0)).isEqualTo(0.4, EPSILON);
	}

	@Test
	void factor_underAttribution_returnsOne() {
		EnergyReport methodReport = report(List.of(new EnergyMeasurement("A", 50.0)));

		assertThat(JoularJxRenormalizer.factor(methodReport, 100.0)).isEqualTo(1.0, EPSILON);
	}

	@Test
	void renormalize_methodLevelReports_scalesBothSides() {
		EnergyReport app = report(List.of(new EnergyMeasurement("app.A", 60.0)));
		EnergyReport all = report(List.of(new EnergyMeasurement("app.A", 60.0), new EnergyMeasurement("jdk.B", 60.0)));
		MethodLevelReports reports = new MethodLevelReports(app, all);

		MethodLevelReports renormalised = JoularJxRenormalizer.renormalize(reports, 100.0);

		assertThat(renormalised.appReport()).isSameAs(app); // 60 < 100 → unchanged
		assertThat(renormalised.allReport().totalEnergyJoules()).isEqualTo(100.0, EPSILON);
	}

	@Test
	void renormalize_nullMethodLevelReports_returnsNull() {
		assertThat(JoularJxRenormalizer.renormalize((MethodLevelReports) null, 100.0)).isNull();
	}

	private static EnergyReport report(List<EnergyMeasurement> measurements) {
		double total = measurements.stream().mapToDouble(EnergyMeasurement::energyJoules).sum();
		return new EnergyReport("run", Instant.parse("2024-01-01T00:00:00Z"), 60L, measurements, total);
	}

}
