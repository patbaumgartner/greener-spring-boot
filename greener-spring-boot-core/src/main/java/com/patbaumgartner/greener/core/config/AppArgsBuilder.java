package com.patbaumgartner.greener.core.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the effective application argument list for the Spring Boot process launched
 * during energy measurement.
 *
 * <p>
 * This class was extracted from {@link PluginDefaults} to isolate the argument assembly
 * logic — including automatic port injection from the base URL and optional Actuator
 * shutdown endpoint enablement — from the other plugin utilities.
 */
public final class AppArgsBuilder {

	private AppArgsBuilder() {
	}

	/**
	 * Builds the effective application arguments list by copying user-provided arguments
	 * and appending the Spring Boot health-probe enablement flag.
	 * @param userArgs optional user-supplied arguments (may be {@code null})
	 * @return a mutable list of effective arguments
	 */
	public static List<String> buildEffectiveAppArgs(List<String> userArgs) {
		return buildEffectiveAppArgs(userArgs, false);
	}

	/**
	 * Builds the effective application arguments list by copying user-provided arguments,
	 * appending the Spring Boot health-probe enablement flag, and optionally enabling the
	 * Actuator shutdown endpoint for graceful JVM shutdown on Windows.
	 * @param userArgs optional user-supplied arguments (may be {@code null})
	 * @param enableShutdownEndpoint whether to enable the Actuator shutdown endpoint
	 * (typically when Joular Code Java is attached for method-level monitoring)
	 * @return a mutable list of effective arguments
	 */
	public static List<String> buildEffectiveAppArgs(List<String> userArgs, boolean enableShutdownEndpoint) {
		return buildEffectiveAppArgs(userArgs, enableShutdownEndpoint, null);
	}

	/**
	 * Builds the effective application arguments list by copying user-provided arguments,
	 * appending the Spring Boot health-probe enablement flag, optionally enabling the
	 * Actuator shutdown endpoint, and auto-injecting {@code --server.port} from the
	 * {@code baseUrl} when not already specified in user args.
	 *
	 * <p>
	 * This ensures the Spring Boot application listens on the same port that the plugin
	 * uses for health checks and workload tools, enabling random port assignment for
	 * stable CI runs.
	 * @param userArgs optional user-supplied arguments (may be {@code null})
	 * @param enableShutdownEndpoint whether to enable the Actuator shutdown endpoint
	 * @param baseUrl the base URL (e.g. {@code http://localhost:9123}); when non-null and
	 * no {@code --server.port} is present in {@code userArgs}, the port is extracted and
	 * appended automatically
	 * @return a mutable list of effective arguments
	 */
	public static List<String> buildEffectiveAppArgs(List<String> userArgs, boolean enableShutdownEndpoint,
			String baseUrl) {
		List<String> effective = new ArrayList<>();
		if (userArgs != null) {
			effective.addAll(userArgs);
		}
		if (baseUrl != null && effective.stream().noneMatch(a -> a.startsWith("--server.port="))) {
			int port = extractPort(baseUrl);
			if (port > 0 && port != 8080) {
				effective.add("--server.port=" + port);
			}
		}
		effective.add("--management.endpoint.health.probes.enabled=true");
		if (enableShutdownEndpoint) {
			effective.add("--management.endpoint.shutdown.enabled=true");
			effective.add("--management.endpoints.web.exposure.include=health,shutdown");
		}
		return effective;
	}

	static int extractPort(String baseUrl) {
		try {
			int port = URI.create(baseUrl).getPort();
			return port > 0 ? port : ("https".equals(URI.create(baseUrl).getScheme()) ? 443 : 80);
		}
		catch (IllegalArgumentException ex) {
			return -1;
		}
	}

}
