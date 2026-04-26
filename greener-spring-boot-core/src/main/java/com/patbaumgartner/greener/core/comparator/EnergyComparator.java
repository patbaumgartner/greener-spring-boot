package com.patbaumgartner.greener.core.comparator;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.RegressionMetric;
import com.patbaumgartner.greener.core.model.Statistics;
import com.patbaumgartner.greener.core.model.Statistics.WelchResult;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Compares the current {@link EnergyReport} against a stored {@link EnergyBaseline}.
 *
 * <h2>Comparison logic</h2>
 *
 * <p>
 * The comparator picks one of two decision modes depending on the input data.
 *
 * <h3>Statistical mode (preferred)</h3>
 *
 * Activated when <em>both</em> the current report and the baseline report carry
 * {@link Statistics} with at least two samples each. The comparator then evaluates:
 * <ul>
 * <li><b>Welch's two-sample t-test</b> for unequal variances on the per-iteration totals
 * &rarr; two-sided p-value <em>p</em>.</li>
 * <li><b>Cohen's d</b> using a pooled standard deviation &rarr; effect size <em>d</em>.
 * </li>
 * </ul>
 * Decision rules (Cohen 1988 conventions):
 * <ol>
 * <li>{@code |d| < 0.5} (small effect) &rarr; {@link ComparisonStatus#UNCHANGED} <em>even
 * when</em> {@code p} is significant. This avoids flagging tiny differences that pass
 * statistical significance only because of large {@code n}.</li>
 * <li>{@code d ≥ 0.5} <b>and</b> {@code p < 0.05} <b>and</b> {@code delta% ≥ threshold}
 * &rarr; {@link ComparisonStatus#REGRESSED}.</li>
 * <li>{@code d ≤ -0.5} <b>and</b> {@code p < 0.05} &rarr;
 * {@link ComparisonStatus#IMPROVED}.</li>
 * <li>Otherwise &rarr; {@link ComparisonStatus#UNCHANGED}.</li>
 * </ol>
 *
 * <h3>Threshold mode (single-iteration)</h3>
 *
 * When statistics are unavailable (e.g. {@code iterations = 1} or a baseline produced
 * before iterations were enabled), the comparator uses the percentage rule:
 * {@code delta = (current − baseline) / baseline × 100 %}; values outside
 * {@code ±threshold} flip the status accordingly.
 */
public class EnergyComparator {

	/** Cohen's d threshold below which the effect is considered "small" (UNCHANGED). */
	private static final double SMALL_EFFECT_D = 0.5;

	/** Significance level for Welch's t-test. */
	private static final double ALPHA = 0.05;

	/**
	 * Compares the current report against an optional baseline using
	 * {@link RegressionMetric#ENERGY_PER_REQUEST} when request counts are available, or
	 * total energy otherwise.
	 * @param current the newly measured {@link EnergyReport}
	 * @param baseline an {@link Optional} wrapping the stored baseline (may be empty)
	 * @param threshold percentage change above/below which status flips from UNCHANGED
	 * (used in threshold mode and as a sanity gate in statistical mode)
	 * @return a {@link ComparisonResult} describing how this run compares to baseline
	 */
	public ComparisonResult compare(EnergyReport current, Optional<EnergyBaseline> baseline, double threshold) {
		return compare(current, baseline, threshold, RegressionMetric.ENERGY_PER_REQUEST, null);
	}

	/**
	 * Compares the current report against an optional baseline using the given metric.
	 *
	 * <p>
	 * When {@code metric} is {@link RegressionMetric#ENERGY_PER_REQUEST} and request
	 * counts are available on <em>both</em> sides (current via {@code currentWorkload};
	 * baseline via {@link EnergyBaseline#workloadStats()}), the comparator computes the
	 * delta on millijoules-per-request. When request counts are missing on either side,
	 * the comparator transparently degrades to {@link RegressionMetric#TOTAL_ENERGY}.
	 */
	public ComparisonResult compare(EnergyReport current, Optional<EnergyBaseline> baseline, double threshold,
			RegressionMetric metric, WorkloadStats currentWorkload) {
		RegressionMetric effectiveMetric = metric == null ? RegressionMetric.ENERGY_PER_REQUEST : metric;
		if (baseline.isEmpty()) {
			return new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, current.totalEnergyJoules(), 0, List.of(),
					false, threshold, null, null, false, effectiveMetric, null, null);
		}

		EnergyReport baselineReport = baseline.get().report();
		double baselineTotal = baselineReport.totalEnergyJoules();
		double currentTotal = current.totalEnergyJoules();

		List<MethodComparison> methodComparisons = buildMethodComparisons(current, baselineReport);

		WorkloadStats baselineWorkload = baseline.get().workloadStats();
		Double baselineMjPerReq = perRequestMillijoules(baselineTotal, baselineWorkload);
		Double currentMjPerReq = perRequestMillijoules(currentTotal, currentWorkload);
		boolean canUsePerRequest = effectiveMetric == RegressionMetric.ENERGY_PER_REQUEST && baselineMjPerReq != null
				&& currentMjPerReq != null;

		double comparisonBaseline = canUsePerRequest ? baselineMjPerReq : baselineTotal;
		double comparisonCurrent = canUsePerRequest ? currentMjPerReq : currentTotal;
		double totalDelta = computeDeltaPercent(comparisonBaseline, comparisonCurrent);
		RegressionMetric metricUsed = canUsePerRequest ? RegressionMetric.ENERGY_PER_REQUEST
				: RegressionMetric.TOTAL_ENERGY;

		Statistics curStats = current.totalEnergyStats();
		Statistics baseStats = baselineReport.totalEnergyStats();
		boolean canUseStats = curStats != null && baseStats != null && curStats.n() >= 2 && baseStats.n() >= 2;

		if (comparisonBaseline == 0) {
			return new ComparisonResult(ComparisonStatus.NO_BASELINE, baselineTotal, currentTotal, totalDelta,
					methodComparisons, false, threshold, null, null, false, metricUsed, baselineMjPerReq,
					currentMjPerReq);
		}

		ComparisonResult inner;
		if (canUseStats) {
			inner = decideStatistically(curStats, baseStats, baselineTotal, currentTotal, totalDelta, methodComparisons,
					threshold);
		}
		else {
			inner = decideByThreshold(baselineTotal, currentTotal, totalDelta, methodComparisons, threshold);
		}
		return new ComparisonResult(inner.overallStatus(), baselineTotal, currentTotal, totalDelta, methodComparisons,
				inner.thresholdBreached(), threshold, inner.pValue(), inner.cohenD(), inner.statisticalDecision(),
				metricUsed, baselineMjPerReq, currentMjPerReq);
	}

	private static Double perRequestMillijoules(double totalJoules, WorkloadStats workload) {
		if (workload == null || !workload.hasRequestCounts()) {
			return null;
		}
		double v = workload.energyPerRequestMillijoules(totalJoules);
		return Double.isNaN(v) ? null : v;
	}

	private ComparisonResult decideStatistically(Statistics current, Statistics baseline, double baselineTotal,
			double currentTotal, double totalDelta, List<MethodComparison> methodComparisons, double threshold) {

		WelchResult welch = current.welchTTest(baseline);
		double d = current.cohenD(baseline);
		double p = welch.pValueTwoSided();

		ComparisonStatus status;
		boolean breached = false;

		if (Math.abs(d) < SMALL_EFFECT_D) {
			// Small effect size: noise dominates. Don't flag.
			status = ComparisonStatus.UNCHANGED;
		}
		else if (d >= SMALL_EFFECT_D && p < ALPHA && totalDelta >= threshold) {
			status = ComparisonStatus.REGRESSED;
			breached = true;
		}
		else if (d <= -SMALL_EFFECT_D && p < ALPHA) {
			status = ComparisonStatus.IMPROVED;
		}
		else {
			status = ComparisonStatus.UNCHANGED;
		}

		return new ComparisonResult(status, baselineTotal, currentTotal, totalDelta, methodComparisons, breached,
				threshold, p, d, true);
	}

	private ComparisonResult decideByThreshold(double baselineTotal, double currentTotal, double totalDelta,
			List<MethodComparison> methodComparisons, double threshold) {
		ComparisonStatus status;
		boolean breached = false;

		if (totalDelta > threshold) {
			status = ComparisonStatus.REGRESSED;
			breached = true;
		}
		else if (totalDelta < -threshold) {
			status = ComparisonStatus.IMPROVED;
		}
		else {
			status = ComparisonStatus.UNCHANGED;
		}

		return new ComparisonResult(status, baselineTotal, currentTotal, totalDelta, methodComparisons, breached,
				threshold);
	}

	private List<MethodComparison> buildMethodComparisons(EnergyReport current, EnergyReport baseline) {
		Map<String, Double> baselineByMethod = baseline.measurements()
			.stream()
			.collect(Collectors.toMap(EnergyMeasurement::methodName, EnergyMeasurement::energyJoules, Double::sum));

		List<MethodComparison> comparisons = new ArrayList<>();

		for (EnergyMeasurement m : current.measurements()) {
			double baselineValue = baselineByMethod.getOrDefault(m.methodName(), 0.0);
			double delta = computeDeltaPercent(baselineValue, m.energyJoules());
			comparisons.add(new MethodComparison(m.methodName(), baselineValue, m.energyJoules(), delta));
		}

		// Methods present in baseline but absent from current run
		for (Map.Entry<String, Double> entry : baselineByMethod.entrySet()) {
			boolean inCurrent = current.measurements().stream().anyMatch(m -> m.methodName().equals(entry.getKey()));
			if (!inCurrent) {
				comparisons.add(new MethodComparison(entry.getKey(), entry.getValue(), 0.0,
						computeDeltaPercent(entry.getValue(), 0.0)));
			}
		}

		return comparisons;
	}

	private double computeDeltaPercent(double baseline, double current) {
		if (baseline == 0) {
			return current == 0 ? 0.0 : 100.0;
		}
		return ((current - baseline) / baseline) * 100.0;
	}

}
