-- =====================================================================
-- Neraca Lab - VIVA's FY2022 balance sheet totals filed in another scale (PostgreSQL 15+)
--
-- PT Visi Media Asia Tbk's FY2022 workbook (FinancialStatement-2022-Tahunan-VIVA.xlsx, in thousands of rupiah) files
-- three cells of its current column (2022-12-31) a thousand times too small (ingestion error 1818):
--   1210000!R246 'Total liabilities'            10,457,577.261  (should be 10,457,577,261 thousand)
--   1210000!R270 'Non-controlling interests'       607,150.53    (should be    607,150,530 thousand)
--   1210000!R272 'Total liabilities and equity'  8,873,182.411  (should be  8,873,182,411 thousand)
-- The filing's own totals prove the figures: total assets 8,873,182,411 - total equity (-1,584,394,850) =
-- 10,457,577,261 liabilities; total equity -1,584,394,850 - equity attributable to the parent (-2,191,545,380) =
-- 607,150,530 non-controlling interests (thousands). The FY2023 filing's comparative column states exactly these
-- (1210000!R247 C 10,457,577,261; R271 C 607,150,530), but a comparative only fills empty fields, so the stored
-- total liabilities 10,457,577,261 and NCI 607,150,530 (rupiah) stayed: 2022's EV and leverage were wrong.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent; changes only the exact wrong values.
-- =====================================================================

UPDATE balance_sheet b
SET total_liabilities = CASE WHEN b.total_liabilities = 10457577261 THEN 10457577261000 ELSE b.total_liabilities END,
    non_controlling_interest = CASE WHEN b.non_controlling_interest = 607150530 THEN 607150530000
                                    ELSE b.non_controlling_interest END
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE b.period_id = rp.period_id
  AND c.ticker = 'VIVA'
  AND rp.period_type = 'FY' AND rp.fiscal_year = 2022
  AND b.total_assets = 8873182411000 AND b.total_equity = -1584394850000
  AND (b.total_liabilities = 10457577261 OR b.non_controlling_interest = 607150530);
