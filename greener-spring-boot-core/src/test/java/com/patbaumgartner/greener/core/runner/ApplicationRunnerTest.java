package com.patbaumgartner.greener.core.runner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ApplicationRunnerTest {

	private final ApplicationRunner runner = new ApplicationRunner();

	@Test
	void waitForStartup_processExitedPrematurely_throwsImmediately() throws Exception {
		// Start a process that exits immediately
		Process process = new ProcessBuilder("false").start();
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
			process = new ProcessBuilder("sleep", "30").start();
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
