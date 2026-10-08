package com.neracalab.backend.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The research agent's brief of one company, from its stored filings and news: business, moat, management,
 * growth, risks, catalysts, news and the evidence behind them (refs of the retrieved excerpts: F1 = filing,
 * N1 = news).
 *
 * @param newsSentiment POSITIVE, NEUTRAL, NEGATIVE, MIXED or NONE (no news)
 */
public record ResearchBrief(String business, String moat, String management, String growth, List<String> risks,
                            List<String> catalysts, String newsSentiment, String newsSummary, List<Evidence> evidence) {

    private static final Set<String> SENTIMENTS = Set.of("POSITIVE", "NEUTRAL", "NEGATIVE", "MIXED", "NONE");

    public record Evidence(String ref, String fact) {
    }

    /** A brief written without a model (no documents, or the budget or the model failed). */
    public static ResearchBrief without(String note) {
        return new ResearchBrief(note, null, null, null, List.of(), List.of(), "NONE", null, List.of());
    }

    /** Cleans a model reply: known sentiment, bounded lists and lengths, evidence with a ref and a fact. */
    public ResearchBrief normalized() {
        String s = newsSentiment == null ? "NONE" : newsSentiment.trim().toUpperCase(Locale.ROOT);
        List<Evidence> facts = evidence == null ? List.of() : evidence.stream().filter(Objects::nonNull)
                .filter(e -> e.ref() != null && !e.ref().isBlank() && e.fact() != null && !e.fact().isBlank())
                .limit(8)
                .map(e -> new Evidence(Texts.clip(e.ref().trim().toUpperCase(Locale.ROOT), 12), Texts.clip(e.fact(), 240)))
                .toList();
        return new ResearchBrief(Texts.clipOrNull(business, 500), Texts.clipOrNull(moat, 400),
                Texts.clipOrNull(management, 400), Texts.clipOrNull(growth, 400), Texts.clip(risks, 4, 160),
                Texts.clip(catalysts, 4, 160), SENTIMENTS.contains(s) ? s : "NONE", Texts.clipOrNull(newsSummary, 500),
                facts);
    }

    /** The brief keeping only evidence whose ref was retrieved (a ref the model made up is dropped). */
    public ResearchBrief withKnownRefs(Set<String> refs) {
        return new ResearchBrief(business, moat, management, growth, risks, catalysts, newsSentiment, newsSummary,
                evidence.stream().filter(e -> refs.contains(e.ref())).toList());
    }
}
