package com.patbaumgartner.greener.core.doctor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentDoctorTest {

	@Test
	void runProducesAtLeastTheCoreChecks() {
		EnvironmentDoctor.Report report = EnvironmentDoctor.run(null, null, null, null);
		assertThat(report.checks()).extracting(EnvironmentDoctor.Check::name)
			.contains("OS / arch", "RAPL energy file", "msr kernel module", "Joular Core binary", "JoularJX agent");
	}

	@Test
	void workloadCommandCheckFailsWhenAbsent() {
		EnvironmentDoctor.Report report = EnvironmentDoctor.run(null, null, "definitely-not-a-real-tool-xyz", null);
		assertThat(report.checks()).anySatisfy(c -> assertThat(c.name()).contains("definitely-not-a-real-tool-xyz"));
	}

	@Test
	void joularJxAgentCheckPassesWhenFileReadable(@TempDir Path tmp) throws IOException {
		Path agent = tmp.resolve("joularjx.jar");
		Files.writeString(agent, "stub");
		EnvironmentDoctor.Report report = EnvironmentDoctor.run(null, agent, null, null);
		assertThat(report.checks()).anySatisfy(c -> assertThat(c.name()).isEqualTo("JoularJX agent"));
	}

	@Test
	void jarAutoDetectFindsJar(@TempDir Path tmp) throws IOException {
		Path target = Files.createDirectory(tmp.resolve("target"));
		Files.writeString(target.resolve("myapp-1.0.jar"), "stub");
		EnvironmentDoctor.Report report = EnvironmentDoctor.run(null, null, null, tmp);
		assertThat(report.checks()).anySatisfy(c -> {
			if ("Spring Boot jar auto-detect".equals(c.name())) {
				assertThat(c.level()).isEqualTo(EnvironmentDoctor.Level.PASS);
				assertThat(c.message()).contains("myapp-1.0.jar");
			}
		});
	}

	@Test
	void formatRendersResultLine() {
		EnvironmentDoctor.Report report = EnvironmentDoctor.run(null, null, null, null);
		String text = EnvironmentDoctor.format(report);
		assertThat(text).contains("[greener] Environment doctor:").contains("[greener] Result:");
	}

}
