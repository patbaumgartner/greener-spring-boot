package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TrendEntryTest {

	@Test
	void rejectsNullTimestamp() {
		assertThatIllegalArgumentException().isThrownBy(() -> new TrendEntry(null, "run", 1.0, null, null, null))
			.withMessageContaining("timestamp");
	}

	@Test
	void rejectsBlankRunId() {
		assertThatIllegalArgumentException().isThrownBy(() -> new TrendEntry(Instant.now(), " ", 1.0, null, null, null))
			.withMessageContaining("runId");
	}

	@Test
	void rejectsNegativeEnergy() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new TrendEntry(Instant.now(), "run", -0.1, null, null, null))
			.withMessageContaining("totalEnergyJoules");
	}

	@Test
	void acceptsValidEntry() {
		TrendEntry e = new TrendEntry(Instant.parse("2025-01-01T00:00:00Z"), "abc", 12.5, 0.42, "abc", "main");
		assertThat(e.totalEnergyJoules()).isEqualTo(12.5);
		assertThat(e.energyPerRequestMillijoules()).isEqualTo(0.42);
		assertThat(e.commitSha()).isEqualTo("abc");
	}

}
