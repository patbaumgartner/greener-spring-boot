package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.AggregatedRunEntry;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.MethodLevelReports;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.TrendEntry;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntFunction;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML report showing current energy measurements
 * and
 * optionally a comparison against a stored baseline.
 *
 * <p>
 * The report is written to {@code {outputDir}/greener-energy-report.html}.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // HTML builder with many small string
												// fragments
public class HtmlReporter {

	private static final Logger LOG = Logger.getLogger(HtmlReporter.class.getName());

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
			.withZone(ZoneId.systemDefault());

	private static final String FMT_ENERGY_JOULES = "%.2f J";

	private static final String FMT_DECIMAL_2 = "%.2f";

	private static final String TABLE_OPEN = "    <table><thead><tr>";

	private static final String THEAD_CLOSE = "</tr></thead><tbody>\n";

	private static final String TR_OPEN = "      <tr>";

	private static final String TD_CODE_OPEN = "<td><code>";

	private static final String TD_CODE_CLOSE = "</code></td>";

	private static final String TD_OPEN = "<td>";

	private static final String TD_NUM_OPEN = "<td class=\"num\">";

	private static final String TD_CLOSE = "</td>";

	private static final String TR_CLOSE = "</tr>\n";

	private static final String DIV_CLOSE = "  </div>\n";

	private static final String LABEL_DURATION = "Duration";

	private static final String LABEL_TOTAL_ENERGY = "Total Energy";

	private static final String FMT_PERCENT_1 = "%.1f%%";

	private static final String METRICS_CLOSE = "    </div>\n";

	private final int topN;

	public HtmlReporter() {
		this(20);
	}

	public HtmlReporter(int topN) {
		this.topN = topN;
	}

	/** Generates a report without workload stats. */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, Path outputDir) throws IOException {
		return generateReport(current, comparison, null, null, (MethodLevelReports) null, outputDir);
	}

	/** Generates a report including use-case energy metrics from the workload. */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			Path outputDir) throws IOException {
		return generateReport(current, comparison, workloadStats, null, (MethodLevelReports) null, outputDir);
	}

	/** Generates a report including power source assumptions. */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource, Path outputDir) throws IOException {
		return generateReport(current, comparison, workloadStats, powerSource, (MethodLevelReports) null, outputDir);
	}

	/** Generates a report including optional Joular Code Java method-level data. */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource, EnergyReport methodLevelReport, Path outputDir) throws IOException {
		MethodLevelReports methodReports = methodLevelReport != null ? new MethodLevelReports(methodLevelReport, null)
				: null;
		return generateReport(current, comparison, workloadStats, powerSource, methodReports, outputDir);
	}

	/**
	 * Generates a report including optional Joular Code Java method-level data (app
	 * +
	 * all).
	 */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource, MethodLevelReports methodLevelReports, Path outputDir) throws IOException {
		return generateReport(current, comparison, workloadStats, powerSource, methodLevelReports, List.of(),
				outputDir);
	}

	/**
	 * Generates a report including optional Joular Code Java method-level data and
	 * a
	 * historical trend (sparkline / line chart) of {@code totalEnergyJoules} over
	 * prior
	 * runs.
	 * 
	 * @param trendEntries chronological list of prior-run trend points
	 *                     (oldest-first);
	 *                     pass an empty list to omit the trend card
	 */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource, MethodLevelReports methodLevelReports, List<TrendEntry> trendEntries,
			Path outputDir) throws IOException {
		Files.createDirectories(outputDir);
		Path reportFile = outputDir.resolve("greener-energy-report.html");
		Files.writeString(reportFile,
				buildHtml(current, comparison, workloadStats, powerSource, methodLevelReports, trendEntries));
		LOG.info(() -> "HTML report written to: " + reportFile);
		return reportFile;
	}

	/**
	 * Generates an aggregated report summarising multiple tool runs in a single
	 * HTML
	 * page. Each entry captures the workload tool, energy report, and optional
	 * comparison.
	 * 
	 * @param runs        the list of tool run entries to aggregate
	 * @param powerSource optional power source used for all runs
	 * @param outputDir   directory where the report file is written
	 * @return path to the generated HTML file
	 */
	public Path generateAggregatedReport(List<AggregatedRunEntry> runs, PowerSource powerSource, Path outputDir)
			throws IOException {
		Files.createDirectories(outputDir);
		Path reportFile = outputDir.resolve("greener-aggregated-report.html");
		Files.writeString(reportFile, buildAggregatedHtml(runs, powerSource));
		LOG.info(() -> "Aggregated HTML report written to: " + reportFile);
		return reportFile;
	}

	private String buildHtml(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource, MethodLevelReports methodLevelReports, List<TrendEntry> trendEntries) {
		StringBuilder sb = new StringBuilder();
		sb.append(htmlHead("Greener Spring Boot — Energy Report"));
		sb.append(
				"""
						<div class="hero">
						  <button class="theme-toggle" onclick="toggleTheme()" title="Toggle light/dark mode">&#9681; Theme</button>
						  <h1><span>&#9889;</span> Greener Spring Boot</h1>
						  <p class="tagline">Energy Report — powered by
						    <a href="https://github.com/joular/joularcore">Joular Core</a>
						  </p>
						</div>
						<div class="container">
						""");

		// Run summary card
		sb.append("  <div class=\"card\">\n    <h2>Measurement Summary</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Run", current.runId()));
		sb.append(metric("Measured at", FORMATTER.format(current.timestamp())));
		sb.append(metric(LABEL_DURATION, current.durationSeconds() + " s"));
		sb.append(metric(LABEL_TOTAL_ENERGY, String.format(FMT_ENERGY_JOULES, current.totalEnergyJoules())));
		sb.append("    </div>\n  </div>\n");

		// Use-case energy card
		if (workloadStats != null) {
			sb.append(buildWorkloadCard(workloadStats, current.totalEnergyJoules()));
		}

		// Power source assumptions card
		if (powerSource != null && powerSource != PowerSource.UNKNOWN) {
			sb.append(buildPowerSourceCard(powerSource));
		}

		// Comparison card
		if (comparison != null && comparison.overallStatus() != ComparisonStatus.NO_BASELINE) {
			sb.append(buildComparisonCard(comparison));
		} else if (comparison != null) {
			sb.append("  <div class=\"card\"><div class=\"note\">"
					+ "No baseline found — this run will be saved as the new baseline.</div></div>\n");
		}

		// Historical trend chart (only meaningful with at least two data points)
		if (trendEntries != null && trendEntries.size() >= 2) {
			sb.append(buildTrendChartCard(trendEntries));
		}

		// Measurements table
		if (!current.measurements().isEmpty()) {
			sb.append("  <div class=\"card\">\n    <h2>Energy Breakdown</h2>\n");
			boolean hasComparison = comparison != null && comparison.overallStatus() != ComparisonStatus.NO_BASELINE;
			sb.append(TABLE_OPEN)
					.append("<th>Component</th><th class=\"num\">Energy (J)</th><th class=\"num\">Share</th>");
			if (hasComparison)
				sb.append("<th class=\"num\">Change</th>");
			sb.append(THEAD_CLOSE);

			double total = current.totalEnergyJoules();
			for (EnergyMeasurement m : current.topMeasurements(topN)) {
				double pct = total > 0 ? (m.energyJoules() / total) * 100 : 0;
				sb.append(TR_OPEN)
						.append(TD_CODE_OPEN)
						.append(escHtml(m.methodName()))
						.append(TD_CODE_CLOSE)
						.append(TD_NUM_OPEN)
						.append(String.format(FMT_DECIMAL_2, m.energyJoules()))
						.append(TD_CLOSE)
						.append(TD_NUM_OPEN)
						.append(String.format(FMT_PERCENT_1, pct))
						.append(TD_CLOSE);
				if (hasComparison) {
					comparison.methodComparisons()
							.stream()
							.filter(mc -> mc.methodName().equals(m.methodName()))
							.findFirst()
							.ifPresentOrElse(mc -> {
								String cls = mc.deltaPercent() > comparison.threshold() ? "badge-red"
										: mc.deltaPercent() < 0 ? "badge-green" : "badge-yellow";
								sb.append("<td class=\"num\"><span class=\"badge ")
										.append(cls)
										.append("\">")
										.append(String.format("%+.2f%%", mc.deltaPercent()))
										.append("</span></td>");
							}, () -> sb.append("<td class=\"num\">—</td>"));
				}
				sb.append(TR_CLOSE);
			}
			sb.append("    </tbody></table>\n  </div>\n");
		}

		if (methodLevelReports != null && methodLevelReports.hasData()) {
			sb.append(buildMethodLevelCard(methodLevelReports, current.totalEnergyJoules()));
		}

		sb.append(htmlFooter());
		sb.append(htmlScript());
		return sb.toString();
	}

	private String buildAggregatedHtml(List<AggregatedRunEntry> runs, PowerSource powerSource) {
		StringBuilder sb = new StringBuilder();
		sb.append(htmlHead("Greener Spring Boot — Aggregated Report"));
		sb.append(
				"""
						<div class="hero">
						  <button class="theme-toggle" onclick="toggleTheme()" title="Toggle light/dark mode">&#9681; Theme</button>
						  <h1><span>&#9889;</span> Greener Spring Boot</h1>
						  <p class="tagline">Aggregated Energy Report — powered by
						    <a href="https://github.com/joular/joularcore">Joular Core</a>
						  </p>
						</div>
						<div class="container">
						""");

		sb.append(buildAggregatedSummaryCard(runs));

		if (powerSource != null && powerSource != PowerSource.UNKNOWN) {
			sb.append(buildPowerSourceCard(powerSource));
		}

		sb.append(buildToolComparisonTable(runs));

		for (AggregatedRunEntry run : runs) {
			sb.append(buildToolDetailCard(run));
		}

		sb.append(htmlFooter());
		sb.append(htmlScript());
		return sb.toString();
	}

	private String buildAggregatedSummaryCard(List<AggregatedRunEntry> runs) {
		double totalEnergy = runs.stream().mapToDouble(r -> r.report().totalEnergyJoules()).sum();
		long totalRequests = runs.stream()
				.filter(r -> r.workloadStats() != null && r.workloadStats().hasRequestCounts())
				.mapToLong(r -> r.workloadStats().totalRequests())
				.sum();

		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Aggregated Summary</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Tool Runs", String.valueOf(runs.size())));
		sb.append(metric(LABEL_TOTAL_ENERGY, String.format(FMT_ENERGY_JOULES, totalEnergy)));
		if (totalRequests > 0) {
			sb.append(metric("Total Requests", String.format("%,d", totalRequests)));
		}
		sb.append("    </div>\n  </div>\n");
		return sb.toString();
	}

	private String buildToolComparisonTable(List<AggregatedRunEntry> runs) {
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Per-Tool Results</h2>\n");
		sb.append(TABLE_OPEN);
		sb.append("<th>Tool</th><th>Run ID</th><th class=\"num\">Duration (s)</th>");
		sb.append("<th class=\"num\">Energy (J)</th><th class=\"num\">Requests</th><th class=\"num\">Failed</th>");
		sb.append("<th class=\"num\">Throughput</th><th class=\"num\">Energy/Req (mJ)</th><th>Status</th>");
		sb.append(THEAD_CLOSE);

		for (AggregatedRunEntry run : runs) {
			EnergyReport report = run.report();
			WorkloadStats stats = run.workloadStats();
			ComparisonResult comp = run.comparison();

			sb.append(TR_OPEN);
			sb.append(TD_CODE_OPEN).append(escHtml(run.tool())).append(TD_CODE_CLOSE);
			sb.append(TD_OPEN).append(escHtml(report.runId())).append(TD_CLOSE);
			sb.append(TD_NUM_OPEN).append(report.durationSeconds()).append(TD_CLOSE);
			sb.append(TD_NUM_OPEN).append(String.format(FMT_DECIMAL_2, report.totalEnergyJoules())).append(TD_CLOSE);

			if (stats != null && stats.hasRequestCounts()) {
				sb.append(TD_NUM_OPEN).append(String.format("%,d", stats.totalRequests())).append(TD_CLOSE);
				sb.append(TD_NUM_OPEN)
						.append(String.format("%,d", Math.max(0, stats.failedRequests())))
						.append(TD_CLOSE);
				if (!Double.isNaN(stats.requestsPerSecond())) {
					sb.append(TD_NUM_OPEN)
							.append(String.format("%.1f %s", stats.requestsPerSecond(), stats.throughputUnit()))
							.append(TD_CLOSE);
				} else {
					sb.append("<td class=\"num\">—</td>");
				}
				double mjPerReq = stats.energyPerRequestMillijoules(report.totalEnergyJoules());
				sb.append(TD_NUM_OPEN)
						.append(!Double.isNaN(mjPerReq) ? String.format("%.3f", mjPerReq) : "—")
						.append(TD_CLOSE);
			} else {
				sb.append(
						"<td class=\"num\">—</td><td class=\"num\">—</td><td class=\"num\">—</td><td class=\"num\">—</td>");
			}

			if (comp != null && comp.overallStatus() != ComparisonStatus.NO_BASELINE) {
				String cls = switch (comp.overallStatus()) {
					case IMPROVED -> "badge-green";
					case REGRESSED -> "badge-red";
					default -> "badge-yellow";
				};
				String label = switch (comp.overallStatus()) {
					case IMPROVED -> "▼ Improved";
					case REGRESSED -> "▲ Regressed";
					default -> "→ Stable";
				};
				sb.append("<td><span class=\"badge ").append(cls).append("\">").append(label).append("</span></td>");
			} else {
				sb.append("<td><span class=\"badge badge-yellow\">No baseline</span></td>");
			}

			sb.append(TR_CLOSE);
		}
		sb.append("    </tbody></table>\n  </div>\n");
		return sb.toString();
	}

	private String buildToolDetailCard(AggregatedRunEntry run) {
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>")
				.append(escHtml(run.tool()))
				.append(" — Run Details</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Run ID", run.report().runId()));
		sb.append(metric("Measured at", FORMATTER.format(run.report().timestamp())));
		sb.append(metric(LABEL_DURATION, run.report().durationSeconds() + " s"));
		sb.append(metric(LABEL_TOTAL_ENERGY, String.format(FMT_ENERGY_JOULES, run.report().totalEnergyJoules())));
		sb.append(METRICS_CLOSE);

		if (!run.report().measurements().isEmpty()) {
			sb.append(TABLE_OPEN)
					.append("<th>Component</th><th class=\"num\">Energy (J)</th><th class=\"num\">Share</th>")
					.append(THEAD_CLOSE);
			double total = run.report().totalEnergyJoules();
			for (EnergyMeasurement m : run.report().topMeasurements(topN)) {
				double pct = total > 0 ? (m.energyJoules() / total) * 100 : 0;
				sb.append(TR_OPEN)
						.append(TD_CODE_OPEN)
						.append(escHtml(m.methodName()))
						.append(TD_CODE_CLOSE)
						.append(TD_NUM_OPEN)
						.append(String.format(FMT_DECIMAL_2, m.energyJoules()))
						.append(TD_CLOSE)
						.append(TD_NUM_OPEN)
						.append(String.format(FMT_PERCENT_1, pct))
						.append(TD_CLOSE)
						.append(TR_CLOSE);
			}
			sb.append("    </tbody></table>\n");
		}
		sb.append(DIV_CLOSE);
		return sb.toString();
	}

	private String buildMethodLevelCard(MethodLevelReports methodLevelReports, double processLevelEnergyJoules) {
		// Determine which report to display — prefer allReport (all methods), fall back
		// to appReport
		EnergyReport displayReport = methodLevelReports.hasAllData() ? methodLevelReports.allReport()
				: methodLevelReports.appReport();

		// Collect app-only method names for filtering
		Set<String> appMethods = new HashSet<>();
		if (methodLevelReports.hasAppData()) {
			methodLevelReports.appReport().measurements().forEach(m -> appMethods.add(m.methodName()));
		}

		boolean hasFilter = methodLevelReports.hasAllData() && methodLevelReports.hasAppData();

		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Method-Level Energy (Joular Code Java)</h2>\n");

		if (hasFilter) {
			sb.append(
					"""
							    <div style="margin-bottom:12px">
							      <button class="filter-toggle" onclick="toggleMethodFilter()" id="methodFilterBtn">Show App Only</button>
							      <span class="note" style="display:inline;border:none;background:none;padding:0 0 0 10px" id="methodFilterHint">
							        Showing all methods. Click to filter to application classes only.
							      </span>
							    </div>
							""");
		}

		sb.append("    <div class=\"metrics\">\n");
		sb.append(metric("Methods", String.valueOf(displayReport.measurements().size())));
		if (hasFilter) {
			sb.append(metric("App Methods", String.valueOf(methodLevelReports.appReport().measurements().size())));
		}
		sb.append(metric("Total Energy (all threads)",
				String.format(FMT_ENERGY_JOULES, displayReport.totalEnergyJoules())));
		sb.append(metric(LABEL_DURATION, displayReport.durationSeconds() + " s"));
		sb.append(METRICS_CLOSE);

		double methodTotal = displayReport.totalEnergyJoules();
		if (methodTotal > processLevelEnergyJoules && processLevelEnergyJoules > 0) {
			sb.append("    <div class=\"note\">")
					.append(String.format(
							"Method-level total (%.2f J) is higher than the process-level energy (%.2f J) "
									+ "because Joular Code Java distributes the full CPU power across all JVM threads. "
									+ "Joular Core measures only this application's share. "
									+ "Use the per-method share (%%) to identify expensive methods.",
							methodTotal, processLevelEnergyJoules))
					.append("</div>\n");
		}

		sb.append(TABLE_OPEN)
				.append("<th class=\"num\">#</th><th>Method</th><th class=\"num\">Energy (J)</th><th class=\"num\">Share</th>")
				.append(THEAD_CLOSE);

		// Merge app methods that are missing from the all-methods report so they
		// are always visible and can be tagged with the app-method CSS class.
		List<EnergyMeasurement> mergedMeasurements = new ArrayList<>(displayReport.measurements());
		if (hasFilter) {
			Set<String> allMethodNames = mergedMeasurements.stream()
					.map(EnergyMeasurement::methodName)
					.collect(Collectors.toSet());
			for (EnergyMeasurement am : methodLevelReports.appReport().measurements()) {
				if (!allMethodNames.contains(am.methodName())) {
					mergedMeasurements.add(am);
				}
			}
		}

		double total = displayReport.totalEnergyJoules();
		int rank = 0;
		for (EnergyMeasurement m : mergedMeasurements.stream()
				.sorted(Comparator.comparingDouble(EnergyMeasurement::energyJoules).reversed())
				.toList()) {
			rank++;
			double pct = total > 0 ? (m.energyJoules() / total) * 100 : 0;
			boolean isApp = appMethods.contains(m.methodName());
			sb.append("      <tr class=\"method-row")
					.append(isApp ? " app-method" : "")
					.append("\"")
					.append(" data-rank=\"")
					.append(rank)
					.append("\">");
			sb.append(TD_NUM_OPEN)
					.append(rank)
					.append(TD_CLOSE)
					.append("<td title=\"")
					.append(escHtml(m.methodName()))
					.append("\"><code>")
					.append(escHtml(leafOf(m.methodName())))
					.append(TD_CODE_CLOSE)
					.append(TD_NUM_OPEN)
					.append(String.format(FMT_DECIMAL_2, m.energyJoules()))
					.append(TD_CLOSE)
					.append(TD_NUM_OPEN)
					.append(String.format(FMT_PERCENT_1, pct))
					.append(TD_CLOSE)
					.append(TR_CLOSE);
		}
		sb.append("    </tbody></table>\n");

		sb.append(DIV_CLOSE);
		return sb.toString();
	}

	private String buildWorkloadCard(WorkloadStats stats, double totalEnergyJoules) {
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Workload Profile</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Tool", stats.tool()));
		sb.append(metric(LABEL_DURATION, stats.durationSeconds() + " s"));

		if (stats.hasRequestCounts()) {
			sb.append(metric("Total Requests", String.valueOf(stats.totalRequests())));
			sb.append(metric("Failed Requests", String.valueOf(Math.max(0, stats.failedRequests()))));

			if (!Double.isNaN(stats.requestsPerSecond())) {
				sb.append(metric("Throughput",
						String.format("%.1f %s", stats.requestsPerSecond(), stats.throughputUnit())));
			}
		} else {
			sb.append(metric("Requests", "N/A"));
		}

		double mjPerReq = stats.energyPerRequestMillijoules(totalEnergyJoules);
		if (!Double.isNaN(mjPerReq)) {
			sb.append(metric("Energy / Request", String.format("%.3f mJ", mjPerReq)));
		} else if (stats.durationSeconds() > 0) {
			double avgWatts = totalEnergyJoules / stats.durationSeconds();
			sb.append(metric("Avg Power", String.format("%.2f W", avgWatts)));
		}

		sb.append(METRICS_CLOSE);

		if (!stats.hasRequestCounts()) {
			sb.append("    <div class=\"note\">")
					.append("Request counts are not available for external tools. ")
					.append("See the tool's own output for throughput and latency details.")
					.append("</div>\n");
		}

		sb.append(DIV_CLOSE);
		return sb.toString();
	}

	/**
	 * Renders a self-contained inline-SVG line chart of the historical trend. Two
	 * series
	 * are drawn when energy-per-request data is available across runs: total Joules
	 * (left, cyan) and mJ/req (right, magenta dashed). The most recent point is
	 * highlighted; X-axis labels are evenly spaced timestamps.
	 */
	private String buildTrendChartCard(List<TrendEntry> entries) {
		final int width = 760;
		final int height = 220;
		final int padL = 56;
		final int padR = 56;
		final int padT = 16;
		final int padB = 36;
		final int plotW = width - padL - padR;
		final int plotH = height - padT - padB;
		final int n = entries.size();

		double minTotal = Double.POSITIVE_INFINITY;
		double maxTotal = Double.NEGATIVE_INFINITY;
		double minPerReq = Double.POSITIVE_INFINITY;
		double maxPerReq = Double.NEGATIVE_INFINITY;
		boolean anyPerReq = false;
		for (TrendEntry e : entries) {
			minTotal = Math.min(minTotal, e.totalEnergyJoules());
			maxTotal = Math.max(maxTotal, e.totalEnergyJoules());
			Double pr = e.energyPerRequestMillijoules();
			if (pr != null && !pr.isNaN() && !pr.isInfinite()) {
				anyPerReq = true;
				minPerReq = Math.min(minPerReq, pr);
				maxPerReq = Math.max(maxPerReq, pr);
			}
		}
		// Avoid zero-range axes — pad by 5 % of the value (or an absolute floor).
		double totalRange = Math.max(maxTotal - minTotal, Math.max(maxTotal * 0.05, 1e-9));
		double minTotalAxis = minTotal - totalRange * 0.05;
		double maxTotalAxis = maxTotal + totalRange * 0.05;
		double perReqRange = anyPerReq ? Math.max(maxPerReq - minPerReq, Math.max(maxPerReq * 0.05, 1e-9)) : 1.0;
		double minPerReqAxis = anyPerReq ? minPerReq - perReqRange * 0.05 : 0;
		double maxPerReqAxis = anyPerReq ? maxPerReq + perReqRange * 0.05 : 1;

		IntFunction<Double> xAt = i -> n == 1 ? padL + plotW / 2.0 : padL + (plotW * (double) i) / (n - 1);
		DoubleUnaryOperator yTotal = v -> padT + plotH - ((v - minTotalAxis) / (maxTotalAxis - minTotalAxis)) * plotH;
		DoubleUnaryOperator yPerReq = v -> padT + plotH
				- ((v - minPerReqAxis) / (maxPerReqAxis - minPerReqAxis)) * plotH;

		StringBuilder totalPts = new StringBuilder();
		StringBuilder perReqPts = new StringBuilder();
		for (int i = 0; i < n; i++) {
			TrendEntry e = entries.get(i);
			double x = xAt.apply(i);
			if (totalPts.length() > 0) {
				totalPts.append(' ');
			}
			totalPts.append(String.format(Locale.ROOT, "%.1f,%.1f", x, yTotal.applyAsDouble(e.totalEnergyJoules())));
			Double pr = e.energyPerRequestMillijoules();
			if (anyPerReq && pr != null && !pr.isNaN() && !pr.isInfinite()) {
				if (perReqPts.length() > 0) {
					perReqPts.append(' ');
				}
				perReqPts.append(String.format(Locale.ROOT, "%.1f,%.1f", x, yPerReq.applyAsDouble(pr)));
			}
		}

		DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Energy Trend (last ").append(n).append(" runs)</h2>\n");
		sb.append("    <svg viewBox=\"0 0 ").append(width).append(' ').append(height).append("\" ");
		sb.append(
				"style=\"width:100%;height:auto;font-family:inherit;\" role=\"img\" aria-label=\"Energy trend chart\">\n");
		// Plot area background grid: 4 horizontal lines.
		for (int i = 0; i <= 4; i++) {
			double y = padT + (plotH * i) / 4.0;
			sb.append("      <line x1=\"")
					.append(padL)
					.append("\" x2=\"")
					.append(padL + plotW)
					.append("\" y1=\"")
					.append(String.format(Locale.ROOT, "%.1f", y))
					.append("\" y2=\"")
					.append(String.format(Locale.ROOT, "%.1f", y))
					.append("\" stroke=\"var(--border)\" stroke-dasharray=\"2,3\" />\n");
		}
		// Y-axis labels (left, total Joules).
		for (int i = 0; i <= 4; i++) {
			double v = maxTotalAxis - ((maxTotalAxis - minTotalAxis) * i) / 4.0;
			double y = padT + (plotH * i) / 4.0;
			sb.append("      <text x=\"")
					.append(padL - 6)
					.append("\" y=\"")
					.append(String.format(Locale.ROOT, "%.1f", y + 4))
					.append("\" text-anchor=\"end\" fill=\"var(--muted)\" font-size=\"10\">")
					.append(String.format(Locale.ROOT, "%.2f J", v))
					.append("</text>\n");
		}
		// Y-axis labels (right, mJ/req) — only if applicable.
		if (anyPerReq) {
			for (int i = 0; i <= 4; i++) {
				double v = maxPerReqAxis - ((maxPerReqAxis - minPerReqAxis) * i) / 4.0;
				double y = padT + (plotH * i) / 4.0;
				sb.append("      <text x=\"")
						.append(padL + plotW + 6)
						.append("\" y=\"")
						.append(String.format(Locale.ROOT, "%.1f", y + 4))
						.append("\" text-anchor=\"start\" fill=\"var(--muted)\" font-size=\"10\">")
						.append(String.format(Locale.ROOT, "%.2f mJ", v))
						.append("</text>\n");
			}
		}
		// X-axis labels: at most 5 evenly-spaced timestamps.
		int xLabelCount = Math.min(5, n);
		for (int i = 0; i < xLabelCount; i++) {
			int idx = xLabelCount == 1 ? 0 : (int) Math.round((double) i * (n - 1) / (xLabelCount - 1));
			double x = xAt.apply(idx);
			sb.append("      <text x=\"")
					.append(String.format(Locale.ROOT, "%.1f", x))
					.append("\" y=\"")
					.append(height - padB + 18)
					.append("\" text-anchor=\"middle\" fill=\"var(--muted)\" font-size=\"10\">")
					.append(escHtml(dateFmt.format(entries.get(idx).timestamp())))
					.append("</text>\n");
		}
		// Total energy polyline.
		sb.append("      <polyline fill=\"none\" stroke=\"var(--cyan)\" stroke-width=\"2\" points=\"")
				.append(totalPts)
				.append("\" />\n");
		// Total energy point markers with tooltips.
		for (int i = 0; i < n; i++) {
			TrendEntry e = entries.get(i);
			double x = xAt.apply(i);
			double y = yTotal.applyAsDouble(e.totalEnergyJoules());
			boolean last = i == n - 1;
			sb.append("      <circle cx=\"")
					.append(String.format(Locale.ROOT, "%.1f", x))
					.append("\" cy=\"")
					.append(String.format(Locale.ROOT, "%.1f", y))
					.append("\" r=\"")
					.append(last ? 4 : 2.5)
					.append("\" fill=\"var(--cyan)\"><title>")
					.append(escHtml(e.runId()))
					.append(" · ")
					.append(String.format(Locale.ROOT, "%.3f J", e.totalEnergyJoules()));
			if (e.energyPerRequestMillijoules() != null) {
				sb.append(String.format(Locale.ROOT, " · %.3f mJ/req", e.energyPerRequestMillijoules()));
			}
			sb.append("</title></circle>\n");
		}
		// Per-request polyline (dashed magenta) when applicable.
		if (anyPerReq && perReqPts.length() > 0) {
			sb.append(
					"      <polyline fill=\"none\" stroke=\"var(--magenta)\" stroke-width=\"1.5\" stroke-dasharray=\"4,3\" points=\"")
					.append(perReqPts)
					.append("\" />\n");
		}
		sb.append("    </svg>\n");
		// Legend.
		sb.append("    <div class=\"note\" style=\"display:flex;gap:1.5em;flex-wrap:wrap;\">");
		sb.append(
				"<span><span style=\"display:inline-block;width:10px;height:2px;background:var(--cyan);vertical-align:middle;\"></span> Total energy (J, left axis)</span>");
		if (anyPerReq) {
			sb.append(
					"<span><span style=\"display:inline-block;width:10px;height:2px;background:var(--magenta);vertical-align:middle;\"></span> Energy / request (mJ, right axis)</span>");
		}
		sb.append("</div>\n");
		sb.append(DIV_CLOSE);
		return sb.toString();
	}

	private String buildPowerSourceCard(PowerSource powerSource) {
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Measurement Assumptions</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Power Source", powerSource.label()));
		sb.append(METRICS_CLOSE);
		sb.append("    <div class=\"note\">").append(escHtml(powerSource.description())).append("</div>\n");
		sb.append(DIV_CLOSE);
		return sb.toString();
	}

	private String buildComparisonCard(ComparisonResult c) {
		ComparisonStatus status = c.overallStatus();
		String cls = switch (status) {
			case IMPROVED -> "improved";
			case REGRESSED -> "regressed";
			default -> "unchanged";
		};
		String label = switch (status) {
			case IMPROVED -> "▼ Improved";
			case REGRESSED -> "▲ Regressed";
			default -> "→ Stable";
		};

		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Baseline vs Current</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Baseline", String.format(FMT_ENERGY_JOULES, c.baselineTotalJoules())));
		sb.append(metric("Current", String.format(FMT_ENERGY_JOULES, c.currentTotalJoules())));
		sb.append(metric("Change", String.format("%+.2f%%", c.totalDeltaPercent())));
		sb.append(metric("Threshold", String.format("±%.1f%%", c.threshold())));
		sb.append("    <div class=\"metric\"><div class=\"label\">Status</div>")
				.append("<div class=\"value ")
				.append(cls)
				.append("\">")
				.append(label)
				.append("</div></div>\n    </div>\n");

		if (c.isFailed()) {
			sb.append("    <div class=\"alert alert-danger\">Energy consumption increased by ")
					.append(String.format("%.2f%%", c.totalDeltaPercent()))
					.append(", exceeding the ±")
					.append(String.format(FMT_PERCENT_1, c.threshold()))
					.append(" threshold.</div>\n");
		}

		List<MethodComparison> regressions = c.methodComparisons()
				.stream()
				.filter(mc -> mc.isRegressed(c.threshold()))
				.sorted(Comparator.comparingDouble(MethodComparison::deltaPercent).reversed())
				.limit(topN)
				.toList();

		if (!regressions.isEmpty()) {
			sb.append("    <h3>Components with Increased Consumption</h3>\n")
					.append(TABLE_OPEN)
					.append("<th>Component</th><th class=\"num\">Baseline (J)</th><th class=\"num\">Current (J)</th><th class=\"num\">Change</th>")
					.append(THEAD_CLOSE);
			regressions.forEach(mc -> sb.append(TR_OPEN)
					.append(TD_CODE_OPEN)
					.append(escHtml(mc.methodName()))
					.append(TD_CODE_CLOSE)
					.append(TD_NUM_OPEN)
					.append(String.format(FMT_DECIMAL_2, mc.baselineEnergyJoules()))
					.append(TD_CLOSE)
					.append(TD_NUM_OPEN)
					.append(String.format(FMT_DECIMAL_2, mc.currentEnergyJoules()))
					.append(TD_CLOSE)
					.append("<td class=\"num\"><span class=\"badge badge-red\">")
					.append(String.format("%+.2f%%", mc.deltaPercent()))
					.append("</span></td>")
					.append(TR_CLOSE));
			sb.append("    </tbody></table>\n");
		}
		sb.append(DIV_CLOSE);
		return sb.toString();
	}

	private String metric(String label, String value) {
		return "    <div class=\"metric\"><div class=\"label\">" + escHtml(label) + "</div><div class=\"value\">"
				+ escHtml(value) + "</div></div>\n";
	}

	private String escHtml(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
	}

	/**
	 * Returns the leaf (innermost) method from a Joular Code Java call-branch
	 * string. The
	 * branch format is {@code parent;...;leaf} where each segment is a
	 * fully-qualified
	 * method name. When no semicolon is present the whole string is returned
	 * unchanged.
	 */
	private static String leafOf(String branch) {
		if (branch == null) {
			return "";
		}
		int lastSemi = branch.lastIndexOf(';');
		return lastSemi >= 0 ? branch.substring(lastSemi + 1) : branch;
	}

	private String htmlHead(String title) {
		return """
				<!DOCTYPE html>
				<html lang="en" data-theme="dark">
				<head>
				  <meta charset="UTF-8">
				  <meta name="viewport" content="width=device-width, initial-scale=1.0">
				  <title>%s</title>
				  <style>
				    :root,[data-theme="dark"]{
				      --cyan:#00e5ff;--magenta:#ff00e5;--green:#39ff14;--yellow:#ffe600;
				      --red:#ff3366;--bg:#0a0a0a;--card:#141414;--card-alt:#181820;
				      --border:#222;--border-glow:#00e5ff18;--text:#d4d4d4;--muted:#777;
				      --white:#f0f0f0;
				      --badge-green-bg:#39ff1412;--badge-green-border:#39ff1430;
				      --badge-red-bg:#ff336612;--badge-red-border:#ff336630;
				      --badge-yellow-bg:#ffe60012;--badge-yellow-border:#ffe60030;
				      --code-bg:#0e0e12;--row-hover:#ffffff06;
				      --hero-bg:linear-gradient(135deg,#0a0a14 0%%,#0d0d1a 50%%,#0a0a14 100%%);
				      --alert-danger-bg:#ff336612;--alert-danger-border:#ff336630}
				    [data-theme="light"]{
				      --cyan:#0097a7;--magenta:#c2185b;--green:#2e7d32;--yellow:#f9a825;
				      --red:#c62828;--bg:#f5f5f5;--card:#ffffff;--card-alt:#fafafa;
				      --border:#ddd;--border-glow:#0097a718;--text:#333;--muted:#888;
				      --white:#111;
				      --badge-green-bg:#e8f5e9;--badge-green-border:#a5d6a7;
				      --badge-red-bg:#ffebee;--badge-red-border:#ef9a9a;
				      --badge-yellow-bg:#fff8e1;--badge-yellow-border:#ffe082;
				      --code-bg:#f0f0f0;--row-hover:#00000006;
				      --hero-bg:linear-gradient(135deg,#e0f7fa 0%%,#f3e5f5 50%%,#e0f7fa 100%%);
				      --alert-danger-bg:#ffebee;--alert-danger-border:#ef9a9a}
				    @media(prefers-color-scheme:light){
				      html:not([data-theme="dark"]){
				        --cyan:#0097a7;--magenta:#c2185b;--green:#2e7d32;--yellow:#f9a825;
				        --red:#c62828;--bg:#f5f5f5;--card:#ffffff;--card-alt:#fafafa;
				        --border:#ddd;--border-glow:#0097a718;--text:#333;--muted:#888;
				        --white:#111;
				        --badge-green-bg:#e8f5e9;--badge-green-border:#a5d6a7;
				        --badge-red-bg:#ffebee;--badge-red-border:#ef9a9a;
				        --badge-yellow-bg:#fff8e1;--badge-yellow-border:#ffe082;
				        --code-bg:#f0f0f0;--row-hover:#00000006;
				        --hero-bg:linear-gradient(135deg,#e0f7fa 0%%,#f3e5f5 50%%,#e0f7fa 100%%);
				        --alert-danger-bg:#ffebee;--alert-danger-border:#ef9a9a}}
				    *{box-sizing:border-box}
				    body{font-family:'Segoe UI',system-ui,-apple-system,sans-serif;
				         margin:0;background:var(--bg);color:var(--text);line-height:1.6;
				         transition:background .3s,color .3s}
				    .container{max-width:960px;margin:0 auto;padding:32px 24px}
				    .hero{text-align:center;padding:48px 24px 36px;
				          background:var(--hero-bg);
				          border-bottom:1px solid var(--border);margin-bottom:32px;position:relative}
				    .hero h1{font-size:32px;color:var(--white);margin:0 0 6px;font-weight:700;
				             letter-spacing:-0.5px}
				    .hero h1 span{color:var(--cyan);text-shadow:0 0 20px color-mix(in srgb,var(--cyan) 40%%,transparent)}
				    .hero .tagline{color:var(--muted);font-size:15px;margin:0}
				    .hero .tagline a{color:var(--magenta);text-decoration:none;
				                     border-bottom:1px solid transparent;transition:border-color .2s}
				    .hero .tagline a:hover{border-bottom-color:var(--magenta)}
				    .theme-toggle{position:absolute;top:16px;right:24px;background:var(--card);
				                  border:1px solid var(--border);border-radius:20px;padding:6px 14px;
				                  cursor:pointer;color:var(--muted);font-size:13px;
				                  transition:all .2s}
				    .theme-toggle:hover{border-color:var(--cyan);color:var(--cyan)}
				    h2{font-size:16px;font-weight:600;text-transform:uppercase;letter-spacing:1.5px;
				       color:var(--magenta);margin:0 0 16px;padding-bottom:10px;
				       border-bottom:1px solid var(--border)}
				    h3{color:var(--red);font-size:14px;text-transform:uppercase;letter-spacing:1px;
				       margin:20px 0 12px}
				    .card{background:var(--card);border:1px solid var(--border);border-radius:10px;
				          padding:24px;margin-bottom:20px;transition:border-color .2s,background .3s;
				          overflow-x:auto}
				    .card:hover{border-color:var(--cyan)}
				    .metrics{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:4px}
				    .metric{flex:1;min-width:130px;padding:14px 18px;
				            background:var(--bg);border:1px solid var(--border);border-radius:8px;
				            transition:background .3s}
				    .metric .label{font-size:11px;text-transform:uppercase;letter-spacing:0.8px;
				                   color:var(--muted);margin-bottom:6px}
				    .metric .value{font-size:22px;font-weight:700;color:var(--white);
				                   letter-spacing:-0.3px}
				    .improved{color:var(--green)!important;text-shadow:0 0 10px color-mix(in srgb,var(--green) 30%%,transparent)}
				    .regressed{color:var(--red)!important;text-shadow:0 0 10px color-mix(in srgb,var(--red) 30%%,transparent)}
				    .unchanged{color:var(--yellow)!important;text-shadow:0 0 10px color-mix(in srgb,var(--yellow) 30%%,transparent)}
				    table{width:100%%;border-collapse:collapse;font-size:13px;margin-top:4px}
				    th{background:var(--bg);text-align:left;padding:10px 14px;
				       border-bottom:2px solid var(--border);color:var(--cyan);
				       font-size:11px;text-transform:uppercase;letter-spacing:0.8px;font-weight:600}
				    td{padding:10px 14px;border-bottom:1px solid var(--border);color:var(--text)}
				    tr:last-child td{border-bottom:none}
				    tr:hover td{background:var(--row-hover)}
				    .badge{display:inline-block;padding:3px 10px;border-radius:20px;
				           font-size:12px;font-weight:600;letter-spacing:0.3px}
				    .badge-green{background:var(--badge-green-bg);color:var(--green);border:1px solid var(--badge-green-border)}
				    .badge-red{background:var(--badge-red-bg);color:var(--red);border:1px solid var(--badge-red-border)}
				    .badge-yellow{background:var(--badge-yellow-bg);color:var(--yellow);border:1px solid var(--badge-yellow-border)}
				    .note{padding:14px 18px;border-radius:8px;margin-top:16px;font-size:13px;
				          color:var(--muted);background:var(--bg);border:1px solid var(--border);
				          line-height:1.5}
				    .alert{padding:14px 18px;border-radius:8px;margin-top:16px;font-weight:500;
				           line-height:1.5}
				    .alert-danger{background:var(--alert-danger-bg);border:1px solid var(--alert-danger-border);color:var(--red)}
				    code{background:var(--code-bg);padding:2px 7px;border-radius:4px;font-size:12px;
				         color:var(--cyan);font-family:'JetBrains Mono','Fira Code',monospace}
				    td code{word-break:break-all}
				    .num{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
				 th.num{text-align:right}
				   .footer{text-align:center;color:var(--muted);font-size:12px;padding:28px 0 12px;
				            border-top:1px solid var(--border);margin-top:36px}
				    .footer a{color:var(--cyan);text-decoration:none}
				    .footer a:hover{text-decoration:underline}
				    .filter-toggle{background:var(--card-alt);border:1px solid var(--border);
				                   border-radius:20px;padding:6px 16px;cursor:pointer;
				                   color:var(--cyan);font-size:13px;font-weight:600;
				                   transition:all .2s;letter-spacing:0.3px}
				    .filter-toggle:hover{border-color:var(--cyan);background:var(--bg)}
				    .filter-toggle.active{background:var(--badge-green-bg);border-color:var(--badge-green-border);
				                          color:var(--green)}
				  </style>
				</head>
				<body>
				"""
				.formatted(escHtml(title));
	}

	private String htmlFooter() {
		return "  <div class=\"footer\">" + "Made with \uD83E\uDE77 by Patrick Baumgartner \u2014 "
				+ "<a href=\"https://github.com/patbaumgartner/greener-spring-boot\">Greener Spring Boot</a>"
				+ "</div>\n";
	}

	private String htmlScript() {
		return """
				</div>
				<script>
				function toggleTheme(){
				  var html=document.documentElement;
				  var t=html.getAttribute('data-theme')==='dark'?'light':'dark';
				  html.setAttribute('data-theme',t);
				}
				function toggleMethodFilter(){
				  var btn=document.getElementById('methodFilterBtn');
				  var hint=document.getElementById('methodFilterHint');
				  var rows=document.querySelectorAll('.method-row');
				  var active=btn.classList.toggle('active');
				  var rank=0;
				  for(var i=0;i<rows.length;i++){
				    if(active && !rows[i].classList.contains('app-method')){
				      rows[i].style.display='none';
				    } else {
				      rows[i].style.display='';
				      rank++;
				      rows[i].cells[0].textContent=rank;
				    }
				  }
				  btn.textContent=active?'Show All Methods':'Show App Only';
				  if(hint) hint.textContent=active
				    ?'Showing application classes only. Click to show all methods.'
				    :'Showing all methods. Click to filter to application classes only.';
				}
				</script>
				</body>
				</html>""";
	}

}
