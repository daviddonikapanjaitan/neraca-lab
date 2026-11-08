package com.neracalab.backend.screening.quant;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;

/**
 * The metrics of the Quant Score tab of a company: Neraca Lab's own data (the filings, the valuation snapshot and the
 * daily prices) combined with the screening data (Yahoo Finance's snapshot in {@code fundamental_snapshot}), so a
 * scorecard has as many metrics as either source knows. The scorecards themselves ({@link QuantScorer}) are unchanged.
 * <p>
 * Which source a metric takes first:
 * <ul>
 *   <li>statement metrics (ROE, margins, debt / equity, growth, the multi-year metrics, interest coverage, Altman Z):
 *       the filings ({@code FILINGS}: the latest fiscal year, the balance sheet of the latest period, up to
 *       {@link #YEARS} consecutive fiscal years), else Yahoo</li>
 *   <li>valuation metrics (P/E, P/B, EV / EBITDA, FCF yield, EPS): the latest {@code valuation_snapshot}
 *       ({@code VALUATION}), else Yahoo</li>
 *   <li>PEG: the P/E above over the earnings CAGR above ({@code DERIVED}), else Yahoo's PEG</li>
 *   <li>market data (price, traded value, 52-week drawdown and range): Yahoo, else the stored daily prices
 *       ({@code PRICES}); beta and insider ownership: Yahoo only</li>
 * </ul>
 * The formulas are those of {@link StockProfile} (the same CAGR, margin trend, Altman Z ...), applied to the filings.
 */
public final class CombinedProfile {

    /** Fiscal years of the multi-year metrics (Yahoo's annual figures also cover four). */
    public static final int YEARS = 4;

    public enum Source { FILINGS, VALUATION, PRICES, DERIVED, YAHOO }

    /** Where a metric came from: the source and what of it (e.g. "FY2025", "valuation 2026-11-06"). */
    public record MetricSource(Source source, String basis) {
    }

    /**
     * Neraca Lab's own figures of a company. Ratios are fractions; amounts in the reporting currency; market data in
     * the listing currency. Null = not known.
     *
     * @param annual          up to {@link #YEARS} consecutive fiscal years, oldest first, with the keys of Yahoo's
     *                        annual figures (revenue, netIncome = to the owners, equity, operatingIncome, capex, ebit,
     *                        interestExpense, totalAssets, totalLiabilities, workingCapital, retainedEarnings,
     *                        freeCashFlow); missing years are null
     * @param fiscalYear      the latest of them ("FY2025")
     * @param yearMetrics     the latest fiscal year's {@code financial_metric}: roe, roa, grossMargin, operatingMargin,
     *                        profitMargin, interestCoverage
     * @param balanceMetrics  the latest balance sheet's {@code financial_metric}: debtToEquity, currentRatio,
     *                        equityToAssets
     * @param balancePeriod   its period ("2026 H1")
     * @param valuationDate   date of the latest valuation snapshot
     * @param marketCap       its market cap (reporting currency, for Altman Z)
     * @param pricesDate      last stored close
     * @param price           that close in the listing currency
     * @param high52 / low52  highest high and lowest low of the 52 weeks to it (stored currency)
     * @param close52         the last close in the same currency as high52 / low52
     * @param avgDailyValue   average close x volume of the last 63 trading days, in the listing currency
     */
    public record Own(AnnualFigures annual, String fiscalYear, Map<String, Double> yearMetrics,
                      Map<String, Double> balanceMetrics, String balancePeriod, LocalDate valuationDate, Double pe,
                      Double pb, Double evToEbitda, Double fcfYield, Double eps, Double marketCap, LocalDate pricesDate,
                      Double price, Double high52, Double low52, Double close52, Double avgDailyValue) {

        public static Own empty() {
            return new Own(AnnualFigures.empty(), null, Map.of(), Map.of(), null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }
    }

    public record Result(StockProfile profile, Map<String, MetricSource> sources) {
    }

    private CombinedProfile() {
    }

    /**
     * @param yahoo     the latest screening snapshot of the listing; null when the stock is not in the screening data
     * @param own       Neraca Lab's figures
     * @param sector    sector / industry to classify a company without a snapshot (financial or not)
     * @param idxSector the IDX-IC sector (IDX company profile), e.g. "Keuangan" / "Financials"
     */
    public static Result of(StockSnapshot yahoo, Own own, String sector, String industry, String idxSector) {
        StockProfile y = yahoo == null ? null : StockProfile.of(yahoo);
        boolean financial = y != null ? y.financial() : StockProfile.isFinancial(sector, industry) || financialIdx(idxSector);
        Map<String, MetricSource> sources = new LinkedHashMap<>();
        String date = yahoo == null || yahoo.snapshotDate() == null ? "screening data" : "screening data " + yahoo.snapshotDate();
        MetricSource yahooSource = new MetricSource(Source.YAHOO, date);
        String fy = own.fiscalYear();
        AnnualFigures a = own.annual() == null ? AnnualFigures.empty() : own.annual();
        String years = a.years().isEmpty() ? fy : a.years().get(0) + " - " + a.years().get(a.years().size() - 1);
        MetricSource filingsYear = new MetricSource(Source.FILINGS, fy);
        MetricSource filingsYears = new MetricSource(Source.FILINGS, years);
        MetricSource balance = new MetricSource(Source.FILINGS, own.balancePeriod());
        MetricSource valuation = new MetricSource(Source.VALUATION,
                own.valuationDate() == null ? "valuation" : "valuation " + own.valuationDate());
        MetricSource prices = new MetricSource(Source.PRICES,
                own.pricesDate() == null ? "daily prices" : "daily prices to " + own.pricesDate());

        Merge m = new Merge(sources, yahooSource);

        // the latest fiscal year
        Double roe = m.pick("roe", own.yearMetrics().get("roe"), filingsYear, y == null ? null : y.roe());
        Double roa = m.pick("roa", own.yearMetrics().get("roa"), filingsYear, y == null ? null : y.roa());
        Double grossMargin = financial ? null
                : m.pick("grossMargin", own.yearMetrics().get("grossMargin"), filingsYear, y == null ? null : y.grossMargin());
        Double operatingMargin = m.pick("operatingMargin", own.yearMetrics().get("operatingMargin"), filingsYear,
                y == null ? null : y.operatingMargin());
        Double profitMargin = m.pick("profitMargin", own.yearMetrics().get("profitMargin"), filingsYear,
                y == null ? null : y.profitMargin());
        Double ownCoverage = own.yearMetrics().get("interestCoverage");
        // capped at 100 as StockProfile does (a company with almost no interest expense)
        Double interestCoverage = financial ? null : m.pick("interestCoverage",
                ownCoverage == null ? null : Math.min(100, ownCoverage), filingsYear,
                y == null ? null : y.interestCoverage());

        // the latest balance sheet
        Double debtToEquity = financial ? null : m.pick("debtToEquity",
                nonNegative(own.balanceMetrics().get("debtToEquity")), balance, y == null ? null : y.debtToEquity());
        Double currentRatio = financial ? null : m.pick("currentRatio", own.balanceMetrics().get("currentRatio"),
                balance, y == null ? null : y.currentRatio());
        Double ownEquityToAssets = own.balanceMetrics().get("equityToAssets");
        MetricSource equitySource = balance;
        if (ownEquityToAssets == null) {
            ownEquityToAssets = StockProfile.ratio(a.latest("equity"), a.latest("totalAssets"));
            equitySource = filingsYear;
        }
        Double equityToAssets = m.pick("equityToAssets", ownEquityToAssets, equitySource,
                y == null ? null : y.equityToAssets());

        // the fiscal years
        List<Double> revenue = a.series("revenue");
        List<Double> netIncome = a.series("netIncome");
        Double roeAverage = m.pick("roeAverage", StockProfile.averageRatio(netIncome, a.series("equity")), filingsYears,
                y == null ? null : y.roeAverage());
        Double positiveYears = m.pick("positiveYears", StockProfile.positiveShare(netIncome), filingsYears,
                y == null ? null : y.positiveYears());
        Double revenueCagr = m.pick("revenueCagr", StockProfile.cagr(revenue), filingsYears,
                y == null ? null : y.revenueCagr());
        Double netIncomeCagr = m.pick("netIncomeCagr", StockProfile.cagr(netIncome), filingsYears,
                y == null ? null : y.netIncomeCagr());
        Double opMarginTrend = m.pick("opMarginTrend", StockProfile.marginTrend(a.series("operatingIncome"), revenue),
                filingsYears, y == null ? null : y.opMarginTrend());
        Double capexIntensity = financial ? null : m.pick("capexIntensity",
                StockProfile.averageAbsRatio(a.series("capex"), revenue), filingsYears, y == null ? null : y.capexIntensity());
        Double revenueGrowth = m.pick("revenueGrowth", lastGrowth(revenue), filingsYear,
                y == null ? null : y.revenueGrowth());
        Double earningsGrowth = m.pick("earningsGrowth", lastGrowth(netIncome), filingsYear,
                y == null ? null : y.earningsGrowth());
        Double ownFcf = a.latest("freeCashFlow");
        Double ownNi = lastValue(netIncome);
        Double fcfConversion = financial ? null : m.pick("fcfConversion",
                ownFcf == null || ownNi == null || ownNi <= 0 || !sameYear(a, "freeCashFlow", "netIncome") ? null
                        : ownFcf / ownNi,
                filingsYear, y == null ? null : y.fcfConversion());
        Double altmanZ = financial ? null : m.pick("altmanZ", StockProfile.altmanZ(a, own.marketCap()),
                new MetricSource(Source.FILINGS, fy + (own.valuationDate() == null ? "" : ", market cap of "
                        + own.valuationDate())), y == null ? null : y.altmanZ());

        // valuation
        Double pe = m.pick("pe", StockProfile.positive(own.pe()), valuation, y == null ? null : y.pe());
        Double pb = m.pick("pb", StockProfile.positive(own.pb()), valuation, y == null ? null : y.pb());
        Double evToEbitda = financial ? null : m.pick("evToEbitda", StockProfile.positive(own.evToEbitda()), valuation,
                y == null ? null : y.evToEbitda());
        Double fcfYield = financial ? null : m.pick("fcfYield", own.fcfYield(), valuation, y == null ? null : y.fcfYield());
        Double eps = m.pick("eps", own.eps(), valuation, y == null ? null : y.eps());
        Double peg = null;
        if (pe != null && netIncomeCagr != null && netIncomeCagr > 0.005) {
            peg = pe / (netIncomeCagr * 100);
            sources.put("peg", new MetricSource(Source.DERIVED, "P/E / (earnings CAGR x 100)"));
        } else if (y != null && y.peg() != null && yahoo.fundamentals() != null && yahoo.fundamentals().pegRatio() != null) {
            // Yahoo's own PEG (StockProfile's fallback), not one of Yahoo's P/E over a CAGR the filings lack
            peg = y.peg();
            sources.put("peg", yahooSource);
        }

        // market data: Yahoo first
        Double price = m.pickYahooFirst("price", y == null ? null : y.price(), own.price(), prices);
        Double avgDailyValue = m.pickYahooFirst("avgDailyValue", y == null ? null : y.avgDailyValue(),
                own.avgDailyValue(), prices);
        Double ownDrawdown = own.close52() != null && own.high52() != null && own.high52() > 0
                ? Math.max(0, 1 - own.close52() / own.high52()) : null;
        Double ownRange = own.high52() != null && own.low52() != null && own.low52() > 0
                ? (own.high52() - own.low52()) / own.low52() : null;
        Double drawdown = m.pickYahooFirst("drawdown", y == null ? null : y.drawdown(), ownDrawdown, prices);
        Double range52w = m.pickYahooFirst("range52w", y == null ? null : y.range52w(), ownRange, prices);
        Double beta = m.pickYahooFirst("beta", y == null ? null : y.beta(), null, null);
        Double insiderOwnership = m.pickYahooFirst("insiderOwnership", y == null ? null : y.insiderOwnership(), null, null);
        Double dividendYield = m.pickYahooFirst("dividendYield", y == null ? null : y.dividendYield(), null, null);
        Double marketCap = m.pickYahooFirst("marketCap", y == null ? null : y.marketCap(), null, null);

        int fiscalYears = Math.max(y == null ? 0 : y.fiscalYears(), (int) netIncome.stream().filter(v -> v != null).count());
        StockProfile profile = new StockProfile(financial, marketCap, price, avgDailyValue, pe, pb, eps, roe, roa,
                grossMargin, operatingMargin, profitMargin, debtToEquity, currentRatio, revenueGrowth, earningsGrowth,
                evToEbitda, dividendYield, beta, insiderOwnership, roeAverage, positiveYears, revenueCagr, netIncomeCagr,
                opMarginTrend, capexIntensity, fcfConversion, fcfYield, peg, drawdown, range52w, interestCoverage,
                altmanZ, equityToAssets, fiscalYears);
        return new Result(profile, sources);
    }

    /** IDX-IC sector Financials (Keuangan): banks, insurers, financing, securities, holding of financials. */
    static boolean financialIdx(String idxSector) {
        if (idxSector == null) {
            return false;
        }
        String s = idxSector.toLowerCase(java.util.Locale.ROOT);
        return s.contains("keuangan") || s.contains("financial");
    }

    /** Growth of the last fiscal year over the one before (both known, the earlier positive). */
    static Double lastGrowth(List<Double> values) {
        int n = values.size();
        if (n < 2 || values.get(n - 1) == null || values.get(n - 2) == null || values.get(n - 2) <= 0) {
            return null;
        }
        return values.get(n - 1) / values.get(n - 2) - 1;
    }

    private static Double lastValue(List<Double> values) {
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** Both keys known in the last fiscal year. */
    private static boolean sameYear(AnnualFigures a, String k1, String k2) {
        List<Double> s1 = a.series(k1);
        List<Double> s2 = a.series(k2);
        return !s1.isEmpty() && !s2.isEmpty() && s1.get(s1.size() - 1) != null && s2.get(s2.size() - 1) != null;
    }

    private static Double nonNegative(Double v) {
        // a negative debt / equity is a negative equity: unknown, as Yahoo leaves it
        return v == null || v < 0 ? null : v;
    }

    /** Picks a metric's value and records its source. */
    private static final class Merge {
        private final Map<String, MetricSource> sources;
        private final MetricSource yahoo;

        Merge(Map<String, MetricSource> sources, MetricSource yahoo) {
            this.sources = sources;
            this.yahoo = yahoo;
        }

        /** Own value first, else Yahoo's. */
        Double pick(String key, Double own, MetricSource ownSource, Double yahooValue) {
            if (finite(own)) {
                sources.put(key, ownSource);
                return own;
            }
            if (finite(yahooValue)) {
                sources.put(key, yahoo);
                return yahooValue;
            }
            return null;
        }

        /** Yahoo's value first, else the own one. */
        Double pickYahooFirst(String key, Double yahooValue, Double own, MetricSource ownSource) {
            if (finite(yahooValue)) {
                sources.put(key, yahoo);
                return yahooValue;
            }
            if (finite(own) && ownSource != null) {
                sources.put(key, ownSource);
                return own;
            }
            return null;
        }

        private static boolean finite(Double v) {
            return v != null && Double.isFinite(v);
        }
    }
}
