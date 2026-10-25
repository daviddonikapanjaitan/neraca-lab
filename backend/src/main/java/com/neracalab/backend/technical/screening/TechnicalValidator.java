package com.neracalab.backend.technical.screening;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reflection, step 1 of the technical screening: checks a technical agent's answer against the stock's data, without a
 * model. Each issue has a code (aggregated into Reflexion lessons) and a message (sent to the critic).
 * <ul>
 *   <li>DIVERGENCE: the score is far from the agent's rules summary (its prior)</li>
 *   <li>STANCE: the stance does not match the score band</li>
 *   <li>RULE: the answer breaks a rule of the trader's own method (Tudor Jones buying below the 200-day average, Murphy
 *       buying a primary and intermediate downtrend, Druckenmiller scoring a laggard high in a falling market, Gill
 *       buying near the 52-week high without a base)</li>
 *   <li>LEVELS: the trade plan is incomplete, out of order or out of range ({@link TradePlan#problems})</li>
 *   <li>UNKNOWN_SIGNAL: the agent cites signals that are not in the dossier</li>
 *   <li>EMPTY: no score or no thesis</li>
 * </ul>
 */
public final class TechnicalValidator {

    static final double MAX_DIVERGENCE = 35;
    private static final double STANCE_TOLERANCE = 5;

    public record Issue(String code, String message) {
    }

    /**
     * The facts of the dossier the rules check (NaN / null when unknown).
     *
     * @param indexAbove200  IHSG closes above its 200-day average
     * @param rsPercentile6m the stock's 6-month return as a percentile of the universe
     * @param range52        the close's position in its 52-week range, 0 (the low) - 1 (the high)
     * @param baseDetected   a base (a tight range with contracting volatility) was detected
     */
    public record Facts(double close, double sma50, double sma200, Boolean indexAbove200, double rsPercentile6m,
                        double range52, boolean baseDetected) {
    }

    private TechnicalValidator() {
    }

    /**
     * @param prior      the agent's rules prior, 0-100 (null: none, no divergence check)
     * @param signalKeys the keys an answer may cite in {@code signalsUsed} (lower case)
     */
    public static List<Issue> validate(TechnicalAgent agent, TechnicalAssessment a, Double prior, Facts facts,
                                       Set<String> signalKeys) {
        List<Issue> issues = new ArrayList<>();
        if (a.score() == null) {
            issues.add(new Issue("EMPTY", "No score was given"));
            return issues;
        }
        double s = a.score();
        if (prior != null && Math.abs(s - prior) > MAX_DIVERGENCE) {
            issues.add(new Issue("DIVERGENCE", "Score " + fmt(s) + " is " + fmt(Math.abs(s - prior))
                    + " points from your rules prior " + fmt(prior) + "; justify it from the chart data or move closer"));
        }
        if (a.stance() != null && (s < TechnicalAssessment.stanceLow(a.stance()) - STANCE_TOLERANCE
                || s > TechnicalAssessment.stanceHigh(a.stance()) + STANCE_TOLERANCE)) {
            issues.add(new Issue("STANCE", "Stance " + a.stance() + " does not match score " + fmt(s)
                    + " (BUY 65-100, ACCUMULATE 50-79, WAIT 35-64, AVOID 0-49)"));
        }
        String rule = facts == null ? null : rule(agent, a, facts);
        if (rule != null) {
            issues.add(new Issue("RULE", rule));
        }
        if (facts != null) {
            List<String> problems = TradePlan.problems(a.buyPrice(), a.entryType(), a.sellPrice(), a.stopPrice(),
                    facts.close());
            if (!problems.isEmpty()) {
                issues.add(new Issue("LEVELS", "Trade plan: " + String.join("; ", problems)));
            }
        }
        if (a.signalsUsed() != null) {
            List<String> unknown = a.signalsUsed().stream().filter(k -> !known(k, signalKeys)).toList();
            if (!unknown.isEmpty()) {
                issues.add(new Issue("UNKNOWN_SIGNAL", "Cites signals that are not in the dossier: " + String.join(", ", unknown)));
            }
        }
        if (a.thesis() == null || a.thesis().isBlank()) {
            issues.add(new Issue("EMPTY", "No thesis was given"));
        }
        return issues;
    }

    /** A cited key is known as a dossier key or path ("rsi14", "momentum.rsi14"), its last part, or "rules". */
    static boolean known(String cited, Set<String> signalKeys) {
        String k = cited == null ? "" : cited.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || signalKeys.contains(k) || k.startsWith("rules")) {
            return true;
        }
        int dot = k.lastIndexOf('.');
        return dot >= 0 && signalKeys.contains(k.substring(dot + 1));
    }

    /** A rule of the trader's method that the answer breaks, or null. */
    static String rule(TechnicalAgent agent, TechnicalAssessment a, Facts f) {
        double s = a.score();
        boolean buying = "BUY".equals(a.stance()) || s >= 65;
        return switch (agent) {
            case TUDOR_JONES -> buying && ok(f.sma200()) && f.close() < f.sma200()
                    ? "Tudor Jones buys nothing below its 200-day average, and the close " + fmt(f.close()) + " is "
                    + fmt(Math.abs(pct(f.close(), f.sma200()))) + "% below the SMA 200 " + fmt(f.sma200())
                    + "; a score of " + fmt(s) + " / " + a.stance() + " breaks the rule" : null;
            case MURPHY -> buying && ok(f.sma200()) && ok(f.sma50()) && f.close() < f.sma200() && f.close() < f.sma50()
                    ? "Murphy trades with the primary trend, and the close is below both the SMA 50 (" + fmt(f.sma50())
                    + ") and the SMA 200 (" + fmt(f.sma200()) + "): a downtrend; a score of " + fmt(s) + " / "
                    + a.stance() + " needs a confirmed turn" : null;
            case DRUCKENMILLER -> s >= 70 && Boolean.FALSE.equals(f.indexAbove200()) && ok(f.rsPercentile6m())
                    && f.rsPercentile6m() < 50
                    ? "Druckenmiller owns leaders while the market supports them, and IHSG is below its 200-day average "
                    + "while the stock's 6-month return is only in the " + Math.round(f.rsPercentile6m())
                    + "th percentile; a score of " + fmt(s) + " needs evidence of leadership" : null;
            case GILL -> buying && ok(f.range52()) && f.range52() >= 0.85 && !f.baseDetected()
                    ? "Gill buys what is out of favour, and the close is at " + Math.round(f.range52() * 100)
                    + "% of its 52-week range without a base; a score of " + fmt(s) + " / " + a.stance()
                    + " is not a contrarian setup" : null;
        };
    }

    private static boolean ok(double v) {
        return Double.isFinite(v);
    }

    private static double pct(double a, double b) {
        return b == 0 ? 0 : (a / b - 1) * 100;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
