package com.patbaumgartner.greener.core.model;

/**
 * Holds the data for a single tool run within a multi-tool aggregated report.
 *
 * <p>
 * Each entry captures the workload tool used, the energy report from that run, optional
 * workload statistics, and optional baseline comparison results.
 *
 * @param tool the workload tool name (e.g. {@code oha}, {@code wrk}, {@code k6})
 * @param report the energy report for this run
 * @param workloadStats optional workload statistics (may be {@code null})
 * @param comparison optional baseline comparison result (may be {@code null})
 */
public record AggregatedRunEntry(String tool, EnergyReport report, WorkloadStats workloadStats,
		ComparisonResult comparison) {

	public AggregatedRunEntry {
		if (tool == null || tool.isBlank()) {
			throw new IllegalArgumentException("tool must not be null or blank");
		}
		if (report == null) {
			throw new IllegalArgumentException("report must not be null");
		}
	}
}
