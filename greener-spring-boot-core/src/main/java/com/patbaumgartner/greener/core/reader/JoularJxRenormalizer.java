package com.patbaumgartner.greener.core.reader;

import com.patbaumgartner.greener.core.model.EnergyMeasurement;
import com.patbaumgartner.greener.core.model.EnergyReport;
import com.patbaumgartner.greener.core.model.MethodLevelReports;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconciles JoularJX method-level energy attributions against the corresponding Joular
 * Core process-level total.
 *
 * <h2>Why renormalise?</h2>
 *
 * <p>
 * JoularJX attributes energy by sampling the JVM call stack via the cycle counter and
 * weighting CPU power across the stack frames present at each sample. This is a
 * statistical estimator: under load it routinely produces method-energy sums that
 * <em>exceed</em> the process-level energy reported by Joular Core (factors of 1.1× to 3×
 * are common).
 *
 * <p>
 * Two factors drive the over-attribution:
 * <ol>
 * <li>JoularJX power readings can include short-lived spikes that are smoothed out by
 * Joular Core's longer 1-second integration window.</li>
 * <li>Sample-based attribution counts every active stack frame, double-counting work that
 * ran briefly between samples.</li>
 * </ol>
 *
 * <p>
 * The standard fix &mdash; documented in the JoularJX paper and used in academic
 * follow-up work &mdash; is to renormalise method energies by
 *
 * <pre>
 *   factor = processEnergyJ / max(Σ methodEnergyJ , ε)
 * </pre>
 *
 * so the sum of attributed energies matches the authoritative process-level reading. This
 * preserves <em>relative</em> attribution (which method dominates) while making absolute
 * numbers comparable to Joular Core, baseline reports, and energy budgets.
 *
 * <p>
 * Renormalisation is a no-op when the process total exceeds the method sum (which can
 * happen when JoularJX did not see the full lifecycle of the JVM, e.g. on Windows
 * shutdown without JVM hooks).
 */
public final class JoularJxRenormalizer {

	/**
	 * Minimum method-energy sum below which renormalisation is skipped to avoid division
	 * blow-up.
	 */
	private static final double EPSILON_JOULES = 1.0e-9;

	private JoularJxRenormalizer() {
	}

	/**
	 * Renormalises a method-level {@link EnergyReport} so that its measurements sum to
	 * {@code processEnergyJoules}.
	 * @param methodReport the method-level report from JoularJX (may be {@code null})
	 * @param processEnergyJoules authoritative total from Joular Core
	 * @return the renormalised report; the original is returned unchanged when
	 * renormalisation would be a no-op (over-attribution absent, missing data, or
	 * non-positive process energy)
	 */
	public static EnergyReport renormalize(EnergyReport methodReport, double processEnergyJoules) {
		if (methodReport == null || processEnergyJoules <= 0) {
			return methodReport;
		}
		double sum = methodReport.measurements().stream().mapToDouble(EnergyMeasurement::energyJoules).sum();
		if (sum <= EPSILON_JOULES) {
			return methodReport;
		}
		// Only renormalise when JoularJX has over-attributed (sum > process). When
		// sum < process (under-attribution due to truncated runs), scaling up would
		// invent energy that JoularJX never observed, so we leave it alone.
		if (sum <= processEnergyJoules) {
			return methodReport;
		}
		double factor = processEnergyJoules / sum;
		List<EnergyMeasurement> rescaled = new ArrayList<>(methodReport.measurements().size());
		for (EnergyMeasurement m : methodReport.measurements()) {
			rescaled.add(new EnergyMeasurement(m.methodName(), m.energyJoules() * factor));
		}
		return new EnergyReport(methodReport.runId(), methodReport.timestamp(), methodReport.durationSeconds(),
				rescaled, processEnergyJoules, methodReport.totalEnergyStats());
	}

	/**
	 * Renormalises both reports inside a {@link MethodLevelReports}. Returns a new
	 * container; either side may be {@code null} (passed through unchanged).
	 */
	public static MethodLevelReports renormalize(MethodLevelReports reports, double processEnergyJoules) {
		if (reports == null) {
			return null;
		}
		EnergyReport app = renormalize(reports.appReport(), processEnergyJoules);
		EnergyReport all = renormalize(reports.allReport(), processEnergyJoules);
		return new MethodLevelReports(app, all);
	}

	/**
	 * Computes the renormalisation factor that would be applied for a given report and
	 * process total, or {@code 1.0} if no scaling is needed. Useful for diagnostic
	 * logging.
	 */
	public static double factor(EnergyReport methodReport, double processEnergyJoules) {
		if (methodReport == null || processEnergyJoules <= 0) {
			return 1.0;
		}
		double sum = methodReport.measurements().stream().mapToDouble(EnergyMeasurement::energyJoules).sum();
		if (sum <= processEnergyJoules || sum <= EPSILON_JOULES) {
			return 1.0;
		}
		return processEnergyJoules / sum;
	}

}
