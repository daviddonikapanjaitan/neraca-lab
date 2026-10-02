package com.neracalab.backend.price;

import java.time.Instant;
import java.util.UUID;

import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceIngestionService.Result;

/** One queued ingestion of one company. State changes come from the worker thread only. */
public final class PriceIngestionJob {

    public enum Status {
        /** waiting in the queue */
        QUEUED,
        /** being fetched / written */
        RUNNING,
        /** the provider answered HTTP 429; the queue is paused until {@code resumeAt}, then this job is retried */
        WAITING_RATE_LIMIT,
        SUCCEEDED,
        FAILED;

        boolean active() {
            return this == QUEUED || this == RUNNING || this == WAITING_RATE_LIMIT;
        }
    }

    /**
     * JSON view of a job.
     *
     * @param attempts runs of the job (more than 1 after rate-limit waits)
     * @param resumeAt end of the current rate-limit wait (status WAITING_RATE_LIMIT)
     * @param message  why the job failed or is waiting
     * @param result   outcome of a SUCCEEDED job
     */
    public record View(UUID id, String exchange, String ticker, boolean full, Status status, Instant requestedAt,
                       Instant startedAt, Instant finishedAt, Instant resumeAt, int attempts, String message,
                       Result result) {
    }

    private final UUID id = UUID.randomUUID();
    private final CompanyRef company;
    private final boolean full;
    private final Instant requestedAt = Instant.now();

    private Status status = Status.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant resumeAt;
    private int attempts;
    private String message;
    private Result result;

    PriceIngestionJob(CompanyRef company, boolean full) {
        this.company = company;
        this.full = full;
    }

    public UUID id() {
        return id;
    }

    CompanyRef company() {
        return company;
    }

    boolean full() {
        return full;
    }

    synchronized boolean isActive() {
        return status.active();
    }

    synchronized void running() {
        status = Status.RUNNING;
        attempts++;
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        resumeAt = null;
        message = null;
    }

    synchronized void waitingForRateLimit(Instant resumeAt, String message) {
        this.status = Status.WAITING_RATE_LIMIT;
        this.resumeAt = resumeAt;
        this.message = message;
    }

    synchronized void succeeded(Result result) {
        this.status = Status.SUCCEEDED;
        this.result = result;
        this.finishedAt = Instant.now();
    }

    synchronized void failed(String message) {
        this.status = Status.FAILED;
        this.message = message;
        this.resumeAt = null;
        this.finishedAt = Instant.now();
    }

    public synchronized View view() {
        return new View(id, company.exchange().code(), company.ticker(), full, status, requestedAt, startedAt,
                finishedAt, resumeAt, attempts, message, result);
    }
}
