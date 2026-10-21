package com.neracalab.backend.macro.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.neracalab.backend.analysis.Texts;

/**
 * The macro research agent's brief of one country, from the stored macro series, commodities, currency pairs and
 * indexes: where the country is in the cycle, liquidity, inflation and rates, the external side (rupiah, reserves,
 * flows, terms of trade), growth, risks, catalysts and the evidence behind them (refs of the tool results: T1, T2, ...).
 */
public record MacroBrief(String regime, String liquidity, String inflationRates, String external, String growth,
                         List<String> risks, List<String> catalysts, List<Evidence> evidence) {

    public record Evidence(String ref, String fact) {
    }

    /** A brief written without a model (the budget did not allow it, or the model failed). */
    public static MacroBrief without(String note) {
        return new MacroBrief(note, null, null, null, null, List.of(), List.of(), List.of());
    }

    /** Cleans a model reply: bounded lists and lengths, evidence with a ref and a fact. */
    public MacroBrief normalized() {
        List<Evidence> facts = evidence == null ? List.of() : evidence.stream().filter(Objects::nonNull)
                .filter(e -> e.ref() != null && !e.ref().isBlank() && e.fact() != null && !e.fact().isBlank())
                .limit(8)
                .map(e -> new Evidence(Texts.clip(e.ref().trim().toUpperCase(Locale.ROOT), 12), Texts.clip(e.fact(), 240)))
                .toList();
        return new MacroBrief(Texts.clipOrNull(regime, 500), Texts.clipOrNull(liquidity, 400),
                Texts.clipOrNull(inflationRates, 400), Texts.clipOrNull(external, 400), Texts.clipOrNull(growth, 400),
                Texts.clip(risks, 4, 160), Texts.clip(catalysts, 4, 160), facts);
    }

    /** The brief keeping only evidence whose ref was returned by a tool (a ref the model made up is dropped). */
    public MacroBrief withKnownRefs(Set<String> refs) {
        return new MacroBrief(regime, liquidity, inflationRates, external, growth, risks, catalysts,
                evidence.stream().filter(e -> refs.contains(e.ref())).toList());
    }
}
