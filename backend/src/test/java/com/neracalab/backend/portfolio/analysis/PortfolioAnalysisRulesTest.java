package com.neracalab.backend.portfolio.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.portfolio.analysis.AnalysisSteps.Outcome;
import com.neracalab.backend.portfolio.analysis.AnalysisSteps.Stock;
import com.neracalab.backend.portfolio.analysis.PortfolioAgents.FundamentalAnswer;
import com.neracalab.backend.portfolio.analysis.PortfolioAgents.Issue;
import com.neracalab.backend.portfolio.analysis.PortfolioAgents.TechnicalAnswer;
import com.neracalab.backend.portfolio.analysis.PortfolioAllocator.Allocation;
import com.neracalab.backend.portfolio.analysis.PortfolioAllocator.Idea;
import com.neracalab.backend.portfolio.analysis.PortfolioAllocator.Kelly;
import com.neracalab.backend.portfolio.analysis.PortfolioAllocator.Limits;
import com.neracalab.backend.portfolio.analysis.PortfolioAllocator.Position;
import com.neracalab.backend.portfolio.analysis.PortfolioAllocator.Proposal;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;

/**
 * The rules of Portfolio &gt; Analysis without a model or a database: the two steps in order (fundamental first,
 * technical after, never combined), half-Kelly sizing, the allocation's limits (stocks held, 33% per stock and per
 * sector, whole lots, the cash), the agents' validators and the synthesis' normalization.
 */
class PortfolioAnalysisRulesTest {

    private static Stock stock(String t, Double f, Double tech, String sector) {
        return new Stock(t, t + " Tbk", sector, f, f == null ? null : "FIT", tech, tech == null ? null : "BUY",
                tech == null ? "No prices stored" : null);
    }

    private static final Limits LIMITS = new Limits(3, 0.33, 0.33, 0.01, 0.5, 100);

    // ------------------------------------------------------------------ the steps

    @Test
    void fundamentalFirstThenTechnicalNeverCombined() {
        // max 2 stocks: step 1 keeps the best 6 (x3), step 2 the best 4 (x2) of those by Technical (Score)
        List<Stock> universe = List.of(
                stock("AAAA", 90.0, 40.0, "Energy"),    // best fundamental, weak chart
                stock("BBBB", 85.0, 80.0, "Energy"),
                stock("CCCC", 80.0, 70.0, "Banks"),
                stock("DDDD", 75.0, 60.0, "Banks"),
                stock("EEEE", 70.0, 50.0, "Retail"),
                stock("FFFF", 65.0, null, "Retail"),    // no Technical (Score)
                stock("GGGG", 60.0, 99.0, "Retail"),    // 7th by fundamental: out at step 1 despite the best chart
                stock("HHHH", 45.0, 95.0, "Retail"),    // below 50: out at step 1
                stock("IIII", null, 90.0, "Retail"));   // no Fundamental (Score)
        AnalysisSteps.Limits limits = new AnalysisSteps.Limits(2, 50, 6, 4);
        AnalysisSteps.Result r = AnalysisSteps.run(universe, Set.of(), Map.of(), limits);

        // step 2 orders step 1's survivors by Technical (Score): BBBB, CCCC, DDDD, EEEE (AAAA 40 is 5th)
        assertThat(r.candidates()).extracting(o -> o.stock().ticker()).containsExactly("BBBB", "CCCC", "DDDD", "EEEE");
        Map<String, Outcome> by = new LinkedHashMap<>();
        r.outcomes().forEach(o -> by.put(o.stock().ticker(), o));
        assertThat(by.get("AAAA").stage()).isEqualTo(AnalysisSteps.TECHNICAL_OUT);
        assertThat(by.get("AAAA").fundamentalRank()).isEqualTo(1);
        assertThat(by.get("AAAA").technicalRank()).isEqualTo(5);
        assertThat(by.get("AAAA").reason()).contains("outside the best 4");
        assertThat(by.get("FFFF").stage()).isEqualTo(AnalysisSteps.TECHNICAL_OUT);
        assertThat(by.get("FFFF").reason()).startsWith("No Technical (Score)");
        assertThat(by.get("GGGG").stage()).isEqualTo(AnalysisSteps.FUNDAMENTAL_OUT);
        assertThat(by.get("GGGG").reason()).contains("rank 7: outside the best 6");
        assertThat(by.get("HHHH").reason()).isEqualTo("Fundamental (Score) 45.0 below 50");
        assertThat(by.get("IIII").reason()).startsWith("No Fundamental (Score)");
        assertThat(by.get("BBBB").fundamentalRank()).isEqualTo(2);
        assertThat(by.get("BBBB").technicalRank()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> f1 = (Map<String, Object>) r.funnel().get("fundamental");
        @SuppressWarnings("unchecked")
        Map<String, Object> f2 = (Map<String, Object>) r.funnel().get("technical");
        assertThat(f1.get("scored")).isEqualTo(8);
        assertThat(f1.get("aboveMin")).isEqualTo(7);
        assertThat(f1.get("passed")).isEqualTo(6);
        assertThat(f2.get("scored")).isEqualTo(5);
        assertThat(f2.get("passed")).isEqualTo(4);
    }

    @Test
    void holdingsAlwaysGoOnAndAreFlagged() {
        List<Stock> universe = List.of(stock("AAAA", 90.0, 80.0, "Energy"), stock("BBBB", 80.0, 70.0, "Banks"));
        Stock weak = stock("WEAK", 30.0, null, "Retail");     // held, outside the universe, fails both steps
        Stock good = stock("BBBB", 80.0, 70.0, "Banks");      // held and in the universe
        AnalysisSteps.Result r = AnalysisSteps.run(universe, Set.of("WEAK", "BBBB"),
                Map.of("WEAK", weak, "BBBB", good), new AnalysisSteps.Limits(1, 50, 3, 2));
        assertThat(r.candidates()).extracting(o -> o.stock().ticker()).containsExactly("AAAA", "BBBB", "WEAK");
        Outcome w = r.candidates().stream().filter(o -> o.stock().ticker().equals("WEAK")).findFirst().orElseThrow();
        assertThat(w.held()).isTrue();
        assertThat(w.passed()).isTrue();
        assertThat(w.flags()).singleElement().asString().contains("below 50");
        Outcome b = r.candidates().stream().filter(o -> o.stock().ticker().equals("BBBB")).findFirst().orElseThrow();
        assertThat(b.held()).isTrue();
        assertThat(b.flags()).isEmpty();
        // a holding takes no place of the step limits: AAAA is the only non-held candidate
        assertThat(r.candidates().stream().filter(o -> !o.held()).count()).isEqualTo(1);
    }

    @Test
    void fundamentalOnlyHasNoTechnicalStep() {
        List<Stock> universe = List.of(
                stock("AAAA", 90.0, 40.0, "Energy"),
                stock("BBBB", 85.0, 80.0, "Energy"),
                stock("CCCC", 80.0, null, "Banks"),     // no Technical (Score): no matter here
                stock("DDDD", 75.0, 60.0, "Banks"),
                stock("EEEE", 45.0, 99.0, "Retail"));   // below 50: out at step 1 whatever its chart
        Stock held = stock("HELD", 88.0, null, "Retail");   // would pass step 1
        AnalysisSteps.Result r = AnalysisSteps.run(universe, Set.of("HELD"), Map.of("HELD", held),
                new AnalysisSteps.Limits(2, 50, 3, 1), false);
        // step 1's best 3 go to the agents in step 1's order (no step 2, which would keep only 1)
        assertThat(r.candidates()).extracting(o -> o.stock().ticker()).containsExactly("AAAA", "BBBB", "CCCC", "HELD");
        assertThat(r.candidates()).allMatch(o -> o.technicalRank() == null);
        assertThat(r.outcomes()).noneMatch(o -> AnalysisSteps.TECHNICAL_OUT.equals(o.stage()));
        Map<String, Outcome> by = new LinkedHashMap<>();
        r.outcomes().forEach(o -> by.put(o.stock().ticker(), o));
        assertThat(by.get("DDDD").stage()).isEqualTo(AnalysisSteps.FUNDAMENTAL_OUT);
        assertThat(by.get("DDDD").reason()).contains("outside the best 3");
        assertThat(by.get("EEEE").stage()).isEqualTo(AnalysisSteps.FUNDAMENTAL_OUT);
        // a holding without a Technical (Score) is not flagged: the technical step is not used
        assertThat(by.get("HELD").flags()).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> f2 = (Map<String, Object>) r.funnel().get("technical");
        assertThat(f2.get("used")).isEqualTo(false);
        assertThat(f2.get("passed")).isEqualTo(0);
        assertThat(r.funnel().get("steps")).isEqualTo("FUNDAMENTAL");
        assertThat(r.funnel().get("candidates")).isEqualTo(4);
    }

    // ------------------------------------------------------------------ Kelly

    @Test
    void halfKelly() {
        // fundamental 100: p 0.65; entry 100, stop 90, target 130: b 3; f = 0.65 - 0.35 / 3 = 0.5333; x 0.5 = 0.2667
        Kelly k = PortfolioAllocator.kelly(100, 100.0, 90.0, 130.0, LIMITS);
        assertThat(k.p()).isCloseTo(0.65, within(1e-12));
        assertThat(k.b()).isCloseTo(3, within(1e-12));
        assertThat(k.f()).isCloseTo(0.65 - 0.35 / 3, within(1e-12));
        assertThat(k.weight()).isCloseTo((0.65 - 0.35 / 3) / 2, within(1e-12));
        // fundamental 50: p 0.5; b 1: no edge
        assertThat(PortfolioAllocator.kelly(50, 100.0, 90.0, 110.0, LIMITS).weight()).isZero();
        // a huge edge is capped at the position limit (half of p 0.65 at most: 32.5% under a 20% limit)
        assertThat(PortfolioAllocator.kelly(100, 100.0, 99.0, 300.0, new Limits(3, 0.2, 0.33, 0.01, 0.5, 100))
                .weight()).isEqualTo(0.2);
        // no complete plan: no size
        assertThat(PortfolioAllocator.kelly(90, 100.0, null, 130.0, LIMITS).weight()).isZero();
        assertThat(PortfolioAllocator.kelly(90, 100.0, 110.0, 130.0, LIMITS).b()).isNull();
        // fundamental only: an even payoff, f = 2p - 1
        Kelly even = PortfolioAllocator.kellyEven(100, LIMITS);
        assertThat(even.p()).isCloseTo(0.65, within(1e-12));
        assertThat(even.b()).isEqualTo(1.0);
        assertThat(even.f()).isCloseTo(0.30, within(1e-12));
        assertThat(even.weight()).isCloseTo(0.15, within(1e-12));
        assertThat(PortfolioAllocator.kellyEven(75, LIMITS).weight()).isCloseTo((2 * 0.575 - 1) / 2, within(1e-12));
        assertThat(PortfolioAllocator.kellyEven(50, LIMITS).weight()).isZero();
    }

    // ------------------------------------------------------------------ allocation

    private static Idea idea(String t, String sector, boolean held, double price, double shares, boolean pass,
                             String stance, double kelly) {
        return new Idea(t, sector, held, price, shares, shares * price, pass, stance, 60,
                new Kelly(0.6, 2.0, 0.4, kelly, null));
    }

    @Test
    void theLimitsHoldWhateverIsProposed() {
        // capital 100M: 70M cash, HELD 30M (30%) in Energy
        List<Idea> ideas = List.of(
                idea("HELD", "Energy", true, 1_000, 30_000, true, "BUY", 0.2),
                idea("ENER", "Energy", false, 500, 0, true, "BUY", 0.3),
                idea("BANK", "Banks", false, 2_000, 0, true, "ACCUMULATE", 0.3),
                idea("BAD1", "Retail", false, 100, 0, false, "BUY", 0.3),        // fundamental FAIL
                idea("WAIT", "Retail", false, 100, 0, true, "WAIT", 0.3),        // not timely
                idea("TINY", "Retail", false, 100, 0, true, "BUY", 0.005));
        Map<String, Proposal> proposals = new LinkedHashMap<>();
        proposals.put("HELD", new Proposal("HELD", "INCREASE", 0.40, "HIGH", "more"));   // above 33%
        proposals.put("ENER", new Proposal("ENER", "BUY", 0.20, "HIGH", "x"));          // Energy 0.33 + 0.20 > 33%
        proposals.put("BANK", new Proposal("BANK", "BUY", 0.50, "HIGH", "x"));          // above 33%
        proposals.put("BAD1", new Proposal("BAD1", "BUY", 0.10, "LOW", "x"));
        proposals.put("WAIT", new Proposal("WAIT", "BUY", 0.10, "LOW", "x"));
        proposals.put("TINY", new Proposal("TINY", "BUY", 0.005, "LOW", "x"));
        Allocation a = PortfolioAllocator.allocate(ideas, proposals, 0.05, 100_000_000, 70_000_000, LIMITS);

        Map<String, Position> by = new LinkedHashMap<>();
        a.positions().forEach(p -> by.put(p.ticker(), p));
        assertThat(by).doesNotContainKeys("BAD1", "WAIT", "TINY");
        assertThat(a.skipped().get("BAD1")).anyMatch(s -> s.contains("fundamental PASS"));
        assertThat(a.skipped().get("WAIT")).anyMatch(s -> s.contains("technical BUY or ACCUMULATE"));
        assertThat(a.skipped().get("TINY")).anyMatch(s -> s.contains("Too small"));
        // no stock above 33%, no sector above 33%
        a.positions().forEach(p -> assertThat(p.targetWeight()).isLessThanOrEqualTo(0.33 + 1e-9));
        a.sectorsAfter().values().forEach(v -> assertThat(v).isLessThanOrEqualTo(0.33 + 1e-9));
        assertThat(by.get("BANK").targetWeight()).isLessThanOrEqualTo(0.33);
        assertThat(by.get("BANK").adjustments()).anyMatch(s -> s.startsWith("Capped at 33.0%"));
        // whole lots, the cash never negative and at least the 5% asked for
        a.positions().forEach(p -> assertThat(p.tradeShares() % 100).isZero());
        assertThat(a.cashAfter()).isGreaterThanOrEqualTo(0);
        assertThat(a.cashTargetWeight()).isGreaterThanOrEqualTo(0.05 - 1e-9);
        assertThat(a.cashAfter()).isCloseTo(70_000_000 - a.positions().stream().mapToDouble(Position::tradeValue).sum(),
                within(0.01));
        assertThat(a.checks()).allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        assertThat(by.get("HELD").action()).isIn("INCREASE", "HOLD", "DECREASE");
        assertThat(by.get("BANK").action()).isEqualTo("BUY");
    }

    @Test
    void atMostMaxStocksSmallestNewBuysDroppedFirst() {
        List<Idea> ideas = new ArrayList<>();
        Map<String, Proposal> proposals = new LinkedHashMap<>();
        String[] sectors = {"A", "B", "C", "D", "E"};
        for (int i = 0; i < 5; i++) {
            String t = "S" + i;
            ideas.add(idea(t, sectors[i], false, 100, 0, true, "BUY", 0.2));
            proposals.put(t, new Proposal(t, "BUY", 0.10 + i * 0.02, "MEDIUM", "x"));
        }
        Allocation a = PortfolioAllocator.allocate(ideas, proposals, null, 10_000_000, 10_000_000, LIMITS);
        assertThat(a.positions()).extracting(Position::ticker).containsExactly("S4", "S3", "S2");
        assertThat(a.skipped().get("S0")).anyMatch(s -> s.contains("at most 3 stocks"));
    }

    @Test
    void oddLotsAndDecreases() {
        // 8,950 shares held at 1,000 = 8.95M of a 20M capital (44.75%): above 33%, it must be decreased in whole lots
        List<Idea> ideas = List.of(idea("ODD", "Energy", true, 1_000, 8_950, true, "BUY", 0.3));
        Allocation a = PortfolioAllocator.allocate(ideas, Map.of(), null, 20_000_000, 11_050_000, LIMITS);
        Position p = a.positions().getFirst();
        assertThat(p.action()).isEqualTo("DECREASE");
        assertThat(p.tradeShares() % 100).isZero();
        assertThat(p.targetValue() / 20_000_000).isLessThanOrEqualTo(0.33);
        assertThat(p.targetShares()).isEqualTo(8_950 - 2_400);   // 6,550 = 32.75%
        // kept as proposed: HOLD at the exact shares; a SELL sells the odd lot too
        Allocation hold = PortfolioAllocator.allocate(List.of(idea("ODD", "Energy", true, 1_000, 950, true, "BUY", 0.3)),
                Map.of(), null, 20_000_000, 19_050_000, LIMITS);
        assertThat(hold.positions().getFirst().action()).isEqualTo("HOLD");
        assertThat(hold.positions().getFirst().targetShares()).isEqualTo(950);
        Allocation sell = PortfolioAllocator.allocate(List.of(idea("ODD", "Energy", true, 1_000, 950, false, "AVOID", 0)),
                Map.of("ODD", new Proposal("ODD", "SELL", 0, "LOW", "x")), null, 20_000_000, 19_050_000, LIMITS);
        assertThat(sell.positions().getFirst().action()).isEqualTo("SELL");
        assertThat(sell.positions().getFirst().tradeShares()).isEqualTo(-950);
        assertThat(sell.cashAfter()).isEqualTo(20_000_000);
        // an increase never sells and buys whole lots on top of the odd lot
        Allocation inc = PortfolioAllocator.allocate(List.of(idea("ODD", "Energy", true, 1_000, 950, true, "BUY", 0.3)),
                Map.of("ODD", new Proposal("ODD", "INCREASE", 0.10, "HIGH", "x")), null, 20_000_000, 19_050_000, LIMITS);
        assertThat(inc.positions().getFirst().action()).isEqualTo("INCREASE");
        assertThat(inc.positions().getFirst().targetShares()).isEqualTo(950 + 1_000);
        // an increase without a fundamental PASS is refused
        Allocation refused = PortfolioAllocator.allocate(List.of(idea("ODD", "Energy", true, 1_000, 950, false, "BUY", 0.3)),
                Map.of("ODD", new Proposal("ODD", "INCREASE", 0.10, "HIGH", "x")), null, 20_000_000, 19_050_000, LIMITS);
        assertThat(refused.positions().getFirst().action()).isEqualTo("HOLD");
        assertThat(refused.positions().getFirst().adjustments()).anyMatch(s -> s.startsWith("Not increased"));
    }

    @Test
    void theFallbackFollowsTheSteps() {
        List<Idea> ideas = List.of(
                idea("SELL", "A", true, 100, 10_000, false, "AVOID", 0),
                idea("HALF", "B", true, 100, 10_000, false, "BUY", 0),
                idea("KEEP", "C", true, 100, 10_000, true, "WAIT", 0),
                idea("GROW", "D", true, 100, 10_000, true, "BUY", 0.2),
                new Idea("NEW1", "E", false, 100.0, 0, 0, true, "BUY", 70, new Kelly(0.6, 2.0, 0.4, 0.1, null)),
                new Idea("NEW2", "F", false, 100.0, 0, 0, true, "ACCUMULATE", 80, new Kelly(0.6, 2.0, 0.4, 0.1, null)),
                new Idea("NEW3", "G", false, 100.0, 0, 0, false, "BUY", 90, new Kelly(0.6, 2.0, 0.4, 0.1, null)));
        // at most 4 stocks: the three holdings kept take three places (a sold one takes none)
        Map<String, Proposal> f = PortfolioAllocator.fallback(ideas, 10_000_000,
                new Limits(4, 0.33, 0.33, 0.01, 0.5, 100));
        assertThat(f.get("SELL").action()).isEqualTo("SELL");
        assertThat(f.get("HALF").action()).isEqualTo("DECREASE");
        assertThat(f.get("HALF").targetWeight()).isCloseTo(0.05, within(1e-12));
        assertThat(f.get("KEEP").action()).isEqualTo("HOLD");
        assertThat(f.get("GROW").action()).isEqualTo("INCREASE");
        // the best technical score first in the one place left, never a fundamental FAIL
        assertThat(f.get("NEW2").action()).isEqualTo("BUY");
        assertThat(f).doesNotContainKeys("NEW1", "NEW3");
    }

    @Test
    void fundamentalOnlyBuysOnAFundamentalPass() {
        // no technical stance: a PASS is enough to buy or increase, a FAIL is not bought
        Kelly k = new Kelly(0.6, 1.0, 0.2, 0.1, null);
        List<Idea> ideas = List.of(
                new Idea("HELD", "A", true, 100.0, 10_000, 1_000_000, true, null, 70, k, false),
                new Idea("GOOD", "B", false, 100.0, 0, 0, true, null, 80, k, false),
                new Idea("POOR", "C", false, 100.0, 0, 0, false, null, 40, k, false));
        Map<String, Proposal> proposals = Map.of(
                "HELD", new Proposal("HELD", "INCREASE", 0.15, "HIGH", "x"),
                "GOOD", new Proposal("GOOD", "BUY", 0.10, "HIGH", "x"),
                "POOR", new Proposal("POOR", "BUY", 0.10, "LOW", "x"));
        Allocation a = PortfolioAllocator.allocate(ideas, proposals, null, 10_000_000, 9_000_000, LIMITS);
        Map<String, Position> by = new LinkedHashMap<>();
        a.positions().forEach(x -> by.put(x.ticker(), x));
        assertThat(by.get("HELD").action()).isEqualTo("INCREASE");
        assertThat(by.get("GOOD").action()).isEqualTo("BUY");
        assertThat(by).doesNotContainKey("POOR");
        assertThat(a.skipped().get("POOR")).singleElement().asString()
                .isEqualTo("Not bought: a new stock needs a fundamental PASS");
        // the same ideas with the technical step required: no stance, nothing bought or increased
        List<Idea> timed = ideas.stream().map(i -> new Idea(i.ticker(), i.sector(), i.held(), i.price(),
                i.currentShares(), i.currentValue(), i.fundamentalPass(), null, i.orderScore(), i.kelly(), true)).toList();
        Allocation t = PortfolioAllocator.allocate(timed, proposals, null, 10_000_000, 9_000_000, LIMITS);
        assertThat(t.positions()).extracting(Position::ticker).containsExactly("HELD");
        assertThat(t.positions().getFirst().action()).isEqualTo("HOLD");
        assertThat(t.skipped().get("GOOD")).anyMatch(x -> x.contains("technical BUY or ACCUMULATE"));

        // the fallback: a FAIL sells, a PASS keeps or raises, new PASS bought by fundamental score
        Kelly big = new Kelly(0.6, 1.0, 0.2, 0.12, null);
        Map<String, Proposal> f = PortfolioAllocator.fallback(List.of(
                new Idea("FAIL", "A", true, 100.0, 10_000, 1_000_000, false, null, 30, big, false),
                new Idea("PASS", "B", true, 100.0, 10_000, 1_000_000, true, null, 70, big, false),
                new Idea("NEW1", "C", false, 100.0, 0, 0, true, null, 60, big, false),
                new Idea("NEW2", "D", false, 100.0, 0, 0, true, null, 90, big, false)), 10_000_000,
                new Limits(2, 0.33, 0.33, 0.01, 0.5, 100));
        assertThat(f.get("FAIL").action()).isEqualTo("SELL");
        assertThat(f.get("PASS").action()).isEqualTo("INCREASE");
        assertThat(f.get("NEW2").action()).isEqualTo("BUY");
        assertThat(f.get("NEW2").reason()).isEqualTo("Fundamental PASS: bought at its half-Kelly weight");
        assertThat(f).doesNotContainKey("NEW1");   // two stocks at most: PASS and NEW2
        assertThat(PortfolioSynthesis.deterministic(f, "x", false).executiveSummary()).contains("fundamental analysis only");
    }

    // ------------------------------------------------------------------ the validators

    @Test
    void validators() {
        FundamentalAnswer ok = new FundamentalAnswer(70.0, "PASS", "Strong franchise", List.of(), List.of(), null);
        assertThat(PortfolioAgents.checkFundamental(ok, 72, 60.0)).isEmpty();
        assertThat(PortfolioAgents.checkFundamental(ok, 30, 60.0)).extracting(Issue::kind).containsExactly("DIVERGENCE");
        assertThat(PortfolioAgents.checkFundamental(ok, 72, 20.0)).extracting(Issue::kind).containsExactly("RULE");
        assertThat(PortfolioAgents.checkFundamental(new FundamentalAnswer(40.0, "PASS", "x", null, null, null), 45, null))
                .extracting(Issue::kind).containsExactly("VERDICT");
        assertThat(PortfolioAgents.checkFundamental(new FundamentalAnswer(80.0, "FAIL", "x", null, null, null), 75, null))
                .extracting(Issue::kind).containsExactly("VERDICT");
        assertThat(PortfolioAgents.checkFundamental(PortfolioAgents.normalize(
                new FundamentalAnswer(60.0, "maybe", "x", null, null, null)), 60, null)).extracting(Issue::kind)
                .containsExactly("EMPTY");

        TechnicalAnswer good = new TechnicalAnswer(70.0, "BUY", 98.0, "LIMIT", 90.0, 120.0, "Uptrend", List.of(), null);
        assertThat(PortfolioAgents.checkTechnical(good, 68, 100)).isEmpty();
        assertThat(PortfolioAgents.checkTechnical(new TechnicalAnswer(30.0, "BUY", 98.0, "LIMIT", 90.0, 120.0, "x", null,
                null), 30, 100)).extracting(Issue::kind).containsExactly("STANCE");
        assertThat(PortfolioAgents.checkTechnical(new TechnicalAnswer(70.0, "BUY", 98.0, "LIMIT", 99.0, 120.0, "x", null,
                null), 70, 100)).extracting(Issue::kind).containsExactly("LEVELS");
        assertThat(PortfolioAgents.checkTechnical(new TechnicalAnswer(70.0, "BUY", 110.0, "LIMIT", 90.0, 120.0, "x", null,
                null), 70, 100)).extracting(Issue::kind).containsExactly("LEVELS");   // a LIMIT above the close
        assertThat(PortfolioLessons.lesson(PortfolioAgents.TECHNICAL, "STANCE")).contains("BUY 65-100");
        assertThat(PortfolioLessons.lesson(PortfolioAgents.FUNDAMENTAL, "RULE")).contains("Risk scorecard");
    }

    // ------------------------------------------------------------------ the synthesis

    @Test
    void theSynthesisIsNormalized() {
        PortfolioSynthesis.Reply reply = new PortfolioSynthesis.Reply("Summary", List.of("Note"), 12.5, List.of(
                new PortfolioSynthesis.PositionReply("held", "buy", 20.0, "high", "Add"),   // a holding: INCREASE
                new PortfolioSynthesis.PositionReply("NEW", "BUY", 150.0, "x", "Too much"), // clamped to 100%
                new PortfolioSynthesis.PositionReply("NEW", "SELL", 0.0, null, "duplicate"),
                new PortfolioSynthesis.PositionReply("ZZZZ", "BUY", 10.0, null, "unknown"),
                new PortfolioSynthesis.PositionReply("OUT", "SELL", 33.0, "LOW", "Exit")));
        PortfolioSynthesis.Result r = PortfolioSynthesis.normalize(reply, Set.of("HELD", "NEW", "OUT"),
                Set.of("HELD", "OUT"), "opus");
        assertThat(r.proposals()).containsOnlyKeys("HELD", "NEW", "OUT");
        assertThat(r.proposals().get("HELD").action()).isEqualTo("INCREASE");
        assertThat(r.proposals().get("HELD").targetWeight()).isCloseTo(0.20, within(1e-12));
        assertThat(r.proposals().get("HELD").conviction()).isEqualTo("HIGH");
        assertThat(r.proposals().get("NEW").targetWeight()).isEqualTo(1.0);
        assertThat(r.proposals().get("NEW").conviction()).isNull();
        assertThat(r.proposals().get("OUT").targetWeight()).isZero();
        assertThat(r.cashTarget()).isCloseTo(0.125, within(1e-12));
        assertThatThrownBy(() -> PortfolioSynthesis.normalize(new PortfolioSynthesis.Reply("x", null, null, List.of()),
                Set.of(), Set.of(), "opus")).isInstanceOf(InvalidReplyException.class);
    }
}
