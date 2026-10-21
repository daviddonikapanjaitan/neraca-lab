-- =====================================================================
-- Neraca Lab - AI macro analysis of a country (PostgreSQL 15+)
--
--   macro_analysis_run          one AI macro analysis of a country's stock market (its index): agents, what the
--                               agents saw (the Macro page's rules, indexes, commodities, currency pairs), research
--                               brief, synthesis, investability score, cost; analysis_id = ingestion_job id (job
--                               type MACRO_ANALYSIS)
--   macro_analysis_agent_score  score and reasoning of every macro agent (Tudor Jones, Druckenmiller, Soros, Dalio)
--   macro_analysis_lesson       Reflexion memory of the macro agents (separate from screening_lesson)
--   llm_usage.macro_analysis_id the model calls of a macro analysis
--
-- The MACRO_ANALYSIS job type is listed in ck_ingestion_job_type by V1.0.10 and V1.0.14, which re-create it on every
-- start.
--
-- Executed on every application start by Spring SQL init, after V1.0.25. Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- macro_analysis_run : one analysis
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS macro_analysis_run (
    analysis_id          UUID PRIMARY KEY
        REFERENCES ingestion_job(job_id) ON DELETE CASCADE,
    country              VARCHAR(10) NOT NULL,
    country_name         VARCHAR(100),
    index_code           VARCHAR(20) NOT NULL,
    index_name           VARCHAR(100),
    agents               JSONB NOT NULL,
    budget_usd           NUMERIC(12,6),
    as_of_date           DATE,
    rules_score          NUMERIC(7,3),
    overall_score        NUMERIC(7,3),
    synthesis_adjustment NUMERIC(7,3),
    verdict              VARCHAR(20),
    action               VARCHAR(20),
    conviction           VARCHAR(10),
    context              JSONB,
    research             JSONB,
    synthesis            JSONB,
    notes                JSONB,
    cost_usd             NUMERIC(14,8) NOT NULL DEFAULT 0,
    prompt_tokens        BIGINT NOT NULL DEFAULT 0,
    completion_tokens    BIGINT NOT NULL DEFAULT 0,
    reasoning_tokens     BIGINT NOT NULL DEFAULT 0,
    cached_tokens        BIGINT NOT NULL DEFAULT 0,
    model_calls          INTEGER NOT NULL DEFAULT 0,

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_macro_analysis_run_verdict
        CHECK (verdict IS NULL OR verdict IN ('INVESTABLE', 'SELECTIVE', 'NOT_INVESTABLE')),
    CONSTRAINT ck_macro_analysis_run_action
        CHECK (action IS NULL OR action IN ('BUY', 'ACCUMULATE', 'WAIT', 'SELL')),
    CONSTRAINT ck_macro_analysis_run_conviction
        CHECK (conviction IS NULL OR conviction IN ('HIGH', 'MEDIUM', 'LOW'))
);

CREATE INDEX IF NOT EXISTS ix_macro_analysis_run_index ON macro_analysis_run (country, index_code);

COMMENT ON TABLE  macro_analysis_run IS 'AI macro analysis of a country''s stock market (Macro > AI Analysis): research agent over the stored macro series, commodities, currency pairs and indexes, four macro agents, Reflection, synthesis. Status and progress are those of its ingestion_job row.';
COMMENT ON COLUMN macro_analysis_run.context IS 'What the agents saw: the Macro page''s rules (regimes, series readings, presets, conclusion), the indexes, the commodities and currency pairs.';
COMMENT ON COLUMN macro_analysis_run.research IS 'Research agent: brief, ReAct steps and the tool results it read.';
COMMENT ON COLUMN macro_analysis_run.rules_score IS 'The Macro page''s rules investability score (0 - 100) as of the analysis.';
COMMENT ON COLUMN macro_analysis_run.overall_score IS 'Investability 0 - 100: the macro agents'' mean blended with the rules score (neracalab.macro-analysis.rules-weight), plus the synthesis adjustment (at most +-5).';
COMMENT ON COLUMN macro_analysis_run.action IS 'BUY, ACCUMULATE, WAIT or SELL from the overall score and the index against its 200-day average (the rules'' table).';

-- ---------------------------------------------------------------------
-- macro_analysis_agent_score : one macro agent's view
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS macro_analysis_agent_score (
    score_id             BIGSERIAL PRIMARY KEY,
    analysis_id          UUID NOT NULL
        REFERENCES macro_analysis_run(analysis_id) ON DELETE CASCADE,
    agent                VARCHAR(20) NOT NULL,
    prior_score          NUMERIC(7,3),
    prior_detail         JSONB,
    llm_score            NUMERIC(7,3),
    final_score          NUMERIC(7,3),
    stance               VARCHAR(20),
    thesis               TEXT,
    supports             JSONB,
    risks                JSONB,
    buy_when             JSONB,
    sell_when            JSONB,
    reflection           JSONB,
    status               VARCHAR(20) NOT NULL,

    CONSTRAINT uq_macro_analysis_agent_score UNIQUE (analysis_id, agent),
    CONSTRAINT ck_macro_analysis_agent_score_agent
        CHECK (agent IN ('TUDOR_JONES', 'DRUCKENMILLER', 'SOROS', 'DALIO')),
    CONSTRAINT ck_macro_analysis_agent_score_stance
        CHECK (stance IS NULL OR stance IN ('BUY', 'ACCUMULATE', 'WAIT', 'AVOID')),
    CONSTRAINT ck_macro_analysis_agent_score_status
        CHECK (status IN ('ASSESSED', 'REVISED', 'PRIOR_ONLY', 'NO_SCORE'))
);

COMMENT ON COLUMN macro_analysis_agent_score.prior_score IS 'The agent''s preset on the Macro page as 0 - 100: 50 + 50 x preset score / its number of signals.';
COMMENT ON COLUMN macro_analysis_agent_score.final_score IS 'Blend of prior_score and llm_score (neracalab.macro-analysis.prior-weight); llm_score alone without a prior; prior_score alone without a model answer (PRIOR_ONLY); null with neither (NO_SCORE).';

-- ---------------------------------------------------------------------
-- macro_analysis_lesson : Reflexion memory of the macro agents
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS macro_analysis_lesson (
    lesson_id            BIGSERIAL PRIMARY KEY,
    agent                VARCHAR(20) NOT NULL,
    lesson               VARCHAR(500) NOT NULL,
    occurrences          INTEGER NOT NULL DEFAULT 1,
    source_analysis      UUID,
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_macro_analysis_lesson UNIQUE (agent, lesson),
    CONSTRAINT ck_macro_analysis_lesson_agent
        CHECK (agent IN ('TUDOR_JONES', 'DRUCKENMILLER', 'SOROS', 'DALIO'))
);

COMMENT ON TABLE macro_analysis_lesson IS 'Reflexion memory of the macro agents: an issue kind the validator found in an agent''s answer becomes a lesson added to its later prompts (set active = false to drop one).';

-- ---------------------------------------------------------------------
-- llm_usage : the model calls of a macro analysis
-- ---------------------------------------------------------------------
ALTER TABLE llm_usage
    ADD COLUMN IF NOT EXISTS macro_analysis_id UUID REFERENCES macro_analysis_run(analysis_id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS ix_llm_usage_macro_analysis ON llm_usage (macro_analysis_id);

COMMENT ON COLUMN llm_usage.macro_analysis_id IS 'The macro analysis of the call (run_id and analysis_id are then null).';
