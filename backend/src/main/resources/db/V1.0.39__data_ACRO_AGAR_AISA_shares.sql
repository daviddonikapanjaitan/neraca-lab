-- =====================================================================
-- Neraca Lab - share counts of ACRO, AGAR and AISA (PostgreSQL 15+)
--
-- None of the three filings states a share count, and no Yahoo Finance count fits them (ACRO and AGAR: none at the
-- dates of the filings; AISA: a count 2.45 times smaller than its EPS gives), so they had prices but no market cap
-- and no valuation. The counts below come from KSEI's monthly lists of the shareholders of more than 1%
-- (ownership_one_holder: a holder's shares / its percentage, February - September 2026) and the listing records.
--
--   * ACRO (PT Samcro Hyosung Adilestari Tbk): 3,469,138,591 shares since its IPO, listed on IDX 2024-01-11
--     (2,775,310,591 founders' shares, 676,625,000 offered, 17,203,000 ESA; par Rp 20). KSEI: Tae Sung Chung's
--     2,720,072,495 shares at 78.40% (78.395 - 78.405) -> 3,469.26 - 3,469.70 million in 2026, 0.12 - 0.56 million
--     (at most 0.016%) above the listing: Series I warrants exercised since (231,276,000 prelisted; the share capital
--     rose by Rp 932,840 = 46,642 shares in FY2025), not published one by one, so the listing count is used. Its
--     prices start at the listing, so no count before it.
--     FY2024 EPS 2.63: 9,112,274,242 / 3,469,138,591 = 2.63.
--   * AGAR (PT Asia Sejahtera Mina Tbk): 1,000,000,000 shares of Rp 100 since its IPO of 2019-12-02 (250,000,000
--     offered); KSEI: exactly 1,000,000,000 at every 2026 list. Its filed EPS does not fit any one count (FY2023
--     0.1656 for a profit of 17,275,909; FY2025 1.78 for 2,036,205,163), so the filing's are not used.
--   * AISA (PT FKS Food Sejahtera Tbk): 9,311,800,000 shares; KSEI: 9,311.2 - 9,312.6 million at every 2026 list;
--     every EPS from FY2021 to H1 2026 gives 9.28 - 9.31 billion (FY2024 69,472,000,000 / 7.46 = 9,312,600,536,
--     filed in millions); the quasi-reorganisation of 2026 lowered the par value, not the number of shares.
--   * no treasury stock stated for any of them
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation snapshots from
-- them) and after every upload. Idempotent; does nothing until a company exists; only fills a share count that is
-- still empty, so a count derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
JOIN (VALUES ('ACRO', DATE '2024-01-11', 3469138591),
             ('ACRO', DATE '2024-12-31', 3469138591),
             ('ACRO', DATE '2025-06-30', 3469138591),
             ('ACRO', DATE '2025-12-31', 3469138591),
             ('ACRO', DATE '2026-06-30', 3469138591),
             ('AGAR', DATE '2021-12-31', 1000000000),
             ('AGAR', DATE '2022-12-31', 1000000000),
             ('AGAR', DATE '2023-12-31', 1000000000),
             ('AGAR', DATE '2024-12-31', 1000000000),
             ('AGAR', DATE '2025-06-30', 1000000000),
             ('AGAR', DATE '2025-12-31', 1000000000),
             ('AGAR', DATE '2026-06-30', 1000000000),
             ('AISA', DATE '2021-12-31', 9311800000),
             ('AISA', DATE '2022-12-31', 9311800000),
             ('AISA', DATE '2023-12-31', 9311800000),
             ('AISA', DATE '2024-12-31', 9311800000),
             ('AISA', DATE '2025-06-30', 9311800000),
             ('AISA', DATE '2025-12-31', 9311800000),
             ('AISA', DATE '2026-06-30', 9311800000)) AS v(ticker, snapshot_date, shares)
  ON v.ticker = c.ticker
WHERE c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;
