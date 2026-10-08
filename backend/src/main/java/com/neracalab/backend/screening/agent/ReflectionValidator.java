package com.neracalab.backend.screening.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.quant.StockProfile;

/**
 * Reflection, step 1: checks an investor agent's assessment against the data, without a model.
 * Each issue has a code (aggregated into Reflexion lessons) and a message (sent to the critic).
 * Without a quantitative score or metrics (a single-stock analysis without market data) the checks that need
 * them are skipped.
 * <ul>
 *   <li>DIVERGENCE: the score is far from the quantitative scorecard</li>
 *   <li>VERDICT: the verdict does not match the score band</li>
 *   <li>RULE: the score breaks a hard rule of the philosophy (e.g. Buffett 70+ with ROE below 10%)</li>
 *   <li>UNKNOWN_METRIC: the agent cites metrics that are not in the data</li>
 *   <li>EMPTY: no thesis</li>
 * </ul>
 */
public final class ReflectionValidator {

    static final double MAX_DIVERGENCE = 35;
    private static final double VERDICT_TOLERANCE = 5;

    public record Issue(String code, String message) {
    }

    private ReflectionValidator() {
    }

    public static List<Issue> validate(InvestorAgent agent, Assessment a, double quantScore, StockProfile p,
                                       Set<String> metricKeys) {
        return validate(agent, a, Double.valueOf(quantScore), p, metricKeys);
    }

    /**
     * @param quantScore the quantitative score (null: none, no divergence check)
     * @param p          the stock's metrics (null: none, no philosophy rules)
     */
    public static List<Issue> validate(InvestorAgent agent, Assessment a, Double quantScore, StockProfile p,
                                       Set<String> metricKeys) {
        List<Issue> issues = new ArrayList<>();
        if (a.score() == null) {
            issues.add(new Issue("EMPTY", "No score was given"));
            return issues;
        }
        double s = a.score();
        if (quantScore != null && Math.abs(s - quantScore) > MAX_DIVERGENCE) {
            issues.add(new Issue("DIVERGENCE", "Score " + fmt(s) + " is " + fmt(Math.abs(s - quantScore))
                    + " points from the quantitative score " + fmt(quantScore) + "; justify it from the data or move closer"));
        }
        if (a.verdict() != null && (s < Assessment.bandLow(a.verdict()) - VERDICT_TOLERANCE
                || s > Assessment.bandHigh(a.verdict()) + VERDICT_TOLERANCE)) {
            issues.add(new Issue("VERDICT", "Verdict " + a.verdict() + " does not match score " + fmt(s) + " (bands: "
                    + ScreeningPrompts.VERDICTS + ")"));
        }
        String rule = p == null ? null : rule(agent, s, p);
        if (rule != null) {
            issues.add(new Issue("RULE", rule));
        }
        if (a.metricsUsed() != null) {
            List<String> unknown = a.metricsUsed().stream()
                    .filter(m -> !metricKeys.contains(m) && !metricKeys.contains(m.replace("annual.", ""))).toList();
            if (!unknown.isEmpty()) {
                issues.add(new Issue("UNKNOWN_METRIC", "Cites metrics that are not in the data: " + String.join(", ", unknown)));
            }
        }
        if (a.thesis() == null || a.thesis().isBlank()) {
            issues.add(new Issue("EMPTY", "No thesis was given"));
        }
        return issues;
    }

    /** A hard rule of the philosophy broken by a high score, or null. */
    static String rule(InvestorAgent agent, double score, StockProfile p) {
        if (score < 70) {
            return null;
        }
        return switch (agent) {
            case BUFFETT -> {
                if (lt(p.roe(), 0.10)) {
                    yield "Buffett score 70+ with ROE " + pct(p.roe()) + " (below 10%)";
                }
                if (lt(p.positiveYears(), 0.75)) {
                    yield "Buffett score 70+ although net income was negative in recent fiscal years";
                }
                yield gt(p.debtToEquity(), 1.5) ? "Buffett score 70+ with debt/equity " + fmt2(p.debtToEquity()) + " (above 1.5)" : null;
            }
            case MUNGER -> {
                if (lt(p.roe(), 0.12)) {
                    yield "Munger score 70+ with ROE " + pct(p.roe()) + " (below 12%)";
                }
                yield lt(p.operatingMargin(), 0.08) ? "Munger score 70+ with operating margin " + pct(p.operatingMargin())
                        + " (below 8%)" : null;
            }
            case LYNCH -> {
                if (gt(p.peg(), 2.0)) {
                    yield "Lynch score 70+ with PEG " + fmt2(p.peg()) + " (above 2)";
                }
                yield p.peg() == null && gt(p.pe(), 25) ? "Lynch score 70+ with P/E " + fmt2(p.pe()) + " and no growth to justify it" : null;
            }
            case FISHER -> lt(p.revenueCagr(), 0.03) ? "Fisher score 70+ with revenue CAGR " + pct(p.revenueCagr())
                    + " (below 3%)" : null;
            case GILL -> gt(p.pb(), 2.5) && (p.fcfYield() == null || p.fcfYield() < 0.03)
                    ? "Gill score 70+ although the stock is not cheap (P/B " + fmt2(p.pb()) + ", low or unknown FCF yield)" : null;
            case RISK -> {
                if (gt(p.debtToEquity(), 2.0)) {
                    yield "Risk (safety) score 70+ with debt/equity " + fmt2(p.debtToEquity()) + " (above 2)";
                }
                if (lt(p.altmanZ(), 1.8)) {
                    yield "Risk (safety) score 70+ with Altman Z " + fmt2(p.altmanZ()) + " (distress zone below 1.8)";
                }
                if (lt(p.interestCoverage(), 1.5)) {
                    yield "Risk (safety) score 70+ with interest coverage " + fmt2(p.interestCoverage()) + " (below 1.5)";
                }
                yield lt(p.positiveYears(), 0.75) ? "Risk (safety) score 70+ although earnings were negative in recent years" : null;
            }
        };
    }

    private static boolean lt(Double v, double bound) {
        return v != null && v < bound;
    }

    private static boolean gt(Double v, double bound) {
        return v != null && v > bound;
    }

    private static String pct(Double v) {
        return v == null ? "unknown" : Math.round(v * 1000) / 10.0 + "%";
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v));
    }

    private static String fmt2(Double v) {
        return v == null ? "unknown" : String.valueOf(Math.round(v * 100) / 100.0);
    }
}
