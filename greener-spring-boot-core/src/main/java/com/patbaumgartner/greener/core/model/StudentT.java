package com.patbaumgartner.greener.core.model;

/**
 * Self-contained Student's t-distribution helpers used by {@link Statistics}.
 *
 * <p>
 * Implements the regularized incomplete beta function to compute two-sided p-values
 * (Numerical Recipes §6.4) and a small inverse for the 95% critical value used by
 * {@link Statistics#ci95Half}.
 *
 * <p>
 * The implementation is based on the Lanczos approximation of {@code log\u0393} and the
 * Numerical Recipes continued-fraction expansion of the regularized incomplete beta
 * function {@code I\u2093(a,b)}, which yields the two-sided t-distribution p-value via
 * the textbook identity
 *
 * <pre>
 *     P(|T| &gt; t) = I_{df/(df+t^2)}(df/2, 1/2).
 * </pre>
 *
 * <p>
 * Tested against tabulated values in {@code StatisticsTest}. Accuracy is typically better
 * than 1e-4 across the 1 &le; df &le; 1000 range relevant to this project (energy
 * benchmarks rarely exceed 30 iterations).
 *
 * <p>
 * Suppressed PMD rule: literals like {@code 0.0}, {@code 1.0}, {@code 0.5}, and the
 * {@code 0.05} significance level are mathematical boundary conditions rather than magic
 * numbers; extracting them harms readability.
 */
@SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
final class StudentT {

	private StudentT() {
	}

	/** Two-sided p-value for the t-statistic at the given degrees of freedom. */
	static double twoSidedPValue(double t, double df) {
		if (Double.isNaN(t) || Double.isNaN(df) || df <= 0) {
			return Double.NaN;
		}
		if (Double.isInfinite(t)) {
			return 0.0;
		}
		if (Double.isInfinite(df)) {
			return 2.0 * (1.0 - normalCdf(Math.abs(t)));
		}
		double x = df / (df + t * t);
		return regularizedIncompleteBeta(df / 2.0, 0.5, x);
	}

	/**
	 * Two-tailed t-critical value at the 95 % confidence level for the given degrees of
	 * freedom. Uses a tabulated value for {@code df &le; 30}, otherwise inverts
	 * {@link #twoSidedPValue(double, double)} by bisection.
	 */
	static double criticalTwoTailed95(int df) {
		if (df <= 0) {
			return Double.NaN;
		}
		// Tabulated t-critical (two-tailed alpha=0.05) for df = 1..30.
		double[] table = { 12.7062, 4.3027, 3.1824, 2.7764, 2.5706, 2.4469, 2.3646, 2.3060, 2.2622, 2.2281, 2.2010,
				2.1788, 2.1604, 2.1448, 2.1314, 2.1199, 2.1098, 2.1009, 2.0930, 2.0860, 2.0796, 2.0739, 2.0687, 2.0639,
				2.0595, 2.0555, 2.0518, 2.0484, 2.0452, 2.0423 };
		if (df <= table.length) {
			return table[df - 1];
		}
		return inverseStudentT(df);
	}

	/**
	 * Bisection inverse for {@code df > 30}; returns t such that P(|T|>t) = 0.05.
	 */
	private static double inverseStudentT(int df) {
		double lo = 1.5;
		double hi = 4.0;
		for (int i = 0; i < 80; i++) {
			double mid = 0.5 * (lo + hi);
			double p = twoSidedPValue(mid, df);
			if (p > 0.05) {
				lo = mid;
			}
			else {
				hi = mid;
			}
		}
		return 0.5 * (lo + hi);
	}

	/** Regularized incomplete beta {@code I_x(a,b)} via Numerical Recipes. */
	static double regularizedIncompleteBeta(double a, double b, double x) {
		if (x <= 0.0) {
			return 0.0;
		}
		if (x >= 1.0) {
			return 1.0;
		}
		double logBeta = logGamma(a + b) - logGamma(a) - logGamma(b);
		double bt = Math.exp(logBeta + a * Math.log(x) + b * Math.log(1.0 - x));
		if (x < (a + 1.0) / (a + b + 2.0)) {
			return bt * betacf(a, b, x) / a;
		}
		return 1.0 - bt * betacf(b, a, 1.0 - x) / b;
	}

	private static double betacf(double a, double b, double x) {
		final int maxIt = 200;
		final double eps = 3.0e-12;
		final double fpmin = 1.0e-30;
		double qab = a + b;
		double qap = a + 1.0;
		double qam = a - 1.0;
		double c = 1.0;
		double d = 1.0 - qab * x / qap;
		if (Math.abs(d) < fpmin) {
			d = fpmin;
		}
		d = 1.0 / d;
		double h = d;
		for (int m = 1; m <= maxIt; m++) {
			int m2 = 2 * m;
			double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
			d = 1.0 + aa * d;
			if (Math.abs(d) < fpmin) {
				d = fpmin;
			}
			c = 1.0 + aa / c;
			if (Math.abs(c) < fpmin) {
				c = fpmin;
			}
			d = 1.0 / d;
			h *= d * c;
			aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
			d = 1.0 + aa * d;
			if (Math.abs(d) < fpmin) {
				d = fpmin;
			}
			c = 1.0 + aa / c;
			if (Math.abs(c) < fpmin) {
				c = fpmin;
			}
			d = 1.0 / d;
			double del = d * c;
			h *= del;
			if (Math.abs(del - 1.0) < eps) {
				return h;
			}
		}
		return h;
	}

	/**
	 * Lanczos approximation of {@code ln \u0393(x)}, accurate to ~15 digits for x &gt; 0.
	 */
	static double logGamma(double x) {
		final double[] g = { 0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
				-176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
				1.5056327351493116e-7 };
		if (x < 0.5) {
			return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1.0 - x);
		}
		double y = x - 1.0;
		double a = g[0];
		double t = y + 7.5;
		for (int i = 1; i < 9; i++) {
			a += g[i] / (y + i);
		}
		return 0.5 * Math.log(2.0 * Math.PI) + (y + 0.5) * Math.log(t) - t + Math.log(a);
	}

	/**
	 * Standard-normal CDF via the Abramowitz &amp; Stegun 26.2.17 approximation.
	 */
	static double normalCdf(double z) {
		double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
		double pdf = Math.exp(-z * z / 2.0) / Math.sqrt(2.0 * Math.PI);
		double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
		double cdf = 1.0 - pdf * poly;
		return z >= 0 ? cdf : 1.0 - cdf;
	}

}
