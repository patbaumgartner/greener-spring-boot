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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Downloads a specific version of
 * <a href="https://www.noureddine.org/research/joular/joularcore">Joular Core</a> from
 * GitHub Releases and caches it locally.
 *
 * <h2>Release URL pattern</h2>
 *
 * <pre>
 * https://github.com/joular/joularcore/releases/download/{VERSION}/joularcore{-PLATFORM}
 * </pre>
 *
 * <h2>Platform detection</h2> The downloader inspects {@code os.name} and {@code os.arch}
 * to choose the correct binary asset. Supported targets:
 * <ul>
 * <li>Linux x86_64 — {@code joularcore-linux-x86_64}</li>
 * <li>Linux aarch64 — {@code joularcore-linux-aarch64}</li>
 * <li>macOS x86_64 — {@code joularcore-macos-x86_64}</li>
 * <li>macOS aarch64 (Apple Silicon) — {@code joularcore-macos-aarch64}</li>
 * <li>Windows x86_64 — {@code joularcore-windows-x86_64.exe}</li>
 * </ul>
 */
public class JoularCoreDownloader {

	private static final Logger LOG = Logger.getLogger(JoularCoreDownloader.class.getName());

	private static final String RELEASE_URL_TEMPLATE = "https://github.com/joular/joularcore/releases/download/%s/%s";

	private static final int HTTP_OK = 200;

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
	 * If the binary already exists in the cache it is returned immediately.
	 * @param version the Joular Core release version, e.g. {@code "0.0.1-beta-2"}
	 * @param cacheDir directory where the binary is cached
	 * @return path to the executable Joular Core binary
	 */
	public Path download(String version, Path cacheDir) throws IOException, InterruptedException {

		Files.createDirectories(cacheDir);

		String assetName = resolveAssetName();
		Path binaryFile = cacheDir.resolve(assetName);

		if (Files.exists(binaryFile)) {
			LOG.fine(() -> "Using cached Joular Core from: " + binaryFile);
			ensureExecutable(binaryFile);
			return binaryFile;
		}

		String url = String.format(RELEASE_URL_TEMPLATE, version, assetName);
		LOG.fine(() -> "Downloading Joular Core " + version + " from: " + url);

		Path tmpFile = cacheDir.resolve(assetName + ".tmp");
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

				if (response.statusCode() != HTTP_OK) {
					String hint = response.statusCode() == 404 ? " The release tag may exist without a prebuilt "
							+ assetName
							+ " asset for your platform — pin a version that does (e.g. <joularCoreVersion>0.0.1-beta-1</joularCoreVersion>) "
							+ "or build Joular Core from source (`cargo build --release`) and point the plugin at the "
							+ "binary via <joularCoreBinaryPath>." : "";
					throw new IOException("Failed to download Joular Core from " + url + " — HTTP "
							+ response.statusCode() + ". Check that version " + version + " exists at "
							+ "https://github.com/joular/joularcore/releases" + "." + hint);
				}

				try (InputStream in = response.body()) {
					Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
				}
				Files.move(tmpFile, binaryFile, StandardCopyOption.REPLACE_EXISTING);
				ensureExecutable(binaryFile);
				verifyChecksum(binaryFile, version, assetName);

				LOG.fine(() -> "Joular Core " + version + " downloaded to: " + binaryFile);
				return binaryFile;
			}
			catch (IOException ex) {
				lastException = ex;
				Files.deleteIfExists(tmpFile);
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
	 * Resolves the asset filename for the current OS / architecture.
	 * @throws UnsupportedOperationException if the platform is not recognised
	 */
	public static String resolveAssetName() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ENGLISH);

		String platform;
		String ext = "";

		if (os.contains("linux")) {
			platform = "linux";
		}
		else if (os.contains("mac") || os.contains("darwin")) {
			platform = "macos";
		}
		else if (os.contains("win")) {
			platform = "windows";
			ext = ".exe";
		}
		else {
			throw new UnsupportedOperationException(
					"Unsupported OS for auto-download: " + os + ". Please download Joular Core manually from "
							+ "https://github.com/joular/joularcore/releases and set joularCoreBinaryPath.");
		}

		String archSuffix;
		if (arch.contains("aarch64") || arch.contains("arm64")) {
			archSuffix = "aarch64";
		}
		else if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x86-64")) {
			archSuffix = "x86_64";
		}
		else {
			throw new UnsupportedOperationException("Unsupported CPU architecture for auto-download: " + arch
					+ ". Please download Joular Core manually from "
					+ "https://github.com/joular/joularcore/releases and set joularCoreBinaryPath.");
		}

		return "joularcore-" + platform + "-" + archSuffix + ext;
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
