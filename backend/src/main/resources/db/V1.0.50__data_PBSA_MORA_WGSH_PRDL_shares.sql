-- =====================================================================
-- Neraca Lab - a stock split and share issues after the stored counts (PostgreSQL 15+)
--
-- Found by the valuation correctness check of 2026-11-05:
--   * PBSA (PT Paramita Bangun Sarana Tbk): stock split 1:2 on 2026-10-13. Two sources: the price fell from 820 to
--     422.50 that day (x0.515, prices split-adjusted), and KSEI's 1% list of 2026-10-30 gives 5,999,738,586 -
--     6,000,258,694 issued shares (every holder intersected) against the 3,000,000,000 of share capital Rp 150,000
--     million at par Rp 50 in its H1 2026 filing (filed EPS 2,992,955,519 shares). 6,000,000,000 = 150,000 million at
--     par Rp 25. Earlier share counts and per-share figures are stored split-adjusted, like the prices (TUGU pattern).
--   * MORA: 47,774,192,736 shares at 2026-06-30 and 2026-09-30 (share capital Rp 4,777,419,273,600 / par Rp 100 in its
--     H1 / 9M 2026 filings; KSEI 47,773,697,783 - 47,776,747,791). Stored until then: 23,646,668,691 (2025-12-31).
--   * WGSH: 2,085,000,000 shares at 2026-06-30 (share capital Rp 41,700 million / par Rp 20, twice that of 2025-12-31;
--     KSEI 2,084,979,920 - 2,085,328,470; H1 2026 profit / EPS 2,085,519,456).
--   * PRDL: 1,742,900,000 shares at 2026-09-30 (share capital Rp 87,145 million / par Rp 50 in its 9M 2026 filing; KSEI
--     1,742,613,079 - 1,743,101,275; 9M 2026 profit / EPS 1,743,672,327). The 15,000 shares stored for 2024 - 2025
--     are its share capital of Rp 15,000 million before the IPO at a par of Rp 1,000,000 and are left as filed.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent; fills only empty counts, and the split
-- adjustment only touches figures still within 10% of the pre-split count.
-- =====================================================================

INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to, shares_issued, description)
SELECT c.company_id, DATE '2026-10-13', 'STOCK_SPLIT', 1, 2, 3000000000,
       'Recorded from public sources: stock split 1:2 (3000000000 -> 6000000000 shares, par value Rp 50 -> Rp 25), '
       || 'effective 2026-10-13 (price 820 -> 422.50; KSEI 1% list of 2026-10-30). Share counts and per-share figures '
       || 'of earlier dates are stored split-adjusted, like the prices.'
FROM company c
WHERE c.ticker = 'PBSA' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'STOCK_SPLIT'
                    AND ca.action_date = DATE '2026-10-13');

UPDATE income_statement i
SET basic_eps      = ROUND(i.basic_eps / 2, 8),
    diluted_eps    = ROUND(i.diluted_eps / 2, 8),
    basic_shares   = i.basic_shares * 2,
    diluted_shares = i.diluted_shares * 2
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = i.period_id
  AND c.ticker = 'PBSA' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2026-10-13'
  AND ABS(COALESCE(i.basic_shares,
                   CASE WHEN i.basic_eps <> 0 THEN i.net_income_to_parent / i.basic_eps END) - 3000000000) <= 300000000;

UPDATE balance_sheet b
SET shares_outstanding = b.shares_outstanding * 2
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = b.period_id
  AND c.ticker = 'PBSA' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2026-10-13'
  AND ABS(b.shares_outstanding - 3000000000) <= 300000000;

UPDATE share_snapshot s
SET shares_outstanding = s.shares_outstanding * 2,
    basic_shares       = s.basic_shares * 2,
    treasury_shares    = s.treasury_shares * 2
FROM company c
WHERE c.company_id = s.company_id
  AND c.ticker = 'PBSA' AND c.exchange = 'IDX'
  AND s.snapshot_date < DATE '2026-10-13'
  AND ABS(COALESCE(s.shares_outstanding, s.basic_shares) - 3000000000) <= 300000000;

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, NULL
FROM company c
JOIN (VALUES ('PBSA', DATE '2026-10-13', 6000000000),
             ('PBSA', DATE '2026-06-30', 6000000000),
             ('MORA', DATE '2026-06-30', 47774192736),
             ('MORA', DATE '2026-09-30', 47774192736),
             ('WGSH', DATE '2026-06-30', 2085000000),
             ('PRDL', DATE '2026-09-30', 1742900000)) AS v(ticker, snapshot_date, shares)
  ON v.ticker = c.ticker
WHERE c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding)
WHERE share_snapshot.shares_outstanding IS NULL;
