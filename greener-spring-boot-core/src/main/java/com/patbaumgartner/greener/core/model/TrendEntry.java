package com.patbaumgartner.greener.core.model;

import java.time.Instant;

/**
 * A single point in the energy trend history. Captures the headline numbers needed to
 * render a sparkline / line chart of energy over time without re-reading per-iteration
 * details.
 *
 * @param timestamp wall-clock time the run completed
 * @param runId the run identifier (typically commit SHA or timestamp slug)
 * @param totalEnergyJoules total process energy in Joules (always present)
 * @param energyPerRequestMillijoules normalised mJ/req when workload reported request
 * counts; {@code null} otherwise
 * @param commitSha optional Git commit SHA at the time of the run
 * @param branch optional Git branch name at the time of the run
 */
public record TrendEntry(Instant timestamp, String runId, double totalEnergyJoules, Double energyPerRequestMillijoules,
		String commitSha, String branch) {

	public TrendEntry {
		if (timestamp == null) {
			throw new IllegalArgumentException("timestamp must not be null");
		}
		if (runId == null || runId.isBlank()) {
			throw new IllegalArgumentException("runId must not be null or blank");
		}
		if (totalEnergyJoules < 0 || Double.isNaN(totalEnergyJoules) || Double.isInfinite(totalEnergyJoules)) {
			throw new IllegalArgumentException("totalEnergyJoules must be a finite, non-negative number");
		}
	}

}
