-- =====================================================================
-- Neraca Lab - share counts of ALKA, AMAN, AREA, ATAP and BATR (PostgreSQL 15+)
--
-- None of the five filings states a share count and no Yahoo Finance count fits them, so they had prices but no
-- market cap and no valuation (Hermes uploads of 2026-10-30). The counts below come from KSEI's monthly lists of the
-- shareholders of more than 1% (ownership_one_holder: a holder's shares / its percentage, February - September 2026),
-- the share capital on the balance sheet, the filed EPS and the listing records.
--
--   * ALKA (PT Alakasa Industrindo Tbk): 507,665,055 shares (listing data, lembarsaham.com). KSEI: PT Gesit Perkasa's
--     425,393,555 shares at 83.79% (83.785 - 83.795) -> 507.66 - 507.72 million, February - August 2026 (September's
--     83.80% gives 507.60 - 507.66 million: 507,665,055 is 83.7944%); EPS FY2021 37.06 -> 507.63 million, FY2022
--     94.56 -> 507.69 million, FY2025 38.29 -> 507.71 million, 9M 2026 1.84 -> 507.89 million.
--   * AMAN (PT Makmur Berkah Amanda Tbk): 3,873,500,000 shares, listed 2020-03-13 (585,000,000 offered, 15.1%).
--     Common stocks Rp 387,350,000 thousand at par Rp 100 = 3,873,500,000; KSEI: PT Griya Prima Amanda's 3,158,500,000
--     at 81.54% -> 3,873.32 - 3,873.80 million at every 2026 list. No treasury stock stated (its weighted EPS gives
--     3.70 - 3.79 billion).
--   * AREA (PT Dunia Virtual Online Tbk): 2,029,601,000 founders' shares, 2,539,601,000 from its IPO of 2024-04-01
--     (510,000,000 offered, par Rp 75; IDX listing announcement). KSEI: 2,539.56 - 2,540.03 million at every 2026
--     list (three holders); EPS FY2023 1.69 -> 2,032.5 million (the founders'), FY2025 3.92 -> 2,541.4 million.
--   * ATAP (PT Trimitra Prawara Goldland Tbk): 1,250,000,000 shares. Common stocks Rp 50,000,000,000 at par Rp 40;
--     KSEI: PT Trimitra Prawara's 950,000,000 at exactly 76.00% at every 2026 list; EPS FY2021 4.42 -> 1,249.7
--     million, FY2024 -1.69 -> 1,248.1 million.
--   * BATR (PT Benteng Api Technic Tbk): 3,025,000,000 shares from its IPO of 2024-06-10 (2,405,000,000 founders'
--     shares, 620,000,000 offered, par Rp 20; IDX listing announcement; the 620,000,000 Series I warrants are not
--     shares). KSEI: Ridwan's 1,332,440,000 at 44.05% -> 3,024.49 - 3,025.18 million at every 2026 list. Its prices
--     start at the listing: no count before it.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation snapshots from
-- them). Idempotent; does nothing until a company exists; only fills a share count that is still empty, so a count
-- derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
JOIN (VALUES ('ALKA', DATE '2021-12-31', 507665055),
             ('ALKA', DATE '2022-12-31', 507665055),
             ('ALKA', DATE '2023-12-31', 507665055),
             ('ALKA', DATE '2024-12-31', 507665055),
             ('ALKA', DATE '2025-06-30', 507665055),
             ('ALKA', DATE '2025-09-30', 507665055),
             ('ALKA', DATE '2025-12-31', 507665055),
             ('ALKA', DATE '2026-06-30', 507665055),
             ('ALKA', DATE '2026-09-30', 507665055),
             ('AMAN', DATE '2021-12-31', 3873500000),
             ('AMAN', DATE '2022-12-31', 3873500000),
             ('AMAN', DATE '2023-12-31', 3873500000),
             ('AMAN', DATE '2024-12-31', 3873500000),
             ('AMAN', DATE '2025-06-30', 3873500000),
             ('AMAN', DATE '2025-12-31', 3873500000),
             ('AMAN', DATE '2026-06-30', 3873500000),
             ('AREA', DATE '2023-12-31', 2029601000),
             ('AREA', DATE '2024-04-01', 2539601000),
             ('AREA', DATE '2024-12-31', 2539601000),
             ('AREA', DATE '2025-06-30', 2539601000),
             ('AREA', DATE '2025-09-30', 2539601000),
             ('AREA', DATE '2025-12-31', 2539601000),
             ('AREA', DATE '2026-06-30', 2539601000),
             ('AREA', DATE '2026-09-30', 2539601000),
             ('ATAP', DATE '2021-12-31', 1250000000),
             ('ATAP', DATE '2022-12-31', 1250000000),
             ('ATAP', DATE '2023-12-31', 1250000000),
             ('ATAP', DATE '2024-12-31', 1250000000),
             ('ATAP', DATE '2025-06-30', 1250000000),
             ('ATAP', DATE '2025-09-30', 1250000000),
             ('ATAP', DATE '2025-12-31', 1250000000),
             ('ATAP', DATE '2026-06-30', 1250000000),
             ('ATAP', DATE '2026-09-30', 1250000000),
             ('BATR', DATE '2024-06-10', 3025000000),
             ('BATR', DATE '2024-12-31', 3025000000),
             ('BATR', DATE '2025-06-30', 3025000000),
             ('BATR', DATE '2025-12-31', 3025000000),
             ('BATR', DATE '2026-06-30', 3025000000)) AS v(ticker, snapshot_date, shares)
  ON v.ticker = c.ticker
WHERE c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;
