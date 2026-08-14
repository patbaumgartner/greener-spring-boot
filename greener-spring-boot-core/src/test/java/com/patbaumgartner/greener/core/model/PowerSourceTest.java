package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class PowerSourceTest {

	@Test
	void detect_nonVmMode_reportsRaplOnlyWhenCountersAreActuallyReadable() {
		PowerSource detected = PowerSource.detect(false);

		assertThat(detected).isEqualTo(PowerSource.raplCountersReadable() ? PowerSource.RAPL : PowerSource.ESTIMATED);
	}

	@Test
	void detect_nonVmMode_neverClaimsRaplWithoutReadableCounters() {
		assumeThat(PowerSource.raplCountersReadable()).as("host exposes readable RAPL counters").isFalse();

		assertThat(PowerSource.detect(false)).isEqualTo(PowerSource.ESTIMATED);
	}

	@Test
	void raplCountersReadable_falseOnNonLinuxPlatforms() {
		assumeThat(System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH)).doesNotContain("linux");

		assertThat(PowerSource.raplCountersReadable()).isFalse();
	}

	@Test
	void detect_vmMode_returnsEstimated() {
		assertThat(PowerSource.detect(true)).isEqualTo(PowerSource.ESTIMATED);
	}

	@Test
	void fromString_rapl() {
		assertThat(PowerSource.fromString("rapl")).isEqualTo(PowerSource.RAPL);
		assertThat(PowerSource.fromString("RAPL")).isEqualTo(PowerSource.RAPL);
		assertThat(PowerSource.fromString("  rapl  ")).isEqualTo(PowerSource.RAPL);
	}

	@Test
	void fromString_vmFile() {
		assertThat(PowerSource.fromString("vm-file")).isEqualTo(PowerSource.VM_FILE);
		assertThat(PowerSource.fromString("scaphandre")).isEqualTo(PowerSource.VM_FILE);
	}

	@Test
	void fromString_estimated() {
		assertThat(PowerSource.fromString("estimated")).isEqualTo(PowerSource.ESTIMATED);
		assertThat(PowerSource.fromString("ci-estimated")).isEqualTo(PowerSource.ESTIMATED);
	}

	@Test
	void fromString_nullOrBlank_returnsUnknown() {
		assertThat(PowerSource.fromString(null)).isEqualTo(PowerSource.UNKNOWN);
		assertThat(PowerSource.fromString("")).isEqualTo(PowerSource.UNKNOWN);
		assertThat(PowerSource.fromString("   ")).isEqualTo(PowerSource.UNKNOWN);
	}

	@Test
	void fromString_unrecognised_returnsUnknown() {
		assertThat(PowerSource.fromString("solar-panel")).isEqualTo(PowerSource.UNKNOWN);
	}

	@Test
	void labelAndDescription_notBlank() {
		for (PowerSource source : PowerSource.values()) {
			assertThat(source.label()).isNotBlank();
			assertThat(source.description()).isNotBlank();
		}
	}

}
