package com.neracalab.backend.screening.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.screening.data.FundamentalRepository.StaleListing;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.Fundamentals;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.ListingQuote;

/**
 * Daily snapshot storage against the Docker Postgres with two test listings (ZZTA, ZZTB, dated in
 * 2001 so the real data stays the latest): market data per day, fundamentals carried forward.
 */
@SpringBootTest
class FundamentalRepositoryTest {

    private static final LocalDate DAY1 = LocalDate.of(2001, 1, 1);
    private static final LocalDate DAY2 = LocalDate.of(2001, 1, 2);

    @Autowired
    private FundamentalRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM stock_listing WHERE exchange = 'IDX' AND ticker IN ('ZZTA', 'ZZTB')").update();
    }

    private static ListingQuote quote(String ticker, double price) {
        return new ListingQuote(ticker + ".JK", ticker, "PT " + ticker + " Tbk", "IDR", LocalDate.of(1995, 5, 5), price,
                price * 1e9, 1e9, 2e6, 1e6, price * 1.2, price * 0.7, 10.0, 9.0, 1.5, price / 10, price / 1.5, 0.02,
                Instant.parse("2001-01-01T09:00:00Z"));
    }

    @Test
    void marketDataEveryDayFundamentalsCarriedForward() {
        Map<String, Long> ids = repository.saveUniverse(Exchange.IDX, List.of(quote("ZZTA", 1000), quote("ZZTB", 500)), DAY1);
        assertThat(ids).containsOnlyKeys("ZZTA", "ZZTB");

        List<StaleListing> stale = repository.staleFundamentals(Exchange.IDX, Instant.now(), List.of("ZZTA", "ZZTB"));
        assertThat(stale).extracting(StaleListing::ticker).containsExactly("ZZTA", "ZZTB");   // largest market cap first

        Map<String, List<Double>> values = new LinkedHashMap<>();
        values.put("revenue", List.of(100d, 110d, 121d, 133d));
        Fundamentals f = new Fundamentals("Industrials", "Machinery", 133d, 13d, 20d, 0.3, 0.15, 0.1, 0.15, 0.18, 0.09,
                0.4, 1.6, 1.1, 50d, 40d, 12d, 18d, 0.1, 0.12, 1e12, 7d, 1.2d, 0.9, 0.8, 0.6, 0.1,
                new AnnualFigures(List.of("1997-12-31", "1998-12-31", "1999-12-31", "2000-12-31"), values));
        repository.saveFundamentals(stale.get(0), f);
        repository.fundamentalsFailed(stale.get(1), "Yahoo Finance has no fundamentals for ZZTB.JK");

        // next day: new market data, the fundamentals of ZZTA come along
        repository.saveUniverse(Exchange.IDX, List.of(quote("ZZTA", 1100), quote("ZZTB", 450)), DAY2);
        List<StockSnapshot> latest = repository.latestSnapshots(Exchange.IDX, List.of("ZZTA", "ZZTB"));
        StockSnapshot a = latest.stream().filter(s -> s.ticker().equals("ZZTA")).findFirst().orElseThrow();
        StockSnapshot b = latest.stream().filter(s -> s.ticker().equals("ZZTB")).findFirst().orElseThrow();
        assertThat(a.snapshotDate()).isEqualTo(DAY2);
        assertThat(a.price()).isEqualTo(1100.0);
        assertThat(a.avgDailyValue3m()).isEqualTo(2e6 * 1100);
        assertThat(a.sector()).isEqualTo("Industrials");
        assertThat(a.hasFundamentals()).isTrue();
        assertThat(a.fundamentals().returnOnEquity()).isEqualTo(0.18);
        assertThat(a.fundamentals().annual().series("revenue")).containsExactly(100d, 110d, 121d, 133d);
        assertThat(b.hasFundamentals()).isFalse();

        // ZZTA's fundamentals are fresh, ZZTB has none
        assertThat(repository.staleFundamentals(Exchange.IDX, Instant.now().minusSeconds(3600), List.of("ZZTA", "ZZTB")))
                .extracting(StaleListing::ticker).containsExactly("ZZTB");
        assertThat(repository.staleFundamentals(Exchange.IDX, Instant.now(), List.of())).isEmpty();
    }
}
