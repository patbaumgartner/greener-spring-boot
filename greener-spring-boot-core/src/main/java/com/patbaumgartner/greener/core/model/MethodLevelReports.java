package com.patbaumgartner.greener.core.model;

/**
 * Holds both the filtered (app-only) and unfiltered (all methods) JoularJX energy reports
 * for method-level energy data.
 *
 * <p>
 * JoularJX produces two result sets:
 * <ul>
 * <li>{@code app/} — methods matching the configured filter (typically the application's
 * own classes)</li>
 * <li>{@code all/} — all methods observed during the measurement, including framework and
 * library code</li>
 * </ul>
 *
 * @param appReport the filtered app-only report (may be {@code null} if no app results
 * were produced)
 * @param allReport the unfiltered all-methods report (may be {@code null} if no results
 * were produced)
 */
public record MethodLevelReports(EnergyReport appReport, EnergyReport allReport) {

	/**
	 * Returns {@code true} if at least one of the reports contains measurements.
	 */
	public boolean hasData() {
		return hasAppData() || hasAllData();
	}

	/**
	 * Returns {@code true} if the app-only report contains measurements.
	 */
	public boolean hasAppData() {
		return appReport != null && !appReport.measurements().isEmpty();
	}

	/**
	 * Returns {@code true} if the all-methods report contains measurements.
	 */
	public boolean hasAllData() {
		return allReport != null && !allReport.measurements().isEmpty();
	}

}
