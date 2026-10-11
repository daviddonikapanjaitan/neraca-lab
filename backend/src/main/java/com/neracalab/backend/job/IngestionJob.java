package com.neracalab.backend.job;

import java.time.Instant;
import java.util.List;
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
 * @param cost        tokens and cost of the job's model calls so far ({@code llm_usage}); {@code null} for a job
 *                    without model calls (prices, PDF, ...) and for uploads from before the cost was recorded
 * @param usage       the model calls per stage and model; only returned by {@code GET /api/v1/ingestions/{id}}
 */
public record IngestionJob(UUID id, IngestionJobType type, IngestionJobStatus status, String stage,
                           String exchange, String ticker, FileRef file, Boolean fullHistory, int attempts,
                           String message, Instant requestedAt, Instant startedAt, Instant finishedAt,
                           Instant resumeAt, Instant updatedAt, CreatedBy createdBy, JsonNode result,
                           Cost cost, List<Usage> usage) {

    /**
     * Tokens and cost of all model calls of a job.
     *
     * @param modelCalls    attempts made, failed ones included
     * @param failedCalls   attempts that failed (timeout, provider error); they carry no tokens and no cost
     * @param costUsd       USD, as reported by OpenRouter
     * @param costEstimated at least one call had no reported cost and was priced from the list prices
     */
    public record Cost(int modelCalls, int failedCalls, long promptTokens, long completionTokens, long reasoningTokens,
                       long cachedTokens, double costUsd, boolean costEstimated) {
    }

    /**
     * The model calls of a job for one stage and model (the shape of the usage rows of a screening).
     *
     * @param stage  PLAN, EXECUTE, REVIEW (standard extraction) or FULL_TEXT (full-text extraction); the stages of
     *               a screening or an analysis for those jobs
     * @param errors attempts that failed
     */
    public record Usage(String stage, String model, int calls, long promptTokens, long completionTokens,
                        long reasoningTokens, long cachedTokens, double costUsd, boolean costEstimated, int errors) {
    }

    /**
     * A model call of a job that failed (a timeout, a stream reset, a provider error), for the error log.
     *
     * @param error what the provider or the client said
     */
    public record FailedCall(String stage, String model, String error, long durationMs, Instant at) {
    }

    /**
     * One model call to record ({@code llm_usage}).
     *
     * @param error why the attempt failed; {@code null} when it answered
     */
    public record ModelCall(String stage, String ticker, String model, int promptTokens, int completionTokens,
                            int reasoningTokens, int cachedTokens, double costUsd, boolean costEstimated,
                            long durationMs, String error) {
    }

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
