-- =====================================================================
-- Neraca Lab - BNGA (PT Bank CIMB Niaga Tbk) share counts (PostgreSQL 15+)
--
-- BNGA has two share classes with different par values (class A Rp 5,000, class B Rp 50) and holds
-- treasury shares, and its basic EPS has 2 decimals, so its filings yield no share counts and BNGA had
-- no market cap / valuation. The counts below are the audited year-end counts of note 33 "Share
-- capital, additional paid-in capital, treasury shares" of the consolidated financial statements:
--
--   date        outstanding (excl. treasury)  treasury       issued
--   2021-12-31  24,929,713,961               201,892,882    25,131,606,843   (2023 annual report)
--   2022-12-31  24,933,123,961               198,482,882    25,131,606,843   (2023 annual report)
--   2023-12-31  25,024,439,161               107,167,682    25,131,606,843   (2023 and 2025 annual reports)
--   2024-12-31  25,137,965,543                 4,240,300    25,142,205,843   (2025 annual report)
--   2025-12-31  25,140,519,043                 1,686,800    25,142,205,843   (2025 annual report)
--
--   * issued = 71,853,936 class A + 25,059,752,907 class B; + 10,599,000 class B issued without
--     pre-emptive rights on 2024-01-31 (EGMS 2024-01-11). Matches the filed share capital exactly:
--     71,853,936 x 5,000 + 25,059,752,907 x 50 = Rp 1,612,257 million (FY2021 .. FY2023),
--     + 10,599,000 x 50 = Rp 1,612,787 million (FY2024 onwards); KSEI: 25,142,205,843 shares
--   * treasury shares (bought back for the MESOP / MRT programmes) at par match the annual reports'
--     share capital split (2025: 1,686,800 x Rp 50 = Rp 84 million)
--   * sources: https://investor.cimbniaga.co.id/misc/AR/AR-2023-EN.pdf and AR-2025-EN.pdf (note 33),
--     https://investor.cimbniaga.co.id/gcg/share_chronology.html
--
-- Dates after 2025-12-31 use the 2025-12-31 count (market_snapshot takes the latest count on or before
-- the trading date): the H1 2026 treasury stock (Rp 605 million at cost) changes it by under 0.01%.
-- Prices before 2021-12-31 get no market cap.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql (which derives market and valuation
-- snapshots from them) and after every upload. Idempotent; does nothing until BNGA exists (created by
-- its first upload); only fills share counts that are still empty, so a count derived from a filing is
-- never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, d.snapshot_date, d.shares_outstanding, d.treasury_shares
FROM company c
CROSS JOIN (VALUES
    (DATE '2021-12-31', 24929713961, 201892882),
    (DATE '2022-12-31', 24933123961, 198482882),
    (DATE '2023-12-31', 25024439161, 107167682),
    (DATE '2024-12-31', 25137965543,   4240300),
    (DATE '2025-12-31', 25140519043,   1686800)
) AS d (snapshot_date, shares_outstanding, treasury_shares)
WHERE c.ticker = 'BNGA' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;
