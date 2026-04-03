package com.patbaumgartner.greener.core.baseline;

import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineManagerTest {

	private final BaselineManager manager = new BaselineManager();

	@Test
	void saveAndLoadBaseline_roundTrip(@TempDir Path tmp) throws IOException {
		EnergyReport report = new EnergyReport("run-123", Instant.parse("2024-01-15T10:30:00Z"), 60L, List
			.of(new EnergyMeasurement("petclinic [app]", 150.0), new EnergyMeasurement("system [total cpu]", 400.0)),
				550.0);

		Path baselineFile = tmp.resolve("energy-baseline.json");
		manager.saveBaseline(report, "abc123", "main", baselineFile);

		Optional<EnergyBaseline> loaded = manager.loadBaseline(baselineFile);

		assertThat(loaded).isPresent();
		EnergyBaseline b = loaded.get();
		assertThat(b.commitSha()).isEqualTo("abc123");
		assertThat(b.branch()).isEqualTo("main");
		assertThat(b.version()).isEqualTo(EnergyBaseline.CURRENT_VERSION);
		assertThat(b.report().runId()).isEqualTo("run-123");
		assertThat(b.report().totalEnergyJoules()).isEqualTo(550.0);
		assertThat(b.report().measurements()).hasSize(2);
	}

	@Test
	void loadBaseline_missingFile_returnsEmpty(@TempDir Path tmp) throws IOException {
		Optional<EnergyBaseline> result = manager.loadBaseline(tmp.resolve("missing.json"));
		assertThat(result).isEmpty();
	}

	@Test
	void saveBaseline_createsParentDirectories(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("r", Instant.now(), 60L, List.of());
		Path nested = tmp.resolve("deep/nested/path/baseline.json");

		manager.saveBaseline(report, nested);

		assertThat(nested).exists();
	}

}
