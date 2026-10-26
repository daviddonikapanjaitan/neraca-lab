-- =====================================================================
-- Neraca Lab - BNBR (PT Bakrie & Brothers Tbk) share counts (PostgreSQL 15+)
--
-- BNBR's filings give no share count: its common stock is five series of different par values (Seri A Rp 5,687,
-- B Rp 796, C Rp 227, D Rp 99, E Rp 12 after the quasi-reorganisation of 2024), so no par value divides it, and the
-- Yahoo Finance count (about 201.4 billion) fits none of its EPS (FY2025: profit attributable 493,852 million / EPS
-- 2.85 = 173.3 billion; H1 2026: 224,811 / 1.30 = 172.9 billion). Without a count BNBR had no market cap and no
-- valuation at all. The counts below are the company's own:
--
--   * 160,057,457,509 shares before the private placement of 2024-12-11 (= 173,416,832,509 - 13,359,375,000); the
--     same at 2023-12-31: the FY2024 statement of changes in equity (the FY2025 filing's comparative) shows no other
--     issuance in 2024 (common stock 23,675,988 - 19,072,123 quasi-reorganisation + 160,313 = 4,764,178 million;
--     160,313 million = 13,359,375,000 x Rp 12), and FY2024 profit 327,590 million / EPS 2.04 = 160.6 billion
--     weighted confirms it
--   * 173,416,832,509 shares from 2024-12-11: PMTHMETD of 13,359,375,000 Seri E shares at Rp 64 (a conversion of
--     debt to Eurofa Capital Investment Inc. and Silvery Moon Investment Ltd.), "Pengumuman Hasil Pelaksanaan
--     PMTHMETD" of 2024-12-12 (bakrie-brothers.com); unchanged to 2026-06-30 (common stock 4,764,178 million in the
--     FY2025 and H1 2026 filings)
--   * 263,336,671,587 shares from 2026-07-31: PMHMETD V, 89,919,839,078 Seri E shares (nominal Rp 12) at Rp 53,
--     27 old shares : 14 rights, new shares distributed 2026-07-16 .. 29, the standby buyer paying in full on
--     2026-07-31 (prospectus of 2026-06-30, bakrie-brothers.com); KSEI lists 263,336,671,587 shares
--   * no treasury stock (the prospectus' capital structure has none)
--
-- The shares of 2021 - 2022 are not recorded (no source of the counts of the series at those dates).
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation snapshots from
-- them) and after every upload. Idempotent; does nothing until BNBR exists (created by its first upload); only fills a
-- share count that is still empty, so a count derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
CROSS JOIN (VALUES (DATE '2023-12-31', 160057457509),
                   (DATE '2024-12-11', 173416832509),
                   (DATE '2026-07-31', 263336671587)) AS v(snapshot_date, shares)
WHERE c.ticker = 'BNBR' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

INSERT INTO corporate_action (company_id, action_date, action_type, shares_issued, cash_raised, description)
SELECT c.company_id, DATE '2024-12-11', 'PRIVATE_PLACEMENT', 13359375000, NULL,
       'Recorded from public sources: PMTHMETD of 13359375000 Seri E shares at Rp 64, a conversion of debt to Eurofa '
       || 'Capital Investment Inc. and Silvery Moon Investment Ltd. (160057457509 -> 173416832509 shares).'
FROM company c
WHERE c.ticker = 'BNBR' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'PRIVATE_PLACEMENT'
                    AND ca.action_date = DATE '2024-12-11');

INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to, shares_issued, cash_raised,
                              description)
SELECT c.company_id, DATE '2026-07-31', 'RIGHTS_ISSUE', 27, 14, 89919839078, 4765751471134,
       'Recorded from public sources: PMHMETD V of 89919839078 Seri E shares (nominal Rp 12) at Rp 53, 27 old shares '
       || ': 14 rights (173416832509 -> 263336671587 shares); new shares distributed 2026-07-16 .. 29, the standby '
       || 'buyer paying in full on 2026-07-31.'
FROM company c
WHERE c.ticker = 'BNBR' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'RIGHTS_ISSUE'
                    AND ca.action_date = DATE '2026-07-31');
