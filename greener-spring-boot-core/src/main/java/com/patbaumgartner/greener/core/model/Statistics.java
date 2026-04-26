package com.patbaumgartner.greener.core.model;

import java.util.Arrays;

/**
 * Descriptive statistics over a set of energy measurements collected from repeated
 * iterations of the same workload.
 *
 * <p>
 * Energy readings on RAPL / Joular Core are noisy &mdash; typical run-to-run variation on
 * the same code path sits between 3 % and 15 % of the mean. A single measurement cannot
 * reliably distinguish a real regression from sampling noise, so the plugin can run the
 * workload {@code N} times and aggregate the per-iteration totals here.
 *
 * <p>
 * Helpers expose Welch's t-test (handles unequal variances and unequal sample sizes
 * &mdash; the Behrens&ndash;Fisher problem) and Cohen's d effect size so the comparator
 * can decide regressions on the basis of statistical significance and effect size rather
 * than a raw percentage threshold.
 *
 * @param n sample size
 * @param mean arithmetic mean
 * @param stddev sample standard deviation (Bessel-corrected, divisor {@code n-1})
 * @param min smallest observation
 * @param max largest observation
 * @param median 50th percentile
 * @param ci95Half half-width of the 95 % confidence interval of the mean
 * ({@code t_{0.975,n-1} \u00d7 stddev / sqrt(n)})
 */
public record Statistics(int n, double mean, double stddev, double min, double max, double median, double ci95Half) {

	private static final double EPSILON = 1e-12;

	public Statistics {
		if (n < 0) {
			throw new IllegalArgumentException("n must be >= 0");
		}
		if (stddev < 0) {
			throw new IllegalArgumentException("stddev must be >= 0");
		}
		if (ci95Half < 0) {
			throw new IllegalArgumentException("ci95Half must be >= 0");
		}
	}

	/** Empty / unknown statistics &mdash; used when no samples are available. */
	public static Statistics empty() {
		return new Statistics(0, 0, 0, 0, 0, 0, 0);
	}

	/**
	 * Treats a single observation as a degenerate sample (no variance information). Used
	 * when a baseline was captured with {@code iterations = 1} and only carries a point
	 * estimate.
	 */
	public static Statistics single(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			throw new IllegalArgumentException("value must be finite");
		}
		return new Statistics(1, value, 0, value, value, value, 0);
	}

	/**
	 * Aggregates a set of samples into descriptive statistics. Uses Bessel's correction
	 * (divisor {@code n-1}) for sample variance.
	 * @param samples raw observations; null or empty returns {@link #empty()}
	 * @return aggregated statistics
	 */
	@SuppressWarnings("PMD.UseVarargs")
	public static Statistics of(double[] samples) {
		if (samples == null || samples.length == 0) {
			return empty();
		}
		final int singleSample = 1;
		if (samples.length == singleSample) {
			return single(samples[0]);
		}
		int len = samples.length;
		double[] sorted = samples.clone();
		Arrays.sort(sorted);
		double sum = 0;
		for (double v : sorted) {
			sum += v;
		}
		double mean = sum / len;
		double sse = 0;
		for (double v : sorted) {
			double d = v - mean;
			sse += d * d;
		}
		double variance = sse / (len - 1);
		double sd = Math.sqrt(variance);
		double median = (len % 2 == 1) ? sorted[len / 2] : 0.5 * (sorted[len / 2 - 1] + sorted[len / 2]);
		double tCrit = StudentT.criticalTwoTailed95(len - 1);
		double ci95Half = tCrit * sd / Math.sqrt(len);
		return new Statistics(len, mean, sd, sorted[0], sorted[len - 1], median, ci95Half);
	}

	/** {@code true} when there is enough data to perform a meaningful comparison. */
	public boolean hasVariance() {
		return n >= 2;
	}

	/**
	 * Coefficient of variation (relative standard deviation) as a percentage. Returns
	 * {@link Double#NaN} when the mean is zero.
	 */
	public double coefficientOfVariationPercent() {
		if (n == 0 || Math.abs(mean) < EPSILON) {
			return Double.NaN;
		}
		return (stddev / Math.abs(mean)) * 100.0;
	}

	/**
	 * Welch's t-test against another independent sample set. Returns NaN values when
	 * either sample has fewer than 2 observations.
	 *
	 * <p>
	 * The convention is {@code (this - other)}: a positive t means the current
	 * measurement consumed more energy than the baseline.
	 * @param other the other (typically the baseline) statistics
	 * @return t statistic, Welch&ndash;Satterthwaite degrees of freedom, and the
	 * two-sided p-value
	 */
	public WelchResult welchTTest(Statistics other) {
		if (n < 2 || other == null || other.n < 2) {
			return new WelchResult(Double.NaN, Double.NaN, Double.NaN);
		}
		double v1 = stddev * stddev;
		double v2 = other.stddev * other.stddev;
		double seSquared = v1 / n + v2 / other.n;
		double dMean = mean - other.mean;
		if (seSquared < EPSILON) {
			double tInf = Math.abs(dMean) < EPSILON ? 0.0
					: (dMean > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
			double p = Math.abs(dMean) < EPSILON ? 1.0 : 0.0;
			return new WelchResult(tInf, Double.POSITIVE_INFINITY, p);
		}
		double t = dMean / Math.sqrt(seSquared);
		double num = seSquared * seSquared;
		double denom = (v1 * v1) / ((double) n * n * (n - 1.0))
				+ (v2 * v2) / ((double) other.n * other.n * (other.n - 1.0));
		double df = denom <= 0 ? Double.POSITIVE_INFINITY : num / denom;
		double pTwoSided = StudentT.twoSidedPValue(t, df);
		return new WelchResult(t, df, pTwoSided);
	}

	/**
	 * Cohen's d effect size using pooled standard deviation.
	 *
	 * <p>
	 * Conventional interpretation: {@code |d|<0.2} negligible, {@code <0.5} small,
	 * {@code <0.8} medium, {@code >=0.8} large.
	 * @param other the other (typically the baseline) statistics
	 * @return effect size, or {@link Double#NaN} when either sample has fewer than 2
	 * observations
	 */
	public double cohenD(Statistics other) {
		if (n < 2 || other == null || other.n < 2) {
			return Double.NaN;
		}
		double pooledVar = ((n - 1.0) * stddev * stddev + (other.n - 1.0) * other.stddev * other.stddev)
				/ (n + other.n - 2.0);
		double pooledSd = Math.sqrt(pooledVar);
		if (pooledSd < EPSILON) {
			if (Math.abs(mean - other.mean) < EPSILON) {
				return 0.0;
			}
			return mean > other.mean ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
		}
		return (mean - other.mean) / pooledSd;
	}

	/**
	 * Result of a Welch t-test.
	 *
	 * @param t the t-statistic; positive when the first sample's mean exceeds the
	 * second's
	 * @param df Welch&ndash;Satterthwaite degrees of freedom
	 * @param pValueTwoSided two-sided p-value &mdash; the probability of observing a mean
	 * difference at least as extreme as {@code t} under the null hypothesis of equal
	 * means
	 */
	public record WelchResult(double t, double df, double pValueTwoSided) {
	}

}
