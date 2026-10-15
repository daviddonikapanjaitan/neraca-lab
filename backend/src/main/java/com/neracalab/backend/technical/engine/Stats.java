package com.neracalab.backend.technical.engine;

import java.util.Arrays;

/** Small statistics on double arrays; NaN marks a missing value (warm-up of an indicator). */
public final class Stats {

    private Stats() {
    }

    /** Least-squares slope of {@code values[from..to]} (inclusive) against the bar index; NaN if any value is missing. */
    public static double slope(double[] values, int from, int to) {
        if (from < 0 || to >= values.length || to - from < 1) {
            return Double.NaN;
        }
        int n = to - from + 1;
        double meanX = (n - 1) / 2.0;
        double meanY = 0;
        for (int i = from; i <= to; i++) {
            if (Double.isNaN(values[i])) {
                return Double.NaN;
            }
            meanY += values[i];
        }
        meanY /= n;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            sxy += dx * (values[from + i] - meanY);
            sxx += dx * dx;
        }
        return sxy / sxx;
    }

    /** Mean of {@code values[from..to]} (inclusive), NaN if a value is missing or the range is empty. */
    public static double mean(double[] values, int from, int to) {
        if (from < 0 || to >= values.length || to < from) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = from; i <= to; i++) {
            if (Double.isNaN(values[i])) {
                return Double.NaN;
            }
            sum += values[i];
        }
        return sum / (to - from + 1);
    }

    /**
     * Percentile rank (0 .. 100) of {@code value} within {@code sample}: the share of values below it plus half the share
     * equal to it (so the median of a sample ranks 50). NaN values of the sample are ignored; NaN for an empty sample.
     */
    public static double percentileRank(double value, double[] sample) {
        if (Double.isNaN(value)) {
            return Double.NaN;
        }
        int below = 0;
        int equal = 0;
        int n = 0;
        for (double v : sample) {
            if (Double.isNaN(v)) {
                continue;
            }
            n++;
            if (v < value) {
                below++;
            } else if (v == value) {
                equal++;
            }
        }
        return n == 0 ? Double.NaN : (below + 0.5 * equal) * 100.0 / n;
    }

    /** Median of the non-NaN values, NaN when there are none. */
    public static double median(double[] values) {
        double[] v = Arrays.stream(values).filter(d -> !Double.isNaN(d)).sorted().toArray();
        if (v.length == 0) {
            return Double.NaN;
        }
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    /** Percentage change a / b - 1 in %, NaN when either is missing or b is not positive. */
    public static double pctChange(double a, double b) {
        return Double.isNaN(a) || Double.isNaN(b) || b <= 0 ? Double.NaN : (a / b - 1) * 100;
    }
}
