package com.neracalab.backend.screening.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One investor agent's view of one stock (model reply).
 *
 * @param score    0-100 fit with the agent's philosophy (Risk: safety)
 * @param verdict  STRONG_FIT, FIT, NEUTRAL, WEAK or REJECT
 * @param note     the critic's note when the reflection reviewed it
 */
public record Assessment(Double score, String verdict, String thesis, List<String> strengths, List<String> concerns,
                         List<String> metricsUsed, String note) {

    static final Set<String> VERDICTS = Set.of("STRONG_FIT", "FIT", "NEUTRAL", "WEAK", "REJECT");

    /** The verdict band of a score. */
    static String band(double score) {
        if (score >= 80) {
            return "STRONG_FIT";
        }
        if (score >= 65) {
            return "FIT";
        }
        if (score >= 45) {
            return "NEUTRAL";
        }
        return score >= 30 ? "WEAK" : "REJECT";
    }

    /** Lower bound of a verdict's band. */
    static double bandLow(String verdict) {
        return switch (verdict) {
            case "STRONG_FIT" -> 80;
            case "FIT" -> 65;
            case "NEUTRAL" -> 45;
            case "WEAK" -> 30;
            default -> 0;
        };
    }

    /** Upper bound of a verdict's band. */
    static double bandHigh(String verdict) {
        return switch (verdict) {
            case "STRONG_FIT" -> 100;
            case "FIT" -> 79;
            case "NEUTRAL" -> 64;
            case "WEAK" -> 44;
            default -> 29;
        };
    }

    /** Score clamped to 0-100 and rounded; verdict upper case (kept even when it does not match the score). */
    public Assessment normalized() {
        Double s = score == null || !Double.isFinite(score) ? null : (double) Math.round(Math.max(0, Math.min(100, score)));
        String v = verdict == null ? null : verdict.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (v != null && !VERDICTS.contains(v)) {
            v = s == null ? null : band(s);
        }
        if (v == null && s != null) {
            v = band(s);
        }
        return new Assessment(s, v, NewsBrief.clip(thesis, 400), NewsBrief.clip(strengths, 3, 120),
                NewsBrief.clip(concerns, 3, 120), NewsBrief.clip(metricsUsed, 12, 40), note == null ? null : NewsBrief.clip(note, 300));
    }
}
