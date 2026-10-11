package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The refresh of the derived data at the end of an upload ({@code market_snapshot}, {@code valuation_snapshot},
 * {@code financial_metric} of every company). It took 20 s and more with 157,000 prices stored, and the uploads
 * queued behind each other for it until they passed their three minutes (the SLOW_EXTRACTION rows of 2026-10-11).
 * Runs against the Docker Postgres; the refresh only recalculates, so it leaves the data as it was.
 */
@SpringBootTest
class DerivedDataRefreshTest {

    @Autowired
    private IngestionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aRefreshTakesSecondsAndChangesNothingThatIsCurrent() {
        repository.refreshDerivedData();       // whatever was stale is current now
        String before = fingerprint();

        long started = System.nanoTime();
        repository.refreshDerivedData();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(fingerprint()).isEqualTo(before);
        assertThat(took).as("a refresh with nothing to change").isLessThan(Duration.ofSeconds(15));
    }

    /** The market snapshot of a trading day carries the latest balance sheet on or before it, as before the rewrite. */
    @Test
    void theMarketSnapshotsAreWhatTheLookupPerTradingDayGives() {
        repository.refreshDerivedData();

        int different = jdbc.sql("""
                SELECT count(*) FROM (
                    SELECT pd.company_id, pd.trading_date, pd.close_price, sh.shares_outstanding,
                           ROUND(pd.close_price * sh.shares_outstanding, 4) AS market_cap,
                           ROUND(pd.close_price * sh.shares_outstanding + bs.net_debt
                                 + COALESCE(bs.non_controlling_interest, 0), 4) AS enterprise_value
                    FROM price_daily pd
                    LEFT JOIN LATERAL (
                        SELECT ss.shares_outstanding FROM share_snapshot ss
                        WHERE ss.company_id = pd.company_id AND ss.snapshot_date <= pd.trading_date
                          AND ss.shares_outstanding IS NOT NULL
                        ORDER BY ss.snapshot_date DESC LIMIT 1) sh ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT km.net_debt, km.non_controlling_interest FROM v_key_metrics km
                        WHERE km.company_id = pd.company_id AND km.period_end <= pd.trading_date
                          AND km.total_assets IS NOT NULL
                        ORDER BY km.period_end DESC LIMIT 1) bs ON TRUE
                    WHERE pd.close_price IS NOT NULL
                      AND pd.company_id IN (SELECT company_id FROM company ORDER BY company_id LIMIT 5)
                    EXCEPT
                    SELECT company_id, snapshot_date, share_price, shares_outstanding, market_cap, enterprise_value
                    FROM market_snapshot) x""").query(Integer.class).single();

        assertThat(different).isZero();
    }

    /**
     * Uploads that finish together: a request that waited while a whole run started and ended is served by it.
     * Six requests at once cost one or two runs (the first, and one for the requests made while it was running).
     */
    @Test
    void requestsThatWaitedForAWholeRunAreServedByIt() throws Exception {
        long before = repository.derivedDataRuns();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    repository.refreshDerivedData();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> call : calls) {
                call.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(repository.derivedDataRuns() - before).isBetween(1L, 2L);

        // a request made after the last run always gets its own
        long runs = repository.derivedDataRuns();
        repository.refreshDerivedData();
        assertThat(repository.derivedDataRuns()).isEqualTo(runs + 1);
    }

    private String fingerprint() {
        return jdbc.sql("""
                SELECT (SELECT count(*) || ':' || COALESCE(sum(market_cap), 0) || ':' || COALESCE(sum(enterprise_value), 0)
                        FROM market_snapshot)
                    || '|' || (SELECT count(*) || ':' || COALESCE(sum(pe_ratio), 0) FROM valuation_snapshot)
                    || '|' || (SELECT count(*) || ':' || COALESCE(sum(metric_value), 0) FROM financial_metric)""")
                .query(String.class).single();
    }
}
