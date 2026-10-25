package com.neracalab.backend.technical.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.TestScreeningProperties;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.ListingPriceRepository;
import com.neracalab.backend.screening.data.ListingPriceRepository.ListingBars;
import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalRepository;
import com.neracalab.backend.technical.TechnicalService;
import com.neracalab.backend.technical.TechnicalTestData;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.idx.IdxTickSize;
import com.neracalab.backend.technical.screening.TechnicalScreener.Evaluation;
import com.neracalab.backend.technical.screening.TechnicalSynthesisAgent.Fallback;
import com.neracalab.backend.technical.screening.TechnicalSynthesisAgent.Row;
import com.neracalab.backend.technical.screening.TechnicalSynthesisAgent.Synthesis;
import com.neracalab.backend.technical.screening.TechnicalValidator.Facts;
import com.neracalab.backend.technical.screening.TechnicalValidator.Issue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The technical screening without a database or a model: trade plans, the validator, the synthesis' normalization and
 * Stage 1 of a market-cap tier on the IDX fixtures (HRTA, MYOR, SMSM and IHSG).
 */
class TechnicalScreeningRulesTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final TechnicalProperties TECHNICAL = TechnicalTestData.properties();
    private static final IdxTickSize TICKS = new IdxTickSize(TECHNICAL.tickSizes());
    private final JsonMapper json = JsonMapper.builder().build();

    static TechnicalScreeningProperties properties() {
        return new TechnicalScreeningProperties(0.5, 0.2, 3, 100, 50, 0.4, 120, Duration.ofDays(7), 700, 16000,
                new TechnicalScreeningProperties.PriceHistory(5, true));
    }

    // ------------------------------------------------------------------ trade plans

    @Test
    void aTradePlanMustBeCompleteOrderedAndNearTheClose() {
        assertThat(TradePlan.problems(950.0, "LIMIT", 1100.0, 900.0, 1000)).isEmpty();
        assertThat(TradePlan.problems(1020.0, "STOP", 1200.0, 960.0, 1000)).isEmpty();
        // missing levels
        assertThat(TradePlan.problems(null, "LIMIT", 1100.0, 900.0, 1000)).hasSize(1);
        // out of order
        assertThat(TradePlan.problems(950.0, "LIMIT", 940.0, 900.0, 1000))
                .anyMatch(p -> p.contains("stop loss < buy < sell"));
        // a limit buy far above the close would fill at once, a stop buy below it is no breakout
        assertThat(TradePlan.problems(1100.0, "LIMIT", 1300.0, 1000.0, 1000)).anyMatch(p -> p.contains("LIMIT buy"));
        assertThat(TradePlan.problems(900.0, "STOP", 1100.0, 850.0, 1000)).anyMatch(p -> p.contains("STOP buy"));
        // too far, too much risk, too much target
        assertThat(TradePlan.problems(500.0, "LIMIT", 700.0, 450.0, 1000)).anyMatch(p -> p.contains("too far"));
        assertThat(TradePlan.problems(1000.0, "LIMIT", 1200.0, 500.0, 1000)).anyMatch(p -> p.contains("40%"));
        assertThat(TradePlan.problems(1000.0, "LIMIT", 3500.0, 900.0, 1000)).anyMatch(p -> p.contains("3 times"));
    }

    @Test
    void aModelPlanIsRoundedToValidIdxPricesWithItsRewardToRisk() {
        // 2,000 - 5,000: tick 10; 5,000 and above: tick 25
        TradePlan plan = TradePlan.ofModel(4_012.0, null, 5_033.0, 3_811.0, 4_100, TICKS, "AI", "MURPHY", "Murphy (AI)");
        assertThat(plan).isNotNull();
        assertThat(plan.buy().price()).isEqualTo(4_010);
        assertThat(plan.buy().type()).isEqualTo("LIMIT");
        assertThat(plan.sell().price()).isEqualTo(5_025);
        assertThat(plan.stopLoss().price()).isEqualTo(3_810);
        assertThat(plan.rewardRisk()).isEqualTo(5.08);
        // a buy above the close without a type is a breakout: a buy-stop rounded up
        TradePlan breakout = TradePlan.ofModel(4_131.0, null, 4_900.0, 3_900.0, 4_100, TICKS, "AI", "TUDOR_JONES", "x");
        assertThat(breakout.buy().type()).isEqualTo("STOP");
        assertThat(breakout.buy().price()).isEqualTo(4_140);
        assertThat(TradePlan.ofModel(4_012.0, null, 3_000.0, 3_811.0, 4_100, TICKS, "AI", "MURPHY", "x")).isNull();
    }

    @Test
    void theRulesPlanIsReadFromAPresetSummary() {
        Map<String, Object> summary = Map.of(
                "buy", Map.of("price", 1250.0, "type", "STOP", "basis", "a buy-stop above the 20-day high", "distancePct", 2.0),
                "sell", Map.of("price", 1500.0, "type", "LIMIT", "basis", "5 times the risk", "distancePct", 22.4),
                "stopLoss", Map.of("price", 1200.0, "type", "STOP", "basis", "2 ATR below the entry", "distancePct", -2.0),
                "rewardRisk", 5.0);
        TradePlan plan = TradePlan.ofRules(summary, "TUDOR_JONES");
        assertThat(plan.complete()).isTrue();
        assertThat(plan.buy().type()).isEqualTo("STOP");
        assertThat(plan.source()).isEqualTo("RULES");
        assertThat(TradePlan.ofRules(Map.of("buy", Map.of()), "GILL").complete()).isFalse();
    }

    // ------------------------------------------------------------------ validator

    private TechnicalAssessment answer(double score, String stance, Double buy, Double sell, Double stop) {
        return new TechnicalAssessment(score, stance, "A thesis", List.of("support"), List.of("risk"),
                buy == null ? null : json.valueToTree(Map.of("price", buy, "type", "LIMIT")), null,
                sell == null ? null : json.valueToTree(sell), stop == null ? null : json.valueToTree(stop),
                List.of("trend.sma200", "rsi14", "rules"), null).normalized();
    }

    @Test
    void theValidatorChecksEachTradersOwnRule() {
        Facts belowTheAverages = new Facts(1000, 1100, 1200, false, 30, 0.2, false);
        Facts nearTheHigh = new Facts(1000, 950, 900, true, 80, 0.95, false);
        assertThat(TechnicalValidator.rule(TechnicalAgent.TUDOR_JONES, answer(75, "BUY", 990.0, 1200.0, 950.0),
                belowTheAverages)).contains("200-day");
        assertThat(TechnicalValidator.rule(TechnicalAgent.MURPHY, answer(70, "BUY", 990.0, 1200.0, 950.0),
                belowTheAverages)).contains("downtrend");
        assertThat(TechnicalValidator.rule(TechnicalAgent.DRUCKENMILLER, answer(72, "BUY", 990.0, 1200.0, 950.0),
                belowTheAverages)).contains("percentile");
        assertThat(TechnicalValidator.rule(TechnicalAgent.GILL, answer(70, "BUY", 990.0, 1200.0, 950.0),
                nearTheHigh)).contains("52-week range");
        // a WAIT below the averages breaks nothing
        assertThat(TechnicalValidator.rule(TechnicalAgent.TUDOR_JONES, answer(40, "WAIT", 990.0, 1200.0, 950.0),
                belowTheAverages)).isNull();
    }

    @Test
    void theValidatorFlagsDivergenceStanceLevelsAndUnknownSignals() {
        Facts facts = new Facts(1000, 950, 900, true, 60, 0.5, false);
        List<Issue> issues = TechnicalValidator.validate(TechnicalAgent.MURPHY, answer(90, "WAIT", 990.0, 980.0, 950.0),
                40.0, facts, java.util.Set.of("trend", "trend.sma200", "sma200"));
        assertThat(issues).extracting(Issue::code).contains("DIVERGENCE", "STANCE", "LEVELS", "UNKNOWN_SIGNAL");
        assertThat(issues).filteredOn(i -> i.code().equals("UNKNOWN_SIGNAL")).singleElement()
                .satisfies(i -> assertThat(i.message()).contains("rsi14").doesNotContain("rules"));
        List<Issue> clean = TechnicalValidator.validate(TechnicalAgent.MURPHY, answer(62, "WAIT", 990.0, 1100.0, 950.0),
                55.0, facts, java.util.Set.of("trend.sma200", "rsi14", "sma200"));
        assertThat(clean).isEmpty();
    }

    @Test
    void aBuyGivenAsANumberOrAStringIsRead() {
        TechnicalAssessment a = new TechnicalAssessment(60.0, "accumulate", "t", List.of("s"), List.of(),
                json.valueToTree(1250), "stop", json.valueToTree(Map.of("price", 1400)), json.valueToTree("1,180"),
                List.of(), null).normalized();
        assertThat(a.stance()).isEqualTo("ACCUMULATE");
        assertThat(a.buyPrice()).isEqualTo(1250);
        assertThat(a.entryType()).isEqualTo("stop");
        assertThat(a.sellPrice()).isEqualTo(1400);
        assertThat(a.stopPrice()).isEqualTo(1180);
    }

    @Test
    void agentsAreParsedByNamePresetOrLabel() {
        assertThat(TechnicalAgent.parse("ptj")).isEqualTo(TechnicalAgent.TUDOR_JONES);
        assertThat(TechnicalAgent.parse("Paul Tudor Jones")).isEqualTo(TechnicalAgent.TUDOR_JONES);
        assertThat(TechnicalAgent.parse("keith gill")).isEqualTo(TechnicalAgent.GILL);
        assertThat(TechnicalAgent.parse("murphy")).isEqualTo(TechnicalAgent.MURPHY);
        assertThat(TechnicalAgent.parse("Stanley Druckenmiller")).isEqualTo(TechnicalAgent.DRUCKENMILLER);
        assertThat(TechnicalAgent.parse("SOROS")).isNull();
    }

    @Test
    void aPresetScoreBecomesAPriorFromZeroToHundred() {
        assertThat(TechnicalScreener.priorScore(Map.of("score", 3.5, "maxScore", 7.0))).isEqualTo(75);
        assertThat(TechnicalScreener.priorScore(Map.of("score", -9.0, "maxScore", 6.0))).isEqualTo(0);
        assertThat(TechnicalScreener.priorScore(Map.of("score", 0.0, "maxScore", 4.5))).isEqualTo(50);
    }

    // ------------------------------------------------------------------ synthesis

    @Test
    void theSynthesisKeepsValidPlansAndFallsBackOnInvalidOnes() {
        TradePlan agentPlan = TradePlan.ofModel(980.0, "LIMIT", 1150.0, 920.0, 1000, TICKS, "AI", "MURPHY", "Murphy (AI)");
        List<Row> rows = List.of(
                new Row(1, "AAAA", "PT Aaaa Tbk", "Industrials", 1000, 72, 65, Map.of(), Map.of(), List.of()),
                new Row(2, "BBBB", "PT Bbbb Tbk", "Energy", 1000, 48, 50, Map.of(), Map.of(), List.of("Thin liquidity")),
                new Row(3, "CCCC", "PT Cccc Tbk", "Energy", 1000, 40, 41, Map.of(), Map.of(), List.of()));
        Map<String, Fallback> fallbacks = Map.of("AAAA", new Fallback(agentPlan, 72), "BBBB", new Fallback(agentPlan, 48),
                "CCCC", new Fallback(agentPlan, 40));
        TechnicalSynthesisAgent.Reply reply = new TechnicalSynthesisAgent.Reply("Summary", List.of("IHSG below its SMA 200"),
                List.of(
                        new TechnicalSynthesisAgent.StockReply("aaaa", "buy", "high", "Leader on a pullback",
                                json.valueToTree(Map.of("price", 990, "type", "LIMIT")), null, json.valueToTree(1180),
                                json.valueToTree(930), 9.0, "Agents agree"),
                        new TechnicalSynthesisAgent.StockReply("BBBB", "HOLD", "?", "x",
                                json.valueToTree(Map.of("price", 990)), null, json.valueToTree(900),
                                json.valueToTree(930), 0.0, null),
                        new TechnicalSynthesisAgent.StockReply("ZZZZ", "BUY", "HIGH", "unknown", null, null, null, null,
                                0.0, null)));
        Synthesis s = TechnicalSynthesisAgent.normalize(reply, rows, fallbacks, TICKS, "anthropic/claude-opus-5.5");
        assertThat(s.stocks()).extracting(TechnicalSynthesisAgent.StockView::ticker).containsExactly("AAAA", "BBBB", "CCCC");
        var a = s.stocks().get(0);
        assertThat(a.action()).isEqualTo("BUY");
        assertThat(a.conviction()).isEqualTo("HIGH");
        assertThat(a.adjustment()).isEqualTo(5);
        assertThat(a.plan().source()).isEqualTo("SYNTHESIS");
        assertThat(a.plan().buy().price()).isEqualTo(990);
        var b = s.stocks().get(1);
        assertThat(b.action()).isEqualTo("WAIT");
        assertThat(b.conviction()).isEqualTo("LOW");
        assertThat(b.plan()).isSameAs(agentPlan);
        assertThat(b.planSource()).startsWith("The synthesis' plan was not valid");
        assertThat(s.stocks().get(2).planSource()).startsWith("Not covered by the synthesis");

        Synthesis fallback = TechnicalSynthesisAgent.fallback(Map.of("topN", 2,
                "market", Map.of("indexAboveSma200", false)), rows, fallbacks, "budget");
        assertThat(fallback.executiveSummary()).contains("AAAA (72)").contains("below its 200-day average");
        assertThat(fallback.marketNotes()).anyMatch(n -> n.contains("red flags"));
        assertThat(fallback.stocks()).allMatch(v -> v.plan() == agentPlan);
    }

    // ------------------------------------------------------------------ Stage 1 of a tier

    @Test
    @SuppressWarnings("unchecked")
    void stage1OfATierReadsTheStoredBarsWithTheRulesOfCompaniesTechnicals() {
        Map<Long, List<Candle>> stored = new LinkedHashMap<>();
        stored.put(1L, TechnicalTestData.fixture("HRTA"));
        stored.put(2L, TechnicalTestData.fixture("MYOR"));
        stored.put(3L, TechnicalTestData.fixture("SMSM"));
        stored.put(4L, TechnicalTestData.fixture("HRTA").subList(0, 50));
        List<ListingBars> listings = List.of(listing(1, "AAAA", stored.get(1L)), listing(2, "BBBB", stored.get(2L)),
                listing(3, "CCCC", stored.get(3L)), listing(4, "DDDD", stored.get(4L)));
        ListingPriceRepository prices = mock(ListingPriceRepository.class);
        when(prices.listings(Exchange.IDX)).thenReturn(listings);
        when(prices.bars(anyCollection(), any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            LocalDate from = inv.getArgument(1);
            Map<Long, List<Candle>> out = new LinkedHashMap<>();
            for (Long id : ids) {
                out.put(id, stored.get(id).stream().filter(c -> from == null || !c.date().isBefore(from)).toList());
            }
            return out;
        });
        TechnicalRepository technicalRepository = mock(TechnicalRepository.class);
        when(technicalRepository.indexBars(eq("IHSG"))).thenReturn(TechnicalTestData.fixture("IHSG"));
        when(technicalRepository.universe(Exchange.IDX)).thenReturn(List.of());
        TechnicalService technicalService = mock(TechnicalService.class);
        when(technicalService.ticks()).thenReturn(TICKS);
        ScreeningProperties screening = TestScreeningProperties.create();
        TechnicalScreener screener = new TechnicalScreener(screening, properties(), TECHNICAL, technicalRepository,
                technicalService, prices, json);

        double mid = 5e12;
        List<StockSnapshot> universe = new ArrayList<>(List.of(snapshot("AAAA", 600, mid), snapshot("BBBB", 2000, mid),
                snapshot("CCCC", 1500, mid), snapshot("DDDD", 600, mid), snapshot("EEEE", 600, 20e12),
                snapshot("FFFF", 40, mid)));
        List<TechnicalAgent> agents = List.of(TechnicalAgent.GILL, TechnicalAgent.TUDOR_JONES);
        TechnicalScreener.Result r = screener.screenTier(Exchange.IDX, MarketCapTier.MID, universe, agents, 1, JAKARTA);

        assertThat(r.funnel()).extracting(TechnicalScreener.FunnelStep::key)
                .containsExactly("universe", "tier", "price", "traded", "liquidity", "board", "history", "shortlist");
        assertThat(r.funnel()).extracting(TechnicalScreener.FunnelStep::remaining).containsExactly(6, 5, 4, 4, 4, 4, 3, 3);
        assertThat(r.asOfDate()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(r.eligible()).extracting(Evaluation::ticker).containsExactlyInAnyOrder("AAAA", "BBBB", "CCCC");
        assertThat(r.shortlist()).hasSize(3);
        for (Evaluation e : r.eligible()) {
            assertThat(e.priors()).containsOnlyKeys(TechnicalAgent.GILL, TechnicalAgent.TUDOR_JONES);
            assertThat(e.priorOverall()).isEqualTo(Math.round((e.priors().get(TechnicalAgent.GILL).score()
                    + e.priors().get(TechnicalAgent.TUDOR_JONES).score()) / 2 * 10) / 10.0);
            assertThat(e.priors().get(TechnicalAgent.TUDOR_JONES).summary().get("preset")).isEqualTo("ptj");
            assertThat(TradePlan.ofRules(e.priors().get(TechnicalAgent.GILL).summary(), "GILL").complete()).isTrue();
            assertThat(e.snapshot().get("ticker")).isEqualTo(e.ticker());
            assertThat((Map<String, Object>) e.snapshot().get("marketRegime")).containsEntry("index", "IHSG");
        }
        assertThat(r.eligible()).isSortedAccordingTo((a, b) -> Double.compare(b.priorOverall(), a.priorOverall()));
        assertThat(r.market()).containsKeys("indexAboveSma200", "breadthAbove200Pct");

        // the chart dossier keeps what the agents read and drops the rest
        Evaluation first = r.eligible().getFirst();
        Map<String, Object> dossier = new TechnicalScreeningService(null, null, null, null, null, null, null, null, null,
                properties(), screening, json).dossier(first);
        String text = json.writeValueAsString(dossier);
        assertThat(text).doesNotContain("usedBy").doesNotContain("\"swings\"").doesNotContain("\"zones\"");
        assertThat((Map<String, Object>) dossier.get("structure")).containsKeys("supportZonesBelow", "resistanceZonesAbove");
        assertThat(TechnicalScreeningService.signalKeys(dossier)).contains("rsi14", "momentum.rsi14", "trend.sma200");
        Map<String, Object> metrics = TechnicalScreeningService.metrics(first);
        assertThat(metrics).containsKeys("close", "rsi14", "vsSma200Pct", "range52Pct", "percentile6m");
        JsonNode summary = json.valueToTree(first.summary());
        assertThat(summary.path("summaries")).hasSize(4);
    }

    private static ListingBars listing(long id, String ticker, List<Candle> bars) {
        return new ListingBars(id, ticker, "PT " + ticker + " Tbk", "Industrials", bars.size(), bars.getFirst().date(),
                bars.getLast().date());
    }

    private static StockSnapshot snapshot(String ticker, double price, double marketCap) {
        LocalDate day = LocalDate.of(2026, 10, 14);
        return new StockSnapshot(1, 1, "IDX", ticker, "PT " + ticker + " Tbk", "Industrials", "Industry", null, day, price,
                marketCap, marketCap / price, 5e6, 5e9, price * 1.3, price * 0.8, 12.0, 11.0, 1.8, 50.0, price / 1.8, 0.03,
                day.atTime(16, 0).atZone(JAKARTA).toInstant(), null, null, 1.0, "IDR");
    }
}
