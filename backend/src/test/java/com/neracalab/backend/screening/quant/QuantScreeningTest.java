package com.neracalab.backend.screening.quant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.TestScreeningProperties;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.Fundamentals;
import com.neracalab.backend.screening.quant.QuantScorer.AgentScore;

/** Stage 1 without database or model: metrics, scorecards, filters and shortlist. */
class QuantScreeningTest {

    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    static ScreeningProperties properties() {
        return TestScreeningProperties.create();
    }

    /** Four fiscal years of a healthy industrial company growing 10% a year. */
    static AnnualFigures healthyAnnual() {
        Map<String, List<Double>> v = new LinkedHashMap<>();
        v.put("revenue", List.of(1000d, 1100d, 1210d, 1331d));
        v.put("netIncome", List.of(100d, 112d, 125d, 140d));
        v.put("operatingIncome", List.of(150d, 168d, 190d, 215d));
        v.put("capex", List.of(-40d, -45d, -50d, -52d));
        v.put("equity", List.of(600d, 650d, 700d, 760d));
        v.put("totalAssets", List.of(1000d, 1050d, 1100d, 1150d));
        v.put("totalLiabilities", List.of(400d, 400d, 400d, 390d));
        v.put("workingCapital", List.of(200d, 210d, 220d, 230d));
        v.put("retainedEarnings", List.of(300d, 350d, 400d, 460d));
        v.put("ebit", List.of(150d, 168d, 190d, 215d));
        v.put("interestExpense", List.of(10d, 10d, 10d, 10d));
        return new AnnualFigures(List.of("2022-12-31", "2023-12-31", "2024-12-31", "2025-12-31"), v);
    }

    static Fundamentals fundamentals(String sector, double roe, double debtToEquity, AnnualFigures annual) {
        return new Fundamentals(sector, "Industry", 1331e9, 140e9, 250e9, 0.35, 0.16, 0.105, 0.19, roe, 0.12,
                debtToEquity, 1.8, 1.2, 100e9, 200e9, 120e9, 180e9, 0.10, 0.12, 2500e9, 8.0, 1.6, 0.9, 0.8, 0.55,
                0.10, annual);
    }

    static StockSnapshot stock(String ticker, double marketCap, double price, double avgValue, Double eps,
                               Fundamentals f) {
        return new StockSnapshot(1, 1, "IDX", ticker, "PT " + ticker + " Tbk", f == null ? null : f.sector(), "Industry",
                null, DAY, price, marketCap, marketCap / price, avgValue / price, avgValue, price * 1.3, price * 0.8,
                12.0, 11.0, 1.8, eps, price / 1.8, 0.03, DAY.atTime(16, 0).atZone(JAKARTA).toInstant(), f,
                f == null ? null : Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void profileMetrics() {
        StockProfile p = StockProfile.of(stock("GOOD", 2e12, 1000, 5e9, 50.0, fundamentals("Industrials", 0.20, 0.3, healthyAnnual())));
        assertThat(p.financial()).isFalse();
        assertThat(p.revenueCagr()).isCloseTo(0.10, within(1e-9));
        assertThat(p.netIncomeCagr()).isCloseTo(Math.pow(1.4, 1.0 / 3) - 1, within(1e-9));
        assertThat(p.positiveYears()).isEqualTo(1.0);
        assertThat(p.opMarginTrend()).isCloseTo(215d / 1331 - 0.15, within(1e-9));
        assertThat(p.interestCoverage()).isCloseTo(21.5, within(1e-9));
        assertThat(p.altmanZ()).isPositive();
        assertThat(p.peg()).isCloseTo(12.0 / (p.netIncomeCagr() * 100), within(1e-9));
        assertThat(p.fcfYield()).isCloseTo(120e9 / 2e12, within(1e-12));
        assertThat(p.drawdown()).isCloseTo(1 - 1 / 1.3, within(1e-9));
        assertThat(p.asMap()).containsKeys("roe", "peg", "altmanZ").doesNotContainKey("unknownKey");
    }

    @Test
    void cagrNeedsPositiveEndsTwoYearsApart() {
        assertThat(StockProfile.cagr(List.of(100d, 121d))).isNull();
        assertThat(StockProfile.cagr(java.util.Arrays.asList(100d, null, 121d))).isCloseTo(0.10, within(1e-9));
        assertThat(StockProfile.cagr(List.of(-5d, 10d, 20d))).isNull();
    }

    @Test
    void scoresRewardTheProfileAndRenormalizeMissingData() {
        StockProfile good = StockProfile.of(stock("GOOD", 2e12, 1000, 5e9, 50.0, fundamentals("Industrials", 0.22, 0.2, healthyAnnual())));
        StockProfile weak = StockProfile.of(stock("WEAK", 2e12, 1000, 5e9, 50.0, fundamentals("Industrials", 0.05, 1.8, healthyAnnual())));
        AgentScore goodBuffett = QuantScorer.score(InvestorAgent.BUFFETT, good);
        AgentScore weakBuffett = QuantScorer.score(InvestorAgent.BUFFETT, weak);
        assertThat(goodBuffett.score()).isBetween(0.0, 100.0).isGreaterThan(weakBuffett.score());
        assertThat(goodBuffett.coverage()).isEqualTo(1.0);
        // the bank-only criterion does not apply to an industrial company
        assertThat(goodBuffett.parts()).extracting(QuantScorer.Part::key).doesNotContain("equityToAssets");

        StockProfile bank = StockProfile.of(stock("BANK", 2e12, 1000, 5e9, 50.0,
                fundamentals("Financial Services", 0.18, 0.0, healthyAnnual())));
        AgentScore risk = QuantScorer.score(InvestorAgent.RISK, bank);
        assertThat(bank.financial()).isTrue();
        assertThat(bank.debtToEquity()).isNull();
        assertThat(risk.parts()).extracting(QuantScorer.Part::key).contains("equityToAssets").doesNotContain("altmanZ");
    }

    @Test
    void overallBlendsTheRiskAgent() {
        Map<InvestorAgent, Double> scores = new EnumMap<>(InvestorAgent.class);
        scores.put(InvestorAgent.BUFFETT, 80.0);
        scores.put(InvestorAgent.LYNCH, 60.0);
        assertThat(QuantScorer.overall(scores, 0.2)).isEqualTo(70.0);
        scores.put(InvestorAgent.RISK, 50.0);
        assertThat(QuantScorer.overall(scores, 0.2)).isEqualTo(66.0);   // 0.8 x 70 + 0.2 x 50
        assertThat(QuantScorer.overall(Map.of(InvestorAgent.RISK, 42.0), 0.2)).isEqualTo(42.0);
    }

    @Test
    void funnelFiltersInOrderAndShortlistsThreeTimesTopN() {
        Fundamentals f = fundamentals("Industrials", 0.2, 0.3, healthyAnnual());
        List<StockSnapshot> universe = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            universe.add(stock("MID" + i, 2e12 + i * 1e10, 1000, 5e9, 50.0, f));       // eligible mid caps
        }
        universe.add(stock("LARGE", 50e12, 1000, 5e9, 50.0, f));                      // other tier
        universe.add(stock("PENNY", 2e12, 30, 5e9, 1.0, f));                          // price below 50
        universe.add(stock("ILLIQ", 2e12, 1000, 1e8, 50.0, f));                       // traded value too low
        universe.add(stock("EXCL", 2e12, 1000, 5e9, 50.0, f));                        // excluded ticker
        universe.add(stock("NOFUND", 2e12, 1000, 5e9, 50.0, null));                   // no fundamentals
        universe.add(stock("LOSS", 2e12, 1000, 5e9, -5.0, f));                        // negative earnings
        StockSnapshot stale = stock("SUSP", 2e12, 1000, 5e9, 50.0, f);
        universe.add(new StockSnapshot(1, 1, "IDX", "SUSP", "PT SUSP Tbk", "Industrials", "Industry", null, DAY, 1000.0,
                2e12, 2e9, 5e6, 5e9, 1300.0, 800.0, 12.0, 11.0, 1.8, 50.0, 555.0, 0.03,
                Instant.parse("2026-08-01T09:00:00Z"), stale.fundamentals(), stale.fundamentalsFetchedAt()));  // suspended

        QuantScreener screener = new QuantScreener(properties());
        List<InvestorAgent> agents = List.of(InvestorAgent.BUFFETT, InvestorAgent.LYNCH, InvestorAgent.RISK);
        QuantScreener.Result result = screener.screen(universe, MarketCapTier.MID, agents, 5, JAKARTA);

        assertThat(result.universe()).isEqualTo(27);
        assertThat(result.funnel()).extracting(QuantScreener.FunnelStep::key).containsExactly("universe", "tier", "price",
                "traded", "liquidity", "board", "fundamentals", "earnings", "equity", "coverage", "shortlist");
        assertThat(result.funnel()).extracting(QuantScreener.FunnelStep::remaining)
                .containsExactly(27, 26, 25, 24, 23, 22, 21, 20, 20, 20, 15);
        assertThat(result.eligible()).hasSize(20);
        assertThat(result.shortlist()).hasSize(15);   // 3 x top 5
        assertThat(result.shortlist()).allSatisfy(c -> assertThat(c.scores()).containsOnlyKeys(agents));
        assertThat(screener.marketFiltered(universe, MarketCapTier.MID, JAKARTA)).hasSize(22);
    }

    @Test
    void selectionTakesTheSameStepsButKeepsAndFlagsStocksFailingATradabilityCheck() {
        Fundamentals f = fundamentals("Industrials", 0.2, 0.3, healthyAnnual());
        List<StockSnapshot> universe = List.of(
                stock("BIG", 50e12, 1000, 6e9, 50.0, f),        // large cap, liquid enough for its tier (5B)
                stock("BIGTHIN", 50e12, 1000, 2e9, 50.0, f),    // large cap below 5B: kept, flagged
                stock("MIDOK", 2e12, 1000, 2e9, 50.0, f),       // mid cap above 1B
                stock("SMALLOK", 0.5e12, 1000, 3e8, 50.0, f),   // small cap above 200M
                stock("PENNY", 0.5e12, 30, 3e8, 2.0, f),        // price below 50: kept, flagged
                stock("LOSS", 2e12, 1000, 5e9, -5.0, f),        // negative earnings: excluded
                stock("THINLOSS", 2e12, 1000, 1e8, -5.0, f));   // flagged (liquidity), then excluded (earnings)
        List<String> selected = List.of("BIG", "BIGTHIN", "MIDOK", "SMALLOK", "PENNY", "LOSS", "THINLOSS", "NOLIST");

        QuantScreener screener = new QuantScreener(properties());
        List<InvestorAgent> agents = List.of(InvestorAgent.BUFFETT, InvestorAgent.RISK);
        QuantScreener.Result result = screener.screenSelection(selected, universe, agents, JAKARTA);

        assertThat(result.universe()).isEqualTo(8);
        assertThat(result.funnel()).extracting(QuantScreener.FunnelStep::key).containsExactly("universe", "listed", "tier",
                "price", "traded", "liquidity", "board", "fundamentals", "earnings", "equity", "coverage", "shortlist");
        assertThat(result.funnel()).extracting(QuantScreener.FunnelStep::remaining)
                .containsExactly(8, 7, 7, 7, 7, 7, 7, 7, 5, 5, 5, 5);
        assertThat(result.funnel().get(5).label()).endsWith("(selected stocks below it are kept and flagged: 2)");
        // every eligible selected stock goes to the agents, whatever top N, also the flagged ones
        assertThat(result.shortlist()).extracting(QuantScreener.Candidate::ticker)
                .containsExactlyInAnyOrder("BIG", "BIGTHIN", "MIDOK", "SMALLOK", "PENNY");
        assertThat(result.excluded()).containsExactly(
                Map.entry("NOLIST", "No market data on Yahoo Finance"),
                Map.entry("LOSS", "Positive trailing earnings"),
                Map.entry("THINLOSS", "Positive trailing earnings"));
        // THINLOSS was flagged, but a later step excluded it: no flag left
        assertThat(result.flags()).containsExactly(
                Map.entry("PENNY", List.of("Share price 30, below 50 (special-monitoring board range)")),
                Map.entry("BIGTHIN", List.of("Low liquidity: traded value per day 2B, below the 5B minimum of large caps")));
        assertThat(result.flags("BIG")).isEmpty();
        // flagged stocks are kept before the fundamentals refresh too
        assertThat(screener.marketFiltered(universe, null, JAKARTA)).hasSize(7);
        // a tier screening still drops them and reports no exclusions or flags
        QuantScreener.Result tier = screener.screen(universe, MarketCapTier.MID, agents, 5, JAKARTA);
        assertThat(tier.eligible()).extracting(QuantScreener.Candidate::ticker).containsExactly("MIDOK");
        assertThat(tier.excluded()).isEmpty();
        assertThat(tier.flags()).isEmpty();
    }

    @Test
    void tiers() {
        ScreeningProperties p = properties();
        assertThat(MarketCapTier.of(10e12, p)).isEqualTo(MarketCapTier.LARGE);
        assertThat(MarketCapTier.of(9.99e12, p)).isEqualTo(MarketCapTier.MID);
        assertThat(MarketCapTier.of(0.5e12, p)).isEqualTo(MarketCapTier.SMALL);
        assertThat(MarketCapTier.parse("medium")).isEqualTo(MarketCapTier.MID);
        assertThat(MarketCapTier.parse("Large cap")).isEqualTo(MarketCapTier.LARGE);
        assertThat(MarketCapTier.parse("huge")).isNull();
        assertThat(InvestorAgent.parse("keith gill")).isEqualTo(InvestorAgent.GILL);
        assertThat(InvestorAgent.parse("Warren Buffett")).isEqualTo(InvestorAgent.BUFFETT);
        assertThat(InvestorAgent.parse("risk agent")).isEqualTo(InvestorAgent.RISK);
    }
}
