-- =====================================================================
-- Neraca Lab - TUGU (PT Asuransi Tugu Pratama Indonesia Tbk) stock split 2:1 of 2023-05-24 (PostgreSQL 15+)
--
-- TUGU split its shares 2:1 (par value Rp 100 -> Rp 50) on 2023-05-24. No stored filing restates it: the
-- FY2023 filing states the FY2022 EPS of before the split (Rp 195, on 1,777,787,800 shares) beside the FY2023
-- EPS of after it (Rp 372, on 3,555,575,600 shares), so it is not recognised as a split restated in a
-- comparative, and the FY2022 filing gives share counts of before the split (par 100: 1,777,787,800).
--
--   * 1,777,787,800 shares of Rp 100 until the split, 3,555,575,600 of Rp 50 since; share capital
--     Rp 177,778,780,000 throughout (the FY2022 filing shows 177,777,780 thousand at 2020-12-31 and
--     2021-12-31, the same capital less Rp 1,000,000: 10,000 shares, 0.0006%)
--     (Yahoo Finance TUGU.JK: split event 2:1 on 2023-05-24; ordinary shares number and share issued
--      3,555,575,600 at every year end and quarter end from 2022-12-31 to 2026-06-30, split-adjusted)
--   * no treasury stock (the statements of changes in equity show none at any date)
--   * consistent with every reported EPS: FY2021 316,480,352 thousand / 1,777,787,800 = 178.02 (filed 178),
--     FY2022 347,150,768 thousand -> 195.27 (195); FY2023 1,322,393,811 thousand / 3,555,575,600 = 371.92
--     (filed 0.372 in thousands), FY2024 700,854,404 -> 197.11 (0.0002 in millions), FY2025 711,056,559
--     -> 199.98 (200), H1 2026 480,285,633 -> 135.08 (135)
--
-- price_daily holds split-adjusted prices (no 2x step at 2023-05-24), so the share counts are split-adjusted
-- too - 3,555,575,600 at every date, which keeps market cap = price x shares before the split as well - and
-- so are the per-share figures of the periods before it (EPS / 2). The corporate action starts with the
-- marker IngestionRepository#detectedSplits reads, so a filing of before the split that is uploaded later, or
-- again, ends on the same basis (IngestionRepository#applyDetectedSplits after every save).
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent; does nothing until TUGU exists;
-- only fills share counts that are still empty, and only adjusts figures that are still on the count of
-- before the split (within 10% of 1,777,787,800).
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, d.snapshot_date, 3555575600, 0
FROM company c
CROSS JOIN (VALUES
    (DATE '2021-12-31'), (DATE '2022-12-31'), (DATE '2023-12-31'), (DATE '2024-12-31'),
    (DATE '2025-06-30'), (DATE '2025-12-31'), (DATE '2026-06-30')
) AS d (snapshot_date)
WHERE c.ticker = 'TUGU' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

-- shares_issued: the shares the split added (3,555,575,600 - 1,777,787,800)
INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to, shares_issued, description)
SELECT c.company_id, DATE '2023-05-24', 'STOCK_SPLIT', 1, 2, 1777787800,
       'Recorded from public sources: stock split 1:2 (1777787800 -> 3555575600 shares, par value Rp 100 -> Rp 50), '
       || 'effective 2023-05-24 (Yahoo Finance TUGU.JK split event). Share counts and per-share figures of earlier '
       || 'dates are stored split-adjusted, like the prices.'
FROM company c
WHERE c.ticker = 'TUGU' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'STOCK_SPLIT'
                    AND ca.action_date = DATE '2023-05-24');

-- figures stored before the split was recorded: per-share figures, balance sheet and snapshot counts of the
-- dates before it that are still on the count of before the split
UPDATE income_statement i
SET basic_eps      = ROUND(i.basic_eps / 2, 8),
    diluted_eps    = ROUND(i.diluted_eps / 2, 8),
    basic_shares   = i.basic_shares * 2,
    diluted_shares = i.diluted_shares * 2
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = i.period_id
  AND c.ticker = 'TUGU' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2023-05-24'
  AND ABS(COALESCE(i.basic_shares,
                   CASE WHEN i.basic_eps <> 0 THEN i.net_income_to_parent / i.basic_eps END) - 1777787800) <= 177778780;

UPDATE balance_sheet b
SET shares_outstanding = b.shares_outstanding * 2
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = b.period_id
  AND c.ticker = 'TUGU' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2023-05-24'
  AND ABS(b.shares_outstanding - 1777787800) <= 177778780;

UPDATE share_snapshot s
SET shares_outstanding = s.shares_outstanding * 2,
    basic_shares       = s.basic_shares * 2,
    treasury_shares    = s.treasury_shares * 2
FROM company c
WHERE c.company_id = s.company_id
  AND c.ticker = 'TUGU' AND c.exchange = 'IDX'
  AND s.snapshot_date < DATE '2023-05-24'
  AND ABS(COALESCE(s.shares_outstanding, s.basic_shares) - 1777787800) <= 177778780;
