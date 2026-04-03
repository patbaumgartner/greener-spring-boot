package com.patbaumgartner.greener.core.model;

/**
 * Statistics captured during the training workload (the "use case") execution.
 *
 * <p>This data is used to compute energy-per-request metrics that make the
 * energy report meaningful: instead of reporting raw Joules, the report can
 * show how much energy a single business transaction costs.
 *
 * <h2>Tool names</h2>
 * <ul>
 *   <li>{@code built-in} — the built-in Java HTTP loader</li>
 *   <li>{@code wrk} — <a href="https://github.com/wg/wrk">wrk</a></li>
 *   <li>{@code wrk2} — <a href="https://github.com/giltene/wrk2">wrk2</a></li>
 *   <li>{@code oha} — <a href="https://github.com/hatoo/oha">oha</a></li>
 *   <li>{@code gatling} — <a href="https://gatling.io">Gatling</a></li>
 *   <li>{@code custom} — any other external command or script</li>
 * </ul>
 *
 * <p>{@code totalRequests} and {@code failedRequests} are {@code -1} when unknown
 * (i.e. the external tool did not report these figures).
 */
public record WorkloadStats(
        String tool,
        long totalRequests,
        long failedRequests,
        long durationSeconds) {

    public static final long UNKNOWN = -1L;

    public WorkloadStats {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("tool must not be null or blank");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must be >= 0");
        }
    }

    /** Factory for the built-in HTTP loader with known request counts. */
    public static WorkloadStats builtIn(long totalRequests, long failedRequests,
                                        long durationSeconds) {
        return new WorkloadStats("built-in", totalRequests, failedRequests, durationSeconds);
    }

    /** Factory for an external tool with unknown request counts. */
    public static WorkloadStats external(String tool, long durationSeconds) {
        return new WorkloadStats(tool, UNKNOWN, UNKNOWN, durationSeconds);
    }

    /** Returns {@code true} if request counts are available. */
    public boolean hasRequestCounts() {
        return totalRequests >= 0;
    }

    /**
     * Requests per second (throughput) during the measurement window.
     * Returns {@link Double#NaN} when counts or duration are unavailable.
     */
    public double requestsPerSecond() {
        if (!hasRequestCounts() || durationSeconds <= 0) return Double.NaN;
        return (double) totalRequests / durationSeconds;
    }

    /**
     * Energy consumed per successful request in millijoules (mJ).
     *
     * <p>A lower value indicates a more energy-efficient code path.
     * Returns {@link Double#NaN} when request counts are unavailable or zero.
     *
     * @param totalEnergyJoules total energy consumed during the measurement window
     */
    public double energyPerRequestMillijoules(double totalEnergyJoules) {
        long successfulRequests = hasRequestCounts()
                ? totalRequests - Math.max(0, failedRequests)
                : UNKNOWN;
        if (successfulRequests <= 0) return Double.NaN;
        return (totalEnergyJoules / successfulRequests) * 1_000.0;
    }

    /**
     * Failure rate as a percentage of total requests.
     * Returns {@link Double#NaN} when counts are unavailable or total is zero.
     */
    public double failureRatePercent() {
        if (!hasRequestCounts() || totalRequests <= 0) return Double.NaN;
        return ((double) Math.max(0, failedRequests) / totalRequests) * 100.0;
    }
}
