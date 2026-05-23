package com.patbaumgartner.greener.core.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a specific version of <a href="https://github.com/joular/joularcore">Joular
 * Core</a> from GitHub Releases and caches it locally.
 *
 * <h2>Asset naming evolution</h2>
 * <ul>
 * <li>Up to {@code 0.0.1-beta-3}: bare binary
 * {@code joularcore-{platform}-{arch}[.exe]}</li>
 * <li>From {@code 0.0.1-beta-4}: zip archive {@code binaries-{platform}-{arch}.zip}
 * containing {@code joularcore[.exe]} and {@code joularcoregui[.exe]}</li>
 * </ul>
 * The downloader tries the zip format first and falls back to the bare binary format for
 * older releases.
 *
 * <h2>Platform detection</h2> The downloader inspects {@code os.name} and {@code os.arch}
 * to choose the correct binary asset. Supported targets:
 * <ul>
 * <li>Linux x86_64</li>
 * <li>Linux aarch64</li>
 * <li>macOS x86_64</li>
 * <li>macOS aarch64 (Apple Silicon)</li>
 * <li>Windows x86_64</li>
 * </ul>
 */
public class JoularCoreDownloader {

	private static final Logger LOG = Logger.getLogger(JoularCoreDownloader.class.getName());

	private static final String RELEASE_URL_TEMPLATE = "https://github.com/joular/joularcore/releases/download/%s/%s";

	private static final int HTTP_OK = 200;

	private static final int HTTP_NOT_FOUND = 404;

	private static final int MAX_RETRIES = 3;

	private final HttpClient httpClient;

	public JoularCoreDownloader() {
		this.httpClient = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.connectTimeout(Duration.ofSeconds(30))
			.build();
	}

	/**
	 * Ensures the Joular Core binary for {@code version} is available in {@code cacheDir}
	 * and returns the path to it.
	 *
	 * <p>
	 * If the binary already exists in the cache it is returned immediately. The
	 * downloader tries the zip asset format ({@code binaries-{platform}-{arch}.zip})
	 * introduced in {@code 0.0.1-beta-4} first, then falls back to the bare binary format
	 * used by older releases.
	 * @param version the Joular Core release version, e.g. {@code "0.0.1-beta-4"}
	 * @param cacheDir directory where the binary is cached
	 * @return path to the executable Joular Core binary
	 */
	public Path download(String version, Path cacheDir) throws IOException, InterruptedException {

		Files.createDirectories(cacheDir);

		String binaryName = resolveAssetName();
		Path binaryFile = cacheDir.resolve(binaryName);

		if (Files.exists(binaryFile)) {
			LOG.fine(() -> "Using cached Joular Core from: " + binaryFile);
			ensureExecutable(binaryFile);
			return binaryFile;
		}

		// Try zip format first (0.0.1-beta-4+), fall back to bare binary (older
		// releases)
		String zipAssetName = resolveZipAssetName();
		try {
			return downloadZipAndExtract(version, zipAssetName, binaryName, binaryFile, cacheDir);
		}
		catch (AssetNotFoundException ex) {
			LOG.fine(() -> "Zip asset " + zipAssetName + " not found for version " + version + ", trying bare binary");
		}

		try {
			return downloadBinary(version, binaryName, binaryFile, cacheDir);
		}
		catch (AssetNotFoundException ex) {
			throw new IOException("Failed to download Joular Core " + version
					+ ": no matching asset found. Tried zip format (" + zipAssetName + ") and bare binary format ("
					+ binaryName + ")." + " Check https://github.com/joular/joularcore/releases", ex);
		}
	}

	private Path downloadZipAndExtract(String version, String zipAssetName, String binaryName, Path binaryFile,
			Path cacheDir) throws IOException, InterruptedException {
		Path tmpZip = cacheDir.resolve(zipAssetName + ".tmp");
		try {
			downloadAsset(version, zipAssetName, tmpZip);
			verifyChecksum(tmpZip, version, zipAssetName);
			String binaryBaseName = binaryName.endsWith(".exe") ? "joularcore.exe" : "joularcore";
			extractBinaryFromZip(tmpZip, binaryBaseName, binaryFile);
			ensureExecutable(binaryFile);
			LOG.info(() -> "Joular Core " + version + " downloaded and extracted to: " + binaryFile);
			return binaryFile;
		}
		catch (IOException | InterruptedException ex) {
			Files.deleteIfExists(binaryFile);
			throw ex;
		}
		finally {
			Files.deleteIfExists(tmpZip);
		}
	}

	private Path downloadBinary(String version, String assetName, Path binaryFile, Path cacheDir)
			throws IOException, InterruptedException {
		Path tmpFile = cacheDir.resolve(assetName + ".tmp");
		try {
			downloadAsset(version, assetName, tmpFile);
			Files.move(tmpFile, binaryFile, StandardCopyOption.REPLACE_EXISTING);
			ensureExecutable(binaryFile);
			verifyChecksum(binaryFile, version, assetName);
			LOG.fine(() -> "Joular Core " + version + " downloaded to: " + binaryFile);
			return binaryFile;
		}
		finally {
			Files.deleteIfExists(tmpFile);
		}
	}

	private void downloadAsset(String version, String assetName, Path target) throws IOException, InterruptedException {
		String url = String.format(RELEASE_URL_TEMPLATE, version, assetName);
		LOG.fine(() -> "Downloading " + assetName + " from: " + url);

		IOException lastException = null;
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(Duration.ofMinutes(5))
					.GET()
					.build();

				HttpResponse<InputStream> response = httpClient.send(request,
						HttpResponse.BodyHandlers.ofInputStream());

				if (response.statusCode() == HTTP_NOT_FOUND) {
					throw new AssetNotFoundException("Asset not found: " + url + " — HTTP 404");
				}
				if (response.statusCode() != HTTP_OK) {
					throw new IOException("Failed to download " + url + " — HTTP " + response.statusCode());
				}

				try (InputStream in = response.body()) {
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				}
				return;
			}
			catch (AssetNotFoundException ex) {
				throw ex; // Never retry 404s
			}
			catch (IOException ex) {
				lastException = ex;
				Files.deleteIfExists(target);
				if (attempt < MAX_RETRIES) {
					long delay = (long) Math.pow(2, attempt);
					final int att = attempt;
					LOG.warning(() -> "Download attempt " + att + "/" + MAX_RETRIES + " failed: " + ex.getMessage()
							+ " — retrying in " + delay + " s");
					Thread.sleep(delay * 1_000);
				}
			}
		}
		throw lastException;
	}

	/**
	 * Extracts the {@code joularcore[.exe]} binary from a zip archive downloaded from a
	 * GitHub Release. The binary entry is matched by filename regardless of path prefix.
	 */
	void extractBinaryFromZip(Path zipFile, String binaryBaseName, Path targetFile) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
			ZipEntry entry = zis.getNextEntry();
			while (entry != null) {
				String entryFilename = Path.of(entry.getName()).getFileName().toString();
				if (entryFilename.equalsIgnoreCase(binaryBaseName)) {
					Path tmpFile = targetFile.getParent().resolve(binaryBaseName + ".tmp");
					try {
						Files.copy(zis, tmpFile, StandardCopyOption.REPLACE_EXISTING);
						Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
						return;
					}
					finally {
						Files.deleteIfExists(tmpFile);
					}
				}
				zis.closeEntry();
				entry = zis.getNextEntry();
			}
		}
		throw new IOException("Binary '" + binaryBaseName + "' not found in zip: " + zipFile.getFileName());
	}

	/**
	 * Marker exception for HTTP 404 — never retried, used to trigger format fallback.
	 */
	private static final class AssetNotFoundException extends IOException {

		@java.io.Serial
		private static final long serialVersionUID = 1L;

		AssetNotFoundException(String message) {
			super(message);
		}

	}

	/**
	 * Resolves the bare-binary asset filename for the current OS / architecture. This
	 * name is used as the cache key (e.g. {@code joularcore-linux-x86_64}).
	 * @throws UnsupportedOperationException if the platform is not recognised
	 */
	public static String resolveAssetName() {
		String platform = resolvePlatform();
		String archSuffix = resolveArchSuffix();
		String ext = "windows".equals(platform) ? ".exe" : "";
		return "joularcore-" + platform + "-" + archSuffix + ext;
	}

	/**
	 * Resolves the zip asset name for the new release format ({@code 0.0.1-beta-4+}).
	 * Returns e.g. {@code binaries-linux-x86_64.zip}.
	 * @throws UnsupportedOperationException if the platform is not recognised
	 */
	public static String resolveZipAssetName() {
		return "binaries-" + resolvePlatform() + "-" + resolveArchSuffix() + ".zip";
	}

	private static String resolvePlatform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
		if (os.contains("linux")) {
			return "linux";
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return "macos";
		}
		if (os.contains("win")) {
			return "windows";
		}
		throw new UnsupportedOperationException(
				"Unsupported OS for auto-download: " + os + ". Please download Joular Core manually from "
						+ "https://github.com/joular/joularcore/releases and set joularCoreBinaryPath.");
	}

	private static String resolveArchSuffix() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ENGLISH);
		if (arch.contains("aarch64") || arch.contains("arm64")) {
			return "aarch64";
		}
		if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x86-64")) {
			return "x86_64";
		}
		throw new UnsupportedOperationException("Unsupported CPU architecture for auto-download: " + arch
				+ ". Please download Joular Core manually from "
				+ "https://github.com/joular/joularcore/releases and set joularCoreBinaryPath.");
	}

	/**
	 * Returns the default local cache directory: {@code ~/.greener/cache/joularcore/}.
	 */
	public static Path defaultCacheDir() {
		return Path.of(System.getProperty("user.home"), ".greener", "cache", "joularcore");
	}

	private void ensureExecutable(Path file) {
		try {
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
			perms.add(PosixFilePermission.OWNER_EXECUTE);
			perms.add(PosixFilePermission.GROUP_EXECUTE);
			Files.setPosixFilePermissions(file, perms);
		}
		catch (UnsupportedOperationException | IOException ignored) {
			// Windows does not support POSIX permissions — executable flag not needed
		}
	}

	/**
	 * Verifies the SHA-256 checksum of a downloaded binary against the digest published
	 * in the GitHub Release API. If the API is unreachable or the asset has no digest, a
	 * warning is logged but the download is not rejected.
	 */
	private void verifyChecksum(Path file, String version, String assetName) throws IOException {
		String apiUrl = String.format("https://api.github.com/repos/joular/joularcore/releases/tags/%s", version);
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiUrl))
				.header("Accept", "application/vnd.github+json")
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != HTTP_OK) {
				Files.delete(file);
				throw new IOException("SHA-256 verification failed: GitHub API returned HTTP " + response.statusCode()
						+ " for release " + version + ". Cannot verify integrity of downloaded binary.");
			}

			String expectedSha = extractDigestForAsset(response.body(), assetName);
			if (expectedSha == null) {
				LOG.fine(() -> "No SHA-256 digest found for asset " + assetName + " in release " + version);
				return;
			}

			String actualSha = computeSha256(file);
			if (!actualSha.equalsIgnoreCase(expectedSha)) {
				Files.delete(file);
				throw new IOException("SHA-256 checksum mismatch for " + assetName + ": expected " + expectedSha
						+ " but got " + actualSha);
			}
			LOG.info(() -> "SHA-256 verified for " + assetName + ": " + actualSha);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			Files.delete(file);
			throw new IOException("SHA-256 verification interrupted for " + assetName, ex);
		}
	}

	/**
	 * Extracts the SHA-256 digest for a named asset from a GitHub Release API JSON
	 * response. Uses simple string scanning to avoid a full JSON parser dependency.
	 * @return the hex digest, or {@code null} if not found
	 */
	static String extractDigestForAsset(String json, String assetName) {
		// Find the asset block by name
		int nameIdx = json.indexOf("\"name\": \"" + assetName + "\"");
		if (nameIdx < 0) {
			nameIdx = json.indexOf("\"name\":\"" + assetName + "\"");
		}
		if (nameIdx < 0) {
			return null;
		}
		// Search forward for the digest field within the same asset object
		String digestPrefix = "\"sha256:";
		int digestIdx = json.indexOf(digestPrefix, nameIdx);
		if (digestIdx < 0) {
			return null;
		}
		// Ensure we haven't crossed into the next asset (look for next "name":)
		int nextAssetIdx = json.indexOf("\"name\":", nameIdx + 1);
		if (nextAssetIdx > 0 && digestIdx > nextAssetIdx) {
			return null;
		}
		int start = digestIdx + digestPrefix.length();
		int end = json.indexOf("\"", start);
		if (end < 0) {
			return null;
		}
		return json.substring(start, end);
	}

	static String computeSha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] fileBytes = Files.readAllBytes(file);
			byte[] hash = digest.digest(fileBytes);
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IOException("SHA-256 algorithm not available", ex);
		}
	}

}
