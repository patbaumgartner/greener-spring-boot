package com.patbaumgartner.greener.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WorkloadStatsTest {

	@Test
	void builtInFactory_setsFieldsCorrectly() {
		WorkloadStats stats = WorkloadStats.builtIn(1000, 5, 60);
		assertThat(stats.tool()).isEqualTo("built-in");
		assertThat(stats.totalRequests()).isEqualTo(1000);
		assertThat(stats.failedRequests()).isEqualTo(5);
		assertThat(stats.durationSeconds()).isEqualTo(60);
		assertThat(stats.hasRequestCounts()).isTrue();
	}

	@Test
	void externalFactory_setsUnknownCounts() {
		WorkloadStats stats = WorkloadStats.external("oha", 60);
		assertThat(stats.tool()).isEqualTo("oha");
		assertThat(stats.totalRequests()).isEqualTo(WorkloadStats.UNKNOWN);
		assertThat(stats.failedRequests()).isEqualTo(WorkloadStats.UNKNOWN);
		assertThat(stats.hasRequestCounts()).isFalse();
	}

	@Test
	void requestsPerSecond_computedCorrectly() {
		WorkloadStats stats = WorkloadStats.builtIn(600, 0, 60);
		assertThat(stats.requestsPerSecond()).isEqualTo(10.0);
	}

	@Test
	void requestsPerSecond_nanWhenUnknown() {
		WorkloadStats stats = WorkloadStats.external("wrk", 60);
		assertThat(stats.requestsPerSecond()).isNaN();
	}

	@Test
	void requestsPerSecond_nanWhenDurationZero() {
		WorkloadStats stats = WorkloadStats.builtIn(100, 0, 0);
		assertThat(stats.requestsPerSecond()).isNaN();
	}

	@Test
	void energyPerRequestMillijoules_computedCorrectly() {
		// 1000 successful requests, 60 J total → 60 mJ each
		WorkloadStats stats = WorkloadStats.builtIn(1000, 0, 60);
		assertThat(stats.energyPerRequestMillijoules(60.0)).isEqualTo(60.0);
	}

	@Test
	void energyPerRequestMillijoules_subtractsFailed() {
		// 1000 total, 100 failed → 900 successful; 90 J → 100 mJ each
		WorkloadStats stats = WorkloadStats.builtIn(1000, 100, 60);
		assertThat(stats.energyPerRequestMillijoules(90.0)).isCloseTo(100.0, within(0.001));
	}

	@Test
	void energyPerRequestMillijoules_nanWhenUnknown() {
		WorkloadStats stats = WorkloadStats.external("gatling", 60);
		assertThat(stats.energyPerRequestMillijoules(100.0)).isNaN();
	}

	@Test
	void energyPerRequestMillijoules_nanWhenAllFailed() {
		WorkloadStats stats = WorkloadStats.builtIn(100, 100, 60);
		assertThat(stats.energyPerRequestMillijoules(100.0)).isNaN();
	}

	@Test
	void failureRatePercent_computedCorrectly() {
		WorkloadStats stats = WorkloadStats.builtIn(200, 10, 60);
		assertThat(stats.failureRatePercent()).isCloseTo(5.0, within(0.001));
	}

	@Test
	void failureRatePercent_nanWhenUnknown() {
		WorkloadStats stats = WorkloadStats.external("wrk2", 60);
		assertThat(stats.failureRatePercent()).isNaN();
	}

	@Test
	void failureRatePercent_zeroWhenNoFailures() {
		WorkloadStats stats = WorkloadStats.builtIn(500, 0, 60);
		assertThat(stats.failureRatePercent()).isEqualTo(0.0);
	}

	@Test
	void constructor_rejectsBlankTool() {
		assertThatIllegalArgumentException().isThrownBy(() -> new WorkloadStats("", 100, 0, 60));
	}

	@Test
	void constructor_rejectsNegativeDuration() {
		assertThatIllegalArgumentException().isThrownBy(() -> new WorkloadStats("built-in", 100, 0, -1));
	}

	@Test
	void throughputUnit_reqsForHttpTools() {
		assertThat(WorkloadStats.builtIn(100, 0, 60).throughputUnit()).isEqualTo("req/s");
		assertThat(WorkloadStats.external("oha", 60).throughputUnit()).isEqualTo("req/s");
		assertThat(WorkloadStats.external("wrk", 60).throughputUnit()).isEqualTo("req/s");
		assertThat(WorkloadStats.external("wrk2", 60).throughputUnit()).isEqualTo("req/s");
		assertThat(WorkloadStats.external("bombardier", 60).throughputUnit()).isEqualTo("req/s");
		assertThat(WorkloadStats.external("ab", 60).throughputUnit()).isEqualTo("req/s");
	}

	@Test
	void throughputUnit_scenariosForScenarioTools() {
		assertThat(WorkloadStats.external("gatling", 60).throughputUnit()).isEqualTo("scenarios/s");
		assertThat(WorkloadStats.external("k6", 60).throughputUnit()).isEqualTo("scenarios/s");
		assertThat(WorkloadStats.external("locust", 60).throughputUnit()).isEqualTo("scenarios/s");
	}

}
