package com.neracalab.backend.ingestion.error;

import java.sql.Types;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ingestion_error}: the error log of the .xlsx ingestion API. Every upload that was rejected, that the
 * standard extraction could not store (a validation the workbook fails, open items, too slow) or that failed
 * is recorded with what went wrong and how it ended. Claude Code reads the open rows, fixes the cause and sets
 * {@code fixed_by_claude_code}; the application only inserts.
 * <p>
 * Recording never fails an upload: a database error here is logged and swallowed.
 */
@Repository
public class IngestionErrorRepository {

    private static final Logger log = LoggerFactory.getLogger(IngestionErrorRepository.class);

    /** Why the standard extraction did not store the workbook. */
    public enum Type {
        /** not a readable IDX XBRL workbook: refused with HTTP 422, nothing stored */
        UPLOAD_REJECTED,
        /** the standard extraction cannot map the workbook (e.g. no balance sheet data) */
        UNSUPPORTED_WORKBOOK,
        /** the standard extraction left open items (a validation the filing fails, a statement not saved) */
        INCOMPLETE,
        /** the standard extraction ran longer than {@code neracalab.ingestion.fallback.after} */
        SLOW_EXTRACTION,
        /** the standard extraction failed */
        EXTRACTION_FAILED
    }

    /**
     * One error.
     *
     * @param jobId           the ingestion job; null for an upload rejected before a job existed
     * @param period          current period of the filing ("2025 FY"), when the workbook could be read
     * @param message         what went wrong in the standard extraction
     * @param details         serialized to JSON (problems, open items, the full-text extraction's report); may be null
     * @param fallbackUsed    the full-text extraction took over
     * @param fallbackColumns columns it worked on ("PRIOR_PERIOD"); null when it did not run
     * @param finalStatus     SUCCEEDED, INCOMPLETE, FAILED or REJECTED
     * @param finalMessage    the job's final stage and message
     */
    public record NewError(UUID jobId, Long fileId, String fileName, String exchange, String ticker, String period,
                           Type type, String message, Object details, boolean fallbackUsed, String fallbackColumns,
                           String finalStatus, String finalMessage) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IngestionErrorRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Records the error (a job at most once: a second record of the same job replaces the first). */
    public void record(NewError e) {
        try {
            jdbc.sql("""
                            INSERT INTO ingestion_error (job_id, file_id, file_name, exchange, ticker, period, error_type,
                                                         message, details, fallback_used, fallback_columns, final_status,
                                                         final_message)
                            VALUES (:job, :file, :fileName, :exchange, :ticker, :period, :type, :message,
                                    CAST(:details AS jsonb), :fallbackUsed, :fallbackColumns, :finalStatus, :finalMessage)
                            ON CONFLICT (job_id) WHERE job_id IS NOT NULL DO UPDATE SET
                                error_type = EXCLUDED.error_type, message = EXCLUDED.message, details = EXCLUDED.details,
                                fallback_used = EXCLUDED.fallback_used, fallback_columns = EXCLUDED.fallback_columns,
                                final_status = EXCLUDED.final_status, final_message = EXCLUDED.final_message""")
                    .param("job", e.jobId(), Types.OTHER)
                    .param("file", e.fileId(), Types.BIGINT)
                    .param("fileName", truncate(e.fileName(), 255), Types.VARCHAR)
                    .param("exchange", e.exchange(), Types.VARCHAR)
                    .param("ticker", e.ticker() != null && e.ticker().length() <= 20 ? e.ticker() : null, Types.VARCHAR)
                    .param("period", truncate(e.period(), 30), Types.VARCHAR)
                    .param("type", e.type().name())
                    .param("message", e.message() == null || e.message().isBlank() ? "(no message)" : e.message())
                    .param("details", toJson(e.details()), Types.VARCHAR)
                    .param("fallbackUsed", e.fallbackUsed())
                    .param("fallbackColumns", truncate(e.fallbackColumns(), 100), Types.VARCHAR)
                    .param("finalStatus", e.finalStatus(), Types.VARCHAR)
                    .param("finalMessage", e.finalMessage(), Types.VARCHAR)
                    .update();
        } catch (RuntimeException failure) {
            log.error("ingestion error of {} ({}) could not be recorded: {}", e.fileName(), e.type(), e.message(), failure);
        }
    }

    /** Errors Claude Code has not fixed yet. */
    public long openCount() {
        return jdbc.sql("SELECT count(*) FROM ingestion_error WHERE NOT fixed_by_claude_code").query(Long.class).single();
    }

    private String toJson(Object details) {
        if (details == null) {
            return null;
        }
        try {
            return json.writeValueAsString(details);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
