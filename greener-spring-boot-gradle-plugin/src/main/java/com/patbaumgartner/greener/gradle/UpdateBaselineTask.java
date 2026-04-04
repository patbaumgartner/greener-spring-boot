package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Promotes the most recent energy measurement as the new baseline.
 *
 * <p>
 * Run this task on the main/release branch after confirming the current
 * energy consumption is acceptable:
 * 
 * <pre>
 * ./gradlew updateEnergyBaseline
 * </pre>
 * 
 * The updated {@code energy-baseline.json} should then be committed to source
 * control so that PR builds can compare against it.
 */
@DisableCachingByDefault(because = "Baseline update depends on external measurement state")
public abstract class UpdateBaselineTask extends DefaultTask {

    /**
     * Path to the JSON baseline file to update.
     * 
     * @return the baseline file property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getBaselineFile();

    /**
     * Path to the latest energy report JSON produced by {@code measureEnergy}.
     * When set, the report at this path is loaded and saved as the new baseline.
     * 
     * @return the latest report file property
     */
    @InputFile
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @org.gradle.api.tasks.Optional
    public abstract RegularFileProperty getLatestReportFile();

    /**
     * Report output directory where the {@code measureEnergy} task writes its
     * results. Used to locate {@code latest-energy-report.json} when
     * {@link #getLatestReportFile()} is not set.
     * 
     * @return the report output directory property
     */
    @Internal
    public abstract DirectoryProperty getReportOutputDir();

    /**
     * Git commit SHA to record in the baseline.
     * 
     * @return the commit SHA property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getCommitSha();

    /**
     * Branch name to record in the baseline.
     * 
     * @return the branch property
     */
    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getBranch();

    /**
     * Promotes the most recent measurement as the new energy baseline.
     * 
     * @throws Exception if the baseline cannot be loaded or saved
     */
    @TaskAction
    public void updateBaseline() throws Exception {
        File baselineFileValue = getBaselineFile().isPresent()
                ? getBaselineFile().get().getAsFile()
                : new File(getProject().getProjectDir(), "energy-baseline.json");

        BaselineManager manager = new BaselineManager();
        EnergyReport report;

        // 1. Try explicit latest report file
        if (getLatestReportFile().isPresent() && getLatestReportFile().get().getAsFile().exists()) {
            Optional<EnergyBaseline> latest = manager.loadBaseline(getLatestReportFile().get().getAsFile().toPath());
            if (latest.isEmpty()) {
                throw new GradleException(
                        "Could not read energy report from: " + getLatestReportFile().get().getAsFile());
            }
            report = latest.get().report();
        }
        // 2. Try auto-detected latest report from report output dir subdirectories
        else {
            Path reportDir = resolveReportDir();
            Path discovered = discoverLatestReport(reportDir);
            if (discovered != null) {
                Optional<EnergyBaseline> latest = manager.loadBaseline(discovered);
                if (latest.isEmpty()) {
                    throw new GradleException("Could not read energy report from: " + discovered);
                }
                report = latest.get().report();
            }
            // 3. Fall back to existing baseline (re-save with updated metadata)
            else {
                Optional<EnergyBaseline> existing = manager.loadBaseline(baselineFileValue.toPath());
                if (existing.isEmpty()) {
                    throw new GradleException(
                            "No energy report found. Run 'measureEnergy' first to generate a measurement, "
                                    + "or set latestReportFile explicitly.");
                }
                report = existing.get().report();
            }
        }

        String sha = PluginDefaults.normalise(getCommitSha().getOrNull());
        String branch = PluginDefaults.normalise(getBranch().getOrNull());

        // Fallback to environment variables
        if (sha == null)
            sha = PluginDefaults.normalise(System.getenv("GITHUB_SHA"));
        if (branch == null)
            branch = PluginDefaults.normalise(System.getenv("GITHUB_REF_NAME"));

        manager.saveBaseline(report, sha, branch, baselineFileValue.toPath());

        for (String line : PluginDefaults.formatBaselineUpdateSummary(baselineFileValue.toPath(), sha, branch,
                report.totalEnergyJoules())) {
            getLogger().lifecycle(line);
        }
    }

    private Path resolveReportDir() {
        if (getReportOutputDir().isPresent()) {
            return getReportOutputDir().get().getAsFile().toPath();
        }
        return getProject().getLayout().getBuildDirectory().getAsFile().get().toPath().resolve("greener-reports");
    }

    /**
     * Scans immediate subdirectories of the given directory for
     * {@code latest-energy-report.json} and returns the most recently modified match.
     */
    private Path discoverLatestReport(Path reportDir) throws IOException {
        if (reportDir == null || !Files.isDirectory(reportDir)) {
            return null;
        }
        Path best = null;
        long bestModified = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportDir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path candidate = child.resolve("latest-energy-report.json");
                if (Files.exists(candidate)) {
                    long modified = Files.getLastModifiedTime(candidate).toMillis();
                    if (modified > bestModified) {
                        best = candidate;
                        bestModified = modified;
                    }
                }
            }
        }
        return best;
    }

}
