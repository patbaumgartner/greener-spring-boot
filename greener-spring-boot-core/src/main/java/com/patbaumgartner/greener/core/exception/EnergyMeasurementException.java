package com.patbaumgartner.greener.core.exception;

import java.io.IOException;

/**
 * Signals a recoverable failure inside the greener-spring-boot measurement pipeline.
 * Carries a {@link Hint} that classifies the error and tells the user exactly which step
 * to take to recover. Extends {@link IOException} so that existing throws-clauses remain
 * compatible.
 */
public class EnergyMeasurementException extends IOException {

	private static final long serialVersionUID = 1L;

	/**
	 * Classification of common measurement failures with an actionable recovery hint.
	 */
	public enum Hint {

		/** Joular Core CSV is empty or unreadable - typically RAPL not accessible. */
		EMPTY_OR_MISSING_CSV(
				"Joular Core produced no power samples. " + "Run `mvn greener:doctor` (or `./gradlew energyDoctor`) "
						+ "to verify RAPL access; on Linux load the msr module: `sudo modprobe msr`."),

		/** External workload tool is not on PATH. */
		WORKLOAD_TOOL_MISSING(
				"Workload tool not found on PATH. " + "Install the tool (e.g. `cargo install oha`, `brew install wrk`) "
						+ "or set `externalTrainingCommand` to a fully-qualified path."),

		/** External training script timed out. */
		WORKLOAD_TIMEOUT("External workload exceeded its timeout. "
				+ "Increase `externalTrainingTimeoutSeconds` or shorten the workload."),

		/** External training script returned non-zero exit code. */
		WORKLOAD_FAILED("External workload exited with a non-zero status. "
				+ "Check the tool's stdout/stderr above for the underlying cause "
				+ "(e.g. connection refused = Spring Boot not yet up)."),

		/** Joular Core binary not present. */
		JOULAR_CORE_BINARY_MISSING("Joular Core binary not found. " + "Either run with network access (auto-download), "
				+ "or set `joularCoreBinaryPath` to a manually-downloaded binary."),

		/** Spring Boot application failed to become healthy. */
		APPLICATION_NOT_READY("Spring Boot application never became ready. " + "Check application logs above; "
				+ "ensure `baseUrl` matches the actual server.port and management.server.port."),

		/** Generic I/O error. */
		GENERIC_IO("Unexpected I/O failure during measurement.");

		private final String guidanceText;

		Hint(String guidance) {
			this.guidanceText = guidance;
		}

		public String guidance() {
			return guidanceText;
		}

	}

	private final Hint hintCode;

	public EnergyMeasurementException(Hint hint, String message) {
		super(buildMessage(hint, message));
		this.hintCode = hint;
	}

	public EnergyMeasurementException(Hint hint, String message, Throwable cause) {
		super(buildMessage(hint, message), cause);
		this.hintCode = hint;
	}

	public Hint hint() {
		return hintCode;
	}

	private static String buildMessage(Hint hint, String message) {
		return message + System.lineSeparator() + "  -> Hint [" + hint.name() + "]: " + hint.guidance();
	}

}
