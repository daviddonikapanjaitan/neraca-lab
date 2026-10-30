-- =====================================================================
-- Neraca Lab - development context of Claude Code (PostgreSQL 15+ with pgvector)
--
--   dev_context : what Claude Code has learned while developing and maintaining the application - a ticker whose
--                 workbook lacks data, an ingestion error and its cause, a fix, a decision, a procedure - so a later
--                 session can find it again instead of investigating once more. One row per fact, with an embedding
--                 (vector(1536), the RAG store's model: openai/text-embedding-3-small) for similarity search and a
--                 full-text vector for keyword search. Written and read through /api/v1/dev-context (which embeds);
--                 also readable with plain SQL.
--
-- Runs on every start; idempotent. Needs pgvector (V1.0.14__schema_rag.sql creates the extension).
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS dev_context (
    context_id          BIGSERIAL PRIMARY KEY,

    -- ISSUE (a problem seen), FIX (what was changed and why), DATA_GAP (data the filings or sources lack),
    -- DECISION (a choice and its reason), PROCEDURE (how to do something), NOTE (anything else)
    kind                VARCHAR(20) NOT NULL,
    -- OPEN (still to do), RESOLVED (done), WONT_FIX (left so on purpose: by design, the filing's), INFO (a fact)
    status              VARCHAR(20) NOT NULL DEFAULT 'INFO',

    -- the company it is about, if any (not a foreign key: a ticker not stored yet can have context too)
    ticker              VARCHAR(20),
    -- the period it is about, if any, as reporting_period shows it: '2025 FY', '2026 H1', '2025 9M'
    period              VARCHAR(20),

    title               VARCHAR(500) NOT NULL,
    body                TEXT NOT NULL,
    tags                TEXT[] NOT NULL DEFAULT '{}',
    -- where it comes from or what it points to: 'ingestion_error 1246', a file, a class, a commit
    refs                TEXT[] NOT NULL DEFAULT '{}',

    -- NULL when the embedding could not be computed (no key, provider down): keyword search still finds it, and
    -- POST /api/v1/dev-context/reembed fills it later
    embedding           vector(1536),
    embedding_model     VARCHAR(100),
    search              tsvector,

    created_by          VARCHAR(100) NOT NULL DEFAULT 'claude_code',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_dev_context_kind CHECK (kind IN ('ISSUE', 'FIX', 'DATA_GAP', 'DECISION', 'PROCEDURE', 'NOTE')),
    CONSTRAINT ck_dev_context_status CHECK (status IN ('OPEN', 'RESOLVED', 'WONT_FIX', 'INFO')),
    CONSTRAINT ck_dev_context_ticker CHECK (ticker IS NULL OR ticker = upper(ticker))
);

CREATE INDEX IF NOT EXISTS ix_dev_context_ticker ON dev_context (ticker, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_dev_context_kind ON dev_context (kind, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_dev_context_search ON dev_context USING gin (search);
CREATE INDEX IF NOT EXISTS ix_dev_context_tags ON dev_context USING gin (tags);
CREATE INDEX IF NOT EXISTS ix_dev_context_embedding ON dev_context USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE  dev_context IS 'Development context of Claude Code: issues, fixes, data gaps, decisions and procedures, with an embedding for similarity search (RAG). Read and written through /api/v1/dev-context.';
COMMENT ON COLUMN dev_context.kind IS 'ISSUE, FIX, DATA_GAP, DECISION, PROCEDURE or NOTE.';
COMMENT ON COLUMN dev_context.status IS 'OPEN (to do), RESOLVED (done), WONT_FIX (left so on purpose), INFO (a fact).';
COMMENT ON COLUMN dev_context.ticker IS 'The company it is about (upper case), NULL for general context; no foreign key.';
COMMENT ON COLUMN dev_context.period IS 'The reporting period it is about, e.g. 2025 FY, 2026 H1; NULL when none.';
COMMENT ON COLUMN dev_context.refs IS 'What it points to: ingestion_error ids, files, classes, commits.';
COMMENT ON COLUMN dev_context.embedding IS 'Embedding of ticker, period, title, body and tags (1536 dimensions); NULL until computed.';
COMMENT ON COLUMN dev_context.search IS 'Full-text vector of the same text (simple configuration), for keyword search.';
