package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.model.EnergyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JoularCoreResultReaderTest {

	private final JoularCoreResultReader reader = new JoularCoreResultReader();

	private static final String APP_ID = "petclinic";

	@Test
	void readResults_standardCsvWithHeader_parsedCorrectly(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("joularcore.csv");
		Files.writeString(csv, """
				timestamp,cpu_power,gpu_power,total_power,cpu_usage,pid_or_app_power
				1700000001,45.2,0.0,45.2,23.1,12.3
				1700000002,47.8,0.0,47.8,25.4,14.1
				1700000003,46.5,0.0,46.5,24.0,13.0
				""");

		EnergyReport report = reader.readResults(csv, "run-1", 3L, APP_ID);

		assertThat(report.runId()).isEqualTo("run-1");
		assertThat(report.durationSeconds()).isEqualTo(3L);
		// App energy = 12.3 + 14.1 + 13.0 = 39.4 J
		// System CPU energy = 45.2 + 47.8 + 46.5 = 139.5 J
		assertThat(report.totalEnergyJoules()).isCloseTo(139.5 + 39.4, org.assertj.core.data.Offset.offset(0.01));
		assertThat(report.measurements()).hasSize(2);
	}

	@Test
	void readResults_joularCoreV0Header_parsedCorrectly(@TempDir Path tmp) throws IOException {
		// Joular Core v0.0.1-beta-1 uses a different column order
		Path csv = tmp.resolve("joularcore.csv");
		Files.writeString(csv, """
				Timestamp,Total Power (W), CPU Power (W),GPU Power (W),CPU Usage (%),Process Power (W)
				1700000001,50.0,30.0,20.0,23.1,12.0
				1700000002,52.0,32.0,20.0,25.4,14.0
				""");

		EnergyReport report = reader.readResults(csv, "run-v0", 2L, APP_ID);

		// CPU power = 30.0 + 32.0 = 62.0 J (from "CPU Power" column)
		// App energy = 12.0 + 14.0 = 26.0 J (from "Process Power" column)
		assertThat(report.measurements()).hasSize(2);
		assertThat(report.measurements()
			.stream()
			.filter(m -> m.methodName().contains("[app]"))
			.findFirst()
			.orElseThrow()
			.energyJoules()).isCloseTo(26.0, org.assertj.core.data.Offset.offset(0.01));
		assertThat(report.measurements()
			.stream()
			.filter(m -> m.methodName().contains("total cpu"))
			.findFirst()
			.orElseThrow()
			.energyJoules()).isCloseTo(62.0, org.assertj.core.data.Offset.offset(0.01));
	}

	@Test
	void readResults_joularCoreV0Header_zeroAppPower(@TempDir Path tmp) throws IOException {
		// Simulates the Windows RAPL case where CPU Power and Process Power = 0
		// but Total Power has real values from the RAPL PKG domain
		Path csv = tmp.resolve("joularcore.csv");
		Files.writeString(csv, """
				Timestamp,Total Power (W), CPU Power (W),GPU Power (W),CPU Usage (%),Process Power (W)
				1775544742,57.68,0.00,57.68,14.25,0.00
				1775544744,39.12,0.00,39.12,17.15,0.00
				""");

		EnergyReport report = reader.readResults(csv, "run-win", 2L, APP_ID);

		// CPU Power = 0.00 → falls back to Total Power as system proxy
		assertThat(report.measurements()).hasSize(1);
		assertThat(report.measurements().get(0).methodName()).contains("total cpu");
		// Total Power = 57.68 + 39.12 = 96.80 J (fallback from Total Power)
		assertThat(report.measurements().get(0).energyJoules()).isCloseTo(96.80,
				org.assertj.core.data.Offset.offset(0.01));
	}

	@Test
	void readResults_csvWithoutHeader_parsedCorrectly(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("joularcore.csv");
		Files.writeString(csv, """
				1700000001,50.0,0.0,50.0,20.0,10.0
				1700000002,52.0,0.0,52.0,22.0,11.0
				""");

		EnergyReport report = reader.readResults(csv, "run-2", 2L, APP_ID);

		// App energy = 10.0 + 11.0 = 21.0 J; System = 50.0 + 52.0 = 102.0 J
		assertThat(report.totalEnergyJoules()).isCloseTo(102.0 + 21.0, org.assertj.core.data.Offset.offset(0.01));
	}

	@Test
	void readResults_missingFile_throwsIOException(@TempDir Path tmp) {
		Path missing = tmp.resolve("missing.csv");

		assertThatThrownBy(() -> reader.readResults(missing, "run-x", 60L, APP_ID)).isInstanceOf(IOException.class)
			.hasMessageContaining("not found");
	}

	@Test
	void readResults_emptyCsv_throwsIOException(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("empty.csv");
		Files.writeString(csv, "");

		assertThatThrownBy(() -> reader.readResults(csv, "run-empty", 60L, APP_ID)).isInstanceOf(IOException.class)
			.hasMessageContaining("no valid power samples");
	}

	@Test
	void readResults_headerOnlyCsv_throwsIOException(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("header-only.csv");
		Files.writeString(csv, "timestamp,cpu_power,gpu_power,total_power,cpu_usage,pid_or_app_power\n");

		assertThatThrownBy(() -> reader.readResults(csv, "run-hdr", 60L, APP_ID)).isInstanceOf(IOException.class)
			.hasMessageContaining("no valid power samples");
	}

	@Test
	void readResults_appEnergyIsZero_onlySystemCpuRecorded(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("joularcore.csv");
		// pid_or_app_power = 0 means process was not monitored or not found
		Files.writeString(csv, "1700000001,40.0,0.0,40.0,18.0,0.0\n");

		EnergyReport report = reader.readResults(csv, "run-no-app", 1L, APP_ID);

		// Only system CPU entry should be present (app entry skipped when 0)
		assertThat(report.measurements()).hasSize(1);
		assertThat(report.measurements().get(0).methodName()).contains("total cpu");
	}

	@Test
	void readResults_gpuPowerIncluded(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("joularcore-gpu.csv");
		Files.writeString(csv, """
				timestamp,cpu_power,gpu_power,total_power,cpu_usage,pid_or_app_power
				1700000001,30.0,20.0,50.0,15.0,8.0
				""");

		EnergyReport report = reader.readResults(csv, "run-gpu", 1L, APP_ID);

		// Should have app energy + system CPU energy
		assertThat(report.measurements()).hasSize(2);
	}

	@Test
	void resolveComparisonEnergy_returnsAppEnergyWhenPresent(@TempDir Path tmp) throws IOException {
		Path csv = tmp.resolve("joularcore.csv");
		Files.writeString(csv, """
				timestamp,cpu_power,gpu_power,total_power,cpu_usage,pid_or_app_power
				1700000001,50.0,0.0,50.0,20.0,15.0
				1700000002,52.0,0.0,52.0,22.0,16.0
				""");

		EnergyReport report = reader.readResults(csv, "run", 2L, APP_ID);
		double comparisonEnergy = reader.resolveComparisonEnergy(report, APP_ID);

		// App energy = 15.0 + 16.0 = 31.0 J
		assertThat(comparisonEnergy).isCloseTo(31.0, org.assertj.core.data.Offset.offset(0.01));
	}

	@Test
	void parseHeader_legacyFormat_mapsCorrectly() {
		JoularCoreResultReader.ColumnMapping mapping = reader
			.parseHeader("timestamp,cpu_power,gpu_power,total_power,cpu_usage,pid_or_app_power");

		assertThat(mapping.cpuPower()).isEqualTo(1);
		assertThat(mapping.gpuPower()).isEqualTo(2);
		assertThat(mapping.totalPower()).isEqualTo(3);
		assertThat(mapping.pidOrAppPower()).isEqualTo(5);
	}

	@Test
	void parseHeader_joularCoreV0Format_mapsCorrectly() {
		JoularCoreResultReader.ColumnMapping mapping = reader
			.parseHeader("Timestamp,Total Power (W), CPU Power (W),GPU Power (W),CPU Usage (%),Process Power (W)");

		// Total Power is at column 1, CPU Power at column 2, GPU Power at 3, Process
		// Power at 5
		assertThat(mapping.totalPower()).isEqualTo(1);
		assertThat(mapping.cpuPower()).isEqualTo(2);
		assertThat(mapping.gpuPower()).isEqualTo(3);
		assertThat(mapping.pidOrAppPower()).isEqualTo(5);
	}

}
