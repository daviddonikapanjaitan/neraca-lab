-- =====================================================================
-- Neraca Lab - RAG vector store (PostgreSQL 15+ with pgvector)
--
--   rag_document : one source document of a company - an uploaded PDF (e.g. the IDX financial
--                  statement FinancialStatement-2025-Tahunan-HRTA.pdf) or a news article - linked to
--                  company (and, for a PDF, to the stored file in ingestion_file)
--   rag_chunk    : the document's text split into overlapping chunks, each with its embedding
--                  (vector(1536): OpenRouter openai/text-embedding-3-small) for similarity search;
--                  company_id is repeated so a search filters by company without a join
--
-- Needs the pgvector extension in the server (the postgres image of docker-compose: backend/postgres/
-- Dockerfile). Runs on every start; idempotent.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- ingestion_job: the two RAG ingestion job types
-- ---------------------------------------------------------------------
ALTER TABLE ingestion_job
    DROP CONSTRAINT IF EXISTS ck_ingestion_job_type,
    ADD  CONSTRAINT ck_ingestion_job_type
        CHECK (job_type IN ('FINANCIAL_STATEMENT', 'PRICE', 'FUNDAMENTALS', 'SCREENING', 'RAG_PDF', 'RAG_NEWS'));

ALTER TABLE ingestion_job
    DROP CONSTRAINT IF EXISTS ck_ingestion_job_file,
    ADD  CONSTRAINT ck_ingestion_job_file
        CHECK (job_type NOT IN ('FINANCIAL_STATEMENT', 'RAG_PDF') OR file_id IS NOT NULL);

COMMENT ON COLUMN ingestion_job.job_type IS
    'FINANCIAL_STATEMENT = IDX .xlsx upload stored by the AI agent, PRICE = daily price ingestion, FUNDAMENTALS = screening data ETL, SCREENING = AI stock screening, RAG_PDF = PDF document into the RAG vector store, RAG_NEWS = news of a company into the RAG vector store.';

COMMENT ON TABLE ingestion_file IS 'Uploaded files, one row per distinct content: financial statement workbooks (.xlsx) and RAG PDF documents (.pdf).';

-- ---------------------------------------------------------------------
-- rag_document
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_document (
    document_id         BIGSERIAL PRIMARY KEY,

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id) ON DELETE CASCADE,

    source_type         VARCHAR(10) NOT NULL,
    -- what identifies the document within the company: the file checksum (PDF) or the URL (news)
    source_key          VARCHAR(1000) NOT NULL,

    title               VARCHAR(1000) NOT NULL,
    source_name         VARCHAR(100),
    source_url          VARCHAR(2000),

    file_id             BIGINT
        REFERENCES ingestion_file(file_id),
    file_name           VARCHAR(255),

    published_at        TIMESTAMPTZ,
    pages               INTEGER,
    characters          INTEGER NOT NULL,
    chunks              INTEGER NOT NULL,
    embedding_model     VARCHAR(100) NOT NULL,

    job_id              UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_rag_document_source UNIQUE (company_id, source_type, source_key),
    CONSTRAINT ck_rag_document_source_type CHECK (source_type IN ('PDF', 'NEWS')),
    CONSTRAINT ck_rag_document_file CHECK (source_type <> 'PDF' OR file_id IS NOT NULL),
    CONSTRAINT ck_rag_document_url CHECK (source_type <> 'NEWS' OR source_url IS NOT NULL),
    CONSTRAINT ck_rag_document_counts CHECK (characters >= 0 AND chunks >= 0 AND (pages IS NULL OR pages >= 0))
);

CREATE INDEX IF NOT EXISTS ix_rag_document_company ON rag_document (company_id, source_type, published_at DESC);

COMMENT ON TABLE  rag_document IS 'A source document of the RAG vector store: an uploaded PDF or a news article of a company.';
COMMENT ON COLUMN rag_document.source_key IS 'PDF: SHA-256 of the file (one document per file and company); NEWS: the article URL.';
COMMENT ON COLUMN rag_document.published_at IS 'News: publication time of the article; PDF: NULL.';
COMMENT ON COLUMN rag_document.embedding_model IS 'Model that embedded the chunks (all chunks of a document use the same model).';
COMMENT ON COLUMN rag_document.job_id IS 'ingestion_job that last stored the document.';

-- ---------------------------------------------------------------------
-- rag_chunk
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_chunk (
    chunk_id            BIGSERIAL PRIMARY KEY,

    document_id         BIGINT NOT NULL
        REFERENCES rag_document(document_id) ON DELETE CASCADE,

    company_id          BIGINT NOT NULL
        REFERENCES company(company_id) ON DELETE CASCADE,

    chunk_index         INTEGER NOT NULL,
    page_from           INTEGER,
    page_to             INTEGER,
    content             TEXT NOT NULL,
    embedding           vector(1536) NOT NULL,

    CONSTRAINT uq_rag_chunk UNIQUE (document_id, chunk_index),
    CONSTRAINT ck_rag_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_rag_chunk_content CHECK (length(content) > 0)
);

CREATE INDEX IF NOT EXISTS ix_rag_chunk_company ON rag_chunk (company_id);
CREATE INDEX IF NOT EXISTS ix_rag_chunk_embedding ON rag_chunk USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE  rag_chunk IS 'Text chunk of a RAG document with its embedding; similarity search: ORDER BY embedding <=> query.';
COMMENT ON COLUMN rag_chunk.page_from IS 'PDF: first page of the chunk (1-based); NEWS: NULL.';
COMMENT ON COLUMN rag_chunk.embedding IS 'Embedding of content (1536 dimensions, cosine distance).';
