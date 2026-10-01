-- =====================================================================
-- Derived data: market_snapshot, valuation_snapshot, financial_metric
--
-- Calculated from the loaded statements, price_daily and share_snapshot for every
-- company, so this script runs after all data scripts. Re-running recalculates and
-- upserts (ids stay stable); add new company data scripts before this one.
--
--   market_snapshot    one row per trading day:
--                      market_cap = close x latest share_snapshot.shares_outstanding,
--                      enterprise_value = market_cap + net debt + non-controlling interest
--                      of the latest balance sheet with period_end <= trading day
--                      (NULL before the first available balance sheet).
--   valuation_snapshot every period end that has trailing-twelve-month figures, plus
--                      the latest trading day; price = last close on or before the date,
--                      fundamentals = latest period ended on or before the date that has
--                      TTM revenue and a balance sheet.
--   financial_metric   fundamental metrics per reporting period (from v_key_metrics) and
--                      valuation metrics per valuation date (from valuation_snapshot),
--                      including ev_op = enterprise value / TTM operating profit.
--
-- Ratios are fractions (0.25 = 25%), multiples are "x". NULL results are not stored.
-- =====================================================================

-- ---------------------------------------------------------------------
-- market_snapshot
-- ---------------------------------------------------------------------
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
WHERE pd.close_price IS NOT NULL
ON CONFLICT ON CONSTRAINT uq_market_snapshot DO UPDATE SET
    share_price        = EXCLUDED.share_price,
    shares_outstanding = EXCLUDED.shares_outstanding,
    market_cap         = EXCLUDED.market_cap,
    enterprise_value   = EXCLUDED.enterprise_value
WHERE (market_snapshot.share_price, market_snapshot.shares_outstanding,
       market_snapshot.market_cap, market_snapshot.enterprise_value)
      IS DISTINCT FROM
      (EXCLUDED.share_price, EXCLUDED.shares_outstanding,
       EXCLUDED.market_cap, EXCLUDED.enterprise_value);

-- ---------------------------------------------------------------------
-- valuation_snapshot
-- ---------------------------------------------------------------------
INSERT INTO valuation_snapshot (
    company_id, period_id, valuation_date,
    share_price, market_cap, enterprise_value,
    eps_ttm, revenue_ttm, ebitda_ttm, operating_income_ttm, fcf_ttm, book_value,
    pe_ratio, ps_ratio, pb_ratio, ev_ebitda, ev_sales, ev_op, fcf_yield, earnings_yield)
WITH valuation_dates AS (
    SELECT t.company_id, t.period_end AS valuation_date
    FROM v_ttm_financials t
    WHERE t.revenue_ttm IS NOT NULL
    UNION
    SELECT pd.company_id, MAX(pd.trading_date)
    FROM price_daily pd
    WHERE pd.close_price IS NOT NULL
    GROUP BY pd.company_id
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
    earnings_yield       = EXCLUDED.earnings_yield;

-- ---------------------------------------------------------------------
-- financial_metric : fundamental metrics per reporting period
-- ---------------------------------------------------------------------
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
    -- profitability
    ('gross_margin',               'PROFITABILITY', km.gross_margin,               'ratio', 'gross_profit / revenue'),
    ('operating_margin',           'PROFITABILITY', km.operating_margin,           'ratio', 'operating_income / revenue'),
    ('ebitda_margin',              'PROFITABILITY', km.ebitda_margin,              'ratio', 'ebitda / revenue'),
    ('net_margin',                 'PROFITABILITY', km.net_margin,                 'ratio', 'net_income / revenue'),
    ('effective_tax_rate',         'PROFITABILITY', km.effective_tax_rate,         'ratio', 'income_tax / pretax_income'),
    ('interest_coverage',          'PROFITABILITY', km.interest_coverage,          'x',     'ebit / interest_expense'),
    ('roe_annualized',             'PROFITABILITY', km.roe_annualized,             'ratio', 'net_income_to_parent x 12/months / shareholders_equity'),
    ('roa_annualized',             'PROFITABILITY', km.roa_annualized,             'ratio', 'net_income x 12/months / total_assets'),
    ('roic_annualized',            'PROFITABILITY', km.roic_annualized,            'ratio', 'ebit x (1 - tax rate) x 12/months / (total_equity + total_debt - cash_and_investments)'),
    -- liquidity
    ('current_ratio',              'LIQUIDITY',     km.current_ratio,              'x',     'current_assets / current_liabilities'),
    ('quick_ratio',                'LIQUIDITY',     km.quick_ratio,                'x',     '(current_assets - inventory) / current_liabilities'),
    ('cash_ratio',                 'LIQUIDITY',     km.cash_ratio,                 'x',     'cash_and_investments / current_liabilities'),
    -- leverage
    ('debt_to_equity',             'LEVERAGE',      km.debt_to_equity,             'x',     'total_debt / shareholders_equity'),
    ('net_debt_to_equity',         'LEVERAGE',      km.net_debt_to_equity,         'x',     'net_debt / shareholders_equity'),
    ('liabilities_to_equity',      'LEVERAGE',      km.liabilities_to_equity,      'x',     'total_liabilities / total_equity'),
    ('equity_to_assets',           'LEVERAGE',      km.equity_to_assets,           'ratio', 'total_equity / total_assets'),
    ('net_debt_to_ebitda_annualized', 'LEVERAGE',   km.net_debt_to_ebitda_annualized, 'x',  'net_debt / (ebitda x 12/months)'),
    -- efficiency
    ('inventory_days',             'EFFICIENCY',    km.inventory_days,             'days',  'inventory / cost_of_revenue x period_days'),
    ('receivable_days',            'EFFICIENCY',    km.receivable_days,            'days',  'accounts_receivable / revenue x period_days'),
    ('payable_days',               'EFFICIENCY',    km.payable_days,               'days',  'accounts_payable / cost_of_revenue x period_days'),
    ('cash_conversion_cycle_days', 'EFFICIENCY',    km.cash_conversion_cycle_days, 'days',  'inventory_days + receivable_days - payable_days'),
    -- cash flow
    ('free_cash_flow',             'CASH_FLOW',     km.free_cash_flow,             'CUR',   'operating_cash_flow + capital_expenditure'),
    ('fcf_after_leases',           'CASH_FLOW',     km.fcf_after_leases,           'CUR',   'free_cash_flow + lease_payments'),
    ('fcf_margin',                 'CASH_FLOW',     km.fcf_margin,                 'ratio', 'free_cash_flow / revenue'),
    ('ocf_to_net_income',          'CASH_FLOW',     km.ocf_to_net_income,          'x',     'operating_cash_flow / net_income'),
    ('capex_to_revenue',           'CASH_FLOW',     km.capex_to_revenue,           'ratio', '-capital_expenditure / revenue'),
    -- balance sheet
    ('net_debt',                   'BALANCE_SHEET', km.net_debt,                   'CUR',   'short_term_debt + long_term_debt + lease_liabilities - cash_and_equivalents - marketable_securities'),
    ('working_capital',            'BALANCE_SHEET', km.working_capital,            'CUR',   'current_assets - current_liabilities'),
    ('tangible_book_value',        'BALANCE_SHEET', km.tangible_book_value,        'CUR',   'shareholders_equity - goodwill - intangible_assets'),
    ('ncav',                       'BALANCE_SHEET', km.ncav,                       'CUR',   'current_assets - total_liabilities (net-net)'),
    -- per share
    ('eps',                        'PER_SHARE',     km.eps,                        'CUR/share', 'reported diluted EPS, else basic EPS'),
    ('book_value_per_share',       'PER_SHARE',     km.book_value_per_share,       'CUR/share', 'shareholders_equity / shares'),
    ('tangible_book_per_share',    'PER_SHARE',     km.tangible_book_per_share,    'CUR/share', 'tangible_book_value / shares'),
    ('ncav_per_share',             'PER_SHARE',     km.ncav_per_share,             'CUR/share', 'ncav / shares'),
    ('net_cash_per_share',         'PER_SHARE',     km.net_cash_per_share,         'CUR/share', '-net_debt / shares'),
    ('fcf_per_share',              'PER_SHARE',     km.fcf_per_share,              'CUR/share', 'free_cash_flow / shares')
) AS m (metric_name, metric_category, metric_value, unit, calculation_formula)
WHERE m.metric_value IS NOT NULL
ON CONFLICT ON CONSTRAINT uq_financial_metric DO UPDATE SET
    metric_category     = EXCLUDED.metric_category,
    metric_value        = EXCLUDED.metric_value,
    unit                = EXCLUDED.unit,
    calculation_formula = EXCLUDED.calculation_formula,
    source              = EXCLUDED.source;

-- ---------------------------------------------------------------------
-- financial_metric : valuation metrics per valuation date (incl. EV/OP)
-- ---------------------------------------------------------------------
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
CROSS JOIN LATERAL (VALUES
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
) AS m (metric_name, metric_value, unit, calculation_formula)
WHERE m.metric_value IS NOT NULL
ON CONFLICT ON CONSTRAINT uq_financial_metric DO UPDATE SET
    metric_category     = EXCLUDED.metric_category,
    metric_value        = EXCLUDED.metric_value,
    unit                = EXCLUDED.unit,
    calculation_formula = EXCLUDED.calculation_formula,
    source              = EXCLUDED.source;
