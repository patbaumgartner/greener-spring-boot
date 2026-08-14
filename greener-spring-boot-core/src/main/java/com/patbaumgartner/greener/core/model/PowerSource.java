package com.patbaumgartner.greener.core.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

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

	private static final String RAPL_POWERCAP_DIR = "/sys/class/powercap";

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
	 * Determines the power source actually in use.
	 *
	 * <p>
	 * When {@code vmMode} is enabled the reading comes from a host-written power file, so
	 * the source is {@link #ESTIMATED} — the Java layer cannot distinguish a Scaphandre
	 * host file from the CPU&times;TDP estimation script.
	 *
	 * <p>
	 * Otherwise the RAPL counters are <em>probed</em> rather than assumed. Claiming
	 * {@link #RAPL} ("high accuracy") on a host where the counters are absent or
	 * unreadable — the normal case on CI runners and inside containers — would attach a
	 * false accuracy claim to every report, so an unreadable counter degrades to
	 * {@link #ESTIMATED}.
	 *
	 * <p>
	 * Callers that <em>know</em> the distinction (e.g. workflow shells) can override this
	 * via the {@code greener.powerSource} system property or the {@code POWER_SOURCE}
	 * environment variable.
	 */
	public static PowerSource detect(boolean vmMode) {
		if (vmMode) {
			return ESTIMATED;
		}
		return raplCountersReadable() ? RAPL : ESTIMATED;
	}

	/**
	 * {@code true} when at least one Intel/AMD RAPL energy counter is readable by the
	 * current user. Only meaningful on Linux; other platforms report {@code false}
	 * because Joular Core reads power through platform-specific back-ends there.
	 */
	static boolean raplCountersReadable() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("linux")) {
			return false;
		}
		Path powercap = Path.of(RAPL_POWERCAP_DIR);
		if (!Files.isDirectory(powercap)) {
			return false;
		}
		try (Stream<Path> zones = Files.list(powercap)) {
			return zones.filter(zone -> zone.getFileName().toString().startsWith("intel-rapl"))
				.anyMatch(zone -> Files.isReadable(zone.resolve("energy_uj")));
		}
		catch (IOException ex) {
			return false;
		}
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
