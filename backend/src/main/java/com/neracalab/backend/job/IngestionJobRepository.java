package com.neracalab.backend.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ingestion_job}: progress of every ingestion process. Written by the background workers
 * (upload worker, price queue), read by {@code GET /api/v1/ingestions}. The job result is stored
 * as JSONB and only loaded for a single job.
 */
@Repository
public class IngestionJobRepository {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobRepository.class);

    /** Longest stored stage text (column VARCHAR(500)). */
    private static final int STAGE_LENGTH = 500;

    private static final String SELECT = """
            SELECT j.job_id, j.job_type, j.status, j.stage, j.exchange, j.ticker, j.file_id, j.file_name,
                   j.file_reused, j.full_history, j.attempts, j.message, j.requested_at, j.started_at,
                   j.finished_at, j.resume_at, j.updated_at, f.size_bytes, f.checksum_sha256,
                   j.created_by, j.created_by_username, u.full_name AS created_by_full_name,
                   c.calls AS cost_calls, c.errors AS cost_errors, c.prompt AS cost_prompt,
                   c.completion AS cost_completion, c.reasoning AS cost_reasoning, c.cached AS cost_cached,
                   c.cost AS cost_usd, c.estimated AS cost_estimated%s
            FROM ingestion_job j
            LEFT JOIN ingestion_file f ON f.file_id = j.file_id
            LEFT JOIN users u ON u.user_id = j.created_by
            LEFT JOIN LATERAL (
                SELECT count(*) AS calls, count(*) FILTER (WHERE x.error IS NOT NULL) AS errors,
                       COALESCE(sum(x.prompt_tokens), 0) AS prompt, COALESCE(sum(x.completion_tokens), 0) AS completion,
                       COALESCE(sum(x.reasoning_tokens), 0) AS reasoning, COALESCE(sum(x.cached_tokens), 0) AS cached,
                       COALESCE(sum(x.cost_usd), 0) AS cost, COALESCE(bool_or(x.cost_estimated), FALSE) AS estimated
                FROM llm_usage x
                WHERE x.job_id = j.job_id OR x.run_id = j.job_id OR x.analysis_id = j.job_id) c ON TRUE""";

    /** The model calls of a job per stage and model; the calls of a screening and of an analysis carry the job id too. */
    private static final String USAGE = """
            SELECT stage, model, count(*) AS calls, sum(prompt_tokens) AS prompt, sum(completion_tokens) AS completion,
                   sum(reasoning_tokens) AS reasoning, sum(cached_tokens) AS cached, sum(cost_usd) AS cost,
                   bool_or(cost_estimated) AS estimated, count(*) FILTER (WHERE error IS NOT NULL) AS errors,
                   min(usage_id) AS first
            FROM llm_usage
            WHERE job_id = :id OR run_id = :id OR analysis_id = :id
            GROUP BY stage, model ORDER BY first""";

    /** Longest stored error text of a model call (column VARCHAR(500)). */
    private static final int ERROR_LENGTH = 500;

    /**
     * Full state of a job, written by {@link #save}.
     *
     * @param result      object serialized to JSON ({@code null}: none)
     * @param requestedBy user who started the job ({@code null}: scheduled run); only written when the
     *                    row is inserted, later snapshots never change it
     */
    public record Snapshot(UUID id, IngestionJobType type, IngestionJobStatus status, String stage, String exchange,
                           String ticker, Boolean fullHistory, int attempts, String message, Instant requestedAt,
                           Instant startedAt, Instant finishedAt, Instant resumeAt, Object result,
                           Requester requestedBy) {
    }

    /** A new upload job, started by {@code requestedBy}. */
    public record NewUpload(UUID id, long fileId, String fileName, boolean fileReused, String stage,
                            Requester requestedBy) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IngestionJobRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Inserts or fully replaces a job (the price queue writes every state change this way). File
     * columns and the requester (created_by) are kept on update.
     */
    public void save(Snapshot s) {
        jdbc.sql("""
                        INSERT INTO ingestion_job (
                            job_id, job_type, status, stage, exchange, ticker, full_history, attempts, message,
                            result, requested_at, started_at, finished_at, resume_at, updated_at,
                            created_by, created_by_username)
                        VALUES (:id, :type, :status, :stage, :exchange, :ticker, :full, :attempts, :message,
                                CAST(:result AS jsonb), :requestedAt, :startedAt, :finishedAt, :resumeAt, now(),
                                :createdBy, :createdByUsername)
                        ON CONFLICT (job_id) DO UPDATE SET
                            status       = EXCLUDED.status,
                            stage        = EXCLUDED.stage,
                            exchange     = EXCLUDED.exchange,
                            ticker       = EXCLUDED.ticker,
                            full_history = EXCLUDED.full_history,
                            attempts     = EXCLUDED.attempts,
                            message      = EXCLUDED.message,
                            result       = EXCLUDED.result,
                            started_at   = EXCLUDED.started_at,
                            finished_at  = EXCLUDED.finished_at,
                            resume_at    = EXCLUDED.resume_at,
                            updated_at   = now()""")
                .param("id", s.id())
                .param("type", s.type().name())
                .param("status", s.status().name())
                .param("stage", truncate(s.stage()), Types.VARCHAR)
                .param("exchange", s.exchange(), Types.VARCHAR)
                .param("ticker", s.ticker(), Types.VARCHAR)
                .param("full", s.fullHistory(), Types.BOOLEAN)
                .param("attempts", s.attempts())
                .param("message", s.message(), Types.VARCHAR)
                .param("result", toJson(s.id(), s.result()), Types.VARCHAR)
                .param("requestedAt", timestamp(s.requestedAt() == null ? Instant.now() : s.requestedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("startedAt", timestamp(s.startedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("finishedAt", timestamp(s.finishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("resumeAt", timestamp(s.resumeAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdBy", s.requestedBy() == null ? null : s.requestedBy().userId(), Types.BIGINT)
                .param("createdByUsername", s.requestedBy() == null ? null : s.requestedBy().username(), Types.VARCHAR)
                .update();
    }

    /** A queued financial statement upload. */
    public void insertUpload(NewUpload upload) {
        insertFileJob(upload, IngestionJobType.FINANCIAL_STATEMENT, null, null);
    }

    /** A queued job of an uploaded file (financial statement workbook, RAG PDF), with its company when known. */
    public void insertFileJob(NewUpload upload, IngestionJobType type, String exchange, String ticker) {
        jdbc.sql("""
                        INSERT INTO ingestion_job (job_id, job_type, status, stage, exchange, ticker, file_id, file_name,
                                                   file_reused, created_by, created_by_username)
                        VALUES (:id, :type, :status, :stage, :exchange, :ticker, :fileId, :fileName, :reused,
                                :createdBy, :createdByUsername)""")
                .param("id", upload.id())
                .param("type", type.name())
                .param("exchange", exchange, Types.VARCHAR)
                .param("ticker", ticker, Types.VARCHAR)
                .param("status", IngestionJobStatus.QUEUED.name())
                .param("stage", truncate(upload.stage()), Types.VARCHAR)
                .param("fileId", upload.fileId())
                .param("fileName", upload.fileName())
                .param("reused", upload.fileReused())
                .param("createdBy", upload.requestedBy() == null ? null : upload.requestedBy().userId(), Types.BIGINT)
                .param("createdByUsername", upload.requestedBy() == null ? null : upload.requestedBy().username(),
                        Types.VARCHAR)
                .update();
    }

    /** The worker picked the job up: RUNNING, one more attempt; the start time of the first attempt is kept. */
    public void running(UUID id, String stage) {
        jdbc.sql("""
                        UPDATE ingestion_job SET
                            status = 'RUNNING', stage = :stage, attempts = attempts + 1,
                            started_at = COALESCE(started_at, now()), resume_at = NULL, message = NULL,
                            updated_at = now()
                        WHERE job_id = :id""")
                .param("id", id)
                .param("stage", truncate(stage), Types.VARCHAR)
                .update();
    }

    /** Next step of a running job; exchange / ticker are set once known (null keeps the stored value). */
    public void progress(UUID id, String stage, String exchange, String ticker) {
        jdbc.sql("""
                        UPDATE ingestion_job SET
                            stage = :stage,
                            exchange = COALESCE(:exchange, exchange),
                            ticker = COALESCE(:ticker, ticker),
                            updated_at = now()
                        WHERE job_id = :id""")
                .param("id", id)
                .param("stage", truncate(stage), Types.VARCHAR)
                .param("exchange", exchange, Types.VARCHAR)
                .param("ticker", ticker, Types.VARCHAR)
                .update();
    }

    /** Final state of a job. */
    public void finish(UUID id, IngestionJobStatus status, String stage, String message, Object result) {
        if (status.active()) {
            throw new IllegalArgumentException("not a final status: " + status);
        }
        jdbc.sql("""
                        UPDATE ingestion_job SET
                            status = :status, stage = :stage, message = :message, result = CAST(:result AS jsonb),
                            finished_at = now(), resume_at = NULL, updated_at = now()
                        WHERE job_id = :id""")
                .param("id", id)
                .param("status", status.name())
                .param("stage", truncate(stage), Types.VARCHAR)
                .param("message", message, Types.VARCHAR)
                .param("result", toJson(id, result), Types.VARCHAR)
                .update();
    }

    /**
     * Fails every active job. Called once at startup: the queues live in memory, so a job that was
     * queued or running when the application stopped will never finish.
     */
    public int failActive(String message) {
        return jdbc.sql("""
                        UPDATE ingestion_job SET
                            status = 'FAILED', stage = 'Interrupted', message = :message,
                            finished_at = now(), resume_at = NULL, updated_at = now()
                        WHERE status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT')""")
                .param("message", message)
                .update();
    }

    /**
     * One model call of an upload job, with its tokens and cost. Written while the job runs, so the cost of a job
     * that is stopped or fails is kept as well.
     */
    public void recordModelCall(UUID jobId, IngestionJob.ModelCall call) {
        String error = call.error() == null || call.error().length() <= ERROR_LENGTH ? call.error()
                : call.error().substring(0, ERROR_LENGTH);
        jdbc.sql("""
                        INSERT INTO llm_usage (job_id, stage, ticker, model, prompt_tokens, completion_tokens,
                            reasoning_tokens, cached_tokens, cost_usd, cost_estimated, duration_ms, error)
                        VALUES (:job, :stage, :ticker, :model, :prompt, :completion, :reasoning, :cached, :cost,
                                :estimated, :ms, :error)""")
                .param("job", jobId)
                .param("stage", call.stage())
                .param("ticker", call.ticker(), Types.VARCHAR)
                .param("model", call.model())
                .param("prompt", call.promptTokens())
                .param("completion", call.completionTokens())
                .param("reasoning", call.reasoningTokens())
                .param("cached", call.cachedTokens())
                .param("cost", call.costUsd())
                .param("estimated", call.costEstimated())
                .param("ms", (int) Math.min(Integer.MAX_VALUE, call.durationMs()))
                .param("error", error, Types.VARCHAR)
                .update();
    }

    // ------------------------------------------------------------------ reads

    /** One job with its result and its model calls per stage and model. */
    public Optional<IngestionJob> find(UUID id) {
        return jdbc.sql(SELECT.formatted(", j.result::text AS result") + " WHERE j.job_id = :id")
                .param("id", id)
                .query((rs, i) -> map(rs, true))
                .optional()
                .map(job -> job.cost() == null ? job : withUsage(job));
    }

    /** The model calls of an upload job that failed, in the order they were made (the error log names them). */
    public List<IngestionJob.FailedCall> failedModelCalls(UUID jobId) {
        return jdbc.sql("""
                        SELECT stage, model, error, duration_ms, created_at FROM llm_usage
                        WHERE job_id = :id AND error IS NOT NULL ORDER BY usage_id""")
                .param("id", jobId)
                .query((rs, i) -> new IngestionJob.FailedCall(rs.getString("stage"), rs.getString("model"),
                        rs.getString("error"), rs.getLong("duration_ms"), instant(rs, "created_at")))
                .list();
    }

    private IngestionJob withUsage(IngestionJob job) {
        List<IngestionJob.Usage> usage = jdbc.sql(USAGE)
                .param("id", job.id())
                .query((rs, i) -> new IngestionJob.Usage(rs.getString("stage"), rs.getString("model"), rs.getInt("calls"),
                        rs.getLong("prompt"), rs.getLong("completion"), rs.getLong("reasoning"), rs.getLong("cached"),
                        rs.getDouble("cost"), rs.getBoolean("estimated"), rs.getInt("errors")))
                .list();
        return new IngestionJob(job.id(), job.type(), job.status(), job.stage(), job.exchange(), job.ticker(),
                job.file(), job.fullHistory(), job.attempts(), job.message(), job.requestedAt(), job.startedAt(),
                job.finishedAt(), job.resumeAt(), job.updatedAt(), job.createdBy(), job.result(), job.cost(), usage);
    }

    /**
     * One page of jobs, most recent first, without their results: {@code limit} jobs after skipping {@code offset};
     * {@code type} / {@code statuses} null or empty = all.
     */
    public List<IngestionJob> list(IngestionJobType type, List<IngestionJobStatus> statuses, int limit, int offset) {
        List<String> where = new ArrayList<>();
        if (type != null) {
            where.add("j.job_type = :type");
        }
        if (statuses != null && !statuses.isEmpty()) {
            where.add("j.status IN (:statuses)");
        }
        String sql = SELECT.formatted("")
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY j.requested_at DESC, j.job_id LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("limit", limit).param("offset", offset);
        if (type != null) {
            spec = spec.param("type", type.name());
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.param("statuses", statuses.stream().map(Enum::name).toList());
        }
        return spec.query((rs, i) -> map(rs, false)).list();
    }

    /** Number of jobs per status (every status present, 0 when none); {@code type} null = all types. */
    public Map<IngestionJobStatus, Long> countByStatus(IngestionJobType type) {
        Map<IngestionJobStatus, Long> counts = new EnumMap<>(IngestionJobStatus.class);
        for (IngestionJobStatus status : IngestionJobStatus.values()) {
            counts.put(status, 0L);
        }
        String sql = "SELECT status, count(*) AS n FROM ingestion_job"
                + (type == null ? "" : " WHERE job_type = :type") + " GROUP BY status";
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (type != null) {
            spec = spec.param("type", type.name());
        }
        spec.query((rs, i) -> Map.entry(IngestionJobStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /**
     * The workbook of an upload job, for download.
     *
     * @param fileName name of this job's upload (the stored row may carry the name of an earlier upload)
     */
    public record UploadedFile(String fileName, String checksumSha256, byte[] content) {
    }

    /** The stored workbook of an upload job; empty for an unknown job or a job without file (PRICE). */
    public Optional<UploadedFile> uploadedFile(UUID jobId) {
        return jdbc.sql("""
                        SELECT COALESCE(j.file_name, f.file_name) AS file_name, f.checksum_sha256, f.content
                        FROM ingestion_job j
                        JOIN ingestion_file f ON f.file_id = j.file_id
                        WHERE j.job_id = :id""")
                .param("id", jobId)
                .query((rs, i) -> new UploadedFile(rs.getString("file_name"), rs.getString("checksum_sha256"),
                        rs.getBytes("content")))
                .optional();
    }

    /** The active upload job of a stored file, if any (the oldest one). */
    public Optional<UUID> activeUploadOf(long fileId) {
        return jdbc.sql("""
                        SELECT job_id FROM ingestion_job
                        WHERE job_type = 'FINANCIAL_STATEMENT' AND file_id = :fileId
                          AND status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT')
                        ORDER BY requested_at LIMIT 1""")
                .param("fileId", fileId)
                .query((rs, i) -> rs.getObject("job_id", UUID.class))
                .optional();
    }

    /** The oldest active job of a type on an exchange, if any (one ETL run per exchange at a time). */
    public Optional<UUID> activeJobOf(IngestionJobType type, String exchange) {
        return jdbc.sql("""
                        SELECT job_id FROM ingestion_job
                        WHERE job_type = :type AND exchange = :exchange
                          AND status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT')
                        ORDER BY requested_at LIMIT 1""")
                .param("type", type.name())
                .param("exchange", exchange)
                .query((rs, i) -> rs.getObject("job_id", UUID.class))
                .optional();
    }

    /** The oldest active job of a type for a company, if any. */
    public Optional<UUID> activeJobOf(IngestionJobType type, String exchange, String ticker) {
        return jdbc.sql("""
                        SELECT job_id FROM ingestion_job
                        WHERE job_type = :type AND exchange = :exchange AND ticker = :ticker
                          AND status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT')
                        ORDER BY requested_at LIMIT 1""")
                .param("type", type.name())
                .param("exchange", exchange)
                .param("ticker", ticker)
                .query((rs, i) -> rs.getObject("job_id", UUID.class))
                .optional();
    }

    /** The oldest active job of a type for a stored file (of any exchange and company), if any. */
    public Optional<UUID> activeFileJobOf(IngestionJobType type, long fileId) {
        return jdbc.sql("""
                        SELECT job_id FROM ingestion_job
                        WHERE job_type = :type AND file_id = :fileId
                          AND status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT')
                        ORDER BY requested_at LIMIT 1""")
                .param("type", type.name())
                .param("fileId", fileId)
                .query((rs, i) -> rs.getObject("job_id", UUID.class))
                .optional();
    }

    /** The oldest active job of a type for a stored file and company, if any. */
    public Optional<UUID> activeFileJobOf(IngestionJobType type, long fileId, String exchange, String ticker) {
        return jdbc.sql("""
                        SELECT job_id FROM ingestion_job
                        WHERE job_type = :type AND file_id = :fileId AND exchange = :exchange AND ticker = :ticker
                          AND status IN ('QUEUED', 'RUNNING', 'WAITING_RATE_LIMIT')
                        ORDER BY requested_at LIMIT 1""")
                .param("type", type.name())
                .param("fileId", fileId)
                .param("exchange", exchange)
                .param("ticker", ticker)
                .query((rs, i) -> rs.getObject("job_id", UUID.class))
                .optional();
    }

    // ------------------------------------------------------------------ mapping

    private IngestionJob map(ResultSet rs, boolean withResult) throws SQLException {
        long fileId = rs.getLong("file_id");
        IngestionJob.FileRef file = rs.wasNull() ? null : new IngestionJob.FileRef(fileId, rs.getString("file_name"),
                rs.getLong("size_bytes"), rs.getString("checksum_sha256"), rs.getBoolean("file_reused"));
        Boolean full = rs.getBoolean("full_history");
        if (rs.wasNull()) {
            full = null;
        }
        JsonNode result = withResult ? fromJson(rs.getString("result")) : null;
        Long createdById = rs.getLong("created_by");
        if (rs.wasNull()) {
            createdById = null;
        }
        String createdByUsername = rs.getString("created_by_username");
        IngestionJob.CreatedBy createdBy = createdByUsername == null ? null
                : new IngestionJob.CreatedBy(createdById, createdByUsername, rs.getString("created_by_full_name"));
        return new IngestionJob(rs.getObject("job_id", UUID.class),
                IngestionJobType.valueOf(rs.getString("job_type")),
                IngestionJobStatus.valueOf(rs.getString("status")),
                rs.getString("stage"), rs.getString("exchange"), rs.getString("ticker"), file, full,
                rs.getInt("attempts"), rs.getString("message"),
                instant(rs, "requested_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
                instant(rs, "resume_at"), instant(rs, "updated_at"), createdBy, result, cost(rs), null);
    }

    /** The model calls of the job together; {@code null} when it made none. */
    private static IngestionJob.Cost cost(ResultSet rs) throws SQLException {
        int calls = rs.getInt("cost_calls");
        if (calls == 0) {
            return null;
        }
        return new IngestionJob.Cost(calls, rs.getInt("cost_errors"), rs.getLong("cost_prompt"),
                rs.getLong("cost_completion"), rs.getLong("cost_reasoning"), rs.getLong("cost_cached"),
                rs.getDouble("cost_usd"), rs.getBoolean("cost_estimated"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static String truncate(String stage) {
        return stage == null || stage.length() <= STAGE_LENGTH ? stage : stage.substring(0, STAGE_LENGTH - 3) + "...";
    }

    /**
     * Serializes a result for the JSONB column. PostgreSQL rejects the NUL character in JSONB, so
     * any (escaped) NUL in text from the model or a provider is dropped. A result that cannot be
     * serialized is not stored (logged), the job state still is.
     */
    private String toJson(UUID id, Object result) {
        if (result == null) {
            return null;
        }
        try {
            String text = json.writeValueAsString(result);
            return text.indexOf("\\u0000") < 0 ? text : text.replace("\\u0000", "");
        } catch (JacksonException e) {
            log.warn("ingestion job {}: result not stored, it cannot be serialized: {}", id, e.getMessage());
            return null;
        }
    }

    private JsonNode fromJson(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            log.warn("stored ingestion job result is not valid JSON: {}", e.getMessage());
            return null;
        }
    }
}
