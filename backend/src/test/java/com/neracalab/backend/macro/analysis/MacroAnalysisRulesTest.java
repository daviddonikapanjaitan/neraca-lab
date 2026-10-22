package com.neracalab.backend.macro.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.macro.analysis.MacroSynthesisAgent.MacroSynthesis;
import com.neracalab.backend.macro.analysis.MacroSynthesisAgent.SectorTilt;
import com.neracalab.backend.macro.analysis.MacroValidator.Facts;
import com.neracalab.backend.macro.analysis.MacroValidator.Issue;
import com.neracalab.backend.macro.outlook.MacroOutlookService;
import com.neracalab.backend.rag.MacroDocumentType;
import com.neracalab.backend.rag.RagRepository.MacroDocumentRow;
import com.neracalab.backend.rag.RagRepository.MacroHit;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * The rules of the AI macro analysis without a model: the tools over the stored histories (point in time, correlation,
 * limits) and the country's macro documents (refs, limits, offered only when there are documents), the validator, the
 * agents' priors and scores, the action table and the synthesis bounds.
 */
class MacroAnalysisRulesTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 21);
    private static final MacroAnalysisProperties PROPERTIES = new MacroAnalysisProperties(0.2, 0.1, 4, 4, 36, 2, 3, 300, 0.02, 1000, 0.6, 0.2);

    // ------------------------------------------------------------------ tools

    @Test
    void historyReadsOnlyWhatWasPublishedByTheAsOfDate() {
        // September CPI is dated 2026-09-01 but released 2026-10-25: not known on 2026-10-21
        List<Observation> cpi = List.of(new Observation(LocalDate.of(2026, 7, 1), 2.88, LocalDate.of(2026, 8, 3)),
                new Observation(LocalDate.of(2026, 8, 1), 3.19, LocalDate.of(2026, 9, 2)),
                new Observation(LocalDate.of(2026, 9, 1), 4.10, LocalDate.of(2026, 10, 25)));
        MacroTools tools = tools(Map.of("ID_CPI_YOY", cpi), Map.of());
        Map<String, Object> h = tools.history("id_cpi_yoy", 12);
        assertThat(h.get("ref")).isEqualTo("T1");
        assertThat(h.get("latest")).isEqualTo(3.19);
        assertThat(h.get("latestMonth")).isEqualTo("2026-08");
        assertThat(h.get("changePoints")).isEqualTo(0.31);   // a rate: points, not percent
        // the stored extremes, of what was published by then (not the unreleased 4.10)
        assertThat(h.get("storedHigh")).isEqualTo(Map.of("value", 3.19, "date", "2026-08-01"));
        assertThat(h.get("storedLow")).isEqualTo(Map.of("value", 2.88, "date", "2026-07-01"));
        assertThat(h.get("storedSince")).isEqualTo("2026-07-01");
        assertThat(tools.retrieved().getFirst().summary()).contains("3.19 in 2026-08");
        assertThat(tools.refs()).containsExactly("T1");
    }

    /**
     * Net foreign buying is a flow of each session: a month is the sum of its sessions (the live run of 2026-10-21 read
     * the last session, -3,344 bn, as October's selling), and a series that crosses zero changes in points, not percent.
     */
    @Test
    void aFlowIsSummedPerMonthAndChangesInPoints() {
        List<Observation> flows = List.of(
                new Observation(LocalDate.of(2026, 9, 29), -1000, LocalDate.of(2026, 9, 29)),
                new Observation(LocalDate.of(2026, 9, 30), 400, LocalDate.of(2026, 9, 30)),
                new Observation(LocalDate.of(2026, 10, 19), -2000, LocalDate.of(2026, 10, 19)),
                new Observation(LocalDate.of(2026, 10, 20), -3344, LocalDate.of(2026, 10, 20)));
        Map<String, MacroTools.Meta> meta = Map.of("ID_FOREIGN_NET_BUY", new MacroTools.Meta("Net foreign buy", "IDR bn",
                false, true));
        MacroTools tools = new MacroTools(Map.of("ID_FOREIGN_NET_BUY", flows), Map.of(), meta, AS_OF, PROPERTIES);
        Map<String, Object> h = tools.history("ID_FOREIGN_NET_BUY", 6);
        assertThat(h.get("latest")).isEqualTo(-5344.0);
        assertThat(h.get("monthValue")).isEqualTo("sum of the month");
        assertThat(h.get("changePoints")).isEqualTo(-4744.0);
        assertThat(h).doesNotContainKey("changePct");
        assertThat(tools.retrieved().getFirst().summary()).endsWith("; monthly sums");
    }

    @Test
    void correlationOfMonthlyChangesAndTheLimits() {
        Map<String, List<Candle>> prices = new LinkedHashMap<>();
        List<Candle> index = new ArrayList<>();
        List<Candle> coal = new ArrayList<>();
        List<Candle> fx = new ArrayList<>();
        double i = 7000;
        double c = 100;
        double f = 15000;
        for (int m = 0; m < 30; m++) {
            LocalDate d = LocalDate.of(2024, 4, 28).plusMonths(m);
            double move = m % 2 == 0 ? 0.03 : -0.02;
            i *= 1 + move;
            c *= 1 + move * 2;          // moves with the index
            f *= 1 - move;              // moves against it
            index.add(Candle.daily(d, i, i, i, i, i, 0));
            coal.add(Candle.daily(d, c, c, c, c, c, 0));
            fx.add(Candle.daily(d, f, f, f, f, f, 0));
        }
        prices.put("IHSG", index);
        prices.put("COAL", coal);
        prices.put("USDIDR", fx);
        MacroTools tools = tools(Map.of(), prices);
        Map<String, Object> together = tools.correlation("IHSG", "COAL", 24);
        assertThat((Double) together.get("r")).isGreaterThan(0.95);
        assertThat(together.get("reading")).isEqualTo("moved together, strongly");
        Map<String, Object> opposite = tools.correlation("IHSG", "usd/idr", 24);
        assertThat((Double) opposite.get("r")).isLessThan(-0.95);
        assertThat(opposite.get("ref")).isEqualTo("T2");

        assertThatThrownBy(() -> tools.correlation("IHSG", "COAL", 24)).hasMessageContaining("asked already");
        assertThatThrownBy(() -> tools.history("NOPE", 12)).hasMessageContaining("Unknown code");
        assertThatThrownBy(() -> tools.correlation("IHSG", "IHSG", 24)).hasMessageContaining("two different codes");
        tools.history("COAL", 12);
        tools.history("IHSG", 12);
        // max-tool-calls 4: the fifth call is refused
        assertThatThrownBy(() -> tools.history("USDIDR", 12)).hasMessageContaining("limit reached");
    }

    private static MacroTools tools(Map<String, List<Observation>> macro, Map<String, List<Candle>> prices) {
        Map<String, MacroTools.Meta> meta = new LinkedHashMap<>();
        macro.keySet().forEach(k -> meta.put(k, new MacroTools.Meta(k, "%", true, false)));
        prices.keySet().forEach(k -> meta.put(k, new MacroTools.Meta(k, "USD", false, false)));
        return new MacroTools(macro, prices, meta, AS_OF, PROPERTIES);
    }

    // ------------------------------------------------------------------ macro documents (RAG)

    private static final Instant AUGUST_REVIEW = LocalDate.of(2026, 8, 20).atStartOfDay(ZoneId.of("Asia/Jakarta"))
            .toInstant();

    /** A document search over fixed hits (what the research agent may read of the country's macro documents). */
    private static MacroTools.DocumentSearch documents(List<MacroDocumentRow> rows, List<MacroHit> hits) {
        return new MacroTools.DocumentSearch() {
            @Override
            public List<MacroDocumentRow> documents() {
                return rows;
            }

            @Override
            public List<MacroHit> search(String query) {
                return hits;
            }
        };
    }

    private static MacroDocumentRow review() {
        return new MacroDocumentRow(1, "ID", "Monetary Policy Review August 2026", "Bank Indonesia",
                MacroDocumentType.MONETARY_POLICY, AUGUST_REVIEW, "Monetary-Policy-Review-August-2026.pdf", 20, 30_000,
                25, "test-model", AUGUST_REVIEW);
    }

    private static MacroHit hit(long chunkId, int from, int to, String text) {
        return new MacroHit(chunkId, 1, "ID", "Monetary Policy Review August 2026", "Bank Indonesia",
                MacroDocumentType.MONETARY_POLICY, AUGUST_REVIEW, "Monetary-Policy-Review-August-2026.pdf", from, to,
                text, 0.1 * chunkId);
    }

    @Test
    void documentSearchGivesEachExcerptAStableRefAndHasItsOwnLimit() {
        MacroTools tools = new MacroTools(Map.of(), Map.of(), Map.of(), AS_OF, PROPERTIES, documents(List.of(review()),
                List.of(hit(11, 3, 4, "Bank Indonesia   held the BI-Rate\nat 5.75% to keep the rupiah stable."),
                        hit(12, 7, 7, "x".repeat(400)))));
        assertThat(tools.hasDocuments()).isTrue();
        List<Map<String, Object>> first = tools.searchDocuments(" BI   forward guidance ");
        assertThat(first).extracting(m -> m.get("ref")).containsExactly("D1", "D2");
        assertThat(first.getFirst()).containsEntry("issuer", "Bank Indonesia")
                .containsEntry("published", "2026-08-20")
                .containsEntry("pages", "pp. 3-4")
                .containsEntry("type", "Monetary policy")
                .containsEntry("excerpt", "Bank Indonesia held the BI-Rate at 5.75% to keep the rupiah stable.");
        assertThat(first.get(1)).containsEntry("pages", "p. 7");
        assertThat((String) first.get(1).get("excerpt")).hasSize(303);     // excerpt-chars 300, then "..."
        assertThat(tools.retrieved().getFirst()).satisfies(r -> {
            assertThat(r.tool()).isEqualTo("searchDocuments");
            assertThat(r.arguments()).isEqualTo("BI forward guidance");
            assertThat(r.summary()).isEqualTo("Bank Indonesia - Monetary Policy Review August 2026 (2026-08-20, "
                    + "pp. 3-4): Bank Indonesia held the BI-Rate at 5.75% to keep the rupiah stable.");
        });

        // the same chunks found again keep their refs; the same query is not searched twice
        assertThat(tools.searchDocuments("rupiah outlook")).extracting(m -> m.get("ref")).containsExactly("D1", "D2");
        assertThat(tools.retrieved()).hasSize(2);
        assertThatThrownBy(() -> tools.searchDocuments("bi forward GUIDANCE")).hasMessageContaining("searched already");
        // max-document-searches 2: the third is refused; it is not counted against the history calls
        assertThatThrownBy(() -> tools.searchDocuments("fiscal deficit")).hasMessageContaining("Document search limit reached (2)");
        assertThat(tools.calls()).isZero();
        assertThat(tools.refs()).containsExactlyInAnyOrder("D1", "D2");
        assertThat(MacroResearchAgent.offered(MacroResearchAgent.catalog(tools), tools, PROPERTIES))
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactlyInAnyOrder("history", "correlation");
        assertThatThrownBy(() -> tools.searchDocuments("  ")).hasMessageContaining("1 to 300 characters");
    }

    @Test
    void theDocumentSearchIsOfferedOnlyWhenTheCountryHasDocuments() {
        MacroTools with = new MacroTools(Map.of(), Map.of(), Map.of(), AS_OF, PROPERTIES,
                documents(List.of(review()), List.of()));
        assertThat(MacroResearchAgent.offered(MacroResearchAgent.catalog(with), with, PROPERTIES))
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactlyInAnyOrder("history", "correlation", "searchDocuments");
        assertThat(MacroResearchAgent.subjectBlock("Indonesia (IHSG)", "As of 2026-10-21.", with, PROPERTIES))
                .contains("Macro documents searchDocuments can read (1, published by the as-of date, latest first):")
                .contains("- 2026-08-20 Bank Indonesia: Monetary Policy Review August 2026 (monetary policy)")
                .contains("4 history / correlation calls, 2 document searches");

        for (MacroTools none : List.of(new MacroTools(Map.of(), Map.of(), Map.of(), AS_OF, PROPERTIES),
                new MacroTools(Map.of(), Map.of(), Map.of(), AS_OF, PROPERTIES, documents(List.of(), List.of())))) {
            assertThat(none.hasDocuments()).isFalse();
            assertThat(MacroResearchAgent.catalog(none)).containsOnlyKeys("history", "correlation");
            assertThatThrownBy(() -> none.searchDocuments("BI guidance")).hasMessageContaining("No macro documents");
            assertThat(MacroResearchAgent.subjectBlock("Indonesia (IHSG)", "As of 2026-10-21.", none, PROPERTIES))
                    .contains("Macro documents: none stored for this country by the as-of date")
                    .doesNotContain("document searches");
        }
    }

    @Test
    void theReportSaysWhichMacroDocumentsWereNotRead() {
        MacroOutlookService.Choice indonesia = new MacroOutlookService.Choice("ID", "Indonesia", "IDX", "IHSG",
                "Jakarta Composite Index");
        assertThat(MacroAnalysisService.documentNotes(indonesia, AS_OF, 0, 0)).singleElement().asString()
                .startsWith("No macro documents of Indonesia are stored");
        assertThat(MacroAnalysisService.documentNotes(indonesia, AS_OF, 0, 2)).singleElement().asString()
                .isEqualTo("None of the 2 macro documents of Indonesia was published by 2026-10-21: the research read "
                        + "the stored series only.");
        assertThat(MacroAnalysisService.documentNotes(indonesia, AS_OF, 3, 4)).singleElement().asString()
                .isEqualTo("1 of the 4 macro documents of Indonesia were published after 2026-10-21 and were not read "
                        + "(point in time).");
        assertThat(MacroAnalysisService.documentNotes(indonesia, AS_OF, 4, 4)).isEmpty();
        // the first instant after the as-of date in Jakarta (17:00 UTC the day before)
        assertThat(MacroDocumentSearch.publishedBefore(AS_OF)).isEqualTo(Instant.parse("2026-10-21T17:00:00Z"));
        assertThat(MacroAnalysisService.documentList(List.of(review()))).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("publishedOn", "2026-08-20").containsEntry("documentType", "MONETARY_POLICY")
                    .containsEntry("documentTypeLabel", "Monetary policy").containsEntry("issuer", "Bank Indonesia");
        });
    }

    // ------------------------------------------------------------------ validator

    @Test
    void theValidatorHoldsEachInvestorToHisOwnRules() {
        Set<String> keys = Set.of("LIQUIDITY", "BI_RATE");
        Facts belowAverage = new Facts(false, -26.0, "TIGHTENING", "VICIOUS", "STAGFLATION");
        // Tudor Jones buying below the 200-day average
        assertThat(codes(MacroValidator.validate(MacroAgent.TUDOR_JONES, answer(70, "BUY"), 68.0, belowAverage, keys)))
                .containsExactly("RULE");
        // Druckenmiller scoring 70+ into tightening liquidity, Soros into a vicious loop
        assertThat(codes(MacroValidator.validate(MacroAgent.DRUCKENMILLER, answer(72, "BUY"), 60.0, belowAverage, keys)))
                .containsExactly("RULE");
        assertThat(codes(MacroValidator.validate(MacroAgent.SOROS, answer(72, "BUY"), 60.0, belowAverage, keys)))
                .containsExactly("RULE");
        // Dalio never buys stagflation
        assertThat(codes(MacroValidator.validate(MacroAgent.DALIO, answer(66, "BUY"), 60.0, belowAverage, keys)))
                .containsExactly("RULE");
        // the same scores above the average, with easing money, a virtuous loop and Goldilocks: no issue
        Facts good = new Facts(true, 5.0, "EASING", "VIRTUOUS", "GOLDILOCKS");
        for (MacroAgent agent : MacroAgent.values()) {
            assertThat(MacroValidator.validate(agent, answer(72, "BUY"), 70.0, good, keys)).as(agent.name()).isEmpty();
        }
        // a score far from the prior, a stance outside its band, signals not in the dossier
        List<Issue> issues = MacroValidator.validate(MacroAgent.DALIO, new MacroAssessment(20.0, "BUY", "x",
                List.of("a"), List.of(), List.of(), List.of(), List.of("liquidity", "GDP"), null), 70.0, good, keys);
        assertThat(codes(issues)).containsExactly("DIVERGENCE", "STANCE", "UNKNOWN_SIGNAL");
        assertThat(issues.get(2).message()).endsWith("GDP");
    }

    private static MacroAssessment answer(double score, String stance) {
        return new MacroAssessment(score, stance, "thesis", List.of("a"), List.of("b"), List.of(), List.of(),
                List.of("liquidity"), null).normalized();
    }

    private static List<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).toList();
    }

    @Test
    void anAnswerIsNormalizedToKnownStancesAndBounds() {
        MacroAssessment a = new MacroAssessment(104.6, "strong buy", " x ", List.of("1", "2", "3", "4"), null, null, null,
                null, null).normalized();
        assertThat(a.score()).isEqualTo(100.0);
        assertThat(a.stance()).isEqualTo("BUY");
        assertThat(a.supports()).hasSize(3);
        assertThat(a.risks()).isEmpty();
        assertThat(MacroAssessment.stanceOf(49)).isEqualTo("WAIT");
        assertThat(MacroAssessment.stanceOf(34)).isEqualTo("AVOID");
    }

    // ------------------------------------------------------------------ priors, scores, action

    @Test
    void thePriorIsThePresetOnZeroToHundred() {
        Map<String, Object> view = Map.of("presets", List.of(
                Map.of("preset", "druckenmiller", "score", -3.0, "maxScore", 6, "stance", "AVOID", "stanceLabel", "Avoid"),
                Map.of("preset", "soros", "score", 4.0, "maxScore", 5, "stance", "BUY", "stanceLabel", "Buy",
                        "loop", "VIRTUOUS"),
                Map.of("preset", "dalio", "stance", "WAIT", "stanceLabel", "Wait", "maxScore", 0, "quadrant", "MIXED")));
        MacroPanel.Prior druckenmiller = MacroAnalysisService.prior(view, MacroAgent.DRUCKENMILLER);
        assertThat(druckenmiller.score()).isEqualTo(25.0);
        assertThat(druckenmiller.presetText()).isEqualTo("-3 of 6");
        assertThat(MacroAnalysisService.prior(view, MacroAgent.SOROS).score()).isEqualTo(90.0);
        assertThat(MacroAnalysisService.prior(view, MacroAgent.DALIO).score()).isNull();
        assertThat(MacroAnalysisService.prior(view, MacroAgent.TUDOR_JONES).presetText()).isEqualTo("no preset");
        Facts facts = MacroAnalysisService.facts(view);
        assertThat(facts.loop()).isEqualTo("VIRTUOUS");
        assertThat(facts.quadrant()).isEqualTo("MIXED");
    }

    @Test
    void scoresBlendThePriorAndTheActionFollowsTheRulesTable() {
        assertThat(MacroAnalysisService.blend(40.0, 70.0, 0.6)).isEqualTo(52.0);
        assertThat(MacroAnalysisService.blend(null, 70.0, 0.6)).isEqualTo(70.0);
        assertThat(MacroAnalysisService.blend(40.0, null, 0.6)).isEqualTo(40.0);
        assertThat(MacroAnalysisService.blend(null, null, 0.6)).isNull();

        assertThat(MacroAnalysisService.verdict(60)).isEqualTo("INVESTABLE");
        assertThat(MacroAnalysisService.verdict(59.9)).isEqualTo("SELECTIVE");
        assertThat(MacroAnalysisService.verdict(39.9)).isEqualTo("NOT_INVESTABLE");

        assertThat(MacroAnalysisService.action(65, true, "EASING")).isEqualTo("BUY");
        assertThat(MacroAnalysisService.action(65, false, "EASING")).isEqualTo("ACCUMULATE");
        assertThat(MacroAnalysisService.action(50, true, "NEUTRAL")).isEqualTo("ACCUMULATE");
        assertThat(MacroAnalysisService.action(50, true, "TIGHTENING")).isEqualTo("WAIT");
        assertThat(MacroAnalysisService.action(50, false, "EASING")).isEqualTo("WAIT");
        assertThat(MacroAnalysisService.action(30, false, "EASING")).isEqualTo("SELL");
        assertThat(MacroAnalysisService.action(30, true, "EASING")).isEqualTo("WAIT");
        assertThat(MacroAnalysisService.action(30, null, "EASING")).isEqualTo("WAIT");
    }

    // ------------------------------------------------------------------ synthesis

    @Test
    void theSynthesisIsBounded() {
        MacroSynthesis s = MacroSynthesisAgent.normalize(new MacroSynthesis("summary", "very high", null, null, null, null,
                null, List.of(new SectorTilt("Coal", "overweight", "up"), new SectorTilt(" ", "UNDERWEIGHT", "x"),
                new SectorTilt("Banks", "sideways", null)), null, -12.4, "too bullish", null, null), 50.0, "opus");
        assertThat(s.adjustment()).isEqualTo(-5.0);
        assertThat(s.conviction()).isEqualTo("LOW");    // unknown: from the score, 50 is no clear call
        assertThat(s.sectorTilts()).extracting(SectorTilt::tilt).containsExactly("OVERWEIGHT", "NEUTRAL");
        assertThat(MacroSynthesisAgent.normalize(s, null, "opus").adjustment()).isZero();
        assertThat(MacroSynthesisAgent.conviction(72.0)).isEqualTo("HIGH");
        assertThat(MacroSynthesisAgent.conviction(25.0)).isEqualTo("HIGH");
        assertThat(MacroSynthesisAgent.conviction(41.0)).isEqualTo("LOW");
        assertThat(MacroSynthesisAgent.conviction(62.0)).isEqualTo("MEDIUM");
    }
}
