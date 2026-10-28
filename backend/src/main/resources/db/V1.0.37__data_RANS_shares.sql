-- =====================================================================
-- Neraca Lab - RANS (PT Rans Entertainmen Indonesia Tbk) share counts around its IPO (PostgreSQL 15+)
--
-- RANS listed on IDX on 2026-07-10 (its first close in price_daily) after its initial public offering. Its only
-- filing (H1 2026) ends before the listing and gives no share count, and the Yahoo Finance count fits none of its EPS
-- (H1 2026: profit attributable 25,573,535,778 / EPS 2.54 = 10.07 billion; H1 2025: 22,734,278,455 / 2.25 = 10.10
-- billion), so RANS had prices but no market cap and no valuation at all.
--
--   * 10,084,250,000 shares at 2025-12-31: the founders' shares before the offering, as IDX's listing announcement
--     lists them ("Pengumuman Pencatatan Efek PT Rans Entertainmen Indonesia Tbk", July 2026); the EPS above implies
--     about 10.07 - 10.10 billion
--   * 12,609,250,000 shares from 2026-07-10: + 2,525,000,000 shares of the offering (20.02% of the shares listed, the
--     free float the announcement gives); KSEI's monthly lists of July - September 2026 bound the listed shares to
--     12,608.2 - 12,610.3 million (Raffi Farid Ahmad's 7,935,000,000 shares at 62.93%)
--   * no treasury stock
--
-- RANS has no full year as a listed company yet (no FY2025 filing): no trailing twelve months, so no P/E, P/S or
-- EV / EBITDA until its FY2026 filing; it has a market cap, EV and P/B.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation snapshots from
-- them) and after every upload. Idempotent; does nothing until RANS exists (created by its first upload); only fills
-- a share count that is still empty, so a count derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
CROSS JOIN (VALUES (DATE '2025-12-31', 10084250000),
                   (DATE '2026-07-10', 12609250000)) AS v(snapshot_date, shares)
WHERE c.ticker = 'RANS' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

INSERT INTO corporate_action (company_id, action_date, action_type, shares_issued, cash_raised, description)
SELECT c.company_id, DATE '2026-07-10', 'IPO', 2525000000, NULL,
       'Recorded from public sources: initial public offering of 2525000000 shares (20.02% of the shares listed), '
       || 'listed on IDX 2026-07-10 (10084250000 -> 12609250000 shares).'
FROM company c
WHERE c.ticker = 'RANS' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'IPO');
