-- =====================================================================
-- Neraca Lab - BACH (PT Bach Multi Global Tbk) share count after its IPO (PostgreSQL 15+)
--
-- BACH listed on IDX on 2026-07-08 (first trading day in price_daily; first close Rp 550 = the
-- 25% upper auto-rejection limit over the offer price) after its initial public offering of
-- 615,000,000 new shares at Rp 442 (Rp 271.83 billion, 15.06% of the shares after the offering).
-- Its only filing (H1 2026) ends before the listing, so the filings give the count of before the
-- offering at every date (3,469,430,000 shares of Rp 50, statement of changes in equity) and every
-- market cap since the listing was 15% too low (2026-10-14: Rp 1,151.9 billion instead of
-- Rp 1,356.0 billion).
--
--   * 3,469,430,000 + 615,000,000 = 4,084,430,000 shares from 2026-07-08
--     (Yahoo Finance BACH.JK: shares outstanding 4,084,430,000; prospectus / offering announcements:
--      615,000,000 new shares, 15.06% of the issued and paid-up capital after the offering, at Rp 442)
--   * no treasury stock
--   * the counts of the filing's own dates (2024-12-31 .. 2026-06-30) stay those of before the offering
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation
-- snapshots from them) and after every upload. Idempotent; does nothing until BACH exists (created by
-- its first upload); only fills a share count that is still empty, so a count derived from a filing is
-- never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, DATE '2026-07-08', 4084430000, 0
FROM company c
WHERE c.ticker = 'BACH' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

INSERT INTO corporate_action (company_id, action_date, action_type, shares_issued, cash_raised, description)
SELECT c.company_id, DATE '2026-07-08', 'IPO', 615000000, 271830000000,
       'Recorded from public sources: initial public offering of 615000000 new shares at Rp 442 '
       || '(3469430000 -> 4084430000 shares, 15.06% after the offering), listed on IDX 2026-07-08.'
FROM company c
WHERE c.ticker = 'BACH' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'IPO');
