-- =====================================================================
-- Neraca Lab - MKNT (PT Mitra Komunikasi Nusantara Tbk, renamed PT Remitra Global International Tbk) share count
-- after its PMTHMETD of 2026-09-30 (PostgreSQL 15+)
--
-- MKNT's filings give 5,500,000,000 shares (common stock Rp 110,000,000,000 at par Rp 20, unchanged in its 9M 2026
-- workbook at 2026-09-30), and so did KSEI up to its list of 2026-08-31 (5,500,504,904 from the holders >= 5%). On
-- 2026-09-30 MKNT issued 1,024,490,000,000 new Seri B shares at Rp 1 without pre-emptive rights (PMTHMETD approved by
-- the EGM of 2026-09-14, at most 1,024,490,000,000; "dilaksanakan pada 30 September 2026 dengan harga pelaksanaan Rp1
-- per saham", paid-up capital Rp 110 billion -> Rp 1.13 trillion, stockwatch.id 2026-10-05; "jumlah saham meningkat dari
-- 5,5 miliar menjadi sekitar 1,02999 triliun", receh.in 2026-10-25). Two sources agree on the count:
--
--   * KSEI's 1% lists of 2026-09-30 and 2026-10-30: the new holders hold exactly the new shares - PT Headwell Bintang
--     Energi Hijau 668,000,000,000 (64.85%), PT Mantra Capital Persadan 156,490,000,000 (15.19%), three holders of
--     50,000,000,000 (4.85%) and two of 25,000,000,000 (2.43%): 1,024,490,000,000; with the 5,500,000,000 old shares
--     1,029,990,000,000, and 668,000,000,000 / 1,029,990,000,000 = 64.855%, 156,490,000,000 / 1,029,990,000,000 =
--     15.193%, 50,000,000,000 / 1,029,990,000,000 = 4.854%, as listed
--   * the press figure of 1.02999 trillion shares and the paid-up capital of Rp 1.13 trillion
--     (110,000,000,000 + 1,024,490,000,000 x Rp 1 = 1,134,490,000,000)
--
-- Market cap had used 5,500,000,000 shares since trading resumed on 2026-10-19 (Rp 2 rising to Rp 32 by 2026-11-05):
-- 187 times too small. The 9M 2026 balance sheet at 2026-09-30 does not yet carry the new capital, so its snapshot of
-- that date keeps the filing's count; the new count is stored from 2026-10-01 (trading was suspended from 2024-07-11 to
-- 2026-10-19, so no valuation date lies in between). No treasury stock.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent; does nothing until MKNT exists; only
-- fills a share count that is still empty.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, DATE '2026-10-01', 1029990000000, 0
FROM company c
WHERE c.ticker = 'MKNT' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

INSERT INTO corporate_action (company_id, action_date, action_type, shares_issued, cash_raised, description)
SELECT c.company_id, DATE '2026-09-30', 'PRIVATE_PLACEMENT', 1024490000000, NULL,
       'Recorded from public sources: PMTHMETD of 1024490000000 Seri B shares at Rp 1 to PT Headwell Bintang Energi '
       || 'Hijau, PT Mantra Capital Persadan and five others (5500000000 -> 1029990000000 shares).'
FROM company c
WHERE c.ticker = 'MKNT' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'PRIVATE_PLACEMENT'
                    AND ca.action_date = DATE '2026-09-30');
