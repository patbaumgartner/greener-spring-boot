package com.patbaumgartner.greener.core.model;

import java.time.Instant;

/**
 * A persisted snapshot of an {@link EnergyReport} used as the reference baseline
 * for future comparison runs.
 *
 * <p>Stored as JSON on disk (or as a CI artifact) so that subsequent runs can
 * load it and compare energy consumption.
 */
public record EnergyBaseline(
        String version,
        Instant createdAt,
        String commitSha,
        String branch,
        EnergyReport report) {

    public static final String CURRENT_VERSION = "1.0";

    public static EnergyBaseline of(EnergyReport report, String commitSha, String branch) {
        return new EnergyBaseline(CURRENT_VERSION, Instant.now(), commitSha, branch, report);
    }

    public static EnergyBaseline of(EnergyReport report) {
        return of(report, null, null);
    }
}
