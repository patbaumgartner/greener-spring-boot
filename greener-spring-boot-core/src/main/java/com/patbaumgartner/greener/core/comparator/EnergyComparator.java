package com.patbaumgartner.greener.core.comparator;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Compares the current {@link EnergyReport} against a stored {@link EnergyBaseline}.
 *
 * <h2>Comparison logic</h2>
 * <ol>
 * <li>If no baseline is available, status is {@link ComparisonStatus#NO_BASELINE}.</li>
 * <li>Total energy delta = {@code (current − baseline) / baseline × 100 %}.</li>
 * <li>{@code delta > +threshold} → {@link ComparisonStatus#REGRESSED} (build may
 * fail).</li>
 * <li>{@code delta < −threshold} → {@link ComparisonStatus#IMPROVED}.</li>
 * <li>Otherwise → {@link ComparisonStatus#UNCHANGED}.</li>
 * </ol>
 */
public class EnergyComparator {

	/**
	 * Compares the current report against an optional baseline.
	 * @param current the newly measured {@link EnergyReport}
	 * @param baseline an {@link Optional} wrapping the stored baseline (may be empty)
	 * @param threshold percentage change above/below which status changes from UNCHANGED
	 * @return a {@link ComparisonResult} describing how this run compares to baseline
	 */
	public ComparisonResult compare(EnergyReport current, Optional<EnergyBaseline> baseline, double threshold) {
		if (baseline.isEmpty()) {
			return new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, current.totalEnergyJoules(), 0, List.of(),
					false, threshold);
		}

		EnergyReport baselineReport = baseline.get().report();
		double baselineTotal = baselineReport.totalEnergyJoules();
		double currentTotal = current.totalEnergyJoules();

		double totalDelta = computeDeltaPercent(baselineTotal, currentTotal);
		List<MethodComparison> methodComparisons = buildMethodComparisons(current, baselineReport);

		ComparisonStatus status;
		boolean thresholdBreached = false;

		if (baselineTotal == 0) {
			status = ComparisonStatus.NO_BASELINE;
		}
		else if (totalDelta > threshold) {
			status = ComparisonStatus.REGRESSED;
			thresholdBreached = true;
		}
		else if (totalDelta < -threshold) {
			status = ComparisonStatus.IMPROVED;
		}
		else {
			status = ComparisonStatus.UNCHANGED;
		}

		return new ComparisonResult(status, baselineTotal, currentTotal, totalDelta, methodComparisons,
				thresholdBreached, threshold);
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
