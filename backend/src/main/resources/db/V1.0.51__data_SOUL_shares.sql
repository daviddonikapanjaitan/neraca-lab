-- =====================================================================
-- Neraca Lab - SOUL (PT Mitra Tirta Buwana Tbk) share count after its IPO (PostgreSQL 15+)
--
-- SOUL's filings stop at FY2023 (its FY2024 workbook is not IDX's XBRL template, error 2052; IDX has no FY2025 or
-- 2026 filing), and their share counts end at 2022-12-31: 812,500,000 shares, the count before its initial public
-- offering. The market cap therefore used the pre-IPO count (valuation_check.py, 2026-11-08: -24.9% vs KSEI).
--
--   * 1,082,500,000 shares from 2023-01-06: + 270,000,000 new shares of the offering at par Rp 20, 24.94% of the issued
--     and paid-up capital after it (270,000,000 / 1,082,500,000 = 24.94%), listed on IDX on 2023-01-06 (offering
--     2 - 4 January 2023, price Rp 110, Rp 29.7 billion; Bisnis.com 2022-12-19 / 2023-01-02, IDX Channel, Kontan
--     2023-01-06). KSEI's 1% list of 2026-10-30 implies 1,082,487,229 (holders >= 5%, median): the 67,500,000
--     series I warrants issued with the offering were not exercised in any number.
--   * no treasury stock stated.
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql. Idempotent; does nothing until SOUL exists; only fills
-- a share count that is still empty, so a count derived from a filing is never replaced.
-- =====================================================================

INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding, treasury_shares)
SELECT c.company_id, DATE '2023-01-06', 1082500000, 0
FROM company c
WHERE c.ticker = 'SOUL' AND c.exchange = 'IDX'
ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
    shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
    treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)
WHERE share_snapshot.shares_outstanding IS NULL OR share_snapshot.treasury_shares IS NULL;

INSERT INTO corporate_action (company_id, action_date, action_type, shares_issued, cash_raised, description)
SELECT c.company_id, DATE '2023-01-06', 'IPO', 270000000, 29700000000,
       'Recorded from public sources: initial public offering of 270000000 shares at Rp 110 (24.94% of the capital '
       || 'after it), listed on IDX 2023-01-06 (812500000 -> 1082500000 shares).'
FROM company c
WHERE c.ticker = 'SOUL' AND c.exchange = 'IDX'
  AND NOT EXISTS (SELECT 1 FROM corporate_action ca
                  WHERE ca.company_id = c.company_id AND ca.action_type = 'IPO');
