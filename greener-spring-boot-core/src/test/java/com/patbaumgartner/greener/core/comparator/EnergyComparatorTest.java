package com.patbaumgartner.greener.core.comparator;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.Statistics;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyComparatorTest {

	private final EnergyComparator comparator = new EnergyComparator();

	private static final double THRESHOLD = 10.0;

	@Test
	void compare_noBaseline_returnsNoBaselineStatus() {
		EnergyReport current = buildReport(100.0);
		ComparisonResult result = comparator.compare(current, Optional.empty(), THRESHOLD);

		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.NO_BASELINE);
		assertThat(result.thresholdBreached()).isFalse();
		assertThat(result.isFailed()).isFalse();
	}

	@Test
	void compare_energyImproved_statusImproved() {
		EnergyReport current = buildReport(80.0); // 20% better

		ComparisonResult result = comparator.compare(current, Optional.of(EnergyBaseline.of(buildReport(100.0))),
				THRESHOLD);

		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.IMPROVED);
		assertThat(result.totalDeltaPercent()).isCloseTo(-20.0, Offset.offset(0.001));
		assertThat(result.isFailed()).isFalse();
	}

	@Test
	void compare_energyRegressedBeyondThreshold_failsBuild() {
		EnergyReport current = buildReport(115.0); // +15% — exceeds 10% threshold

		ComparisonResult result = comparator.compare(current, Optional.of(EnergyBaseline.of(buildReport(100.0))),
				THRESHOLD);

		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.REGRESSED);
		assertThat(result.thresholdBreached()).isTrue();
		assertThat(result.isFailed()).isTrue();
		assertThat(result.totalDeltaPercent()).isCloseTo(15.0, Offset.offset(0.001));
	}

	@Test
	void compare_energyRegressedWithinThreshold_unchanged() {
		EnergyReport current = buildReport(105.0); // +5% — within 10% threshold

		ComparisonResult result = comparator.compare(current, Optional.of(EnergyBaseline.of(buildReport(100.0))),
				THRESHOLD);

		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.UNCHANGED);
		assertThat(result.thresholdBreached()).isFalse();
	}

	@Test
	void compare_methodLevelComparisonsIncluded() {
		EnergyReport baseline = new EnergyReport("b", Instant.now(), 60,
				List.of(new EnergyMeasurement("com.example.A.method", 50.0),
						new EnergyMeasurement("com.example.B.method", 50.0)),
				100.0);

		EnergyReport current = new EnergyReport("c", Instant.now(), 60,
				List.of(new EnergyMeasurement("com.example.A.method", 60.0), // +20%
						new EnergyMeasurement("com.example.B.method", 50.0)),
				110.0);

		ComparisonResult result = comparator.compare(current, Optional.of(EnergyBaseline.of(baseline)), THRESHOLD);

		assertThat(result.methodComparisons()).hasSize(2);

		ComparisonResult.MethodComparison methodA = result.methodComparisons()
			.stream()
			.filter(mc -> mc.methodName().equals("com.example.A.method"))
			.findFirst()
			.orElseThrow();

		assertThat(methodA.deltaPercent()).isCloseTo(20.0, Offset.offset(0.001));
		assertThat(methodA.isRegressed(THRESHOLD)).isTrue();
	}

	@Test
	void compare_methodPresentInBaselineButNotCurrent_appearsWithZeroEnergy() {
		EnergyReport baseline = new EnergyReport("b", Instant.now(), 60,
				List.of(new EnergyMeasurement("com.example.OldMethod.run", 30.0)), 30.0);

		EnergyReport current = new EnergyReport("c", Instant.now(), 60,
				List.of(new EnergyMeasurement("com.example.NewMethod.run", 20.0)), 20.0);

		ComparisonResult result = comparator.compare(current, Optional.of(EnergyBaseline.of(baseline)), THRESHOLD);

		boolean hasOldMethod = result.methodComparisons()
			.stream()
			.anyMatch(mc -> mc.methodName().equals("com.example.OldMethod.run") && mc.currentEnergyJoules() == 0.0);
		assertThat(hasOldMethod).isTrue();
	}

	@Test
	void compare_zeroBaselineEnergy_noBaseline() {
		ComparisonResult result = comparator.compare(buildReport(5.0), Optional.of(EnergyBaseline.of(buildReport(0.0))),
				THRESHOLD);

		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.NO_BASELINE);
	}

	// --- Statistical mode tests -----------------------------------------------------

	@Test
	void compare_statisticalMode_smallEffectIgnoresLowPValue() {
		// Tiny mean shift but tight noise → very small p but |d| < 0.5.
		// Statistical mode must report UNCHANGED.
		Statistics base = Statistics.of(new double[] { 100.0, 100.1, 99.9, 100.0, 100.2, 99.8 });
		Statistics curr = Statistics.of(new double[] { 100.5, 100.4, 100.6, 100.5, 100.5, 100.5 });

		EnergyReport baseRep = withStats(buildReport(base.mean()), base);
		EnergyReport currRep = withStats(buildReport(curr.mean()), curr);

		ComparisonResult result = comparator.compare(currRep, Optional.of(EnergyBaseline.of(baseRep)), THRESHOLD);

		assertThat(result.statisticalDecision()).isTrue();
		assertThat(result.cohenD()).isNotNull();
		assertThat(result.pValue()).isNotNull();
		// Even though p is tiny, effect-size gate must keep us at UNCHANGED.
		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.UNCHANGED);
		assertThat(result.thresholdBreached()).isFalse();
	}

	@Test
	void compare_statisticalMode_largeEffectAndSignificant_regresses() {
		Statistics base = Statistics.of(new double[] { 100.0, 101.0, 99.0, 100.5, 100.5 });
		// 30% higher with low noise → large d, tiny p, delta% above threshold.
		Statistics curr = Statistics.of(new double[] { 130.0, 131.0, 129.0, 130.5, 130.5 });

		EnergyReport baseRep = withStats(buildReport(base.mean()), base);
		EnergyReport currRep = withStats(buildReport(curr.mean()), curr);

		ComparisonResult result = comparator.compare(currRep, Optional.of(EnergyBaseline.of(baseRep)), THRESHOLD);

		assertThat(result.statisticalDecision()).isTrue();
		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.REGRESSED);
		assertThat(result.thresholdBreached()).isTrue();
		assertThat(result.cohenD()).isGreaterThan(0.5);
		assertThat(result.pValue()).isLessThan(0.05);
		assertThat(result.isFailed()).isTrue();
	}

	@Test
	void compare_statisticalMode_largeImprovement_isImproved() {
		Statistics base = Statistics.of(new double[] { 100.0, 101.0, 99.0, 100.5, 100.5 });
		Statistics curr = Statistics.of(new double[] { 70.0, 71.0, 69.0, 70.5, 70.5 });

		EnergyReport baseRep = withStats(buildReport(base.mean()), base);
		EnergyReport currRep = withStats(buildReport(curr.mean()), curr);

		ComparisonResult result = comparator.compare(currRep, Optional.of(EnergyBaseline.of(baseRep)), THRESHOLD);

		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.IMPROVED);
		assertThat(result.cohenD()).isLessThan(-0.5);
		assertThat(result.pValue()).isLessThan(0.05);
	}

	@Test
	void compare_singleIterationBaseline_fallsBackToThresholdMode() {
		// Current has stats but baseline does not → fall back to legacy % rule.
		Statistics curr = Statistics.of(new double[] { 130.0, 131.0, 129.0 });
		EnergyReport currRep = withStats(buildReport(130.0), curr);
		EnergyReport baseRep = buildReport(100.0);

		ComparisonResult result = comparator.compare(currRep, Optional.of(EnergyBaseline.of(baseRep)), THRESHOLD);

		assertThat(result.statisticalDecision()).isFalse();
		assertThat(result.pValue()).isNull();
		// Legacy rule: 30% > 10% → REGRESSED.
		assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.REGRESSED);
	}

	private static EnergyReport withStats(EnergyReport rep, Statistics stats) {
		return rep.withStats(stats);
	}

	private EnergyReport buildReport(double totalJoules) {
		return new EnergyReport("test-run", Instant.now(), 60L, List.of(), totalJoules);
	}

}
