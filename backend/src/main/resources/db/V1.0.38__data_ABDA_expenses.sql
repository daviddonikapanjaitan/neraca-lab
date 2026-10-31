-- =====================================================================
-- Neraca Lab - ABDA (PT Asuransi Bina Dana Arta Tbk) general and administrative expenses of FY2021 and FY2022
-- (PostgreSQL 15+)
--
-- ABDA's FY2022 filing (FinancialStatement-2022-Tahunan-ABDA.xlsx, sheet 6312000) tags its general and
-- administrative expenses as 'Interest and finance costs' (Beban bunga dan keuangan) in both columns:
-- 328,420,609 thousand for FY2022, 284,745,743 for FY2021. Its FY2023 filing files the FY2022 amount,
-- 328,420,609, as 'General and administrative expenses', and ABDA has no borrowings. The full-text extraction
-- followed the tag: FY2021 was stored with no operating expenses and interest of 284,745,743, FY2022 with
-- interest of 328,420,609 beside operating expenses of the same amount (filled in by the FY2023 filing's
-- comparative), so operating income - profit before tax + interest, as for every insurer - came to
-- 459,445,724 and 434,411,704 thousand for profits before tax of 174,699,981 and 105,991,095.
--
--   * operating expenses: the G&A of 284,745,743 (FY2021) and 328,420,609 (FY2022)
--   * interest and finance costs: none
--   * operating income: the profit before tax (an insurer without finance costs), 174,699,981 and 105,991,095
--   * revenue - cost of revenue - operating expenses = profit before tax in both years:
--     760,278,094 - 300,832,370 - 284,745,743 = 174,699,981; 765,672,363 - 331,260,659 - 328,420,609 = 105,991,095
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent; does nothing until ABDA exists, and
-- changes a year only while it still holds the figures of the tag (an upload that stores it otherwise is kept).
-- =====================================================================

UPDATE income_statement i
SET operating_expenses = i.interest_expense,
    interest_expense = NULL,
    operating_income = i.pretax_income
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE i.period_id = rp.period_id
  AND c.ticker = 'ABDA' AND c.exchange = 'IDX'
  AND rp.fiscal_year = 2021 AND rp.period_type = 'FY'
  AND i.interest_expense = 284745743000 AND i.operating_expenses IS NULL
  AND i.revenue - i.cost_of_revenue - i.interest_expense = i.pretax_income;

UPDATE income_statement i
SET interest_expense = NULL,
    operating_income = i.pretax_income
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE i.period_id = rp.period_id
  AND c.ticker = 'ABDA' AND c.exchange = 'IDX'
  AND rp.fiscal_year = 2022 AND rp.period_type = 'FY'
  AND i.interest_expense = 328420609000 AND i.operating_expenses = 328420609000
  AND i.revenue - i.cost_of_revenue - i.operating_expenses = i.pretax_income;
