-- =====================================================================
-- Neraca Lab - ELTY (PT Bakrieland Development Tbk) share count (PostgreSQL 15+)
--
-- ELTY's common stock of Rp 4,912,191 million (unchanged in every workbook from FY2021 to 9M 2026) is two series:
-- 1,400,000,000 Seri A shares at Rp 500 and 42,121,913,019 Seri B shares at Rp 100 (700,000,000,000 +
-- 4,212,191,301,900 = 4,912,191,301,900). Its 9M 2026 upload inferred one par value, Rp 100, from the coarse 9M 2025
-- EPS (-0.29 for -14,199 million: 48.1 - 49.8 billion shares) and stored 4,912,191 million / Rp 100 = 49,121,910,000
-- shares at 2025-09-30, 2025-12-31 and 2026-09-30 (and the snapshot of 2024-12-31): 12.9% more than ELTY has, so the
-- market cap was 12.9% too high. The count, from three sources that agree:
--
--   * 43,521,913,019 shares (lembarsaham.com, ELTY)
--   * KSEI's 1% lists of 2026-08-31 to 2026-10-30: PT Asuransi Simas Jiwa 2,329,095,649 at 5.35% (43,493.8 - 43,575.2
--     million), Interventures Capital 2,227,645,000 at 5.12% (43,466.2 - 43,551.2 million), Bank of Singapore
--     1,510,156,800 at 3.47%: 43,493.8 - 43,551.2 million
--   * the share capital above: 1,400,000,000 x Rp 500 + 42,121,913,019 x Rp 100 = Rp 4,912,191 million as filed
--
-- No treasury stock. The share capital did not change from 2021 to 2026-09-30, so the count is stored at every year
-- end since 2021-12-31 (filling empty dates) and replaces the wrong 49,121,910,000 wherever it was stored. Listed in
-- IngestionRepository.SHARE_SCRIPTS: an upload of the 9M 2026 filing would infer Rp 100 again.
-- Idempotent; does nothing until ELTY exists.
-- =====================================================================

UPDATE share_snapshot s
SET shares_outstanding = 43521913019
FROM company c
WHERE c.company_id = s.company_id AND c.ticker = 'ELTY' AND c.exchange = 'IDX'
  AND s.shares_outstanding = 49121910000;

UPDATE share_snapshot s
SET basic_shares = 43521913019
FROM company c
WHERE c.company_id = s.company_id AND c.ticker = 'ELTY' AND c.exchange = 'IDX'
  AND s.basic_shares = 49121910000;

UPDATE balance_sheet b
SET shares_outstanding = 43521913019
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.company_id = b.company_id AND rp.period_id = b.period_id
  AND c.ticker = 'ELTY' AND c.exchange = 'IDX'
  AND b.shares_outstanding = 49121910000;

UPDATE income_statement i
SET basic_shares = 43521913019
FROM reporting_period rp
JOIN company c ON c.company_id = rp.company_id
WHERE rp.company_id = i.company_id AND rp.period_id = i.period_id
  AND c.ticker = 'ELTY' AND c.exchange = 'IDX'
  AND i.basic_shares = 49121910000;

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, d.snapshot_date, 43521913019, 0
FROM company c
CROSS JOIN (VALUES (DATE '2021-12-31'), (DATE '2022-12-31'), (DATE '2023-12-31'), (DATE '2024-12-31'),
                   (DATE '2025-12-31')) AS d(snapshot_date)
WHERE c.ticker = 'ELTY' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;
