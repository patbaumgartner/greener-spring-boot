package com.patbaumgartner.greener.core.comparator;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
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
        EnergyReport current = buildReport(80.0);  // 20% better

        ComparisonResult result = comparator.compare(current,
                Optional.of(EnergyBaseline.of(buildReport(100.0))), THRESHOLD);

        assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.IMPROVED);
        assertThat(result.totalDeltaPercent()).isCloseTo(-20.0, Offset.offset(0.001));
        assertThat(result.isFailed()).isFalse();
    }

    @Test
    void compare_energyRegressedBeyondThreshold_failsBuild() {
        EnergyReport current = buildReport(115.0);  // +15% — exceeds 10% threshold

        ComparisonResult result = comparator.compare(current,
                Optional.of(EnergyBaseline.of(buildReport(100.0))), THRESHOLD);

        assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.REGRESSED);
        assertThat(result.thresholdBreached()).isTrue();
        assertThat(result.isFailed()).isTrue();
        assertThat(result.totalDeltaPercent()).isCloseTo(15.0, Offset.offset(0.001));
    }

    @Test
    void compare_energyRegressedWithinThreshold_unchanged() {
        EnergyReport current = buildReport(105.0);  // +5% — within 10% threshold

        ComparisonResult result = comparator.compare(current,
                Optional.of(EnergyBaseline.of(buildReport(100.0))), THRESHOLD);

        assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.UNCHANGED);
        assertThat(result.thresholdBreached()).isFalse();
    }

    @Test
    void compare_methodLevelComparisonsIncluded() {
        EnergyReport baseline = new EnergyReport("b", Instant.now(), 60, List.of(
                new EnergyMeasurement("com.example.A.method", 50.0),
                new EnergyMeasurement("com.example.B.method", 50.0)), 100.0);

        EnergyReport current = new EnergyReport("c", Instant.now(), 60, List.of(
                new EnergyMeasurement("com.example.A.method", 60.0),  // +20%
                new EnergyMeasurement("com.example.B.method", 50.0)), 110.0);

        ComparisonResult result = comparator.compare(current,
                Optional.of(EnergyBaseline.of(baseline)), THRESHOLD);

        assertThat(result.methodComparisons()).hasSize(2);

        ComparisonResult.MethodComparison methodA = result.methodComparisons().stream()
                .filter(mc -> mc.methodName().equals("com.example.A.method"))
                .findFirst().orElseThrow();

        assertThat(methodA.deltaPercent()).isCloseTo(20.0, Offset.offset(0.001));
        assertThat(methodA.isRegressed(THRESHOLD)).isTrue();
    }

    @Test
    void compare_methodPresentInBaselineButNotCurrent_appearsWithZeroEnergy() {
        EnergyReport baseline = new EnergyReport("b", Instant.now(), 60,
                List.of(new EnergyMeasurement("com.example.OldMethod.run", 30.0)), 30.0);

        EnergyReport current = new EnergyReport("c", Instant.now(), 60,
                List.of(new EnergyMeasurement("com.example.NewMethod.run", 20.0)), 20.0);

        ComparisonResult result = comparator.compare(current,
                Optional.of(EnergyBaseline.of(baseline)), THRESHOLD);

        boolean hasOldMethod = result.methodComparisons().stream()
                .anyMatch(mc -> mc.methodName().equals("com.example.OldMethod.run")
                        && mc.currentEnergyJoules() == 0.0);
        assertThat(hasOldMethod).isTrue();
    }

    @Test
    void compare_zeroBaselineEnergy_noBaseline() {
        ComparisonResult result = comparator.compare(buildReport(5.0),
                Optional.of(EnergyBaseline.of(buildReport(0.0))), THRESHOLD);

        assertThat(result.overallStatus()).isEqualTo(ComparisonStatus.NO_BASELINE);
    }

    private EnergyReport buildReport(double totalJoules) {
        return new EnergyReport("test-run", Instant.now(), 60L, List.of(), totalJoules);
    }
}
