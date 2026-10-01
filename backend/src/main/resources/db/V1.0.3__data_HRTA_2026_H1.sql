-- =====================================================================
-- PT Hartadinata Abadi Tbk (IDX: HRTA)
-- Source : data/FinancialStatement-2026-II-HRTA.xlsx (IDX XBRL, Kuartal II 2026)
--          Consolidated, unaudited, full Rupiah amounts (Satuan Penuh).
--
-- Periods in this filing
--   2026 H1 : 2026-01-01 .. 2026-06-30  income, balance, cash flow, revenue split
--   2025 H1 : 2025-01-01 .. 2025-06-30  income, cash flow, revenue split (comparative),
--                                       equity only on the balance sheet (sheet 1410000PY)
--   2025 FY : 2025-12-31                balance sheet only (comparative)
--
-- Sheet references: 1210000 balance sheet, 1311000 profit or loss,
--   1410000 changes in equity, 1510000 cash flow (direct method),
--   1611000 / 1612000 PP&E and right-of-use roll-forward, 1617000 revenue by type.
--
-- Derived values (written as explicit sums so they can be audited):
--   * shares outstanding 4,605,262,400 = common stock 460,526,240,000 / par Rp100.
--     Cross-check: 700,013,601,427 / 4,605,262,400 = 152.00 (reported basic EPS 152)
--                  348,509,525,446 / 4,605,262,400 = 75.68  (reported basic EPS 75.68)
--                  184,210,496,000 / 4,605,262,400 = Rp40 dividend per share exactly.
--     Common stock is unchanged across all periods, so weighted = period-end shares.
--   * EBIT = gross profit - selling - G&A - other expenses
--          = pretax income + interest & finance costs - finance income.
--   * amortization 2026 H1 = intangibles opening 1,637,953,751 + purchases 466,292,272
--                            - closing 1,874,152,973 (assumes no disposals/impairment).
--   * 2025 H1 depreciation is not disclosed (PY roll-forward sheets are full year 2025).
--   * Cash flow items without a line in the statement (buybacks, issuance, dividends,
--     acquisitions) are 0: the reported section totals reconcile without them.
--     The dividends declared (Rp184.2bn in 2026, Rp96.7bn in 2025) were still
--     unpaid at 30 June (dividends payable), hence dividends_paid = 0.
--
-- Idempotent: upserts on the natural keys, safe to run on every start.
-- =====================================================================

-- ---------------------------------------------------------------------
-- company
-- ---------------------------------------------------------------------
INSERT INTO company (
    ticker, exchange, cik, company_name, legal_name, industry, sector,
    country, currency, fiscal_year_end, ipo_date, active)
VALUES (
    'HRTA', 'IDX', NULL, 'Hartadinata Abadi', 'PT Hartadinata Abadi Tbk',
    'Apparel & Luxury Goods', 'Consumer Cyclicals',
    'Indonesia', 'IDR', DATE '2025-12-31', NULL, TRUE)
ON CONFLICT ON CONSTRAINT uq_company_ticker_exchange DO UPDATE SET
    company_name    = EXCLUDED.company_name,
    legal_name      = EXCLUDED.legal_name,
    industry        = EXCLUDED.industry,
    sector          = EXCLUDED.sector,
    country         = EXCLUDED.country,
    currency        = EXCLUDED.currency,
    fiscal_year_end = GREATEST(company.fiscal_year_end, EXCLUDED.fiscal_year_end),
    updated_at      = now()
WHERE (company.company_name, company.legal_name, company.industry,
       company.sector, company.country, company.currency)
      IS DISTINCT FROM
      (EXCLUDED.company_name, EXCLUDED.legal_name, EXCLUDED.industry,
       EXCLUDED.sector, EXCLUDED.country, EXCLUDED.currency)
   OR company.fiscal_year_end IS DISTINCT FROM
      GREATEST(company.fiscal_year_end, EXCLUDED.fiscal_year_end);

-- ---------------------------------------------------------------------
-- reporting periods
-- ---------------------------------------------------------------------
INSERT INTO reporting_period (
    company_id, fiscal_year, fiscal_quarter, period_type,
    period_start, period_end, filing_date, source_filing, audited)
SELECT c.company_id, v.fiscal_year, v.fiscal_quarter, v.period_type,
       v.period_start, v.period_end, NULL, v.source_filing, v.audited
FROM company c
CROSS JOIN (VALUES
    (2026, 2::SMALLINT,    'H1', DATE '2026-01-01', DATE '2026-06-30', 'FinancialStatement-2026-II-HRTA.xlsx', FALSE),
    (2025, 2::SMALLINT,    'H1', DATE '2025-01-01', DATE '2025-06-30', 'FinancialStatement-2026-II-HRTA.xlsx', FALSE),
    (2025, NULL::SMALLINT, 'FY', DATE '2025-01-01', DATE '2025-12-31', 'FinancialStatement-2026-II-HRTA.xlsx', NULL::BOOLEAN)
) AS v (fiscal_year, fiscal_quarter, period_type, period_start, period_end, source_filing, audited)
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_reporting_period DO UPDATE SET
    period_start  = EXCLUDED.period_start,
    period_end    = EXCLUDED.period_end,
    -- keep an already-set source/audit flag (e.g. FY 2025 loaded from the audited annual report)
    source_filing = COALESCE(reporting_period.source_filing, EXCLUDED.source_filing),
    audited       = COALESCE(reporting_period.audited, EXCLUDED.audited);

-- ---------------------------------------------------------------------
-- income statement  (expenses positive)
-- ---------------------------------------------------------------------
INSERT INTO income_statement (
    company_id, period_id,
    revenue, cost_of_revenue, gross_profit,
    operating_expenses, sga_expense, rd_expense,
    depreciation, amortization,
    operating_income, ebit, ebitda,
    interest_income, interest_expense,
    pretax_income, income_tax,
    net_income, net_income_to_parent,
    basic_eps, diluted_eps,
    basic_shares, diluted_shares)
SELECT rp.company_id, rp.period_id,
       v.revenue, v.cost_of_revenue, v.gross_profit,
       v.operating_expenses, v.sga_expense, v.rd_expense,
       v.depreciation, v.amortization,
       v.operating_income, v.ebit, v.ebitda,
       v.interest_income, v.interest_expense,
       v.pretax_income, v.income_tax,
       v.net_income, v.net_income_to_parent,
       v.basic_eps, v.diluted_eps,
       v.basic_shares, v.diluted_shares
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    -- ---------------- 2026 H1 (sheet 1311000, CurrentYearDuration) ----------------
    (2026, 'H1',
     33806129682661::NUMERIC,                              -- revenue: Sales and revenue
     32557665361296::NUMERIC,                              -- cost_of_revenue
     1248464321365::NUMERIC,                               -- gross_profit
     (54867505125 + 137638680923 + 68024657)::NUMERIC,     -- operating_expenses: selling + G&A + other expenses
     (54867505125 + 137638680923)::NUMERIC,                -- sga_expense: selling + G&A
     NULL::NUMERIC,                                        -- rd_expense: not reported
     (22198242941 + 9549547988)::NUMERIC,                  -- depreciation: PP&E (1611000) + right-of-use (1612000)
     (1637953751 + 466292272 - 1874152973)::NUMERIC,       -- amortization: derived intangible roll-forward
     (1248464321365 - 54867505125 - 137638680923 - 68024657)::NUMERIC,  -- operating_income
     (1248464321365 - 54867505125 - 137638680923 - 68024657)::NUMERIC,  -- ebit
     (1248464321365 - 54867505125 - 137638680923 - 68024657
       + 22198242941 + 9549547988
       + 1637953751 + 466292272 - 1874152973)::NUMERIC,    -- ebitda: ebit + depreciation + amortization
     5424883874::NUMERIC,                                  -- interest_income: Finance income
     165599080739::NUMERIC,                                -- interest_expense: Interest and finance costs
     895715913795::NUMERIC,                                -- pretax_income
     194834304532::NUMERIC,                                -- income_tax: tax expense (reported -194,834,304,532)
     700881609263::NUMERIC,                                -- net_income: Total profit
     700013601427::NUMERIC,                                -- net_income_to_parent
     152::NUMERIC,                                         -- basic_eps
     NULL::NUMERIC,                                        -- diluted_eps: not reported
     4605262400::NUMERIC,                                  -- basic_shares
     NULL::NUMERIC),                                       -- diluted_shares: not reported
    -- ---------------- 2025 H1 (sheet 1311000, PriorYearDuration) ----------------
    (2025, 'H1',
     15051320171432,                                       -- revenue
     14304480574370,                                       -- cost_of_revenue
     746839597062,                                         -- gross_profit
     (11343780638 + 112589127234 + 722971245),             -- operating_expenses: selling + G&A + other expenses
     (11343780638 + 112589127234),                         -- sga_expense
     NULL,                                                 -- rd_expense: not reported
     NULL,                                                 -- depreciation: not disclosed for H1 2025
     NULL,                                                 -- amortization: not disclosed for H1 2025
     (746839597062 - 11343780638 - 112589127234 - 722971245),  -- operating_income
     (746839597062 - 11343780638 - 112589127234 - 722971245),  -- ebit
     NULL,                                                 -- ebitda: D&A not disclosed
     1556192348,                                           -- interest_income
     174620352980,                                         -- interest_expense
     449119557313,                                         -- pretax_income
     100167307146,                                         -- income_tax (reported -100,167,307,146)
     348952250167,                                         -- net_income
     348509525446,                                         -- net_income_to_parent
     75.68,                                                -- basic_eps
     NULL,                                                 -- diluted_eps
     4605262400,                                           -- basic_shares
     NULL)                                                 -- diluted_shares
) AS v (fiscal_year, period_type,
        revenue, cost_of_revenue, gross_profit,
        operating_expenses, sga_expense, rd_expense,
        depreciation, amortization,
        operating_income, ebit, ebitda,
        interest_income, interest_expense,
        pretax_income, income_tax,
        net_income, net_income_to_parent,
        basic_eps, diluted_eps,
        basic_shares, diluted_shares)
  ON rp.fiscal_year = v.fiscal_year AND rp.period_type = v.period_type AND rp.fiscal_quarter = 2
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_income_statement DO UPDATE SET
    revenue              = EXCLUDED.revenue,
    cost_of_revenue      = EXCLUDED.cost_of_revenue,
    gross_profit         = EXCLUDED.gross_profit,
    operating_expenses   = EXCLUDED.operating_expenses,
    sga_expense          = EXCLUDED.sga_expense,
    rd_expense           = EXCLUDED.rd_expense,
    depreciation         = EXCLUDED.depreciation,
    amortization         = EXCLUDED.amortization,
    operating_income     = EXCLUDED.operating_income,
    ebit                 = EXCLUDED.ebit,
    ebitda               = EXCLUDED.ebitda,
    interest_income      = EXCLUDED.interest_income,
    interest_expense     = EXCLUDED.interest_expense,
    pretax_income        = EXCLUDED.pretax_income,
    income_tax           = EXCLUDED.income_tax,
    net_income           = EXCLUDED.net_income,
    net_income_to_parent = EXCLUDED.net_income_to_parent,
    basic_eps            = EXCLUDED.basic_eps,
    diluted_eps          = EXCLUDED.diluted_eps,
    basic_shares         = EXCLUDED.basic_shares,
    diluted_shares       = EXCLUDED.diluted_shares;

-- ---------------------------------------------------------------------
-- balance sheet
-- ---------------------------------------------------------------------
INSERT INTO balance_sheet (
    company_id, period_id,
    cash_and_equivalents, marketable_securities,
    accounts_receivable, inventory,
    current_assets, total_assets,
    accounts_payable, deferred_revenue,
    current_liabilities, total_liabilities,
    short_term_debt, long_term_debt, lease_liabilities,
    shareholders_equity, non_controlling_interest, total_equity,
    retained_earnings, goodwill, intangible_assets,
    shares_outstanding)
SELECT rp.company_id, rp.period_id,
       v.cash_and_equivalents, v.marketable_securities,
       v.accounts_receivable, v.inventory,
       v.current_assets, v.total_assets,
       v.accounts_payable, v.deferred_revenue,
       v.current_liabilities, v.total_liabilities,
       v.short_term_debt, v.long_term_debt, v.lease_liabilities,
       v.shareholders_equity, v.non_controlling_interest, v.total_equity,
       v.retained_earnings, v.goodwill, v.intangible_assets,
       v.shares_outstanding
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    -- ---------------- 2026-06-30 (sheet 1210000, CurrentYearInstant) ----------------
    (2026, 'H1',
     983698277704::NUMERIC,                                -- cash_and_equivalents
     NULL::NUMERIC,                                        -- marketable_securities: none (other current financial assets 6,160,301,570 not included)
     586943106078::NUMERIC,                                -- accounts_receivable: trade receivables (pawn customer receivables 973,580,986,332 excluded)
     7819352373229::NUMERIC,                               -- inventory
     10617440147006::NUMERIC,                              -- current_assets
     11350876052356::NUMERIC,                              -- total_assets
     1355764782::NUMERIC,                                  -- accounts_payable: trade payables
     2975881199161::NUMERIC,                               -- deferred_revenue: advances from customers
     6284538006569::NUMERIC,                               -- current_liabilities
     7604210484596::NUMERIC,                               -- total_liabilities
     (2726547777781 + 147525000000 + 4765980071)::NUMERIC, -- short_term_debt: ST bank loans + current bank loans + current consumer financing
     (286612500000 + 13656040150 + 994064003060)::NUMERIC, -- long_term_debt: LT bank loans + LT consumer financing + bonds
     (8031537949 + 8078893933)::NUMERIC,                   -- lease_liabilities: current + long-term finance lease
     3735746835279::NUMERIC,                               -- shareholders_equity: attributable to parent
     10918732481::NUMERIC,                                 -- non_controlling_interest
     3746665567760::NUMERIC,                               -- total_equity
     (546860065188 + 2524642825005)::NUMERIC,              -- retained_earnings: appropriated + unappropriated
     340406202::NUMERIC,                                   -- goodwill
     1874152973::NUMERIC,                                  -- intangible_assets
     4605262400::NUMERIC),                                 -- shares_outstanding
    -- ---------------- 2025-12-31 (sheet 1210000, PriorEndYearInstant) ----------------
    (2025, 'FY',
     1529409541076,                                        -- cash_and_equivalents
     NULL,                                                 -- marketable_securities (other current financial assets 1,007,725,444 not included)
     712618818455,                                         -- accounts_receivable (customer receivables 758,295,146,089 excluded)
     8269317493555,                                        -- inventory
     11909616019425,                                       -- current_assets
     12602439207614,                                       -- total_assets
     2022988535,                                           -- accounts_payable
     5186474145692,                                        -- deferred_revenue
     8116912449130,                                        -- current_liabilities
     9371717785233,                                        -- total_liabilities
     (2791066666667 + 0 + 4706525523),                     -- short_term_debt: no current maturities of bank loans at 2025-12-31
     (225000000000 + 16039102424 + 993172922372),          -- long_term_debt
     (6503931989 + 4710776935),                            -- lease_liabilities
     3220656576575,                                        -- shareholders_equity
     10064845806,                                          -- non_controlling_interest
     3230721422381,                                        -- total_equity
     (350939353188 + 2205473278301),                       -- retained_earnings
     340406202,                                            -- goodwill
     1637953751,                                           -- intangible_assets
     4605262400),                                          -- shares_outstanding
    -- ---------------- 2025-06-30 (sheet 1410000PY, equity only) ----------------
    (2025, 'H1',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     NULL, NULL, NULL,
     2591800705252,                                        -- shareholders_equity
     10216467655,                                          -- non_controlling_interest
     2602017172907,                                        -- total_equity
     (350939353188 + 1576617406978),                       -- retained_earnings
     NULL, NULL,
     4605262400)                                           -- shares_outstanding
) AS v (fiscal_year, period_type,
        cash_and_equivalents, marketable_securities,
        accounts_receivable, inventory,
        current_assets, total_assets,
        accounts_payable, deferred_revenue,
        current_liabilities, total_liabilities,
        short_term_debt, long_term_debt, lease_liabilities,
        shareholders_equity, non_controlling_interest, total_equity,
        retained_earnings, goodwill, intangible_assets,
        shares_outstanding)
  ON rp.fiscal_year = v.fiscal_year AND rp.period_type = v.period_type
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_balance_sheet DO UPDATE SET
    cash_and_equivalents     = EXCLUDED.cash_and_equivalents,
    marketable_securities    = EXCLUDED.marketable_securities,
    accounts_receivable      = EXCLUDED.accounts_receivable,
    inventory                = EXCLUDED.inventory,
    current_assets           = EXCLUDED.current_assets,
    total_assets             = EXCLUDED.total_assets,
    accounts_payable         = EXCLUDED.accounts_payable,
    deferred_revenue         = EXCLUDED.deferred_revenue,
    current_liabilities      = EXCLUDED.current_liabilities,
    total_liabilities        = EXCLUDED.total_liabilities,
    short_term_debt          = EXCLUDED.short_term_debt,
    long_term_debt           = EXCLUDED.long_term_debt,
    lease_liabilities        = EXCLUDED.lease_liabilities,
    shareholders_equity      = EXCLUDED.shareholders_equity,
    non_controlling_interest = EXCLUDED.non_controlling_interest,
    total_equity             = EXCLUDED.total_equity,
    retained_earnings        = EXCLUDED.retained_earnings,
    goodwill                 = EXCLUDED.goodwill,
    intangible_assets        = EXCLUDED.intangible_assets,
    shares_outstanding       = EXCLUDED.shares_outstanding;

-- ---------------------------------------------------------------------
-- cash flow statement  (inflow +, outflow -)
-- ---------------------------------------------------------------------
INSERT INTO cash_flow_statement (
    company_id, period_id,
    operating_cash_flow, capital_expenditure,
    investing_cash_flow, financing_cash_flow,
    acquisitions, share_buybacks, stock_issuance, dividends_paid,
    debt_issued, debt_repaid, lease_payments,
    cash_change, ending_cash)
SELECT rp.company_id, rp.period_id,
       v.operating_cash_flow, v.capital_expenditure,
       v.investing_cash_flow, v.financing_cash_flow,
       v.acquisitions, v.share_buybacks, v.stock_issuance, v.dividends_paid,
       v.debt_issued, v.debt_repaid, v.lease_payments,
       v.cash_change, v.ending_cash
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    -- ---------------- 2026 H1 (sheet 1510000, CurrentYearDuration) ----------------
    (2026, 'H1',
     -625814236569::NUMERIC,                               -- operating_cash_flow
     -(44484016562 + 3274238905 + 466292272)::NUMERIC,     -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -48204727919::NUMERIC,                                -- investing_cash_flow
     128307701116::NUMERIC,                                -- financing_cash_flow
     0::NUMERIC,                                           -- acquisitions
     0::NUMERIC,                                           -- share_buybacks
     0::NUMERIC,                                           -- stock_issuance
     0::NUMERIC,                                           -- dividends_paid (declared Rp184.2bn still payable at 30 Jun)
     4129850000000::NUMERIC,                               -- debt_issued: bank loans
     -(3990375000000 + 2338676924)::NUMERIC,               -- debt_repaid: bank loans + consumer financing
     -5067510849::NUMERIC,                                 -- lease_payments
     -545711263372::NUMERIC,                               -- cash_change
     983698277704::NUMERIC),                               -- ending_cash
    -- ---------------- 2025 H1 (sheet 1510000, prior-year column) ----------------
    (2025, 'H1',
     -178078150405,                                        -- operating_cash_flow
     -(93547744130 + 23259551151 + 410348290),             -- capital_expenditure
     -116272643571,                                        -- investing_cash_flow
     430826807898,                                         -- financing_cash_flow
     0,                                                    -- acquisitions
     0,                                                    -- share_buybacks
     0,                                                    -- stock_issuance
     0,                                                    -- dividends_paid (declared Rp96.7bn still payable at 30 Jun)
     (3289549000000 + 24475537800 + 100000000000),         -- debt_issued: bank loans + consumer financing + bonds
     -(2973941500000 + 1304275437),                        -- debt_repaid: bank loans + consumer financing
     -4285538311,                                          -- lease_payments
     136476013922,                                         -- cash_change
     350023553373)                                         -- ending_cash
) AS v (fiscal_year, period_type,
        operating_cash_flow, capital_expenditure,
        investing_cash_flow, financing_cash_flow,
        acquisitions, share_buybacks, stock_issuance, dividends_paid,
        debt_issued, debt_repaid, lease_payments,
        cash_change, ending_cash)
  ON rp.fiscal_year = v.fiscal_year AND rp.period_type = v.period_type AND rp.fiscal_quarter = 2
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_cash_flow_statement DO UPDATE SET
    operating_cash_flow = EXCLUDED.operating_cash_flow,
    capital_expenditure = EXCLUDED.capital_expenditure,
    investing_cash_flow = EXCLUDED.investing_cash_flow,
    financing_cash_flow = EXCLUDED.financing_cash_flow,
    acquisitions        = EXCLUDED.acquisitions,
    share_buybacks      = EXCLUDED.share_buybacks,
    stock_issuance      = EXCLUDED.stock_issuance,
    dividends_paid      = EXCLUDED.dividends_paid,
    debt_issued         = EXCLUDED.debt_issued,
    debt_repaid         = EXCLUDED.debt_repaid,
    lease_payments      = EXCLUDED.lease_payments,
    cash_change         = EXCLUDED.cash_change,
    ending_cash         = EXCLUDED.ending_cash;

-- ---------------------------------------------------------------------
-- revenue by type (sheet 1617000)
-- ---------------------------------------------------------------------
INSERT INTO revenue_segment (
    company_id, period_id, segment_type, segment_name, segment_name_en, revenue)
SELECT rp.company_id, rp.period_id, v.segment_type, v.segment_name, v.segment_name_en, v.revenue
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    (2026, 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 'Jewelry and precious metal sales - Wholesale', 30199040866058::NUMERIC),
    (2026, 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko',   'Jewelry and precious metal sales - Retail stores', 3484461583830::NUMERIC),
    (2026, 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 'Jewelry and precious metal sales - Export', 9099295193::NUMERIC),
    (2026, 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 'Pawn loan interest and administration fees', 111475496630::NUMERIC),
    (2026, 'SERVICE', 'Pendapatan jasa pemurnian emas', 'Gold refining services', 2052440950::NUMERIC),
    (2025, 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 'Jewelry and precious metal sales - Wholesale', 12086285344831::NUMERIC),
    (2025, 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko',   'Jewelry and precious metal sales - Retail stores', 2845855930199::NUMERIC),
    (2025, 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 'Jewelry and precious metal sales - Export', 53569698846::NUMERIC),
    (2025, 'SERVICE', 'Penjualan dengan rekanan', 'Sales with partners', 619131316::NUMERIC),
    (2025, 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 'Pawn loan interest and administration fees', 60884262770::NUMERIC),
    (2025, 'SERVICE', 'Pendapatan jasa pemurnian emas', 'Gold refining services', 4105803470::NUMERIC)
) AS v (fiscal_year, segment_type, segment_name, segment_name_en, revenue)
  ON rp.fiscal_year = v.fiscal_year AND rp.period_type = 'H1' AND rp.fiscal_quarter = 2
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_revenue_segment DO UPDATE SET
    segment_name_en = EXCLUDED.segment_name_en,
    revenue         = EXCLUDED.revenue;
