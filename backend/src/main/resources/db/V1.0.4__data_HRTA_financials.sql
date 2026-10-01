-- =====================================================================
-- PT Hartadinata Abadi Tbk (IDX: HRTA) - financial statements
-- Source : data/HRTA/xlsx/FinancialStatement-<period>-HRTA.xlsx (IDX XBRL filings)
--          Consolidated, full Rupiah amounts (Satuan Penuh).
--
-- Filings and the periods they contain (current period + prior-period comparative)
--   2025-I        Q1 2025 (unaudited)  + Q1 2024 ; balance sheet 2025-03-31 + 2024-12-31
--   2025-II       H1 2025 (unaudited)  + H1 2024 ; balance sheet 2025-06-30 + 2024-12-31
--   2025-III      9M 2025 (unaudited)  + 9M 2024 ; balance sheet 2025-09-30 + 2024-12-31
--   2025-Tahunan  FY 2025 (audited)    + FY 2024 ; balance sheet 2025-12-31 + 2024-12-31
--   2026-I        Q1 2026 (unaudited)  + Q1 2025 ; balance sheet 2026-03-31 + 2025-12-31
--   2026-II       H1 2026 (unaudited)  + H1 2025 ; balance sheet 2026-06-30 + 2025-12-31
-- Each period is loaded from the filing in which it is the current period; 2024 periods
-- come from the 2025 comparatives (FY 2024 from the audited annual report). Every
-- comparative was checked against the original filing: no restatements.
-- 2024 Q1 / H1 / 9M have no balance sheet (filings only compare to the prior year end).
--
-- Sheet references: 1210000 balance sheet, 1311000 profit or loss,
--   1510000 cash flow (direct method), 1611000 / 1612000 PP&E and right-of-use
--   roll-forward (the *PY sheets are the full prior year), 1617000 revenue by type.
--
-- Derived values (written as explicit sums so they can be audited):
--   * shares outstanding 4,605,262,400 = common stock 460,526,240,000 / par Rp100;
--     unchanged in every filing, no treasury shares. Reported basic EPS = profit to
--     parent / 4,605,262,400 in every period (checked), so weighted = period-end shares.
--   * operating_income = gross profit - selling - G&A + other income - other expenses
--                      = ebit = pretax income + interest & finance costs - finance income.
--   * depreciation = PP&E + right-of-use additions to accumulated depreciation.
--     Not disclosed for 2024 Q1 / H1 / 9M (their roll-forwards are not in the filings).
--   * amortization = intangibles opening + purchases - closing (no disposals/impairment).
--     Not derivable for FY 2024 (2023-12-31 balance not in the filings), so EBITDA is
--     NULL there; also NULL for 2024 Q1 / H1 / 9M.
--   * Cash flow lines without a counterpart in the statement (acquisitions, buybacks,
--     share issuance) are 0: every section total reconciles with the lines used.
--     Bond issuance costs, NCI capital contributions and "other financing" stay in
--     financing_cash_flow only. Dividends declared but unpaid at the period end
--     (dividends payable) are not in dividends_paid.
--
-- Checks done while generating this script: assets = liabilities + equity, current +
-- non-current = total, pretax / net income / NCI reconciliation, cash flow sections and
-- cash roll-forward, balance-sheet cash = cash-flow ending cash, revenue segments sum
-- to revenue, EPS = profit to parent / shares.
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
-- reporting periods (Q1 = 3 months, H1 = 6-month YTD, 9M = 9-month YTD)
-- ---------------------------------------------------------------------
INSERT INTO reporting_period (
    company_id, fiscal_year, fiscal_quarter, period_type,
    period_start, period_end, filing_date, source_filing, audited)
SELECT c.company_id, v.fiscal_year, v.fiscal_quarter, v.period_type,
       v.period_start, v.period_end, NULL, v.source_filing, v.audited
FROM company c
CROSS JOIN (VALUES
    (2024, 1::SMALLINT   , 'Q1', DATE '2024-01-01', DATE '2024-03-31', 'FinancialStatement-2025-I-HRTA.xlsx', FALSE),
    (2024, 2::SMALLINT   , 'H1', DATE '2024-01-01', DATE '2024-06-30', 'FinancialStatement-2025-II-HRTA.xlsx', FALSE),
    (2024, 3::SMALLINT   , '9M', DATE '2024-01-01', DATE '2024-09-30', 'FinancialStatement-2025-III-HRTA.xlsx', FALSE),
    (2024, NULL::SMALLINT, 'FY', DATE '2024-01-01', DATE '2024-12-31', 'FinancialStatement-2025-Tahunan-HRTA.xlsx', TRUE ),
    (2025, 1::SMALLINT   , 'Q1', DATE '2025-01-01', DATE '2025-03-31', 'FinancialStatement-2025-I-HRTA.xlsx', FALSE),
    (2025, 2::SMALLINT   , 'H1', DATE '2025-01-01', DATE '2025-06-30', 'FinancialStatement-2025-II-HRTA.xlsx', FALSE),
    (2025, 3::SMALLINT   , '9M', DATE '2025-01-01', DATE '2025-09-30', 'FinancialStatement-2025-III-HRTA.xlsx', FALSE),
    (2025, NULL::SMALLINT, 'FY', DATE '2025-01-01', DATE '2025-12-31', 'FinancialStatement-2025-Tahunan-HRTA.xlsx', TRUE ),
    (2026, 1::SMALLINT   , 'Q1', DATE '2026-01-01', DATE '2026-03-31', 'FinancialStatement-2026-I-HRTA.xlsx', FALSE),
    (2026, 2::SMALLINT   , 'H1', DATE '2026-01-01', DATE '2026-06-30', 'FinancialStatement-2026-II-HRTA.xlsx', FALSE)
) AS v (fiscal_year, fiscal_quarter, period_type, period_start, period_end, source_filing, audited)
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_reporting_period DO UPDATE SET
    period_start  = EXCLUDED.period_start,
    period_end    = EXCLUDED.period_end,
    source_filing = EXCLUDED.source_filing,
    audited       = EXCLUDED.audited;

-- ---------------------------------------------------------------------
-- income statement  (expenses positive)
-- ---------------------------------------------------------------------
INSERT INTO income_statement (
    company_id, period_id,
    revenue, cost_of_revenue, gross_profit, operating_expenses, sga_expense, rd_expense,
    depreciation, amortization, operating_income, ebit, ebitda, interest_income,
    interest_expense, pretax_income, income_tax, net_income, net_income_to_parent,
    basic_eps, diluted_eps, basic_shares, diluted_shares)
SELECT rp.company_id, rp.period_id,
       v.revenue, v.cost_of_revenue, v.gross_profit, v.operating_expenses, v.sga_expense,
       v.rd_expense, v.depreciation, v.amortization, v.operating_income, v.ebit, v.ebitda,
       v.interest_income, v.interest_expense, v.pretax_income, v.income_tax, v.net_income,
       v.net_income_to_parent, v.basic_eps, v.diluted_eps, v.basic_shares, v.diluted_shares
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    -- ---------------- 2024 Q1 : 2024-01-01 .. 2024-03-31  (FinancialStatement-2025-I-HRTA.xlsx, prior-period comparative) ----------------
    (2024, 'Q1',
     4017481710616::NUMERIC,                                  -- revenue: Sales and revenue
     3758872440985::NUMERIC,                                  -- cost_of_revenue: Cost of sales and revenue
     258609269631::NUMERIC,                                   -- gross_profit: Total gross profit
     (2927108923 + 53308821828)::NUMERIC,                     -- operating_expenses: selling + G&A
     (2927108923 + 53308821828)::NUMERIC,                     -- sga_expense: selling + G&A
     NULL::NUMERIC,                                           -- rd_expense: not reported
     NULL::NUMERIC,                                           -- depreciation: not disclosed (no roll-forward for this comparative)
     NULL::NUMERIC,                                           -- amortization: not derivable (opening balance not in filings)
     203122319670::NUMERIC,                                   -- operating_income: gross profit - selling - G&A + other income
     203122319670::NUMERIC,                                   -- ebit: pretax + interest & finance costs - finance income
     NULL::NUMERIC,                                           -- ebitda: D&A not available
     468906815::NUMERIC,                                      -- interest_income: Finance income
     70565095122::NUMERIC,                                    -- interest_expense: Interest and finance costs
     133026131363::NUMERIC,                                   -- pretax_income: Total profit before tax
     30295209691::NUMERIC,                                    -- income_tax: tax expense (reported -30,295,209,691)
     102730921672::NUMERIC,                                   -- net_income: Total profit
     102697004473::NUMERIC,                                   -- net_income_to_parent: attributable to parent entity
     22.3::NUMERIC,                                           -- basic_eps: reported
     NULL::NUMERIC,                                           -- diluted_eps: not reported
     4605262400::NUMERIC,                                     -- basic_shares: common stock / par (no treasury shares)
     NULL::NUMERIC),                                          -- diluted_shares: not reported
    -- ---------------- 2024 H1 : 2024-01-01 .. 2024-06-30  (FinancialStatement-2025-II-HRTA.xlsx, prior-period comparative) ----------------
    (2024, 'H1',
     8241384800675,                                           -- revenue: Sales and revenue
     7722460806438,                                           -- cost_of_revenue: Cost of sales and revenue
     518923994237,                                            -- gross_profit: Total gross profit
     (6057699754 + 104139640743 + 2336621406),                -- operating_expenses: selling + G&A + other expenses
     (6057699754 + 104139640743),                             -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     NULL,                                                    -- depreciation: not disclosed (no roll-forward for this comparative)
     NULL,                                                    -- amortization: not derivable (opening balance not in filings)
     406390032334,                                            -- operating_income: gross profit - selling - G&A - other expenses
     406390032334,                                            -- ebit: pretax + interest & finance costs - finance income
     NULL,                                                    -- ebitda: D&A not available
     988602285,                                               -- interest_income: Finance income
     142291855242,                                            -- interest_expense: Interest and finance costs
     265086779377,                                            -- pretax_income: Total profit before tax
     59240517507,                                             -- income_tax: tax expense (reported -59,240,517,507)
     205846261870,                                            -- net_income: Total profit
     205628276283,                                            -- net_income_to_parent: attributable to parent entity
     44.65,                                                   -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2024 9M : 2024-01-01 .. 2024-09-30  (FinancialStatement-2025-III-HRTA.xlsx, prior-period comparative) ----------------
    (2024, '9M',
     13290380945734,                                          -- revenue: Sales and revenue
     12491725840648,                                          -- cost_of_revenue: Cost of sales and revenue
     798655105086,                                            -- gross_profit: Total gross profit
     (9971170788 + 164443647376 + 11697669517),               -- operating_expenses: selling + G&A + other expenses
     (9971170788 + 164443647376),                             -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     NULL,                                                    -- depreciation: not disclosed (no roll-forward for this comparative)
     NULL,                                                    -- amortization: not derivable (opening balance not in filings)
     612542617405,                                            -- operating_income: gross profit - selling - G&A - other expenses
     612542617405,                                            -- ebit: pretax + interest & finance costs - finance income
     NULL,                                                    -- ebitda: D&A not available
     1455327865,                                              -- interest_income: Finance income
     224398312792,                                            -- interest_expense: Interest and finance costs
     389599632478,                                            -- pretax_income: Total profit before tax
     87430012150,                                             -- income_tax: tax expense (reported -87,430,012,150)
     302169620328,                                            -- net_income: Total profit
     301918426543,                                            -- net_income_to_parent: attributable to parent entity
     65.56,                                                   -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2024 FY : 2024-01-01 .. 2024-12-31  (FinancialStatement-2025-Tahunan-HRTA.xlsx, prior-period comparative) ----------------
    (2024, 'FY',
     18228628989765,                                          -- revenue: Sales and revenue
     17131863817700,                                          -- cost_of_revenue: Cost of sales and revenue
     1096765172065,                                           -- gross_profit: Total gross profit
     (9923225050 + 209767760971 + 2455885786),                -- operating_expenses: selling + G&A + other expenses
     (9923225050 + 209767760971),                             -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     (23604077887 + 12167919469),                             -- depreciation: PP&E (1611000) + right-of-use (1612000)
     NULL,                                                    -- amortization: not derivable (opening balance not in filings)
     874618300258,                                            -- operating_income: gross profit - selling - G&A - other expenses
     874618300258,                                            -- ebit: pretax + interest & finance costs - finance income
     NULL,                                                    -- ebitda: D&A not available
     2681680761,                                              -- interest_income: Finance income
     310216709454,                                            -- interest_expense: Interest and finance costs
     567083271565,                                            -- pretax_income: Total profit before tax
     124363244066,                                            -- income_tax: tax expense (reported -124,363,244,066)
     442720027499,                                            -- net_income: Total profit
     442180750218,                                            -- net_income_to_parent: attributable to parent entity
     96.02,                                                   -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2025 Q1 : 2025-01-01 .. 2025-03-31  (FinancialStatement-2025-I-HRTA.xlsx, current period) ----------------
    (2025, 'Q1',
     6788278060014,                                           -- revenue: Sales and revenue
     6445219154668,                                           -- cost_of_revenue: Cost of sales and revenue
     343058905346,                                            -- gross_profit: Total gross profit
     (8367744388 + 55867896528 + 54763632),                   -- operating_expenses: selling + G&A + other expenses
     (8367744388 + 55867896528),                              -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     (6913056759 + 3578823595),                               -- depreciation: PP&E (1611000) + right-of-use (1612000)
     133666196,                                               -- amortization: intangibles opening + purchases - closing
     278768500798,                                            -- operating_income: gross profit - selling - G&A - other expenses
     278768500798,                                            -- ebit: pretax + interest & finance costs - finance income
     (278768500798 + 10491880354 + 133666196),                -- ebitda: ebit + depreciation + amortization
     805961851,                                               -- interest_income: Finance income
     87068242375,                                             -- interest_expense: Interest and finance costs
     192506220274,                                            -- pretax_income: Total profit before tax
     42566869869,                                             -- income_tax: tax expense (reported -42,566,869,869)
     149939350405,                                            -- net_income: Total profit
     149749971103,                                            -- net_income_to_parent: attributable to parent entity
     32.52,                                                   -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2025 H1 : 2025-01-01 .. 2025-06-30  (FinancialStatement-2025-II-HRTA.xlsx, current period) ----------------
    (2025, 'H1',
     15051320171432,                                          -- revenue: Sales and revenue
     14304480574370,                                          -- cost_of_revenue: Cost of sales and revenue
     746839597062,                                            -- gross_profit: Total gross profit
     (11343780638 + 112589127234 + 722971245),                -- operating_expenses: selling + G&A + other expenses
     (11343780638 + 112589127234),                            -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     (14354616891 + 7843692974),                              -- depreciation: PP&E (1611000) + right-of-use (1612000)
     287074368,                                               -- amortization: intangibles opening + purchases - closing
     622183717945,                                            -- operating_income: gross profit - selling - G&A - other expenses
     622183717945,                                            -- ebit: pretax + interest & finance costs - finance income
     (622183717945 + 22198309865 + 287074368),                -- ebitda: ebit + depreciation + amortization
     1556192348,                                              -- interest_income: Finance income
     174620352980,                                            -- interest_expense: Interest and finance costs
     449119557313,                                            -- pretax_income: Total profit before tax
     100167307146,                                            -- income_tax: tax expense (reported -100,167,307,146)
     348952250167,                                            -- net_income: Total profit
     348509525446,                                            -- net_income_to_parent: attributable to parent entity
     75.68,                                                   -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2025 9M : 2025-01-01 .. 2025-09-30  (FinancialStatement-2025-III-HRTA.xlsx, current period) ----------------
    (2025, '9M',
     25193124000784,                                          -- revenue: Sales and revenue
     24004621805493,                                          -- cost_of_revenue: Cost of sales and revenue
     1188502195291,                                           -- gross_profit: Total gross profit
     (13687588973 + 174253235272),                            -- operating_expenses: selling + G&A
     (13687588973 + 174253235272),                            -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     (22243426675 + 12244900203),                             -- depreciation: PP&E (1611000) + right-of-use (1612000)
     448559624,                                               -- amortization: intangibles opening + purchases - closing
     1001423453425,                                           -- operating_income: gross profit - selling - G&A + other income
     1001423453425,                                           -- ebit: pretax + interest & finance costs - finance income
     (1001423453425 + 34488326878 + 448559624),               -- ebitda: ebit + depreciation + amortization
     2908132463,                                              -- interest_income: Finance income
     263729916316,                                            -- interest_expense: Interest and finance costs
     740601669572,                                            -- pretax_income: Total profit before tax
     164090505701,                                            -- income_tax: tax expense (reported -164,090,505,701)
     576511163871,                                            -- net_income: Total profit
     575756407958,                                            -- net_income_to_parent: attributable to parent entity
     125.02,                                                  -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2025 FY : 2025-01-01 .. 2025-12-31  (FinancialStatement-2025-Tahunan-HRTA.xlsx, current period) ----------------
    (2025, 'FY',
     44548424151152,                                          -- revenue: Sales and revenue
     42626749035722,                                          -- cost_of_revenue: Cost of sales and revenue
     1921675115430,                                           -- gross_profit: Total gross profit
     (82025885408 + 242272212153),                            -- operating_expenses: selling + G&A
     (82025885408 + 242272212153),                            -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     (31256964523 + 16886759310),                             -- depreciation: PP&E (1611000) + right-of-use (1612000)
     588617629,                                               -- amortization: intangibles opening + purchases - closing
     1598361745717,                                           -- operating_income: gross profit - selling - G&A + other income
     1598361745717,                                           -- ebit: pretax + interest & finance costs - finance income
     (1598361745717 + 48143723833 + 588617629),               -- ebitda: ebit + depreciation + amortization
     5801415718,                                              -- interest_income: Finance income
     344173757866,                                            -- interest_expense: Interest and finance costs
     1259989403569,                                           -- pretax_income: Total profit before tax
     280385844641,                                            -- income_tax: tax expense (reported -280,385,844,641)
     979603558928,                                            -- net_income: Total profit
     978493183523,                                            -- net_income_to_parent: attributable to parent entity
     212.47,                                                  -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2026 Q1 : 2026-01-01 .. 2026-03-31  (FinancialStatement-2026-I-HRTA.xlsx, current period) ----------------
    (2026, 'Q1',
     20158489321037,                                          -- revenue: Sales and revenue
     19414235916829,                                          -- cost_of_revenue: Cost of sales and revenue
     744253404208,                                            -- gross_profit: Total gross profit
     (43134352702 + 67188209335 + 405793103),                 -- operating_expenses: selling + G&A + other expenses
     (43134352702 + 67188209335),                             -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     (10791183158 + 4547239014),                              -- depreciation: PP&E (1611000) + right-of-use (1612000)
     113834858,                                               -- amortization: intangibles opening + purchases - closing
     633525049068,                                            -- operating_income: gross profit - selling - G&A - other expenses
     633525049068,                                            -- ebit: pretax + interest & finance costs - finance income
     (633525049068 + 15338422172 + 113834858),                -- ebitda: ebit + depreciation + amortization
     3649238320,                                              -- interest_income: Finance income
     83265441220,                                             -- interest_expense: Interest and finance costs
     553908846168,                                            -- pretax_income: Total profit before tax
     119991110442,                                            -- income_tax: tax expense (reported -119,991,110,442)
     433917735726,                                            -- net_income: Total profit
     433493059715,                                            -- net_income_to_parent: attributable to parent entity
     94.13,                                                   -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL),                                                   -- diluted_shares: not reported
    -- ---------------- 2026 H1 : 2026-01-01 .. 2026-06-30  (FinancialStatement-2026-II-HRTA.xlsx, current period) ----------------
    (2026, 'H1',
     33806129682661,                                          -- revenue: Sales and revenue
     32557665361296,                                          -- cost_of_revenue: Cost of sales and revenue
     1248464321365,                                           -- gross_profit: Total gross profit
     (54867505125 + 137638680923 + 68024657),                 -- operating_expenses: selling + G&A + other expenses
     (54867505125 + 137638680923),                            -- sga_expense: selling + G&A
     NULL,                                                    -- rd_expense: not reported
     (22198242941 + 9549547988),                              -- depreciation: PP&E (1611000) + right-of-use (1612000)
     230093050,                                               -- amortization: intangibles opening + purchases - closing
     1055890110660,                                           -- operating_income: gross profit - selling - G&A - other expenses
     1055890110660,                                           -- ebit: pretax + interest & finance costs - finance income
     (1055890110660 + 31747790929 + 230093050),               -- ebitda: ebit + depreciation + amortization
     5424883874,                                              -- interest_income: Finance income
     165599080739,                                            -- interest_expense: Interest and finance costs
     895715913795,                                            -- pretax_income: Total profit before tax
     194834304532,                                            -- income_tax: tax expense (reported -194,834,304,532)
     700881609263,                                            -- net_income: Total profit
     700013601427,                                            -- net_income_to_parent: attributable to parent entity
     152,                                                     -- basic_eps: reported
     NULL,                                                    -- diluted_eps: not reported
     4605262400,                                              -- basic_shares: common stock / par (no treasury shares)
     NULL)                                                    -- diluted_shares: not reported
) AS v (fiscal_year, period_type,
        revenue, cost_of_revenue, gross_profit, operating_expenses, sga_expense, rd_expense,
        depreciation, amortization, operating_income, ebit, ebitda, interest_income,
        interest_expense, pretax_income, income_tax, net_income, net_income_to_parent,
        basic_eps, diluted_eps, basic_shares, diluted_shares)
  ON rp.fiscal_year = v.fiscal_year AND rp.period_type = v.period_type
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_income_statement DO UPDATE SET
    revenue                  = EXCLUDED.revenue,
    cost_of_revenue          = EXCLUDED.cost_of_revenue,
    gross_profit             = EXCLUDED.gross_profit,
    operating_expenses       = EXCLUDED.operating_expenses,
    sga_expense              = EXCLUDED.sga_expense,
    rd_expense               = EXCLUDED.rd_expense,
    depreciation             = EXCLUDED.depreciation,
    amortization             = EXCLUDED.amortization,
    operating_income         = EXCLUDED.operating_income,
    ebit                     = EXCLUDED.ebit,
    ebitda                   = EXCLUDED.ebitda,
    interest_income          = EXCLUDED.interest_income,
    interest_expense         = EXCLUDED.interest_expense,
    pretax_income            = EXCLUDED.pretax_income,
    income_tax               = EXCLUDED.income_tax,
    net_income               = EXCLUDED.net_income,
    net_income_to_parent     = EXCLUDED.net_income_to_parent,
    basic_eps                = EXCLUDED.basic_eps,
    diluted_eps              = EXCLUDED.diluted_eps,
    basic_shares             = EXCLUDED.basic_shares,
    diluted_shares           = EXCLUDED.diluted_shares;

-- ---------------------------------------------------------------------
-- balance sheet  (period-end positions)
-- ---------------------------------------------------------------------
INSERT INTO balance_sheet (
    company_id, period_id,
    cash_and_equivalents, marketable_securities, accounts_receivable, inventory,
    current_assets, total_assets, accounts_payable, deferred_revenue, current_liabilities,
    total_liabilities, short_term_debt, long_term_debt, lease_liabilities,
    shareholders_equity, non_controlling_interest, total_equity, retained_earnings,
    goodwill, intangible_assets, shares_outstanding)
SELECT rp.company_id, rp.period_id,
       v.cash_and_equivalents, v.marketable_securities, v.accounts_receivable, v.inventory,
       v.current_assets, v.total_assets, v.accounts_payable, v.deferred_revenue,
       v.current_liabilities, v.total_liabilities, v.short_term_debt, v.long_term_debt,
       v.lease_liabilities, v.shareholders_equity, v.non_controlling_interest,
       v.total_equity, v.retained_earnings, v.goodwill, v.intangible_assets,
       v.shares_outstanding
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    -- ---------------- 2024 FY : 2024-01-01 .. 2024-12-31  (FinancialStatement-2025-Tahunan-HRTA.xlsx, prior-period comparative) ----------------
    (2024, 'FY',
     213547539451::NUMERIC,
     NULL::NUMERIC,                                           -- marketable_securities: none (other current financial assets 1,328,709,955 not included)
     980949475774::NUMERIC,                                   -- accounts_receivable: trade receivables (pawn customer receivables 443,212,735,983 excluded)
     3858747485253::NUMERIC,
     5533919489214::NUMERIC,
     5959783480127::NUMERIC,
     2314421612::NUMERIC,                                     -- accounts_payable: trade payables
     68645728351::NUMERIC,                                    -- deferred_revenue: advances from customers
     2698776465671::NUMERIC,
     3610015391823::NUMERIC,
     2521894500000::NUMERIC,                                  -- short_term_debt: ST bank loans
     892686589423::NUMERIC,                                   -- long_term_debt: bonds
     (6839070052 + 2916185819)::NUMERIC,                      -- lease_liabilities: current + long-term finance lease
     2339995153393::NUMERIC,                                  -- shareholders_equity: attributable to parent
     9772934911::NUMERIC,
     2349768088304::NUMERIC,
     (262395347689 + 1413355860618)::NUMERIC,                 -- retained_earnings: appropriated + unappropriated
     340406202::NUMERIC,
     1344473090::NUMERIC,
     4605262400::NUMERIC),                                    -- shares_outstanding: issued shares, no treasury shares
    -- ---------------- 2025 Q1 : 2025-01-01 .. 2025-03-31  (FinancialStatement-2025-I-HRTA.xlsx, current period) ----------------
    (2025, 'Q1',
     342449195204,
     NULL,                                                    -- marketable_securities: none (other current financial assets 2,937,612,589 not included)
     822069129607,                                            -- accounts_receivable: trade receivables (pawn customer receivables 481,410,820,660 excluded)
     4176777253457,
     5924826384282,
     6393863696209,
     5096909602,                                              -- accounts_payable: trade payables
     24442481305,                                             -- deferred_revenue: advances from customers
     2743198116190,
     3894152585082,
     (2554398666667 + 3145663560),                            -- short_term_debt: ST bank loans + current consumer financing
     (223500000000 + 12172335142 + 893016799571),             -- long_term_debt: LT bank loans + LT consumer financing + bonds
     (7768803980 + 5795653896),                               -- lease_liabilities: current + long-term finance lease
     2489748392901,                                           -- shareholders_equity: attributable to parent
     9962718226,
     2499711111127,
     (262395347689 + 1563109100126),                          -- retained_earnings: appropriated + unappropriated
     340406202,
     1352521894,
     4605262400),                                             -- shares_outstanding: issued shares, no treasury shares
    -- ---------------- 2025 H1 : 2025-01-01 .. 2025-06-30  (FinancialStatement-2025-II-HRTA.xlsx, current period) ----------------
    (2025, 'H1',
     350023553373,
     NULL,                                                    -- marketable_securities: none (other current financial assets 2,848,634,259 not included)
     773514568241,                                            -- accounts_receivable: trade receivables (pawn customer receivables 556,157,012,047 excluded)
     4461292248034,
     6290296885477,
     6804780285147,
     1238231242,                                              -- accounts_payable: trade payables
     48879560086,                                             -- deferred_revenue: advances from customers
     2943140880510,
     4202763112240,
     (2619752000000 + 4650069081),                            -- short_term_debt: ST bank loans + current consumer financing
     (224000000000 + 18380547413 + 992314048994),             -- long_term_debt: LT bank loans + LT consumer financing + bonds
     (9860535934 + 7648625668),                               -- lease_liabilities: current + long-term finance lease
     2591800705252,                                           -- shareholders_equity: attributable to parent
     10216467655,
     2602017172907,
     (350939353188 + 1576617406978),                          -- retained_earnings: appropriated + unappropriated
     340406202,
     1467747012,
     4605262400),                                             -- shares_outstanding: issued shares, no treasury shares
    -- ---------------- 2025 9M : 2025-01-01 .. 2025-09-30  (FinancialStatement-2025-III-HRTA.xlsx, current period) ----------------
    (2025, '9M',
     1135953013575,
     NULL,                                                    -- marketable_securities: none (other current financial assets 2,839,171,263 not included)
     755795839790,                                            -- accounts_receivable: trade receivables (pawn customer receivables 619,450,331,649 excluded)
     4872408750438,
     7627983650710,
     8171528127926,
     1598186257,                                              -- accounts_payable: trade payables
     1157221613237,                                           -- deferred_revenue: advances from customers
     4083344325804,
     5341948368896,
     (2703681500000 + 4650069081),                            -- short_term_debt: ST bank loans + current consumer financing
     (224500000000 + 17247383663 + 992739533639),             -- long_term_debt: LT bank loans + LT consumer financing + bonds
     (7638497649 + 6004586762),                               -- lease_liabilities: current + long-term finance lease
     2819050856170,                                           -- shareholders_equity: attributable to parent
     10528902860,
     2829579759030,
     (350939353188 + 1803867557896),                          -- retained_earnings: appropriated + unappropriated
     340406202,
     1694761756,
     4605262400),                                             -- shares_outstanding: issued shares, no treasury shares
    -- ---------------- 2025 FY : 2025-01-01 .. 2025-12-31  (FinancialStatement-2025-Tahunan-HRTA.xlsx, current period) ----------------
    (2025, 'FY',
     1529409541076,
     NULL,                                                    -- marketable_securities: none (other current financial assets 1,007,725,444 not included)
     712618818455,                                            -- accounts_receivable: trade receivables (pawn customer receivables 758,295,146,089 excluded)
     8269317493555,
     11909616019425,
     12602439207614,
     2022988535,                                              -- accounts_payable: trade payables
     5186474145692,                                           -- deferred_revenue: advances from customers
     8116912449130,
     9371717785233,
     (2791066666667 + 4706525523),                            -- short_term_debt: ST bank loans + current consumer financing
     (225000000000 + 16039102424 + 993172922372),             -- long_term_debt: LT bank loans + LT consumer financing + bonds
     (6503931989 + 4710776935),                               -- lease_liabilities: current + long-term finance lease
     3220656576575,                                           -- shareholders_equity: attributable to parent
     10064845806,
     3230721422381,
     (350939353188 + 2205473278301),                          -- retained_earnings: appropriated + unappropriated
     340406202,
     1637953751,
     4605262400),                                             -- shares_outstanding: issued shares, no treasury shares
    -- ---------------- 2026 Q1 : 2026-01-01 .. 2026-03-31  (FinancialStatement-2026-I-HRTA.xlsx, current period) ----------------
    (2026, 'Q1',
     2726378484564,
     NULL,                                                    -- marketable_securities: none (other current financial assets 4,452,195,939 not included)
     577482626752,                                            -- accounts_receivable: trade receivables (pawn customer receivables 889,812,052,097 excluded)
     8668886867618,
     13061297902825,
     13764436636819,
     1439879785,                                              -- accounts_payable: trade payables
     5752467094417,                                           -- deferred_revenue: advances from customers
     8846316730080,
     10100160963152,
     (2860724444448 + 4735974979),                            -- short_term_debt: ST bank loans + current consumer financing
     (224062500000 + 14851427728 + 993614362247),             -- long_term_debt: LT bank loans + LT consumer financing + bonds
     (6663248389 + 4706643075),                               -- lease_liabilities: current + long-term finance lease
     3653793212441,                                           -- shareholders_equity: attributable to parent
     10482461226,
     3664275673667,
     (350939353188 + 2638609914167),                          -- retained_earnings: appropriated + unappropriated
     340406202,
     1524118893,
     4605262400),                                             -- shares_outstanding: issued shares, no treasury shares
    -- ---------------- 2026 H1 : 2026-01-01 .. 2026-06-30  (FinancialStatement-2026-II-HRTA.xlsx, current period) ----------------
    (2026, 'H1',
     983698277704,
     NULL,                                                    -- marketable_securities: none (other current financial assets 6,160,301,570 not included)
     586943106078,                                            -- accounts_receivable: trade receivables (pawn customer receivables 973,580,986,332 excluded)
     7819352373229,
     10617440147006,
     11350876052356,
     1355764782,                                              -- accounts_payable: trade payables
     2975881199161,                                           -- deferred_revenue: advances from customers
     6284538006569,
     7604210484596,
     (2726547777781 + 147525000000 + 4765980071),             -- short_term_debt: ST bank loans + current bank loans + current consumer financing
     (286612500000 + 13656040150 + 994064003060),             -- long_term_debt: LT bank loans + LT consumer financing + bonds
     (8031537949 + 8078893933),                               -- lease_liabilities: current + long-term finance lease
     3735746835279,                                           -- shareholders_equity: attributable to parent
     10918732481,
     3746665567760,
     (546860065188 + 2524642825005),                          -- retained_earnings: appropriated + unappropriated
     340406202,
     1874152973,
     4605262400)                                              -- shares_outstanding: issued shares, no treasury shares
) AS v (fiscal_year, period_type,
        cash_and_equivalents, marketable_securities, accounts_receivable, inventory,
        current_assets, total_assets, accounts_payable, deferred_revenue,
        current_liabilities, total_liabilities, short_term_debt, long_term_debt,
        lease_liabilities, shareholders_equity, non_controlling_interest, total_equity,
        retained_earnings, goodwill, intangible_assets, shares_outstanding)
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
    operating_cash_flow, capital_expenditure, investing_cash_flow, financing_cash_flow,
    acquisitions, share_buybacks, stock_issuance, dividends_paid, debt_issued, debt_repaid,
    lease_payments, cash_change, ending_cash)
SELECT rp.company_id, rp.period_id,
       v.operating_cash_flow, v.capital_expenditure, v.investing_cash_flow,
       v.financing_cash_flow, v.acquisitions, v.share_buybacks, v.stock_issuance,
       v.dividends_paid, v.debt_issued, v.debt_repaid, v.lease_payments, v.cash_change,
       v.ending_cash
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    -- ---------------- 2024 Q1 : 2024-01-01 .. 2024-03-31  (FinancialStatement-2025-I-HRTA.xlsx, prior-period comparative) ----------------
    (2024, 'Q1',
     28883106150::NUMERIC,
     (-28290259169 - 1841266494)::NUMERIC,                    -- capital_expenditure: PP&E + advances for PP&E
     -30131525663::NUMERIC,
     -51961224571::NUMERIC,
     0::NUMERIC,
     0::NUMERIC,
     0::NUMERIC,                                              -- stock_issuance: NCI capital contribution 100,000,000 kept in financing total only
     0::NUMERIC,                                              -- dividends_paid: none paid in the period
     428830000000::NUMERIC,                                   -- debt_issued: bank loans
     (-478361000000)::NUMERIC,                                -- debt_repaid: bank loans
     (-2530224571)::NUMERIC,                                  -- lease_payments: finance lease principal
     -53209644084::NUMERIC,
     239415749419::NUMERIC),
    -- ---------------- 2024 H1 : 2024-01-01 .. 2024-06-30  (FinancialStatement-2025-II-HRTA.xlsx, prior-period comparative) ----------------
    (2024, 'H1',
     148550478578,
     (-46284065817 - 5768310223 - 226520000),                 -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -52278896040,
     -102103100154,
     0,
     0,
     0,                                                       -- stock_issuance: NCI capital contribution 100,000,000 kept in financing total only
     (-69078936000),                                          -- dividends_paid: dividends paid
     2941284000000,                                           -- debt_issued: bank loans
     (-2970949014996),                                        -- debt_repaid: bank loans
     (-3459149158),                                           -- lease_payments: finance lease principal
     -5831517616,
     286793875887),
    -- ---------------- 2024 9M : 2024-01-01 .. 2024-09-30  (FinancialStatement-2025-III-HRTA.xlsx, prior-period comparative) ----------------
    (2024, '9M',
     -243457854213,
     (-61737038148 - 5440043902 - 383534000),                 -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -67560616050,
     221903891582,
     0,
     0,
     0,                                                       -- stock_issuance: NCI capital contribution 575,000,000 kept in financing total only
     (-69078936000),                                          -- dividends_paid: dividends paid
     5996775294823,                                           -- debt_issued: bank loans
     (-5700092050001),                                        -- debt_repaid: bank loans
     (-6275417240),                                           -- lease_payments: finance lease principal
     -89114578681,
     203510814822),
    -- ---------------- 2024 FY : 2024-01-01 .. 2024-12-31  (FinancialStatement-2025-Tahunan-HRTA.xlsx, prior-period comparative) ----------------
    (2024, 'FY',
     -423584251291,
     (-185157946123 - 6022759988 - 463534000),                -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -191089906777,
     535596304016,
     0,
     0,
     0,                                                       -- stock_issuance: NCI capital contribution 3,725,000,000 kept in financing total only
     (-69078936000),                                          -- dividends_paid: dividends paid
     (7916167267046 + 900000000000),                          -- debt_issued: bank loans + bonds
     (-7597357267046 - 600000000000),                         -- debt_repaid: bank loans + bonds
     (-10546349407),                                          -- lease_payments: finance lease principal
     -79077854052,
     213547539451),
    -- ---------------- 2025 Q1 : 2025-01-01 .. 2025-03-31  (FinancialStatement-2025-I-HRTA.xlsx, current period) ----------------
    (2025, 'Q1',
     -72589501413,
     (-43510685109 - 19745133162 - 141715000),                -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -63397533271,
     264888690437,
     0,
     0,
     0,
     0,                                                       -- dividends_paid: none paid in the period
     (1963909000000 + 15728317800),                           -- debt_issued: bank loans + consumer financing
     (-1709946500000 - 262138631),                            -- debt_repaid: bank loans + consumer financing
     (-1972630065),                                           -- lease_payments: finance lease principal
     128901655753,
     342449195204),
    -- ---------------- 2025 H1 : 2025-01-01 .. 2025-06-30  (FinancialStatement-2025-II-HRTA.xlsx, current period) ----------------
    (2025, 'H1',
     -178078150405,
     (-93547744130 - 23259551151 - 410348290),                -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -116272643571,
     430826807898,
     0,
     0,
     0,
     0,                                                       -- dividends_paid: none paid in the period
     (3289549000000 + 24475537800 + 100000000000),            -- debt_issued: bank loans + consumer financing + bonds
     (-2973941500000 - 1304275437),                           -- debt_repaid: bank loans + consumer financing
     (-4285538311),                                           -- lease_payments: finance lease principal
     136476013922,
     350023553373),
    -- ---------------- 2025 9M : 2025-01-01 .. 2025-09-30  (FinancialStatement-2025-III-HRTA.xlsx, current period) ----------------
    (2025, '9M',
     677579567858,
     (-133843236404 - 29608644426 - 798848290),               -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -163305729120,
     408131635386,
     0,
     0,
     0,
     (-96710510400),                                          -- dividends_paid: dividends paid
     (5143749000000 + 24475537800 + 100000000000),            -- debt_issued: bank loans + consumer financing + bonds
     (-4733583500000 - 2444973787),                           -- debt_repaid: bank loans + consumer financing
     (-8947502073),                                           -- lease_payments: finance lease principal
     922405474124,
     1135953013575),
    -- ---------------- 2025 FY : 2025-01-01 .. 2025-12-31  (FinancialStatement-2025-Tahunan-HRTA.xlsx, current period) ----------------
    (2025, 'FY',
     1142547120207,
     (-289001544584 - 20048367803 - 882098290),               -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -308987010677,
     482301892095,
     0,
     0,
     0,
     (-97506835400),                                          -- dividends_paid: dividends paid
     (6567999000000 + 15264336800 + 100000000000),            -- debt_issued: bank loans + consumer financing + bonds
     (-6088693500000 - 3342194552),                           -- debt_repaid: bank loans + consumer financing
     (-10319857266),                                          -- lease_payments: finance lease principal
     1315862001625,
     1529409541076),
    -- ---------------- 2026 Q1 : 2026-01-01 .. 2026-03-31  (FinancialStatement-2026-I-HRTA.xlsx, current period) ----------------
    (2026, 'Q1',
     1163200685553,
     (-18212911667 - 9115578263),                             -- capital_expenditure: PP&E + advances for PP&E
     -27308670110,
     61076928045,
     0,
     0,
     0,
     0,                                                       -- dividends_paid: none paid in the period
     1708100000000,                                           -- debt_issued: bank loans
     (-1640800000000 - 1165759839),                           -- debt_repaid: bank loans + consumer financing
     (-2421201005),                                           -- lease_payments: finance lease principal
     1196968943488,
     2726378484564),
    -- ---------------- 2026 H1 : 2026-01-01 .. 2026-06-30  (FinancialStatement-2026-II-HRTA.xlsx, current period) ----------------
    (2026, 'H1',
     -625814236569,
     (-44484016562 - 3274238905 - 466292272),                 -- capital_expenditure: PP&E + advances for PP&E + intangibles
     -48204727919,
     128307701116,
     0,
     0,
     0,
     0,                                                       -- dividends_paid: none paid in the period
     4129850000000,                                           -- debt_issued: bank loans
     (-3990375000000 - 2338676924),                           -- debt_repaid: bank loans + consumer financing
     (-5067510849),                                           -- lease_payments: finance lease principal
     -545711263372,
     983698277704)
) AS v (fiscal_year, period_type,
        operating_cash_flow, capital_expenditure, investing_cash_flow, financing_cash_flow,
        acquisitions, share_buybacks, stock_issuance, dividends_paid, debt_issued,
        debt_repaid, lease_payments, cash_change, ending_cash)
  ON rp.fiscal_year = v.fiscal_year AND rp.period_type = v.period_type
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_cash_flow_statement DO UPDATE SET
    operating_cash_flow      = EXCLUDED.operating_cash_flow,
    capital_expenditure      = EXCLUDED.capital_expenditure,
    investing_cash_flow      = EXCLUDED.investing_cash_flow,
    financing_cash_flow      = EXCLUDED.financing_cash_flow,
    acquisitions             = EXCLUDED.acquisitions,
    share_buybacks           = EXCLUDED.share_buybacks,
    stock_issuance           = EXCLUDED.stock_issuance,
    dividends_paid           = EXCLUDED.dividends_paid,
    debt_issued              = EXCLUDED.debt_issued,
    debt_repaid              = EXCLUDED.debt_repaid,
    lease_payments           = EXCLUDED.lease_payments,
    cash_change              = EXCLUDED.cash_change,
    ending_cash              = EXCLUDED.ending_cash;

-- ---------------------------------------------------------------------
-- segments: revenue by type (sheet 1617000). "Selisih penilaian wajar piutang usaha"
-- is filed as other service revenue (9M 2024) and other product revenue (FY 2024/2025),
-- so it is kept as segment_type OTHER.
-- ---------------------------------------------------------------------
INSERT INTO segment (company_id, segment_type, segment_name, segment_name_en)
SELECT c.company_id, v.segment_type, v.segment_name, v.segment_name_en
FROM company c
CROSS JOIN (VALUES
    ('OTHER', 'Selisih penilaian wajar piutang usaha', 'Fair value adjustment on trade receivables'),
    ('PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 'Jewelry and precious metal sales - Export'),
    ('PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 'Jewelry and precious metal sales - Wholesale'),
    ('PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 'Jewelry and precious metal sales - Retail stores'),
    ('SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 'Pawn loan interest and administration fees'),
    ('SERVICE', 'Pendapatan jasa pemurnian emas', 'Gold refining services'),
    ('SERVICE', 'Penjualan dengan rekanan', 'Sales with partners')
) AS v (segment_type, segment_name, segment_name_en)
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_segment DO UPDATE SET
    segment_name_en = EXCLUDED.segment_name_en;

INSERT INTO segment_financial (segment_id, company_id, period_id, revenue)
SELECT s.segment_id, rp.company_id, rp.period_id, v.revenue
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
JOIN (VALUES
    (2024, 'Q1', 'SERVICE', 'Penjualan dengan rekanan', 1046734845::NUMERIC),
    (2024, 'Q1', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 12995498731),
    (2024, 'Q1', 'SERVICE', 'Pendapatan jasa pemurnian emas', 1385074444),
    (2024, 'Q1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 3332842204567),
    (2024, 'Q1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 669212198029),
    (2024, 'H1', 'SERVICE', 'Penjualan dengan rekanan', 2052701416),
    (2024, 'H1', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 37344006036),
    (2024, 'H1', 'SERVICE', 'Pendapatan jasa pemurnian emas', 1895768272),
    (2024, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 5446788774171),
    (2024, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 1391364510561),
    (2024, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 1361939040219),
    (2024, '9M', 'SERVICE', 'Penjualan dengan rekanan', 2496928192),
    (2024, '9M', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 58220550162),
    (2024, '9M', 'SERVICE', 'Pendapatan jasa pemurnian emas', 3247831065),
    (2024, '9M', 'OTHER', 'Selisih penilaian wajar piutang usaha', 11892598883),
    (2024, '9M', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 8267759613418),
    (2024, '9M', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 3184905147270),
    (2024, '9M', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 1761858276744),
    (2024, 'FY', 'SERVICE', 'Penjualan dengan rekanan', 4986283773),
    (2024, 'FY', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 81570145466),
    (2024, 'FY', 'SERVICE', 'Pendapatan jasa pemurnian emas', 5897377853),
    (2024, 'FY', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 11347558830357),
    (2024, 'FY', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 4730318199008),
    (2024, 'FY', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 2046405554425),
    (2024, 'FY', 'OTHER', 'Selisih penilaian wajar piutang usaha', 11892598883),
    (2025, 'Q1', 'SERVICE', 'Penjualan dengan rekanan', 317050682),
    (2025, 'Q1', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 28519678046),
    (2025, 'Q1', 'SERVICE', 'Pendapatan jasa pemurnian emas', 2203212207),
    (2025, 'Q1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 5545484439203),
    (2025, 'Q1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 1165647641650),
    (2025, 'Q1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 46106038226),
    (2025, 'H1', 'SERVICE', 'Penjualan dengan rekanan', 619131316),
    (2025, 'H1', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 60884262770),
    (2025, 'H1', 'SERVICE', 'Pendapatan jasa pemurnian emas', 4105803470),
    (2025, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 12086285344831),
    (2025, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 2845855930199),
    (2025, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 53569698846),
    (2025, '9M', 'SERVICE', 'Penjualan dengan rekanan', 606944882),
    (2025, '9M', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 97660672273),
    (2025, '9M', 'SERVICE', 'Pendapatan jasa pemurnian emas', 8785081296),
    (2025, '9M', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 20812780843034),
    (2025, '9M', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 4165837271533),
    (2025, '9M', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 107453187766),
    (2025, 'FY', 'SERVICE', 'Penjualan dengan rekanan', 616043533),
    (2025, 'FY', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 140988689753),
    (2025, 'FY', 'SERVICE', 'Pendapatan jasa pemurnian emas', 8990153049),
    (2025, 'FY', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 39010218971384),
    (2025, 'FY', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 5204206686750),
    (2025, 'FY', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 167293311290),
    (2025, 'FY', 'OTHER', 'Selisih penilaian wajar piutang usaha', 16110295393),
    (2026, 'Q1', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 52786574110),
    (2026, 'Q1', 'SERVICE', 'Pendapatan jasa pemurnian emas', 1255735695),
    (2026, 'Q1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 18264388290382),
    (2026, 'Q1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 1840058720850),
    (2026, 'H1', 'SERVICE', 'Bunga pinjaman dan administrasi dari usaha gadai', 111475496630),
    (2026, 'H1', 'SERVICE', 'Pendapatan jasa pemurnian emas', 2052440950),
    (2026, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Grosir', 30199040866058),
    (2026, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Toko', 3484461583830),
    (2026, 'H1', 'PRODUCT', 'Penjualan perhiasan dan logam mulia - Ekspor', 9099295193)
) AS v (fiscal_year, period_type, segment_type, segment_name, revenue)
  ON rp.fiscal_year = v.fiscal_year AND rp.period_type = v.period_type
JOIN segment s ON s.company_id = rp.company_id
              AND s.segment_type = v.segment_type AND s.segment_name = v.segment_name
WHERE c.ticker = 'HRTA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_segment_financial DO UPDATE SET
    revenue = EXCLUDED.revenue;
