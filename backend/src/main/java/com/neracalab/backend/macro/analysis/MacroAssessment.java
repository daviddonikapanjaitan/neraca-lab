package com.neracalab.backend.macro.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.neracalab.backend.analysis.Texts;

/**
 * One macro agent's view of a country's stock market (model reply).
 *
 * @param score  0-100: how investable the market is now through the agent's lens
 * @param stance BUY, ACCUMULATE, WAIT or AVOID
 * @param note   the critic's note when the reflection reviewed it
 */
public record MacroAssessment(Double score, String stance, String thesis, List<String> supports, List<String> risks,
                              List<String> buyWhen, List<String> sellWhen, List<String> signalsUsed, String note) {

    static final Set<String> STANCES = Set.of("BUY", "ACCUMULATE", "WAIT", "AVOID");

    /** The stance a score stands for when the reply gives none (or an unknown one). */
    static String stanceOf(double score) {
        if (score >= 70) {
            return "BUY";
        }
        if (score >= 50) {
            return "ACCUMULATE";
        }
        return score >= 35 ? "WAIT" : "AVOID";
    }

    /** Lowest score a stance fits (the bands of the reply format). */
    static double stanceLow(String stance) {
        return switch (stance) {
            case "BUY" -> 65;
            case "ACCUMULATE" -> 50;
            case "WAIT" -> 35;
            default -> 0;
        };
    }

    /** Highest score a stance fits. */
    static double stanceHigh(String stance) {
        return switch (stance) {
            case "BUY" -> 100;
            case "ACCUMULATE" -> 79;
            case "WAIT" -> 64;
            default -> 49;
        };
    }

    /** Score clamped to 0-100 and rounded, a known stance (kept even when it does not match the score), bounded text. */
    public MacroAssessment normalized() {
        Double s = score == null || !Double.isFinite(score) ? null : (double) Math.round(Math.max(0, Math.min(100, score)));
        String st = stance == null ? null : stance.trim().toUpperCase(Locale.ROOT);
        if (st == null || !STANCES.contains(st)) {
            st = s == null ? null : stanceOf(s);
        }
        return new MacroAssessment(s, st, Texts.clipOrNull(thesis, 450), Texts.clip(supports, 3, 160),
                Texts.clip(risks, 3, 160), Texts.clip(buyWhen, 2, 160), Texts.clip(sellWhen, 2, 160),
                Texts.clip(signalsUsed, 12, 40), Texts.clipOrNull(note, 300));
    }
}
