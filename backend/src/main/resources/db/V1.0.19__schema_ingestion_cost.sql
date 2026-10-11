-- =====================================================================
-- Neraca Lab - model cost of the .xlsx ingestion (PostgreSQL 15+)
--
--   llm_usage.job_id : the upload job (ingestion_job, job type FINANCIAL_STATEMENT) of a model call.
--                      Every model call of an IDX XBRL upload is one row, as for a screening run
--                      (run_id) and an analysis (analysis_id): the planner, the tool turns and the
--                      reviewer of the standard extraction (DeepSeek by default) and the answers of
--                      the full-text extraction (neracalab.ingestion.fallback.model), each with its
--                      model, tokens and the cost OpenRouter reports. A failed attempt is a row too
--                      (error set, no tokens).
--
--   stage            : PLAN, EXECUTE, REVIEW (standard extraction), FULL_TEXT (full-text extraction)
--
-- Cost of the last uploads, per model:
--
--   SELECT j.file_name, u.model, count(*) AS calls, sum(u.prompt_tokens) AS prompt,
--          sum(u.completion_tokens) AS completion, sum(u.cost_usd) AS cost_usd
--   FROM llm_usage u JOIN ingestion_job j ON j.job_id = u.job_id
--   GROUP BY j.job_id, j.file_name, j.requested_at, u.model
--   ORDER BY j.requested_at DESC;
--
-- Executed on every application start by Spring SQL init, after V1.0.18. Every statement is idempotent.
-- =====================================================================

ALTER TABLE llm_usage
    ADD COLUMN IF NOT EXISTS job_id UUID REFERENCES ingestion_job(job_id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS ix_llm_usage_job ON llm_usage (job_id);

COMMENT ON TABLE  llm_usage IS 'Every model call of a screening run (run_id), an analysis (analysis_id) or a financial statement upload (job_id): tokens and cost.';
COMMENT ON COLUMN llm_usage.job_id IS 'The financial statement upload job of the call (run_id and analysis_id are then null).';
