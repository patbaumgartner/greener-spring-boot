package com.patbaumgartner.greener.core.model;

import java.util.Collections;
import java.util.List;

/**
 * Result of running a multi-iteration measurement.
 *
 * <p>
 * {@code representativeReport} is the report selected to summarise the run (typically the
 * iteration whose total energy is closest to the median); it carries the multi-iteration
 * {@link Statistics} attached to {@link EnergyReport#totalEnergyStats()} so that the
 * comparator can apply Welch's t-test. {@code perIterationReports} preserves the raw
 * per-iteration data for transparency. {@code mergedWorkload} aggregates request counts
 * across all iterations.
 *
 * <p>
 * Single-iteration runs return an instance with one entry in {@code perIterationReports}
 * and a {@link Statistics} of size 1 (or {@link Statistics#empty()} for back-compat).
 */
public record IteratedMeasurement(EnergyReport representativeReport, List<EnergyReport> perIterationReports,
		WorkloadStats mergedWorkload) {

	public IteratedMeasurement {
		perIterationReports = perIterationReports == null ? Collections.emptyList()
				: Collections.unmodifiableList(perIterationReports);
	}

	/** Number of iterations that contributed to this measurement. */
	public int iterations() {
		return perIterationReports.size();
	}

}
