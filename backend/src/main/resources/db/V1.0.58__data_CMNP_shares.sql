-- =====================================================================
-- Neraca Lab - CMNP (PT Citra Marga Nusaphala Persada Tbk) share counts of 2025 - 2026 (PostgreSQL 15+)
--
-- CMNP's latest share count was 5,436,479,510 at 2023-12-31 (common stock 2,718,239,755 thousand / par Rp 500), so its
-- market cap left out the shares issued since (KSEI 2026-10-30: 6,696,042,918 - 6,696,721,311 from the four holders >=
-- 5%, e.g. 3,974,436,274 at 59.35%). Its FY2025, H1 2026 and 9M 2026 workbooks give no count: their EPS cells are
-- filed in thousands with too few decimals (0.00018 for FY2024, 0.00001 for H1 2025, 0.000072 for H1 2026) and fit
-- no par value. The count from two sources that agree:
--
--   * common stock Rp 3,348,177,195 thousand at 2025-12-31, 2026-06-30 and 2026-09-30 (the FY2025, H1 2026 and 9M
--     2026 workbooks) / par Rp 500 (the par of the FY2023 count) = 6,696,354,390 shares; no treasury stock filed
--   * KSEI's 1% list of 2026-10-30: 6,696,042,918 - 6,696,721,311
--
-- 2024-12-31 (common stock 3,007,000,582 thousand = 6,014,001,164 at Rp 500) is not stored: no KSEI list of that date
-- and no usable EPS confirms it. Runs on every start before V1.0.6; idempotent; only fills an empty count.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, d.snapshot_date, 6696354390, 0
FROM company c
CROSS JOIN (VALUES (DATE '2025-12-31'), (DATE '2026-06-30'), (DATE '2026-09-30')) AS d(snapshot_date)
WHERE c.ticker = 'CMNP' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;
