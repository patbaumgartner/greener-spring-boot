package com.patbaumgartner.greener.core.config;

import com.patbaumgartner.greener.core.model.PowerSource;

/**
 * Shared utilities used by both Maven and Gradle plugins to resolve runtime context.
 */
public final class PluginDefaults {

	private PluginDefaults() {
	}

	/**
	 * Builds a short run identifier from the environment. Uses the first 8 characters of
	 * {@code GITHUB_SHA} when available, otherwise falls back to the current timestamp.
	 */
	public static String buildRunId() {
		String sha = System.getenv("GITHUB_SHA");
		return sha != null && !sha.isBlank() ? sha.substring(0, Math.min(sha.length(), 8))
				: String.valueOf(System.currentTimeMillis());
	}

	/**
	 * Resolves the {@link PowerSource} from system properties, environment variables, or
	 * the VM mode flag, in that order of precedence.
	 * <ol>
	 * <li>System property {@code greener.powerSource}</li>
	 * <li>Environment variable {@code POWER_SOURCE}</li>
	 * <li>{@link PowerSource#detect(boolean)} based on {@code vmMode}</li>
	 * </ol>
	 */
	public static PowerSource resolvePowerSource(boolean vmMode) {
		String override = System.getProperty("greener.powerSource");
		if (override != null && !override.isBlank()) {
			return PowerSource.fromString(override);
		}
		String envOverride = System.getenv("POWER_SOURCE");
		if (envOverride != null && !envOverride.isBlank()) {
			return PowerSource.fromString(envOverride);
		}
		return PowerSource.detect(vmMode);
	}

}
