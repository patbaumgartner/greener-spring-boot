package com.patbaumgartner.greener.maven;

import com.patbaumgartner.greener.core.doctor.EnvironmentDoctor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs preflight environment checks for the greener-spring-boot plugins. Verifies OS,
 * RAPL access, msr module, Joular Core binary, JoularJX agent, workload tool on PATH, and
 * Spring Boot jar auto-detection. Fails fast and tells you exactly what to fix before
 * running an actual measurement.
 *
 * <pre>
 * mvn greener:doctor
 * </pre>
 */
@Mojo(name = "doctor", defaultPhase = LifecyclePhase.NONE, requiresProject = false, threadSafe = true)
public class DoctorMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project.basedir}", readonly = true)
	private File projectBasedir;

	/** Optional path to an existing Joular Core binary (skips download check). */
	@Parameter(property = "greener.joularCoreBinaryPath")
	private File joularCoreBinaryPath;

	/** Optional path to a JoularJX agent jar. */
	@Parameter(property = "greener.joularJxAgentPath")
	private File joularJxAgentPath;

	/**
	 * Optional first token of the workload command (e.g. {@code oha}). When set, the
	 * doctor verifies it is on {@code PATH}.
	 */
	@Parameter(property = "greener.workloadCommand")
	private String workloadCommand;

	/** Fail the build when any check is FAIL. Default {@code true}. */
	@Parameter(property = "greener.doctor.failOnError", defaultValue = "true")
	private boolean failOnError;

	@Override
	public void execute() throws MojoFailureException {
		Path projectDir = (projectBasedir != null) ? projectBasedir.toPath() : Paths.get("").toAbsolutePath();
		Path binary = (joularCoreBinaryPath != null) ? joularCoreBinaryPath.toPath() : null;
		Path agent = (joularJxAgentPath != null) ? joularJxAgentPath.toPath() : null;

		EnvironmentDoctor.Report report = EnvironmentDoctor.run(binary, agent, workloadCommand, projectDir);
		String text = EnvironmentDoctor.format(report);
		for (String line : text.split("\n")) {
			getLog().info(line);
		}
		if (failOnError && report.hasFailures()) {
			throw new MojoFailureException("greener:doctor reported failures (see log above)");
		}
	}

}
