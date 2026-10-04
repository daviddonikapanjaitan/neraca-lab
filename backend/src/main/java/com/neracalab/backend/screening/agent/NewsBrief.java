package com.neracalab.backend.screening.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The research agent's summary of a stock's news.
 *
 * @param sentiment POSITIVE, NEUTRAL, NEGATIVE or MIXED
 * @param sources   URLs the brief relies on
 */
public record NewsBrief(String sentiment, String summary, List<String> catalysts, List<String> risks,
                        List<String> sources) {

    private static final Set<String> SENTIMENTS = Set.of("POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED");

    /** A brief written without a model (no news found, or the budget did not allow a call). */
    public static NewsBrief without(String summary) {
        return new NewsBrief("NEUTRAL", summary, List.of(), List.of(), List.of());
    }

    /** Cleans a model reply: known sentiment, at most 3 / 3 / 4 entries, bounded lengths. */
    public NewsBrief normalized() {
        String s = sentiment == null ? "NEUTRAL" : sentiment.trim().toUpperCase(Locale.ROOT);
        return new NewsBrief(SENTIMENTS.contains(s) ? s : "NEUTRAL", clip(summary, 600), clip(catalysts, 3, 160),
                clip(risks, 3, 160), clip(sources, 4, 1000));
    }

    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static List<String> clip(List<String> items, int maxItems, int maxChars) {
        if (items == null) {
            return List.of();
        }
        return items.stream().filter(i -> i != null && !i.isBlank()).limit(maxItems).map(i -> clip(i, maxChars)).toList();
    }
}
