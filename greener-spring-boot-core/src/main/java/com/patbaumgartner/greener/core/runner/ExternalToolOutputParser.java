package com.patbaumgartner.greener.core.runner;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts request counts from the stdout of well-known HTTP load testing tools.
 *
 * <p>
 * Supported tools and the patterns matched:
 * <ul>
 * <li><b>oha</b> — status code distribution lines ({@code [200] 49820 responses})</li>
 * <li><b>wrk / wrk2</b> — summary line ({@code 24340 requests in 60.00s}), non-success
 * line ({@code Non-2xx or 3xx responses: 5})</li>
 * <li><b>bombardier</b> — status-code summary ({@code 2xx - 49820, …, 5xx - 3})</li>
 * <li><b>ab</b> — summary lines ({@code Complete requests: 10000},
 * {@code Failed requests: 0})</li>
 * <li><b>k6</b> — metrics output ({@code http_reqs…: 12345})</li>
 * <li><b>gatling</b> — global information block
 * ({@code > request count  1200 (OK=1200  KO=0)})</li>
 * <li><b>locust</b> — aggregated summary line ({@code Aggregated  1500  2(0.13%)})</li>
 * </ul>
 *
 * <p>
 * Parsing is best-effort. If the output does not match any known pattern, both
 * {@link #totalRequests()} and {@link #failedRequests()} return {@code -1}.
 */
public class ExternalToolOutputParser {

	private static final long UNKNOWN = -1L;

	private static final int HTTP_CLIENT_ERROR_THRESHOLD = 400;

	private static final int BOMBARDIER_CLIENT_ERROR_CLASS = 4;

	// oha: "[200] 49820 responses"
	private static final Pattern OHA_STATUS = Pattern.compile("^\\s*\\[(\\d{3})]\\s+(\\d+)\\s+responses",
			Pattern.MULTILINE);

	// wrk / wrk2: "24340 requests in 60.00s"
	private static final Pattern WRK_REQUESTS = Pattern.compile("^\\s*(\\d+)\\s+requests\\s+in\\s+", Pattern.MULTILINE);

	// wrk: "Non-2xx or 3xx responses: 5"
	private static final Pattern WRK_NON_SUCCESS = Pattern.compile("Non-2xx or 3xx responses:\\s*(\\d+)",
			Pattern.MULTILINE);

	// bombardier: "2xx - 49820" / "5xx - 3"
	private static final Pattern BOMBARDIER_STATUS = Pattern.compile("\\b([1-5])xx\\s*-\\s*(\\d+)");

	// ab: "Complete requests: 10000"
	private static final Pattern AB_TOTAL = Pattern.compile("Complete requests:\\s*(\\d+)", Pattern.MULTILINE);

	// ab: "Failed requests: 0"
	private static final Pattern AB_FAILED = Pattern.compile("Failed requests:\\s*(\\d+)", Pattern.MULTILINE);

	// k6: "http_reqs......................: 12345"
	private static final Pattern K6_REQS = Pattern.compile("http_reqs\\.+:\\s*(\\d+)", Pattern.MULTILINE);

	// k6: "http_req_failed................: 0.41% ✓ 51 ✗ 12294"
	// In k6, ✓ = condition matched (request WAS failed), ✗ = was NOT failed.
	private static final Pattern K6_FAILED = Pattern.compile("http_req_failed\\.+:.*✓\\s*(\\d+)", Pattern.MULTILINE);

	// k6 v1.x compact: "http_req_failed................: 6.67% 3 out of 45"
	private static final Pattern K6_FAILED_COMPACT = Pattern
		.compile("http_req_failed\\.+:\\s*[\\d.]+%\\s+(\\d+)\\s+out of", Pattern.MULTILINE);

	// gatling: "> request count 1200 (OK=1200
	// KO=0 )"
	private static final Pattern GATLING_REQUEST_COUNT = Pattern
		.compile(">\\s*request count\\s+(\\d+)\\s+\\(OK=(\\d+)\\s+KO=(\\d+)", Pattern.MULTILINE);

	// locust: "Aggregated 1500 2(0.13%)"
	// The aggregated line appears at the end of the headless summary table.
	private static final Pattern LOCUST_AGGREGATED = Pattern.compile("^\\s*Aggregated\\s+(\\d+)\\s+(\\d+)",
			Pattern.MULTILINE);

	private long parsedTotalRequests = UNKNOWN;

	private long parsedFailedRequests = UNKNOWN;

	/**
	 * Parses the combined stdout of a load-testing tool.
	 * @param toolName tool identifier (e.g. "oha", "wrk", "bombardier")
	 * @param output full stdout captured from the tool process
	 */
	public void parse(String toolName, String output) {
		if (output == null || output.isBlank()) {
			return;
		}

		switch (toolName.toLowerCase(Locale.ENGLISH)) {
			case "oha" -> parseOha(output);
			case "wrk", "wrk2" -> parseWrk(output);
			case "bombardier" -> parseBombardier(output);
			case "ab" -> parseAb(output);
			case "k6" -> parseK6(output);
			case "gatling" -> parseGatling(output);
			case "locust" -> parseLocust(output);
			default -> tryAllParsers(output);
		}
	}

	public long totalRequests() {
		return parsedTotalRequests;
	}

	public long failedRequests() {
		return parsedFailedRequests;
	}

	public boolean hasResults() {
		return parsedTotalRequests >= 0;
	}

	// ---- Tool-specific parsers ----

	private void parseOha(String output) {
		long total = 0;
		long failed = 0;
		boolean found = false;
		Matcher m = OHA_STATUS.matcher(output);
		while (m.find()) {
			found = true;
			int status = Integer.parseInt(m.group(1));
			long count = Long.parseLong(m.group(2));
			total += count;
			if (status >= HTTP_CLIENT_ERROR_THRESHOLD) {
				failed += count;
			}
		}
		if (found) {
			parsedTotalRequests = total;
			parsedFailedRequests = failed;
		}
	}

	private void parseWrk(String output) {
		Matcher m = WRK_REQUESTS.matcher(output);
		if (m.find()) {
			parsedTotalRequests = Long.parseLong(m.group(1));
			parsedFailedRequests = 0;

			Matcher nonSuccess = WRK_NON_SUCCESS.matcher(output);
			if (nonSuccess.find()) {
				parsedFailedRequests = Long.parseLong(nonSuccess.group(1));
			}
		}
	}

	private void parseBombardier(String output) {
		long total = 0;
		long failed = 0;
		boolean found = false;
		Matcher m = BOMBARDIER_STATUS.matcher(output);
		while (m.find()) {
			found = true;
			int statusClass = Integer.parseInt(m.group(1));
			long count = Long.parseLong(m.group(2));
			total += count;
			if (statusClass >= BOMBARDIER_CLIENT_ERROR_CLASS) {
				failed += count;
			}
		}
		if (found) {
			parsedTotalRequests = total;
			parsedFailedRequests = failed;
		}
	}

	private void parseAb(String output) {
		Matcher m = AB_TOTAL.matcher(output);
		if (m.find()) {
			parsedTotalRequests = Long.parseLong(m.group(1));
			parsedFailedRequests = 0;

			Matcher fm = AB_FAILED.matcher(output);
			if (fm.find()) {
				parsedFailedRequests = Long.parseLong(fm.group(1));
			}
		}
	}

	private void parseK6(String output) {
		Matcher m = K6_REQS.matcher(output);
		if (m.find()) {
			parsedTotalRequests = Long.parseLong(m.group(1));
			parsedFailedRequests = 0;

			Matcher fm = K6_FAILED.matcher(output);
			if (fm.find()) {
				parsedFailedRequests = Long.parseLong(fm.group(1));
			}
			else {
				Matcher fc = K6_FAILED_COMPACT.matcher(output);
				if (fc.find()) {
					parsedFailedRequests = Long.parseLong(fc.group(1));
				}
			}
		}
	}

	private void parseGatling(String output) {
		Matcher m = GATLING_REQUEST_COUNT.matcher(output);
		if (m.find()) {
			parsedTotalRequests = Long.parseLong(m.group(1));
			parsedFailedRequests = Long.parseLong(m.group(3));
		}
	}

	private void parseLocust(String output) {
		Matcher m = LOCUST_AGGREGATED.matcher(output);
		if (m.find()) {
			parsedTotalRequests = Long.parseLong(m.group(1));
			parsedFailedRequests = Long.parseLong(m.group(2));
		}
	}

	private void tryAllParsers(String output) {
		parseOha(output);
		if (hasResults())
			return;
		parseWrk(output);
		if (hasResults())
			return;
		parseBombardier(output);
		if (hasResults())
			return;
		parseAb(output);
		if (hasResults())
			return;
		parseK6(output);
		if (hasResults())
			return;
		parseGatling(output);
		if (hasResults())
			return;
		parseLocust(output);
	}

}
