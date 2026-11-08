package com.neracalab.backend.screening.quant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.Fundamentals;
import com.neracalab.backend.screening.quant.CombinedProfile.Own;
import com.neracalab.backend.screening.quant.CombinedProfile.Result;
import com.neracalab.backend.screening.quant.CombinedProfile.Source;

/** The Quant Score tab's metrics: Neraca Lab's own data first, Yahoo's screening data for the gaps. */
class CombinedProfileTest {

    private static Fundamentals yahooFundamentals(String sector, String industry) {
        // ROE 10%, margins, D/E 0.9, PEG 1.4, beta 0.8, insiders 40%
        return new Fundamentals(sector, industry, 1000d, 100d, 200d, 0.30, 0.15, 0.10, 0.2, 0.10, 0.04, 0.9, 1.3,
                1.0, 50d, 300d, 80d, 120d, 0.05, 0.04, 2000d, 9d, 2d, 1.4, 0.8, 0.40, 0.2,
                AnnualFigures.empty(), "IDR");
    }

    private static StockSnapshot snapshot(Fundamentals f) {
        return new StockSnapshot(1, 1, "IDX", "TEST", "Test", f == null ? "Industrials" : f.sector(),
                f == null ? "Machinery" : f.industry(), null, LocalDate.of(2026, 11, 6), 1000d, 1_000_000d, 1000d,
                5_000_000d, 5_000_000_000d, 1200d, 800d, 12d, 11d, 1.5, 83d, 666d, 0.03, null, f, null, 1d, "IDR");
    }

    private static AnnualFigures annual(List<String> years, Map<String, Double[]> series) {
        Map<String, List<Double>> values = new LinkedHashMap<>();
        series.forEach((k, v) -> values.put(k, Arrays.asList(v)));
        return new AnnualFigures(years, values);
    }

    private static Own own(AnnualFigures annual, Map<String, Double> year, Map<String, Double> balance, Double pe) {
        return new Own(annual, "FY2025", year, balance, "2026 H1", LocalDate.of(2026, 11, 6), pe, 1.2, 6d, 0.07, 90d,
                900_000d, LocalDate.of(2026, 11, 6), 1000d, 1300d, 700d, 1000d, 4_000_000_000d);
    }

    @Test
    void ownFiguresComeFirstAndYahooFillsTheGaps() {
        Map<String, Double[]> series = new LinkedHashMap<>();
        series.put("revenue", new Double[] {100d, 110d, 121d, 133.1});
        series.put("netIncome", new Double[] {10d, 12d, 14.4, 17.28});
        series.put("equity", new Double[] {100d, 100d, 100d, 100d});
        series.put("operatingIncome", new Double[] {15d, 17d, 19d, 22d});
        Own own = own(annual(List.of("2022", "2023", "2024", "2025"), series),
                Map.of("roe", 0.18, "operatingMargin", 0.165), Map.of("debtToEquity", 0.4), 14d);

        Result r = CombinedProfile.of(snapshot(yahooFundamentals("Industrials", "Machinery")), own, null, null, null);
        StockProfile p = r.profile();

        // own: the latest fiscal year, the balance sheet, the valuation, the fiscal years
        assertThat(p.roe()).isEqualTo(0.18);
        assertThat(r.sources().get("roe").source()).isEqualTo(Source.FILINGS);
        assertThat(r.sources().get("roe").basis()).isEqualTo("FY2025");
        assertThat(p.debtToEquity()).isEqualTo(0.4);
        assertThat(r.sources().get("debtToEquity").basis()).isEqualTo("2026 H1");
        assertThat(p.pe()).isEqualTo(14d);
        assertThat(r.sources().get("pe").source()).isEqualTo(Source.VALUATION);
        assertThat(p.revenueCagr()).isCloseTo(0.10, within(1e-9));
        assertThat(p.netIncomeCagr()).isCloseTo(0.20, within(1e-9));
        assertThat(p.revenueGrowth()).isCloseTo(0.10, within(1e-9));
        assertThat(r.sources().get("revenueCagr").basis()).isEqualTo("2022 - 2025");
        // PEG from the P/E and the earnings CAGR above: 14 / 20
        assertThat(p.peg()).isCloseTo(0.7, within(1e-9));
        assertThat(r.sources().get("peg").source()).isEqualTo(Source.DERIVED);

        // Yahoo: what the filings lack, and the market data first
        assertThat(p.roa()).isEqualTo(0.04);
        assertThat(r.sources().get("roa").source()).isEqualTo(Source.YAHOO);
        assertThat(p.currentRatio()).isEqualTo(1.3);
        assertThat(p.beta()).isEqualTo(0.8);
        assertThat(p.insiderOwnership()).isEqualTo(0.40);
        assertThat(p.avgDailyValue()).isEqualTo(5_000_000_000d);
        assertThat(r.sources().get("avgDailyValue").source()).isEqualTo(Source.YAHOO);
        // drawdown of the snapshot: 1 - 1000 / 1200
        assertThat(p.drawdown()).isCloseTo(1 - 1000d / 1200d, within(1e-9));
    }

    @Test
    void withoutScreeningDataTheStoredPricesGiveTheMarketData() {
        Own own = own(AnnualFigures.empty(), Map.of("roe", 0.12), Map.of(), 10d);
        Result r = CombinedProfile.of(null, own, "Industrials", "Machinery", null);
        StockProfile p = r.profile();

        assertThat(p.avgDailyValue()).isEqualTo(4_000_000_000d);
        assertThat(r.sources().get("avgDailyValue").source()).isEqualTo(Source.PRICES);
        // 1 - 1000 / 1300 and (1300 - 700) / 700
        assertThat(p.drawdown()).isCloseTo(1 - 1000d / 1300d, within(1e-9));
        assertThat(p.range52w()).isCloseTo(600d / 700d, within(1e-9));
        assertThat(p.beta()).isNull();
        assertThat(r.sources()).doesNotContainKey("beta");
        // a scorecard is still scored on what is known
        assertThat(QuantScorer.score(InvestorAgent.BUFFETT, p).coverage()).isGreaterThan(0);
    }

    @Test
    void aMissingYearCountsInTheCagr() {
        Map<String, Double[]> series = new LinkedHashMap<>();
        // 2023 missing: 100 -> 121 over 2 years is 10% a year, not 21% over one
        series.put("revenue", new Double[] {100d, null, 121d});
        series.put("netIncome", new Double[] {10d, null, 12.1});
        Own own = own(annual(List.of("2022", "2023", "2024"), series), Map.of(), Map.of(), null);
        StockProfile p = CombinedProfile.of(null, own, null, null, null).profile();
        assertThat(p.revenueCagr()).isCloseTo(0.10, within(1e-9));
        // the latest growth needs the year before
        assertThat(p.revenueGrowth()).isNull();
    }

    @Test
    void aBankIsScoredOnItsCapitalNotOnIndustrialRatios() {
        Own own = own(AnnualFigures.empty(), Map.of("roe", 0.2, "grossMargin", 0.9), Map.of("debtToEquity", 5d,
                "equityToAssets", 0.15), 10d);
        Result r = CombinedProfile.of(snapshot(yahooFundamentals("Financial Services", "Banks - Regional")), own, null,
                null, null);
        StockProfile p = r.profile();
        assertThat(p.financial()).isTrue();
        assertThat(p.grossMargin()).isNull();
        assertThat(p.debtToEquity()).isNull();
        assertThat(p.equityToAssets()).isEqualTo(0.15);
        // without a snapshot the IDX-IC sector tells
        assertThat(CombinedProfile.of(null, own, null, null, "Keuangan").profile().financial()).isTrue();
        assertThat(CombinedProfile.of(null, own, "Industrials", null, "Perindustrian").profile().financial()).isFalse();
    }

    @Test
    void theScorecardBoundsAreThoseTheScoresUse() {
        for (InvestorAgent agent : InvestorAgent.values()) {
            List<QuantScorer.CriterionInfo> card = QuantScorer.card(agent);
            assertThat(card).extracting(c -> c.label() + " (" + c.key() + "): poor " + c.poor() + ", good " + c.good())
                    .containsExactlyElementsOf(QuantScorer.criteria(agent));
        }
    }
}
