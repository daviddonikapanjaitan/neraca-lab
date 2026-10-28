-- =====================================================================
-- Neraca Lab - ENRG (PT Energi Mega Persada Tbk) share counts (PostgreSQL 15+)
--
-- ENRG's filings (USD) give no share count, and the Yahoo Finance count (30,331,949,667 in October 2026) fits none
-- of its EPS (FY2025: profit attributable 91,532,975 USD / EPS 0.00359 = 25.5 billion weighted; H1 2026: 45,234,133 /
-- 0.00171 = 26.45 billion), so every upload stored no share count: ENRG had prices and market snapshots but no market
-- cap and no valuation at all. The counts below are the company's own:
--
--   * 26,346,230,250 shares at 2025-12-31: the count before the private placement of June 2026 (26,564,543,750 -
--     218,313,500); the H1 2026 statement of changes in equity shows that placement as the half-year's only issuance
--     (common stock +1,220,786 USD = 218,313,500 x Rp 100 nominal). KSEI's monthly lists of February - May 2026 put
--     the listed shares between 26.343 and 26.353 billion (its largest holders' shares / percentage)
--   * 26,564,543,750 shares from 2026-06-12: PMTHMETD of 218,313,500 Seri B shares (nominal Rp 100) at Rp 1,550 to
--     PT Bakrie Kalila Investment, issued 2026-06-12, listed 2026-06-15 (Liputan6 2026-06-07, Viva 2026-06-06: "modal
--     ditempatkan dan disetor penuh ... 26,56 miliar saham"); KSEI's lists of June - September 2026 imply 26.55 -
--     26.58 billion; 2 x 13,282,271,875 rights of PMHMETD IV = 26,564,543,750
--   * no treasury stock (the filings' treasury stock lines are empty)
--
-- Not recorded yet: PMHMETD IV, up to 13,282,271,875 Seri B shares (nominal Rp 2) at Rp 310, 2 old : 1 right, new
-- shares listed from 2026-10-08 as the rights are exercised (to 2026-10-21), allotment 2026-10-26, the standby buyer
-- PT Bakrie Kalila Investment paying 2026-10-27; at most 39,846,815,625 shares after it. Add its count once ENRG
-- announces the result (Pengumuman Hasil Pelaksanaan PMHMETD IV). Until then the valuation of a date from
-- 2026-10-08 uses 26,564,543,750 shares, fewer than are listed. The shares of 2021 - 2024 are not recorded (no source
-- of the exact counts; the EPS implies about 24.8 billion weighted).
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation snapshots from
-- them) and after every upload. Idempotent; does nothing until ENRG exists (created by its first upload); only fills
-- a share count that is still empty, so a count derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
CROSS JOIN (VALUES (DATE '2025-12-31', 26346230250),
                   (DATE '2026-06-12', 26564543750)) AS v(snapshot_date, shares)
WHERE c.ticker = 'ENRG' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

INSERT INTO corporate_action (company_id, action_date, action_type, shares_issued, cash_raised, description)
SELECT c.company_id, DATE '2026-06-12', 'PRIVATE_PLACEMENT', 218313500, NULL,
       'Recorded from public sources: PMTHMETD of 218313500 Seri B shares (nominal Rp 100) at Rp 1,550 (Rp '
       || '338,385,925,000; the company reports in USD) to PT Bakrie Kalila Investment, listed 2026-06-15 '
       || '(26346230250 -> 26564543750 shares).'
FROM company c
WHERE c.ticker = 'ENRG' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'PRIVATE_PLACEMENT'
                    AND ca.action_date = DATE '2026-06-12');
