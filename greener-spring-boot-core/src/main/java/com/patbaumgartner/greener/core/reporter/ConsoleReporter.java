package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.util.Comparator;
import java.util.List;

/**
 * Prints a human-readable energy consumption report to {@link System#out}.
 */
public class ConsoleReporter {

	private static final int REPORT_WIDTH = 72;

	private static final String LINE = "=".repeat(REPORT_WIDTH);

	private static final String THIN_LINE = "-".repeat(REPORT_WIDTH);

	private final int topN;

	public ConsoleReporter() {
		this(10);
	}

	public ConsoleReporter(int topN) {
		this.topN = topN;
	}

	/** Reports energy measurements without workload stats. */
	public void report(EnergyReport current, ComparisonResult comparison) {
		report(current, comparison, null, null);
	}

	/** Reports energy measurements including use-case energy metrics. */
	public void report(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats) {
		report(current, comparison, workloadStats, null);
	}

	/** Reports energy measurements including power source assumptions. */
	public void report(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource) {
		System.out.println();
		System.out.println(LINE);
		System.out.println(" greener-spring-boot - Energy Consumption Report");
		System.out.println(LINE);
		System.out.printf(" Run ID      : %s%n", current.runId());
		System.out.printf(" Timestamp   : %s%n", current.timestamp());
		System.out.printf(" Duration    : %d s%n", current.durationSeconds());
		System.out.printf(" Total Energy: %.4f J%n", current.totalEnergyJoules());

		if (powerSource != null && powerSource != PowerSource.UNKNOWN) {
			System.out.printf(" Power Source : %s%n", powerSource.label());
			System.out.printf("               %s%n", powerSource.description());
		}

		System.out.println(THIN_LINE);

		// Use-case energy section
		if (workloadStats != null) {
			printWorkloadStats(workloadStats, current.totalEnergyJoules());
			System.out.println(THIN_LINE);
		}

		if (!current.measurements().isEmpty()) {
			System.out.printf(" Top %d measurements by energy consumption:%n", topN);
			System.out.printf("   %-55s  %10s%n", "Name", "Joules");
			System.out.println("   " + "-".repeat(68));
			current.topMethods(topN)
				.forEach(m -> System.out.printf("   %-55s  %10.4f%n", truncate(m.methodName(), 55), m.energyJoules()));
		}
		else {
			System.out.println(" No energy data recorded.");
			System.out.println(" Hint: Joular Core requires hardware power counters (RAPL on Linux/Windows,");
			System.out.println("       powermetrics on macOS) or a VM power file in VM mode.");
			System.out.println("       Ensure the binary is accessible and has sufficient permissions.");
		}

		System.out.println(THIN_LINE);

		if (comparison != null) {
			printComparison(comparison);
		}

		System.out.println(LINE);
		System.out.println();
	}

	private void printWorkloadStats(WorkloadStats stats, double totalEnergyJoules) {
		System.out.println(" Use-Case Energy:");
		System.out.printf("   Tool         : %s%n", stats.tool());
		System.out.printf("   Duration     : %d s%n", stats.durationSeconds());

		if (stats.hasRequestCounts()) {
			System.out.printf("   Requests     : %d total, %d failed (%.1f%%)%n", stats.totalRequests(),
					Math.max(0, stats.failedRequests()),
					Double.isNaN(stats.failureRatePercent()) ? 0.0 : stats.failureRatePercent());
			System.out.printf("   Throughput   : %.1f req/s%n", stats.requestsPerSecond());
		}
		else {
			System.out.println("   Requests     : N/A (external tool - counts not captured)");
		}

		double mjPerReq = stats.energyPerRequestMillijoules(totalEnergyJoules);
		if (!Double.isNaN(mjPerReq)) {
			System.out.printf("   Energy/Req   : %.3f mJ%n", mjPerReq);
		}
		else if (stats.durationSeconds() > 0) {
			double wattsAvg = totalEnergyJoules / stats.durationSeconds();
			System.out.printf("   Avg Power    : %.2f W  (energy/req unavailable - no request count)%n", wattsAvg);
		}
	}

	private void printComparison(ComparisonResult comparison) {
		ComparisonStatus status = comparison.overallStatus();

		if (status == ComparisonStatus.NO_BASELINE) {
			System.out.println(" Baseline: No baseline found - this run will be saved as the new baseline.");
			System.out.println(THIN_LINE);
			return;
		}

		String arrow = switch (status) {
			case IMPROVED -> "▼ IMPROVED  ✓";
			case REGRESSED -> "▲ REGRESSED ✗";
			default -> "~ UNCHANGED";
		};

		System.out.printf(" Baseline comparison:%n");
		System.out.printf("   Baseline Total : %.4f J%n", comparison.baselineTotalJoules());
		System.out.printf("   Current Total  : %.4f J%n", comparison.currentTotalJoules());
		System.out.printf("   Delta          : %+.2f%%%n", comparison.totalDeltaPercent());
		System.out.printf("   Threshold      : +/-%.1f%%%n", comparison.threshold());
		System.out.printf("   Status         : %s%n", arrow);

		if (comparison.isFailed()) {
			System.out.printf("%n   !!  Energy consumption increased by %.2f%% (threshold: +/-%.1f%%)%n",
					comparison.totalDeltaPercent(), comparison.threshold());
		}

		List<MethodComparison> regressions = comparison.methodComparisons()
			.stream()
			.filter(mc -> mc.isRegressed(comparison.threshold()))
			.sorted(Comparator.comparingDouble(MethodComparison::deltaPercent).reversed())
			.limit(topN)
			.toList();

		if (!regressions.isEmpty()) {
			System.out.println();
			System.out.printf("   Top regressed entries (delta > %.1f%%):%n", comparison.threshold());
			System.out.printf("   %-48s  %8s  %8s  %8s%n", "Name", "Baseline", "Current", "Delta%");
			System.out.println("   " + "-".repeat(80));
			regressions
				.forEach(mc -> System.out.printf("   %-48s  %8.4f  %8.4f  %+8.2f%n", truncate(mc.methodName(), 48),
						mc.baselineEnergyJoules(), mc.currentEnergyJoules(), mc.deltaPercent()));
		}

		System.out.println(THIN_LINE);
	}

	private String truncate(String s, int maxLen) {
		if (s.length() <= maxLen)
			return s;
		return ".." + s.substring(s.length() - (maxLen - 1));
	}

}
