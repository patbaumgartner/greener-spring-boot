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
 *
 * <p>
 * When the plugin runs more than one measurement iteration, {@link #totalEnergyStats}
 * captures the run-to-run distribution of {@link #totalEnergyJoules} and enables the
 * comparator to apply a statistical significance test instead of a raw percentage
 * threshold. Single-iteration runs leave {@code totalEnergyStats} as
 * {@link Statistics#empty()}.
 */
public record EnergyReport(String runId, Instant timestamp, long durationSeconds, List<EnergyMeasurement> measurements,
		double totalEnergyJoules, Statistics totalEnergyStats) {

	public EnergyReport {
		measurements = measurements == null ? Collections.emptyList() : Collections.unmodifiableList(measurements);
		if (totalEnergyJoules < 0) {
			throw new IllegalArgumentException("totalEnergyJoules must be >= 0");
		}
		if (totalEnergyStats == null) {
			totalEnergyStats = Statistics.empty();
		}
	}

	/**
	 * Convenience constructor for callers that don't carry per-iteration statistics
	 * (single-iteration runs, JSON deserialisers, simple unit tests). Defaults
	 * {@code totalEnergyStats} to {@link Statistics#empty()}.
	 */
	public EnergyReport(String runId, Instant timestamp, long durationSeconds, List<EnergyMeasurement> measurements,
			double totalEnergyJoules) {
		this(runId, timestamp, durationSeconds, measurements, totalEnergyJoules, Statistics.empty());
	}

	/**
	 * Convenience factory that derives {@code totalEnergyJoules} from the measurements.
	 */
	public static EnergyReport of(String runId, Instant timestamp, long durationSeconds,
			List<EnergyMeasurement> measurements) {
		double total = measurements.stream().mapToDouble(EnergyMeasurement::energyJoules).sum();
		return new EnergyReport(runId, timestamp, durationSeconds, measurements, total, Statistics.empty());
	}

	/**
	 * Convenience factory that attaches multi-iteration {@link Statistics} to a report.
	 * The representative {@code totalEnergyJoules} is taken to be the mean of the
	 * provided statistics so that downstream consumers see a stable summary value.
	 */
	public static EnergyReport of(String runId, Instant timestamp, long durationSeconds,
			List<EnergyMeasurement> measurements, Statistics totalEnergyStats) {
		double total = totalEnergyStats != null && totalEnergyStats.n() > 0 ? totalEnergyStats.mean()
				: measurements.stream().mapToDouble(EnergyMeasurement::energyJoules).sum();
		return new EnergyReport(runId, timestamp, durationSeconds, measurements, total, totalEnergyStats);
	}

	/** Returns a copy of this report with the given statistics attached. */
	public EnergyReport withStats(Statistics stats) {
		return new EnergyReport(runId, timestamp, durationSeconds, measurements, totalEnergyJoules,
				stats == null ? Statistics.empty() : stats);
	}

	/**
	 * Returns the top {@code n} most energy-consuming entries, sorted descending.
	 */
	public List<EnergyMeasurement> topMeasurements(int n) {
		return measurements.stream()
			.sorted(Comparator.comparingDouble(EnergyMeasurement::energyJoules).reversed())
			.limit(n)
			.toList();
	}

}
