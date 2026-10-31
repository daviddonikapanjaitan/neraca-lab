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
 * EV / EBITDA of an interim period whose TTM EBITDA is not derivable takes the full year inside the trailing window
 * (FY2025 for H1 2026; {@code ebitda_basis} 'FY 2025', else 'TTM'), as V1.0.6.
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
                ('ev_ebitda',        vs.ev_ebitda,        'x',         'enterprise_value / ebitda_ttm (else the full year''s ebitda: ebitda_basis)'),
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

    /**
     * Everything {@code V1.0.6} derives, for ONE company, after a filing of it was stored: market and valuation
     * snapshots, the trailing ratios of a valuation whose trailing twelve months no longer exist cleared, the
     * fundamental metrics per reporting period and the valuation metrics. A filing changes only its own company's
     * statements, so this replaces the run of the whole script for every company after each upload (10.8 s with 361
     * companies and 1.06 million prices on 2026-10-31, under one lock for all uploads; this takes well under a second
     * and runs beside the refreshes of other companies, whose rows it never touches).
     */
    public Refreshed refreshAfterFiling(long companyId) {
        int market = refreshMarketSnapshots(companyId);
        int valuations = refreshValuationSnapshots(companyId) + clearStaleTrailingRatios(companyId);
        refreshFundamentalMetrics(companyId);
        int metrics = refreshValuationMetrics(companyId);
        int deleted = deleteStaleValuationMetrics(companyId);
        return new Refreshed(market, valuations, metrics, deleted);
    }

    /**
     * The valuations on a period end whose trailing twelve months no longer exist (their periods were removed or
     * replaced: ANJT's USD FY2025) lose their trailing ratios; market cap, EV, book value and P/B stay (V1.0.6).
     */
    int clearStaleTrailingRatios(long companyId) {
        return jdbc.sql("""
                        UPDATE valuation_snapshot vs
                        SET eps_ttm = NULL, revenue_ttm = NULL, ebitda_ttm = NULL, operating_income_ttm = NULL, fcf_ttm = NULL,
                            pe_ratio = NULL, ps_ratio = NULL, ev_ebitda = NULL, ev_sales = NULL, ev_op = NULL, fcf_yield = NULL,
                            earnings_yield = NULL, ebitda_basis = NULL
                        WHERE vs.company_id = :c
                          AND (vs.eps_ttm IS NOT NULL OR vs.revenue_ttm IS NOT NULL OR vs.ebitda_ttm IS NOT NULL
                               OR vs.pe_ratio IS NOT NULL OR vs.ps_ratio IS NOT NULL OR vs.ev_ebitda IS NOT NULL
                               OR vs.fcf_yield IS NOT NULL)
                          AND EXISTS (SELECT 1 FROM reporting_period rp
                                      WHERE rp.company_id = vs.company_id AND rp.period_end = vs.valuation_date)
                          AND NOT EXISTS (SELECT 1 FROM v_ttm_financials t
                                          WHERE t.company_id = vs.company_id AND t.period_end = vs.valuation_date
                                            AND (t.revenue_ttm IS NOT NULL OR t.net_income_to_parent_ttm IS NOT NULL))""")
                .param("c", companyId)
                .update();
    }

    /** The fundamental metrics of every reporting period of the company, from {@code v_key_metrics} (V1.0.6). */
    int refreshFundamentalMetrics(long companyId) {
        return jdbc.sql("""
                        INSERT INTO financial_metric (
                            company_id, period_id, metric_date, metric_name, metric_category,
                            metric_value, unit, calculation_formula, source)
                        SELECT km.company_id, km.period_id, km.period_end,
                               m.metric_name, m.metric_category, m.metric_value,
                               CASE m.unit WHEN 'CUR' THEN km.currency
                                           WHEN 'CUR/share' THEN km.currency || '/share'
                                           ELSE m.unit END,
                               m.calculation_formula, 'v_key_metrics'
                        FROM v_key_metrics km
                        CROSS JOIN LATERAL (VALUES
                            ('gross_margin',               'PROFITABILITY', km.gross_margin,               'ratio', 'gross_profit / revenue'),
                            ('operating_margin',           'PROFITABILITY', km.operating_margin,           'ratio', 'operating_income / revenue'),
                            ('ebitda_margin',              'PROFITABILITY', km.ebitda_margin,              'ratio', 'ebitda / revenue'),
                            ('net_margin',                 'PROFITABILITY', km.net_margin,                 'ratio', 'net_income / revenue'),
                            ('effective_tax_rate',         'PROFITABILITY', km.effective_tax_rate,         'ratio', 'income_tax / pretax_income'),
                            ('interest_coverage',          'PROFITABILITY', km.interest_coverage,          'x',     'ebit / interest_expense'),
                            ('roe_annualized',             'PROFITABILITY', km.roe_annualized,             'ratio', 'net_income_to_parent x 12/months / shareholders_equity'),
                            ('roa_annualized',             'PROFITABILITY', km.roa_annualized,             'ratio', 'net_income x 12/months / total_assets'),
                            ('roic_annualized',            'PROFITABILITY', km.roic_annualized,            'ratio', 'ebit x (1 - tax rate) x 12/months / (total_equity + total_debt - cash_and_investments)'),
                            ('current_ratio',              'LIQUIDITY',     km.current_ratio,              'x',     'current_assets / current_liabilities'),
                            ('quick_ratio',                'LIQUIDITY',     km.quick_ratio,                'x',     '(current_assets - inventory) / current_liabilities'),
                            ('cash_ratio',                 'LIQUIDITY',     km.cash_ratio,                 'x',     'cash_and_investments / current_liabilities'),
                            ('debt_to_equity',             'LEVERAGE',      km.debt_to_equity,             'x',     'total_debt / shareholders_equity'),
                            ('net_debt_to_equity',         'LEVERAGE',      km.net_debt_to_equity,         'x',     'net_debt / shareholders_equity'),
                            ('liabilities_to_equity',      'LEVERAGE',      km.liabilities_to_equity,      'x',     'total_liabilities / total_equity'),
                            ('equity_to_assets',           'LEVERAGE',      km.equity_to_assets,           'ratio', 'total_equity / total_assets'),
                            ('net_debt_to_ebitda_annualized', 'LEVERAGE',   km.net_debt_to_ebitda_annualized, 'x',  'net_debt / (ebitda x 12/months)'),
                            ('inventory_days',             'EFFICIENCY',    km.inventory_days,             'days',  'inventory / cost_of_revenue x period_days'),
                            ('receivable_days',            'EFFICIENCY',    km.receivable_days,            'days',  'accounts_receivable / revenue x period_days'),
                            ('payable_days',               'EFFICIENCY',    km.payable_days,               'days',  'accounts_payable / cost_of_revenue x period_days'),
                            ('cash_conversion_cycle_days', 'EFFICIENCY',    km.cash_conversion_cycle_days, 'days',  'inventory_days + receivable_days - payable_days'),
                            ('free_cash_flow',             'CASH_FLOW',     km.free_cash_flow,             'CUR',   'operating_cash_flow + capital_expenditure'),
                            ('fcf_after_leases',           'CASH_FLOW',     km.fcf_after_leases,           'CUR',   'free_cash_flow + lease_payments'),
                            ('fcf_margin',                 'CASH_FLOW',     km.fcf_margin,                 'ratio', 'free_cash_flow / revenue'),
                            ('ocf_to_net_income',          'CASH_FLOW',     km.ocf_to_net_income,          'x',     'operating_cash_flow / net_income'),
                            ('capex_to_revenue',           'CASH_FLOW',     km.capex_to_revenue,           'ratio', '-capital_expenditure / revenue'),
                            ('net_debt',                   'BALANCE_SHEET', km.net_debt,                   'CUR',   'short_term_debt + long_term_debt + lease_liabilities - cash_and_equivalents - marketable_securities'),
                            ('working_capital',            'BALANCE_SHEET', km.working_capital,            'CUR',   'current_assets - current_liabilities'),
                            ('tangible_book_value',        'BALANCE_SHEET', km.tangible_book_value,        'CUR',   'shareholders_equity - goodwill - intangible_assets'),
                            ('ncav',                       'BALANCE_SHEET', km.ncav,                       'CUR',   'current_assets - total_liabilities (net-net)'),
                            ('eps',                        'PER_SHARE',     km.eps,                        'CUR/share', 'reported diluted EPS, else basic EPS'),
                            ('book_value_per_share',       'PER_SHARE',     km.book_value_per_share,       'CUR/share', 'shareholders_equity / shares'),
                            ('tangible_book_per_share',    'PER_SHARE',     km.tangible_book_per_share,    'CUR/share', 'tangible_book_value / shares'),
                            ('ncav_per_share',             'PER_SHARE',     km.ncav_per_share,             'CUR/share', 'ncav / shares'),
                            ('net_cash_per_share',         'PER_SHARE',     km.net_cash_per_share,         'CUR/share', '-net_debt / shares'),
                            ('fcf_per_share',              'PER_SHARE',     km.fcf_per_share,              'CUR/share', 'free_cash_flow / shares')
                        ) AS m (metric_name, metric_category, metric_value, unit, calculation_formula)
                        WHERE km.company_id = :c
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
                               EXCLUDED.unit, EXCLUDED.calculation_formula, EXCLUDED.source)""")
                .param("c", companyId)
                .update();
    }

    int refreshMarketSnapshots(long companyId) {
        return jdbc.sql("""
                        INSERT INTO market_snapshot (
                            company_id, snapshot_date, share_price, shares_outstanding, market_cap, enterprise_value)
                        WITH bs AS MATERIALIZED (
                            SELECT d.company_id, d.period_end, d.net_debt, d.non_controlling_interest,
                                   LEAD(d.period_end) OVER (PARTITION BY d.company_id ORDER BY d.period_end) AS next_period_end
                            FROM (
                                SELECT DISTINCT ON (km.company_id, km.period_end)
                                       km.company_id, km.period_end, km.net_debt, km.non_controlling_interest
                                FROM v_key_metrics km
                                WHERE km.company_id = :c
                                  AND km.total_assets IS NOT NULL
                                ORDER BY km.company_id, km.period_end, km.period_id
                            ) d
                        ),
                        calc AS (
                        SELECT
                            pd.company_id,
                            pd.trading_date,
                            pd.close_price,
                            sh.shares_outstanding,
                            ROUND(pd.close_price * sh.shares_outstanding, 4) AS market_cap,
                            ROUND(pd.close_price * sh.shares_outstanding + bs.net_debt
                                  + COALESCE(bs.non_controlling_interest, 0), 4) AS enterprise_value
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
                        LEFT JOIN bs ON bs.company_id = pd.company_id
                                    AND bs.period_end <= pd.trading_date
                                    AND (bs.next_period_end IS NULL OR bs.next_period_end > pd.trading_date)
                        WHERE pd.company_id = :c
                          AND pd.close_price IS NOT NULL
                        )
                        SELECT c.company_id, c.trading_date, c.close_price, c.shares_outstanding, c.market_cap, c.enterprise_value
                        FROM calc c
                        LEFT JOIN market_snapshot ms ON ms.company_id = c.company_id AND ms.snapshot_date = c.trading_date
                        WHERE ms.snapshot_id IS NULL
                           OR (ms.share_price, ms.shares_outstanding, ms.market_cap, ms.enterprise_value)
                              IS DISTINCT FROM
                              (c.close_price, c.shares_outstanding, c.market_cap, c.enterprise_value)
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
                            pe_ratio, ps_ratio, pb_ratio, ev_ebitda, ev_sales, ev_op, fcf_yield, earnings_yield,
                            ebitda_basis)
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
                                COALESCE(f.ebitda_ttm, lfy.ebitda)               AS ebitda_used,
                                CASE WHEN f.ebitda_ttm IS NOT NULL THEN 'TTM'
                                     WHEN lfy.ebitda IS NOT NULL THEN 'FY ' || lfy.fiscal_year END AS ebitda_basis,
                                f.operating_income_ttm,
                                f.free_cash_flow_ttm                             AS fcf_ttm,
                                f.shareholders_equity                            AS book_value
                            FROM valuation_dates d
                            -- fundamentals: latest period on or before the date with TTM revenue and a balance
                            -- sheet; without any (newly listed company) the latest balance sheet: no TTM figures
                            JOIN LATERAL (
                                SELECT t.period_id, t.fiscal_year, t.period_type, t.revenue_ttm, t.ebitda_ttm,
                                       t.operating_income_ttm, t.net_income_to_parent_ttm, t.free_cash_flow_ttm,
                                       km.net_debt, km.non_controlling_interest, km.shares, km.shareholders_equity
                                FROM v_ttm_financials t
                                JOIN v_key_metrics km ON km.period_id = t.period_id
                                WHERE t.company_id = d.company_id
                                  AND t.period_end <= d.valuation_date
                                  AND km.total_assets IS NOT NULL
                                ORDER BY t.revenue_ttm IS NULL, t.period_end DESC
                                LIMIT 1
                            ) f ON TRUE
                            -- an interim period without TTM EBITDA: the full year inside its trailing window
                            LEFT JOIN LATERAL (
                                SELECT i.ebitda, rp.fiscal_year
                                FROM reporting_period rp
                                JOIN income_statement i ON i.period_id = rp.period_id
                                WHERE rp.company_id = d.company_id
                                  AND rp.period_type = 'FY'
                                  AND rp.fiscal_year = f.fiscal_year - 1
                                  AND i.ebitda IS NOT NULL
                                ORDER BY rp.period_end DESC
                                LIMIT 1
                            ) lfy ON f.ebitda_ttm IS NULL AND f.period_type <> 'FY'
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
                            ROUND(enterprise_value / NULLIF(ebitda_used, 0), 8)         AS ev_ebitda,
                            ROUND(enterprise_value / NULLIF(revenue_ttm, 0), 8)         AS ev_sales,
                            ROUND(enterprise_value / NULLIF(operating_income_ttm, 0), 8) AS ev_op,
                            ROUND(fcf_ttm / NULLIF(market_cap, 0), 8)                   AS fcf_yield,
                            ROUND(eps_ttm / NULLIF(share_price, 0), 8)                  AS earnings_yield,
                            ebitda_basis
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
                            earnings_yield       = EXCLUDED.earnings_yield,
                            ebitda_basis         = EXCLUDED.ebitda_basis
                        WHERE (valuation_snapshot.period_id, valuation_snapshot.share_price,
                               valuation_snapshot.market_cap, valuation_snapshot.enterprise_value,
                               valuation_snapshot.eps_ttm, valuation_snapshot.revenue_ttm,
                               valuation_snapshot.ebitda_ttm, valuation_snapshot.operating_income_ttm,
                               valuation_snapshot.fcf_ttm, valuation_snapshot.book_value,
                               valuation_snapshot.pe_ratio, valuation_snapshot.ps_ratio,
                               valuation_snapshot.pb_ratio, valuation_snapshot.ev_ebitda,
                               valuation_snapshot.ev_sales, valuation_snapshot.ev_op,
                               valuation_snapshot.fcf_yield, valuation_snapshot.earnings_yield,
                               valuation_snapshot.ebitda_basis)
                              IS DISTINCT FROM
                              (EXCLUDED.period_id, EXCLUDED.share_price,
                               EXCLUDED.market_cap, EXCLUDED.enterprise_value,
                               EXCLUDED.eps_ttm, EXCLUDED.revenue_ttm,
                               EXCLUDED.ebitda_ttm, EXCLUDED.operating_income_ttm,
                               EXCLUDED.fcf_ttm, EXCLUDED.book_value,
                               EXCLUDED.pe_ratio, EXCLUDED.ps_ratio,
                               EXCLUDED.pb_ratio, EXCLUDED.ev_ebitda,
                               EXCLUDED.ev_sales, EXCLUDED.ev_op,
                               EXCLUDED.fcf_yield, EXCLUDED.earnings_yield,
                               EXCLUDED.ebitda_basis)""")
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

    public int deleteStaleValuationMetrics(long companyId) {
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
