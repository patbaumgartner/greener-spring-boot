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
 * {@code methodLevelStartTimestampMs} is the epoch-millisecond timestamp recorded
 * immediately after the warmup phase completed. The Joular Code Java result reader uses
 * this to skip rows accumulated during warmup so that Spring Boot initialisation and JIT
 * warm-up energy are excluded from per-method attribution. {@code 0} means no filtering
 * (no warmup phase was run, or single-iteration path).
 *
 * <p>
 * Single-iteration runs return an instance with one entry in {@code perIterationReports}
 * and a {@link Statistics} of size 1 (or {@link Statistics#empty()} for back-compat).
 */
public record IteratedMeasurement(EnergyReport representativeReport, List<EnergyReport> perIterationReports,
		WorkloadStats mergedWorkload, long methodLevelStartTimestampMs) {

	public IteratedMeasurement {
		perIterationReports = perIterationReports == null ? Collections.emptyList()
				: Collections.unmodifiableList(perIterationReports);
		if (methodLevelStartTimestampMs < 0) {
			methodLevelStartTimestampMs = 0;
		}
	}

	/**
	 * Convenience constructor when no warmup timestamp is available (no filtering).
	 */
	public IteratedMeasurement(EnergyReport representativeReport, List<EnergyReport> perIterationReports,
			WorkloadStats mergedWorkload) {
		this(representativeReport, perIterationReports, mergedWorkload, 0L);
	}

	/** Number of iterations that contributed to this measurement. */
	public int iterations() {
		return perIterationReports.size();
	}

}
