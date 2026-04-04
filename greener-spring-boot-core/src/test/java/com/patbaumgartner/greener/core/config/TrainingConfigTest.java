package com.patbaumgartner.greener.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TrainingConfigTest {

	// ---- defaults ----

	@Test
	void defaults_areReasonable() {
		TrainingConfig config = new TrainingConfig();

		assertThat(config.getBaseUrl()).isEqualTo("http://localhost:8080");
		assertThat(config.getRequestsPerSecond()).isEqualTo(5);
		assertThat(config.getWarmupDurationSeconds()).isEqualTo(30);
		assertThat(config.getMeasureDurationSeconds()).isEqualTo(60);
		assertThat(config.getExternalCommand()).isNull();
		assertThat(config.getExternalScriptFile()).isNull();
	}

	@Test
	void totalDurationSeconds_sumOfWarmupAndMeasure() {
		TrainingConfig config = new TrainingConfig().warmupDurationSeconds(10).measureDurationSeconds(20);

		assertThat(config.getTotalDurationSeconds()).isEqualTo(30);
	}

	// ---- builder-style setters ----

	@Test
	void fluentSetters_returnSameInstance() {
		TrainingConfig config = new TrainingConfig();

		assertThat(config.baseUrl("http://example.com")).isSameAs(config);
		assertThat(config.requestsPerSecond(10)).isSameAs(config);
		assertThat(config.warmupDurationSeconds(5)).isSameAs(config);
		assertThat(config.measureDurationSeconds(15)).isSameAs(config);
		assertThat(config.externalCommand("k6 run test.js")).isSameAs(config);
		assertThat(config.externalScriptFile("/tmp/run.sh")).isSameAs(config);

	}

	@Test
	void setters_updateValues() {
		TrainingConfig config = new TrainingConfig().baseUrl("http://example.com:9090")
			.requestsPerSecond(50)
			.warmupDurationSeconds(10)
			.measureDurationSeconds(120)
			.externalCommand("wrk -t4 -c20 -d60s http://localhost:8080/")
			.externalScriptFile("/tmp/run.sh");

		assertThat(config.getBaseUrl()).isEqualTo("http://example.com:9090");
		assertThat(config.getRequestsPerSecond()).isEqualTo(50);
		assertThat(config.getWarmupDurationSeconds()).isEqualTo(10);
		assertThat(config.getMeasureDurationSeconds()).isEqualTo(120);
		assertThat(config.getExternalCommand()).isEqualTo("wrk -t4 -c20 -d60s http://localhost:8080/");
		assertThat(config.getExternalScriptFile()).isEqualTo("/tmp/run.sh");

	}

	// ---- edge cases ----

	@Test
	void zeroWarmup_producesTotalEqualToMeasure() {
		TrainingConfig config = new TrainingConfig().warmupDurationSeconds(0).measureDurationSeconds(60);

		assertThat(config.getTotalDurationSeconds()).isEqualTo(60);
	}

	@Test
	void zeroMeasure_producesTotalEqualToWarmup() {
		TrainingConfig config = new TrainingConfig().warmupDurationSeconds(30).measureDurationSeconds(0);

		assertThat(config.getTotalDurationSeconds()).isEqualTo(30);
	}

	@Test
	void nullExternalCommand_isAllowed() {
		TrainingConfig config = new TrainingConfig().externalCommand(null);

		assertThat(config.getExternalCommand()).isNull();
	}

	@Test
	void nullExternalScriptFile_isAllowed() {
		TrainingConfig config = new TrainingConfig().externalScriptFile(null);

		assertThat(config.getExternalScriptFile()).isNull();
	}

	@Test
	void blankExternalCommand_isStoredAsIs() {
		TrainingConfig config = new TrainingConfig().externalCommand("   ");

		assertThat(config.getExternalCommand()).isEqualTo("   ");
	}

	@Test
	void blankExternalScriptFile_isStoredAsIs() {
		TrainingConfig config = new TrainingConfig().externalScriptFile("   ");

		assertThat(config.getExternalScriptFile()).isEqualTo("   ");
	}

}
