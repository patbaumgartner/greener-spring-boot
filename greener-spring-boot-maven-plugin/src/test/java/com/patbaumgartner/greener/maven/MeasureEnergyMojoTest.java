package com.patbaumgartner.greener.maven;

import com.patbaumgartner.greener.core.config.JoularCoreConfig;
import com.patbaumgartner.greener.core.config.TrainingConfig;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasureEnergyMojoTest {

	@TempDir
	Path tempDir;

	@Test
	void skipsExecutionWhenSkipIsTrue() throws Exception {
		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "skip", true);

		// Should not throw
		mojo.execute();
	}

	@Test
	void failsWhenSpringBootJarNotFound() throws Exception {
		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "skip", false);
		setField(mojo, "springBootJar", new File("/nonexistent/app.jar"));
		setField(mojo, "measureDurationSeconds", 60);

		assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
			.hasMessageContaining("Spring Boot jar not found");
	}

	@Test
	void failsWhenMeasureDurationIsZero() throws Exception {
		File fakeJar = tempDir.resolve("app.jar").toFile();
		Files.createFile(fakeJar.toPath());

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "skip", false);
		setField(mojo, "springBootJar", fakeJar);
		setField(mojo, "measureDurationSeconds", 0);

		assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
			.hasMessageContaining("measureDurationSeconds must be > 0");
	}

	@Test
	void failsWhenExternalTrainingScriptConfiguredButMissing() throws Exception {
		File fakeJar = tempDir.resolve("app.jar").toFile();
		Files.createFile(fakeJar.toPath());

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "skip", false);
		setField(mojo, "springBootJar", fakeJar);
		setField(mojo, "measureDurationSeconds", 60);
		setField(mojo, "externalTrainingScriptFile", tempDir.resolve("missing-run.sh").toFile());

		assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
			.hasMessageContaining("externalTrainingScriptFile does not exist");
	}

	@Test
	void autoDetectsSpringBootJarFromBuildDirectory() throws Exception {
		// Create a build directory with a single jar
		Path buildDir = tempDir.resolve("target");
		Files.createDirectories(buildDir);
		Files.createFile(buildDir.resolve("myapp-1.0.jar"));

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "buildDirectory", buildDir.toFile());

		Method autoDetect = MeasureEnergyMojo.class.getDeclaredMethod("autoDetectSpringBootJar");
		autoDetect.setAccessible(true);
		File detected = (File) autoDetect.invoke(mojo);

		assertThat(detected.getName()).isEqualTo("myapp-1.0.jar");
	}

	@Test
	void autoDetectExcludesSourcesAndJavadocJars() throws Exception {
		Path buildDir = tempDir.resolve("target");
		Files.createDirectories(buildDir);
		Files.createFile(buildDir.resolve("myapp-1.0.jar"));
		Files.createFile(buildDir.resolve("myapp-1.0-sources.jar"));
		Files.createFile(buildDir.resolve("myapp-1.0-javadoc.jar"));

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "buildDirectory", buildDir.toFile());

		Method autoDetect = MeasureEnergyMojo.class.getDeclaredMethod("autoDetectSpringBootJar");
		autoDetect.setAccessible(true);
		File detected = (File) autoDetect.invoke(mojo);

		assertThat(detected.getName()).isEqualTo("myapp-1.0.jar");
	}

	@Test
	void autoDetectFailsWithMultipleJars() throws Exception {
		Path buildDir = tempDir.resolve("target");
		Files.createDirectories(buildDir);
		Files.createFile(buildDir.resolve("app1.jar"));
		Files.createFile(buildDir.resolve("app2.jar"));

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "buildDirectory", buildDir.toFile());

		Method autoDetect = MeasureEnergyMojo.class.getDeclaredMethod("autoDetectSpringBootJar");
		autoDetect.setAccessible(true);

		assertThatThrownBy(() -> autoDetect.invoke(mojo))
			.isInstanceOf(java.lang.reflect.InvocationTargetException.class)
			.cause()
			.isInstanceOf(MojoExecutionException.class)
			.hasMessageContaining("Multiple jars");
	}

	@Test
	void autoDetectFailsMissingBuildDirectory() throws Exception {
		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "buildDirectory", new File("/nonexistent-dir"));

		Method autoDetect = MeasureEnergyMojo.class.getDeclaredMethod("autoDetectSpringBootJar");
		autoDetect.setAccessible(true);

		assertThatThrownBy(() -> autoDetect.invoke(mojo))
			.isInstanceOf(java.lang.reflect.InvocationTargetException.class)
			.cause()
			.isInstanceOf(MojoExecutionException.class)
			.hasMessageContaining("build directory not found");
	}

	@Test
	void autoDetectFailsWithNoJars() throws Exception {
		Path buildDir = tempDir.resolve("target");
		Files.createDirectories(buildDir);

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "buildDirectory", buildDir.toFile());

		Method autoDetect = MeasureEnergyMojo.class.getDeclaredMethod("autoDetectSpringBootJar");
		autoDetect.setAccessible(true);

		assertThatThrownBy(() -> autoDetect.invoke(mojo))
			.isInstanceOf(java.lang.reflect.InvocationTargetException.class)
			.cause()
			.isInstanceOf(MojoExecutionException.class)
			.hasMessageContaining("No jar found");
	}

	@Test
	void failsWhenExternalTrainingCommandIsBlank() throws Exception {
		File fakeJar = tempDir.resolve("app.jar").toFile();
		Files.createFile(fakeJar.toPath());

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "skip", false);
		setField(mojo, "springBootJar", fakeJar);
		setField(mojo, "measureDurationSeconds", 60);
		setField(mojo, "externalTrainingCommand", "   ");

		assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
	}

	@Test
	void failsWhenJoularCodeJavaAgentPathConfiguredButMissing() throws Exception {
		File fakeJar = tempDir.resolve("app.jar").toFile();
		Files.createFile(fakeJar.toPath());

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "skip", false);
		setField(mojo, "springBootJar", fakeJar);
		setField(mojo, "measureDurationSeconds", 60);
		setField(mojo, "joularCodeJavaAgentPath", tempDir.resolve("missing-agent.jar").toFile());

		assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class)
			.hasMessageContaining("joularCodeJavaAgentPath does not exist");
	}

	// ---- buildTrainingConfig ----

	@Test
	void buildTrainingConfig_propagatesTheWorkloadTimeout() throws Exception {
		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "baseUrl", "http://localhost:8080");
		setField(mojo, "requestsPerSecond", 5);
		setField(mojo, "externalTrainingTimeoutSeconds", 45);
		setField(mojo, "externalTrainingCommand", "oha -n 10 http://localhost:8080/");

		TrainingConfig config = invokeBuildTrainingConfig(mojo);

		assertThat(config.getTimeoutSeconds()).isEqualTo(45);
		assertThat(config.getExternalCommand()).isEqualTo("oha -n 10 http://localhost:8080/");
	}

	@Test
	void buildTrainingConfig_defaultTimeoutIsUnbounded() throws Exception {
		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "baseUrl", "http://localhost:8080");
		setField(mojo, "requestsPerSecond", 5);
		setField(mojo, "externalTrainingCommand", "echo hi");

		assertThat(invokeBuildTrainingConfig(mojo).getTimeoutSeconds()).isZero();
	}

	@Test
	void buildTrainingConfig_scriptFileTakesPrecedenceOverCommand() throws Exception {
		File script = tempDir.resolve("run.sh").toFile();
		Files.createFile(script.toPath());

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "baseUrl", "http://localhost:9999");
		setField(mojo, "requestsPerSecond", 7);
		setField(mojo, "externalTrainingScriptFile", script);
		setField(mojo, "externalTrainingCommand", "should-be-ignored");

		TrainingConfig config = invokeBuildTrainingConfig(mojo);

		assertThat(config.getExternalScriptFile()).isEqualTo(script.getAbsolutePath());
		assertThat(config.getExternalCommand()).isNull();
		assertThat(config.getBaseUrl()).isEqualTo("http://localhost:9999");
		assertThat(config.getRequestsPerSecond()).isEqualTo(7);
	}

	// ---- createJoularCoreConfig ----

	@Test
	void createJoularCoreConfig_enablesRingBufferOnlyWithTheJoularCodeJavaAgent() throws Exception {
		File agent = tempDir.resolve("agent.jar").toFile();
		Files.createFile(agent.toPath());
		Path binary = tempDir.resolve("joularcore");
		Path csv = tempDir.resolve("out.csv");

		MeasureEnergyMojo withoutAgent = new MeasureEnergyMojo();
		setField(withoutAgent, "joularCoreComponent", "cpu");
		assertThat(invokeCreateJoularCoreConfig(withoutAgent, binary, 123L, csv).isRingBuffer()).isFalse();

		MeasureEnergyMojo withAgent = new MeasureEnergyMojo();
		setField(withAgent, "joularCoreComponent", "cpu");
		setField(withAgent, "joularCodeJavaAgentPath", agent);
		assertThat(invokeCreateJoularCoreConfig(withAgent, binary, 123L, csv).isRingBuffer()).isTrue();
	}

	@Test
	void createJoularCoreConfig_vmModeCarriesThePowerFileIntoTheEnvironment() throws Exception {
		File powerFile = tempDir.resolve("power.txt").toFile();
		Files.createFile(powerFile.toPath());

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "joularCoreComponent", "cpu");
		setField(mojo, "vmMode", true);
		setField(mojo, "vmPowerFilePath", powerFile);

		JoularCoreConfig config = invokeCreateJoularCoreConfig(mojo, tempDir.resolve("joularcore"), 7L,
				tempDir.resolve("out.csv"));

		assertThat(config.isVmMode()).isTrue();
		assertThat(config.buildVmEnvironment()).containsEntry("VM_CPU_POWER_FILE", powerFile.getAbsolutePath())
			.containsEntry("VM_CPU_POWER_FORMAT", "watts");
	}

	@Test
	void createJoularCoreConfig_monitorsTheApplicationByPid() throws Exception {
		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "joularCoreComponent", "cpu");
		Path binary = tempDir.resolve("joularcore");

		JoularCoreConfig config = invokeCreateJoularCoreConfig(mojo, binary, 4242L, tempDir.resolve("out.csv"));

		assertThat(config.buildCommand(binary)).containsSequence("-p", "4242").contains("-s");
	}

	// ---- resolveJoularCoreBinary ----

	@Test
	void resolveJoularCoreBinary_explicitPathThatDoesNotExist_failsWithActionableMessage() throws Exception {
		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "joularCoreBinaryPath", tempDir.resolve("nope").toFile());

		Method resolve = MeasureEnergyMojo.class.getDeclaredMethod("resolveJoularCoreBinary");
		resolve.setAccessible(true);

		assertThatThrownBy(() -> resolve.invoke(mojo)).hasRootCauseInstanceOf(MojoExecutionException.class)
			.rootCause()
			.hasMessageContaining("does not exist")
			.hasMessageContaining("enable auto-download");
	}

	@Test
	void resolveJoularCoreBinary_explicitPathThatExists_isUsedVerbatim() throws Exception {
		Path binary = tempDir.resolve("joularcore");
		Files.createFile(binary);

		MeasureEnergyMojo mojo = new MeasureEnergyMojo();
		setField(mojo, "joularCoreBinaryPath", binary.toFile());

		Method resolve = MeasureEnergyMojo.class.getDeclaredMethod("resolveJoularCoreBinary");
		resolve.setAccessible(true);

		assertThat((Path) resolve.invoke(mojo)).isEqualTo(binary);
	}

	private static TrainingConfig invokeBuildTrainingConfig(MeasureEnergyMojo mojo) throws Exception {
		Method build = MeasureEnergyMojo.class.getDeclaredMethod("buildTrainingConfig");
		build.setAccessible(true);
		return (TrainingConfig) build.invoke(mojo);
	}

	private static JoularCoreConfig invokeCreateJoularCoreConfig(MeasureEnergyMojo mojo, Path binary, long pid,
			Path outputCsv) throws Exception {
		Method create = MeasureEnergyMojo.class.getDeclaredMethod("createJoularCoreConfig", Path.class, long.class,
				Path.class);
		create.setAccessible(true);
		return (JoularCoreConfig) create.invoke(mojo, binary, pid, outputCsv);
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

}
