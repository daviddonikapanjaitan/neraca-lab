-- =====================================================================
-- Neraca Lab - Portfolio > Analysis: the analysis steps of a run (instruction/INSTRUCTION.md)
--
--   portfolio_analysis_run.steps   FUNDAMENTAL_TECHNICAL: step 1 the Fundamental (Score), step 2 the Technical (Score)
--                                  of step 1's stocks, the fundamental then the technical agent (as before);
--                                  FUNDAMENTAL: step 1 and the fundamental agent only (no technical step or agent)
--
-- The runs stored before this column are FUNDAMENTAL_TECHNICAL (the default).
-- Executed on every application start by Spring SQL init, after V1.0.60. Every statement is idempotent.
-- =====================================================================

ALTER TABLE portfolio_analysis_run
    ADD COLUMN IF NOT EXISTS steps VARCHAR(30) NOT NULL DEFAULT 'FUNDAMENTAL_TECHNICAL';

ALTER TABLE portfolio_analysis_run
    DROP CONSTRAINT IF EXISTS ck_portfolio_analysis_steps,
    ADD  CONSTRAINT ck_portfolio_analysis_steps CHECK (steps IN ('FUNDAMENTAL', 'FUNDAMENTAL_TECHNICAL'));

COMMENT ON COLUMN portfolio_analysis_run.steps IS 'FUNDAMENTAL_TECHNICAL: the fundamental step then the technical step (and their agents); FUNDAMENTAL: the fundamental step and agent only (V1.0.61).';
