package com.neracalab.backend.technical.screening;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.neracalab.backend.analysis.Texts;

import tools.jackson.databind.JsonNode;

/**
 * One technical agent's view of one stock (model reply).
 *
 * @param score    0-100: how attractive the chart is now through the agent's lens
 * @param stance   BUY, ACCUMULATE, WAIT or AVOID
 * @param buy      the entry: {"price": 1250, "type": "LIMIT"} (a number alone is accepted; its type is then inferred)
 * @param buyType  the entry type when the model gave it beside a numeric {@code buy}
 * @param sell     the target ({"price": ...} is accepted too)
 * @param stopLoss where the idea is wrong
 * @param note     the critic's note when the reflection reviewed it
 */
public record TechnicalAssessment(Double score, String stance, String thesis, List<String> supports, List<String> risks,
                                  JsonNode buy, String buyType, JsonNode sell, JsonNode stopLoss, List<String> signalsUsed,
                                  String note) {

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

    /** The entry price ({@code buy} as a number or {@code buy.price}); null when missing. */
    public Double buyPrice() {
        return price(buy);
    }

    /** The entry type: {@code buy.type}, else {@code buyType}; null when neither (inferred later). */
    public String entryType() {
        if (buy != null && buy.isObject() && buy.path("type").isString()) {
            return buy.path("type").asString();
        }
        return buyType;
    }

    public Double sellPrice() {
        return price(sell);
    }

    public Double stopPrice() {
        return price(stopLoss);
    }

    /** A price given as a number, a numeric string or {"price": ...}. */
    static Double price(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.isObject() ? node.path("price") : node;
        if (value.isNumber()) {
            double v = value.asDouble();
            return Double.isFinite(v) ? v : null;
        }
        if (value.isString()) {
            try {
                double v = Double.parseDouble(value.asString().replace(",", "").trim());
                return Double.isFinite(v) ? v : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Score clamped to 0-100 and rounded, a known stance (kept even when it does not match the score), bounded text. */
    public TechnicalAssessment normalized() {
        Double s = score == null || !Double.isFinite(score) ? null : (double) Math.round(Math.max(0, Math.min(100, score)));
        String st = stance == null ? null : stance.trim().toUpperCase(Locale.ROOT);
        if (st == null || !STANCES.contains(st)) {
            st = s == null ? null : stanceOf(s);
        }
        return new TechnicalAssessment(s, st, Texts.clipOrNull(thesis, 450), Texts.clip(supports, 3, 160),
                Texts.clip(risks, 3, 160), buy, buyType, sell, stopLoss, Texts.clip(signalsUsed, 12, 60),
                Texts.clipOrNull(note, 300));
    }
}
