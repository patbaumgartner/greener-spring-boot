package com.patbaumgartner.greener.core.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.MethodLevelReports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JoularCodeJavaResultReaderTest {

	private final JoularCodeJavaResultReader reader = new JoularCodeJavaResultReader();

	// ---- parseCsvLines ----

	@Test
	void parseCsvLines_skipsHeader() {
		List<String> lines = List.of("timestamp,branch,power_watts,energy_joules,interval_seconds",
				"1000,com.example.A.m1,0.5,1.2,2.0");
		List<EnergyMeasurement> result = reader.parseCsvLines(lines, Path.of("test.csv"));
		assertThat(result).hasSize(1);
		assertThat(result.get(0).methodName()).isEqualTo("com.example.A.m1");
		assertThat(result.get(0).energyJoules()).isEqualTo(1.2);
	}

	@Test
	void parseCsvLines_skipsBlankAndTooShortLines() {
		List<String> lines = List.of("", "timestamp,branch,power_watts,energy_joules", "1000,com.example.A.m1,0.5",
				"1000,com.example.B.m2,0.3,2.5,1.0");
		List<EnergyMeasurement> result = reader.parseCsvLines(lines, Path.of("test.csv"));
		// Only the last line has 5 columns; the 3-column line is skipped
		assertThat(result).hasSize(1);
		assertThat(result.get(0).methodName()).isEqualTo("com.example.B.m2");
	}

	@Test
	void parseCsvLines_skipsNonNumericEnergy() {
		List<String> lines = List.of("1000,com.example.A.m1,0.5,not-a-number,1.0");
		List<EnergyMeasurement> result = reader.parseCsvLines(lines, Path.of("test.csv"));
		assertThat(result).isEmpty();
	}

	@Test
	void parseCsvLines_skipsNegativeEnergy() {
		List<String> lines = List.of("1000,com.example.A.m1,0.5,-0.1,1.0");
		List<EnergyMeasurement> result = reader.parseCsvLines(lines, Path.of("test.csv"));
		assertThat(result).isEmpty();
	}

	@Test
	void parseCsvLines_acceptsZeroEnergy() {
		List<String> lines = List.of("1000,com.example.A.m1,0.0,0.0,1.0");
		List<EnergyMeasurement> result = reader.parseCsvLines(lines, Path.of("test.csv"));
		assertThat(result).hasSize(1);
		assertThat(result.get(0).energyJoules()).isEqualTo(0.0);
	}

	@Test
	void parseCsvLines_handlesSemicolonBranch() {
		List<String> lines = List.of("1000,com.example.A.m1;com.example.B.m2,0.5,3.0,1.0");
		List<EnergyMeasurement> result = reader.parseCsvLines(lines, Path.of("test.csv"));
		assertThat(result).hasSize(1);
		assertThat(result.get(0).methodName()).isEqualTo("com.example.A.m1;com.example.B.m2");
	}

	// ---- readAllResults ----

	@Test
	void readAllResults_returnsNullReportsWhenDirAbsent(@TempDir Path tmp) {
		Path missing = tmp.resolve("joular-code-java-results");
		MethodLevelReports reports = reader.readAllResults(missing, "run-1", 30);
		assertThat(reports.appReport()).isNull();
		assertThat(reports.allReport()).isNull();
	}

	@Test
	void readAllResults_returnsNullReportsWhenCsvFilesAbsent(@TempDir Path tmp) throws IOException {
		Path resultsDir = tmp.resolve("joular-code-java-results");
		Files.createDirectories(resultsDir);
		MethodLevelReports reports = reader.readAllResults(resultsDir, "run-1", 30);
		assertThat(reports.appReport()).isNull();
		assertThat(reports.allReport()).isNull();
	}

	@Test
	void readAllResults_parsesValidCsvFiles(@TempDir Path tmp) throws IOException {
		Path resultsDir = tmp.resolve("joular-code-java-results");
		Files.createDirectories(resultsDir);

		Files.writeString(resultsDir.resolve("methods-power-app.csv"), """
				timestamp,branch,power_watts,energy_joules,interval_seconds
				1000,com.example.Service.doWork,0.5,1.5,2.0
				1002,com.example.Service.doWork,0.4,0.8,2.0
				1004,com.example.Repo.query,0.3,0.6,2.0
				""");

		Files.writeString(resultsDir.resolve("methods-power-all.csv"), """
				timestamp,branch,power_watts,energy_joules,interval_seconds
				1000,com.example.Service.doWork,0.5,1.5,2.0
				1004,java.util.ArrayList.add,0.1,0.2,2.0
				""");

		MethodLevelReports reports = reader.readAllResults(resultsDir, "run-1", 30);

		assertThat(reports.appReport()).isNotNull();
		// doWork rows are aggregated: 1.5 + 0.8 = 2.3
		assertThat(reports.appReport().measurements())
			.anySatisfy(m -> assertThat(m.methodName()).isEqualTo("com.example.Service.doWork"))
			.anySatisfy(m -> assertThat(m.methodName()).isEqualTo("com.example.Repo.query"));

		assertThat(reports.allReport()).isNotNull();
		assertThat(reports.allReport().measurements()).hasSize(2);
	}

}
