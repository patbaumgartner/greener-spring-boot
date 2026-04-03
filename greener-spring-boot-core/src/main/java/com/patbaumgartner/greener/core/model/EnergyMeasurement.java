package com.patbaumgartner.greener.core.model;

/**
 * Represents the energy consumption measurement of a single Java method or process.
 * Energy is measured in joules.
 *
 * <p>
 * When using Joular Core in process-mode (no JoularJX agent), the {@code methodName} will
 * hold the monitored process / application name.
 */
public record EnergyMeasurement(String methodName, double energyJoules) {

	public EnergyMeasurement {
		if (methodName == null || methodName.isBlank()) {
			throw new IllegalArgumentException("methodName must not be null or blank");
		}
		if (energyJoules < 0) {
			throw new IllegalArgumentException("energyJoules must be >= 0");
		}
	}

	/** Returns the simple class/method name portion of a fully-qualified identifier. */
	public String simpleMethodName() {
		int lastDot = methodName.lastIndexOf('.');
		return lastDot >= 0 ? methodName.substring(lastDot + 1) : methodName;
	}
}
