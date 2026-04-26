package com.patbaumgartner.greener.core.model;

import com.patbaumgartner.greener.core.model.Statistics.WelchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class StatisticsTest {

	@Test
	void emptyAndSingleAreDegenerate() {
		assertThat(Statistics.empty().n()).isZero();
		Statistics one = Statistics.single(42.0);
		assertThat(one.n()).isEqualTo(1);
		assertThat(one.mean()).isEqualTo(42.0);
		assertThat(one.stddev()).isZero();
		assertThat(one.median()).isEqualTo(42.0);
		assertThat(one.hasVariance()).isFalse();
	}

	@Test
	void ofComputesMeanStdDevMinMaxMedian() {
		// Samples chosen so that the descriptive stats are exact.
		double[] samples = { 2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0 };
		Statistics s = Statistics.of(samples);
		assertThat(s.n()).isEqualTo(8);
		assertThat(s.mean()).isEqualTo(5.0);
		// sample variance (n-1) = 32/7; sd = sqrt(32/7) ≈ 2.13809
		assertThat(s.stddev()).isCloseTo(2.13809, within(1e-4));
		assertThat(s.min()).isEqualTo(2.0);
		assertThat(s.max()).isEqualTo(9.0);
		assertThat(s.median()).isEqualTo(4.5);
		assertThat(s.hasVariance()).isTrue();
		assertThat(s.coefficientOfVariationPercent()).isCloseTo(42.7618, within(1e-3));
	}

	@Test
	void medianHandlesOddSampleCount() {
		Statistics s = Statistics.of(new double[] { 3.0, 1.0, 2.0 });
		assertThat(s.median()).isEqualTo(2.0);
	}

	@Test
	void ci95HalfMatchesTextbookFormula() {
		// 5 samples drawn from population mean 100 with deliberate spread.
		double[] samples = { 95.0, 98.0, 100.0, 102.0, 105.0 };
		Statistics s = Statistics.of(samples);
		assertThat(s.mean()).isEqualTo(100.0);
		// stddev = sqrt(58/4) ≈ 3.8079; tCrit(df=4)=2.7764; ci95half ≈ 2.7764 * 3.8079
		// / sqrt(5) ≈ 4.728
		assertThat(s.stddev()).isCloseTo(3.8079, within(1e-3));
		assertThat(s.ci95Half()).isCloseTo(4.728, within(0.01));
	}

	@Test
	void rejectsInvalidConstructorArgs() {
		assertThatThrownBy(() -> new Statistics(-1, 0, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Statistics(1, 0, -0.1, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Statistics(1, 0, 0, 0, 0, 0, -0.1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void coefficientOfVariationIsNaNForZeroMean() {
		assertThat(Statistics.empty().coefficientOfVariationPercent()).isNaN();
	}

	@Test
	void welchTReturnsNaNWithoutVariance() {
		Statistics a = Statistics.single(1.0);
		Statistics b = Statistics.single(2.0);
		WelchResult r = a.welchTTest(b);
		assertThat(r.t()).isNaN();
		assertThat(r.pValueTwoSided()).isNaN();
	}

	@Test
	void welchTMatchesTextbookExample() {
		// Hand-computed reference: A = {27,20,22,28,21,29,23,18}, meanA=23.5,
		// varA=114/7=16.2857; B = {25,23,24,28,21,22,29,30}, meanB=25.25,
		// varB=79.5/7=11.3571. Welch t = (23.5-25.25)/sqrt(16.2857/8 + 11.3571/8)
		// = -1.75 / 1.8590 ≈ -0.9414; Welch–Satterthwaite df ≈ 13.62.
		Statistics a = Statistics.of(new double[] { 27, 20, 22, 28, 21, 29, 23, 18 });
		Statistics b = Statistics.of(new double[] { 25, 23, 24, 28, 21, 22, 29, 30 });
		WelchResult r = a.welchTTest(b);
		assertThat(r.t()).isCloseTo(-0.9414, within(0.005));
		assertThat(r.df()).isCloseTo(13.62, within(0.1));
		// Two-sided p for t≈-0.94, df≈13.6 is ≈ 0.36 — null hypothesis kept.
		assertThat(r.pValueTwoSided()).isBetween(0.30, 0.40);
	}

	@Test
	void welchTDetectsClearRegression() {
		// Current run substantially higher than baseline, n=5 each.
		Statistics current = Statistics.of(new double[] { 120, 122, 119, 121, 123 });
		Statistics baseline = Statistics.of(new double[] { 100, 101, 99, 102, 100 });
		WelchResult r = current.welchTTest(baseline);
		assertThat(r.t()).isPositive();
		assertThat(r.pValueTwoSided()).isLessThan(0.001);
	}

	@Test
	void welchTHandlesIdenticalZeroVarianceSamples() {
		Statistics a = Statistics.of(new double[] { 5.0, 5.0, 5.0 });
		Statistics b = Statistics.of(new double[] { 5.0, 5.0, 5.0 });
		WelchResult r = a.welchTTest(b);
		assertThat(r.pValueTwoSided()).isEqualTo(1.0);
	}

	@Test
	void cohenDClassifiesEffectSize() {
		Statistics tinyShift = Statistics.of(new double[] { 100, 100.5, 99.5, 100, 100 });
		Statistics baseline = Statistics.of(new double[] { 99, 100, 101, 100, 100 });
		assertThat(Math.abs(tinyShift.cohenD(baseline))).isLessThan(0.5);

		Statistics large = Statistics.of(new double[] { 130, 132, 128, 131, 129 });
		assertThat(large.cohenD(baseline)).isGreaterThan(0.8);
	}

	@Test
	void studentTPValuesMatchTables() {
		// t=2.776, df=4 -> two-sided p ≈ 0.05
		assertThat(StudentT.twoSidedPValue(2.776, 4)).isCloseTo(0.05, within(0.001));
		// t=2.228, df=10 -> p ≈ 0.05
		assertThat(StudentT.twoSidedPValue(2.228, 10)).isCloseTo(0.05, within(0.001));
		// t=1.96, df=∞ -> p ≈ 0.05
		assertThat(StudentT.twoSidedPValue(1.96, Double.POSITIVE_INFINITY)).isCloseTo(0.05, within(0.001));
		// t=0 -> p=1
		assertThat(StudentT.twoSidedPValue(0.0, 10)).isCloseTo(1.0, within(1e-9));
	}

	@Test
	void criticalValueMatchesTable() {
		assertThat(StudentT.criticalTwoTailed95(1)).isCloseTo(12.706, within(0.01));
		assertThat(StudentT.criticalTwoTailed95(4)).isCloseTo(2.776, within(0.01));
		assertThat(StudentT.criticalTwoTailed95(10)).isCloseTo(2.228, within(0.01));
		// Above tabulated range, falls back to bisection inverse.
		assertThat(StudentT.criticalTwoTailed95(60)).isCloseTo(2.000, within(0.01));
		assertThat(StudentT.criticalTwoTailed95(120)).isCloseTo(1.980, within(0.01));
	}

	@Test
	void normalCdfMatchesKnownValues() {
		assertThat(StudentT.normalCdf(0.0)).isCloseTo(0.5, within(1e-6));
		assertThat(StudentT.normalCdf(1.96)).isCloseTo(0.975, within(1e-3));
		assertThat(StudentT.normalCdf(-1.96)).isCloseTo(0.025, within(1e-3));
	}

}
