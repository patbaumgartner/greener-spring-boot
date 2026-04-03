package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
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
        Optional<EnergyBaseline> existing = manager.loadBaseline(baselineFileValue.toPath());

        if (existing.isEmpty()) {
            throw new GradleException(
                    "No energy baseline found at: " + baselineFileValue
                            + ". Run 'measureEnergy' first to generate a measurement.");
        }

        EnergyReport report = existing.get().report();
        String sha = normalise(getCommitSha().getOrNull());
        String branch = normalise(getBranch().getOrNull());

        // Fallback to environment variables
        if (sha == null)
            sha = normalise(System.getenv("GITHUB_SHA"));
        if (branch == null)
            branch = normalise(System.getenv("GITHUB_REF_NAME"));

        manager.saveBaseline(report, sha, branch, baselineFileValue.toPath());

        getLogger().lifecycle("Energy baseline updated: " + baselineFileValue);
        getLogger().lifecycle("  commit : " + sha);
        getLogger().lifecycle("  branch : " + branch);
        getLogger().lifecycle("  energy : " + String.format("%.2f J", report.totalEnergyJoules()));
    }

    private String normalise(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
