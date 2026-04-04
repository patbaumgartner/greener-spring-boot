package com.patbaumgartner.greener.maven;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.model.EnergyReport;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;

final class SharedMojoUtils {

	private SharedMojoUtils() {
		// utility class
	}

	static void saveAndLogBaseline(BaselineManager manager, EnergyReport report, String commitSha, String branch,
			File baselineFile, Log log) throws IOException {
		String sha = PluginDefaults.normalise(commitSha);
		String br = PluginDefaults.normalise(branch);

		manager.saveBaseline(report, sha, br, baselineFile.toPath());

		for (String line : PluginDefaults.formatBaselineUpdateSummary(baselineFile.toPath(), sha, br,
				report.totalEnergyJoules())) {
			log.info(line);
		}
	}

}
