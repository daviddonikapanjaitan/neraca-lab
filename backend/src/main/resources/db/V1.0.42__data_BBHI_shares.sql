-- =====================================================================
-- Neraca Lab - BBHI share count at 2025-06-30 (PostgreSQL 15+)
--
-- PT Allo Bank Indonesia Tbk files its interim EPS annualized: H1 2025 profit to the parent 227,071,454,453 x 2 /
-- 21,730,256,442 shares = 20.90, the basic EPS filed (H1 2026: 233,673,888,981 x 2 / 21.73 billion = 21.51, filed
-- 21.5937). Its H1 2026 filing's par value inference therefore fitted Rp 200 instead of Rp 100 and stored
-- 2,173,025,644,200 / 200 = 10,865,128,221 shares at 2025-06-30 - half the count, so the market cap was halved.
-- Share capital 2,173,025,644,200 at par Rp 100 = 21,730,256,442 shares (FY2022 - FY2024 filings: EPS 12.43 / 20.46 /
-- 21.50 for annual profits give 21.73 billion; KSEI's 1% list of 2026-09-30: 21,730,256,288 issued); the H1 2026
-- filing shows no treasury stock at 2025-06-30.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql and after every upload (IngestionRepository.SHARE_SCRIPTS):
-- replaces only the exact wrong count, so an upload of the H1 2026 filing again is corrected too. Idempotent.
-- =====================================================================

UPDATE share_snapshot s
SET shares_outstanding = 21730256442
FROM company c
WHERE c.company_id = s.company_id
  AND c.ticker = 'BBHI' AND c.exchange = 'IDX'
  AND s.snapshot_date = DATE '2025-06-30'
  AND s.shares_outstanding = 10865128221;
