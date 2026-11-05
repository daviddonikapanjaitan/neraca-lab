-- =====================================================================
-- Neraca Lab - share counts proven by the share capital and KSEI (PostgreSQL 15+)
--
-- Found by the valuation gap check of 2026-11-05: these companies had prices but no share count (no valuation at all).
-- Their filings' EPS does not let FilingMapper.inferParValue pick one par value (a par outside STANDARD_PAR_VALUES, or
-- a weighted-average EPS after a share issue), and Yahoo Finance has no count at the filings' dates.
--
-- Each count = the filed share capital ('Common stocks', statement of changes in equity, end of the period) / a par
-- value that divides it exactly AND lands inside the issued count KSEI's list of holders >= 1% gives on 2026-10-30
-- (every holder's shares / percent, the percent's 2 decimals giving a range; the ranges intersected), e.g. DOSS
-- 69,000,000,000 / Rp 40 = 1,725,000,000 (KSEI 1,724,890,830 - 1,725,150,877; 9M 2026 profit 35,764,919,157 / EPS 20.73
-- = 1,725,273,476). Dates: since the share capital last changed (the count KSEI confirms), and earlier period ends only
-- where the filed EPS (profit to the parent / basic EPS) gives the same count within 2%.
--
--   par: MENN 10, PTDU 50, COAL 10, PPGL 22, DOSS 40, EMMI 50, ESTI 21, HAJJ 30, IDEA 40, KLAS 40, MINE 100, PLAN 80,
--        PSDN 175, RGAS 40, UFOE 40, VERN 80, WBSA 40, YOII 100.
--   MENN: its EPS cell holds the profit (no EPS check). EMMI, WBSA, YOII: the latest count only (share issues in 2025 -
--        2026). ESTI: from 2025-12-31 (its earlier IDR comparatives give 9,596,232,000 at another capital).
--   COAL (6,250,000,000) and PTDU (1,500,000,000): KSEI and the share capital agree exactly, but the filed EPS uses
--        about 4.25 billion / 966.6 million shares in every year - recorded in dev_context.
-- Not here (dev_context): treasury-stock holders with treasury shares of 3 - 25% (OPMS, CHEK, LABS, FAST) or an EPS above the issued count (TRIS), USD
-- filers (no rupiah par: PMMP, INRU, POLY, CANI, ERTX, GDYR, LEAD, OKAS, TBMS), capital fitting no single par (ZBRA,
-- BIMA, INCF, IBFN, CASH, ICON, KBLM, KOPI, MERI, MGNA, OILS, PART, RBMS, SATU).
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent; fills only empty counts.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, 0
FROM company c
JOIN (VALUES
             ('MENN', DATE '2024-12-31', 1434052006),
             ('MENN', DATE '2025-12-31', 1434052006),
             ('PTDU', DATE '2021-12-31', 1500000000),
             ('PTDU', DATE '2022-12-31', 1500000000),
             ('PTDU', DATE '2023-12-31', 1500000000),
             ('PTDU', DATE '2024-12-31', 1500000000),
             ('COAL', DATE '2022-12-31', 6250000000),
             ('COAL', DATE '2023-12-31', 6250000000),
             ('COAL', DATE '2024-12-31', 6250000000),
             ('COAL', DATE '2025-12-31', 6250000000),
             ('PPGL', DATE '2021-12-31', 750021341),
             ('PPGL', DATE '2022-12-31', 771178020),
             ('PPGL', DATE '2023-12-31', 771178020),
             ('PPGL', DATE '2024-12-31', 771178020),
             ('PPGL', DATE '2025-06-30', 771178020),
             ('PPGL', DATE '2025-12-31', 771178020),
             ('PPGL', DATE '2026-06-30', 771178020),
             ('DOSS', DATE '2024-12-31', 1725000000),
             ('DOSS', DATE '2025-06-30', 1725000000),
             ('DOSS', DATE '2025-09-30', 1725000000),
             ('DOSS', DATE '2025-12-31', 1725000000),
             ('DOSS', DATE '2026-06-30', 1725000000),
             ('DOSS', DATE '2026-09-30', 1725000000),
             ('EMMI', DATE '2026-09-30', 1742857000),
             ('ESTI', DATE '2025-12-31', 2015208720),
             ('ESTI', DATE '2026-06-30', 2015208720),
             ('ESTI', DATE '2026-09-30', 2015208720),
             ('HAJJ', DATE '2021-12-31', 1603400000),
             ('HAJJ', DATE '2022-12-31', 1603400000),
             ('HAJJ', DATE '2023-12-31', 2290513056),
             ('HAJJ', DATE '2024-12-31', 2290536745),
             ('HAJJ', DATE '2025-06-30', 2468272953),
             ('HAJJ', DATE '2025-09-30', 2468282115),
             ('HAJJ', DATE '2025-12-31', 2468293035),
             ('HAJJ', DATE '2026-06-30', 2468527572),
             ('HAJJ', DATE '2026-09-30', 2468527572),
             ('IDEA', DATE '2021-12-31', 1062437500),
             ('IDEA', DATE '2022-12-31', 1062437500),
             ('IDEA', DATE '2023-12-31', 1062437500),
             ('IDEA', DATE '2024-12-31', 1062437500),
             ('IDEA', DATE '2025-06-30', 1062437500),
             ('IDEA', DATE '2025-12-31', 1062437500),
             ('IDEA', DATE '2026-06-30', 1062437500),
             ('KLAS', DATE '2024-12-31', 3644415975),
             ('KLAS', DATE '2025-06-30', 3644415975),
             ('KLAS', DATE '2025-09-30', 3644415975),
             ('KLAS', DATE '2025-12-31', 3644415975),
             ('KLAS', DATE '2026-06-30', 3644415975),
             ('KLAS', DATE '2026-09-30', 3644415975),
             ('MINE', DATE '2025-06-30', 4084440000),
             ('MINE', DATE '2025-12-31', 4084440000),
             ('MINE', DATE '2026-06-30', 4084440000),
             ('PLAN', DATE '2021-12-31', 892856800),
             ('PLAN', DATE '2022-12-31', 892863180),
             ('PLAN', DATE '2023-12-31', 896709596),
             ('PLAN', DATE '2024-12-31', 896709596),
             ('PLAN', DATE '2025-06-30', 896709596),
             ('PLAN', DATE '2025-12-31', 896709596),
             ('PLAN', DATE '2026-06-30', 896709596),
             ('PSDN', DATE '2021-12-31', 1440000000),
             ('PSDN', DATE '2022-12-31', 1440000000),
             ('PSDN', DATE '2023-12-31', 1440000000),
             ('PSDN', DATE '2024-12-31', 1440000000),
             ('PSDN', DATE '2025-06-30', 1440000000),
             ('PSDN', DATE '2025-09-30', 1440000000),
             ('PSDN', DATE '2025-12-31', 1440000000),
             ('PSDN', DATE '2026-06-30', 1440000000),
             ('PSDN', DATE '2026-09-30', 1440000000),
             ('RGAS', DATE '2024-12-31', 1459234110),
             ('RGAS', DATE '2025-06-30', 1459234110),
             ('RGAS', DATE '2025-09-30', 1459234110),
             ('RGAS', DATE '2025-12-31', 1459234110),
             ('RGAS', DATE '2026-06-30', 1459234110),
             ('RGAS', DATE '2026-09-30', 1459234110),
             ('UFOE', DATE '2021-12-31', 2287500000),
             ('UFOE', DATE '2022-12-31', 2287500000),
             ('UFOE', DATE '2023-12-31', 2287500000),
             ('UFOE', DATE '2024-12-31', 2898261693),
             ('UFOE', DATE '2025-06-30', 2898261693),
             ('UFOE', DATE '2025-09-30', 2898261693),
             ('UFOE', DATE '2025-12-31', 2898261693),
             ('UFOE', DATE '2026-06-30', 2898261693),
             ('UFOE', DATE '2026-09-30', 2898261693),
             ('VERN', DATE '2024-12-31', 4765525000),
             ('VERN', DATE '2025-06-30', 4765525000),
             ('VERN', DATE '2025-09-30', 4765525000),
             ('VERN', DATE '2025-12-31', 4765525000),
             ('VERN', DATE '2026-06-30', 4765525000),
             ('VERN', DATE '2026-09-30', 4765525000),
             ('WBSA', DATE '2026-06-30', 8675000000),
             ('WBSA', DATE '2026-09-30', 8675000000),
             ('YOII', DATE '2026-09-30', 3926983180)
) AS v(ticker, snapshot_date, shares)
  ON v.ticker = c.ticker
WHERE c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

-- Treasury-stock holders whose filings state the treasury stock's cost but not its count (FilingMapper leaves their
-- shares outstanding empty). User's decision of 2026-11-05: the issued count (share capital / par = KSEI) is stored as
-- shares outstanding where the filed EPS shows the treasury shares under 2% of it - LPKR (9M 2026 EPS count
-- 70,869,050,343 vs 70,898,020,000 issued), PTPP (6,189,667,543 vs 6,199,897,354), WIKA (FY2025 39,872,532,526 vs
-- 39,873,063,860; FY2021 9,051,302,385 vs 8,969,951,370 - FY2022 / FY2023 left out, their EPS does not confirm it),
-- DPUM (4,174,690,402 vs 4,175,000,000), MCAS (866,897,319 vs 867,933,300). PANS and YULE: their Securities-taxonomy
-- filings (read since 2026-11-05) give exact counts with the treasury shares (PANS 711,428,800 + 8,571,200).
-- The treasury count stays unknown (NULL). Left empty (treasury 3 - 25% or an EPS above the issued count): OPMS,
-- CHEK, LABS, YULE, FAST, TRIS.
INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, v.shares, NULL
FROM company c
JOIN (VALUES
             ('LPKR', DATE '2021-12-31', 70898020000),
             ('LPKR', DATE '2022-12-31', 70898020000),
             ('LPKR', DATE '2023-12-31', 70898020000),
             ('LPKR', DATE '2024-12-31', 70898020000),
             ('LPKR', DATE '2025-06-30', 70898020000),
             ('LPKR', DATE '2025-09-30', 70898020000),
             ('LPKR', DATE '2025-12-31', 70898020000),
             ('LPKR', DATE '2026-06-30', 70898020000),
             ('LPKR', DATE '2026-09-30', 70898020000),
             ('PTPP', DATE '2021-12-31', 6199897354),
             ('PTPP', DATE '2022-12-31', 6199897354),
             ('PTPP', DATE '2023-12-31', 6199897354),
             ('PTPP', DATE '2024-12-31', 6199897354),
             ('PTPP', DATE '2025-06-30', 6199897354),
             ('PTPP', DATE '2025-09-30', 6199897354),
             ('PTPP', DATE '2025-12-31', 6199897354),
             ('PTPP', DATE '2026-06-30', 6199897354),
             ('PTPP', DATE '2026-09-30', 6199897354),
             ('WIKA', DATE '2021-12-31', 8969951370),
             ('WIKA', DATE '2024-12-31', 39873063860),
             ('WIKA', DATE '2025-06-30', 39873063860),
             ('WIKA', DATE '2025-09-30', 39873063860),
             ('WIKA', DATE '2025-12-31', 39873063860),
             ('WIKA', DATE '2026-06-30', 39873063860),
             ('WIKA', DATE '2026-09-30', 39873063860),
             ('DPUM', DATE '2021-12-31', 4175000000),
             ('DPUM', DATE '2022-12-31', 4175000000),
             ('DPUM', DATE '2023-12-31', 4175000000),
             ('DPUM', DATE '2024-12-31', 4175000000),
             ('DPUM', DATE '2025-06-30', 4175000000),
             ('DPUM', DATE '2025-12-31', 4175000000),
             ('DPUM', DATE '2026-06-30', 4175000000),
             ('MCAS', DATE '2021-12-31', 867933300),
             ('MCAS', DATE '2022-12-31', 867933300),
             ('MCAS', DATE '2023-12-31', 867933300),
             ('MCAS', DATE '2024-12-31', 867933300),
             ('MCAS', DATE '2025-06-30', 867933300),
             ('MCAS', DATE '2025-12-31', 867933300),
             ('MCAS', DATE '2026-06-30', 867933300)
) AS v(ticker, snapshot_date, shares)
  ON v.ticker = c.ticker
WHERE c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding)
WHERE share_snapshot.shares_outstanding IS NULL;
