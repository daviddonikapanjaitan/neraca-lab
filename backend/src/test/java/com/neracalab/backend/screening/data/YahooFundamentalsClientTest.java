package com.neracalab.backend.screening.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.Fundamentals;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.ListingQuote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parsing of Yahoo Finance screener, quoteSummary and time series responses saved in October 2026. */
class YahooFundamentalsClientTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/screening/" + name)) {
            assertThat(in).as(name).isNotNull();
            return json.readTree(in);
        }
    }

    @Test
    void screenerQuote() throws IOException {
        JsonNode result = fixture("yahoo_screener_page.json").path("finance").path("result").path(0);
        assertThat(result.path("total").asInt()).isEqualTo(837);
        ListingQuote bbca = YahooFundamentalsClient.quote(Exchange.IDX, result.path("quotes").path(0));
        assertThat(bbca).isNotNull();
        assertThat(bbca.ticker()).isEqualTo("BBCA");
        assertThat(bbca.symbol()).isEqualTo("BBCA.JK");
        assertThat(bbca.name()).isEqualTo("PT Bank Central Asia Tbk");
        assertThat(bbca.currency()).isEqualTo("IDR");
        assertThat(bbca.marketCap()).isEqualTo(749_334_689_742_848d);
        assertThat(bbca.avgVolume3m()).isEqualTo(130_302_280d);
        assertThat(bbca.price()).isPositive();
        assertThat(bbca.trailingPe()).isPositive();
        assertThat(bbca.lastTradeAt()).isAfter(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(bbca.firstTradeDate()).isBefore(LocalDate.of(2010, 1, 1));
    }

    /** BNGA.JK share counts (October 2026): annual and quarterly points merged per date. */
    @Test
    void shareCounts() throws IOException {
        var counts = YahooFundamentalsClient.shareCounts(fixture("yahoo_timeseries_shares_BNGA.json"));

        assertThat(counts).extracting(c -> c.date().toString()).containsExactly("2022-12-31", "2023-12-31",
                "2024-12-31", "2025-03-31", "2025-06-30", "2025-12-31", "2026-03-31", "2026-06-30");
        var fy2025 = counts.get(5);
        assertThat(fy2025.outstanding()).isEqualTo(25_140_519_043L);
        assertThat(fy2025.issued()).isEqualTo(25_142_205_843L);
        assertThat(fy2025.treasury()).isEqualTo(1_686_800L);
        assertThat(counts.getFirst().outstanding()).isEqualTo(24_933_123_961L);
        // as published: outstanding = issued although 336,000 treasury shares are reported (inconsistent)
        var h1 = counts.getLast();
        assertThat(h1.outstanding()).isEqualTo(h1.issued()).isEqualTo(25_141_869_843L);
        assertThat(h1.treasury()).isEqualTo(336_000L);
    }

    @Test
    void shareCountsWithoutTreasuryShares() throws IOException {
        var counts = YahooFundamentalsClient.shareCounts(fixture("yahoo_timeseries_shares_SMDR.json"));

        assertThat(counts).isNotEmpty().allSatisfy(c -> {
            assertThat(c.outstanding()).isEqualTo(16_375_600_000L);
            assertThat(c.treasury()).isNull();
        });
    }

    @Test
    void symbolsOfTheExchange() {
        assertThat(YahooFundamentalsClient.symbol(Exchange.IDX, "HRTA")).isEqualTo("HRTA.JK");
        assertThat(YahooFundamentalsClient.ticker(Exchange.IDX, "HRTA.JK")).isEqualTo("HRTA");
        assertThat(YahooFundamentalsClient.ticker(Exchange.IDX, "AAPL")).isNull();
        assertThat(YahooFundamentalsClient.ticker(Exchange.IDX, ".JK")).isNull();
        assertThat(YahooFundamentalsClient.ticker(Exchange.IDX, "bad ticker.JK")).isNull();
    }

    @Test
    void annualFiguresOfABank() throws IOException {
        AnnualFigures a = YahooFundamentalsClient.annual(fixture("yahoo_timeseries_BBRI.json"));
        assertThat(a.years()).hasSize(4).isSorted();
        assertThat(a.series("revenue")).hasSize(4).doesNotContainNull();
        assertThat(a.series("netIncome")).hasSize(4).doesNotContainNull();
        assertThat(a.series("equity")).hasSize(4);
        // banks report no operating income / gross profit
        assertThat(a.series("operatingIncome")).isEmpty();
        assertThat(a.latest("totalAssets")).isPositive();
    }

    @Test
    void annualFiguresOfAConsumerCompany() throws IOException {
        AnnualFigures a = YahooFundamentalsClient.annual(fixture("yahoo_timeseries_UNVR.json"));
        assertThat(a.years()).hasSize(4);
        assertThat(a.series("operatingIncome")).hasSize(4);
        assertThat(a.series("workingCapital")).hasSize(4);
        assertThat(a.latest("ebit")).isNotNull();
    }

    @Test
    void quoteSummaryFundamentals() throws IOException {
        JsonNode result = fixture("yahoo_quotesummary_HRTA.json").path("quoteSummary").path("result").path(0);
        Fundamentals f = YahooFundamentalsClient.fundamentals(result, AnnualFigures.empty());
        assertThat(f.sector()).isEqualTo("Consumer Cyclical");
        assertThat(f.industry()).isEqualTo("Luxury Goods");
        assertThat(f.returnOnEquity()).isCloseTo(0.41947, within(0.001));
        assertThat(f.debtToEquity()).isCloseTo(1.11814, within(0.001));   // Yahoo: 111.814 (percent)
        assertThat(f.grossMargin()).isCloseTo(0.03823, within(0.0001));
        assertThat(f.insiderOwnership()).isCloseTo(0.768, within(0.001));
        assertThat(f.netIncomeTtm()).isPositive();
        assertThat(f.beta()).isNotNull();
    }

    @Test
    void zeroMarginsOfBanksAreUnknown() {
        JsonNode result = json.readTree("""
                {"financialData":{"grossMargins":0.0,"ebitdaMargins":0.0,"operatingMargins":0.41,"profitMargins":0.42,
                  "returnOnEquity":0.19,"debtToEquity":{"raw":45.0}},"assetProfile":{"sector":"Financial Services"}}""");
        Fundamentals f = YahooFundamentalsClient.fundamentals(result, AnnualFigures.empty());
        assertThat(f.grossMargin()).isNull();
        assertThat(f.ebitdaMargin()).isNull();
        assertThat(f.operatingMargin()).isEqualTo(0.41);
        assertThat(f.debtToEquity()).isEqualTo(0.45);
    }
}
