package com.patbaumgartner.greener.gradle;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.config.PluginDefaults;
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
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.provider.ProviderFactory;
import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Promotes the most recent energy measurement as the new baseline.
 *
 * <p>
 * Run this task on the main/release branch after confirming the current energy
 * consumption is acceptable:
 *
 * <pre>
 * ./gradlew updateEnergyBaseline
 * </pre>
 *
 * The updated {@code energy-baseline.json} should then be committed to source control so
 * that PR builds can compare against it.
 */
@DisableCachingByDefault(because = "Baseline update depends on external measurement state")
public abstract class UpdateBaselineTask extends DefaultTask {

	/**
	 * Gets the project layout.
	 * @return the project layout
	 */
	@Inject
	protected abstract ProjectLayout getLayout();

	/**
	 * Gets the provider factory.
	 * @return the provider factory
	 */
	@Inject
	protected abstract ProviderFactory getProviders();

	/**
	 * Path to the JSON baseline file to update.
	 * @return the baseline file property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getBaselineFile();

	/**
	 * Path to the latest energy report JSON produced by {@code measureEnergy}. When set,
	 * the report at this path is loaded and saved as the new baseline.
	 * @return the latest report file property
	 */
	@InputFile
	@PathSensitive(PathSensitivity.ABSOLUTE)
	@org.gradle.api.tasks.Optional
	public abstract RegularFileProperty getLatestReportFile();

	/**
	 * Report output directory where the {@code measureEnergy} task writes its results.
	 * Used to locate {@code latest-energy-report.json} when
	 * {@link #getLatestReportFile()} is not set.
	 * @return the report output directory property
	 */
	@Internal
	public abstract DirectoryProperty getReportOutputDir();

	/**
	 * Git commit SHA to record in the baseline.
	 * @return the commit SHA property
	 */
	@Input
	@org.gradle.api.tasks.Optional
	public abstract Property<String> getCommitSha();

	/**
	 * Branch name to record in the baseline.
	 * @return the branch property
	 */
	@Input
	@org.gradle.api.tasks.Optional
	public abstract Property<String> getBranch();

	/**
	 * When {@code true}, the task execution is skipped entirely.
	 * @return the skip property
	 */
	@Input
	public abstract Property<Boolean> getSkip();

	/**
	 * Promotes the most recent measurement as the new energy baseline.
	 * @throws IOException if the baseline cannot be loaded or saved
	 */
	@TaskAction
	public void updateBaseline() throws IOException {
		if (getSkip().get()) {
			getLogger().lifecycle("[greener] updateEnergyBaseline skipped (skip=true)");
			return;
		}

		File baselineFileValue = getBaselineFile().isPresent() ? getBaselineFile().get().getAsFile()
				: getLayout().getProjectDirectory().file("energy-baseline.json").getAsFile();

		if (getLatestReportFile().isPresent() && !getLatestReportFile().get().getAsFile().exists()) {
			throw new GradleException(
					"Configured latestReportFile does not exist: " + getLatestReportFile().get().getAsFile());
		}

		BaselineManager manager = new BaselineManager();

		Path latestPath = getLatestReportFile().isPresent() ? getLatestReportFile().get().getAsFile().toPath() : null;
		Path reportDir = resolveReportDir();
		Optional<EnergyReport> report = manager.resolveLatestReport(latestPath, reportDir, baselineFileValue.toPath());

		if (report.isEmpty()) {
			getLogger().warn("No energy report to promote to baseline. "
					+ "Run 'measureEnergy' first, or set latestReportFile explicitly.");
			return;
		}

		PluginDefaults.saveAndLogBaseline(manager, report.get(), getCommitSha().getOrNull(), getBranch().getOrNull(),
				baselineFileValue.toPath(), msg -> getLogger().lifecycle(msg));
	}

	private Path resolveReportDir() {
		if (getReportOutputDir().isPresent()) {
			return getReportOutputDir().get().getAsFile().toPath();
		}
		return getLayout().getBuildDirectory().dir("greener-reports").get().getAsFile().toPath();
	}

}
