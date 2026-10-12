-- =====================================================================
-- Neraca Lab - RAJA (PT Rukun Raharja Tbk) share counts and stock split (PostgreSQL 15+)
--
-- RAJA reports in USD: its share capital fits no rupiah par value and its basic EPS (0.0054) is too
-- coarse to give the share count, so the filings yield no share counts. The published counts did not
-- fit the filings either: RAJA split its shares 1:5 on 2026-07-16, after its latest filing (H1 2026),
-- so every filing states the earnings per share of before the split while the published counts are
-- those of after it (5 x). Without share counts RAJA had no market cap and no valuation.
--
--   * 4,227,082,500 shares until the 1:5 stock split effective 2026-07-16, 21,135,412,500 afterwards
--     (Yahoo Finance RAJA.JK: split event 5:1 on 2026-07-16; ordinary shares number and share issued
--      21,135,412,500 at every year end and quarter end from 2022-12-31 to 2026-06-30, split-adjusted)
--   * no treasury stock (shares issued = ordinary shares number at every date)
--   * consistent with every reported EPS on the count of before the split:
--     FY2021  2,252,268 / 4,227,082,500 = 0.000533 (filed 0.00053), FY2022  8,821,573 -> 0.002087 (0.00209),
--     FY2023 25,606,534 -> 0.006058 (0.00606), FY2024 25,552,112 -> 0.006045 (0.00604),
--     H1 2025 11,354,744 -> 0.002686 (0.00269), FY2025 27,240,089 -> 0.006444 (0.00644),
--     H1 2026 22,754,966 -> 0.005383 (0.0054); the count is asserted from 2021-12-31 only
--
-- price_daily holds split-adjusted prices (the provider's close is split-adjusted: no 5x step at
-- 2026-07-16), so the counts are split-adjusted too - 21,135,412,500 at every date, which keeps
-- market cap = price x shares before the split as well - and so are the per-share figures of the
-- periods before it (EPS / 5), as for a split a later filing restates (IngestionRepository
-- #applyDetectedSplits). The corporate action starts with the marker that method reads, so a filing
-- of before the split that is uploaded later, or again, ends on the same basis.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation
-- snapshots from them). Idempotent; does nothing until RAJA exists (created by its first upload);
-- only fills share counts that are still empty, and only divides an EPS that is still on the count
-- of before the split (profit / EPS within 10% of 4,227,082,500).
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, d.snapshot_date, 21135412500, 0
FROM company c
CROSS JOIN (VALUES
    (DATE '2021-12-31'), (DATE '2022-12-31'), (DATE '2023-12-31'), (DATE '2024-12-31'),
    (DATE '2025-06-30'), (DATE '2025-12-31'), (DATE '2026-06-30')
) AS d (snapshot_date)
WHERE c.ticker = 'RAJA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

-- shares_issued: the shares the split added (21,135,412,500 - 4,227,082,500)
INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to, shares_issued, description)
SELECT c.company_id, DATE '2026-07-16', 'STOCK_SPLIT', 1, 5, 16908330000,
       'Recorded from public sources: stock split 1:5 (4227082500 -> 21135412500 shares), effective 2026-07-16 '
       || '(Yahoo Finance RAJA.JK split event). Share counts and per-share figures of earlier dates are stored '
       || 'split-adjusted, like the prices.'
FROM company c
WHERE c.ticker = 'RAJA' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'STOCK_SPLIT'
                    AND ca.action_date = DATE '2026-07-16');

UPDATE income_statement i
SET basic_eps      = ROUND(i.basic_eps / 5, 8),
    diluted_eps    = ROUND(i.diluted_eps / 5, 8),
    basic_shares   = i.basic_shares * 5,
    diluted_shares = i.diluted_shares * 5
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.period_id = i.period_id
  AND c.ticker = 'RAJA' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2026-07-16'
  AND i.basic_eps IS NOT NULL AND i.basic_eps <> 0 AND i.net_income_to_parent IS NOT NULL
  AND ABS(i.net_income_to_parent / i.basic_eps - 4227082500) <= 422708250;
