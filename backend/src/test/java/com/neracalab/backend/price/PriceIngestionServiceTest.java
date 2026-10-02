package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.price.provider.DailyBar;
import com.neracalab.backend.price.provider.PriceHistory;
import com.neracalab.backend.price.provider.PriceProvider;
import com.neracalab.backend.price.provider.PriceProviderException;

/**
 * Price ingestion against the Docker Postgres with the HRTA seed data (price_daily up to 2026-09-30).
 * A stub provider replaces Yahoo; every test runs in a transaction that is rolled back.
 */
@SpringBootTest(properties = "neracalab.prices.provider=stub")
@Transactional
class PriceIngestionServiceTest {

    /** Saturday 2026-10-03 10:00 WIB: the last completed trading day is Friday 2026-10-02. */
    private static final Clock SATURDAY = Clock.fixed(Instant.parse("2026-10-03T03:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate SEED_END = LocalDate.of(2026, 9, 30);
    private static final BigDecimal HRTA_SHARES = new BigDecimal("4605262400");

    @TestConfiguration
    static class StubProviderConfig {

        /** Context-wide provider (the queue worker is never fed in this class). */
        @Bean
        PriceProvider stubPriceProvider() {
            return new StubProvider();
        }
    }

    @Autowired
    private PriceDailyRepository prices;

    @Autowired
    private ValuationRepository valuations;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    private CompanyRef hrta;
    private StubProvider provider;

    @BeforeEach
    void seedState() {
        hrta = prices.company(Exchange.IDX, "HRTA").orElseThrow();
        // the tests start from the seed: drop days a real ingestion may have added (rolled back afterwards)
        for (String delete : List.of(
                "DELETE FROM financial_metric WHERE company_id = :c AND source = 'valuation_snapshot' AND metric_date > :d",
                "DELETE FROM valuation_snapshot WHERE company_id = :c AND valuation_date > :d",
                "DELETE FROM market_snapshot WHERE company_id = :c AND snapshot_date > :d",
                "DELETE FROM price_daily WHERE company_id = :c AND trading_date > :d")) {
            jdbc.sql(delete).param("c", hrta.companyId()).param("d", SEED_END).update();
        }
        provider = new StubProvider();
    }

    private PriceIngestionService service(Clock clock) {
        return new PriceIngestionService(provider, prices, valuations, transaction, TestPriceProperties.defaults(), clock);
    }

    @Test
    void companyScopedRefreshMatchesTheStartupScript() {
        // V1.0.6 ran on start; the company-scoped SQL must produce exactly the same rows
        ValuationRepository.Refreshed refreshed = valuations.refresh(hrta.companyId());

        assertThat(refreshed.marketSnapshots()).isZero();
        assertThat(refreshed.valuationSnapshots()).isZero();
        assertThat(refreshed.valuationMetrics()).isZero();
        assertThat(refreshed.staleValuationMetricsDeleted()).isZero();
    }

    @Test
    void fetchesFromTheLatestStoredDayAndValuesTheNewLatestDay() {
        provider.respond(history("IDR",
                bar("2026-09-30", "2230", "2300", "2210", "2280", "2280", 11986000L),     // overlap, unchanged
                bar("2026-10-01", "2280", "2280", "2160", "2190", "2190", 8198600L),
                new DailyBar(LocalDate.of(2026, 10, 2), null, null, null, null, null, null), // placeholder
                bar("2026-10-02", "2190", "2240", "2160", "2190", "2190", 4309100L)));

        Result result = service(SATURDAY).ingest(hrta, false);

        assertThat(provider.requests).containsExactly(SEED_END + ".." + LocalDate.of(2026, 10, 3));
        assertThat(result.requests()).isEqualTo(1);
        assertThat(result.fullHistory()).isFalse();
        assertThat(result.reAdjusted()).isFalse();
        assertThat(result.barsReceived()).isEqualTo(4);
        assertThat(result.barsSkipped()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.latestTradingDate()).isEqualTo(LocalDate.of(2026, 10, 2));

        Map<String, Object> price = jdbc.sql("""
                        SELECT open_price, high_price, low_price, close_price, adjusted_close, volume
                        FROM price_daily WHERE company_id = :c AND trading_date = DATE '2026-10-02'""")
                .param("c", hrta.companyId()).query().singleRow();
        assertThat((BigDecimal) price.get("close_price")).isEqualByComparingTo("2190");
        assertThat((BigDecimal) price.get("high_price")).isEqualByComparingTo("2240");
        assertThat(price.get("volume")).isEqualTo(4309100L);

        // market snapshot of the new day
        BigDecimal marketCap = jdbc.sql("SELECT market_cap FROM market_snapshot WHERE company_id = :c AND snapshot_date = DATE '2026-10-02'")
                .param("c", hrta.companyId()).query(BigDecimal.class).single();
        assertThat(marketCap).isEqualByComparingTo(new BigDecimal("2190").multiply(HRTA_SHARES));

        // valuation of the new latest day: same fundamentals as 2026-09-30, new price
        Map<String, Object> before = valuation("2026-09-30");
        Map<String, Object> after = valuation("2026-10-02");
        assertThat(after.get("period_id")).isEqualTo(before.get("period_id"));
        assertThat((BigDecimal) after.get("share_price")).isEqualByComparingTo("2190");
        assertThat((BigDecimal) after.get("eps_ttm")).isEqualByComparingTo((BigDecimal) before.get("eps_ttm"));
        assertThat((BigDecimal) after.get("pe_ratio")).isEqualByComparingTo(
                new BigDecimal("2190").divide((BigDecimal) after.get("eps_ttm"), 8, java.math.RoundingMode.HALF_UP));
        assertThat((BigDecimal) after.get("pe_ratio")).isLessThan((BigDecimal) before.get("pe_ratio"));
        assertThat((BigDecimal) before.get("share_price")).isEqualByComparingTo("2280");   // earlier snapshot kept

        // valuation metrics of the new day, one per non-null valuation figure
        List<String> metrics = jdbc.sql("""
                        SELECT metric_name FROM financial_metric
                        WHERE company_id = :c AND metric_category = 'VALUATION' AND metric_date = DATE '2026-10-02'""")
                .param("c", hrta.companyId()).query(String.class).list();
        assertThat(metrics).contains("market_cap", "enterprise_value", "pe_ratio", "pb_ratio", "ev_op");
        assertThat(result.valuation().valuationSnapshots()).isEqualTo(1);
        assertThat(result.valuation().valuationMetrics()).isEqualTo(metrics.size());
        assertThat(result.valuation().marketSnapshots()).isEqualTo(2);
    }

    @Test
    void skipsTodaysUnfinishedBarBeforeTheCutoff() {
        Clock fridayNoon = Clock.fixed(Instant.parse("2026-10-02T05:00:00Z"), ZoneOffset.UTC);   // 12:00 WIB
        provider.respond(history("IDR",
                bar("2026-09-30", "2230", "2300", "2210", "2280", "2280", 11986000L),
                bar("2026-10-01", "2280", "2280", "2160", "2190", "2190", 8198600L),
                bar("2026-10-02", "2190", "2240", "2160", "2200", "2200", 1000000L)));      // intraday

        Result result = service(fridayNoon).ingest(hrta, false);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.barsSkipped()).isEqualTo(1);
        assertThat(result.latestTradingDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void makesNoRequestWhenTheLatestCompletedDayIsStored() {
        Clock wednesdayEvening = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);   // 19:00 WIB

        Result result = service(wednesdayEvening).ingest(hrta, false);

        assertThat(provider.requests).isEmpty();
        assertThat(result.requests()).isZero();
        assertThat(result.requestedFrom()).isNull();
        assertThat(result.latestTradingDate()).isEqualTo(SEED_END);
    }

    @Test
    void refetchesTheFullHistoryWhenTheProviderReAdjustedIt() {
        // a dividend after 2026-09-30 lowers the adjusted close of every earlier day
        provider.respond(history("IDR",
                bar("2026-09-30", "2230", "2300", "2210", "2280", "2240", 11986000L),
                bar("2026-10-01", "2280", "2280", "2160", "2190", "2190", 8198600L)));
        provider.respond(history("IDR",
                bar("2026-09-29", "2150", "2200", "2080", "2180", "2141.7544", 8721700L),
                bar("2026-09-30", "2230", "2300", "2210", "2280", "2240", 11986000L),
                bar("2026-10-01", "2280", "2280", "2160", "2190", "2190", 8198600L)));

        Result result = service(SATURDAY).ingest(hrta, false);

        assertThat(provider.requests).containsExactly(
                SEED_END + ".." + LocalDate.of(2026, 10, 3), "1990-01-01.." + LocalDate.of(2026, 10, 3));
        assertThat(result.requests()).isEqualTo(2);
        assertThat(result.reAdjusted()).isTrue();
        assertThat(result.fullHistory()).isTrue();
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(2);
        BigDecimal adjusted = jdbc.sql("SELECT adjusted_close FROM price_daily WHERE company_id = :c AND trading_date = DATE '2026-09-29'")
                .param("c", hrta.companyId()).query(BigDecimal.class).single();
        assertThat(adjusted).isEqualByComparingTo("2141.7544");
    }

    @Test
    void firstIngestionOfACompanyWithoutPricesFetchesTheFullHistory() {
        CompanyRef company = companyWithoutPrices();
        provider.respond(history("IDR",
                bar("2026-10-01", "6400", "6450", "6375", "6425", "6425", 3000000L),
                bar("2026-10-02", "6425", "6500", "6400", "6475", "6475", 2500000L)));

        Result result = service(SATURDAY).ingest(company, false);

        assertThat(provider.requests).containsExactly("1990-01-01.." + LocalDate.of(2026, 10, 3));
        assertThat(result.fullHistory()).isTrue();
        assertThat(result.reAdjusted()).isFalse();
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.latestTradingDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(prices.latestTradingDate(company.companyId())).contains(LocalDate.of(2026, 10, 2));
    }

    @Test
    void latestTradingDateIsEmptyWithoutPrices() {
        assertThat(prices.latestTradingDate(companyWithoutPrices().companyId())).isEmpty();
    }

    /** A company with no price_daily rows, created inside the rolled-back test transaction. */
    private CompanyRef companyWithoutPrices() {
        long id = jdbc.sql("""
                        INSERT INTO company (ticker, exchange, company_name, currency)
                        VALUES ('ZZTEST', 'IDX', 'Price ingestion test company', 'IDR')
                        RETURNING company_id""")
                .query(Long.class).single();
        return prices.company(Exchange.IDX, "ZZTEST").filter(c -> c.companyId() == id).orElseThrow();
    }

    @Test
    void storesNothingWhenTheCurrencyDoesNotMatch() {
        provider.respond(history("USD", bar("2026-10-01", "1", "1", "1", "1", "1", 5L)));

        assertThatThrownBy(() -> service(SATURDAY).ingest(hrta, false))
                .isInstanceOf(PriceProviderException.class)
                .hasMessageContaining("quoted in USD");
        assertThat(prices.latestTradingDate(hrta.companyId())).contains(SEED_END);
    }

    private Map<String, Object> valuation(String date) {
        return jdbc.sql("""
                        SELECT period_id, share_price, eps_ttm, pe_ratio FROM valuation_snapshot
                        WHERE company_id = :c AND valuation_date = :d""")
                .param("c", hrta.companyId()).param("d", LocalDate.parse(date))
                .query().singleRow();
    }

    private static PriceHistory history(String currency, DailyBar... bars) {
        return new PriceHistory("HRTA.JK", currency, List.of(bars));
    }

    private static DailyBar bar(String date, String open, String high, String low, String close, String adjusted,
                                long volume) {
        return new DailyBar(LocalDate.parse(date), new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), new BigDecimal(adjusted), volume);
    }

    /** Returns the queued responses in order and records each request as "from..to". */
    static final class StubProvider implements PriceProvider {

        final List<String> requests = new ArrayList<>();
        private final Deque<PriceHistory> responses = new ArrayDeque<>();

        void respond(PriceHistory history) {
            responses.add(history);
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public PriceHistory fetch(Exchange exchange, String ticker, LocalDate from, LocalDate to) {
            requests.add(from + ".." + to);
            PriceHistory history = responses.poll();
            if (history == null) {
                throw new com.neracalab.backend.price.provider.SymbolNotFoundException("stub has no data for " + ticker);
            }
            return history;
        }
    }
}
