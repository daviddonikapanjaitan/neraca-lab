-- =====================================================================
-- Neraca Lab - share counts of MSIN, RMKE, SMGA, WIFI, INET and PACK (PostgreSQL 15+)
--
-- Found by the valuation correctness check of 2026-10-30 / 2026-10-31 (valuation_check.py: the latest share count
-- against KSEI's 1% list of 2026-09-30, holders >= 5%: shares / percentage). Each count below is confirmed by at least
-- two sources: the share capital on the balance sheet at a par value the company's earlier filings prove, KSEI's
-- monthly lists (ownership_one_holder), the filed EPS, the IDX listing announcement or the company's disclosure.
--
--   * MSIN (PT MNC Digital Entertainment Tbk): 60,676,178,205 shares since its 1:5 split of 2024-10-07 (par Rp 10;
--     share capital Rp 606,762 million). Its H1 2026 filing files the EPS of both columns ten times too large (H1 2026
--     0.0000469 million = 46.9 for 284,347 million / 60.68 billion = 4.69; H1 2025 39.80 for 241,315 million = 3.98),
--     so FilingMapper.inferParValue fitted Rp 100 and stored 606,762 million / 100 = 6,067,620,000 shares at
--     2024-12-31, 2025-06-30, 2025-12-31 and 2026-06-30 - a tenth of the count (P/E 0.63). FY2025 EPS 16.07 ->
--     60,676,219,025 shares; KSEI: PT Media Nusantara Citra's 32,669,021,001 at 53.84% -> 60,672 - 60,684 million.
--     Replaced only where the exact wrong count (and the exact wrong EPS) is stored.
--   * RMKE (PT RMK Energy Tbk): stock split 1:5 (par Rp 100 -> Rp 20), trading at the new par from 2026-07-17 (IDX /
--     company disclosure: RUPSLB 2026-06-26, theoretical price 2,480 / 5 = 496; Yahoo Finance split event): 4,375,000,000
--     -> 21,875,000,000 shares. KSEI: PT RMK Investama's 2,485,000,000 at 56.80% (February 2026) -> 12,425,000,000 at
--     56.80% (September 2026). price_daily is split-adjusted (no step at 2026-07-17), so the counts and per-share
--     figures of earlier dates are stored split-adjusted too (as TUGU, V1.0.22); the H1 2026 filing already restates
--     them (EPS 7.48 -> 21.84 billion).
--   * SMGA (PT Sumber Mineral Global Abadi Tbk): 7,000,000,000 founders' shares, 8,750,000,000 since its IPO of
--     2024-01-30 (1,750,000,000 offered, par Rp 20; IDX listing announcement). Share capital Rp 140,000,000,000 ->
--     Rp 175,000,000,000 at Rp 20. Its FY2024 EPS 1.73 fits no count (20,345 million / 8.75 billion = 2.33), and the
--     par value inference took Rp 10: 14,000,000,000 at 2022-12-31 / 2023-12-31 and 17,500,000,000 at 2024-12-31 - twice
--     the count. KSEI: PT Sumber Global Energy's 6,300,000,000 at exactly 72.00%; EPS FY2025 5.58 -> 8,618.5 million.
--   * WIFI (PT Solusi Sinergi Digital Tbk): share capital Rp 530,854,901,500 at 2025-12-31 and 2026-06-30, par Rp 100
--     (Rp 235,935,511,800 = 2,359,355,118 shares at 2024-12-31) -> 5,308,549,015 shares after its 2025 rights issue.
--     KSEI: PT Investasi Sukses Bersama's 2,889,087,404 at 54.42% -> 5,308.4 - 5,309.4 million (February and September
--     2026).
--   * INET (PT Sinergi Inti Andalan Prima Tbk): share capital Rp 223,741,110,880 at 2026-06-30, par Rp 10
--     (Rp 95,810,752,960 = 9,581,075,296 shares at 2025-12-31) -> 22,374,111,088 shares after its 2026 rights issue.
--     KSEI: PT Abadi Kreasi Unggul Nusantara's 13,286,121,131 at 59.38% (February 2026) -> 22,372.9 - 22,376.6 million.
--   * PACK (PT Abadi Nusantara Hijau Investama Tbk): rights issue PMHMETD I of mandatory convertible bonds (5 : 102,
--     recording date 2026-01-13; 1,597,398,988 x 102 / 5 = 32,586,939,355 bonds of one share each), converted during
--     2026. Share capital Rp 323,792,536,950 at 2026-06-30, par Rp 10 (Rp 15,973,989,880 = 1,597,398,988 shares at
--     2025-12-31) -> 32,379,253,695 shares. KSEI: 15.44 billion in February, 34.11 - 34.12 billion in September 2026
--     (the remaining bonds converted after June); no count is stated for those dates, so none is stored.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql and after every upload (IngestionRepository.SHARE_SCRIPTS),
-- so an upload of a filing that stored a wrong count again is corrected too. Idempotent; does nothing until a company
-- exists; fills only empty counts, and changes stored figures only where the exact wrong value (or, for RMKE, the
-- count of before the split) is still stored.
-- =====================================================================

-- ---------------------------------------------------------------- counts that are still empty
INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
JOIN (VALUES ('RMKE', DATE '2026-06-30', 21875000000),
             ('RMKE', DATE '2026-07-17', 21875000000),
             ('SMGA', DATE '2024-01-30', 8750000000),
             ('SMGA', DATE '2025-06-30', 8750000000),
             ('SMGA', DATE '2025-12-31', 8750000000),
             ('SMGA', DATE '2026-06-30', 8750000000),
             ('WIFI', DATE '2025-12-31', 5308549015),
             ('WIFI', DATE '2026-06-30', 5308549015),
             ('INET', DATE '2026-06-30', 22374111088),
             ('PACK', DATE '2026-06-30', 32379253695)) AS v(ticker, snapshot_date, shares)
  ON v.ticker = c.ticker
WHERE c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

-- ---------------------------------------------------------------- MSIN: the count of a par value of Rp 100
UPDATE share_snapshot s
SET shares_outstanding = 60676178205
FROM company c
WHERE c.company_id = s.company_id
  AND c.ticker = 'MSIN' AND c.exchange = 'IDX'
  AND s.snapshot_date >= DATE '2024-10-07'
  AND s.shares_outstanding = 6067620000;

UPDATE balance_sheet b
SET shares_outstanding = 60676178205
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = b.period_id
  AND c.ticker = 'MSIN' AND c.exchange = 'IDX'
  AND b.shares_outstanding = 6067620000;

-- the EPS the H1 2026 filing states ten times too large (H1 2025: 39.80, H1 2026: 46.90)
UPDATE income_statement i
SET basic_eps   = ROUND(i.basic_eps / 10, 8),
    diluted_eps = ROUND(i.diluted_eps / 10, 8)
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = i.period_id
  AND c.ticker = 'MSIN' AND c.exchange = 'IDX'
  AND rp.period_type = 'H1' AND rp.fiscal_year IN (2025, 2026)
  AND ((rp.fiscal_year = 2025 AND i.basic_eps = 39.80) OR (rp.fiscal_year = 2026 AND i.basic_eps = 46.90));

-- ---------------------------------------------------------------- SMGA: the counts of a par value of Rp 10
UPDATE share_snapshot s
SET shares_outstanding = CASE s.shares_outstanding WHEN 14000000000 THEN 7000000000 ELSE 8750000000 END
FROM company c
WHERE c.company_id = s.company_id
  AND c.ticker = 'SMGA' AND c.exchange = 'IDX'
  AND ((s.snapshot_date < DATE '2024-01-30' AND s.shares_outstanding = 14000000000)
    OR (s.snapshot_date >= DATE '2024-01-30' AND s.shares_outstanding = 17500000000));

UPDATE balance_sheet b
SET shares_outstanding = CASE b.shares_outstanding WHEN 14000000000 THEN 7000000000 ELSE 8750000000 END
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = b.period_id
  AND c.ticker = 'SMGA' AND c.exchange = 'IDX'
  AND ((rp.period_end < DATE '2024-01-30' AND b.shares_outstanding = 14000000000)
    OR (rp.period_end >= DATE '2024-01-30' AND b.shares_outstanding = 17500000000));

-- ---------------------------------------------------------------- RMKE: the split 1:5 of 2026-07-17
-- shares_issued: the shares the split added (21,875,000,000 - 4,375,000,000)
INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to, shares_issued, description)
SELECT c.company_id, DATE '2026-07-17', 'STOCK_SPLIT', 1, 5, 17500000000,
       'Recorded from public sources: stock split 1:5 (4375000000 -> 21875000000 shares, par value Rp 100 -> Rp 20), '
       || 'effective 2026-07-17 (IDX / company disclosure, Yahoo Finance RMKE.JK split event). Share counts and '
       || 'per-share figures of earlier dates are stored split-adjusted, like the prices.'
FROM company c
WHERE c.ticker = 'RMKE' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'STOCK_SPLIT'
                    AND ca.action_date = DATE '2026-07-17');

-- figures of the dates before it that are still on the count of before the split (within 10% of 4,375,000,000)
UPDATE income_statement i
SET basic_eps      = ROUND(i.basic_eps / 5, 8),
    diluted_eps    = ROUND(i.diluted_eps / 5, 8),
    basic_shares   = i.basic_shares * 5,
    diluted_shares = i.diluted_shares * 5
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = i.period_id
  AND c.ticker = 'RMKE' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2026-07-17'
  AND ABS(COALESCE(i.basic_shares,
                   CASE WHEN i.basic_eps <> 0 THEN i.net_income_to_parent / i.basic_eps END) - 4375000000) <= 437500000;

UPDATE balance_sheet b
SET shares_outstanding = b.shares_outstanding * 5
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = b.period_id
  AND c.ticker = 'RMKE' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2026-07-17'
  AND ABS(b.shares_outstanding - 4375000000) <= 437500000;

UPDATE share_snapshot s
SET shares_outstanding = s.shares_outstanding * 5,
    basic_shares       = s.basic_shares * 5,
    treasury_shares    = s.treasury_shares * 5
FROM company c
WHERE c.company_id = s.company_id
  AND c.ticker = 'RMKE' AND c.exchange = 'IDX'
  AND s.snapshot_date < DATE '2026-07-17'
  AND ABS(COALESCE(s.shares_outstanding, s.basic_shares) - 4375000000) <= 437500000;
