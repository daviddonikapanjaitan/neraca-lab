-- =====================================================================
-- Neraca Lab - who started an ingestion (PostgreSQL 15+)
--
--   ingestion_job.created_by          user who uploaded the workbook / requested the prices
--   ingestion_job.created_by_username the username at that time (kept when the user is deleted)
--   app_migration                     one-time data migrations already applied by the backend
--
-- Jobs that existed before this version are attributed once to the root user (admin) by the
-- backend at startup (IngestionCreatorBackfill), after the root user is guaranteed to exist.
-- created_by NULL afterwards means a scheduled run (no user) or a deleted user (then
-- created_by_username still names it).
--
-- Executed on every application start by Spring SQL init, after V1.0.8__schema_auth.sql
-- (users must exist for the foreign key). Every statement is idempotent.
-- =====================================================================

ALTER TABLE ingestion_job
    ADD COLUMN IF NOT EXISTS created_by BIGINT
        REFERENCES users(user_id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS created_by_username VARCHAR(50);

CREATE INDEX IF NOT EXISTS ix_ingestion_job_created_by
    ON ingestion_job (created_by);

COMMENT ON COLUMN ingestion_job.created_by IS 'User who started the job (upload or price request). NULL: scheduled run, or the user was deleted.';
COMMENT ON COLUMN ingestion_job.created_by_username IS 'Username of created_by when the job was started, kept after the user is deleted. NULL for scheduled runs.';

-- ---------------------------------------------------------------------
-- app_migration : one-time data migrations done by the backend
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_migration (
    name                VARCHAR(100) PRIMARY KEY,
    applied_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    details             VARCHAR(500)
);

COMMENT ON TABLE app_migration IS 'One-time data migrations applied by the backend at startup, so each runs exactly once.';
