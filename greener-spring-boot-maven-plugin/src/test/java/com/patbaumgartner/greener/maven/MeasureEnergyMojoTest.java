package com.patbaumgartner.greener.maven;

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

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

}
