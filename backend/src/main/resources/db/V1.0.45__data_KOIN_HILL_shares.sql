-- =====================================================================
-- Neraca Lab - share counts of KOIN, HILL and FLMC (PostgreSQL 15+)
--
-- Found by the valuation correctness check of 2026-10-31 (evening): both companies' 9M / H1 2026 filings gave other
-- counts than the stored ones, and KSEI's 1% lists side with the new filings, not with the stored history.
--
--   * KOIN (PT Kokoh Inti Arebama Tbk): 980,843,732 shares. Share capital Rp 98,084 million in every filing of 2021 -
--     2026 (par Rp 100); the 9M 2026 filing's profit attributable to the parent 10,235 million / basic EPS 10.43 and its
--     9M 2025 column give 980,843,732 exactly; KSEI (February - September 2026): 980,839,205 (one holder >= 5%). The
--     annual workbooks file the EPS a hundred times too large (FY2021 -2,838 for -27,840 million / 980.8 million =
--     -28.38), so FilingMapper.inferParValue fitted Rp 10,000 and later Rp 10: 9,808,437 shares at 2020 - 2022 and
--     9,808,400,000 at 2023 - 2025, and the upload's split detection recorded a reverse split 100:1 at 2023-01-01
--     between them that never happened ('Restated in the filings: reverse split 100:1 (about 980857507 -> 9808575
--     shares)'). That corporate action is deleted (guarded on its exact description), the counts replaced.
--   * HILL (PT Hillcon Tbk): 14,741,500,000 shares at 2024-12-31 and 2025-12-31. Share capital Rp 294,830 million at
--     both dates (par Rp 20); the H1 2026 filing's count at 2025-12-31 and KSEI (February - September 2026: PT Hillcon
--     Equity Management's 5,472,759,400 at 37.12% -> 14,743 million; PT Bukit Persada Indonesia's 2,380,700,000 at
--     16.15% -> 14,741 million). The FY2024 / FY2025 filings' EPS (0.20, -35) fitted Rp 10: 29,483,000,000, twice the
--     count. 2023-12-31 (2,948,300,000 at par Rp 100, its FY2023 EPS 119 = 351,078 million / 2.95 billion) is left as
--     stored: a 1:5 split between then and 2024-12-31 is likely (the prices are split-adjusted), its date is not known.
--   * FLMC (PT Falmaco Nonwoven Industri Tbk): 781,250,000 shares. Share capital Rp 62,500,000,000 in every filing of
--     2021 - 2026 (par Rp 80); filed EPS FY2022 -23.32, FY2024 -54.33, FY2025 -29.39, H1 2026 -29.79 give 781.18, 781.24,
--     781.32, 781.20 million; KSEI (September 2026): Theresia Indra Wirawan's 308,477,875 at 39.49% -> 781.06 - 781.25
--     million. Its FY2022 - FY2025 workbooks file the profit in the EPS cell of the current column, which gave 1 share
--     (FilingMapper.sharesFromEps no longer takes it; the stored 1s were cleared on 2026-10-31): filled where empty.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql and after every upload (IngestionRepository.SHARE_SCRIPTS).
-- Idempotent; changes only the exact wrong values. The filed EPS figures are left as filed.
-- =====================================================================

DELETE FROM corporate_action ca
USING company c
WHERE ca.company_id = c.company_id AND c.ticker = 'KOIN' AND ca.action_type = 'REVERSE_SPLIT'
  AND ca.action_date = DATE '2023-01-01'
  AND ca.description LIKE 'Restated in the filings: reverse split 100:1 (about 980857507 -> 9808575 shares)%';

UPDATE share_snapshot ss SET shares_outstanding = v.shares
FROM company c, (VALUES ('KOIN', 9808437::numeric, 980843732::numeric),
                        ('KOIN', 9808400000, 980843732),
                        ('KOIN', 980840000, 980843732),
                        ('HILL', 29483000000, 14741500000)) AS v(ticker, wrong, shares)
WHERE ss.company_id = c.company_id AND c.ticker = v.ticker AND ss.shares_outstanding = v.wrong
  AND (c.ticker = 'KOIN' OR ss.snapshot_date IN (DATE '2024-12-31', DATE '2025-12-31'));

UPDATE balance_sheet b SET shares_outstanding = v.shares
FROM company c, reporting_period rp,
     (VALUES ('KOIN', 9808437::numeric, 980843732::numeric),
             ('KOIN', 9808400000, 980843732),
             ('HILL', 29483000000, 14741500000)) AS v(ticker, wrong, shares)
WHERE rp.company_id = c.company_id AND b.period_id = rp.period_id AND c.ticker = v.ticker
  AND b.shares_outstanding = v.wrong
  AND (c.ticker = 'KOIN' OR rp.period_end IN (DATE '2024-12-31', DATE '2025-12-31'));

UPDATE income_statement i SET basic_shares = v.shares
FROM company c, reporting_period rp,
     (VALUES ('KOIN', 9808437::numeric, 980843732::numeric),
             ('KOIN', 9808400000, 980843732),
             ('HILL', 29483000000, 14741500000)) AS v(ticker, wrong, shares)
WHERE rp.company_id = c.company_id AND i.period_id = rp.period_id AND c.ticker = v.ticker
  AND i.basic_shares = v.wrong
  AND (c.ticker = 'KOIN' OR rp.period_end IN (DATE '2024-12-31', DATE '2025-12-31'));

-- KOIN's counts at the dates its filings report and none is stored
INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, 980843732, 0
FROM company c
JOIN (VALUES (DATE '2026-09-30')) AS v(snapshot_date) ON c.ticker = 'KOIN'
ON CONFLICT (company_id, snapshot_date) DO UPDATE
  SET shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding);

-- FLMC's count at its year ends and its latest interim, where none is stored
INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, v.snapshot_date, 781250000, 0
FROM company c
JOIN (VALUES (DATE '2021-12-31'), (DATE '2022-12-31'), (DATE '2023-12-31'), (DATE '2024-12-31'), (DATE '2025-06-30'),
             (DATE '2025-12-31'), (DATE '2026-06-30')) AS v(snapshot_date) ON c.ticker = 'FLMC'
ON CONFLICT (company_id, snapshot_date) DO UPDATE
  SET shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding);

UPDATE balance_sheet b SET shares_outstanding = 781250000
FROM company c, reporting_period rp
WHERE c.ticker = 'FLMC' AND rp.company_id = c.company_id AND b.period_id = rp.period_id
  AND rp.period_end >= DATE '2021-12-31' AND (b.shares_outstanding IS NULL OR b.shares_outstanding = 1);
