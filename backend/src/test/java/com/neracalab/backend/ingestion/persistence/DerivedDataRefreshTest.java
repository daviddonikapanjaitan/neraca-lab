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

import com.neracalab.backend.price.ValuationRepository;

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
        // V1.0.6 over every company: 10.8 s with ~360 companies, 17.6 s with 625 (2026-10-31) - it grows with the data;
        // an upload refreshes its own company only (theRefreshOfOneCompanyLeavesWhatTheScriptComputes, under a second)
        assertThat(took).as("a refresh with nothing to change").isLessThan(Duration.ofSeconds(30));
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
     * A company with prices, a share count and a balance sheet has a valuation for its latest trading day, also without
     * trailing-twelve-month figures (BACH, listed 2026-07-08: its only filing is H1 2026, no FY2025 income statement is
     * stored, and it had no valuation at all). Such a valuation has market cap, EV, book value and P/B and no TTM
     * ratios, and the per-company refresh after new prices (ValuationRepository) gives the same rows as V1.0.6.
     */
    @Test
    void aCompanyWithoutTrailingTwelveMonthsIsValuedOnItsBalanceSheet(@Autowired ValuationRepository valuations) {
        repository.refreshDerivedData();

        List<String> unvalued = jdbc.sql("""
                SELECT c.ticker FROM company c
                JOIN LATERAL (SELECT max(trading_date) AS d FROM price_daily pd
                              WHERE pd.company_id = c.company_id AND pd.close_price IS NOT NULL) px ON px.d IS NOT NULL
                WHERE EXISTS (SELECT 1 FROM share_snapshot ss WHERE ss.company_id = c.company_id
                                AND ss.snapshot_date <= px.d AND ss.shares_outstanding IS NOT NULL)
                  AND EXISTS (SELECT 1 FROM v_key_metrics km JOIN v_ttm_financials t ON t.period_id = km.period_id
                              WHERE km.company_id = c.company_id AND km.period_end <= px.d AND km.total_assets IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM valuation_snapshot vs
                                  WHERE vs.company_id = c.company_id AND vs.valuation_date = px.d)""")
                .query(String.class).list();
        assertThat(unvalued).isEmpty();

        // no trailing twelve months at all - not merely no revenue (PEGE, an investment company, files none: it has
        // trailing earnings, so a P/E, and no P/S)
        List<Long> withoutTtm = jdbc.sql("""
                SELECT DISTINCT vs.company_id FROM valuation_snapshot vs WHERE vs.revenue_ttm IS NULL
                  AND NOT EXISTS (SELECT 1 FROM v_ttm_financials t WHERE t.company_id = vs.company_id
                                    AND t.period_end <= vs.valuation_date
                                    AND (t.revenue_ttm IS NOT NULL OR t.net_income_to_parent_ttm IS NOT NULL))""")
                .query(Long.class).list();
        for (long companyId : withoutTtm) {
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM valuation_snapshot WHERE company_id = :c
                      AND (market_cap IS NULL OR book_value IS NULL OR pb_ratio IS NULL OR pe_ratio IS NOT NULL
                           OR ps_ratio IS NOT NULL OR ev_ebitda IS NOT NULL OR eps_ttm IS NOT NULL)""")
                    .param("c", companyId).query(Long.class).single()).as("company " + companyId).isZero();
            assertThat(valuations.refresh(companyId).valuationSnapshots()).as("company " + companyId).isZero();
        }
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

    /**
     * After an upload only its company is refreshed ({@code IngestionRepository.refreshDerivedData(companyId)}:
     * {@link ValuationRepository#refreshAfterFiling}, 2026-10-31, for 50 uploads at once). For every company it leaves
     * exactly what V1.0.6 computes - market and valuation snapshots (the existing valuation dates revalued in both: the
     * first run of this test found MSIN's, GOTO's, DYAN's ... history rows stale in the script), cleared trailing ratios,
     * fundamental and valuation metrics, every row compared - and the script changes nothing it left; well under a
     * second per company.
     */
    @Test
    void theRefreshOfOneCompanyLeavesWhatTheScriptComputes(@Autowired ValuationRepository valuations) {
        repository.refreshDerivedData();
        String before = rows();
        List<Long> companies = jdbc.sql("SELECT company_id FROM company ORDER BY company_id").query(Long.class).list();

        long started = System.nanoTime();
        companies.forEach(valuations::refreshAfterFiling);
        Duration perCompany = Duration.ofNanos((System.nanoTime() - started) / Math.max(1, companies.size()));

        assertThat(rows()).isEqualTo(before);
        assertThat(perCompany).isLessThan(Duration.ofSeconds(1));
        // and the script changes nothing the per-company refresh left
        repository.refreshDerivedData();
        assertThat(rows()).isEqualTo(before);
    }

    /** Every derived row (key and values) hashed and summed: two states are the same only when every row is. */
    private String rows() {
        return jdbc.sql("""
                SELECT (SELECT count(*) || ':' || COALESCE(sum(hashtextextended(x::text, 0)::numeric), 0) FROM (
                            SELECT company_id, snapshot_date, share_price, shares_outstanding, market_cap, enterprise_value
                            FROM market_snapshot) x)
                    || '/' || (SELECT count(*) || ':' || COALESCE(sum(hashtextextended(x::text, 0)::numeric), 0) FROM (
                            SELECT company_id, period_id, valuation_date, share_price, market_cap, enterprise_value, eps_ttm,
                                   revenue_ttm, ebitda_ttm, operating_income_ttm, fcf_ttm, book_value, pe_ratio, ps_ratio,
                                   pb_ratio, ev_ebitda, ev_sales, ev_op, fcf_yield, earnings_yield, ebitda_basis
                            FROM valuation_snapshot) x)
                    || '/' || (SELECT count(*) || ':' || COALESCE(sum(hashtextextended(x::text, 0)::numeric), 0) FROM (
                            SELECT company_id, period_id, metric_date, metric_name, metric_category, metric_value, unit,
                                   calculation_formula, source
                            FROM financial_metric) x)""").query(String.class).single();
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
