package com.patbaumgartner.greener.core.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationRunnerTest {

	private final ApplicationRunner runner = new ApplicationRunner();

	@Test
	void stop_nullProcess_doesNotThrow() throws InterruptedException {
		runner.stop(null);
		// should not throw
	}

	@Test
	void stop_alreadyExitedProcess_doesNotThrow() throws Exception {
		Process process = new ProcessBuilder("/usr/bin/true").start();
		process.waitFor();

		runner.stop(process);
		// should not throw
	}

	@Test
	void stop_runningProcess_stopsGracefully() throws Exception {
		Process process = new ProcessBuilder("/usr/bin/sleep", "60").start();
		assertThat(process.isAlive()).isTrue();

		runner.stop(process);
		assertThat(process.isAlive()).isFalse();
	}

	@Test
	void start_createsWorkingDirectory(@TempDir Path tempDir) throws Exception {
		Path workDir = tempDir.resolve("subdir").resolve("work");
		// Create a minimal jar that exits immediately
		Path fakeJar = tempDir.resolve("fake.jar");
		java.nio.file.Files.writeString(fakeJar, "not-a-jar");

		Process process = runner.start(fakeJar, null, null, workDir, null, null);
		try {
			// Working directory should have been created
			assertThat(workDir).exists();
		}
		finally {
			process.destroyForcibly();
		}
	}

	@Test
	void start_passesJvmAndAppArgs(@TempDir Path tempDir) throws Exception {
		Path fakeJar = tempDir.resolve("fake.jar");
		java.nio.file.Files.writeString(fakeJar, "not-a-jar");
		Path workDir = tempDir.resolve("work");

		List<String> jvmArgs = List.of("-Xmx128m");
		List<String> appArgs = List.of("--server.port=9999");

		Process process = runner.start(fakeJar, null, null, workDir, jvmArgs, appArgs);
		try {
			assertThat(process).isNotNull();
		}
		finally {
			process.destroyForcibly();
		}
	}

	@Test
	void start_withEmptyArgs(@TempDir Path tempDir) throws Exception {
		Path fakeJar = tempDir.resolve("fake.jar");
		Files.writeString(fakeJar, "not-a-jar");
		Path workDir = tempDir.resolve("work");

		Process process = runner.start(fakeJar, null, null, workDir, Collections.emptyList(), Collections.emptyList());
		try {
			assertThat(process).isNotNull();
		}
		finally {
			process.destroyForcibly();
		}
	}

	@Test
	void start_withJoularJxAgent(@TempDir Path tempDir) throws Exception {
		Path fakeJar = tempDir.resolve("fake.jar");
		Files.writeString(fakeJar, "not-a-jar");
		Path fakeAgent = tempDir.resolve("joularjx.jar");
		Files.writeString(fakeAgent, "not-a-jar");
		Path fakeConfig = tempDir.resolve("config.properties");
		Files.writeString(fakeConfig, "key=value");
		Path workDir = tempDir.resolve("work");

		Process process = runner.start(fakeJar, fakeAgent, fakeConfig, workDir, null, null);
		try {
			assertThat(process).isNotNull();
		}
		finally {
			process.destroyForcibly();
		}
	}

	@Test
	void start_withNonExistentJoularJxAgent(@TempDir Path tempDir) throws Exception {
		Path fakeJar = tempDir.resolve("fake.jar");
		Files.writeString(fakeJar, "not-a-jar");
		Path nonExistent = tempDir.resolve("does-not-exist.jar");
		Path workDir = tempDir.resolve("work");

		Process process = runner.start(fakeJar, nonExistent, null, workDir, null, null);
		try {
			assertThat(process).isNotNull();
		}
		finally {
			process.destroyForcibly();
		}
	}

	@Test
	void start_redirectsOutputToWorkDir(@TempDir Path tempDir) throws Exception {
		Path fakeJar = tempDir.resolve("fake.jar");
		Files.writeString(fakeJar, "not-a-jar");
		Path workDir = tempDir.resolve("work");

		Process process = runner.start(fakeJar, null, null, workDir, null, null);
		try {
			assertThat(workDir.resolve("app-stdout.log")).exists();
			assertThat(workDir.resolve("app-stderr.log")).exists();
		}
		finally {
			process.destroyForcibly();
		}
	}

	@Test
	void waitForStartup_processExitedPrematurely_throwsImmediately() throws Exception {
		// Start a process that exits immediately
		Process process = new ProcessBuilder("/usr/bin/false").start();
		process.waitFor();

		assertThatThrownBy(() -> runner.waitForStartup(process, "http://localhost:19999", "/actuator/health", 5))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("exited prematurely")
			.hasMessageContaining("app-stdout.log");
	}

	@Test
	void waitForStartup_noServerRunning_throwsTimeout() {
		// Start a process that stays alive but does not listen on the port
		Process process;
		try {
			process = new ProcessBuilder("/usr/bin/sleep", "30").start();
		}
		catch (Exception ex) {
			// skip if sleep not available
			return;
		}

		try {
			assertThatThrownBy(() -> runner.waitForStartup(process, "http://localhost:19999", "/health", 3))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("did not become healthy within");
		}
		finally {
			process.destroyForcibly();
		}
	}

}
