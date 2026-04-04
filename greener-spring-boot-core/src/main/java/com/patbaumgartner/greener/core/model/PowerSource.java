package com.patbaumgartner.greener.core.model;

import java.util.Locale;

/**
 * Describes how the energy data was obtained during a measurement run.
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName") // record-style accessors
public enum PowerSource {

	/**
	 * Hardware energy counters (Intel RAPL / AMD RAPL). Most accurate — reads real power
	 * from the CPU's built-in energy registers.
	 */
	RAPL("RAPL (hardware energy counters)", "Energy measured via Intel/AMD RAPL hardware counters - high accuracy."),

	/**
	 * VM power file written by the hypervisor host (e.g. Scaphandre on KVM). Joular Core
	 * reads the file in {@code --vm} mode.
	 */
	VM_FILE("Scaphandre VM power file",
			"Energy derived from a host-side power file (e.g. Scaphandre on KVM) - good accuracy."),

	/**
	 * Software estimation: CPU utilisation × TDP. Used on CI runners and VMs where
	 * neither RAPL nor a host power file is available.
	 */
	ESTIMATED("CPU utilisation x TDP (estimated)",
			"Energy estimated from CPU load and TDP - suitable for relative comparisons between commits."),

	/**
	 * Fallback when the actual power source is not known.
	 */
	UNKNOWN("Unknown", "Power source could not be determined.");

	private final String label;

	private final String description;

	PowerSource(String label, String description) {
		this.label = label;
		this.description = description;
	}

	/** Human-readable short label (e.g. for table cells). */
	public String label() {
		return label;
	}

	/** Longer explanation suitable for report footnotes. */
	public String description() {
		return description;
	}

	/**
	 * Determines the power source from the VM mode flag. When {@code vmMode} is
	 * {@code false}, RAPL is assumed (hardware counters are available). When
	 * {@code vmMode} is {@code true}, the source is reported as {@link #ESTIMATED}
	 * because the Java layer cannot distinguish between a Scaphandre host file and the
	 * CPU×TDP estimation script.
	 *
	 * <p>
	 * Callers that <em>know</em> the distinction (e.g. workflow shells) can override this
	 * via a system property ({@code greener.powerSource}).
	 */
	public static PowerSource detect(boolean vmMode) {
		return vmMode ? ESTIMATED : RAPL;
	}

	/**
	 * Resolves the power source from a string identifier (case-insensitive). Accepted
	 * values: {@code rapl}, {@code vm-file}, {@code ci-estimated}, {@code estimated},
	 * {@code unknown}.
	 */
	public static PowerSource fromString(String value) {
		if (value == null || value.isBlank()) {
			return UNKNOWN;
		}
		return switch (value.strip().toLowerCase(Locale.ENGLISH)) {
			case "rapl" -> RAPL;
			case "vm-file", "scaphandre" -> VM_FILE;
			case "ci-estimated", "estimated" -> ESTIMATED;
			default -> UNKNOWN;
		};
	}

}
