-- =====================================================================
-- Neraca Lab - share counts ten / a hundred times too large (PostgreSQL 15+)
--
-- Found by the valuation correctness check of 2026-11-05 (SHARE_GAP after Hermes' uploads): FilingMapper.inferParValue
-- fitted par Rp 1 to EPS figures these companies file in another scale, so the stored counts are the share capital /
-- Rp 1. The share capital ('Common stocks', statement of changes in equity) at the right par lands exactly inside the
-- issued count of KSEI's 1% list of 2026-10-30 (every holder's shares / percent, intersected):
--   * MKTR: Rp 10. 120,604,286,000 / 10 = 12,060,428,600 (KSEI 12,060,269,170 - 12,061,808,642); 2025-06-30
--     12,052,397,400, 2025-09-30 12,052,536,900 (stored x10 from 2025-06-30; the counts before were right).
--   * NAYZ: Rp 10. 25,500,114,720 / 10 = 2,550,011,472 (KSEI 2,549,940,337 - 2,550,586,635); 2023-12-31 2,550,007,264.
--   * VICO: Rp 100. 1,521,707,566,000 / 100 = 15,217,075,660 (KSEI 15,216,138,329 - 15,218,639,175), as stored at
--     2022-12-31 before the x100 counts from 2023-12-31.
--   * WAPO: Rp 100. 124,092,311,100 / 100 = 1,240,923,111 (KSEI 1,240,753,583 - 1,240,988,493).
--
-- Runs on every start before V1.0.6__data_metrics_valuation.sql and after every upload
-- (IngestionRepository.SHARE_SCRIPTS: a re-upload writes the filing's counts again). Idempotent; changes only the
-- exact wrong values (share_snapshot, balance_sheet.shares_outstanding, income_statement basic / diluted shares).
-- =====================================================================

UPDATE share_snapshot s SET shares_outstanding = f.fixed
FROM company c, (VALUES ('MKTR', 120523974000::numeric, 12052397400::numeric),
             ('MKTR', 120525369000, 12052536900),
             ('MKTR', 120604286000, 12060428600),
             ('NAYZ', 25500072640, 2550007264),
             ('NAYZ', 25500114720, 2550011472),
             ('VICO', 1521707566000, 15217075660),
             ('WAPO', 124092311100, 1240923111)) AS f(ticker, wrong, fixed)
WHERE c.company_id = s.company_id AND c.ticker = f.ticker AND c.exchange = 'IDX' AND s.shares_outstanding = f.wrong;

UPDATE share_snapshot s SET basic_shares = f.fixed
FROM company c, (VALUES ('MKTR', 120523974000::numeric, 12052397400::numeric),
             ('MKTR', 120525369000, 12052536900),
             ('MKTR', 120604286000, 12060428600),
             ('NAYZ', 25500072640, 2550007264),
             ('NAYZ', 25500114720, 2550011472),
             ('VICO', 1521707566000, 15217075660),
             ('WAPO', 124092311100, 1240923111)) AS f(ticker, wrong, fixed)
WHERE c.company_id = s.company_id AND c.ticker = f.ticker AND c.exchange = 'IDX' AND s.basic_shares = f.wrong;

UPDATE balance_sheet b SET shares_outstanding = f.fixed
FROM reporting_period rp, company c, (VALUES ('MKTR', 120523974000::numeric, 12052397400::numeric),
             ('MKTR', 120525369000, 12052536900),
             ('MKTR', 120604286000, 12060428600),
             ('NAYZ', 25500072640, 2550007264),
             ('NAYZ', 25500114720, 2550011472),
             ('VICO', 1521707566000, 15217075660),
             ('WAPO', 124092311100, 1240923111)) AS f(ticker, wrong, fixed)
WHERE rp.period_id = b.period_id AND c.company_id = rp.company_id AND c.ticker = f.ticker AND c.exchange = 'IDX'
  AND b.shares_outstanding = f.wrong;

UPDATE income_statement i SET basic_shares = f.fixed
FROM reporting_period rp, company c, (VALUES ('MKTR', 120523974000::numeric, 12052397400::numeric),
             ('MKTR', 120525369000, 12052536900),
             ('MKTR', 120604286000, 12060428600),
             ('NAYZ', 25500072640, 2550007264),
             ('NAYZ', 25500114720, 2550011472),
             ('VICO', 1521707566000, 15217075660),
             ('WAPO', 124092311100, 1240923111)) AS f(ticker, wrong, fixed)
WHERE rp.period_id = i.period_id AND c.company_id = rp.company_id AND c.ticker = f.ticker AND c.exchange = 'IDX'
  AND i.basic_shares = f.wrong;

UPDATE income_statement i SET diluted_shares = f.fixed
FROM reporting_period rp, company c, (VALUES ('MKTR', 120523974000::numeric, 12052397400::numeric),
             ('MKTR', 120525369000, 12052536900),
             ('MKTR', 120604286000, 12060428600),
             ('NAYZ', 25500072640, 2550007264),
             ('NAYZ', 25500114720, 2550011472),
             ('VICO', 1521707566000, 15217075660),
             ('WAPO', 124092311100, 1240923111)) AS f(ticker, wrong, fixed)
WHERE rp.period_id = i.period_id AND c.company_id = rp.company_id AND c.ticker = f.ticker AND c.exchange = 'IDX'
  AND i.diluted_shares = f.wrong;
