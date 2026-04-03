package com.patbaumgartner.greener.core.downloader;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JoularCoreDownloaderTest {

	@Test
	void resolveAssetName_containsPlatformAndArch() {
		String asset = JoularCoreDownloader.resolveAssetName();

		assertThat(asset).startsWith("joularcore-");
		// Should contain one of: linux, macos, windows
		assertThat(asset).containsAnyOf("linux", "macos", "windows");
		// Should contain architecture
		assertThat(asset).containsAnyOf("x86_64", "aarch64");
	}

	@Test
	void defaultCacheDir_isUnderUserHome() {
		Path cacheDir = JoularCoreDownloader.defaultCacheDir();

		assertThat(cacheDir.toString()).contains(".greener");
		assertThat(cacheDir.toString()).contains("joularcore");
	}

}
