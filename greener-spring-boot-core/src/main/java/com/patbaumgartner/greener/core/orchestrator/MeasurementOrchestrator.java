package com.patbaumgartner.greener.core.orchestrator;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.baseline.RunEntryStore;
import com.patbaumgartner.greener.core.comparator.EnergyComparator;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.model.AggregatedRunEntry;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import com.patbaumgartner.greener.core.reader.JoularCoreResultReader;
import com.patbaumgartner.greener.core.reporter.ConsoleReporter;
import com.patbaumgartner.greener.core.reporter.HtmlReporter;
import com.patbaumgartner.greener.core.runner.JoularCoreRunner;
import com.patbaumgartner.greener.core.runner.TrainingRunner;
import com.patbaumgartner.greener.core.config.JoularCoreConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared measurement workflow logic used by both the Maven and Gradle plugins.
 *
 * <p>
 * This class extracts the common orchestration steps — warmup, measurement, result
 * processing, baseline comparison, and report generation — so that both plugins delegate
 * to the same implementation.
 *
 * <p>
 * Plugin-specific concerns (logging, exception wrapping, property resolution) remain in
 * the plugin modules. Logging is handled via a {@link Consumer} callback so this class
 * has no dependency on Maven or Gradle APIs.
 */
public class MeasurementOrchestrator {

	private final Consumer<String> logger;

	public MeasurementOrchestrator(Consumer<String> logger) {
		this.logger = logger;
	}

	/**
	 * Executes the warmup and measurement phases.
	 *
	 * <p>
	 * During warmup the Joular Core CSV is cleared and monitoring restarted so that only
	 * the measurement phase contributes energy data.
	 * @param runner the Joular Core runner (already started)
	 * @param config Joular Core configuration (for restarting after warmup)
	 * @param outputCsv path to the Joular Core CSV output
	 * @param configFactory supplier that creates a fresh {@link TrainingConfig} with
	 * baseUrl, RPS, and external script/command already configured
	 * @param warmupSeconds warmup duration (0 to skip)
	 * @param measureSeconds measurement duration
	 * @return workload statistics from the measurement phase
	 */
	public WorkloadStats executeWorkloads(JoularCoreRunner runner, JoularCoreConfig config, Path outputCsv,
			Supplier<TrainingConfig> configFactory, int warmupSeconds, int measureSeconds) throws Exception {
		if (warmupSeconds > 0) {
			logger.accept("[greener] Warmup phase: " + warmupSeconds + " s ...");
			TrainingConfig warmupConfig = configFactory.get()
				.warmupDurationSeconds(warmupSeconds)
				.measureDurationSeconds(0);
			new TrainingRunner().run(warmupConfig);

			runner.stop();
			Files.deleteIfExists(outputCsv);
			runner.start(config);
		}

		logger.accept("[greener] Measurement phase: " + measureSeconds + " s ...");
		TrainingConfig measureConfig = configFactory.get()
			.warmupDurationSeconds(0)
			.measureDurationSeconds(measureSeconds);
		return new TrainingRunner().run(measureConfig);
	}

	/**
	 * Reads Joular Core CSV output and constructs an {@link EnergyReport}.
	 * @param outputCsv path to the CSV file written by Joular Core
	 * @param duration measurement duration in seconds
	 * @param appIdentifier application identifier (typically the jar name without
	 * extension)
	 * @return the energy report
	 */
	public EnergyReport processResults(Path outputCsv, int duration, String appIdentifier) throws IOException {
		logger.accept("[greener] Processing results ...");
		JoularCoreResultReader reader = new JoularCoreResultReader();
		return reader.readResults(outputCsv, PluginDefaults.buildRunId(), duration, appIdentifier);
	}

	/**
	 * Loads the baseline, compares the report against it, saves the latest report, and
	 * optionally auto-updates the baseline.
	 * @param report the current energy report
	 * @param baselinePath path to the baseline file ({@code null} if none configured)
	 * @param runDir directory for the current run
	 * @param threshold regression threshold percentage
	 * @param autoUpdate whether to auto-promote the report to baseline
	 * @param commitSha Git commit SHA (may be {@code null})
	 * @param branch Git branch name (may be {@code null})
	 * @return the comparison result
	 */
	public ComparisonResult processBaselineComparison(EnergyReport report, Path baselinePath, Path runDir,
			double threshold, boolean autoUpdate, String commitSha, String branch) throws IOException {
		BaselineManager manager = new BaselineManager();
		Optional<EnergyBaseline> baseline = loadBaselineSafely(manager, baselinePath);
		ComparisonResult comparison = new EnergyComparator().compare(report, baseline, threshold);

		Path latestReportPath = runDir.resolve("latest-energy-report.json");
		manager.saveBaseline(report, null, null, latestReportPath);

		if (autoUpdate && baselinePath != null) {
			String sha = PluginDefaults.normalise(commitSha);
			String br = PluginDefaults.normalise(branch);
			manager.saveBaseline(report, sha, br, baselinePath);
			for (String line : PluginDefaults.formatBaselineUpdateSummary(baselinePath, sha, br,
					report.totalEnergyJoules())) {
				logger.accept(line);
			}
		}

		return comparison;
	}

	/**
	 * Generates console and HTML reports, saves the run entry, and produces an aggregated
	 * report when multiple runs exist.
	 * @param report the energy report
	 * @param comparison the baseline comparison result
	 * @param workloadStats workload statistics
	 * @param toolName name of the workload tool
	 * @param reportDir top-level report directory
	 * @param runDir tool-specific run directory
	 * @param vmMode whether VM mode was used
	 * @return path to the generated HTML report
	 */
	public Path generateFinalReports(EnergyReport report, ComparisonResult comparison, WorkloadStats workloadStats,
			String toolName, Path reportDir, Path runDir, boolean vmMode) throws IOException {
		PowerSource powerSource = PluginDefaults.resolvePowerSource(vmMode);
		new ConsoleReporter().report(report, comparison, workloadStats, powerSource);

		HtmlReporter htmlReporter = new HtmlReporter();
		Path htmlReport = htmlReporter.generateReport(report, comparison, workloadStats, powerSource, runDir);
		logger.accept("[greener] HTML report: " + htmlReport);

		RunEntryStore runEntryStore = new RunEntryStore();
		AggregatedRunEntry runEntry = new AggregatedRunEntry(toolName, report, workloadStats, comparison);
		runEntryStore.save(runEntry, runDir.resolve(RunEntryStore.RUN_ENTRY_FILE));

		List<AggregatedRunEntry> allEntries = runEntryStore.loadAll(reportDir);
		if (!allEntries.isEmpty() && allEntries.size() != 1) {
			Path aggregatedReport = htmlReporter.generateAggregatedReport(allEntries, powerSource, reportDir);
			logger.accept("[greener] Aggregated report (" + allEntries.size() + " runs): " + aggregatedReport);
		}

		return htmlReport;
	}

	private Optional<EnergyBaseline> loadBaselineSafely(BaselineManager manager, Path baselinePath) {
		if (baselinePath == null || !Files.exists(baselinePath)) {
			return Optional.empty();
		}
		try {
			return manager.loadBaseline(baselinePath);
		}
		catch (IOException e) {
			logger.accept("[greener] Could not load baseline: " + e.getMessage());
			return Optional.empty();
		}
	}

}
