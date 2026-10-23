-- =====================================================================
-- Neraca Lab - macro documents of a country in the RAG vector store (PostgreSQL 15+ with pgvector)
--
--   rag_document : a document belongs to a company (company_id, V1.0.14) or, a macro document, to a country
--                  (country: ID; US and CN later) - a PDF of Bank Indonesia, the Ministry of Finance, the IMF, ...
--                  with its issuer, document type and publication date (required: the AI macro analysis reads only
--                  the documents published by its as-of date)
--   rag_chunk    : country is repeated (as company_id) so a search filters by country without a join
--
-- Macro PDFs are uploaded on Ingestion > PDF Documents (RAG) as RAG_PDF jobs (exchange MACRO, ticker = the country);
-- the research agent of Macro > AI Analysis searches them (docs/v1_docs/RAG_DOCS.md, MACRO_ANALYSIS_DOCS.md).
--
-- Executed on every application start by Spring SQL init, after V1.0.14. Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- rag_document : company or country
-- ---------------------------------------------------------------------
ALTER TABLE rag_document ALTER COLUMN company_id DROP NOT NULL;

ALTER TABLE rag_document
    ADD COLUMN IF NOT EXISTS country       VARCHAR(10),
    ADD COLUMN IF NOT EXISTS issuer        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS document_type VARCHAR(30);

ALTER TABLE rag_document
    DROP CONSTRAINT IF EXISTS ck_rag_document_scope,
    ADD  CONSTRAINT ck_rag_document_scope CHECK ((company_id IS NULL) <> (country IS NULL));

-- a macro document is a PDF with its type and publication date
ALTER TABLE rag_document
    DROP CONSTRAINT IF EXISTS ck_rag_document_macro,
    ADD  CONSTRAINT ck_rag_document_macro
        CHECK (country IS NULL OR (source_type = 'PDF' AND published_at IS NOT NULL AND document_type IS NOT NULL));

ALTER TABLE rag_document
    DROP CONSTRAINT IF EXISTS ck_rag_document_type,
    ADD  CONSTRAINT ck_rag_document_type
        CHECK (document_type IS NULL OR document_type IN ('MONETARY_POLICY', 'FINANCIAL_STABILITY', 'FISCAL',
                                                          'EXTERNAL_SECTOR', 'ECONOMIC_OUTLOOK', 'STATISTICS',
                                                          'DEVELOPMENT_PLAN', 'RESEARCH', 'OTHER',
                                                          -- the company PDF types of V1.0.29: this script runs first on
                                                          -- every start, so it must accept them too
                                                          'FINANCIAL_STATEMENT', 'REGISTRATION'));

-- one document per file and country (uq_rag_document_source keys the company documents)
CREATE UNIQUE INDEX IF NOT EXISTS uq_rag_document_country_source
    ON rag_document (country, source_type, source_key) WHERE country IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_rag_document_country ON rag_document (country, published_at DESC) WHERE country IS NOT NULL;

COMMENT ON TABLE  rag_document IS 'A source document of the RAG vector store: an uploaded PDF or a news article of a company, or a macro PDF of a country (V1.0.28).';
COMMENT ON COLUMN rag_document.company_id IS 'Company of the document; NULL for a macro document (country set instead).';
COMMENT ON COLUMN rag_document.country IS 'Country of a macro document (ID; US and CN later); NULL for a company document.';
COMMENT ON COLUMN rag_document.issuer IS 'Macro document: who published it, e.g. Bank Indonesia, Ministry of Finance, IMF.';
COMMENT ON COLUMN rag_document.document_type IS 'Macro document: MONETARY_POLICY, FINANCIAL_STABILITY, FISCAL, EXTERNAL_SECTOR, ECONOMIC_OUTLOOK, STATISTICS, DEVELOPMENT_PLAN, RESEARCH (opinion) or OTHER.';
COMMENT ON COLUMN rag_document.published_at IS 'News: publication time of the article; macro PDF: publication date (00:00 Jakarta), required; company PDF: NULL.';
COMMENT ON COLUMN rag_document.source_key IS 'PDF: SHA-256 of the file (one document per file and company or country); NEWS: the article URL.';

-- ---------------------------------------------------------------------
-- rag_chunk : company or country
-- ---------------------------------------------------------------------
ALTER TABLE rag_chunk ALTER COLUMN company_id DROP NOT NULL;

ALTER TABLE rag_chunk ADD COLUMN IF NOT EXISTS country VARCHAR(10);

ALTER TABLE rag_chunk
    DROP CONSTRAINT IF EXISTS ck_rag_chunk_scope,
    ADD  CONSTRAINT ck_rag_chunk_scope CHECK ((company_id IS NULL) <> (country IS NULL));

CREATE INDEX IF NOT EXISTS ix_rag_chunk_country ON rag_chunk (country) WHERE country IS NOT NULL;

COMMENT ON COLUMN rag_chunk.company_id IS 'Company of the document (repeated for filtered search); NULL for a macro document.';
COMMENT ON COLUMN rag_chunk.country IS 'Country of a macro document (repeated for filtered search); NULL for a company document.';
