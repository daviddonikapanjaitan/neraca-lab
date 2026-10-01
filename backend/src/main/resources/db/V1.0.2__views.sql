-- =====================================================================
-- Analysis views - "Roaring Kitty" (Keith Gill) style deep-value checklist
--
--   1. Balance sheet first : net cash, net-net (NCAV), tangible book, liquidity.
--   2. Cash generation     : FCF, FCF after leases, cash conversion of earnings.
--   3. Profitability       : margins, ROE / ROA / ROIC, interest coverage.
--   4. Efficiency          : inventory / receivable / payable days.
--   5. Valuation vs price  : P/E, P/B, P/TBV, EV/EBIT, EV/Sales, FCF yield,
--                            net cash as % of market cap.
--
-- Flow figures of partial-year periods are annualised (x 12 / months)
-- in the *_annualized columns; prefer FY or TTM periods when available.
-- Views are dropped and recreated on every start (they hold no data).
-- =====================================================================

DROP VIEW IF EXISTS v_latest_valuation;
DROP VIEW IF EXISTS v_valuation;
DROP VIEW IF EXISTS v_key_metrics;

-- ---------------------------------------------------------------------
-- v_key_metrics : one row per company per reporting period
-- ---------------------------------------------------------------------
CREATE VIEW v_key_metrics AS
WITH base AS (
    SELECT
        c.company_id,
        c.ticker,
        c.exchange,
        c.currency,
        rp.period_id,
        rp.fiscal_year,
        rp.fiscal_quarter,
        rp.period_type,
        rp.period_start,
        rp.period_end,
        rp.audited,
        (rp.period_end - rp.period_start + 1) AS period_days,
        (EXTRACT(YEAR  FROM age(rp.period_end + 1, rp.period_start)) * 12
       + EXTRACT(MONTH FROM age(rp.period_end + 1, rp.period_start)))::INTEGER AS period_months,

        i.revenue, i.cost_of_revenue, i.gross_profit, i.operating_income,
        i.ebit, i.ebitda, i.interest_expense, i.pretax_income, i.income_tax,
        i.net_income, i.net_income_to_parent, i.basic_eps, i.diluted_eps,

        b.cash_and_equivalents, b.marketable_securities, b.accounts_receivable,
        b.inventory, b.current_assets, b.total_assets, b.accounts_payable,
        b.deferred_revenue, b.current_liabilities, b.total_liabilities,
        b.shareholders_equity, b.non_controlling_interest, b.total_equity,
        b.goodwill, b.intangible_assets,

        b.short_term_debt + b.long_term_debt + COALESCE(b.lease_liabilities, 0) AS total_debt,
        b.cash_and_equivalents + COALESCE(b.marketable_securities, 0)          AS cash_and_investments,
        COALESCE(b.shares_outstanding, i.basic_shares)                          AS shares,

        cf.operating_cash_flow, cf.capital_expenditure, cf.lease_payments,
        cf.dividends_paid, cf.share_buybacks,
        cf.operating_cash_flow + cf.capital_expenditure                          AS free_cash_flow
    FROM reporting_period rp
    JOIN company c                     ON c.company_id = rp.company_id
    LEFT JOIN income_statement i   ON i.period_id  = rp.period_id
    LEFT JOIN balance_sheet b      ON b.period_id  = rp.period_id
    LEFT JOIN cash_flow_statement cf ON cf.period_id = rp.period_id
),
calc AS (
    SELECT
        base.*,
        12.0 / NULLIF(period_months, 0)                                 AS annualize_factor,
        total_debt - cash_and_investments                               AS net_debt,
        -- a missing goodwill / intangible line only means "none" on a complete balance sheet
        CASE WHEN total_assets IS NOT NULL
             THEN shareholders_equity - COALESCE(goodwill, 0) - COALESCE(intangible_assets, 0)
        END                                                             AS tangible_book_value,
        current_assets - total_liabilities                              AS ncav,
        current_assets - current_liabilities                            AS working_capital,
        free_cash_flow + COALESCE(lease_payments, 0)                    AS fcf_after_leases
    FROM base
)
SELECT
    company_id, ticker, exchange, currency,
    period_id, fiscal_year, fiscal_quarter, period_type, period_start, period_end,
    period_days, period_months, audited,

    -- headline figures
    revenue, gross_profit, ebit, ebitda, net_income, net_income_to_parent,
    operating_cash_flow, capital_expenditure, free_cash_flow, fcf_after_leases,
    cash_and_investments, current_assets, total_assets,
    current_liabilities, total_liabilities, total_debt, net_debt,
    shareholders_equity, total_equity, tangible_book_value, ncav, working_capital,
    shares,

    -- 1. balance sheet strength
    ROUND(current_assets / NULLIF(current_liabilities, 0), 4)                       AS current_ratio,
    ROUND((current_assets - inventory) / NULLIF(current_liabilities, 0), 4)         AS quick_ratio,
    ROUND(cash_and_investments / NULLIF(current_liabilities, 0), 4)                 AS cash_ratio,
    ROUND(total_debt / NULLIF(shareholders_equity, 0), 4)                           AS debt_to_equity,
    ROUND(net_debt / NULLIF(shareholders_equity, 0), 4)                             AS net_debt_to_equity,
    ROUND(total_liabilities / NULLIF(total_equity, 0), 4)                           AS liabilities_to_equity,
    ROUND(total_equity / NULLIF(total_assets, 0), 4)                                AS equity_to_assets,
    ROUND(net_debt / NULLIF(ebitda * annualize_factor, 0), 4)                       AS net_debt_to_ebitda_annualized,

    -- per share
    ROUND(shareholders_equity / NULLIF(shares, 0), 4)                               AS book_value_per_share,
    ROUND(tangible_book_value / NULLIF(shares, 0), 4)                               AS tangible_book_per_share,
    ROUND(cash_and_investments / NULLIF(shares, 0), 4)                              AS cash_per_share,
    ROUND(-net_debt / NULLIF(shares, 0), 4)                                         AS net_cash_per_share,
    ROUND(ncav / NULLIF(shares, 0), 4)                                              AS ncav_per_share,
    ROUND(free_cash_flow / NULLIF(shares, 0), 4)                                    AS fcf_per_share,
    COALESCE(diluted_eps, basic_eps)                                                AS eps,

    -- 2. cash generation
    ROUND(free_cash_flow / NULLIF(revenue, 0), 6)                                   AS fcf_margin,
    ROUND(operating_cash_flow / NULLIF(net_income, 0), 4)                           AS ocf_to_net_income,
    ROUND(-capital_expenditure / NULLIF(revenue, 0), 6)                             AS capex_to_revenue,

    -- 3. profitability
    ROUND(gross_profit / NULLIF(revenue, 0), 6)                                     AS gross_margin,
    ROUND(operating_income / NULLIF(revenue, 0), 6)                                 AS operating_margin,
    ROUND(ebitda / NULLIF(revenue, 0), 6)                                           AS ebitda_margin,
    ROUND(net_income / NULLIF(revenue, 0), 6)                                       AS net_margin,
    ROUND(income_tax / NULLIF(pretax_income, 0), 6)                                 AS effective_tax_rate,
    ROUND(ebit / NULLIF(interest_expense, 0), 4)                                    AS interest_coverage,
    ROUND(net_income_to_parent * annualize_factor / NULLIF(shareholders_equity, 0), 6) AS roe_annualized,
    ROUND(net_income * annualize_factor / NULLIF(total_assets, 0), 6)               AS roa_annualized,
    ROUND(ebit * (1 - income_tax / NULLIF(pretax_income, 0)) * annualize_factor
          / NULLIF(total_equity + total_debt - cash_and_investments, 0), 6)         AS roic_annualized,

    -- 4. efficiency (days, based on period-end balances)
    ROUND(inventory / NULLIF(cost_of_revenue, 0) * period_days, 2)                  AS inventory_days,
    ROUND(accounts_receivable / NULLIF(revenue, 0) * period_days, 2)                AS receivable_days,
    ROUND(accounts_payable / NULLIF(cost_of_revenue, 0) * period_days, 2)           AS payable_days,
    ROUND((inventory / NULLIF(cost_of_revenue, 0)
         + accounts_receivable / NULLIF(revenue, 0)
         - accounts_payable / NULLIF(cost_of_revenue, 0)) * period_days, 2)         AS cash_conversion_cycle_days,

    -- annualised flows (for valuation of partial-year periods)
    ROUND(revenue * annualize_factor, 4)                                            AS revenue_annualized,
    ROUND(ebit * annualize_factor, 4)                                               AS ebit_annualized,
    ROUND(ebitda * annualize_factor, 4)                                             AS ebitda_annualized,
    ROUND(net_income_to_parent * annualize_factor, 4)                               AS net_income_to_parent_annualized,
    ROUND(free_cash_flow * annualize_factor, 4)                                     AS free_cash_flow_annualized,
    ROUND(COALESCE(diluted_eps, basic_eps) * annualize_factor, 8)                   AS eps_annualized,

    non_controlling_interest
FROM calc;

COMMENT ON VIEW v_key_metrics IS
    'Per-period fundamentals and ratios. Ratios are fractions (0.25 = 25%). *_annualized columns scale partial-year flows by 12 / period_months.';

-- ---------------------------------------------------------------------
-- v_valuation : each period valued at the last price on or
-- before its period_end (historical multiples)
-- ---------------------------------------------------------------------
CREATE VIEW v_valuation AS
WITH priced AS (
    SELECT
        km.*,
        px.price_date,
        px.close_price,
        px.close_price * COALESCE(px.shares_outstanding, km.shares) AS market_cap
    FROM v_key_metrics km
    JOIN LATERAL (
        SELECT sp.price_date, sp.close_price, sp.shares_outstanding
        FROM stock_price sp
        WHERE sp.company_id = km.company_id
          AND sp.price_date <= km.period_end
        ORDER BY sp.price_date DESC
        LIMIT 1
    ) px ON TRUE
),
ev AS (
    SELECT
        priced.*,
        market_cap + net_debt + COALESCE(non_controlling_interest, 0) AS enterprise_value
    FROM priced
)
SELECT
    company_id, ticker, period_id, fiscal_year, fiscal_quarter, period_type, period_end,
    price_date, close_price, shares, market_cap, net_debt, enterprise_value,
    ROUND(market_cap / NULLIF(net_income_to_parent_annualized, 0), 4) AS pe_annualized,
    ROUND(market_cap / NULLIF(shareholders_equity, 0), 4)             AS price_to_book,
    ROUND(market_cap / NULLIF(tangible_book_value, 0), 4)             AS price_to_tangible_book,
    ROUND(market_cap / NULLIF(revenue_annualized, 0), 6)              AS price_to_sales_annualized,
    ROUND(enterprise_value / NULLIF(revenue_annualized, 0), 6)        AS ev_to_sales_annualized,
    ROUND(enterprise_value / NULLIF(ebit_annualized, 0), 4)           AS ev_to_ebit_annualized,
    ROUND(enterprise_value / NULLIF(ebitda_annualized, 0), 4)         AS ev_to_ebitda_annualized,
    ROUND(ebit_annualized / NULLIF(enterprise_value, 0), 6)           AS earnings_yield_annualized,
    ROUND(free_cash_flow_annualized / NULLIF(market_cap, 0), 6)       AS fcf_yield_annualized,
    ROUND(-net_debt / NULLIF(market_cap, 0), 6)                       AS net_cash_to_market_cap,
    ROUND(ncav / NULLIF(market_cap, 0), 6)                            AS ncav_to_market_cap
FROM ev;

COMMENT ON VIEW v_valuation IS
    'Valuation multiples per period using the last stock_price on or before period_end.';

-- ---------------------------------------------------------------------
-- v_latest_valuation : latest price vs latest full report
-- (latest period that has both an income statement and a balance sheet)
-- ---------------------------------------------------------------------
CREATE VIEW v_latest_valuation AS
WITH latest_period AS (
    SELECT DISTINCT ON (km.company_id) km.*
    FROM v_key_metrics km
    WHERE km.revenue IS NOT NULL
      AND km.total_assets IS NOT NULL
    ORDER BY km.company_id, km.period_end DESC,
             CASE km.period_type WHEN 'TTM' THEN 0 WHEN 'FY' THEN 1 ELSE 2 END
),
priced AS (
    SELECT
        lp.*,
        px.price_date,
        px.close_price,
        px.close_price * COALESCE(px.shares_outstanding, lp.shares) AS market_cap
    FROM latest_period lp
    JOIN LATERAL (
        SELECT sp.price_date, sp.close_price, sp.shares_outstanding
        FROM stock_price sp
        WHERE sp.company_id = lp.company_id
        ORDER BY sp.price_date DESC
        LIMIT 1
    ) px ON TRUE
),
ev AS (
    SELECT
        priced.*,
        market_cap + net_debt + COALESCE(non_controlling_interest, 0) AS enterprise_value
    FROM priced
)
SELECT
    company_id, ticker, period_id, fiscal_year, fiscal_quarter, period_type, period_end,
    price_date, close_price, shares, market_cap, net_debt, enterprise_value,
    ROUND(market_cap / NULLIF(net_income_to_parent_annualized, 0), 4) AS pe_annualized,
    ROUND(market_cap / NULLIF(shareholders_equity, 0), 4)             AS price_to_book,
    ROUND(market_cap / NULLIF(tangible_book_value, 0), 4)             AS price_to_tangible_book,
    ROUND(market_cap / NULLIF(revenue_annualized, 0), 6)              AS price_to_sales_annualized,
    ROUND(enterprise_value / NULLIF(revenue_annualized, 0), 6)        AS ev_to_sales_annualized,
    ROUND(enterprise_value / NULLIF(ebit_annualized, 0), 4)           AS ev_to_ebit_annualized,
    ROUND(enterprise_value / NULLIF(ebitda_annualized, 0), 4)         AS ev_to_ebitda_annualized,
    ROUND(ebit_annualized / NULLIF(enterprise_value, 0), 6)           AS earnings_yield_annualized,
    ROUND(free_cash_flow_annualized / NULLIF(market_cap, 0), 6)       AS fcf_yield_annualized,
    ROUND(-net_debt / NULLIF(market_cap, 0), 6)                       AS net_cash_to_market_cap,
    ROUND(ncav / NULLIF(market_cap, 0), 6)                            AS ncav_to_market_cap
FROM ev;

COMMENT ON VIEW v_latest_valuation IS
    'Current valuation: most recent stock_price against the most recent period with full statements.';
