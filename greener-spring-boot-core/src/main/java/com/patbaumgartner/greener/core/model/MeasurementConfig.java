package com.patbaumgartner.greener.core.model;

import java.nio.file.Path;

/**
 * Aggregates the configuration parameters needed by
 * {@link com.patbaumgartner.greener.core.orchestrator.MeasurementOrchestrator#processAndReport}.
 *
 * <p>
 * Both the Maven and Gradle plugins construct a {@code MeasurementConfig} from their
 * respective plugin properties and pass it to the shared orchestrator.
 */
public record MeasurementConfig(Path outputCsv, int measureDurationSeconds, String appIdentifier, Path baselinePath,
		Path reportDir, Path runDir, String toolName, boolean vmMode, double threshold, boolean autoUpdate,
		String commitSha, String branch, Path joularJxWorkingDir, boolean hasJoularJx) {
}
