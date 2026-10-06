-- =====================================================================
-- Neraca Lab - SMDR (PT Samudera Indonesia Tbk) share counts (PostgreSQL 15+)
--
-- SMDR reports in USD: its share capital (USD 47,460,340) fits no rupiah par value, and its basic
-- EPS has 3 decimals (0.003), too coarse to give the share count, so the filings yield no share
-- counts and SMDR had no market cap / valuation. The counts below come from public sources:
--
--   * 3,275,120,000 shares of Rp 25 until the 1:5 stock split effective 2023-01-31
--     (RUPS 2022-11-09), 16,375,600,000 shares of Rp 5 afterwards
--     (Bisnis.com 2023-01-25 "Samudera Indonesia (SMDR) Tetapkan Stock Split 1:5 Akhir Januari";
--      Kontan; KSEI registered securities: 16,375,600,000 shares)
--   * no treasury stock (treasury column 0 in every statement of changes in equity)
--   * share capital unchanged from 2020-12-31 to 2026-06-30 in the filings (USD 47,460,340), so the
--     count is asserted from 2020-12-31 only; earlier prices get no market cap
--   * consistent with every reported EPS: FY2022 212,694,879 / 3,275,120,000 = 0.0649 (filed 0.065),
--     FY2023 74,588,339 / 16,375,600,000 = 0.0046 (0.005), FY2025 52,050,752 / 16,375,600,000 = 0.0032
--     (0.003), H1 2026 32,524,639 / 16,375,600,000 = 0.0020 (0.002)
--
-- price_daily holds split-adjusted prices (the provider's close is split-adjusted: no 5x step at
-- 2023-01-31), so the counts are split-adjusted too: 16,375,600,000 at every date, which keeps
-- market cap = price x shares right before the split as well.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation
-- snapshots from them). Idempotent; does nothing until SMDR exists (created by its first upload);
-- only fills share counts that are still empty, so a count derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, d.snapshot_date, 16375600000, 0
FROM company c
CROSS JOIN (VALUES
    (DATE '2020-12-31'), (DATE '2021-12-31'), (DATE '2022-12-31'), (DATE '2023-12-31'),
    (DATE '2024-12-31'), (DATE '2025-06-30'), (DATE '2025-12-31'), (DATE '2026-06-30')
) AS d (snapshot_date)
WHERE c.ticker = 'SMDR' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to, description)
SELECT c.company_id, DATE '2023-01-31', 'STOCK_SPLIT', 1, 5,
       'Stock split 1:5 (par Rp 25 -> Rp 5): 3,275,120,000 -> 16,375,600,000 shares; effective 2023-01-31 (RUPS 2022-11-09)'
FROM company c
WHERE c.ticker = 'SMDR' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'STOCK_SPLIT'
                    AND ca.action_date = DATE '2023-01-31');
