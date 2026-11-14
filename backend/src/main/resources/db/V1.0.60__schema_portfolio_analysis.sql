-- =====================================================================
-- Neraca Lab - Portfolio > Analysis (instruction/INSTRUCTION.md): AI recommendations for a user's portfolio
--
--   portfolio_analysis_run        one run (run_id = ingestion_job id, type PORTFOLIO_ANALYSIS): the user, the
--                                 universe (BULK: every company with a Fundamental (Score); SELECTED: tickers), the
--                                 maximum stocks to hold, the capital, the two rules steps' funnel, the synthesis,
--                                 the cash and sector allocation before / after, the checks, notes, tokens and cost
--   portfolio_analysis_candidate  every stock of the run: the current position (if held), step 1 (Fundamental
--                                 (Score), rank), step 2 (Technical (Score), rank), the fundamental and technical AI
--                                 agents' answers, the Kelly sizing and the final recommendation
--   portfolio_analysis_lesson     Reflexion memory of the two portfolio agents
--   llm_usage.portfolio_analysis_id  the model calls of a run
--
-- The PORTFOLIO_ANALYSIS job type is listed in ck_ingestion_job_type by V1.0.10 and V1.0.14, which re-create it on
-- every start.
--
-- Executed on every application start by Spring SQL init, after V1.0.59. Every statement is idempotent.
-- =====================================================================

CREATE TABLE IF NOT EXISTS portfolio_analysis_run (
    run_id              UUID PRIMARY KEY REFERENCES ingestion_job (job_id) ON DELETE CASCADE,
    user_id             BIGINT NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    exchange            VARCHAR(20) NOT NULL,
    mode                VARCHAR(10) NOT NULL CHECK (mode IN ('BULK', 'SELECTED')),
    tickers             JSONB,
    max_stocks          INTEGER NOT NULL CHECK (max_stocks BETWEEN 1 AND 100),
    added_capital       NUMERIC(20, 2) NOT NULL DEFAULT 0 CHECK (added_capital >= 0),
    budget_usd          NUMERIC(10, 4) NOT NULL,
    capital             JSONB,
    funnel              JSONB,
    synthesis           JSONB,
    allocation          JSONB,
    checks              JSONB,
    notes               JSONB,
    model_calls         INTEGER NOT NULL DEFAULT 0,
    prompt_tokens       BIGINT NOT NULL DEFAULT 0,
    completion_tokens   BIGINT NOT NULL DEFAULT 0,
    cost_usd            NUMERIC(14, 8) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_portfolio_analysis_scope CHECK ((mode = 'BULK') = (tickers IS NULL))
);

CREATE INDEX IF NOT EXISTS ix_portfolio_analysis_run_user ON portfolio_analysis_run (user_id, created_at DESC);

COMMENT ON TABLE portfolio_analysis_run IS 'Portfolio > Analysis: one AI run of a user (V1.0.60); run_id = ingestion_job.job_id.';
COMMENT ON COLUMN portfolio_analysis_run.mode IS 'BULK: every company with a Fundamental (Score) of Companies > Fundamentals; SELECTED: the tickers chosen.';
COMMENT ON COLUMN portfolio_analysis_run.max_stocks IS 'The most stocks the recommended portfolio may hold.';
COMMENT ON COLUMN portfolio_analysis_run.added_capital IS 'Money the user plans to add (IDR), invested with the cash.';
COMMENT ON COLUMN portfolio_analysis_run.capital IS 'The portfolio at the start: cash, market value, positions, total capital.';
COMMENT ON COLUMN portfolio_analysis_run.funnel IS 'Step 1 (fundamental) and step 2 (technical) counts and limits.';
COMMENT ON COLUMN portfolio_analysis_run.allocation IS 'Cash, stock and sector weights before and after the recommendations.';
COMMENT ON COLUMN portfolio_analysis_run.checks IS 'The limits checked on the result (stocks held, 33% per stock and per sector, cash).';

CREATE TABLE IF NOT EXISTS portfolio_analysis_candidate (
    candidate_id        BIGSERIAL PRIMARY KEY,
    run_id              UUID NOT NULL REFERENCES portfolio_analysis_run (run_id) ON DELETE CASCADE,
    ticker              VARCHAR(20) NOT NULL,
    company_name        VARCHAR(255),
    sector              VARCHAR(150),
    held                BOOLEAN NOT NULL DEFAULT FALSE,
    position            JSONB,
    close_price         NUMERIC(20, 4),
    price_date          DATE,
    fundamental_score   NUMERIC(6, 2),
    fundamental_verdict VARCHAR(20),
    fundamental_rank    INTEGER,
    technical_score     NUMERIC(6, 2),
    technical_stance    VARCHAR(20),
    technical_rank      INTEGER,
    stage               VARCHAR(30) NOT NULL,
    fundamental_ai      JSONB,
    technical_ai        JSONB,
    kelly               JSONB,
    action              VARCHAR(10),
    recommendation      JSONB,
    conviction          VARCHAR(10),
    thesis              TEXT,
    red_flags           JSONB,
    final_rank          INTEGER,
    CONSTRAINT uq_portfolio_analysis_candidate UNIQUE (run_id, ticker),
    CONSTRAINT ck_portfolio_analysis_stage CHECK (stage IN ('FUNDAMENTAL_OUT', 'TECHNICAL_OUT', 'FUNDAMENTAL_AI_OUT',
        'TECHNICAL_AI_OUT', 'RECOMMENDED', 'NOT_CHOSEN', 'HELD')),
    CONSTRAINT ck_portfolio_analysis_action CHECK (action IS NULL OR action IN ('BUY', 'INCREASE', 'HOLD', 'DECREASE',
        'SELL'))
);

CREATE INDEX IF NOT EXISTS ix_portfolio_analysis_candidate_run ON portfolio_analysis_candidate (run_id);

COMMENT ON TABLE portfolio_analysis_candidate IS 'A stock of a Portfolio > Analysis run (V1.0.60): its two rules steps, the two AI agents and the recommendation.';
COMMENT ON COLUMN portfolio_analysis_candidate.stage IS 'How far it went: FUNDAMENTAL_OUT / TECHNICAL_OUT (left out by step 1 / 2), FUNDAMENTAL_AI_OUT / TECHNICAL_AI_OUT (rejected by the AI agent), RECOMMENDED (bought or kept), NOT_CHOSEN (passed every step, not in the portfolio), HELD (a holding).';
COMMENT ON COLUMN portfolio_analysis_candidate.position IS 'The current position (shares, average price, market value, weight) of a held stock.';
COMMENT ON COLUMN portfolio_analysis_candidate.kelly IS 'Half-Kelly sizing: win probability (from the fundamental step), payoff (reward / risk of the technical plan), Kelly fraction, the weight used.';
COMMENT ON COLUMN portfolio_analysis_candidate.recommendation IS 'Target weight, target value, target shares, shares and lots to trade at the price, and the reason.';

CREATE TABLE IF NOT EXISTS portfolio_analysis_lesson (
    lesson_id     BIGSERIAL PRIMARY KEY,
    agent         VARCHAR(20) NOT NULL CHECK (agent IN ('FUNDAMENTAL', 'TECHNICAL')),
    issue_kind    VARCHAR(40) NOT NULL,
    lesson        TEXT NOT NULL,
    occurrences   INTEGER NOT NULL DEFAULT 1,
    first_run_id  UUID,
    last_run_id   UUID,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_portfolio_analysis_lesson UNIQUE (agent, issue_kind)
);

COMMENT ON TABLE portfolio_analysis_lesson IS 'Reflexion memory of the portfolio agents: an issue kind the validator found twice or more in a run becomes a lesson added to the agent''s later prompts (set active = false to drop one).';

ALTER TABLE llm_usage
    ADD COLUMN IF NOT EXISTS portfolio_analysis_id UUID REFERENCES portfolio_analysis_run (run_id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS ix_llm_usage_portfolio_analysis ON llm_usage (portfolio_analysis_id);

COMMENT ON COLUMN llm_usage.portfolio_analysis_id IS 'The Portfolio > Analysis run of the call (the other run columns are then null).';
