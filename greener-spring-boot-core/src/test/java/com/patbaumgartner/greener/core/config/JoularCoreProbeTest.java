package com.patbaumgartner.greener.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JoularCoreProbeTest {

	private static Path fakeJoularCore(Path dir, String body) throws IOException {
		Path script = dir.resolve("joularcore");
		Files.writeString(script, body);
		Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE));
		return script;
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void returnsCpuWhenCpuPowerIsAvailable(@TempDir Path tempDir) throws IOException {
		Path binary = fakeJoularCore(tempDir, "#!/bin/sh\necho 12.5\n");

		assertThat(JoularCoreProbe.probeJoularCoreComponent(binary)).isEqualTo("cpu");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void fallsBackToGpuWhenOnlyGpuReportsPower(@TempDir Path tempDir) throws IOException {
		Path binary = fakeJoularCore(tempDir, "#!/bin/sh\nif [ \"$2\" = \"cpu\" ]; then echo 0; else echo 12.5; fi\n");

		assertThat(JoularCoreProbe.probeJoularCoreComponent(binary)).isEqualTo("gpu");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void defaultsToCpuWhenNeitherComponentReportsPower(@TempDir Path tempDir) throws IOException {
		Path binary = fakeJoularCore(tempDir, "#!/bin/sh\necho 0\n");

		assertThat(JoularCoreProbe.probeJoularCoreComponent(binary)).isEqualTo("cpu");
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void ignoresNonNumericOutput(@TempDir Path tempDir) throws IOException {
		Path binary = fakeJoularCore(tempDir, "#!/bin/sh\necho 'warning: no RAPL domain'\necho 0\n");

		assertThat(JoularCoreProbe.probeJoularCoreComponent(binary)).isEqualTo("cpu");
	}

	@Test
	void defaultsToCpuWhenTheBinaryCannotBeLaunched(@TempDir Path tempDir) {
		Path missing = tempDir.resolve("does-not-exist");

		assertThat(JoularCoreProbe.probeJoularCoreComponent(missing)).isEqualTo("cpu");
	}

}
