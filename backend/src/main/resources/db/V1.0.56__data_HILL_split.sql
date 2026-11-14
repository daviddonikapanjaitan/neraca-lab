-- =====================================================================
-- Neraca Lab - HILL (PT Hillcon Tbk) stock split 1:5 of 2025-03-14 (PostgreSQL 15+)
--
-- HILL split each share into five (par value Rp 100 -> Rp 20): approved by the AGM announced 2025-03-10, the last
-- trading day at Rp 100 2025-03-13, shares at Rp 20 traded from 2025-03-14 (stockwatch.id 'Siap-siap saham nominal
-- Rp20 Hillcon mulai diperdagangkan 14 Maret 2025'; bisnis.com 2025-03-14, theoretical price Rp 392 - 393 after a
-- cum price of Rp 1,965); 2,948,300,000 -> 14,741,500,000 shares. The share capital confirms it: Rp 294,830 million
-- = 2,948,300,000 x Rp 100 (FY2023) = 14,741,500,000 x Rp 20 (FY2024 on, KSEI 2026). Yahoo's prices (from
-- 2023-03-01) are split-adjusted, but the counts and EPS of 2021 - 2023 were stored on the old basis (FY2022 EPS 120
-- on 2,506,000,000 shares, FY2023 119 on 2,948,300,000), so the market cap of those dates was five times too small.
-- They are stored split-adjusted here, like the prices; the corporate action (its description starting 'Recorded
-- from public sources: ') makes later uploads of those filings store them split-adjusted too
-- (IngestionRepository.detectedSplits). FY2024 and later filings already state the counts at Rp 20.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent: each update applies only to a count
-- still within 10% of an old-basis count (2,506,000,000 before the IPO of 2023, 2,948,300,000 after it).
-- =====================================================================

INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to, shares_issued, description)
SELECT c.company_id, DATE '2025-03-14', 'STOCK_SPLIT', 1, 5, 11793200000,
       'Recorded from public sources: stock split 1:5 (2948300000 -> 14741500000 shares, par value Rp 100 -> Rp 20), '
       || 'shares at Rp 20 traded from 2025-03-14. Share counts and per-share figures of earlier dates are stored '
       || 'split-adjusted, like the prices.'
FROM company c
WHERE c.ticker = 'HILL' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'STOCK_SPLIT'
                    AND ca.action_date = DATE '2025-03-14');

UPDATE income_statement i
SET basic_eps      = ROUND(i.basic_eps / 5, 8),
    diluted_eps    = ROUND(i.diluted_eps / 5, 8),
    basic_shares   = i.basic_shares * 5,
    diluted_shares = i.diluted_shares * 5
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.company_id = i.company_id AND rp.period_id = i.period_id
  AND c.ticker = 'HILL' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2025-03-14'
  AND (ABS(COALESCE(i.basic_shares, CASE WHEN i.basic_eps <> 0 THEN i.net_income_to_parent / i.basic_eps END) - 2948300000) <= 294830000
    OR ABS(COALESCE(i.basic_shares, CASE WHEN i.basic_eps <> 0 THEN i.net_income_to_parent / i.basic_eps END) - 2506000000) <= 250600000);

UPDATE balance_sheet b
SET shares_outstanding = b.shares_outstanding * 5
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.company_id = b.company_id AND rp.period_id = b.period_id
  AND c.ticker = 'HILL' AND c.exchange = 'IDX'
  AND rp.period_end < DATE '2025-03-14'
  AND (ABS(b.shares_outstanding - 2948300000) <= 294830000 OR ABS(b.shares_outstanding - 2506000000) <= 250600000);

UPDATE share_snapshot s
SET shares_outstanding = s.shares_outstanding * 5,
    basic_shares       = s.basic_shares * 5,
    treasury_shares    = s.treasury_shares * 5
FROM company c
WHERE c.company_id = s.company_id
  AND c.ticker = 'HILL' AND c.exchange = 'IDX'
  AND s.snapshot_date < DATE '2025-03-14'
  AND (ABS(COALESCE(s.shares_outstanding, s.basic_shares) - 2948300000) <= 294830000
    OR ABS(COALESCE(s.shares_outstanding, s.basic_shares) - 2506000000) <= 250600000);
