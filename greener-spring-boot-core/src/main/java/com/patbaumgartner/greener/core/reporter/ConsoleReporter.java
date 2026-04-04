package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.io.PrintStream;
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

	private final PrintStream out;

	public ConsoleReporter() {
		this(10);
	}

	public ConsoleReporter(int topN) {
		this(topN, System.out);
	}

	public ConsoleReporter(int topN, PrintStream out) {
		this.topN = topN;
		this.out = out;
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
		out.println();
		out.println(LINE);
		out.println(" greener-spring-boot - Energy Consumption Report");
		out.println(LINE);
		out.printf(" Run ID      : %s%n", current.runId());
		out.printf(" Timestamp   : %s%n", current.timestamp());
		out.printf(" Duration    : %d s%n", current.durationSeconds());
		out.printf(" Total Energy: %.2f J%n", current.totalEnergyJoules());

		if (powerSource != null && powerSource != PowerSource.UNKNOWN) {
			out.printf(" Power Source : %s%n", powerSource.label());
			out.printf("               %s%n", powerSource.description());
		}

		out.println(THIN_LINE);

		// Use-case energy section
		if (workloadStats != null) {
			printWorkloadStats(workloadStats, current.totalEnergyJoules());
			out.println(THIN_LINE);
		}

		if (!current.measurements().isEmpty()) {
			out.printf(" Top %d measurements by energy consumption:%n", topN);
			out.printf("   %-55s  %10s%n", "Name", "Joules");
			out.println("   " + "-".repeat(68));
			current.topMeasurements(topN)
				.forEach(m -> out.printf("   %-55s  %10.2f%n", truncate(m.methodName(), 55), m.energyJoules()));
		}
		else {
			out.println(" No energy data recorded.");
			out.println(" Hint: Joular Core requires hardware power counters (RAPL on Linux/Windows,");
			out.println("       powermetrics on macOS) or a VM power file in VM mode.");
			out.println("       Ensure the binary is accessible and has sufficient permissions.");
		}

		out.println(THIN_LINE);

		if (comparison != null) {
			printComparison(comparison);
		}

		out.println(LINE);
		out.println();
	}

	private void printWorkloadStats(WorkloadStats stats, double totalEnergyJoules) {
		out.println(" Use-Case Energy:");
		out.printf("   Tool         : %s%n", stats.tool());
		out.printf("   Duration     : %d s%n", stats.durationSeconds());

		if (stats.hasRequestCounts()) {
			out.printf("   Requests     : %d total, %d failed (%.1f%%)%n", stats.totalRequests(),
					Math.max(0, stats.failedRequests()),
					Double.isNaN(stats.failureRatePercent()) ? 0.0 : stats.failureRatePercent());
			out.printf("   Throughput   : %.1f %s%n", stats.requestsPerSecond(), stats.throughputUnit());
		}
		else {
			out.println("   Requests     : N/A (external tool - counts not captured)");
		}

		double mjPerReq = stats.energyPerRequestMillijoules(totalEnergyJoules);
		if (!Double.isNaN(mjPerReq)) {
			out.printf("   Energy/Req   : %.3f mJ%n", mjPerReq);
		}
		else if (stats.durationSeconds() > 0) {
			double wattsAvg = totalEnergyJoules / stats.durationSeconds();
			out.printf("   Avg Power    : %.2f W  (energy/req unavailable - no request count)%n", wattsAvg);
		}
	}

	private void printComparison(ComparisonResult comparison) {
		ComparisonStatus status = comparison.overallStatus();

		if (status == ComparisonStatus.NO_BASELINE) {
			out.println(" Baseline: No baseline found - this run will be saved as the new baseline.");
			out.println(THIN_LINE);
			return;
		}

		String arrow = switch (status) {
			case IMPROVED -> "▼ IMPROVED  ✓";
			case REGRESSED -> "▲ REGRESSED ✗";
			default -> "~ UNCHANGED";
		};

		out.printf(" Baseline comparison:%n");
		out.printf("   Baseline Total : %.2f J%n", comparison.baselineTotalJoules());
		out.printf("   Current Total  : %.2f J%n", comparison.currentTotalJoules());
		out.printf("   Delta          : %+.2f%%%n", comparison.totalDeltaPercent());
		out.printf("   Threshold      : +/-%.1f%%%n", comparison.threshold());
		out.printf("   Status         : %s%n", arrow);

		if (comparison.isFailed()) {
			out.printf("%n   !!  Energy consumption increased by %.2f%% (threshold: +/-%.1f%%)%n",
					comparison.totalDeltaPercent(), comparison.threshold());
		}

		List<MethodComparison> regressions = comparison.methodComparisons()
			.stream()
			.filter(mc -> mc.isRegressed(comparison.threshold()))
			.sorted(Comparator.comparingDouble(MethodComparison::deltaPercent).reversed())
			.limit(topN)
			.toList();

		if (!regressions.isEmpty()) {
			out.println();
			out.printf("   Top regressed entries (delta > %.1f%%):%n", comparison.threshold());
			out.printf("   %-48s  %8s  %8s  %8s%n", "Name", "Baseline", "Current", "Delta%");
			out.println("   " + "-".repeat(80));
			regressions.forEach(mc -> out.printf("   %-48s  %8.2f  %8.2f  %+8.2f%n", truncate(mc.methodName(), 48),
					mc.baselineEnergyJoules(), mc.currentEnergyJoules(), mc.deltaPercent()));
		}

		out.println(THIN_LINE);
	}

	private String truncate(String s, int maxLen) {
		if (s.length() <= maxLen)
			return s;
		return ".." + s.substring(s.length() - (maxLen - 1));
	}

}
