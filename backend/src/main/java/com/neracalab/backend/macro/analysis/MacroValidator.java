package com.neracalab.backend.macro.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reflection, step 1 of the macro analysis: checks a macro agent's answer against the dossier, without a model. Each
 * issue has a code (aggregated into Reflexion lessons) and a message (sent to the critic).
 * <ul>
 *   <li>DIVERGENCE: the score is far from the agent's rules prior (its preset)</li>
 *   <li>STANCE: the stance does not match the score band</li>
 *   <li>RULE: the score breaks a rule of the investor's own method (Tudor Jones buying below the 200-day average,
 *       Druckenmiller scoring high into tightening liquidity, Soros into a vicious loop, Dalio buying stagflation)</li>
 *   <li>UNKNOWN_SIGNAL: the agent cites signals that are not in the dossier</li>
 *   <li>EMPTY: no score or no thesis</li>
 * </ul>
 */
public final class MacroValidator {

    static final double MAX_DIVERGENCE = 35;
    private static final double STANCE_TOLERANCE = 5;

    public record Issue(String code, String message) {
    }

    /**
     * The facts of the dossier the rules check.
     *
     * @param aboveSma200 the index closes above its 200-day average (null: unknown)
     * @param vsSma200Pct the index against its 200-day average, percent
     * @param liquidity   the liquidity regime: EASING, NEUTRAL, TIGHTENING (null: unknown)
     * @param loop        the Soros loop: VIRTUOUS, NEUTRAL, VICIOUS (null: unknown)
     * @param quadrant    the Dalio quadrant: GOLDILOCKS, REFLATION, STAGFLATION, DISINFLATIONARY_SLOWDOWN, MIXED
     */
    public record Facts(Boolean aboveSma200, Double vsSma200Pct, String liquidity, String loop, String quadrant) {
    }

    private MacroValidator() {
    }

    /**
     * @param prior     the agent's rules prior, 0-100 (null: none, no divergence check)
     * @param signalKeys the keys and codes an answer may cite in {@code signalsUsed} (upper case)
     */
    public static List<Issue> validate(MacroAgent agent, MacroAssessment a, Double prior, Facts facts,
                                       Set<String> signalKeys) {
        List<Issue> issues = new ArrayList<>();
        if (a.score() == null) {
            issues.add(new Issue("EMPTY", "No score was given"));
            return issues;
        }
        double s = a.score();
        if (prior != null && Math.abs(s - prior) > MAX_DIVERGENCE) {
            issues.add(new Issue("DIVERGENCE", "Score " + fmt(s) + " is " + fmt(Math.abs(s - prior))
                    + " points from your rules prior " + fmt(prior) + "; justify it from the dossier or move closer"));
        }
        if (a.stance() != null && (s < MacroAssessment.stanceLow(a.stance()) - STANCE_TOLERANCE
                || s > MacroAssessment.stanceHigh(a.stance()) + STANCE_TOLERANCE)) {
            issues.add(new Issue("STANCE", "Stance " + a.stance() + " does not match score " + fmt(s)
                    + " (BUY 65-100, ACCUMULATE 50-79, WAIT 35-64, AVOID 0-49)"));
        }
        String rule = facts == null ? null : rule(agent, a, facts);
        if (rule != null) {
            issues.add(new Issue("RULE", rule));
        }
        if (a.signalsUsed() != null) {
            List<String> unknown = a.signalsUsed().stream()
                    .filter(k -> !signalKeys.contains(k.trim().toUpperCase(Locale.ROOT))).toList();
            if (!unknown.isEmpty()) {
                issues.add(new Issue("UNKNOWN_SIGNAL", "Cites signals that are not in the dossier: " + String.join(", ", unknown)));
            }
        }
        if (a.thesis() == null || a.thesis().isBlank()) {
            issues.add(new Issue("EMPTY", "No thesis was given"));
        }
        return issues;
    }

    /** A rule of the investor's method that the answer breaks, or null. */
    static String rule(MacroAgent agent, MacroAssessment a, Facts f) {
        double s = a.score();
        boolean buying = "BUY".equals(a.stance()) || s >= 65;
        return switch (agent) {
            case TUDOR_JONES -> buying && Boolean.FALSE.equals(f.aboveSma200())
                    ? "Tudor Jones buys nothing below its 200-day average, and the index is " + (f.vsSma200Pct() == null ? ""
                    : fmt(Math.abs(f.vsSma200Pct())) + "% ") + "below it; a score of " + fmt(s) + " / " + a.stance()
                    + " breaks the rule" : null;
            case DRUCKENMILLER -> s >= 70 && "TIGHTENING".equals(f.liquidity())
                    ? "Druckenmiller sells when liquidity is tightening, and the liquidity regime is tightening; a score of "
                    + fmt(s) + " needs evidence that money is turning easier" : null;
            case SOROS -> s >= 70 && "VICIOUS".equals(f.loop())
                    ? "The reflexive loop of foreign money, the rupiah and BI is vicious (a bust); a score of " + fmt(s)
                    + " needs evidence that the loop is turning" : null;
            case DALIO -> buying && "STAGFLATION".equals(f.quadrant())
                    ? "Dalio never buys stagflation, and the quadrant is stagflation; a score of " + fmt(s) + " / "
                    + a.stance() + " breaks the rule" : null;
        };
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
