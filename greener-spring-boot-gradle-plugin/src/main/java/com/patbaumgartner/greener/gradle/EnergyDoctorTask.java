package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.doctor.EnvironmentDoctor;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.nio.file.Path;

/**
 * Runs preflight environment checks for the greener-spring-boot Gradle plugin. Verifies
 * OS, RAPL access, msr module, Joular Core binary, JoularJX agent, workload tool on PATH,
 * and Spring Boot jar auto-detection. Fails fast and tells you exactly what to fix before
 * running an actual measurement.
 *
 * <pre>
 * ./gradlew energyDoctor
 * </pre>
 */
@DisableCachingByDefault(because = "Environment probe is read-only and must run every time")
public abstract class EnergyDoctorTask extends DefaultTask {

	/**
	 * Optional Joular Core binary path. Marked {@link Internal} because the doctor
	 * deliberately tolerates a missing file (that's part of the diagnostic).
	 * @return the property
	 */
	@Internal
	public abstract RegularFileProperty getJoularCoreBinaryPath();

	/**
	 * Optional Joular Code Java agent path. Marked {@link Internal} for the same reason
	 * as {@link #getJoularCoreBinaryPath()}.
	 * @return the property
	 */
	@Internal
	public abstract RegularFileProperty getJoularCodeJavaAgentPath();

	/**
	 * Optional first token of the workload command (e.g. {@code oha}).
	 * @return the property
	 */
	@Optional
	@Input
	public abstract Property<String> getWorkloadCommand();

	/**
	 * When {@code true} (default) the build fails on any FAIL-level check.
	 * @return the property
	 */
	@Input
	public abstract Property<Boolean> getFailOnError();

	/**
	 * Project directory used for jar auto-detection.
	 * @return the property
	 */
	@Internal
	public abstract Property<java.io.File> getProjectDir();

	/**
	 * Runs the doctor checks and prints them via the Gradle logger.
	 */
	@TaskAction
	public void runDoctor() {
		Path binary = getJoularCoreBinaryPath().isPresent() ? getJoularCoreBinaryPath().get().getAsFile().toPath()
				: null;
		Path agent = getJoularCodeJavaAgentPath().isPresent() ? getJoularCodeJavaAgentPath().get().getAsFile().toPath()
				: null;
		String cmd = getWorkloadCommand().getOrNull();
		Path projectPath = getProjectDir().isPresent() ? getProjectDir().get().toPath() : null;

		EnvironmentDoctor.Report report = EnvironmentDoctor.run(binary, agent, cmd, projectPath);
		String text = EnvironmentDoctor.format(report);
		for (String line : text.split("\n")) {
			getLogger().lifecycle(line);
		}
		if (getFailOnError().getOrElse(true) && report.hasFailures()) {
			throw new GradleException("energyDoctor reported failures (see log above)");
		}
	}

}
