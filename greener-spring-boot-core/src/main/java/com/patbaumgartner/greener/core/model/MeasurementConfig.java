package com.patbaumgartner.greener.core.model;

import java.nio.file.Path;

/**
 * Aggregates the configuration parameters needed by
 * {@link com.patbaumgartner.greener.core.orchestrator.MeasurementOrchestrator#processAndReport}.
 *
 * <p>
 * Both the Maven and Gradle plugins construct a {@code MeasurementConfig} from their
 * respective plugin properties and pass it to the shared orchestrator.
 *
 * <p>
 * <b>v0.2 fields:</b> {@code iterations}, {@code regressionMetric}, and
 * {@code idleProbeSeconds} are optional and have backwards-compatible defaults (1,
 * {@link RegressionMetric#TOTAL_ENERGY}, 0). The 14-arg constructor is preserved.
 */
public record MeasurementConfig(Path outputCsv, int measureDurationSeconds, String appIdentifier, Path baselinePath,
		Path reportDir, Path runDir, String toolName, boolean vmMode, double threshold, boolean autoUpdate,
		String commitSha, String branch, Path joularJxWorkingDir, boolean hasJoularJx, int iterations,
		RegressionMetric regressionMetric, int idleProbeSeconds) {

	public MeasurementConfig {
		final int minIterations = 1;
		if (iterations < minIterations) {
			iterations = minIterations;
		}
		if (regressionMetric == null) {
			regressionMetric = RegressionMetric.TOTAL_ENERGY;
		}
		if (idleProbeSeconds < 0) {
			idleProbeSeconds = 0;
		}
	}

	/** Backwards-compatible 14-arg constructor (pre-v0.2 fields). */
	public MeasurementConfig(Path outputCsv, int measureDurationSeconds, String appIdentifier, Path baselinePath,
			Path reportDir, Path runDir, String toolName, boolean vmMode, double threshold, boolean autoUpdate,
			String commitSha, String branch, Path joularJxWorkingDir, boolean hasJoularJx) {
		this(outputCsv, measureDurationSeconds, appIdentifier, baselinePath, reportDir, runDir, toolName, vmMode,
				threshold, autoUpdate, commitSha, branch, joularJxWorkingDir, hasJoularJx, 1,
				RegressionMetric.TOTAL_ENERGY, 0);
	}
}
