package com.patbaumgartner.greener.maven;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateBaselineMojoTest {

	@TempDir
	Path tempDir;

	@Test
	void updatesBaselineFromLatestReportFile() throws Exception {
		// Arrange: write a latest report file
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app", 42.0)));
		Path latestReport = tempDir.resolve("latest-energy-report.json");
		new BaselineManager().saveBaseline(report, null, null, latestReport);

		Path baselineFile = tempDir.resolve("energy-baseline.json");

		UpdateBaselineMojo mojo = new UpdateBaselineMojo();
		setField(mojo, "latestReportFile", latestReport.toFile());
		setField(mojo, "baselineFile", baselineFile.toFile());
		setField(mojo, "skip", false);

		// Act
		mojo.execute();

		// Assert
		assertThat(baselineFile).exists();
		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().report().totalEnergyJoules()).isEqualTo(42.0);
	}

	@Test
	void updatesBaselineFromExistingBaselineFile() throws Exception {
		// Arrange: write an existing baseline file
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app", 100.0)));
		Path baselineFile = tempDir.resolve("energy-baseline.json");
		new BaselineManager().saveBaseline(report, "abc123", "main", baselineFile);

		UpdateBaselineMojo mojo = new UpdateBaselineMojo();
		setField(mojo, "baselineFile", baselineFile.toFile());
		setField(mojo, "commitSha", "def456");
		setField(mojo, "branch", "release");
		setField(mojo, "skip", false);

		// Act
		mojo.execute();

		// Assert
		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().commitSha()).isEqualTo("def456");
		assertThat(baseline.get().branch()).isEqualTo("release");
	}

	@Test
	void skipsExecutionWhenSkipIsTrue() throws Exception {
		UpdateBaselineMojo mojo = new UpdateBaselineMojo();
		setField(mojo, "skip", true);

		// Should not throw
		mojo.execute();
	}

	@Test
	void warnsWhenNoReportAvailable() throws Exception {
		Path baselineFile = tempDir.resolve("nonexistent-baseline.json");

		UpdateBaselineMojo mojo = new UpdateBaselineMojo();
		setField(mojo, "baselineFile", baselineFile.toFile());
		setField(mojo, "skip", false);

		// Execute should not throw (it logs a warning and returns)
		mojo.execute();

		// Baseline file should not have been created
		assertThat(baselineFile).doesNotExist();
	}

	@Test
	void normalisesBlankStringsToNull() throws Exception {
		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app", 50.0)));
		Path baselineFile = tempDir.resolve("energy-baseline.json");
		new BaselineManager().saveBaseline(report, null, null, baselineFile);

		UpdateBaselineMojo mojo = new UpdateBaselineMojo();
		setField(mojo, "baselineFile", baselineFile.toFile());
		setField(mojo, "commitSha", "   ");
		setField(mojo, "branch", "");
		setField(mojo, "skip", false);

		mojo.execute();

		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().commitSha()).isNull();
		assertThat(baseline.get().branch()).isNull();
	}

	@Test
	void updatesBaselineFromReportOutputDir() throws Exception {
		// Arrange: create a tool subdirectory with a report
		Path reportOutputDir = tempDir.resolve("greener-reports");
		Path toolDir = reportOutputDir.resolve("oha");
		Files.createDirectories(toolDir);

		EnergyReport report = EnergyReport.of("run-1", Instant.now(), 60, List.of(new EnergyMeasurement("app", 77.0)));
		new BaselineManager().saveBaseline(report, null, null, toolDir.resolve("latest-energy-report.json"));

		Path baselineFile = tempDir.resolve("energy-baseline.json");

		UpdateBaselineMojo mojo = new UpdateBaselineMojo();
		setField(mojo, "reportOutputDir", reportOutputDir.toFile());
		setField(mojo, "baselineFile", baselineFile.toFile());
		setField(mojo, "skip", false);

		// Act
		mojo.execute();

		// Assert
		assertThat(baselineFile).exists();
		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().report().totalEnergyJoules()).isEqualTo(77.0);
	}

	@Test
	void prefersLatestReportFileOverReportOutputDir() throws Exception {
		// Arrange: both latestReportFile and reportOutputDir set
		EnergyReport explicitReport = EnergyReport.of("run-explicit", Instant.now(), 60,
				List.of(new EnergyMeasurement("app", 55.0)));
		Path latestReport = tempDir.resolve("explicit-report.json");
		new BaselineManager().saveBaseline(explicitReport, null, null, latestReport);

		Path reportOutputDir = tempDir.resolve("greener-reports");
		Path toolDir = reportOutputDir.resolve("wrk");
		Files.createDirectories(toolDir);
		EnergyReport autoReport = EnergyReport.of("run-auto", Instant.now(), 60,
				List.of(new EnergyMeasurement("app", 99.0)));
		new BaselineManager().saveBaseline(autoReport, null, null, toolDir.resolve("latest-energy-report.json"));

		Path baselineFile = tempDir.resolve("energy-baseline.json");

		UpdateBaselineMojo mojo = new UpdateBaselineMojo();
		setField(mojo, "latestReportFile", latestReport.toFile());
		setField(mojo, "reportOutputDir", reportOutputDir.toFile());
		setField(mojo, "baselineFile", baselineFile.toFile());
		setField(mojo, "skip", false);

		// Act
		mojo.execute();

		// Assert: explicit latestReportFile wins
		var baseline = new BaselineManager().loadBaseline(baselineFile);
		assertThat(baseline).isPresent();
		assertThat(baseline.get().report().totalEnergyJoules()).isEqualTo(55.0);
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

}
