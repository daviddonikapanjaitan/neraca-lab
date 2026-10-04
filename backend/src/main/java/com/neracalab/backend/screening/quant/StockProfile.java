package com.neracalab.backend.screening.quant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.Fundamentals;

/**
 * The metrics of one stock that Stage 1 scores and the agents see, derived from its latest
 * snapshot. Ratios are fractions; null = not known (the criterion is then skipped). Multi-year
 * metrics use the four fiscal years of {@code fundamental_snapshot.annual}.
 *
 * @param financial         bank / insurer / financial services: leverage, margin and cash-flow ratios
 *                          of industrial companies do not apply
 * @param roeAverage        average of net income / equity over the fiscal years
 * @param positiveYears     share of the fiscal years with positive net income
 * @param revenueCagr       compound annual revenue growth, first to last fiscal year
 * @param netIncomeCagr     compound annual net income growth (both ends positive)
 * @param opMarginTrend     operating margin of the last fiscal year minus that of the first
 * @param capexIntensity    average capital expenditure / revenue
 * @param fcfConversion     free cash flow / net income (trailing)
 * @param fcfYield          free cash flow / market cap
 * @param peg               trailing P/E / (net income CAGR x 100); Yahoo's PEG when no CAGR
 * @param drawdown          1 - price / 52-week high
 * @param range52w          (52-week high - low) / low: a volatility proxy
 * @param interestCoverage  EBIT / interest expense of the last fiscal year
 * @param altmanZ           Altman Z-score (non-financial companies)
 * @param equityToAssets    equity / total assets of the last fiscal year (capital strength of a bank)
 */
public record StockProfile(boolean financial, Double marketCap, Double price, Double avgDailyValue, Double pe,
                           Double pb, Double eps, Double roe, Double roa, Double grossMargin, Double operatingMargin,
                           Double profitMargin, Double debtToEquity, Double currentRatio, Double revenueGrowth,
                           Double earningsGrowth, Double evToEbitda, Double dividendYield, Double beta,
                           Double insiderOwnership, Double roeAverage, Double positiveYears, Double revenueCagr,
                           Double netIncomeCagr, Double opMarginTrend, Double capexIntensity, Double fcfConversion,
                           Double fcfYield, Double peg, Double drawdown, Double range52w, Double interestCoverage,
                           Double altmanZ, Double equityToAssets, int fiscalYears) {

    /** Every metric key of {@link #asMap()} (also those that are unknown for a stock). */
    public static final List<String> METRIC_KEYS = List.of("marketCap", "price", "avgDailyValue", "pe", "pb", "eps",
            "roe", "roa", "grossMargin", "operatingMargin", "profitMargin", "debtToEquity", "currentRatio",
            "revenueGrowth", "earningsGrowth", "evToEbitda", "dividendYield", "beta", "insiderOwnership", "roeAverage",
            "positiveYears", "revenueCagr", "netIncomeCagr", "opMarginTrend", "capexIntensity", "fcfConversion",
            "fcfYield", "peg", "drawdown", "range52w", "interestCoverage", "altmanZ", "equityToAssets", "financial",
            "fiscalYears");

    public static StockProfile of(StockSnapshot s) {
        Fundamentals f = s.fundamentals();
        AnnualFigures a = f == null || f.annual() == null ? AnnualFigures.empty() : f.annual();
        boolean financial = isFinancial(s.sector(), s.industry());

        List<Double> revenue = a.series("revenue");
        List<Double> netIncome = a.series("netIncome");
        List<Double> equity = a.series("equity");
        List<Double> opIncome = a.series("operatingIncome");
        List<Double> capex = a.series("capex");

        Double roeAverage = averageRatio(netIncome, equity);
        Double positiveYears = positiveShare(netIncome);
        Double revenueCagr = cagr(revenue);
        Double netIncomeCagr = cagr(netIncome);
        Double opMarginTrend = marginTrend(opIncome, revenue);
        Double capexIntensity = financial ? null : averageAbsRatio(capex, revenue);

        Double fcf = f == null ? null : f.freeCashFlow();
        Double netIncomeTtm = f == null ? null : f.netIncomeTtm();
        Double fcfConversion = financial || fcf == null || netIncomeTtm == null || netIncomeTtm <= 0 ? null : fcf / netIncomeTtm;
        Double fcfYield = financial || fcf == null || s.marketCap() == null || s.marketCap() <= 0 ? null : fcf / s.marketCap();

        Double pe = positive(s.trailingPe());
        Double peg = null;
        if (pe != null && netIncomeCagr != null && netIncomeCagr > 0.005) {
            peg = pe / (netIncomeCagr * 100);
        } else if (f != null && f.pegRatio() != null && f.pegRatio() > 0) {
            peg = f.pegRatio();
        }
        Double drawdown = s.price() != null && s.fiftyTwoWeekHigh() != null && s.fiftyTwoWeekHigh() > 0
                ? Math.max(0, 1 - s.price() / s.fiftyTwoWeekHigh()) : null;
        Double range52w = s.fiftyTwoWeekHigh() != null && s.fiftyTwoWeekLow() != null && s.fiftyTwoWeekLow() > 0
                ? (s.fiftyTwoWeekHigh() - s.fiftyTwoWeekLow()) / s.fiftyTwoWeekLow() : null;

        Double interestCoverage = null;
        Double ebit = a.latest("ebit");
        Double interest = a.latest("interestExpense");
        if (!financial && ebit != null && interest != null && Math.abs(interest) > 0) {
            interestCoverage = Math.min(100, ebit / Math.abs(interest));
        }
        Double altmanZ = financial ? null : altmanZ(a, s.marketCap());
        Double equityToAssets = ratio(a.latest("equity"), a.latest("totalAssets"));

        return new StockProfile(financial, s.marketCap(), s.price(), s.avgDailyValue3m(), pe, positive(s.priceToBook()),
                s.epsTtm(), f == null ? null : f.returnOnEquity(), f == null ? null : f.returnOnAssets(),
                financial || f == null ? null : f.grossMargin(), f == null ? null : f.operatingMargin(),
                f == null ? null : f.profitMargin(), financial || f == null ? null : f.debtToEquity(),
                financial || f == null ? null : f.currentRatio(), f == null ? null : f.revenueGrowth(),
                f == null ? null : f.earningsGrowth(), financial || f == null ? null : positive(f.evToEbitda()),
                s.dividendYield(), f == null ? null : f.beta(), f == null ? null : f.insiderOwnership(), roeAverage,
                positiveYears, revenueCagr, netIncomeCagr, opMarginTrend, capexIntensity, fcfConversion, fcfYield, peg,
                drawdown, range52w, interestCoverage, altmanZ, equityToAssets, a.years().size());
    }

    /** Banks, insurers and other financial services (Yahoo sector "Financial Services"). */
    static boolean isFinancial(String sector, String industry) {
        String s = sector == null ? "" : sector.toLowerCase();
        String i = industry == null ? "" : industry.toLowerCase();
        return s.contains("financial") || i.startsWith("banks") || i.contains("insurance") || i.contains("capital markets")
                || i.contains("credit services");
    }

    /** The metrics as an ordered map for the report and the agent prompts (rounded, nulls kept out). */
    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "marketCap", marketCap, 0);
        put(m, "price", price, 2);
        put(m, "avgDailyValue", avgDailyValue, 0);
        put(m, "pe", pe, 2);
        put(m, "pb", pb, 2);
        put(m, "eps", eps, 2);
        put(m, "roe", roe, 4);
        put(m, "roa", roa, 4);
        put(m, "grossMargin", grossMargin, 4);
        put(m, "operatingMargin", operatingMargin, 4);
        put(m, "profitMargin", profitMargin, 4);
        put(m, "debtToEquity", debtToEquity, 3);
        put(m, "currentRatio", currentRatio, 2);
        put(m, "revenueGrowth", revenueGrowth, 4);
        put(m, "earningsGrowth", earningsGrowth, 4);
        put(m, "evToEbitda", evToEbitda, 2);
        put(m, "dividendYield", dividendYield, 4);
        put(m, "beta", beta, 2);
        put(m, "insiderOwnership", insiderOwnership, 4);
        put(m, "roeAverage", roeAverage, 4);
        put(m, "positiveYears", positiveYears, 2);
        put(m, "revenueCagr", revenueCagr, 4);
        put(m, "netIncomeCagr", netIncomeCagr, 4);
        put(m, "opMarginTrend", opMarginTrend, 4);
        put(m, "capexIntensity", capexIntensity, 4);
        put(m, "fcfConversion", fcfConversion, 3);
        put(m, "fcfYield", fcfYield, 4);
        put(m, "peg", peg, 2);
        put(m, "drawdown", drawdown, 4);
        put(m, "range52w", range52w, 3);
        put(m, "interestCoverage", interestCoverage, 2);
        put(m, "altmanZ", altmanZ, 2);
        put(m, "equityToAssets", equityToAssets, 4);
        m.put("financial", financial);
        m.put("fiscalYears", fiscalYears);
        return m;
    }

    private static void put(Map<String, Object> m, String key, Double value, int decimals) {
        if (value != null && Double.isFinite(value)) {
            double scale = Math.pow(10, decimals);
            m.put(key, decimals == 0 ? (Object) (double) Math.round(value) : Math.round(value * scale) / scale);
        }
    }

    // ------------------------------------------------------------------ helpers

    static Double positive(Double value) {
        return value == null || value <= 0 ? null : value;
    }

    static Double ratio(Double a, Double b) {
        return a == null || b == null || b == 0 ? null : a / b;
    }

    /** Average of a[i] / b[i] over the years where both are known and b > 0. */
    static Double averageRatio(List<Double> a, List<Double> b) {
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (a.get(i) != null && b.get(i) != null && b.get(i) > 0) {
                ratios.add(a.get(i) / b.get(i));
            }
        }
        return ratios.size() < 2 ? null : ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static Double averageAbsRatio(List<Double> a, List<Double> b) {
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (a.get(i) != null && b.get(i) != null && b.get(i) > 0) {
                ratios.add(Math.abs(a.get(i)) / b.get(i));
            }
        }
        return ratios.isEmpty() ? null : ratios.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static Double positiveShare(List<Double> values) {
        long known = values.stream().filter(v -> v != null).count();
        if (known < 2) {
            return null;
        }
        return values.stream().filter(v -> v != null && v > 0).count() / (double) known;
    }

    /** CAGR between the first and the last known value (both > 0, at least two years apart). */
    static Double cagr(List<Double> values) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0 || last - first < 2 || values.get(first) <= 0 || values.get(last) <= 0) {
            return null;
        }
        return Math.pow(values.get(last) / values.get(first), 1.0 / (last - first)) - 1;
    }

    /** Margin of the last year with data minus that of the first (at least two years with data). */
    static Double marginTrend(List<Double> income, List<Double> revenue) {
        Double first = null;
        Double last = null;
        int points = 0;
        for (int i = 0; i < Math.min(income.size(), revenue.size()); i++) {
            if (income.get(i) != null && revenue.get(i) != null && revenue.get(i) > 0) {
                double margin = income.get(i) / revenue.get(i);
                if (first == null) {
                    first = margin;
                }
                last = margin;
                points++;
            }
        }
        return points < 2 ? null : last - first;
    }

    /** Altman Z = 1.2 WC/TA + 1.4 RE/TA + 3.3 EBIT/TA + 0.6 MVE/TL + 1.0 Sales/TA (last fiscal year). */
    static Double altmanZ(AnnualFigures a, Double marketCap) {
        Double ta = a.latest("totalAssets");
        Double tl = a.latest("totalLiabilities");
        Double wc = a.latest("workingCapital");
        Double re = a.latest("retainedEarnings");
        Double ebit = a.latest("ebit");
        Double sales = a.latest("revenue");
        if (ta == null || ta <= 0 || tl == null || tl <= 0 || wc == null || re == null || ebit == null || sales == null
                || marketCap == null) {
            return null;
        }
        return 1.2 * wc / ta + 1.4 * re / ta + 3.3 * ebit / ta + 0.6 * marketCap / tl + 1.0 * sales / ta;
    }
}
