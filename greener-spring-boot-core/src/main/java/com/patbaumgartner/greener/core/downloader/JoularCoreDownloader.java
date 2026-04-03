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
import java.time.Duration;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Downloads a specific version of
 * <a href="https://www.noureddine.org/research/joular/joularcore">Joular Core</a>
 * from GitHub Releases and caches it locally.
 *
 * <h2>Release URL pattern</h2>
 * <pre>
 * https://github.com/joular/joularcore/releases/download/v{VERSION}/joularcore{-PLATFORM}
 * </pre>
 *
 * <h2>Platform detection</h2>
 * The downloader inspects {@code os.name} and {@code os.arch} to choose the correct
 * binary asset.  Supported targets:
 * <ul>
 *   <li>Linux x86_64  — {@code joularcore-linux-x86_64}</li>
 *   <li>Linux aarch64 — {@code joularcore-linux-aarch64}</li>
 *   <li>macOS x86_64  — {@code joularcore-macos-x86_64}</li>
 *   <li>macOS aarch64 (Apple Silicon) — {@code joularcore-macos-aarch64}</li>
 *   <li>Windows x86_64 — {@code joularcore-windows-x86_64.exe}</li>
 * </ul>
 */
public class JoularCoreDownloader {

    private static final Logger LOG = Logger.getLogger(JoularCoreDownloader.class.getName());

    private static final String RELEASE_URL_TEMPLATE =
            "https://github.com/joular/joularcore/releases/download/v%s/%s";

    private final HttpClient httpClient;

    public JoularCoreDownloader() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Ensures the Joular Core binary for {@code version} is available in
     * {@code cacheDir} and returns the path to it.
     *
     * <p>If the binary already exists in the cache it is returned immediately.
     *
     * @param version  the Joular Core release version, e.g. {@code "0.0.1-alpha-11"}
     * @param cacheDir directory where the binary is cached
     * @return path to the executable Joular Core binary
     */
    public Path download(String version, Path cacheDir)
            throws IOException, InterruptedException {

        Files.createDirectories(cacheDir);

        String assetName = resolveAssetName();
        Path binaryFile = cacheDir.resolve(assetName);

        if (Files.exists(binaryFile)) {
            LOG.info("Using cached Joular Core from: " + binaryFile);
            ensureExecutable(binaryFile);
            return binaryFile;
        }

        String url = String.format(RELEASE_URL_TEMPLATE, version, assetName);
        LOG.info("Downloading Joular Core " + version + " from: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download Joular Core from " + url
                    + " — HTTP " + response.statusCode()
                    + ". Check that version " + version + " exists at "
                    + "https://github.com/joular/joularcore/releases");
        }

        Path tmpFile = cacheDir.resolve(assetName + ".tmp");
        try (InputStream in = response.body()) {
            Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tmpFile, binaryFile, StandardCopyOption.REPLACE_EXISTING);
        ensureExecutable(binaryFile);

        LOG.info("Joular Core " + version + " downloaded to: " + binaryFile);
        return binaryFile;
    }

    /**
     * Resolves the asset filename for the current OS / architecture.
     *
     * @throws UnsupportedOperationException if the platform is not recognised
     */
    public static String resolveAssetName() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String platform;
        String ext = "";

        if (os.contains("linux")) {
            platform = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "macos";
        } else if (os.contains("win")) {
            platform = "windows";
            ext = ".exe";
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported OS for auto-download: " + os
                    + ". Please download Joular Core manually from "
                    + "https://github.com/joular/joularcore/releases and set joularCoreBinaryPath.");
        }

        String archSuffix;
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archSuffix = "aarch64";
        } else if (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x86-64")) {
            archSuffix = "x86_64";
        } else {
            archSuffix = "x86_64"; // best-effort fallback
            LOG.warning("Unrecognised architecture '" + arch + "' — assuming x86_64");
        }

        return "joularcore-" + platform + "-" + archSuffix + ext;
    }

    /** Returns the default local cache directory: {@code ~/.greener/cache/joularcore/}. */
    public static Path defaultCacheDir() {
        return Path.of(System.getProperty("user.home"), ".greener", "cache", "joularcore");
    }

    private void ensureExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows does not support POSIX permissions — executable flag not needed
        }
    }
}
