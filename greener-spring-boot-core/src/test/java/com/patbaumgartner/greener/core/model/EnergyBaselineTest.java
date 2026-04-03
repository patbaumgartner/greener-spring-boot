package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyBaselineTest {

	@Test
	void of_withVcsMetadata() {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, java.util.List.of());
		EnergyBaseline baseline = EnergyBaseline.of(report, "abc123", "main");

		assertThat(baseline.version()).isEqualTo(EnergyBaseline.CURRENT_VERSION);
		assertThat(baseline.commitSha()).isEqualTo("abc123");
		assertThat(baseline.branch()).isEqualTo("main");
		assertThat(baseline.report()).isSameAs(report);
		assertThat(baseline.createdAt()).isNotNull();
	}

	@Test
	void of_withoutVcsMetadata() {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, java.util.List.of());
		EnergyBaseline baseline = EnergyBaseline.of(report);

		assertThat(baseline.commitSha()).isNull();
		assertThat(baseline.branch()).isNull();
		assertThat(baseline.report()).isSameAs(report);
	}

}
