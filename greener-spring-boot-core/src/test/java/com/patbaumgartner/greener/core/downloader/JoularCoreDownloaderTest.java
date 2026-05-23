package com.patbaumgartner.greener.core.downloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	void resolveAssetName_doesNotEndWithExeOnLinux() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String asset = JoularCoreDownloader.resolveAssetName();
		if (os.contains("linux") || os.contains("mac")) {
			assertThat(asset).doesNotEndWith(".exe");
		}
	}

	@Test
	void resolveAssetName_followsNamingConvention() {
		String asset = JoularCoreDownloader.resolveAssetName();
		// Pattern: joularcore-{platform}-{arch}[.exe]
		String withoutExe = asset.replace(".exe", "");
		String[] parts = withoutExe.split("-");
		assertThat(parts).hasSizeGreaterThanOrEqualTo(3);
		assertThat(parts[0]).isEqualTo("joularcore");
	}

	@Test
	void defaultCacheDir_isUnderUserHome() {
		Path cacheDir = JoularCoreDownloader.defaultCacheDir();

		assertThat(cacheDir.toString()).contains(".greener");
		assertThat(cacheDir.toString()).contains("joularcore");
		assertThat(cacheDir.toString()).startsWith(System.getProperty("user.home"));
	}

	@Test
	void defaultCacheDir_hasExpectedStructure() {
		Path cacheDir = JoularCoreDownloader.defaultCacheDir();
		Path expected = Path.of(System.getProperty("user.home"), ".greener", "cache", "joularcore");

		assertThat(cacheDir).isEqualTo(expected);
	}

	@Test
	void download_usesCachedBinary(@TempDir Path tempDir) throws Exception {
		// Pre-create a fake cached binary
		String assetName = JoularCoreDownloader.resolveAssetName();
		Path cachedBinary = tempDir.resolve(assetName);
		Files.writeString(cachedBinary, "fake-binary-content");

		JoularCoreDownloader downloader = new JoularCoreDownloader();
		Path result = downloader.download("0.0.1-beta-4", tempDir);

		assertThat(result).isEqualTo(cachedBinary);
		assertThat(Files.readString(result)).isEqualTo("fake-binary-content");
	}

	@Test
	void download_createsCacheDirectory(@TempDir Path tempDir) throws Exception {
		Path nestedCache = tempDir.resolve("sub").resolve("cache");
		String assetName = JoularCoreDownloader.resolveAssetName();
		// Pre-create the cached binary so no actual download happens
		Files.createDirectories(nestedCache);
		Files.writeString(nestedCache.resolve(assetName), "fake");

		JoularCoreDownloader downloader = new JoularCoreDownloader();
		Path result = downloader.download("0.0.1-beta-4", nestedCache);

		assertThat(result).exists();
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void download_cachedBinary_hasExecutePermission(@TempDir Path tempDir) throws Exception {
		String assetName = JoularCoreDownloader.resolveAssetName();
		Path cachedBinary = tempDir.resolve(assetName);
		Files.writeString(cachedBinary, "fake-binary-content");
		// Remove execute permission first
		Files.setPosixFilePermissions(cachedBinary,
				Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

		JoularCoreDownloader downloader = new JoularCoreDownloader();
		downloader.download("0.0.1-beta-4", tempDir);

		Set<PosixFilePermission> perms = Files.getPosixFilePermissions(cachedBinary);
		assertThat(perms).contains(PosixFilePermission.OWNER_EXECUTE);
	}

	// ---- extractDigestForAsset ----

	@Test
	void extractDigestForAsset_matchingAsset_returnsDigest() {
		String json = """
				{
				  "assets": [
				    { "name": "joularcore-linux-x86_64", "digest": "sha256:abcdef1234567890" },
				    { "name": "joularcore-macos-aarch64", "digest": "sha256:0987654321fedcba" }
				  ]
				}
				""";
		assertThat(JoularCoreDownloader.extractDigestForAsset(json, "joularcore-linux-x86_64"))
			.isEqualTo("abcdef1234567890");
	}

	@Test
	void extractDigestForAsset_secondAsset_returnsCorrectDigest() {
		String json = """
				{
				  "assets": [
				    { "name": "joularcore-linux-x86_64", "digest": "sha256:first" },
				    { "name": "joularcore-macos-aarch64", "digest": "sha256:second" }
				  ]
				}
				""";
		assertThat(JoularCoreDownloader.extractDigestForAsset(json, "joularcore-macos-aarch64")).isEqualTo("second");
	}

	@Test
	void extractDigestForAsset_noMatchingAsset_returnsNull() {
		String json = """
				{ "assets": [ { "name": "joularcore-linux-x86_64", "digest": "sha256:abc" } ] }
				""";
		assertThat(JoularCoreDownloader.extractDigestForAsset(json, "nonexistent")).isNull();
	}

	@Test
	void extractDigestForAsset_noDigestField_returnsNull() {
		String json = """
				{ "assets": [ { "name": "joularcore-linux-x86_64", "size": 12345 } ] }
				""";
		assertThat(JoularCoreDownloader.extractDigestForAsset(json, "joularcore-linux-x86_64")).isNull();
	}

	// ---- computeSha256 ----

	@Test
	void computeSha256_knownContent_matchesExpected(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("test.bin");
		Files.writeString(file, "hello world");

		String sha = JoularCoreDownloader.computeSha256(file);

		// SHA-256 of "hello world"
		assertThat(sha).isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
	}

	@Test
	void computeSha256_emptyFile(@TempDir Path tempDir) throws IOException {
		Path file = tempDir.resolve("empty.bin");
		Files.writeString(file, "");

		String sha = JoularCoreDownloader.computeSha256(file);

		// SHA-256 of empty string
		assertThat(sha).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
	}

	@Test
	void download_nonexistentVersion_throwsIOException(@TempDir Path tempDir) {
		JoularCoreDownloader downloader = new JoularCoreDownloader();

		assertThatThrownBy(() -> downloader.download("99.99.99-nonexistent", tempDir)).isInstanceOf(IOException.class)
			.hasMessageContaining("Failed to download");
	}

	// ---- resolveZipAssetName ----

	@Test
	void resolveZipAssetName_endsWithZip() {
		String asset = JoularCoreDownloader.resolveZipAssetName();

		assertThat(asset).endsWith(".zip");
	}

	@Test
	void resolveZipAssetName_startsWithBinaries() {
		String asset = JoularCoreDownloader.resolveZipAssetName();

		assertThat(asset).startsWith("binaries-");
	}

	@Test
	void resolveZipAssetName_containsPlatformAndArch() {
		String asset = JoularCoreDownloader.resolveZipAssetName();

		assertThat(asset).containsAnyOf("linux", "macos", "windows");
		assertThat(asset).containsAnyOf("x86_64", "aarch64");
	}

	// ---- extractBinaryFromZip ----

	@Test
	void extractBinaryFromZip_extractsMatchingEntry(@TempDir Path tempDir) throws Exception {
		Path zipFile = tempDir.resolve("test.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
			zos.putNextEntry(new ZipEntry("target/release/joularcore"));
			zos.write("fake-binary".getBytes());
			zos.closeEntry();
		}

		Path targetFile = tempDir.resolve("joularcore");
		new JoularCoreDownloader().extractBinaryFromZip(zipFile, "joularcore", targetFile);

		assertThat(Files.readString(targetFile)).isEqualTo("fake-binary");
	}

	@Test
	void extractBinaryFromZip_matchesByFilenameIgnoringPath(@TempDir Path tempDir) throws Exception {
		Path zipFile = tempDir.resolve("test.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
			zos.putNextEntry(new ZipEntry("deep/nested/path/joularcore"));
			zos.write("content".getBytes());
			zos.closeEntry();
		}

		Path targetFile = tempDir.resolve("joularcore");
		new JoularCoreDownloader().extractBinaryFromZip(zipFile, "joularcore", targetFile);

		assertThat(targetFile).exists();
	}

	@Test
	void extractBinaryFromZip_binaryNotFound_throwsIOException(@TempDir Path tempDir) throws Exception {
		Path zipFile = tempDir.resolve("test.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
			zos.putNextEntry(new ZipEntry("joularcoregui"));
			zos.write("gui".getBytes());
			zos.closeEntry();
		}

		Path targetFile = tempDir.resolve("joularcore");
		assertThatThrownBy(() -> new JoularCoreDownloader().extractBinaryFromZip(zipFile, "joularcore", targetFile))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("not found in zip");
	}

}
