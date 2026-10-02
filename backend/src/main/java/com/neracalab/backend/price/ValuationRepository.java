package com.neracalab.backend.price;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Recalculates the price-dependent data of ONE company after new prices: market_snapshot,
 * valuation_snapshot and the VALUATION rows of financial_metric.
 * <p>
 * The formulas are those of {@code db/V1.0.6__data_metrics_valuation.sql} (which recalculates
 * every company on start and after a filing upload), restricted to one company so a nightly run
 * over hundreds of tickers stays cheap. Keep both in sync. Differences, all limited to the company:
 * <ul>
 *   <li>valuation dates also include the company's existing valuation_snapshot dates, so the
 *       snapshots of earlier "latest trading days" are kept up to date when prices are corrected</li>
 *   <li>VALUATION metrics no longer produced by a valuation_snapshot (value became NULL, period
 *       changed) are deleted, so financial_metric never keeps a stale valuation</li>
 * </ul>
 */
@Repository
public class ValuationRepository {

    /** Valuation metrics of a valuation_snapshot row {@code vs} (V1.0.6). */
    private static final String VALUATION_METRICS = """
            (VALUES
                ('market_cap',       vs.market_cap,       'CUR',       'share_price x shares_outstanding'),
                ('enterprise_value', vs.enterprise_value, 'CUR',       'market_cap + total_debt - cash_and_investments + non_controlling_interest'),
                ('eps_ttm',          vs.eps_ttm,          'CUR/share', 'net_income_to_parent_ttm / shares_outstanding'),
                ('pe_ratio',         vs.pe_ratio,         'x',         'share_price / eps_ttm'),
                ('ps_ratio',         vs.ps_ratio,         'x',         'market_cap / revenue_ttm'),
                ('pb_ratio',         vs.pb_ratio,         'x',         'market_cap / book_value'),
                ('ev_ebitda',        vs.ev_ebitda,        'x',         'enterprise_value / ebitda_ttm'),
                ('ev_sales',         vs.ev_sales,         'x',         'enterprise_value / revenue_ttm'),
                ('ev_op',            vs.ev_op,            'x',         'enterprise_value / operating_income_ttm (EV / operating profit)'),
                ('fcf_yield',        vs.fcf_yield,        'ratio',     'fcf_ttm / market_cap'),
                ('earnings_yield',   vs.earnings_yield,   'ratio',     'eps_ttm / share_price')
            ) AS m (metric_name, metric_value, unit, calculation_formula)""";

    /** Counts of rows written by {@link #refresh(long)}. */
    public record Refreshed(int marketSnapshots, int valuationSnapshots, int valuationMetrics,
                            int staleValuationMetricsDeleted) {
    }

    private final JdbcClient jdbc;

    public ValuationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Market snapshots, then valuation snapshots, then valuation metrics (each step reads the previous). */
    public Refreshed refresh(long companyId) {
        int market = refreshMarketSnapshots(companyId);
        int valuations = refreshValuationSnapshots(companyId);
        int metrics = refreshValuationMetrics(companyId);
        int deleted = deleteStaleValuationMetrics(companyId);
        return new Refreshed(market, valuations, metrics, deleted);
    }

    int refreshMarketSnapshots(long companyId) {
        return jdbc.sql("""
                        INSERT INTO market_snapshot (
                            company_id, snapshot_date, share_price, shares_outstanding, market_cap, enterprise_value)
                        SELECT
                            pd.company_id,
                            pd.trading_date,
                            pd.close_price,
                            sh.shares_outstanding,
                            ROUND(pd.close_price * sh.shares_outstanding, 4),
                            ROUND(pd.close_price * sh.shares_outstanding + bs.net_debt
                                  + COALESCE(bs.non_controlling_interest, 0), 4)
                        FROM price_daily pd
                        LEFT JOIN LATERAL (
                            SELECT ss.shares_outstanding
                            FROM share_snapshot ss
                            WHERE ss.company_id = pd.company_id
                              AND ss.snapshot_date <= pd.trading_date
                              AND ss.shares_outstanding IS NOT NULL
                            ORDER BY ss.snapshot_date DESC
                            LIMIT 1
                        ) sh ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT km.net_debt, km.non_controlling_interest
                            FROM v_key_metrics km
                            WHERE km.company_id = pd.company_id
                              AND km.period_end <= pd.trading_date
                              AND km.total_assets IS NOT NULL
                            ORDER BY km.period_end DESC
                            LIMIT 1
                        ) bs ON TRUE
                        WHERE pd.company_id = :c
                          AND pd.close_price IS NOT NULL
                        ON CONFLICT ON CONSTRAINT uq_market_snapshot DO UPDATE SET
                            share_price        = EXCLUDED.share_price,
                            shares_outstanding = EXCLUDED.shares_outstanding,
                            market_cap         = EXCLUDED.market_cap,
                            enterprise_value   = EXCLUDED.enterprise_value
                        WHERE (market_snapshot.share_price, market_snapshot.shares_outstanding,
                               market_snapshot.market_cap, market_snapshot.enterprise_value)
                              IS DISTINCT FROM
                              (EXCLUDED.share_price, EXCLUDED.shares_outstanding,
                               EXCLUDED.market_cap, EXCLUDED.enterprise_value)""")
                .param("c", companyId)
                .update();
    }

    int refreshValuationSnapshots(long companyId) {
        return jdbc.sql("""
                        INSERT INTO valuation_snapshot (
                            company_id, period_id, valuation_date,
                            share_price, market_cap, enterprise_value,
                            eps_ttm, revenue_ttm, ebitda_ttm, operating_income_ttm, fcf_ttm, book_value,
                            pe_ratio, ps_ratio, pb_ratio, ev_ebitda, ev_sales, ev_op, fcf_yield, earnings_yield)
                        WITH valuation_dates AS (
                            SELECT t.company_id, t.period_end AS valuation_date
                            FROM v_ttm_financials t
                            WHERE t.company_id = :c
                              AND t.revenue_ttm IS NOT NULL
                            UNION
                            SELECT pd.company_id, MAX(pd.trading_date)
                            FROM price_daily pd
                            WHERE pd.company_id = :c
                              AND pd.close_price IS NOT NULL
                            GROUP BY pd.company_id
                            UNION
                            SELECT vs.company_id, vs.valuation_date
                            FROM valuation_snapshot vs
                            WHERE vs.company_id = :c
                        ),
                        valued AS (
                            SELECT
                                d.company_id,
                                d.valuation_date,
                                f.period_id,
                                px.close_price                                   AS share_price,
                                px.close_price * sh.shares_outstanding           AS market_cap,
                                px.close_price * sh.shares_outstanding + f.net_debt
                                    + COALESCE(f.non_controlling_interest, 0)    AS enterprise_value,
                                f.net_income_to_parent_ttm / NULLIF(f.shares, 0) AS eps_ttm,
                                f.revenue_ttm,
                                f.ebitda_ttm,
                                f.operating_income_ttm,
                                f.free_cash_flow_ttm                             AS fcf_ttm,
                                f.shareholders_equity                            AS book_value
                            FROM valuation_dates d
                            -- fundamentals: latest period on or before the date with TTM revenue and a balance sheet
                            JOIN LATERAL (
                                SELECT t.period_id, t.revenue_ttm, t.ebitda_ttm, t.operating_income_ttm,
                                       t.net_income_to_parent_ttm, t.free_cash_flow_ttm,
                                       km.net_debt, km.non_controlling_interest, km.shares, km.shareholders_equity
                                FROM v_ttm_financials t
                                JOIN v_key_metrics km ON km.period_id = t.period_id
                                WHERE t.company_id = d.company_id
                                  AND t.period_end <= d.valuation_date
                                  AND t.revenue_ttm IS NOT NULL
                                  AND km.total_assets IS NOT NULL
                                ORDER BY t.period_end DESC
                                LIMIT 1
                            ) f ON TRUE
                            -- price: last close on or before the date
                            JOIN LATERAL (
                                SELECT pd.close_price
                                FROM price_daily pd
                                WHERE pd.company_id = d.company_id
                                  AND pd.trading_date <= d.valuation_date
                                  AND pd.close_price IS NOT NULL
                                ORDER BY pd.trading_date DESC
                                LIMIT 1
                            ) px ON TRUE
                            -- shares: latest share count on or before the date
                            JOIN LATERAL (
                                SELECT ss.shares_outstanding
                                FROM share_snapshot ss
                                WHERE ss.company_id = d.company_id
                                  AND ss.snapshot_date <= d.valuation_date
                                  AND ss.shares_outstanding IS NOT NULL
                                ORDER BY ss.snapshot_date DESC
                                LIMIT 1
                            ) sh ON TRUE
                        )
                        SELECT
                            company_id, period_id, valuation_date,
                            share_price,
                            ROUND(market_cap, 4),
                            ROUND(enterprise_value, 4),
                            ROUND(eps_ttm, 8),
                            revenue_ttm, ebitda_ttm, operating_income_ttm, fcf_ttm, book_value,
                            ROUND(share_price / NULLIF(eps_ttm, 0), 8)                  AS pe_ratio,
                            ROUND(market_cap / NULLIF(revenue_ttm, 0), 8)               AS ps_ratio,
                            ROUND(market_cap / NULLIF(book_value, 0), 8)                AS pb_ratio,
                            ROUND(enterprise_value / NULLIF(ebitda_ttm, 0), 8)          AS ev_ebitda,
                            ROUND(enterprise_value / NULLIF(revenue_ttm, 0), 8)         AS ev_sales,
                            ROUND(enterprise_value / NULLIF(operating_income_ttm, 0), 8) AS ev_op,
                            ROUND(fcf_ttm / NULLIF(market_cap, 0), 8)                   AS fcf_yield,
                            ROUND(eps_ttm / NULLIF(share_price, 0), 8)                  AS earnings_yield
                        FROM valued
                        ON CONFLICT ON CONSTRAINT uq_valuation_snapshot DO UPDATE SET
                            period_id            = EXCLUDED.period_id,
                            share_price          = EXCLUDED.share_price,
                            market_cap           = EXCLUDED.market_cap,
                            enterprise_value     = EXCLUDED.enterprise_value,
                            eps_ttm              = EXCLUDED.eps_ttm,
                            revenue_ttm          = EXCLUDED.revenue_ttm,
                            ebitda_ttm           = EXCLUDED.ebitda_ttm,
                            operating_income_ttm = EXCLUDED.operating_income_ttm,
                            fcf_ttm              = EXCLUDED.fcf_ttm,
                            book_value           = EXCLUDED.book_value,
                            pe_ratio             = EXCLUDED.pe_ratio,
                            ps_ratio             = EXCLUDED.ps_ratio,
                            pb_ratio             = EXCLUDED.pb_ratio,
                            ev_ebitda            = EXCLUDED.ev_ebitda,
                            ev_sales             = EXCLUDED.ev_sales,
                            ev_op                = EXCLUDED.ev_op,
                            fcf_yield            = EXCLUDED.fcf_yield,
                            earnings_yield       = EXCLUDED.earnings_yield
                        WHERE (valuation_snapshot.period_id, valuation_snapshot.share_price,
                               valuation_snapshot.market_cap, valuation_snapshot.enterprise_value,
                               valuation_snapshot.eps_ttm, valuation_snapshot.revenue_ttm,
                               valuation_snapshot.ebitda_ttm, valuation_snapshot.operating_income_ttm,
                               valuation_snapshot.fcf_ttm, valuation_snapshot.book_value,
                               valuation_snapshot.pe_ratio, valuation_snapshot.ps_ratio,
                               valuation_snapshot.pb_ratio, valuation_snapshot.ev_ebitda,
                               valuation_snapshot.ev_sales, valuation_snapshot.ev_op,
                               valuation_snapshot.fcf_yield, valuation_snapshot.earnings_yield)
                              IS DISTINCT FROM
                              (EXCLUDED.period_id, EXCLUDED.share_price,
                               EXCLUDED.market_cap, EXCLUDED.enterprise_value,
                               EXCLUDED.eps_ttm, EXCLUDED.revenue_ttm,
                               EXCLUDED.ebitda_ttm, EXCLUDED.operating_income_ttm,
                               EXCLUDED.fcf_ttm, EXCLUDED.book_value,
                               EXCLUDED.pe_ratio, EXCLUDED.ps_ratio,
                               EXCLUDED.pb_ratio, EXCLUDED.ev_ebitda,
                               EXCLUDED.ev_sales, EXCLUDED.ev_op,
                               EXCLUDED.fcf_yield, EXCLUDED.earnings_yield)""")
                .param("c", companyId)
                .update();
    }

    int refreshValuationMetrics(long companyId) {
        return jdbc.sql("""
                        INSERT INTO financial_metric (
                            company_id, period_id, metric_date, metric_name, metric_category,
                            metric_value, unit, calculation_formula, source)
                        SELECT vs.company_id, vs.period_id, vs.valuation_date,
                               m.metric_name, 'VALUATION', m.metric_value,
                               CASE m.unit WHEN 'CUR' THEN c.currency
                                           WHEN 'CUR/share' THEN c.currency || '/share'
                                           ELSE m.unit END,
                               m.calculation_formula, 'valuation_snapshot'
                        FROM valuation_snapshot vs
                        JOIN company c ON c.company_id = vs.company_id
                        CROSS JOIN LATERAL %s
                        WHERE vs.company_id = :c
                          AND m.metric_value IS NOT NULL
                        ON CONFLICT ON CONSTRAINT uq_financial_metric DO UPDATE SET
                            metric_category     = EXCLUDED.metric_category,
                            metric_value        = EXCLUDED.metric_value,
                            unit                = EXCLUDED.unit,
                            calculation_formula = EXCLUDED.calculation_formula,
                            source              = EXCLUDED.source
                        WHERE (financial_metric.metric_category, financial_metric.metric_value,
                               financial_metric.unit, financial_metric.calculation_formula, financial_metric.source)
                              IS DISTINCT FROM
                              (EXCLUDED.metric_category, EXCLUDED.metric_value,
                               EXCLUDED.unit, EXCLUDED.calculation_formula, EXCLUDED.source)"""
                        .formatted(VALUATION_METRICS))
                .param("c", companyId)
                .update();
    }

    int deleteStaleValuationMetrics(long companyId) {
        return jdbc.sql("""
                        DELETE FROM financial_metric fm
                        WHERE fm.company_id = :c
                          AND fm.source = 'valuation_snapshot'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM valuation_snapshot vs
                              CROSS JOIN LATERAL %s
                              WHERE vs.company_id = fm.company_id
                                AND vs.valuation_date = fm.metric_date
                                AND vs.period_id IS NOT DISTINCT FROM fm.period_id
                                AND m.metric_name = fm.metric_name
                                AND m.metric_value IS NOT NULL)"""
                        .formatted(VALUATION_METRICS))
                .param("c", companyId)
                .update();
    }
}
