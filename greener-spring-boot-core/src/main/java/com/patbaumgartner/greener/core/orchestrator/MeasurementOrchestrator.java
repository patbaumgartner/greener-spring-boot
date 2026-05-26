package com.patbaumgartner.greener.core.orchestrator;

import com.patbaumgartner.greener.core.baseline.BaselineManager;
import com.patbaumgartner.greener.core.baseline.RunEntryStore;
import com.patbaumgartner.greener.core.baseline.TrendHistoryStore;
import com.patbaumgartner.greener.core.comparator.EnergyComparator;
import com.patbaumgartner.greener.core.config.PluginDefaults;
import com.patbaumgartner.greener.core.config.TrainingConfig;
import com.patbaumgartner.greener.core.model.AggregatedRunEntry;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.EnergyBaseline;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.IteratedMeasurement;
import com.patbaumgartner.greener.core.model.MeasurementConfig;
import com.patbaumgartner.greener.core.model.MeasurementResult;
import com.patbaumgartner.greener.core.model.MethodLevelReports;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.RegressionMetric;
import com.patbaumgartner.greener.core.model.Statistics;
import com.patbaumgartner.greener.core.model.TrendEntry;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import com.patbaumgartner.greener.core.reader.JoularCodeJavaResultReader;
import com.patbaumgartner.greener.core.reader.JoularCoreResultReader;
import com.patbaumgartner.greener.core.reporter.ConsoleReporter;
import com.patbaumgartner.greener.core.reporter.HtmlReporter;
import com.patbaumgartner.greener.core.runner.JoularCoreRunner;
import com.patbaumgartner.greener.core.runner.TrainingRunner;
import com.patbaumgartner.greener.core.config.JoularCoreConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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

	private static final int DEFAULT_TOP_N = 20;

	private static final String SECONDS_SUFFIX = " s ...";

	private final Consumer<String> logger;

	private final int topN;

	public MeasurementOrchestrator(Consumer<String> logger) {
		this(logger, DEFAULT_TOP_N);
	}

	public MeasurementOrchestrator(Consumer<String> logger, int topN) {
		this.logger = logger;
		this.topN = topN;
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
			Supplier<TrainingConfig> configFactory, int warmupSeconds, int measureSeconds)
			throws IOException, InterruptedException {
		if (warmupSeconds > 0) {
			logger.accept("[greener] Warmup phase: " + warmupSeconds + "" + SECONDS_SUFFIX);
			TrainingConfig warmupConfig = configFactory.get()
				.warmupDurationSeconds(warmupSeconds)
				.measureDurationSeconds(0);
			new TrainingRunner().run(warmupConfig);

			runner.stop();
			Files.deleteIfExists(outputCsv);
			runner.start(config);
		}

		logger.accept("[greener] Measurement phase: " + measureSeconds + "" + SECONDS_SUFFIX);
		TrainingConfig measureConfig = configFactory.get()
			.warmupDurationSeconds(0)
			.measureDurationSeconds(measureSeconds);
		return new TrainingRunner().run(measureConfig);
	}

	/**
	 * Runs an optional warmup followed by {@code iterations} measurement windows. Each
	 * iteration rotates the Joular Core CSV (stop, archive, restart) so that per-window
	 * energy can be parsed independently. The per-iteration totals are aggregated into a
	 * {@link Statistics} attached to the representative report.
	 *
	 * <p>
	 * <b>Statistical rigor.</b> Multiple independent windows are required to estimate
	 * baseline variance; with {@code iterations >= 2} the comparator can apply Welch's
	 * t-test instead of falling back to a raw percentage threshold. Cohen (1988)
	 * recommends {@code n >= 5} per group for stable effect-size estimates; values lower
	 * than that are still emitted but treated more conservatively by the comparator
	 * (effect-size gate of |d| < 0.5).
	 *
	 * <p>
	 * The "representative" report is the iteration whose total energy is closest to the
	 * <em>median</em> of the iterations &mdash; a robust choice that resists outliers
	 * caused by noisy neighbours, GC pauses, or kernel housekeeping. Its measurements are
	 * preserved (the median iteration's per-method or per-process breakdown). Workload
	 * statistics are summed (request counts) across iterations and rescaled by total
	 * duration.
	 * @param appIdentifier application identifier (typically the jar name without
	 * extension); used to tag the parsed reports
	 * @param iterationCsvDir directory under which per-iteration CSVs are archived as
	 * {@code joularcore-output-iter-N.csv}
	 */
	public IteratedMeasurement executeIteratedWorkloads(JoularCoreRunner runner, JoularCoreConfig config,
			Path outputCsv, Supplier<TrainingConfig> configFactory, int warmupSeconds, int measureSeconds,
			int iterations, String appIdentifier, Path iterationCsvDir) throws IOException, InterruptedException {
		return executeIteratedWorkloads(runner, config, outputCsv, configFactory, warmupSeconds, measureSeconds,
				iterations, appIdentifier, iterationCsvDir, null);
	}

	/**
	 * Variant of
	 * {@link #executeIteratedWorkloads(JoularCoreRunner, JoularCoreConfig, Path, Supplier, int, int, int, String, Path)}
	 * that additionally clears the Joular Code Java result CSVs after the warmup phase
	 * and before the first measurement iteration. This prevents startup-phase energy
	 * (Spring Boot initialisation, JIT compilation) from accumulating into method-level
	 * totals and distorting per-method attribution (e.g. inflating
	 * {@code PetClinicApplication.main}).
	 * @param joularCodeJavaResultsDir the directory that holds
	 * {@code methods-power-app.csv} and {@code methods-power-all.csv}, or {@code null}
	 * when Joular Code Java is not used
	 */
	public IteratedMeasurement executeIteratedWorkloads(JoularCoreRunner runner, JoularCoreConfig config,
			Path outputCsv, Supplier<TrainingConfig> configFactory, int warmupSeconds, int measureSeconds,
			int iterations, String appIdentifier, Path iterationCsvDir, Path joularCodeJavaResultsDir)
			throws IOException, InterruptedException {

		int n = Math.max(1, iterations);

		if (warmupSeconds > 0) {
			logger.accept("[greener] Warmup phase: " + warmupSeconds + "" + SECONDS_SUFFIX);
			TrainingConfig warmupConfig = configFactory.get()
				.warmupDurationSeconds(warmupSeconds)
				.measureDurationSeconds(0);
			new TrainingRunner().run(warmupConfig);
		}

		// Record the epoch-millis timestamp at the end of warmup. This is passed to the
		// Joular Code Java result reader, which will skip any CSV rows written before
		// this moment, effectively excluding warmup energy from per-method attribution.
		// We do NOT delete the CSVs here because JoularCode Java's ResultWriter caches
		// open BufferedWriter handles; deleting a file while the handle is open leaves
		// the writer appending to an orphaned inode (no new file is ever created).
		long methodLevelStartMs = (joularCodeJavaResultsDir != null && warmupSeconds > 0) ? System.currentTimeMillis()
				: 0L;

		Files.createDirectories(iterationCsvDir);

		List<EnergyReport> perIteration = new ArrayList<>(n);
		long totalRequests = 0;
		long totalFailed = 0;
		long totalDuration = 0;
		String toolName = null;
		boolean anyHasCounts = false;

		for (int i = 1; i <= n; i++) {
			// Rotate Joular Core: stop, clear CSV, restart for a fresh per-iteration
			// window. The very first iteration may already have a started runner; either
			// way we ensure the CSV is empty before this iteration starts.
			runner.stop();
			Files.deleteIfExists(outputCsv);
			runner.start(config);

			logger.accept("[greener] Iteration " + i + "/" + n + " — measurement phase: " + measureSeconds + ""
					+ SECONDS_SUFFIX);
			TrainingConfig measureConfig = configFactory.get()
				.warmupDurationSeconds(0)
				.measureDurationSeconds(measureSeconds);
			WorkloadStats stats = new TrainingRunner().run(measureConfig);

			// Stop the runner so the CSV is fully flushed before we read it.
			runner.stop();
			Path iterCsv = iterationCsvDir.resolve("joularcore-output-iter-" + i + ".csv");
			if (Files.exists(outputCsv)) {
				Files.copy(outputCsv, iterCsv, StandardCopyOption.REPLACE_EXISTING);
			}
			EnergyReport report = new JoularCoreResultReader().readResults(iterCsv, PluginDefaults.buildRunId(),
					measureSeconds, appIdentifier);
			perIteration.add(report);

			if (toolName == null) {
				toolName = stats.tool();
			}
			if (stats.hasRequestCounts()) {
				anyHasCounts = true;
				totalRequests += stats.totalRequests();
				totalFailed += Math.max(0, stats.failedRequests());
			}
			totalDuration += stats.durationSeconds();
		}

		double[] totals = perIteration.stream().mapToDouble(EnergyReport::totalEnergyJoules).toArray();
		Statistics stats = totals.length == 0 ? Statistics.empty() : Statistics.of(totals);

		EnergyReport representative = pickMedianClosest(perIteration, stats).withStats(stats);

		WorkloadStats merged;
		if (toolName == null) {
			merged = WorkloadStats.external("custom", totalDuration);
		}
		else if (anyHasCounts) {
			merged = WorkloadStats.external(toolName, totalRequests, totalFailed, totalDuration);
		}
		else {
			merged = WorkloadStats.external(toolName, totalDuration);
		}

		return new IteratedMeasurement(representative, perIteration, merged, methodLevelStartMs);
	}

	/**
	 * Picks the iteration whose total energy is closest to the median across all
	 * iterations. Falls back to the first report when the list is empty after filtering
	 * (defensive; the caller guarantees at least one iteration).
	 */
	private static EnergyReport pickMedianClosest(List<EnergyReport> reports, Statistics stats) {
		final int singleton = 1;
		if (reports.size() == singleton) {
			return reports.get(0);
		}
		double median = stats.median();
		EnergyReport best = reports.get(0);
		double bestDist = Math.abs(best.totalEnergyJoules() - median);
		for (int i = 1; i < reports.size(); i++) {
			double dist = Math.abs(reports.get(i).totalEnergyJoules() - median);
			if (dist < bestDist) {
				best = reports.get(i);
				bestDist = dist;
			}
		}
		return best;
	}

	/**
	 * Runs a brief idle window with no workload and returns the per-second power
	 * consumption observed (mean of the per-second app-share Joules over the idle
	 * window). The caller may subtract this baseline from a subsequent measurement to
	 * approximate <em>marginal</em> energy attributable to the workload.
	 *
	 * <p>
	 * Returns {@code 0.0} when {@code idleSeconds <= 0} or no power samples were
	 * collected. The Joular Core runner is restarted so the idle CSV does not contaminate
	 * subsequent measurement windows.
	 *
	 * <p>
	 * When {@code idleSavePath} is non-{@code null} the idle Joular Core CSV is copied
	 * there before being deleted, preserving the raw sensor data for later analysis.
	 */
	public double measureIdleBaselinePower(JoularCoreRunner runner, JoularCoreConfig config, Path outputCsv,
			int idleSeconds, String appIdentifier, Path idleSavePath) throws IOException, InterruptedException {
		if (idleSeconds <= 0) {
			return 0.0;
		}
		logger.accept("[greener] Idle baseline phase: " + idleSeconds + " s (no workload) ...");
		runner.stop();
		Files.deleteIfExists(outputCsv);
		runner.start(config);
		Thread.sleep(idleSeconds * 1000L);
		runner.stop();
		double idlePowerW = 0.0;
		if (Files.exists(outputCsv)) {
			EnergyReport idle = new JoularCoreResultReader().readResults(outputCsv,
					PluginDefaults.buildRunId() + "-idle", idleSeconds, appIdentifier);
			idlePowerW = idle.durationSeconds() > 0 ? idle.totalEnergyJoules() / idle.durationSeconds() : 0.0;
			if (idleSavePath != null) {
				Files.createDirectories(idleSavePath.getParent());
				Files.copy(outputCsv, idleSavePath, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		logger.accept(String.format("[greener] Idle baseline: %.3f W average over %d s", idlePowerW, idleSeconds));
		// Restart for the upcoming workload phase.
		Files.deleteIfExists(outputCsv);
		runner.start(config);
		return idlePowerW;
	}

	/**
	 * Subtracts {@code idlePowerW * durationSeconds} from
	 * {@code report.totalEnergyJoules} (clamped at zero) and returns a copy with the
	 * adjusted total. The original statistics, if any, are dropped because they no longer
	 * correspond to the new total.
	 */
	public EnergyReport subtractIdleBaseline(EnergyReport report, double idlePowerW) {
		if (idlePowerW <= 0.0 || report == null) {
			return report;
		}
		double idleJoules = idlePowerW * report.durationSeconds();
		double adjusted = Math.max(0.0, report.totalEnergyJoules() - idleJoules);
		logger
			.accept(String.format("[greener] Idle subtraction: -%.3f J (workload net = %.3f J)", idleJoules, adjusted));
		return new EnergyReport(report.runId(), report.timestamp() == null ? Instant.now() : report.timestamp(),
				report.durationSeconds(), report.measurements(), adjusted, report.totalEnergyStats());
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
	 * Reads both filtered (app-only) and unfiltered (all methods) Joular Code Java
	 * results from the working directory, returning them as a {@link MethodLevelReports}.
	 * All rows are included regardless of timestamp.
	 * @param workingDir the working directory where Joular Code Java writes its results
	 * @param duration measurement duration in seconds
	 * @return a {@link MethodLevelReports}, or {@code null} if no results directory
	 * exists
	 */
	public MethodLevelReports readJoularCodeJavaMethodLevelReports(Path workingDir, int duration) {
		return readJoularCodeJavaMethodLevelReports(workingDir, duration, 0L);
	}

	/**
	 * Reads both filtered (app-only) and unfiltered (all methods) Joular Code Java
	 * results, skipping rows written before {@code startTimestampMs}.
	 * @param workingDir the working directory where Joular Code Java writes its results
	 * @param duration measurement duration in seconds
	 * @param startTimestampMs epoch-millis cut-off; rows with a timestamp strictly before
	 * this value are excluded (0 = no filtering)
	 * @return a {@link MethodLevelReports}, or {@code null} if no results directory
	 * exists
	 */
	public MethodLevelReports readJoularCodeJavaMethodLevelReports(Path workingDir, int duration,
			long startTimestampMs) {
		Path resultsDir = workingDir.resolve("joular-code-java-results");
		if (!Files.isDirectory(resultsDir)) {
			logger.accept("[greener] Joular Code Java results directory not found: " + resultsDir);
			return null;
		}
		JoularCodeJavaResultReader reader = new JoularCodeJavaResultReader();
		MethodLevelReports reports = reader.readAllResults(resultsDir, PluginDefaults.buildRunId(), duration,
				startTimestampMs);
		if (!reports.hasData()) {
			logger.accept("[greener] Joular Code Java produced no method-level data");
			return null;
		}
		if (reports.hasAppData()) {
			logger.accept("[greener] Joular Code Java app methods: " + reports.appReport().measurements().size()
					+ " methods, " + String.format("%.2f J total", reports.appReport().totalEnergyJoules()));
		}
		if (reports.hasAllData()) {
			logger.accept("[greener] Joular Code Java all methods: " + reports.allReport().measurements().size()
					+ " methods, " + String.format("%.2f J total", reports.allReport().totalEnergyJoules()));
		}
		return reports;
	}

	/**
	 * Loads the baseline, compares the report against it (using the given metric and
	 * workload statistics), saves the latest report, and optionally auto-updates the
	 * baseline.
	 */
	public ComparisonResult processBaselineComparison(EnergyReport report, Path baselinePath, Path runDir,
			double threshold, boolean autoUpdate, String commitSha, String branch, RegressionMetric metric,
			WorkloadStats currentWorkload) throws IOException {
		BaselineManager manager = new BaselineManager();
		Optional<EnergyBaseline> baseline = loadBaselineSafely(manager, baselinePath);
		ComparisonResult comparison = new EnergyComparator().compare(report, baseline, threshold, metric,
				currentWorkload);

		String sha = PluginDefaults.normalise(commitSha);
		String br = PluginDefaults.normalise(branch);

		Path latestReportPath = runDir.resolve("latest-energy-report.json");
		manager.saveBaseline(report, sha, br, currentWorkload, latestReportPath);

		if (autoUpdate && baselinePath != null) {
			manager.saveBaseline(report, sha, br, currentWorkload, baselinePath);
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
		return generateFinalReports(report, comparison, workloadStats, toolName, reportDir, runDir, vmMode,
				(MethodLevelReports) null);
	}

	/**
	 * Generates console and HTML reports including optional Joular Code Java method-level
	 * data (both app-only and all methods), saves the run entry, and produces an
	 * aggregated report when multiple runs exist.
	 * @param report the energy report
	 * @param comparison the baseline comparison result
	 * @param workloadStats workload statistics
	 * @param toolName name of the workload tool
	 * @param reportDir top-level report directory
	 * @param runDir tool-specific run directory
	 * @param vmMode whether VM mode was used
	 * @param methodLevelReports optional Joular Code Java method-level reports
	 * ({@code null} if not used)
	 * @return path to the generated HTML report
	 */
	public Path generateFinalReports(EnergyReport report, ComparisonResult comparison, WorkloadStats workloadStats,
			String toolName, Path reportDir, Path runDir, boolean vmMode, MethodLevelReports methodLevelReports)
			throws IOException {
		return generateFinalReports(report, comparison, workloadStats, toolName, reportDir, runDir, vmMode,
				methodLevelReports, Collections.emptyList());
	}

	/**
	 * Variant of
	 * {@link #generateFinalReports(EnergyReport, ComparisonResult, WorkloadStats, String, Path, Path, boolean, MethodLevelReports)}
	 * that also threads a chronological energy-trend history into the HTML report so it
	 * can render a sparkline / line chart of prior runs.
	 * @param trendHistory chronological list of prior-run trend points (oldest-first);
	 * pass an empty list to omit the chart
	 */
	public Path generateFinalReports(EnergyReport report, ComparisonResult comparison, WorkloadStats workloadStats,
			String toolName, Path reportDir, Path runDir, boolean vmMode, MethodLevelReports methodLevelReports,
			List<TrendEntry> trendHistory) throws IOException {
		PowerSource powerSource = PluginDefaults.resolvePowerSource(vmMode);
		// Console reporter uses app-only methods (or all methods when app filter empty)
		EnergyReport consoleJoularCodeReport = methodLevelReports != null && methodLevelReports.hasAppData()
				? methodLevelReports.appReport() : (methodLevelReports != null && methodLevelReports.hasAllData()
						? methodLevelReports.allReport() : null);
		new ConsoleReporter().report(report, comparison, workloadStats, powerSource, consoleJoularCodeReport);

		HtmlReporter htmlReporter = new HtmlReporter(topN);
		Path htmlReport = htmlReporter.generateReport(report, comparison, workloadStats, powerSource,
				methodLevelReports, trendHistory == null ? Collections.emptyList() : trendHistory, runDir);
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

	/**
	 * Convenience method that combines result processing, baseline comparison,
	 * method-level reading, and report generation into a single call.
	 * @param config measurement configuration aggregating all parameters
	 * @param workloadStats workload statistics from the measurement phase
	 * @return a {@link MeasurementResult} aggregating all outputs
	 */
	public MeasurementResult processAndReport(MeasurementConfig config, WorkloadStats workloadStats)
			throws IOException {
		EnergyReport report = processResults(config.outputCsv(), config.measureDurationSeconds(),
				config.appIdentifier());
		return processAndReport(config, report, workloadStats);
	}

	/**
	 * Variant of {@link #processAndReport(MeasurementConfig, WorkloadStats)} that accepts
	 * a pre-built {@link EnergyReport}. Used by callers that have already parsed the
	 * Joular Core output (e.g. multi-iteration runs that aggregate per-iteration
	 * statistics, or idle-baseline-subtracted runs).
	 */
	public MeasurementResult processAndReport(MeasurementConfig config, EnergyReport report,
			WorkloadStats workloadStats) throws IOException {
		ComparisonResult comparison = processBaselineComparison(report, config.baselinePath(), config.runDir(),
				config.threshold(), config.autoUpdate(), config.commitSha(), config.branch(), config.regressionMetric(),
				workloadStats);
		MethodLevelReports methodLevelReports = config.hasJoularCodeJava()
				? readJoularCodeJavaMethodLevelReports(config.joularCodeJavaWorkingDir(),
						config.measureDurationSeconds(), config.methodLevelStartTimestampMs())
				: null;
		Path htmlReport = generateFinalReports(report, comparison, workloadStats, config.toolName(), config.reportDir(),
				config.runDir(), config.vmMode(), methodLevelReports,
				updateTrendHistory(config, report, workloadStats));
		return new MeasurementResult(report, comparison, workloadStats, methodLevelReports, htmlReport);
	}

	/**
	 * Appends the current run's headline numbers to the trend-history file (alongside the
	 * baseline) and returns the resulting chronological list for chart rendering. Returns
	 * an empty list when no baseline path is configured (we have no obvious place to
	 * persist history) or when persistence fails &mdash; the chart is a convenience and
	 * never blocks the build.
	 */
	private List<TrendEntry> updateTrendHistory(MeasurementConfig config, EnergyReport report,
			WorkloadStats workloadStats) {
		Path baselinePath = config.baselinePath();
		if (baselinePath == null) {
			return Collections.emptyList();
		}
		Path trendFile = TrendHistoryStore.trendFileFor(baselinePath);
		TrendHistoryStore store = new TrendHistoryStore();
		double total = report.totalEnergyJoules();
		Double perReq = null;
		if (workloadStats != null) {
			double mj = workloadStats.energyPerRequestMillijoules(total);
			if (!Double.isNaN(mj) && !Double.isInfinite(mj)) {
				perReq = mj;
			}
		}
		String runId = config.commitSha() != null && !config.commitSha().isBlank() ? config.commitSha()
				: Instant.now().toString();
		TrendEntry entry = new TrendEntry(Instant.now(), runId, total, perReq, config.commitSha(), config.branch());
		try {
			return store.appendAndSave(trendFile, entry);
		}
		catch (IOException e) {
			logger.accept("[greener] Could not update trend history: " + e.getMessage());
			return Collections.emptyList();
		}
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
