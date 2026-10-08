-- =====================================================================
-- Neraca Lab - AI stock screening (PostgreSQL 15+)
--
--   stock_listing          screening universe: every equity Yahoo Finance lists on an exchange
--   fundamental_snapshot   daily ETL: market data of every listing per day plus its fundamentals
--                          (ratios and four years of annual figures), carried forward between refreshes
--   news_article           news cache of the research agent (crawled sites and Tavily), one row per URL
--   news_article_ticker    which listings an article was found for
--   news_source_fetch      when a news source was last asked about a ticker (cache window)
--   news_brief             research agent output per ticker and day (reused by later runs that day)
--   screening_run          one screening (parameters, funnel, synthesis, cost); job_id = ingestion_job
--   screening_candidate    the Stage 1 shortlist of a run with metrics, scores, rank and thesis
--   screening_agent_score  score and reasoning of every investor agent per candidate
--   screening_lesson       Reflexion memory: lessons from earlier runs injected into later prompts
--   llm_usage              every model call: tokens and cost (screening runs)
--
-- Also extends two CHECK constraints of earlier scripts: the SCREENING permission
-- (role_permissions) and the FUNDAMENTALS / SCREENING job types (ingestion_job).
--
-- Executed on every application start by Spring SQL init, after V1.0.9. Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- new permission and job types (drop and re-add: idempotent, the tables are small)
-- ---------------------------------------------------------------------
ALTER TABLE role_permissions
    DROP CONSTRAINT IF EXISTS ck_role_permissions_permission,
    ADD  CONSTRAINT ck_role_permissions_permission
        CHECK (permission IN ('ADMIN', 'INGESTION', 'COMPANIES', 'SCREENING'));

-- This script runs on every start, before the later schema scripts: the list must also hold the job
-- types added later (RAG_PDF, RAG_NEWS by V1.0.14), or the stored jobs of those types violate it.
ALTER TABLE ingestion_job
    DROP CONSTRAINT IF EXISTS ck_ingestion_job_type,
    ADD  CONSTRAINT ck_ingestion_job_type
        CHECK (job_type IN ('FINANCIAL_STATEMENT', 'PRICE', 'FUNDAMENTALS', 'SCREENING', 'RAG_PDF', 'RAG_NEWS'));

COMMENT ON COLUMN ingestion_job.job_type IS
    'FINANCIAL_STATEMENT = IDX .xlsx upload stored by the AI agent, PRICE = daily price ingestion, FUNDAMENTALS = screening data ETL (Yahoo Finance), SCREENING = AI stock screening run.';

-- ---------------------------------------------------------------------
-- stock_listing : screening universe
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stock_listing (
    listing_id           BIGSERIAL PRIMARY KEY,
    exchange             VARCHAR(50) NOT NULL,
    ticker               VARCHAR(20) NOT NULL,
    symbol               VARCHAR(30) NOT NULL,
    company_name         VARCHAR(255) NOT NULL,
    sector               VARCHAR(150),
    industry             VARCHAR(150),
    currency             CHAR(3),
    first_trade_date     DATE,
    board                VARCHAR(30),
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_stock_listing UNIQUE (exchange, ticker),
    CONSTRAINT ck_stock_listing_ticker CHECK (ticker ~ '^[A-Z0-9][A-Z0-9.-]*$'),
    CONSTRAINT ck_stock_listing_board
        CHECK (board IS NULL OR board IN ('MAIN', 'DEVELOPMENT', 'ACCELERATION', 'NEW_ECONOMY', 'WATCHLIST'))
);

COMMENT ON TABLE  stock_listing IS 'Screening universe: equities listed on an exchange, as returned by the Yahoo Finance screener.';
COMMENT ON COLUMN stock_listing.symbol IS 'Yahoo Finance symbol, e.g. BBCA.JK.';
COMMENT ON COLUMN stock_listing.board IS 'IDX listing board when known (NULL = unknown: Yahoo does not publish it). WATCHLIST is excluded by Stage 1.';
COMMENT ON COLUMN stock_listing.active IS 'FALSE when the listing was missing from the last complete universe sync (delisted or suspended for long).';

-- ---------------------------------------------------------------------
-- fundamental_snapshot : daily market data and fundamentals per listing
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fundamental_snapshot (
    snapshot_id              BIGSERIAL PRIMARY KEY,
    listing_id               BIGINT NOT NULL
        REFERENCES stock_listing(listing_id) ON DELETE CASCADE,
    snapshot_date            DATE NOT NULL,

    -- market data (Yahoo screener, every day)
    price                    NUMERIC(20,6),
    market_cap               NUMERIC(28,4),
    shares_outstanding       NUMERIC(24,4),
    avg_volume_3m            NUMERIC(24,4),
    avg_volume_10d           NUMERIC(24,4),
    avg_daily_value_3m       NUMERIC(28,4),
    fifty_two_week_high      NUMERIC(20,6),
    fifty_two_week_low       NUMERIC(20,6),
    trailing_pe              NUMERIC(20,8),
    forward_pe               NUMERIC(20,8),
    price_to_book            NUMERIC(20,8),
    eps_ttm                  NUMERIC(20,8),
    book_value_per_share     NUMERIC(20,8),
    dividend_yield           NUMERIC(20,8),
    last_trade_at            TIMESTAMPTZ,

    -- fundamentals (Yahoo quoteSummary + annual time series, refreshed every few days, carried forward)
    revenue_ttm              NUMERIC(28,4),
    net_income_ttm           NUMERIC(28,4),
    ebitda_ttm               NUMERIC(28,4),
    gross_margin             NUMERIC(20,8),
    operating_margin         NUMERIC(20,8),
    profit_margin            NUMERIC(20,8),
    ebitda_margin            NUMERIC(20,8),
    return_on_equity         NUMERIC(20,8),
    return_on_assets         NUMERIC(20,8),
    debt_to_equity           NUMERIC(20,8),
    current_ratio            NUMERIC(20,8),
    quick_ratio              NUMERIC(20,8),
    total_cash               NUMERIC(28,4),
    total_debt               NUMERIC(28,4),
    free_cash_flow           NUMERIC(28,4),
    operating_cash_flow      NUMERIC(28,4),
    revenue_growth           NUMERIC(20,8),
    earnings_growth          NUMERIC(20,8),
    enterprise_value         NUMERIC(28,4),
    ev_to_ebitda             NUMERIC(20,8),
    ev_to_revenue            NUMERIC(20,8),
    peg_ratio                NUMERIC(20,8),
    beta                     NUMERIC(20,8),
    insider_ownership        NUMERIC(20,8),
    institution_ownership    NUMERIC(20,8),
    annual                   JSONB,
    fundamentals_fetched_at  TIMESTAMPTZ,
    fundamentals_error       VARCHAR(500),

    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_fundamental_snapshot UNIQUE (listing_id, snapshot_date)
);

CREATE INDEX IF NOT EXISTS ix_fundamental_snapshot_latest
    ON fundamental_snapshot (listing_id, snapshot_date DESC);

COMMENT ON TABLE  fundamental_snapshot IS 'Daily ETL of the screening data: market data per listing per day, fundamentals carried forward between refreshes.';
COMMENT ON COLUMN fundamental_snapshot.avg_daily_value_3m IS 'avg_volume_3m x price: average traded value per day (liquidity filter of Stage 1), in the listing currency.';
COMMENT ON COLUMN fundamental_snapshot.debt_to_equity IS 'Total debt / equity as a fraction (Yahoo reports a percentage, stored divided by 100).';
COMMENT ON COLUMN fundamental_snapshot.annual IS 'Four fiscal years of annual figures: {"years":[...], "revenue":[...], "netIncome":[...], ...}, oldest first.';
COMMENT ON COLUMN fundamental_snapshot.fundamentals_fetched_at IS 'When the fundamentals of this row were fetched; NULL on a row whose fundamentals were never loaded.';
COMMENT ON COLUMN fundamental_snapshot.fundamentals_error IS 'Why the last fundamentals refresh failed (the previous values are kept).';

-- ---------------------------------------------------------------------
-- news cache
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS news_article (
    article_id           BIGSERIAL PRIMARY KEY,
    url                  VARCHAR(1000) NOT NULL,
    source               VARCHAR(30) NOT NULL,
    title                VARCHAR(500) NOT NULL,
    description          TEXT,
    body_excerpt         TEXT,
    published_at         TIMESTAMPTZ,
    fetched_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    body_fetched_at      TIMESTAMPTZ,

    CONSTRAINT uq_news_article_url UNIQUE (url),
    CONSTRAINT ck_news_article_source
        CHECK (source IN ('TAVILY', 'EMITENNEWS', 'PASARDANA', 'IDXCHANNEL', 'INVESTOR_ID'))
);

CREATE TABLE IF NOT EXISTS news_article_ticker (
    article_id           BIGINT NOT NULL
        REFERENCES news_article(article_id) ON DELETE CASCADE,
    exchange             VARCHAR(50) NOT NULL,
    ticker               VARCHAR(20) NOT NULL,
    found_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_news_article_ticker PRIMARY KEY (article_id, exchange, ticker)
);

CREATE INDEX IF NOT EXISTS ix_news_article_ticker_ticker
    ON news_article_ticker (exchange, ticker);

CREATE TABLE IF NOT EXISTS news_source_fetch (
    exchange             VARCHAR(50) NOT NULL,
    ticker               VARCHAR(20) NOT NULL,
    source               VARCHAR(30) NOT NULL,
    fetched_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    article_count        INTEGER NOT NULL DEFAULT 0,
    error                VARCHAR(500),

    CONSTRAINT pk_news_source_fetch PRIMARY KEY (exchange, ticker, source)
);

CREATE TABLE IF NOT EXISTS news_brief (
    exchange             VARCHAR(50) NOT NULL,
    ticker               VARCHAR(20) NOT NULL,
    brief_date           DATE NOT NULL,
    brief                JSONB NOT NULL,
    model                VARCHAR(100),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_news_brief PRIMARY KEY (exchange, ticker, brief_date)
);

COMMENT ON TABLE news_article IS 'News found by the screening research agent (crawled sites and Tavily search), one row per URL.';
COMMENT ON TABLE news_source_fetch IS 'Last time a news source was searched for a ticker; a newer fetch within the cache window is skipped.';
COMMENT ON TABLE news_brief IS 'Research agent summary of the news of a ticker on a day; a later screening the same day reuses it (no model call).';

-- ---------------------------------------------------------------------
-- screening_run : one screening
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS screening_run (
    run_id               UUID PRIMARY KEY
        REFERENCES ingestion_job(job_id) ON DELETE CASCADE,
    exchange             VARCHAR(50) NOT NULL,
    market_cap_tier      VARCHAR(10) NOT NULL,
    top_n                INTEGER NOT NULL,
    agents               JSONB NOT NULL,
    snapshot_date        DATE,
    universe_count       INTEGER,
    eligible_count       INTEGER,
    shortlist_count      INTEGER,
    selected_count       INTEGER,
    funnel               JSONB,
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

    CONSTRAINT ck_screening_run_tier CHECK (market_cap_tier IN ('LARGE', 'MID', 'SMALL')),
    CONSTRAINT ck_screening_run_top_n CHECK (top_n BETWEEN 1 AND 100)
);

COMMENT ON TABLE  screening_run IS 'One AI screening: parameters, Stage 1 funnel, Opus synthesis, token usage and cost. Status and progress are in ingestion_job (same id).';
COMMENT ON COLUMN screening_run.agents IS 'Selected investor agents, e.g. ["BUFFETT","LYNCH","RISK"].';
COMMENT ON COLUMN screening_run.funnel IS 'Stage 1 filters in order with the number of stocks left after each.';
COMMENT ON COLUMN screening_run.synthesis IS 'Final synthesis: executive summary, portfolio notes, model, whether the fallback was used.';
COMMENT ON COLUMN screening_run.notes IS 'Notes of the run: degraded steps, budget stops, data gaps.';

-- ---------------------------------------------------------------------
-- screening_candidate : the shortlist of a run
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS screening_candidate (
    candidate_id         BIGSERIAL PRIMARY KEY,
    run_id               UUID NOT NULL
        REFERENCES screening_run(run_id) ON DELETE CASCADE,
    ticker               VARCHAR(20) NOT NULL,
    company_name         VARCHAR(255) NOT NULL,
    sector               VARCHAR(150),
    industry             VARCHAR(150),
    metrics              JSONB NOT NULL,
    quant_overall        NUMERIC(7,3),
    quant_rank           INTEGER,
    news                 JSONB,
    overall_score        NUMERIC(7,3),
    synthesis_adjustment NUMERIC(7,3),
    final_rank           INTEGER,
    selected             BOOLEAN NOT NULL DEFAULT FALSE,
    conviction           VARCHAR(10),
    thesis               TEXT,
    red_flags            JSONB,

    CONSTRAINT uq_screening_candidate UNIQUE (run_id, ticker)
);

CREATE INDEX IF NOT EXISTS ix_screening_candidate_run ON screening_candidate (run_id, final_rank);

COMMENT ON TABLE  screening_candidate IS 'Stage 1 shortlist of a run: metrics used, quantitative score, news brief, final score and rank; selected = in the top N.';
COMMENT ON COLUMN screening_candidate.synthesis_adjustment IS 'Score points (-5..+5) the final synthesis added with a stated reason; included in overall_score.';

-- ---------------------------------------------------------------------
-- screening_agent_score : one investor agent's view of one candidate
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS screening_agent_score (
    score_id             BIGSERIAL PRIMARY KEY,
    candidate_id         BIGINT NOT NULL
        REFERENCES screening_candidate(candidate_id) ON DELETE CASCADE,
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

    CONSTRAINT uq_screening_agent_score UNIQUE (candidate_id, agent),
    CONSTRAINT ck_screening_agent_score_agent
        CHECK (agent IN ('BUFFETT', 'MUNGER', 'LYNCH', 'FISHER', 'GILL', 'RISK')),
    CONSTRAINT ck_screening_agent_score_status
        CHECK (status IN ('ASSESSED', 'REVISED', 'QUANT_ONLY'))
);

COMMENT ON COLUMN screening_agent_score.final_score IS 'Blend of quant_score and llm_score (neracalab.screening.llm-weight), quant_score alone when the model gave none (status QUANT_ONLY).';
COMMENT ON COLUMN screening_agent_score.quant_detail IS 'Stage 1 scorecard of the agent: every criterion with its metric value, weight and points.';
COMMENT ON COLUMN screening_agent_score.reflection IS 'Reflection: the issues the validator found and the revision of the critic, if any.';

-- ---------------------------------------------------------------------
-- screening_lesson : Reflexion memory
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS screening_lesson (
    lesson_id            BIGSERIAL PRIMARY KEY,
    agent                VARCHAR(20) NOT NULL,
    lesson               VARCHAR(500) NOT NULL,
    occurrences          INTEGER NOT NULL DEFAULT 1,
    source_run           UUID,
    active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_screening_lesson UNIQUE (agent, lesson),
    CONSTRAINT ck_screening_lesson_agent
        CHECK (agent IN ('BUFFETT', 'MUNGER', 'LYNCH', 'FISHER', 'GILL', 'RISK', 'RESEARCH'))
);

COMMENT ON TABLE screening_lesson IS 'Reflexion memory: verbal lessons from the reflection of earlier runs, injected into the prompts of later runs.';

-- ---------------------------------------------------------------------
-- llm_usage : tokens and cost of every model call
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS llm_usage (
    usage_id             BIGSERIAL PRIMARY KEY,
    run_id               UUID
        REFERENCES screening_run(run_id) ON DELETE CASCADE,
    stage                VARCHAR(30) NOT NULL,
    agent                VARCHAR(20),
    ticker               VARCHAR(20),
    model                VARCHAR(100) NOT NULL,
    prompt_tokens        INTEGER NOT NULL DEFAULT 0,
    completion_tokens    INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens     INTEGER NOT NULL DEFAULT 0,
    cached_tokens        INTEGER NOT NULL DEFAULT 0,
    cost_usd             NUMERIC(14,8) NOT NULL DEFAULT 0,
    cost_estimated       BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms          INTEGER,
    error                VARCHAR(500),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_llm_usage_run ON llm_usage (run_id);

COMMENT ON TABLE  llm_usage IS 'Every model call of a screening run: tokens and cost.';
COMMENT ON COLUMN llm_usage.cost_usd IS 'Cost reported by OpenRouter (usage.cost); estimated from neracalab.screening.prices when the provider reported none (cost_estimated).';
