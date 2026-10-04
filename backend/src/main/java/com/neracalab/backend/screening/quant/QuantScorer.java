package com.neracalab.backend.screening.quant;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.neracalab.backend.screening.InvestorAgent;

/**
 * Stage 1 scorecards: a quantitative score 0-100 per investor agent, without any model. Every
 * criterion maps one metric linearly from a "poor" bound (0 points) to a "good" bound (full points),
 * clamped; the bounds may be reversed (lower is better, e.g. debt / equity). Unknown metrics and
 * criteria that do not apply (industrial ratios of a bank) are left out and the weights
 * renormalized; the score is then scaled by {@code 0.75 + 0.25 x coverage} so that a stock is not
 * rewarded for missing data.
 */
public final class QuantScorer {

    /** Which companies a criterion applies to. */
    enum Applies { ALL, NON_FINANCIAL, FINANCIAL }

    /**
     * @param poor   metric value worth 0 points
     * @param good   metric value worth full points
     */
    record Criterion(String key, String label, Function<StockProfile, Double> metric, double poor, double good,
                     double weight, Applies applies) {

        boolean appliesTo(StockProfile p) {
            return switch (applies) {
                case ALL -> true;
                case NON_FINANCIAL -> !p.financial();
                case FINANCIAL -> p.financial();
            };
        }

        /** 0..1 for a known value. */
        double points(double value) {
            double x = (value - poor) / (good - poor);
            return Math.max(0, Math.min(1, x));
        }
    }

    /** One criterion of a score: the metric value and the points it earned (null value: unknown). */
    public record Part(String key, String label, Double value, double weight, Double points) {
    }

    /**
     * @param coverage share of the applicable criteria weight with a known metric
     */
    public record AgentScore(InvestorAgent agent, double score, double coverage, List<Part> parts) {
    }

    private static final Map<InvestorAgent, List<Criterion>> CARDS = new EnumMap<>(InvestorAgent.class);

    static {
        CARDS.put(InvestorAgent.BUFFETT, List.of(
                c("roe", "Return on equity", StockProfile::roe, 0.08, 0.20, 3, Applies.ALL),
                c("roeAverage", "Average ROE over the fiscal years", StockProfile::roeAverage, 0.08, 0.18, 2, Applies.ALL),
                c("positiveYears", "Profitable fiscal years", StockProfile::positiveYears, 0.5, 1.0, 2, Applies.ALL),
                c("profitMargin", "Net profit margin", StockProfile::profitMargin, 0.03, 0.18, 1.5, Applies.ALL),
                c("debtToEquity", "Debt / equity", StockProfile::debtToEquity, 1.5, 0.3, 2, Applies.NON_FINANCIAL),
                c("fcfYield", "Free cash flow yield", StockProfile::fcfYield, 0.0, 0.08, 1.5, Applies.NON_FINANCIAL),
                c("pe", "P/E (sensible price)", StockProfile::pe, 25, 10, 2, Applies.ALL),
                c("revenueCagr", "Revenue CAGR", StockProfile::revenueCagr, 0.0, 0.10, 1, Applies.ALL),
                c("equityToAssets", "Equity / assets (bank capital)", StockProfile::equityToAssets, 0.08, 0.16, 1.5,
                        Applies.FINANCIAL)));
        CARDS.put(InvestorAgent.MUNGER, List.of(
                c("roe", "Return on equity", StockProfile::roe, 0.12, 0.25, 3, Applies.ALL),
                c("grossMargin", "Gross margin (pricing power)", StockProfile::grossMargin, 0.20, 0.50, 2, Applies.NON_FINANCIAL),
                c("operatingMargin", "Operating margin", StockProfile::operatingMargin, 0.08, 0.25, 2, Applies.ALL),
                c("capexIntensity", "Capex / revenue", StockProfile::capexIntensity, 0.15, 0.03, 1.5, Applies.NON_FINANCIAL),
                c("fcfConversion", "Free cash flow / net income", StockProfile::fcfConversion, 0.5, 1.1, 1.5,
                        Applies.NON_FINANCIAL),
                c("debtToEquity", "Debt / equity", StockProfile::debtToEquity, 1.0, 0.2, 1.5, Applies.NON_FINANCIAL),
                c("pe", "P/E (fair price)", StockProfile::pe, 30, 12, 1.5, Applies.ALL),
                c("positiveYears", "Profitable fiscal years", StockProfile::positiveYears, 0.75, 1.0, 1, Applies.ALL),
                c("roa", "Return on assets (bank quality)", StockProfile::roa, 0.008, 0.025, 2, Applies.FINANCIAL)));
        CARDS.put(InvestorAgent.LYNCH, List.of(
                c("peg", "PEG ratio", StockProfile::peg, 2.0, 0.5, 3, Applies.ALL),
                c("netIncomeCagr", "Earnings CAGR", QuantScorer::lynchGrowth, 0.05, 0.25, 2.5, Applies.ALL),
                c("pe", "P/E", StockProfile::pe, 25, 8, 1, Applies.ALL),
                c("debtToEquity", "Debt / equity", StockProfile::debtToEquity, 0.8, 0.2, 1.5, Applies.NON_FINANCIAL),
                c("revenueCagr", "Revenue CAGR", StockProfile::revenueCagr, 0.0, 0.15, 1, Applies.ALL),
                c("positiveYears", "Profitable fiscal years", StockProfile::positiveYears, 0.5, 1.0, 1, Applies.ALL)));
        CARDS.put(InvestorAgent.FISHER, List.of(
                c("revenueCagr", "Revenue CAGR", StockProfile::revenueCagr, 0.03, 0.20, 3, Applies.ALL),
                c("revenueGrowth", "Latest revenue growth", StockProfile::revenueGrowth, 0.0, 0.15, 1.5, Applies.ALL),
                c("operatingMargin", "Operating margin", StockProfile::operatingMargin, 0.08, 0.25, 2, Applies.ALL),
                c("opMarginTrend", "Operating margin trend", StockProfile::opMarginTrend, -0.03, 0.03, 2, Applies.ALL),
                c("roe", "Return on equity", StockProfile::roe, 0.10, 0.22, 1.5, Applies.ALL),
                c("netIncomeCagr", "Earnings CAGR", StockProfile::netIncomeCagr, 0.0, 0.20, 1.5, Applies.ALL)));
        CARDS.put(InvestorAgent.GILL, List.of(
                c("pb", "Price / book", StockProfile::pb, 2.0, 0.6, 3, Applies.ALL),
                c("evToEbitda", "EV / EBITDA", StockProfile::evToEbitda, 12, 4, 2, Applies.NON_FINANCIAL),
                c("drawdown", "Drawdown from 52-week high", StockProfile::drawdown, 0.10, 0.50, 2, Applies.ALL),
                c("fcfYield", "Free cash flow yield", StockProfile::fcfYield, 0.0, 0.12, 2, Applies.NON_FINANCIAL),
                c("pe", "P/E", StockProfile::pe, 15, 5, 1, Applies.ALL),
                c("insiderOwnership", "Insider ownership", StockProfile::insiderOwnership, 0.10, 0.50, 1, Applies.ALL),
                c("currentRatio", "Current ratio (survival)", StockProfile::currentRatio, 0.8, 1.5, 1, Applies.NON_FINANCIAL)));
        CARDS.put(InvestorAgent.RISK, List.of(
                c("debtToEquity", "Debt / equity", StockProfile::debtToEquity, 2.0, 0.3, 3, Applies.NON_FINANCIAL),
                c("interestCoverage", "Interest coverage", StockProfile::interestCoverage, 1.5, 8, 2, Applies.NON_FINANCIAL),
                c("altmanZ", "Altman Z-score", StockProfile::altmanZ, 1.8, 3.0, 2, Applies.NON_FINANCIAL),
                c("currentRatio", "Current ratio", StockProfile::currentRatio, 0.9, 2.0, 1, Applies.NON_FINANCIAL),
                c("equityToAssets", "Equity / assets (bank capital)", StockProfile::equityToAssets, 0.08, 0.15, 3,
                        Applies.FINANCIAL),
                c("roa", "Return on assets (bank quality)", StockProfile::roa, 0.005, 0.02, 1, Applies.FINANCIAL),
                c("positiveYears", "Profitable fiscal years", StockProfile::positiveYears, 0.5, 1.0, 2, Applies.ALL),
                c("range52w", "52-week price range (volatility)", StockProfile::range52w, 1.2, 0.3, 1.5, Applies.ALL),
                c("liquidity", "Traded value per day (log10)", QuantScorer::logLiquidity, 8.3, 10.7, 1.5, Applies.ALL),
                c("beta", "Beta", StockProfile::beta, 1.5, 0.5, 0.5, Applies.ALL)));
    }

    private QuantScorer() {
    }

    private static Criterion c(String key, String label, Function<StockProfile, Double> metric, double poor, double good,
                               double weight, Applies applies) {
        return new Criterion(key, label, metric, poor, good, weight, applies);
    }

    /** Lynch distrusts very fast growth: above 30% a year the metric is pulled back (no extra points). */
    static Double lynchGrowth(StockProfile p) {
        Double g = p.netIncomeCagr() != null ? p.netIncomeCagr() : p.earningsGrowth();
        if (g == null) {
            return null;
        }
        return g <= 0.30 ? g : Math.max(0.05, 0.30 - (g - 0.30) / 2);
    }

    static Double logLiquidity(StockProfile p) {
        return p.avgDailyValue() == null || p.avgDailyValue() <= 0 ? null : Math.log10(p.avgDailyValue());
    }

    /** The scorecard of an agent (for the report and the prompts). */
    public static List<String> criteria(InvestorAgent agent) {
        return CARDS.get(agent).stream().map(c -> c.label() + " (" + c.key() + "): poor " + c.poor() + ", good " + c.good())
                .toList();
    }

    public static AgentScore score(InvestorAgent agent, StockProfile p) {
        List<Part> parts = new ArrayList<>();
        double applicable = 0;
        double known = 0;
        double earned = 0;
        for (Criterion c : CARDS.get(agent)) {
            if (!c.appliesTo(p)) {
                continue;
            }
            applicable += c.weight();
            Double value = c.metric().apply(p);
            if (value == null || !Double.isFinite(value)) {
                parts.add(new Part(c.key(), c.label(), null, c.weight(), null));
                continue;
            }
            double points = c.points(value);
            known += c.weight();
            earned += points * c.weight();
            parts.add(new Part(c.key(), c.label(), value, c.weight(), points));
        }
        if (known == 0) {
            return new AgentScore(agent, 0, 0, parts);
        }
        double coverage = known / applicable;
        double score = 100 * (earned / known) * (0.75 + 0.25 * coverage);
        return new AgentScore(agent, round1(score), round3(coverage), parts);
    }

    /**
     * The overall score of the selected agents: the average of the investor agents, blended with the
     * Risk agent (weight {@code riskWeight}) when it is selected; the Risk score alone when only it is.
     */
    public static double overall(Map<InvestorAgent, Double> scores, double riskWeight) {
        double sum = 0;
        int n = 0;
        Double risk = null;
        for (Map.Entry<InvestorAgent, Double> e : scores.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (e.getKey() == InvestorAgent.RISK) {
                risk = e.getValue();
            } else {
                sum += e.getValue();
                n++;
            }
        }
        if (n == 0) {
            return risk == null ? 0 : round1(risk);
        }
        double investors = sum / n;
        return round1(risk == null ? investors : (1 - riskWeight) * investors + riskWeight * risk);
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
