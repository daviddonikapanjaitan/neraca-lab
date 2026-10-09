-- =====================================================================
-- Neraca Lab - screening of selected stocks (PostgreSQL 15+)
--
--   screening_run.tickers          the stocks the user picked from the companies table (Screening >
--                                  Selected Stocks), e.g. ["BBCA","BBRI","TLKM"]; NULL for a screening
--                                  of a market-cap tier.
--   screening_run.market_cap_tier  now NULL for a screening of selected stocks (their market caps differ).
--
-- A run screens either a tier or a selection: ck_screening_run_scope.
--
-- Executed on every application start by Spring SQL init, after V1.0.16. Every statement is idempotent.
-- =====================================================================

ALTER TABLE screening_run ADD COLUMN IF NOT EXISTS tickers JSONB;

ALTER TABLE screening_run ALTER COLUMN market_cap_tier DROP NOT NULL;

ALTER TABLE screening_run
    DROP CONSTRAINT IF EXISTS ck_screening_run_scope,
    ADD  CONSTRAINT ck_screening_run_scope
        CHECK ((market_cap_tier IS NULL) <> (tickers IS NULL));

COMMENT ON COLUMN screening_run.tickers IS
    'Selected stocks of a screening of selected stocks, e.g. ["BBCA","BBRI"]; NULL when a market-cap tier is screened.';
COMMENT ON COLUMN screening_run.market_cap_tier IS
    'LARGE, MID or SMALL; NULL for a screening of selected stocks (tickers).';
