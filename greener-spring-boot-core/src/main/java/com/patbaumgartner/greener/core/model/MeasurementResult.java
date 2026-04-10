package com.patbaumgartner.greener.core.model;

import java.nio.file.Path;

/**
 * Aggregates the outputs of a complete measurement run — energy report, baseline
 * comparison, workload statistics, optional method-level data, and the path to the
 * generated HTML report.
 *
 * <p>
 * Returned by
 * {@link com.patbaumgartner.greener.core.orchestrator.MeasurementOrchestrator#processAndReport}
 * so that plugin code can inspect the results in a single, type-safe object.
 */
public record MeasurementResult(EnergyReport report, ComparisonResult comparison, WorkloadStats workloadStats,
		MethodLevelReports methodLevelReports, Path htmlReport) {
}
