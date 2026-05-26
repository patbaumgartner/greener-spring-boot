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
 * {@code methodLevelStartTimestampMs} is the epoch-millisecond timestamp recorded right
 * after the warmup phase finished. The Joular Code Java result reader skips CSV rows
 * written before this timestamp so that warmup energy (Spring Boot initialisation, JIT
 * compilation) is excluded from per-method attribution. Pass {@code 0} when there is no
 * warmup or when timestamp filtering is not required.
 */
public record MeasurementConfig(Path outputCsv, int measureDurationSeconds, String appIdentifier, Path baselinePath,
		Path reportDir, Path runDir, String toolName, boolean vmMode, double threshold, boolean autoUpdate,
		String commitSha, String branch, Path joularCodeJavaWorkingDir, boolean hasJoularCodeJava, int iterations,
		RegressionMetric regressionMetric, int idleProbeSeconds, long methodLevelStartTimestampMs) {

	public MeasurementConfig {
		final int minIterations = 1;
		if (iterations < minIterations) {
			iterations = minIterations;
		}
		if (regressionMetric == null) {
			regressionMetric = RegressionMetric.ENERGY_PER_REQUEST;
		}
		if (idleProbeSeconds < 0) {
			idleProbeSeconds = 0;
		}
		if (methodLevelStartTimestampMs < 0) {
			methodLevelStartTimestampMs = 0;
		}
	}

}
