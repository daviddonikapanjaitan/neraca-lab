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
                   j.finished_at, j.resume_at, j.updated_at, f.size_bytes, f.checksum_sha256%s
            FROM ingestion_job j
            LEFT JOIN ingestion_file f ON f.file_id = j.file_id""";

    /**
     * Full state of a job, written by {@link #save}.
     *
     * @param result object serialized to JSON ({@code null}: none)
     */
    public record Snapshot(UUID id, IngestionJobType type, IngestionJobStatus status, String stage, String exchange,
                           String ticker, Boolean fullHistory, int attempts, String message, Instant requestedAt,
                           Instant startedAt, Instant finishedAt, Instant resumeAt, Object result) {
    }

    /** A new upload job. */
    public record NewUpload(UUID id, long fileId, String fileName, boolean fileReused, String stage) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IngestionJobRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ writes

    /** Inserts or fully replaces a job (the price queue writes every state change this way). File columns are kept. */
    public void save(Snapshot s) {
        jdbc.sql("""
                        INSERT INTO ingestion_job (
                            job_id, job_type, status, stage, exchange, ticker, full_history, attempts, message,
                            result, requested_at, started_at, finished_at, resume_at, updated_at)
                        VALUES (:id, :type, :status, :stage, :exchange, :ticker, :full, :attempts, :message,
                                CAST(:result AS jsonb), :requestedAt, :startedAt, :finishedAt, :resumeAt, now())
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
                .update();
    }

    /** A queued financial statement upload. */
    public void insertUpload(NewUpload upload) {
        jdbc.sql("""
                        INSERT INTO ingestion_job (job_id, job_type, status, stage, file_id, file_name, file_reused)
                        VALUES (:id, :type, :status, :stage, :fileId, :fileName, :reused)""")
                .param("id", upload.id())
                .param("type", IngestionJobType.FINANCIAL_STATEMENT.name())
                .param("status", IngestionJobStatus.QUEUED.name())
                .param("stage", truncate(upload.stage()), Types.VARCHAR)
                .param("fileId", upload.fileId())
                .param("fileName", upload.fileName())
                .param("reused", upload.fileReused())
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

    // ------------------------------------------------------------------ reads

    /** One job with its result. */
    public Optional<IngestionJob> find(UUID id) {
        return jdbc.sql(SELECT.formatted(", j.result::text AS result") + " WHERE j.job_id = :id")
                .param("id", id)
                .query((rs, i) -> map(rs, true))
                .optional();
    }

    /** Jobs, most recent first, without their results; {@code type} / {@code statuses} null or empty = all. */
    public List<IngestionJob> list(IngestionJobType type, List<IngestionJobStatus> statuses, int limit) {
        List<String> where = new ArrayList<>();
        if (type != null) {
            where.add("j.job_type = :type");
        }
        if (statuses != null && !statuses.isEmpty()) {
            where.add("j.status IN (:statuses)");
        }
        String sql = SELECT.formatted("")
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY j.requested_at DESC, j.job_id LIMIT :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("limit", limit);
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
        return new IngestionJob(rs.getObject("job_id", UUID.class),
                IngestionJobType.valueOf(rs.getString("job_type")),
                IngestionJobStatus.valueOf(rs.getString("status")),
                rs.getString("stage"), rs.getString("exchange"), rs.getString("ticker"), file, full,
                rs.getInt("attempts"), rs.getString("message"),
                instant(rs, "requested_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
                instant(rs, "resume_at"), instant(rs, "updated_at"), result);
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
