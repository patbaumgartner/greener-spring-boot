package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonResultTest {

	@Test
	void isFailed_regressionWithThresholdBreached_returnsTrue() {
		ComparisonResult result = new ComparisonResult(ComparisonResult.ComparisonStatus.REGRESSED, 100.0, 120.0, 20.0,
				List.of(), true, 10.0);

		assertThat(result.isFailed()).isTrue();
	}

	@Test
	void isFailed_regressionWithoutThresholdBreached_returnsFalse() {
		ComparisonResult result = new ComparisonResult(ComparisonResult.ComparisonStatus.REGRESSED, 100.0, 105.0, 5.0,
				List.of(), false, 10.0);

		assertThat(result.isFailed()).isFalse();
	}

	@Test
	void isFailed_improved_returnsFalse() {
		ComparisonResult result = new ComparisonResult(ComparisonResult.ComparisonStatus.IMPROVED, 100.0, 80.0, -20.0,
				List.of(), false, 10.0);

		assertThat(result.isFailed()).isFalse();
	}

	@Test
	void isFailed_noBaseline_returnsFalse() {
		ComparisonResult result = new ComparisonResult(ComparisonResult.ComparisonStatus.NO_BASELINE, 0, 50.0, 0,
				List.of(), false, 10.0);

		assertThat(result.isFailed()).isFalse();
	}

	@Test
	void methodComparisons_nullTreatedAsEmpty() {
		ComparisonResult result = new ComparisonResult(ComparisonResult.ComparisonStatus.UNCHANGED, 100.0, 100.0, 0.0,
				null, false, 10.0);

		assertThat(result.methodComparisons()).isEmpty();
	}

	@Test
	void methodComparisons_areUnmodifiable() {
		ComparisonResult result = new ComparisonResult(ComparisonResult.ComparisonStatus.UNCHANGED, 100.0, 100.0, 0.0,
				List.of(), false, 10.0);

		assertThat(result.methodComparisons()).isUnmodifiable();
	}

	@Test
	void methodComparison_isRegressed_deltaAboveThreshold() {
		ComparisonResult.MethodComparison mc = new ComparisonResult.MethodComparison("method", 10.0, 15.0, 50.0);

		assertThat(mc.isRegressed(10.0)).isTrue();
		assertThat(mc.isRegressed(50.0)).isFalse();
	}

	@Test
	void methodComparison_isImproved_negativeDelta() {
		ComparisonResult.MethodComparison mc = new ComparisonResult.MethodComparison("method", 10.0, 8.0, -20.0);

		assertThat(mc.isImproved()).isTrue();
	}

	@Test
	void methodComparison_notImproved_positiveDelta() {
		ComparisonResult.MethodComparison mc = new ComparisonResult.MethodComparison("method", 10.0, 12.0, 20.0);

		assertThat(mc.isImproved()).isFalse();
	}

}
