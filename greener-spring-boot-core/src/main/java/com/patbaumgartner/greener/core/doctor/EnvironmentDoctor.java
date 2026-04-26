package com.patbaumgartner.greener.core.doctor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Preflight environment check for greener-spring-boot. Runs a set of read-only probes
 * (OS, RAPL, msr module, Joular Core binary, JoularJX agent, workload tool, jar
 * detection) and returns a {@link Report}. Designed to fail fast and tell the user
 * exactly what to fix &mdash; before they wait minutes for a real measurement to break.
 */
public final class EnvironmentDoctor {

	/** Outcome levels. */
	public enum Level {

		/** All good. */
		PASS,
		/** Works but suboptimal. */
		WARN,
		/** Will block measurement. */
		FAIL

	}

	/** A single check line. */
	public record Check(String name, Level level, String message, String hint) {
	}

	/** Aggregated doctor report. */
	public record Report(List<Check> checks) {

		public boolean hasFailures() {
			return checks.stream().anyMatch(c -> c.level() == Level.FAIL);
		}

		public boolean hasWarnings() {
			return checks.stream().anyMatch(c -> c.level() == Level.WARN);
		}

	}

	private EnvironmentDoctor() {
	}

	private static final String CHECK_OS_ARCH = "OS / arch";

	private static final String CHECK_RAPL = "RAPL energy file";

	private static final String CHECK_MSR = "msr kernel module";

	private static final String CHECK_JOULAR_CORE = "Joular Core binary";

	private static final String OS_NAME_PROP = "os.name";

	/**
	 * Runs all preflight checks.
	 * @param joularCoreBinary optional path to the Joular Core binary (may be
	 * {@code null})
	 * @param joularJxAgent optional path to the JoularJX agent jar (may be {@code null})
	 * @param workloadCommand optional first token of the configured workload command
	 * @param projectDir optional project root for jar auto-detection
	 * @return a {@link Report} aggregating all checks
	 */
	public static Report run(Path joularCoreBinary, Path joularJxAgent, String workloadCommand, Path projectDir) {
		List<Check> checks = new ArrayList<>();
		checks.add(checkOsArch());
		checks.add(checkRapl());
		checks.add(checkMsrModule());
		checks.add(checkJoularCoreBinary(joularCoreBinary));
		checks.add(checkJoularJxAgent(joularJxAgent));
		if (workloadCommand != null && !workloadCommand.isBlank()) {
			checks.add(checkOnPath(workloadCommand));
		}
		if (projectDir != null) {
			checks.add(checkJarAutoDetect(projectDir));
		}
		return new Report(List.copyOf(checks));
	}

	private static Check checkOsArch() {
		String os = System.getProperty(OS_NAME_PROP, "?").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "?");
		String value = System.getProperty(OS_NAME_PROP) + " / " + arch;
		if (os.contains("linux") || os.contains("mac") || os.contains("windows")) {
			return new Check(CHECK_OS_ARCH, Level.PASS, value, null);
		}
		return new Check(CHECK_OS_ARCH, Level.WARN, value, "Joular Core is tested on Linux, macOS and Windows.");
	}

	private static Check checkRapl() {
		Path rapl = Paths.get("/sys/class/powercap/intel-rapl:0/energy_uj");
		if (Files.isReadable(rapl)) {
			return new Check(CHECK_RAPL, Level.PASS, rapl.toString(), null);
		}
		if (Files.exists(rapl)) {
			return new Check(CHECK_RAPL, Level.FAIL, rapl + " exists but is not readable",
					"Run with --vm mode or grant read access: sudo chmod -R a+r /sys/class/powercap/intel-rapl");
		}
		String os = System.getProperty(OS_NAME_PROP, "").toLowerCase(Locale.ROOT);
		if (os.contains("linux")) {
			return new Check(CHECK_RAPL, Level.FAIL, "not found",
					"Enable Intel/AMD RAPL or set vmMode=true and provide vmPowerFilePath");
		}
		return new Check(CHECK_RAPL, Level.WARN, "not applicable on " + os,
				"On macOS/Windows Joular Core uses platform-specific power sources");
	}

	private static Check checkMsrModule() {
		String os = System.getProperty(OS_NAME_PROP, "").toLowerCase(Locale.ROOT);
		if (!os.contains("linux")) {
			return new Check(CHECK_MSR, Level.PASS, "not required on " + os, null);
		}
		Path modules = Paths.get("/proc/modules");
		if (!Files.isReadable(modules)) {
			return new Check(CHECK_MSR, Level.WARN, "/proc/modules unreadable",
					"Cannot verify; usually fine if RAPL is readable");
		}
		try (var stream = Files.lines(modules)) {
			boolean loaded = stream.anyMatch(l -> l.startsWith("msr "));
			if (loaded) {
				return new Check(CHECK_MSR, Level.PASS, "loaded", null);
			}
			return new Check(CHECK_MSR, Level.WARN, "not loaded",
					"sudo modprobe msr (only required for some Joular Core power sources)");
		}
		catch (IOException ex) {
			return new Check(CHECK_MSR, Level.WARN, "I/O error: " + ex.getMessage(), null);
		}
	}

	private static Check checkJoularCoreBinary(Path binary) {
		if (binary == null) {
			return new Check(CHECK_JOULAR_CORE, Level.WARN, "not yet resolved",
					"Will be auto-downloaded on first run; ensure outbound HTTPS to api.github.com");
		}
		if (Files.isExecutable(binary)) {
			return new Check(CHECK_JOULAR_CORE, Level.PASS, binary.toString(), null);
		}
		if (Files.exists(binary)) {
			return new Check(CHECK_JOULAR_CORE, Level.FAIL, binary + " exists but is not executable",
					"chmod +x " + binary);
		}
		return new Check(CHECK_JOULAR_CORE, Level.FAIL, "not found at " + binary,
				"Delete ~/.greener/cache/joularcore to force re-download, or set joularCoreBinaryPath");
	}

	private static Check checkJoularJxAgent(Path agent) {
		if (agent == null) {
			return new Check("JoularJX agent", Level.PASS, "not configured (method-level disabled)", null);
		}
		if (Files.isReadable(agent)) {
			return new Check("JoularJX agent", Level.PASS, agent.toString(), null);
		}
		return new Check("JoularJX agent", Level.FAIL, "not found at " + agent,
				"Download joularjx-*.jar from https://github.com/joular/joularjx/releases");
	}

	private static Check checkOnPath(String command) {
		String pathEnv = System.getenv("PATH");
		if (pathEnv == null) {
			return new Check("Workload tool '" + command + "'", Level.WARN, "PATH not set", null);
		}
		String[] dirs = pathEnv.split(java.io.File.pathSeparator);
		for (String d : dirs) {
			Path candidate = Paths.get(d, command);
			if (Files.isExecutable(candidate)) {
				return new Check("Workload tool '" + command + "'", Level.PASS, candidate.toString(), null);
			}
		}
		return new Check("Workload tool '" + command + "'", Level.FAIL, "not found on PATH",
				"Install via your package manager (apt/brew/winget) or pin the absolute path");
	}

	private static Check checkJarAutoDetect(Path projectDir) {
		Path target = projectDir.resolve("target");
		Path build = projectDir.resolve("build").resolve("libs");
		Path[] candidates = { target, build };
		for (Path dir : candidates) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (var stream = Files.list(dir)) {
				var jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
					.filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
					.toList();
				if (!jars.isEmpty()) {
					return new Check("Spring Boot jar auto-detect", Level.PASS, jars.get(0).toString(), null);
				}
			}
			catch (IOException ex) {
				continue;
			}
		}
		return new Check("Spring Boot jar auto-detect", Level.WARN, "no jar found in target/ or build/libs/",
				"Build first (mvn package / ./gradlew bootJar) or set springBootJar explicitly");
	}

	/**
	 * Renders a human-readable report.
	 * @param report the doctor report
	 * @return formatted multi-line text suitable for {@code System.out.println}
	 */
	public static String format(Report report) {
		StringBuilder sb = new StringBuilder(512);
		sb.append("[greener] Environment doctor:\n");
		for (Check c : report.checks()) {
			sb.append("  ").append(badge(c.level())).append(' ').append(c.name()).append(": ").append(c.message());
			if (c.hint() != null) {
				sb.append("\n      hint: ").append(c.hint());
			}
			sb.append('\n');
		}
		if (report.hasFailures()) {
			sb.append("[greener] Result: FAIL — fix the items above before running greener:measure.\n");
		}
		else if (report.hasWarnings()) {
			sb.append("[greener] Result: WARN — measurement should work but accuracy may be reduced.\n");
		}
		else {
			sb.append("[greener] Result: PASS — all checks green.\n");
		}
		return sb.toString();
	}

	private static String badge(Level level) {
		return switch (level) {
			case PASS -> "[ OK ]";
			case WARN -> "[WARN]";
			case FAIL -> "[FAIL]";
		};
	}

}
