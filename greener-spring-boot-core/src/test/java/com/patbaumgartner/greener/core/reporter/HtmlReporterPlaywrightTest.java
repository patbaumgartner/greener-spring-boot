package com.patbaumgartner.greener.core.reporter;

import com.microsoft.playwright.*;
import com.patbaumgartner.greener.core.model.ComparisonResult;
import com.patbaumgartner.greener.core.model.ComparisonResult.ComparisonStatus;
import com.patbaumgartner.greener.core.model.ComparisonResult.MethodComparison;
import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.PowerSource;
import com.patbaumgartner.greener.core.model.WorkloadStats;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Playwright-based integration tests that render the HTML energy report in a real browser
 * and verify visible content, layout, and interactive behaviour.
 */
class HtmlReporterPlaywrightTest {

	private static Playwright playwright;

	private static Browser browser;

	private BrowserContext context;

	private Page page;

	private final HtmlReporter reporter = new HtmlReporter();

	@BeforeAll
	static void launchBrowser() {
		playwright = Playwright.create();
		browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
	}

	@AfterAll
	static void closeBrowser() {
		if (browser != null)
			browser.close();
		if (playwright != null)
			playwright.close();
	}

	@BeforeEach
	void createContext() {
		context = browser.newContext();
		page = context.newPage();
	}

	@AfterEach
	void closeContext() {
		if (context != null)
			context.close();
	}

	@Test
	void reportRendersHeroSection(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		assertThat(page.locator(".hero h1").textContent()).contains("Greener Spring Boot");
		assertThat(page.locator(".hero .tagline").textContent()).contains("Joular Core");
	}

	@Test
	void reportRendersMeasurementSummaryCard(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		Locator card = page.locator(".card").first();
		assertThat(card.textContent()).contains("Measurement Summary");
		assertThat(card.textContent()).contains("workload-run-1");
		assertThat(card.textContent()).contains("60 s");
	}

	@Test
	void reportRendersWorkloadProfile(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		Locator workloadCard = page.locator("text=Workload Profile").locator("..");
		assertThat(workloadCard.textContent()).contains("oha");
		assertThat(workloadCard.textContent()).contains("10000");
		assertThat(workloadCard.textContent()).contains("50");
	}

	@Test
	void reportRendersPowerSource(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		Locator powerCard = page.locator("text=Measurement Assumptions").locator("..");
		assertThat(powerCard.textContent()).contains("RAPL");
	}

	@Test
	void reportRendersBaselineComparison(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		Locator comparisonCard = page.locator("text=Baseline vs Current").locator("..");
		assertThat(comparisonCard.textContent()).contains("100.00 J");
		assertThat(comparisonCard.textContent()).contains("125.00 J");
		assertThat(comparisonCard.textContent()).contains("Regressed");
	}

	@Test
	void reportRendersEnergyBreakdownTable(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		Locator table = page.locator("text=Energy Breakdown").locator("..").locator("table");
		assertThat(table.locator("tbody tr").count()).isGreaterThanOrEqualTo(2);
		assertThat(table.textContent()).contains("com.example.ServiceA.handle");
		assertThat(table.textContent()).contains("com.example.ServiceB.process");
	}

	@Test
	void reportRendersFooterWithLoveMessage(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		Locator footer = page.locator(".footer");
		assertThat(footer.textContent()).contains("Made with");
		assertThat(footer.textContent()).contains("Patrick Baumgartner");
		assertThat(footer.locator("a").getAttribute("href"))
			.isEqualTo("https://github.com/patbaumgartner/greener-spring-boot");
	}

	@Test
	void themeToggleSwitchesTheme(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		String initialTheme = page.locator("html").getAttribute("data-theme");
		assertThat(initialTheme).isEqualTo("dark");

		page.locator(".theme-toggle").click();

		String toggledTheme = page.locator("html").getAttribute("data-theme");
		assertThat(toggledTheme).isEqualTo("light");

		page.locator(".theme-toggle").click();

		String revertedTheme = page.locator("html").getAttribute("data-theme");
		assertThat(revertedTheme).isEqualTo("dark");
	}

	@Test
	void reportRendersRegressionHighlights(@TempDir Path tmp) throws IOException {
		Path htmlFile = generateFullReport(tmp);
		page.navigate(htmlFile.toUri().toString());

		Locator regressionSection = page.locator("text=Components with Increased Consumption").locator("..");
		assertThat(regressionSection.locator("table tbody tr").count()).isGreaterThanOrEqualTo(1);
		assertThat(regressionSection.textContent()).contains("com.example.ServiceA.handle");
	}

	@Test
	void reportWithNoBaselineShowsInfoNote(@TempDir Path tmp) throws IOException {
		EnergyReport report = EnergyReport.of("no-baseline-run", Instant.now(), 30,
				List.of(new EnergyMeasurement("app [app]", 42.0)));
		ComparisonResult noBaseline = new ComparisonResult(ComparisonStatus.NO_BASELINE, 0, 42.0, 0, List.of(), false,
				10.0);

		Path htmlFile = reporter.generateReport(report, noBaseline, tmp);
		page.navigate(htmlFile.toUri().toString());

		assertThat(page.locator(".note").textContent()).contains("No baseline found");
	}

	private Path generateFullReport(Path outputDir) throws IOException {
		EnergyReport report = EnergyReport.of("workload-run-1", Instant.now(), 60,
				List.of(new EnergyMeasurement("com.example.ServiceA.handle", 75.0),
						new EnergyMeasurement("com.example.ServiceB.process", 50.0)));

		MethodComparison mc1 = new MethodComparison("com.example.ServiceA.handle", 60.0, 75.0, 25.0);
		MethodComparison mc2 = new MethodComparison("com.example.ServiceB.process", 40.0, 50.0, 25.0);

		ComparisonResult comparison = new ComparisonResult(ComparisonStatus.REGRESSED, 100.0, 125.0, 25.0,
				List.of(mc1, mc2), true, 10.0);

		WorkloadStats stats = WorkloadStats.external("oha", 10000, 50, 60);

		return reporter.generateReport(report, comparison, stats, PowerSource.RAPL, outputDir);
	}

}
