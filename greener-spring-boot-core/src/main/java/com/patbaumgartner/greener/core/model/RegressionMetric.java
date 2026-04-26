package com.patbaumgartner.greener.core.model;

/**
 * Selects the metric used by
 * {@link com.patbaumgartner.greener.core.comparator.EnergyComparator} to decide whether a
 * run regressed against the baseline.
 *
 * <h2>Why two metrics?</h2>
 *
 * Comparing {@link #TOTAL_ENERGY} alone is misleading whenever throughput changes between
 * runs: a faster build that processes more requests trivially burns more Joules in the
 * same wall-clock window. {@link #ENERGY_PER_REQUEST} normalises by useful work
 * (successful requests) and is the recommended default when the workload tool reports
 * request counts (oha, wrk, k6, Gatling, &hellip;).
 *
 * <p>
 * If {@link #ENERGY_PER_REQUEST} is selected but request counts are unavailable on either
 * side of the comparison, the comparator transparently falls back to
 * {@link #TOTAL_ENERGY}.
 */
public enum RegressionMetric {

	/** Compare absolute total energy in Joules. */
	TOTAL_ENERGY,

	/** Compare energy per successful request in millijoules (recommended). */
	ENERGY_PER_REQUEST;

	/** Default metric when none is configured. */
	public static final RegressionMetric DEFAULT = ENERGY_PER_REQUEST;

}
