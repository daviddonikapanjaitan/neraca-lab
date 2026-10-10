-- =====================================================================
-- Neraca Lab - error log of the .xlsx ingestion API (PostgreSQL 15+)
--
--   ingestion_error : one row per upload of an IDX XBRL workbook that did not go through cleanly:
--                     rejected at the upload (HTTP 422), not storable by the standard extraction (a
--                     validation the workbook fails, an INCOMPLETE result, an extraction slower than
--                     the limit) or failed. It says what went wrong, whether the full-text extraction
--                     took over and how the upload ended.
--
-- The table is the work list of Claude Code: read the open rows, fix the cause in the ingestion code,
-- then mark the row.
--
--   SELECT error_id, occurred_at, file_name, ticker, period, error_type, message
--   FROM ingestion_error WHERE NOT fixed_by_claude_code ORDER BY occurred_at;
--
--   UPDATE ingestion_error
--   SET fixed_by_claude_code = TRUE, fixed_at = now(), fix_note = 'what was changed'
--   WHERE error_id = 1;
--
-- Executed on every application start by Spring SQL init, after V1.0.17. Every statement is idempotent.
-- =====================================================================

CREATE TABLE IF NOT EXISTS ingestion_error (
    error_id                BIGSERIAL PRIMARY KEY,
    occurred_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    job_id                  UUID
        REFERENCES ingestion_job(job_id) ON DELETE SET NULL,
    file_id                 BIGINT
        REFERENCES ingestion_file(file_id) ON DELETE SET NULL,
    file_name               VARCHAR(255),

    exchange                VARCHAR(50),
    ticker                  VARCHAR(20),
    period                  VARCHAR(30),

    error_type              VARCHAR(40) NOT NULL,
    message                 TEXT NOT NULL,
    details                 JSONB,

    fallback_used           BOOLEAN NOT NULL DEFAULT FALSE,
    fallback_columns        VARCHAR(100),
    final_status            VARCHAR(30),
    final_message           TEXT,

    fixed_by_claude_code    BOOLEAN NOT NULL DEFAULT FALSE,
    fixed_at                TIMESTAMPTZ,
    fix_note                TEXT,

    CONSTRAINT ck_ingestion_error_type CHECK (error_type IN (
        'UPLOAD_REJECTED', 'UNSUPPORTED_WORKBOOK', 'INCOMPLETE', 'SLOW_EXTRACTION', 'EXTRACTION_FAILED')),
    CONSTRAINT ck_ingestion_error_final_status CHECK (final_status IS NULL OR final_status IN (
        'SUCCEEDED', 'INCOMPLETE', 'FAILED', 'REJECTED')),
    CONSTRAINT ck_ingestion_error_fixed CHECK (fixed_by_claude_code OR fixed_at IS NULL)
);

-- one row per job: a job is recorded once, with its final state
CREATE UNIQUE INDEX IF NOT EXISTS uq_ingestion_error_job
    ON ingestion_error (job_id) WHERE job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_ingestion_error_open
    ON ingestion_error (occurred_at) WHERE NOT fixed_by_claude_code;

CREATE INDEX IF NOT EXISTS ix_ingestion_error_ticker
    ON ingestion_error (ticker, occurred_at DESC);

COMMENT ON TABLE  ingestion_error IS
    'Error log of the .xlsx ingestion API: every upload that was rejected, could not be stored by the standard extraction, or failed. Work list of Claude Code (fixed_by_claude_code).';
COMMENT ON COLUMN ingestion_error.job_id IS 'The ingestion job; NULL for an upload rejected before a job existed (HTTP 422).';
COMMENT ON COLUMN ingestion_error.period IS 'Current period of the filing, e.g. 2025 FY (when the workbook could be read).';
COMMENT ON COLUMN ingestion_error.error_type IS
    'UPLOAD_REJECTED: not a readable IDX XBRL workbook (HTTP 422, nothing stored). UNSUPPORTED_WORKBOOK: the standard extraction cannot map the workbook (e.g. no balance sheet data). INCOMPLETE: the standard extraction left open items (a validation the filing fails, a statement not saved). SLOW_EXTRACTION: the standard extraction ran longer than neracalab.ingestion.fallback.after. EXTRACTION_FAILED: the standard extraction failed.';
COMMENT ON COLUMN ingestion_error.message IS 'What went wrong in the standard extraction (the failed checks, the open items, the error).';
COMMENT ON COLUMN ingestion_error.details IS 'Problems and open items of the standard extraction and of the full-text extraction, as JSON.';
COMMENT ON COLUMN ingestion_error.fallback_used IS 'The full-text extraction (the model maps the text of the whole workbook to the database fields) took over.';
COMMENT ON COLUMN ingestion_error.fallback_columns IS 'Columns the full-text extraction worked on, e.g. PRIOR_PERIOD.';
COMMENT ON COLUMN ingestion_error.final_status IS 'How the upload ended: SUCCEEDED, INCOMPLETE or FAILED (the job status); REJECTED for an upload refused with HTTP 422.';
COMMENT ON COLUMN ingestion_error.fixed_by_claude_code IS 'TRUE once Claude Code has fixed the cause in the ingestion code (set by Claude Code, with fixed_at and fix_note).';
COMMENT ON COLUMN ingestion_error.fix_note IS 'What Claude Code changed, or why the error cannot be fixed in code (e.g. the workbook itself lacks the data).';
