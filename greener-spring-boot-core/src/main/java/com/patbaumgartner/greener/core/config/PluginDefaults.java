package com.patbaumgartner.greener.core.config;

import com.patbaumgartner.greener.core.model.PowerSource;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared utilities used by both Maven and Gradle plugins to resolve runtime context.
 */
public final class PluginDefaults {

	private static final int EXPECTED_JAR_COUNT = 1;

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

	/**
	 * Normalises a user-supplied string by returning {@code null} for null, blank, or
	 * unresolved Maven property expressions (strings starting with {@code $&#123;}).
	 * @param value the raw input
	 * @return the trimmed value, or {@code null} if it should be treated as absent
	 */
	public static String normalise(String value) {
		return (value == null || value.isBlank() || value.startsWith("${")) ? null : value;
	}

	/**
	 * Builds the effective application arguments list by copying user-provided arguments
	 * and appending the Spring Boot health-probe enablement flag.
	 * @param userArgs optional user-supplied arguments (may be {@code null})
	 * @return a mutable list of effective arguments
	 */
	public static List<String> buildEffectiveAppArgs(List<String> userArgs) {
		List<String> effective = new ArrayList<>();
		if (userArgs != null) {
			effective.addAll(userArgs);
		}
		effective.add("--management.endpoint.health.probes.enabled=true");
		return effective;
	}

	/**
	 * Auto-detects a single Spring Boot fat-jar in the given directory, excluding common
	 * non-executable jars (sources, javadoc, tests, plain, original).
	 * @param directory the directory to scan (e.g. {@code target/} or
	 * {@code build/libs/})
	 * @param additionalExclusions extra filename suffixes to exclude (e.g.
	 * {@code "-plain.jar"})
	 * @return the single detected jar wrapped in an {@link Optional}, or empty if the
	 * directory doesn't exist
	 * @throws IllegalStateException if zero or multiple jars are found
	 */
	public static Optional<File> autoDetectJar(Path directory, String... additionalExclusions) {
		File dir = directory.toFile();
		if (!dir.isDirectory()) {
			return Optional.empty();
		}

		File[] jars = dir.listFiles((d, name) -> {
			if (!name.endsWith(".jar")) {
				return false;
			}
			if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar") || name.endsWith("-tests.jar")
					|| name.contains(".original")) {
				return false;
			}
			for (String excl : additionalExclusions) {
				if (name.endsWith(excl)) {
					return false;
				}
			}
			return true;
		});

		if (jars == null || jars.length == 0) {
			throw new IllegalStateException("No jar found in " + dir);
		}
		if (jars.length > EXPECTED_JAR_COUNT) {
			throw new IllegalStateException("Multiple jars found in " + dir + ": "
					+ Arrays.stream(jars).map(File::getName).collect(Collectors.joining(", ")));
		}

		return Optional.of(jars[0]);
	}

	/**
	 * Formats baseline update summary lines for consistent output across both plugins.
	 * @param baselineFile the updated baseline file path
	 * @param commitSha the recorded commit SHA (may be {@code null})
	 * @param branch the recorded branch name (may be {@code null})
	 * @param totalEnergyJoules the total energy in joules
	 * @return list of formatted summary lines
	 */
	public static List<String> formatBaselineUpdateSummary(Path baselineFile, String commitSha, String branch,
			double totalEnergyJoules) {
		List<String> lines = new ArrayList<>();
		lines.add("Energy baseline updated: " + baselineFile);
		lines.add("  commit : " + commitSha);
		lines.add("  branch : " + branch);
		lines.add("  energy : " + String.format("%.2f J", totalEnergyJoules));
		return lines;
	}

}
