package com.patbaumgartner.greener.core.model;

import java.util.Collections;
import java.util.List;

/**
 * Holds the result of comparing current energy measurements against a stored baseline.
 *
 * <p>
 * <b>Statistical fields.</b> When both the current report and the baseline carry
 * multi-iteration {@link Statistics}, the comparator computes Welch's t-test
 * ({@link #pValue()}) and Cohen's d effect size ({@link #cohenD()}) and sets
 * {@link #statisticalDecision()} to {@code true}. With a single-iteration run, those
 * fields are {@code null} and the comparator decides via the percentage threshold only.
 *
 * <p>
 * Nullable {@link Double}s keep the type Jackson-friendly without requiring the
 * {@code jackson-datatype-jdk8} module.
 */
public record ComparisonResult(ComparisonStatus overallStatus, double baselineTotalJoules, double currentTotalJoules,
		double totalDeltaPercent, List<MethodComparison> methodComparisons, boolean thresholdBreached, double threshold,
		Double pValue, Double cohenD, boolean statisticalDecision, RegressionMetric metricUsed,
		Double baselineEnergyPerRequestMillijoules, Double currentEnergyPerRequestMillijoules) {

	public ComparisonResult {
		methodComparisons = methodComparisons == null ? Collections.emptyList()
				: Collections.unmodifiableList(methodComparisons);
		if (metricUsed == null) {
			metricUsed = RegressionMetric.ENERGY_PER_REQUEST;
		}
	}

	/**
	 * Convenience constructor for threshold-only (single-iteration) comparisons.
	 */
	public ComparisonResult(ComparisonStatus overallStatus, double baselineTotalJoules, double currentTotalJoules,
			double totalDeltaPercent, List<MethodComparison> methodComparisons, boolean thresholdBreached,
			double threshold) {
		this(overallStatus, baselineTotalJoules, currentTotalJoules, totalDeltaPercent, methodComparisons,
				thresholdBreached, threshold, null, null, false, RegressionMetric.ENERGY_PER_REQUEST, null, null);
	}

	/**
	 * Convenience constructor for statistical comparisons that don't track per-request
	 * metrics.
	 */
	public ComparisonResult(ComparisonStatus overallStatus, double baselineTotalJoules, double currentTotalJoules,
			double totalDeltaPercent, List<MethodComparison> methodComparisons, boolean thresholdBreached,
			double threshold, Double pValue, Double cohenD, boolean statisticalDecision) {
		this(overallStatus, baselineTotalJoules, currentTotalJoules, totalDeltaPercent, methodComparisons,
				thresholdBreached, threshold, pValue, cohenD, statisticalDecision, RegressionMetric.ENERGY_PER_REQUEST,
				null, null);
	}

	/** {@code true} when the build should be failed due to an energy regression. */
	public boolean isFailed() {
		return thresholdBreached && overallStatus == ComparisonStatus.REGRESSED;
	}

	public enum ComparisonStatus {

		/** Energy consumption decreased compared to baseline. */
		IMPROVED,
		/** Energy consumption increased beyond the configured threshold. */
		REGRESSED,
		/** Energy consumption changed within the configured threshold (noise). */
		UNCHANGED,
		/** No baseline available; comparison was skipped. */
		NO_BASELINE

	}

	/**
	 * Per-method (or per-process) comparison details.
	 */
	public record MethodComparison(String methodName, double baselineEnergyJoules, double currentEnergyJoules,
			double deltaPercent) {

		public boolean isRegressed(double threshold) {
			return deltaPercent > threshold;
		}

		public boolean isImproved() {
			return deltaPercent < 0;
		}
	}
}
