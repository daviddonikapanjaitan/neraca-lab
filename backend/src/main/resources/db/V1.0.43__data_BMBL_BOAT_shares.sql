-- =====================================================================
-- Neraca Lab - share counts of BMBL and BOAT (PostgreSQL 15+)
--
-- Neither filing states a share count and no Yahoo Finance count fits them ("The filing gives no share counts and no
-- Yahoo Finance count fits it"), so both had prices but no market cap and no valuation (Hermes uploads of
-- 2026-10-30). The counts below come from the share capital on the balance sheet, KSEI's monthly lists of the
-- shareholders of more than 1% (ownership_one_holder: a holder's shares / its percentage, 2026), the filed EPS and the
-- listing records.
--
--   * BMBL (PT Lavender Bina Cendikia Tbk): par Rp 40, not a standard par value (FilingMapper.STANDARD_PAR_VALUES: Rp 40
--     there would make DEFI FY2025's Rp 50 ambiguous - its prior-column EPS of -2.0 fits both - while KSEI gives DEFI
--     687.2 - 687.3 million, not the 845 million of Rp 40; ATAP's Rp 40 is in V1.0.41 alike). Common stocks
--     Rp 41,203,126,600 at 2023-12-31 = 1,030,078,165 shares; Rp 41,203,239,800 from 2024-12-31 = 1,030,080,995
--     shares (FY2024 - H1 2026 filings). KSEI 2026: PT Ammar Al Amanah's 258,750,000 at 25.12% -> 1,029.85 - 1,030.26
--     million, Galih Pandekar's 96,250,000 at 9.34% -> 1,029.96 - 1,031.07 million, every other holder alike. EPS FY2024
--     -8.6 (filed without its sign) -> 1,029.9 million, FY2025 -2.13 -> 1,031.5 million, H1 2026 -1.05 -> 1,029.9
--     million. Before 2023 (common stocks Rp 7,000,000,000, its listing on 2023-01-11) no count is stated.
--   * BOAT (PT Newport Marine Services Tbk, USD filer): 2,501,200,000 founders' shares, 3,501,680,000 from its IPO of
--     2024-11-12 (1,000,480,000 offered, par Rp 5: Rp 17,508,400,000; IDX listing announcement). KSEI 2026: Sujaya
--     Soekarno Putra's 1,000,400,000 at 28.57% -> 3,500.96 - 3,502.19 million, Sugiman Halim's 369,910,200 at 10.56%
--     -> 3,501.28 - 3,504.60 million. EPS (USD) FY2025 0.00058 -> 3,511.6 million (two digits), H1 2026 0.0001024 ->
--     3,501.0 - 3,504.5 million, 9M 2026 0.0003143 -> 3,501.7 - 3,502.8 million.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation snapshots from
-- them). Idempotent; does nothing until a company exists; only fills a share count that is still empty, so a count
-- derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
JOIN (VALUES ('BMBL', DATE '2023-12-31', 1030078165),
             ('BMBL', DATE '2024-12-31', 1030080995),
             ('BMBL', DATE '2025-06-30', 1030080995),
             ('BMBL', DATE '2025-12-31', 1030080995),
             ('BMBL', DATE '2026-06-30', 1030080995),
             ('BOAT', DATE '2023-12-31', 2501200000),
             ('BOAT', DATE '2024-11-12', 3501680000),
             ('BOAT', DATE '2024-12-31', 3501680000),
             ('BOAT', DATE '2025-06-30', 3501680000),
             ('BOAT', DATE '2025-09-30', 3501680000),
             ('BOAT', DATE '2025-12-31', 3501680000),
             ('BOAT', DATE '2026-06-30', 3501680000),
             ('BOAT', DATE '2026-09-30', 3501680000)) AS v(ticker, snapshot_date, shares)
  ON v.ticker = c.ticker
WHERE c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;
