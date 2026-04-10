package com.patbaumgartner.greener.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppArgsBuilderTest {

	@Test
	void buildEffectiveAppArgs_null_addsHealthProbe() {
		List<String> result = AppArgsBuilder.buildEffectiveAppArgs(null);

		assertThat(result).containsExactly("--management.endpoint.health.probes.enabled=true");
	}

	@Test
	void buildEffectiveAppArgs_preservesUserArgs() {
		List<String> result = AppArgsBuilder.buildEffectiveAppArgs(List.of("--server.port=9090"));

		assertThat(result).contains("--server.port=9090", "--management.endpoint.health.probes.enabled=true");
	}

	@Test
	void buildEffectiveAppArgs_withShutdownEndpoint() {
		List<String> result = AppArgsBuilder.buildEffectiveAppArgs(null, true);

		assertThat(result).contains("--management.endpoint.shutdown.enabled=true",
				"--management.endpoints.web.exposure.include=health,shutdown");
	}

	@Test
	void buildEffectiveAppArgs_injectsPortFromBaseUrl() {
		List<String> result = AppArgsBuilder.buildEffectiveAppArgs(null, false, "http://localhost:9123");

		assertThat(result).contains("--server.port=9123");
	}

	@Test
	void buildEffectiveAppArgs_doesNotInjectDefaultPort() {
		List<String> result = AppArgsBuilder.buildEffectiveAppArgs(null, false, "http://localhost:8080");

		assertThat(result).noneMatch(a -> a.startsWith("--server.port="));
	}

	@Test
	void buildEffectiveAppArgs_doesNotOverrideExistingPort() {
		List<String> result = AppArgsBuilder.buildEffectiveAppArgs(List.of("--server.port=7777"), false,
				"http://localhost:9123");

		assertThat(result).contains("--server.port=7777");
		assertThat(result).doesNotContain("--server.port=9123");
	}

	@Test
	void extractPort_standardUrl() {
		assertThat(AppArgsBuilder.extractPort("http://localhost:9090")).isEqualTo(9090);
	}

	@Test
	void extractPort_noExplicitPort_http_returns80() {
		assertThat(AppArgsBuilder.extractPort("http://localhost")).isEqualTo(80);
	}

	@Test
	void extractPort_noExplicitPort_https_returns443() {
		assertThat(AppArgsBuilder.extractPort("https://localhost")).isEqualTo(443);
	}

	@Test
	void extractPort_invalidUrl_returnsNegative() {
		assertThat(AppArgsBuilder.extractPort("not a url")).isEqualTo(-1);
	}

}
