package com.neracalab.backend.macro.source;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.special.Gamma;

/**
 * The Standardized Precipitation Index (McKee et al. 1993) of a monthly rainfall series, over {@code months}-month
 * totals (SPI-3: 3 months):
 * <ol>
 *   <li>the total of each window of {@code months} consecutive months (all of them present);</li>
 *   <li>per calendar end month, a gamma distribution fitted to the totals of the base period 1991 - 2020 (Thom's
 *       maximum-likelihood estimate), with the share of zero totals kept apart (a mixed distribution);</li>
 *   <li>the total's cumulative probability turned into a standard normal z-score.</li>
 * </ol>
 * -1 or less is a moderate drought, -1.5 severe, -2 extreme; +1.5 or more very wet. The score is bounded to
 * {@code +/- MAX}. A calendar month with fewer than {@link #MIN_BASE_YEARS} base totals has no score.
 */
public final class StandardizedPrecipitation {

    public static final int BASE_FROM = 1991;
    public static final int BASE_TO = 2020;
    static final int MIN_BASE_YEARS = 25;
    static final double MAX = 4.5;
    private static final NormalDistribution NORMAL = new NormalDistribution(0, 1);

    private StandardizedPrecipitation() {
    }

    /** A fitted calendar month: the share of zero totals, the gamma shape and scale. */
    record Fit(double zeros, double shape, double scale) {
    }

    /**
     * @param rainfall mm per month, by month (missing months break the windows that include them)
     * @return the SPI of every month that ends a complete window and whose calendar month has a fit, oldest first
     */
    public static NavigableMap<YearMonth, Double> compute(Map<YearMonth, Double> rainfall, int months) {
        NavigableMap<YearMonth, Double> totals = totals(rainfall, months);
        Map<Integer, Fit> fits = new java.util.HashMap<>();
        for (int calendar = 1; calendar <= 12; calendar++) {
            List<Double> base = new ArrayList<>();
            for (int year = BASE_FROM; year <= BASE_TO; year++) {
                Double t = totals.get(YearMonth.of(year, calendar));
                if (t != null) {
                    base.add(t);
                }
            }
            if (base.size() >= MIN_BASE_YEARS) {
                Fit fit = fit(base);
                if (fit != null) {
                    fits.put(calendar, fit);
                }
            }
        }
        NavigableMap<YearMonth, Double> out = new TreeMap<>();
        for (Map.Entry<YearMonth, Double> e : totals.entrySet()) {
            Fit fit = fits.get(e.getKey().getMonthValue());
            if (fit != null) {
                out.put(e.getKey(), spi(e.getValue(), fit));
            }
        }
        return out;
    }

    /** The calendar months (1 - 12) without enough base totals to fit (an incomplete 1991 - 2020 history). */
    public static List<Integer> unfitted(Map<YearMonth, Double> rainfall, int months) {
        NavigableMap<YearMonth, Double> totals = totals(rainfall, months);
        List<Integer> out = new ArrayList<>();
        for (int calendar = 1; calendar <= 12; calendar++) {
            int n = 0;
            for (int year = BASE_FROM; year <= BASE_TO; year++) {
                if (totals.containsKey(YearMonth.of(year, calendar))) {
                    n++;
                }
            }
            if (n < MIN_BASE_YEARS) {
                out.add(calendar);
            }
        }
        return out;
    }

    /** The total of every window of {@code months} consecutive months, by its last month. */
    static NavigableMap<YearMonth, Double> totals(Map<YearMonth, Double> rainfall, int months) {
        NavigableMap<YearMonth, Double> out = new TreeMap<>();
        for (YearMonth end : new TreeMap<>(rainfall).keySet()) {
            double sum = 0;
            boolean complete = true;
            for (int k = 0; k < months; k++) {
                Double v = rainfall.get(end.minusMonths(k));
                if (v == null || v.isNaN() || v < 0) {
                    complete = false;
                    break;
                }
                sum += v;
            }
            if (complete) {
                out.put(end, sum);
            }
        }
        return out;
    }

    /** Thom's estimate of the gamma distribution of the positive totals; null when fewer than 3 are positive. */
    static Fit fit(List<Double> totals) {
        List<Double> positive = totals.stream().filter(v -> v > 0).toList();
        if (positive.size() < 3) {
            return null;
        }
        double zeros = (double) (totals.size() - positive.size()) / totals.size();
        double mean = positive.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double meanLog = positive.stream().mapToDouble(Math::log).average().orElseThrow();
        double a = Math.log(mean) - meanLog;
        if (a <= 0) {
            return null;    // every positive total equal: no spread to fit
        }
        double shape = (1 + Math.sqrt(1 + 4 * a / 3)) / (4 * a);
        return new Fit(zeros, shape, mean / shape);
    }

    static double spi(double total, Fit fit) {
        double g = total <= 0 ? 0 : Gamma.regularizedGammaP(fit.shape(), total / fit.scale());
        double p = fit.zeros() + (1 - fit.zeros()) * g;
        double low = NORMAL.cumulativeProbability(-MAX);
        double z = NORMAL.inverseCumulativeProbability(Math.max(low, Math.min(1 - low, p)));
        return Math.max(-MAX, Math.min(MAX, z));
    }
}
