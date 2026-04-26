package com.patbaumgartner.greener.core.model;

import java.time.Instant;

/**
 * A persisted snapshot of an {@link EnergyReport} used as the reference baseline for
 * future comparison runs.
 *
 * <p>
 * Stored as JSON on disk (or as a CI artifact) so that subsequent runs can load it and
 * compare energy consumption. The {@link EnergyReport} embedded inside the baseline may
 * carry distributional statistics ({@link EnergyReport#totalEnergyStats()}) when the
 * baseline was produced from a multi-iteration run; the comparator uses those statistics
 * to apply Welch's t-test rather than a raw percentage threshold.
 *
 * <p>
 * <b>Schema versions:</b> v1.0 baselines (no statistics, single-shot measurement) remain
 * fully readable &mdash; missing fields default to {@link Statistics#empty()} and the
 * comparator falls back to the legacy percentage rule. v1.1 baselines additionally
 * persist {@link Statistics} so future runs can perform statistical comparisons.
 */
public record EnergyBaseline(String version, Instant createdAt, String commitSha, String branch, EnergyReport report,
		WorkloadStats workloadStats) {

	/** Current on-disk schema version. v1.0 and v1.1 baselines remain readable. */
	public static final String CURRENT_VERSION = "1.2";

	/** Backwards-compatible 5-arg constructor (pre-workload-stats baselines). */
	public EnergyBaseline(String version, Instant createdAt, String commitSha, String branch, EnergyReport report) {
		this(version, createdAt, commitSha, branch, report, null);
	}

	public static EnergyBaseline of(EnergyReport report, String commitSha, String branch) {
		return new EnergyBaseline(CURRENT_VERSION, Instant.now(), commitSha, branch, report, null);
	}

	public static EnergyBaseline of(EnergyReport report, String commitSha, String branch, WorkloadStats workloadStats) {
		return new EnergyBaseline(CURRENT_VERSION, Instant.now(), commitSha, branch, report, workloadStats);
	}

	public static EnergyBaseline of(EnergyReport report) {
		return of(report, null, null);
	}
}
