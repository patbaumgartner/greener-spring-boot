package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates a self-contained HTML report showing current energy measurements and
 * optionally a comparison against a stored baseline.
 *
 * <p>
 * The report is written to {@code {outputDir}/greener-energy-report.html}.
 */
public class HtmlReporter {

	private static final Logger LOG = Logger.getLogger(HtmlReporter.class.getName());

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
		.withZone(ZoneId.systemDefault());

	private final int topN;

	public HtmlReporter() {
		this(20);
	}

	public HtmlReporter(int topN) {
		this.topN = topN;
	}

	/** Generates a report without workload stats. */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, Path outputDir) throws IOException {
		return generateReport(current, comparison, null, null, outputDir);
	}

	/** Generates a report including use-case energy metrics from the workload. */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			Path outputDir) throws IOException {
		return generateReport(current, comparison, workloadStats, null, outputDir);
	}

	/** Generates a report including power source assumptions. */
	public Path generateReport(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource, Path outputDir) throws IOException {
		Files.createDirectories(outputDir);
		Path reportFile = outputDir.resolve("greener-energy-report.html");
		Files.writeString(reportFile, buildHtml(current, comparison, workloadStats, powerSource));
		LOG.info("HTML report written to: " + reportFile);
		return reportFile;
	}

	private String buildHtml(EnergyReport current, ComparisonResult comparison, WorkloadStats workloadStats,
			PowerSource powerSource) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				<!DOCTYPE html>
				<html lang="en" data-theme="dark">
				<head>
				  <meta charset="UTF-8">
				  <meta name="viewport" content="width=device-width, initial-scale=1.0">
				  <title>Greener Spring Boot — Energy Report</title>
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
				      --hero-bg:linear-gradient(135deg,#0a0a14 0%,#0d0d1a 50%,#0a0a14 100%);
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
				      --hero-bg:linear-gradient(135deg,#e0f7fa 0%,#f3e5f5 50%,#e0f7fa 100%);
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
				        --hero-bg:linear-gradient(135deg,#e0f7fa 0%,#f3e5f5 50%,#e0f7fa 100%);
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
				    .hero h1 span{color:var(--cyan);text-shadow:0 0 20px color-mix(in srgb,var(--cyan) 40%,transparent)}
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
				          padding:24px;margin-bottom:20px;transition:border-color .2s,background .3s}
				    .card:hover{border-color:var(--cyan)}
				    .metrics{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:4px}
				    .metric{flex:1;min-width:130px;padding:14px 18px;
				            background:var(--bg);border:1px solid var(--border);border-radius:8px;
				            transition:background .3s}
				    .metric .label{font-size:11px;text-transform:uppercase;letter-spacing:0.8px;
				                   color:var(--muted);margin-bottom:6px}
				    .metric .value{font-size:22px;font-weight:700;color:var(--white);
				                   letter-spacing:-0.3px}
				    .improved{color:var(--green)!important;text-shadow:0 0 10px color-mix(in srgb,var(--green) 30%,transparent)}
				    .regressed{color:var(--red)!important;text-shadow:0 0 10px color-mix(in srgb,var(--red) 30%,transparent)}
				    .unchanged{color:var(--yellow)!important;text-shadow:0 0 10px color-mix(in srgb,var(--yellow) 30%,transparent)}
				    table{width:100%;border-collapse:collapse;font-size:13px;margin-top:4px}
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
				    .footer{text-align:center;color:var(--muted);font-size:12px;padding:28px 0 12px;
				            border-top:1px solid var(--border);margin-top:36px}
				    .footer a{color:var(--cyan);text-decoration:none}
				    .footer a:hover{text-decoration:underline}
				  </style>
				</head>
				<body>
				<div class="hero">
				  <button class="theme-toggle" onclick="toggleTheme()" title="Toggle light/dark mode">&#9681; Theme</button>
				  <h1><span>&#9889;</span> Greener Spring Boot</h1>
				  <p class="tagline">Energy Report — powered by
				    <a href="https://www.noureddine.org/research/joular/joularcore">Joular Core</a>
				  </p>
				</div>
				<div class="container">
				""");

		// Run summary card
		sb.append("  <div class=\"card\">\n    <h2>Measurement Summary</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Run", current.runId()));
		sb.append(metric("Measured at", FORMATTER.format(current.timestamp())));
		sb.append(metric("Duration", current.durationSeconds() + " s"));
		sb.append(metric("Total Energy", String.format("%.2f J", current.totalEnergyJoules())));
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
		}
		else if (comparison != null) {
			sb.append("  <div class=\"card\"><div class=\"note\">"
					+ "No baseline found — this run will be saved as the new baseline.</div></div>\n");
		}

		// Measurements table
		if (!current.measurements().isEmpty()) {
			sb.append("  <div class=\"card\">\n    <h2>Energy Breakdown</h2>\n");
			boolean hasComparison = comparison != null && comparison.overallStatus() != ComparisonStatus.NO_BASELINE;
			sb.append("    <table><thead><tr>").append("<th>Component</th><th>Energy (J)</th><th>Share</th>");
			if (hasComparison)
				sb.append("<th>Change</th>");
			sb.append("</tr></thead><tbody>\n");

			double total = current.totalEnergyJoules();
			for (EnergyMeasurement m : current.topMethods(topN)) {
				double pct = total > 0 ? (m.energyJoules() / total) * 100 : 0;
				sb.append("      <tr>")
					.append("<td><code>")
					.append(escHtml(m.methodName()))
					.append("</code></td>")
					.append("<td>")
					.append(String.format("%.2f", m.energyJoules()))
					.append("</td>")
					.append("<td>")
					.append(String.format("%.1f%%", pct))
					.append("</td>");
				if (hasComparison) {
					comparison.methodComparisons()
						.stream()
						.filter(mc -> mc.methodName().equals(m.methodName()))
						.findFirst()
						.ifPresentOrElse(mc -> {
							String cls = mc.deltaPercent() > comparison.threshold() ? "badge-red"
									: mc.deltaPercent() < 0 ? "badge-green" : "badge-yellow";
							sb.append("<td><span class=\"badge ")
								.append(cls)
								.append("\">")
								.append(String.format("%+.2f%%", mc.deltaPercent()))
								.append("</span></td>");
						}, () -> sb.append("<td>—</td>"));
				}
				sb.append("</tr>\n");
			}
			sb.append("    </tbody></table>\n  </div>\n");
		}

		sb.append("  <div class=\"footer\">")
			.append("<a href=\"https://github.com/patbaumgartner/greener-spring-boot\">Greener Spring Boot</a>")
			.append(" — making software sustainability visible, one commit at a time</div>\n");
		sb.append("""
				</div>
				<script>
				function toggleTheme(){
				  var html=document.documentElement;
				  var t=html.getAttribute('data-theme')==='dark'?'light':'dark';
				  html.setAttribute('data-theme',t);
				}
				</script>
				</body>
				</html>""");
		return sb.toString();
	}

	private String buildWorkloadCard(WorkloadStats stats, double totalEnergyJoules) {
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Workload Profile</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Tool", stats.tool()));
		sb.append(metric("Duration", stats.durationSeconds() + " s"));

		if (stats.hasRequestCounts()) {
			sb.append(metric("Total Requests", String.valueOf(stats.totalRequests())));
			sb.append(metric("Failed Requests", String.valueOf(Math.max(0, stats.failedRequests()))));

			if (!Double.isNaN(stats.requestsPerSecond())) {
				sb.append(metric("Throughput",
						String.format("%.1f %s", stats.requestsPerSecond(), stats.throughputUnit())));
			}
		}
		else {
			sb.append(metric("Requests", "N/A"));
		}

		double mjPerReq = stats.energyPerRequestMillijoules(totalEnergyJoules);
		if (!Double.isNaN(mjPerReq)) {
			sb.append(metric("Energy / Request", String.format("%.3f mJ", mjPerReq)));
		}
		else if (stats.durationSeconds() > 0) {
			double avgWatts = totalEnergyJoules / stats.durationSeconds();
			sb.append(metric("Avg Power", String.format("%.2f W", avgWatts)));
		}

		sb.append("    </div>\n");

		if (!stats.hasRequestCounts()) {
			sb.append("    <div class=\"note\">")
				.append("Request counts are not available for external tools. ")
				.append("See the tool's own output for throughput and latency details.")
				.append("</div>\n");
		}

		sb.append("  </div>\n");
		return sb.toString();
	}

	private String buildPowerSourceCard(PowerSource powerSource) {
		StringBuilder sb = new StringBuilder();
		sb.append("  <div class=\"card\">\n    <h2>Measurement Assumptions</h2>\n    <div class=\"metrics\">\n");
		sb.append(metric("Power Source", powerSource.label()));
		sb.append("    </div>\n");
		sb.append("    <div class=\"note\">").append(escHtml(powerSource.description())).append("</div>\n");
		sb.append("  </div>\n");
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
		sb.append(metric("Baseline", String.format("%.2f J", c.baselineTotalJoules())));
		sb.append(metric("Current", String.format("%.2f J", c.currentTotalJoules())));
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
				.append(String.format("%.1f%%", c.threshold()))
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
				.append("    <table><thead><tr>")
				.append("<th>Component</th><th>Baseline (J)</th><th>Current (J)</th><th>Change</th>")
				.append("</tr></thead><tbody>\n");
			regressions.forEach(mc -> sb.append("      <tr>")
				.append("<td><code>")
				.append(escHtml(mc.methodName()))
				.append("</code></td>")
				.append("<td>")
				.append(String.format("%.2f", mc.baselineEnergyJoules()))
				.append("</td>")
				.append("<td>")
				.append(String.format("%.2f", mc.currentEnergyJoules()))
				.append("</td>")
				.append("<td><span class=\"badge badge-red\">")
				.append(String.format("%+.2f%%", mc.deltaPercent()))
				.append("</span></td>")
				.append("</tr>\n"));
			sb.append("    </tbody></table>\n");
		}
		sb.append("  </div>\n");
		return sb.toString();
	}

	private String metric(String label, String value) {
		return "    <div class=\"metric\"><div class=\"label\">" + escHtml(label) + "</div><div class=\"value\">"
				+ escHtml(value) + "</div></div>\n";
	}

	private String escHtml(String s) {
		if (s == null)
			return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

}
