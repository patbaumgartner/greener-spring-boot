package com.patbaumgartner.greener.core.model;

import java.util.Collections;
import java.util.List;

/**
 * Holds the result of comparing current energy measurements against a stored baseline.
 */
public record ComparisonResult(
        ComparisonStatus overallStatus,
        double baselineTotalJoules,
        double currentTotalJoules,
        double totalDeltaPercent,
        List<MethodComparison> methodComparisons,
        boolean thresholdBreached,
        double threshold) {

    public ComparisonResult {
        methodComparisons = methodComparisons == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(methodComparisons);
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
    public record MethodComparison(
            String methodName,
            double baselineEnergyJoules,
            double currentEnergyJoules,
            double deltaPercent) {

        public boolean isRegressed(double threshold) {
            return deltaPercent > threshold;
        }

        public boolean isImproved() {
            return deltaPercent < 0;
        }
    }
}
