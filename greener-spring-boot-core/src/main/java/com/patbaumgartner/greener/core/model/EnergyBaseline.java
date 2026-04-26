package com.patbaumgartner.greener.core.model;

import java.time.Instant;

/**
 * A persisted snapshot of an {@link EnergyReport} used as the reference baseline for
 * future comparison runs.
 *
 * <p>
 * Stored as JSON on disk (or as a CI artifact) so that subsequent runs can load it and
 * compare energy consumption. The {@link EnergyReport} embedded inside the baseline
 * carries distributional statistics ({@link EnergyReport#totalEnergyStats()}) when the
 * baseline was produced from a multi-iteration run; the comparator uses those statistics
 * to apply Welch's t-test rather than a raw percentage threshold.
 */
public record EnergyBaseline(String version, Instant createdAt, String commitSha, String branch, EnergyReport report,
		WorkloadStats workloadStats) {

	/** Current on-disk schema version. */
	public static final String CURRENT_VERSION = "1.2";

	public static EnergyBaseline of(EnergyReport report, String commitSha, String branch, WorkloadStats workloadStats) {
		return new EnergyBaseline(CURRENT_VERSION, Instant.now(), commitSha, branch, report, workloadStats);
	}

}
