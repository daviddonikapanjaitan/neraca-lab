-- =====================================================================
-- Neraca Lab - ingestion bookkeeping (PostgreSQL 15+)
--
--   ingestion_file : every uploaded IDX XBRL workbook (.xlsx), stored once per content
--                    (SHA-256 checksum); re-uploading the same file reuses the stored row
--   ingestion_job  : one row per ingestion process (financial statement upload, price
--                    ingestion) with its progress, written by the background workers
--
-- Executed on every application start by Spring SQL init, after V1.0.3__views.sql.
-- Every statement is idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- ingestion_file : uploaded workbooks, unique by content
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ingestion_file (
    file_id             BIGSERIAL PRIMARY KEY,
    file_name           VARCHAR(255) NOT NULL,
    content_type        VARCHAR(255),
    size_bytes          BIGINT NOT NULL,
    checksum_sha256     CHAR(64) NOT NULL,
    content             BYTEA NOT NULL,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_ingestion_file_checksum UNIQUE (checksum_sha256),
    CONSTRAINT ck_ingestion_file_checksum CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ingestion_file_size CHECK (size_bytes > 0 AND size_bytes = octet_length(content))
);

COMMENT ON TABLE  ingestion_file IS 'Uploaded financial statement workbooks (.xlsx), one row per distinct content.';
COMMENT ON COLUMN ingestion_file.file_name IS 'File name of the first upload of this content.';
COMMENT ON COLUMN ingestion_file.checksum_sha256 IS 'SHA-256 of content, lower-case hex. An upload with a known checksum reuses this row.';
COMMENT ON COLUMN ingestion_file.content IS 'The workbook bytes as uploaded.';

-- ---------------------------------------------------------------------
-- ingestion_job : progress of every ingestion process
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ingestion_job (
    job_id              UUID PRIMARY KEY,
    job_type            VARCHAR(30) NOT NULL,
    status              VARCHAR(30) NOT NULL,
    stage               VARCHAR(500),

    exchange            VARCHAR(50),
    ticker              VARCHAR(20),

    file_id             BIGINT
        REFERENCES ingestion_file(file_id),
    file_name           VARCHAR(255),
    file_reused         BOOLEAN,
    full_history        BOOLEAN,

    attempts            INTEGER NOT NULL DEFAULT 0,
    message             TEXT,
    result              JSONB,

    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    resume_at           TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_ingestion_job_type
        CHECK (job_type IN ('FINANCIAL_STATEMENT', 'PRICE')),
    CONSTRAINT ck_ingestion_job_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT', 'SUCCEEDED', 'INCOMPLETE', 'FAILED')),
    CONSTRAINT ck_ingestion_job_file
        CHECK (job_type <> 'FINANCIAL_STATEMENT' OR file_id IS NOT NULL),
    CONSTRAINT ck_ingestion_job_attempts CHECK (attempts >= 0)
);

CREATE INDEX IF NOT EXISTS ix_ingestion_job_requested
    ON ingestion_job (requested_at DESC);

CREATE INDEX IF NOT EXISTS ix_ingestion_job_file
    ON ingestion_job (file_id);

CREATE INDEX IF NOT EXISTS ix_ingestion_job_active
    ON ingestion_job (status)
    WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT');

COMMENT ON TABLE  ingestion_job IS 'One row per ingestion process (financial statement upload or price ingestion) and its progress.';
COMMENT ON COLUMN ingestion_job.job_type IS 'FINANCIAL_STATEMENT = IDX .xlsx upload stored by the AI agent, PRICE = daily price ingestion.';
COMMENT ON COLUMN ingestion_job.status IS
    'QUEUED, RUNNING, WAITING_RATE_LIMIT (price provider answered HTTP 429, retried at resume_at) are active. SUCCEEDED, INCOMPLETE (filing stored but the verification found something pending), FAILED are final.';
COMMENT ON COLUMN ingestion_job.stage IS 'Current step of an active job, or a one-line summary of a finished one.';
COMMENT ON COLUMN ingestion_job.ticker IS 'Company ticker. For an upload it is known once the workbook has been read.';
COMMENT ON COLUMN ingestion_job.file_name IS 'File name of this upload (the stored file may carry the name of an earlier upload of the same content).';
COMMENT ON COLUMN ingestion_job.file_reused IS 'TRUE when the uploaded content was already stored (same checksum) and the stored file was used.';
COMMENT ON COLUMN ingestion_job.full_history IS 'Price ingestion: the whole price history was requested (full=true).';
COMMENT ON COLUMN ingestion_job.attempts IS 'Runs of the job (more than 1 after price provider rate-limit waits).';
COMMENT ON COLUMN ingestion_job.message IS 'Why the job failed, is waiting or is incomplete.';
COMMENT ON COLUMN ingestion_job.result IS 'Outcome of a finished job: the AI agent audit trail of an upload or the price ingestion result.';
