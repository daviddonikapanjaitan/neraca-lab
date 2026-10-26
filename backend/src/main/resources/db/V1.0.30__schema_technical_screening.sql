-- =====================================================================
-- Neraca Lab - AI technical screening (PostgreSQL 15+)
--
--   listing_price_daily               daily bars of every listing of the screening universe (stock_listing), from
--                                     Yahoo Finance (Ingestion > Screening Data IDX, job type LISTING_PRICES); the
--                                     companies of the companies table keep theirs in price_daily
--   technical_screening_run           one technical screening (Screening > Technicals (All) of a market-cap tier,
--                                     Technicals (Selected) of stocks of the companies table): parameters, funnel,
--                                     market regime, synthesis, cost; run_id = ingestion_job id (TECHNICAL_SCREENING)
--   technical_screening_candidate     the Stage 1 shortlist: technicals, the four rules summaries, scores, rank and the
--                                     final trade plan (buy, sell, stop loss)
--   technical_screening_agent_score   score, stance, trade plan and reasoning of every technical agent per candidate
--                                     (Keith Gill, Murphy, Paul Tudor Jones, Druckenmiller)
--   technical_screening_lesson        Reflexion memory of the technical agents (separate from screening_lesson)
--   llm_usage.technical_screening_id  the model calls of a technical screening
--
-- The LISTING_PRICES and TECHNICAL_SCREENING job types are listed in ck_ingestion_job_type by V1.0.10 and V1.0.14,
-- which re-create it on every start.
--
-- Executed on every application start by Spring SQL init, after V1.0.29. Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- listing_price_daily : daily bars of the screening universe
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS listing_price_daily (
    listing_id           BIGINT NOT NULL
        REFERENCES stock_listing(listing_id) ON DELETE CASCADE,
    trading_date         DATE NOT NULL,
    open_price           NUMERIC(20,4),
    high_price           NUMERIC(20,4),
    low_price            NUMERIC(20,4),
    close_price          NUMERIC(20,4) NOT NULL,
    adjusted_close       NUMERIC(20,4),
    volume               BIGINT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_listing_price_daily PRIMARY KEY (listing_id, trading_date),
    CONSTRAINT ck_listing_price_daily_close CHECK (close_price > 0),
    CONSTRAINT ck_listing_price_daily_range CHECK (high_price IS NULL OR low_price IS NULL OR high_price >= low_price),
    CONSTRAINT ck_listing_price_daily_volume CHECK (volume IS NULL OR volume >= 0)
);

COMMENT ON TABLE  listing_price_daily IS 'Daily bars of every listing of the screening universe (stock_listing) from Yahoo Finance, split-adjusted as traded, in the listing currency: the price movements of Screening > Technicals (All). Loaded by Ingestion > Screening Data IDX (job type LISTING_PRICES) and by a technical screening for the listings of its tier.';
COMMENT ON COLUMN listing_price_daily.adjusted_close IS 'Close adjusted for splits and cash dividends (Yahoo adjclose).';

-- ---------------------------------------------------------------------
-- technical_screening_run : one technical screening
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS technical_screening_run (
    run_id               UUID PRIMARY KEY
        REFERENCES ingestion_job(job_id) ON DELETE CASCADE,
    exchange             VARCHAR(50) NOT NULL,
    market_cap_tier      VARCHAR(10),
    tickers              JSONB,
    top_n                INTEGER NOT NULL,
    agents               JSONB NOT NULL,
    as_of_date           DATE,
    universe_count       INTEGER,
    eligible_count       INTEGER,
    shortlist_count      INTEGER,
    selected_count       INTEGER,
    funnel               JSONB,
    market               JSONB,
    synthesis            JSONB,
    notes                JSONB,
    budget_usd           NUMERIC(12,6),
    cost_usd             NUMERIC(14,8) NOT NULL DEFAULT 0,
    prompt_tokens        BIGINT NOT NULL DEFAULT 0,
    completion_tokens    BIGINT NOT NULL DEFAULT 0,
    reasoning_tokens     BIGINT NOT NULL DEFAULT 0,
    cached_tokens        BIGINT NOT NULL DEFAULT 0,
    model_calls          INTEGER NOT NULL DEFAULT 0,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_technical_screening_run_tier
        CHECK (market_cap_tier IS NULL OR market_cap_tier IN ('LARGE', 'MID', 'SMALL')),
    CONSTRAINT ck_technical_screening_run_scope CHECK ((market_cap_tier IS NULL) <> (tickers IS NULL)),
    CONSTRAINT ck_technical_screening_run_top_n CHECK (top_n BETWEEN 1 AND 100)
);

COMMENT ON TABLE  technical_screening_run IS 'One AI technical screening (Screening > Technicals): a market-cap tier of the listings (tier) or stocks of the companies table (tickers); Stage 1 rules, the technical agents, Reflection, synthesis. Status and progress are in ingestion_job (same id).';
COMMENT ON COLUMN technical_screening_run.market IS 'The market the agents saw: IHSG against its 200-day average and the breadth of the universe.';
COMMENT ON COLUMN technical_screening_run.synthesis IS 'Final synthesis: executive summary, market notes, model, whether the fallback was used.';

-- ---------------------------------------------------------------------
-- technical_screening_candidate : the shortlist of a run
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS technical_screening_candidate (
    candidate_id         BIGSERIAL PRIMARY KEY,
    run_id               UUID NOT NULL
        REFERENCES technical_screening_run(run_id) ON DELETE CASCADE,
    ticker               VARCHAR(20) NOT NULL,
    company_name         VARCHAR(255) NOT NULL,
    sector               VARCHAR(150),
    close_price          NUMERIC(20,4),
    as_of_date           DATE,
    market_cap           NUMERIC(28,4),
    metrics              JSONB NOT NULL,
    rules                JSONB NOT NULL,
    prior_overall        NUMERIC(7,3),
    prior_rank           INTEGER,
    overall_score        NUMERIC(7,3),
    synthesis_adjustment NUMERIC(7,3),
    final_rank           INTEGER,
    selected             BOOLEAN NOT NULL DEFAULT FALSE,
    action               VARCHAR(20),
    conviction           VARCHAR(10),
    plan                 JSONB,
    thesis               TEXT,
    red_flags            JSONB,

    CONSTRAINT uq_technical_screening_candidate UNIQUE (run_id, ticker),
    CONSTRAINT ck_technical_screening_candidate_action
        CHECK (action IS NULL OR action IN ('BUY', 'ACCUMULATE', 'WAIT', 'AVOID')),
    CONSTRAINT ck_technical_screening_candidate_conviction
        CHECK (conviction IS NULL OR conviction IN ('HIGH', 'MEDIUM', 'LOW'))
);

CREATE INDEX IF NOT EXISTS ix_technical_screening_candidate_run ON technical_screening_candidate (run_id, final_rank);

COMMENT ON COLUMN technical_screening_candidate.metrics IS 'Key technicals of the stock as of the run (trend, momentum, volatility, volume, relative strength, 52-week range).';
COMMENT ON COLUMN technical_screening_candidate.rules IS 'The rules summaries of the four presets (Companies > Technicals): stance, score, buy / sell / stop and the evidence.';
COMMENT ON COLUMN technical_screening_candidate.prior_overall IS 'Stage 1: the average of the selected agents'' rules scores (0 - 100).';
COMMENT ON COLUMN technical_screening_candidate.plan IS 'The final trade plan: buy (price and LIMIT / STOP), sell (target), stop loss, reward / risk, and who set it (the synthesis, else the best agent).';

-- ---------------------------------------------------------------------
-- technical_screening_agent_score : one technical agent's view of one candidate
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS technical_screening_agent_score (
    score_id             BIGSERIAL PRIMARY KEY,
    candidate_id         BIGINT NOT NULL
        REFERENCES technical_screening_candidate(candidate_id) ON DELETE CASCADE,
    agent                VARCHAR(20) NOT NULL,
    prior_score          NUMERIC(7,3),
    llm_score            NUMERIC(7,3),
    final_score          NUMERIC(7,3),
    stance               VARCHAR(20),
    thesis               TEXT,
    supports             JSONB,
    risks                JSONB,
    plan                 JSONB,
    reflection           JSONB,
    status               VARCHAR(20) NOT NULL,

    CONSTRAINT uq_technical_screening_agent_score UNIQUE (candidate_id, agent),
    CONSTRAINT ck_technical_screening_agent_score_agent
        CHECK (agent IN ('GILL', 'MURPHY', 'TUDOR_JONES', 'DRUCKENMILLER')),
    CONSTRAINT ck_technical_screening_agent_score_stance
        CHECK (stance IS NULL OR stance IN ('BUY', 'ACCUMULATE', 'WAIT', 'AVOID')),
    CONSTRAINT ck_technical_screening_agent_score_status
        CHECK (status IN ('ASSESSED', 'REVISED', 'PRIOR_ONLY'))
);

COMMENT ON COLUMN technical_screening_agent_score.prior_score IS 'The agent''s rules summary as 0 - 100: 50 + 50 x preset score / its maximum.';
COMMENT ON COLUMN technical_screening_agent_score.final_score IS 'Blend of prior_score and llm_score (neracalab.technical-screening.llm-weight); prior_score alone without a model answer (PRIOR_ONLY).';
COMMENT ON COLUMN technical_screening_agent_score.plan IS 'The agent''s trade plan: buy, sell, stop loss, reward / risk and its source (AI: the agent''s own valid levels, RULES: the preset''s).';

-- ---------------------------------------------------------------------
-- technical_screening_lesson : Reflexion memory of the technical agents
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS technical_screening_lesson (
    lesson_id            BIGSERIAL PRIMARY KEY,
    agent                VARCHAR(20) NOT NULL,
    lesson               VARCHAR(500) NOT NULL,
    occurrences          INTEGER NOT NULL DEFAULT 1,
    source_run           UUID,
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_technical_screening_lesson UNIQUE (agent, lesson),
    CONSTRAINT ck_technical_screening_lesson_agent
        CHECK (agent IN ('GILL', 'MURPHY', 'TUDOR_JONES', 'DRUCKENMILLER'))
);

COMMENT ON TABLE technical_screening_lesson IS 'Reflexion memory of the technical agents: an issue kind the validator found in an agent''s answers becomes a lesson added to its later prompts (set active = false to drop one).';

-- ---------------------------------------------------------------------
-- llm_usage : the model calls of a technical screening
-- ---------------------------------------------------------------------
ALTER TABLE llm_usage
    ADD COLUMN IF NOT EXISTS technical_screening_id UUID REFERENCES technical_screening_run(run_id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS ix_llm_usage_technical_screening ON llm_usage (technical_screening_id);

COMMENT ON COLUMN llm_usage.technical_screening_id IS 'The technical screening of the call (run_id, analysis_id and macro_analysis_id are then null).';
