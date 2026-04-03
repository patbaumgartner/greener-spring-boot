package com.patbaumgartner.greener.core.reporter;

import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;

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
 * Generates a self-contained HTML report showing current energy measurements
 * and optionally a comparison against a stored baseline.
 *
 * <p>The report is written to {@code {outputDir}/greener-energy-report.html}.
 */
public class HtmlReporter {

    private static final Logger LOG = Logger.getLogger(HtmlReporter.class.getName());
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final int topN;

    public HtmlReporter() {
        this(20);
    }

    public HtmlReporter(int topN) {
        this.topN = topN;
    }

    /** Generates a report without workload stats. */
    public Path generateReport(EnergyReport current, ComparisonResult comparison,
                               Path outputDir) throws IOException {
        return generateReport(current, comparison, null, outputDir);
    }

    /** Generates a report including use-case energy metrics from the workload. */
    public Path generateReport(EnergyReport current, ComparisonResult comparison,
                               WorkloadStats workloadStats, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path reportFile = outputDir.resolve("greener-energy-report.html");
        Files.writeString(reportFile, buildHtml(current, comparison, workloadStats));
        LOG.info("HTML report written to: " + reportFile);
        return reportFile;
    }

    private String buildHtml(EnergyReport current, ComparisonResult comparison,
                             WorkloadStats workloadStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>greener-spring-boot — Energy Report</title>
                  <style>
                    body{font-family:system-ui,sans-serif;margin:0;background:#f6f8fa;color:#24292f}
                    .container{max-width:980px;margin:32px auto;padding:0 24px}
                    h1{color:#1a7f37;margin-bottom:4px}
                    .subtitle{color:#57606a;font-size:14px;margin-bottom:24px}
                    h2{border-bottom:1px solid #d0d7de;padding-bottom:8px;color:#0550ae}
                    .card{background:#fff;border:1px solid #d0d7de;border-radius:6px;padding:20px;margin-bottom:24px}
                    .metrics{display:flex;flex-wrap:wrap;gap:12px;margin-bottom:8px}
                    .metric{padding:12px 20px;background:#f6f8fa;border:1px solid #d0d7de;
                            border-radius:6px;min-width:140px}
                    .metric .label{font-size:12px;color:#57606a;margin-bottom:4px}
                    .metric .value{font-size:20px;font-weight:600}
                    .improved{color:#1a7f37}.regressed{color:#cf222e}.unchanged{color:#9a6700}
                    table{width:100%;border-collapse:collapse;font-size:14px}
                    th{background:#f6f8fa;text-align:left;padding:8px 12px;border:1px solid #d0d7de}
                    td{padding:8px 12px;border:1px solid #d0d7de}
                    tr:hover td{background:#f0f6ff}
                    .badge{display:inline-block;padding:2px 8px;border-radius:12px;font-size:12px;font-weight:600}
                    .badge-green{background:#dafbe1;color:#116329}
                    .badge-red{background:#ffebe9;color:#82071e}
                    .badge-yellow{background:#fff8c5;color:#7d4e00}
                    .alert{padding:12px 16px;border-radius:6px;margin-top:16px;font-weight:500}
                    .alert-danger{background:#ffebe9;border:1px solid #ff818266;color:#82071e}
                    .alert-info{background:#ddf4ff;border:1px solid #54aeff66;color:#0550ae}
                    code{background:#f6f8fa;padding:1px 5px;border-radius:3px;font-size:13px}
                  </style>
                </head>
                <body>
                <div class="container">
                  <h1>⚡ greener-spring-boot</h1>
                  <div class="subtitle">Energy Consumption Report — powered by
                    <a href="https://www.noureddine.org/research/joular/joularcore">Joular Core</a>
                  </div>
                """);

        // Run summary card
        sb.append("  <div class=\"card\">\n    <h2>Current Run</h2>\n    <div class=\"metrics\">\n");
        sb.append(metric("Run ID",       current.runId()));
        sb.append(metric("Timestamp",    FORMATTER.format(current.timestamp())));
        sb.append(metric("Duration",     current.durationSeconds() + " s"));
        sb.append(metric("Total Energy", String.format("%.4f J", current.totalEnergyJoules())));
        sb.append("    </div>\n  </div>\n");

        // Use-case energy card
        if (workloadStats != null) {
            sb.append(buildWorkloadCard(workloadStats, current.totalEnergyJoules()));
        }

        // Comparison card
        if (comparison != null && comparison.overallStatus() != ComparisonStatus.NO_BASELINE) {
            sb.append(buildComparisonCard(comparison));
        } else if (comparison != null) {
            sb.append("  <div class=\"card alert alert-info\">"
                    + "ℹ No baseline found — this run will be saved as the new baseline.</div>\n");
        }

        // Measurements table
        if (!current.measurements().isEmpty()) {
            sb.append("  <div class=\"card\">\n    <h2>Top ").append(topN)
                    .append(" Energy Measurements</h2>\n");
            boolean hasComparison = comparison != null
                    && comparison.overallStatus() != ComparisonStatus.NO_BASELINE;
            sb.append("    <table><thead><tr>")
                    .append("<th>Name</th><th>Energy (J)</th><th>% of Total</th>");
            if (hasComparison) sb.append("<th>vs Baseline</th>");
            sb.append("</tr></thead><tbody>\n");

            double total = current.totalEnergyJoules();
            for (EnergyMeasurement m : current.topMethods(topN)) {
                double pct = total > 0 ? (m.energyJoules() / total) * 100 : 0;
                sb.append("      <tr>")
                        .append("<td><code>").append(escHtml(m.methodName())).append("</code></td>")
                        .append("<td>").append(String.format("%.4f", m.energyJoules())).append("</td>")
                        .append("<td>").append(String.format("%.1f%%", pct)).append("</td>");
                if (hasComparison) {
                    comparison.methodComparisons().stream()
                            .filter(mc -> mc.methodName().equals(m.methodName()))
                            .findFirst()
                            .ifPresentOrElse(mc -> {
                                String cls = mc.deltaPercent() > comparison.threshold()
                                        ? "badge-red"
                                        : mc.deltaPercent() < 0 ? "badge-green" : "badge-yellow";
                                sb.append("<td><span class=\"badge ").append(cls).append("\">")
                                        .append(String.format("%+.2f%%", mc.deltaPercent()))
                                        .append("</span></td>");
                            }, () -> sb.append("<td>—</td>"));
                }
                sb.append("</tr>\n");
            }
            sb.append("    </tbody></table>\n  </div>\n");
        }

        sb.append("</div>\n</body>\n</html>");
        return sb.toString();
    }

    private String buildWorkloadCard(WorkloadStats stats, double totalEnergyJoules) {
        StringBuilder sb = new StringBuilder();
        sb.append("  <div class=\"card\">\n    <h2>Use-Case Energy</h2>\n    <div class=\"metrics\">\n");
        sb.append(metric("Tool",     stats.tool()));
        sb.append(metric("Duration", stats.durationSeconds() + " s"));

        if (stats.hasRequestCounts()) {
            sb.append(metric("Total Requests",  String.valueOf(stats.totalRequests())));
            sb.append(metric("Failed Requests", String.valueOf(Math.max(0, stats.failedRequests()))));

            if (!Double.isNaN(stats.requestsPerSecond())) {
                sb.append(metric("Throughput", String.format("%.1f req/s", stats.requestsPerSecond())));
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

        sb.append("    </div>\n");

        if (!stats.hasRequestCounts()) {
            sb.append("    <div class=\"alert alert-info\">")
                    .append("ℹ Request counts are not available for external tools. ")
                    .append("See the tool's own output in the CI log for throughput and latency details.")
                    .append("</div>\n");
        }

        sb.append("  </div>\n");
        return sb.toString();
    }

    private String buildComparisonCard(ComparisonResult c) {
        ComparisonStatus status = c.overallStatus();
        String cls = switch (status) {
            case IMPROVED  -> "improved";
            case REGRESSED -> "regressed";
            default        -> "unchanged";
        };
        String label = switch (status) {
            case IMPROVED  -> "▼ IMPROVED";
            case REGRESSED -> "▲ REGRESSED";
            default        -> "→ UNCHANGED";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("  <div class=\"card\">\n    <h2>Baseline Comparison</h2>\n    <div class=\"metrics\">\n");
        sb.append(metric("Baseline Total", String.format("%.4f J", c.baselineTotalJoules())));
        sb.append(metric("Current Total",  String.format("%.4f J", c.currentTotalJoules())));
        sb.append(metric("Delta",          String.format("%+.2f%%", c.totalDeltaPercent())));
        sb.append(metric("Threshold",      String.format("±%.1f%%", c.threshold())));
        sb.append("    <div class=\"metric\"><div class=\"label\">Status</div>")
                .append("<div class=\"value ").append(cls).append("\">").append(label)
                .append("</div></div>\n    </div>\n");

        if (c.isFailed()) {
            sb.append("    <div class=\"alert alert-danger\">⚠ Energy regression: ")
                    .append(String.format("%.2f%%", c.totalDeltaPercent()))
                    .append(" increase exceeds ±").append(String.format("%.1f%%", c.threshold()))
                    .append(" threshold</div>\n");
        }

        List<MethodComparison> regressions = c.methodComparisons().stream()
                .filter(mc -> mc.isRegressed(c.threshold()))
                .sorted(Comparator.comparingDouble(MethodComparison::deltaPercent).reversed())
                .limit(topN).toList();

        if (!regressions.isEmpty()) {
            sb.append("    <h3>Regressed Entries</h3>\n")
                    .append("    <table><thead><tr>")
                    .append("<th>Name</th><th>Baseline (J)</th><th>Current (J)</th><th>Delta</th>")
                    .append("</tr></thead><tbody>\n");
            regressions.forEach(mc ->
                    sb.append("      <tr>")
                            .append("<td><code>").append(escHtml(mc.methodName())).append("</code></td>")
                            .append("<td>").append(String.format("%.4f", mc.baselineEnergyJoules())).append("</td>")
                            .append("<td>").append(String.format("%.4f", mc.currentEnergyJoules())).append("</td>")
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
        return "    <div class=\"metric\"><div class=\"label\">" + escHtml(label)
                + "</div><div class=\"value\">" + escHtml(value) + "</div></div>\n";
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
