package com.patbaumgartner.greener.maven;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Promotes the energy report produced by the last {@code greener:measure} execution to
 * become the new baseline.
 *
 * <p>
 * Intended to be run manually (or on the main branch in CI) after a run whose energy
 * consumption you accept as the reference:
 *
 * <pre>
 * mvn greener:update-baseline
 * </pre>
 *
 * <p>
 * The baseline JSON file is written to {@link #baselineFile} and should be committed to
 * source control or cached as a CI artifact so that PR builds can compare against it.
 */
@Mojo(name = "update-baseline", defaultPhase = LifecyclePhase.VERIFY, threadSafe = false)
public class UpdateBaselineMojo extends AbstractMojo {

	/**
	 * Path to the JSON baseline file to update. Defaults to
	 * {@code ${project.basedir}/energy-baseline.json}.
	 */
	@Parameter(property = "greener.baselineFile", defaultValue = "${project.basedir}/energy-baseline.json")
	private File baselineFile;

	/**
	 * Path to the latest energy report JSON produced by {@code greener:measure}. Defaults
	 * to {@code ${project.build.directory}/greener-reports/work/}.
	 *
	 * <p>
	 * When set, the report at this path is loaded and saved as the new baseline. When not
	 * set, the mojo reads the most recent report from the default work directory.
	 */
	@Parameter(property = "greener.latestReportFile")
	private File latestReportFile;

	/** Git commit SHA recorded in the baseline (e.g. from {@code GITHUB_SHA}). */
	@Parameter(property = "greener.commitSha", defaultValue = "${env.GITHUB_SHA}")
	private String commitSha;

	/** Branch name recorded in the baseline (e.g. from {@code GITHUB_REF_NAME}). */
	@Parameter(property = "greener.branch", defaultValue = "${env.GITHUB_REF_NAME}")
	private String branch;

	/** When {@code true}, skip execution. */
	@Parameter(property = "greener.skip", defaultValue = "false")
	private boolean skip;

	@Override
	public void execute() throws MojoExecutionException {
		if (skip) {
			getLog().info("greener:update-baseline skipped");
			return;
		}

		BaselineManager manager = new BaselineManager();

		try {
			EnergyReport report;

			if (latestReportFile != null && latestReportFile.exists()) {
				// Load the report produced by the most recent greener:measure run
				Optional<EnergyBaseline> latest = manager.loadBaseline(latestReportFile.toPath());
				if (latest.isEmpty()) {
					getLog().warn("No energy report found at: " + latestReportFile);
					return;
				}
				report = latest.get().report();
			}
			else {
				// Fall back to re-saving an existing baseline with updated metadata
				Optional<EnergyBaseline> existing = manager.loadBaseline(baselineFile.toPath());
				if (existing.isEmpty()) {
					getLog().warn("No energy report to promote to baseline. "
							+ "Run 'mvn greener:measure' first, or set -Dgreener.latestReportFile.");
					return;
				}
				report = existing.get().report();
			}
			manager.saveBaseline(report, PluginDefaults.normalise(commitSha), PluginDefaults.normalise(branch),
					baselineFile.toPath());

			for (String line : PluginDefaults.formatBaselineUpdateSummary(baselineFile.toPath(),
					PluginDefaults.normalise(commitSha), PluginDefaults.normalise(branch),
					report.totalEnergyJoules())) {
				getLog().info(line);
			}

		}
		catch (IOException e) {
			throw new MojoExecutionException("Failed to update energy baseline: " + e.getMessage(), e);
		}
	}

}
