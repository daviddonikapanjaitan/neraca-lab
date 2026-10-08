-- =====================================================================
-- Neraca Lab - AI analysis of one stock (PostgreSQL 15+)
--
--   analysis_run          one analysis of one company (agents, what the agents saw, research brief,
--                         synthesis, overall score, cost); analysis_id = ingestion_job id (job type ANALYSIS)
--   analysis_agent_score  score and reasoning of every investor agent
--   llm_usage.analysis_id the model calls of an analysis (llm_usage.run_id stays the screening run)
--
-- The ANALYSIS job type is listed in ck_ingestion_job_type by V1.0.10 and V1.0.14, which re-create it on
-- every start. Lessons learned go to screening_lesson (shared Reflexion memory of the investor agents).
--
-- Executed on every application start by Spring SQL init, after V1.0.14. Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- analysis_run : one analysis
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS analysis_run (
    analysis_id          UUID PRIMARY KEY
        REFERENCES ingestion_job(job_id) ON DELETE CASCADE,
    company_id           BIGINT NOT NULL
        REFERENCES company(company_id) ON DELETE CASCADE,
    exchange             VARCHAR(50) NOT NULL,
    ticker               VARCHAR(20) NOT NULL,
    company_name         VARCHAR(255),
    agents               JSONB NOT NULL,
    budget_usd           NUMERIC(12,6),
    market_data_date     DATE,
    quant_overall        NUMERIC(7,3),
    overall_score        NUMERIC(7,3),
    synthesis_adjustment NUMERIC(7,3),
    verdict              VARCHAR(20),
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

    CONSTRAINT ck_analysis_run_verdict
        CHECK (verdict IS NULL OR verdict IN ('STRONG_FIT', 'FIT', 'NEUTRAL', 'WEAK', 'REJECT')),
    CONSTRAINT ck_analysis_run_conviction
        CHECK (conviction IS NULL OR conviction IN ('HIGH', 'MEDIUM', 'LOW'))
);

CREATE INDEX IF NOT EXISTS ix_analysis_run_company ON analysis_run (company_id);

COMMENT ON TABLE  analysis_run IS 'AI analysis of one stock (Screening > Analysis): research agent over the company''s stored filings and news, investor agents, Reflection, synthesis. Status and progress are those of its ingestion_job row.';
COMMENT ON COLUMN analysis_run.context IS 'What the agents saw: the fact sheet from the stored statements, metrics, prices and valuations, the Yahoo Finance market data and the stored documents.';
COMMENT ON COLUMN analysis_run.research IS 'Research agent: brief, ReAct steps and the document chunks it retrieved.';
COMMENT ON COLUMN analysis_run.overall_score IS 'Average of the investor agents blended with the Risk agent (neracalab.screening.risk-weight), plus the synthesis adjustment (at most +-5).';
COMMENT ON COLUMN analysis_run.quant_overall IS 'The same blend of the quantitative scorecards alone (null without market data).';

-- ---------------------------------------------------------------------
-- analysis_agent_score : one investor agent's view
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS analysis_agent_score (
    score_id             BIGSERIAL PRIMARY KEY,
    analysis_id          UUID NOT NULL
        REFERENCES analysis_run(analysis_id) ON DELETE CASCADE,
    agent                VARCHAR(20) NOT NULL,
    quant_score          NUMERIC(7,3),
    quant_detail         JSONB,
    llm_score            NUMERIC(7,3),
    final_score          NUMERIC(7,3),
    verdict              VARCHAR(20),
    thesis               TEXT,
    strengths            JSONB,
    concerns             JSONB,
    reflection           JSONB,
    status               VARCHAR(20) NOT NULL,

    CONSTRAINT uq_analysis_agent_score UNIQUE (analysis_id, agent),
    CONSTRAINT ck_analysis_agent_score_agent
        CHECK (agent IN ('BUFFETT', 'MUNGER', 'LYNCH', 'FISHER', 'GILL', 'RISK')),
    CONSTRAINT ck_analysis_agent_score_status
        CHECK (status IN ('ASSESSED', 'REVISED', 'QUANT_ONLY', 'NO_SCORE'))
);

COMMENT ON COLUMN analysis_agent_score.final_score IS 'Blend of quant_score and llm_score (neracalab.screening.llm-weight); llm_score alone without market data; quant_score alone without a model answer (QUANT_ONLY); null with neither (NO_SCORE).';

-- ---------------------------------------------------------------------
-- llm_usage : the model calls of an analysis
-- ---------------------------------------------------------------------
ALTER TABLE llm_usage
    ADD COLUMN IF NOT EXISTS analysis_id UUID REFERENCES analysis_run(analysis_id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS ix_llm_usage_analysis ON llm_usage (analysis_id);

COMMENT ON COLUMN llm_usage.analysis_id IS 'The analysis of the call (run_id is then null).';
