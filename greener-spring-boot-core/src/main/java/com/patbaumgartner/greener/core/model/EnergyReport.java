package com.patbaumgartner.greener.core.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The aggregated energy report produced by a single measurement run.
 *
 * <p>
 * In <em>process mode</em> (Joular Core only), {@code measurements} contains one entry
 * per monitored process / app name. In <em>method mode</em> (JoularJX + Joular Core),
 * {@code measurements} contains per-method entries.
 */
public record EnergyReport(String runId, Instant timestamp, long durationSeconds, List<EnergyMeasurement> measurements,
		double totalEnergyJoules) {

	@SuppressWarnings("PMD.UnusedAssignment") // compact constructor field normalization
	public EnergyReport {
		measurements = measurements == null ? Collections.emptyList() : Collections.unmodifiableList(measurements);
		if (totalEnergyJoules < 0) {
			throw new IllegalArgumentException("totalEnergyJoules must be >= 0");
		}
	}

	/**
	 * Convenience factory that derives {@code totalEnergyJoules} from the measurements.
	 */
	public static EnergyReport of(String runId, Instant timestamp, long durationSeconds,
			List<EnergyMeasurement> measurements) {
		double total = measurements.stream().mapToDouble(EnergyMeasurement::energyJoules).sum();
		return new EnergyReport(runId, timestamp, durationSeconds, measurements, total);
	}

	/**
	 * Returns the top {@code n} most energy-consuming entries, sorted descending.
	 */
	public List<EnergyMeasurement> topMethods(int n) {
		return measurements.stream()
			.sorted(Comparator.comparingDouble(EnergyMeasurement::energyJoules).reversed())
			.limit(n)
			.toList();
	}
}
