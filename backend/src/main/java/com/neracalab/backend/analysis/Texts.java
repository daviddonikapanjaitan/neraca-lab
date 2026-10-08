package com.neracalab.backend.analysis;

import java.util.List;
import java.util.Objects;

/** Bounds on model-written text kept in the report. */
final class Texts {

    private Texts() {
    }

    /** Trimmed, at most {@code max} characters (an ellipsis marks the cut); "" for null. */
    static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** As {@link #clip(String, int)}, null for null or blank. */
    static String clipOrNull(String text, int max) {
        return text == null || text.isBlank() ? null : clip(text, max);
    }

    /** At most {@code maxItems} non-blank items of at most {@code maxChars} characters. */
    static List<String> clip(List<String> items, int maxItems, int maxChars) {
        if (items == null) {
            return List.of();
        }
        return items.stream().filter(Objects::nonNull).filter(i -> !i.isBlank()).limit(maxItems)
                .map(i -> clip(i, maxChars)).toList();
    }

    /** Whitespace collapsed (PDF text is full of padding). */
    static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
