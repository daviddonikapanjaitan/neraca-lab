package com.neracalab.backend.job;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * One row of {@code ingestion_job} as the API returns it.
 *
 * @param stage       current step of an active job, or a one-line summary of a finished one
 * @param ticker      company ticker; for an upload known once the workbook has been read
 * @param file        the uploaded workbook (FINANCIAL_STATEMENT only)
 * @param fullHistory price ingestion with {@code full=true} (PRICE only)
 * @param attempts    runs of the job (more than 1 after price provider rate-limit waits)
 * @param resumeAt    end of the current rate-limit wait (status WAITING_RATE_LIMIT)
 * @param message     why the job failed, is waiting or is incomplete
 * @param createdBy   the user who started the job (upload or price request); {@code null} for a
 *                    scheduled run
 * @param result      outcome of a finished job; only returned by {@code GET /api/v1/ingestions/{id}}
 */
public record IngestionJob(UUID id, IngestionJobType type, IngestionJobStatus status, String stage,
                           String exchange, String ticker, FileRef file, Boolean fullHistory, int attempts,
                           String message, Instant requestedAt, Instant startedAt, Instant finishedAt,
                           Instant resumeAt, Instant updatedAt, CreatedBy createdBy, JsonNode result) {

    /**
     * Who started a job.
     *
     * @param userId   {@code null} when the user has been deleted since
     * @param username the username when the job was started
     * @param fullName the user's current full name ({@code null}: none, or the user was deleted)
     */
    public record CreatedBy(Long userId, String username, String fullName) {
    }

    /**
     * The workbook of an upload.
     *
     * @param fileName name of this upload
     * @param reused   the same content (checksum) was already stored, so the stored file was used
     */
    public record FileRef(long fileId, String fileName, long sizeBytes, String checksumSha256, boolean reused) {
    }
}
