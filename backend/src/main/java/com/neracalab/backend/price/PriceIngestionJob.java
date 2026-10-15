package com.neracalab.backend.price;

import java.time.Instant;
import java.util.UUID;

import com.neracalab.backend.job.Requester;
import com.neracalab.backend.price.IndexIngestion.IndexRef;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceIngestionService.Result;

/**
 * One queued ingestion of one company, or of an exchange's index (IHSG). State changes come only from the worker thread
 * that processes it.
 */
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
     * @param message     why the job failed or is waiting
     * @param result      outcome of a SUCCEEDED job
     * @param requestedBy user who requested the prices ({@code null}: scheduled run)
     * @param index       the job ingests the exchange's index ({@code ticker} = its code, e.g. IHSG), not a company
     */
    public record View(UUID id, String exchange, String ticker, boolean full, Status status, Instant requestedAt,
                       Instant startedAt, Instant finishedAt, Instant resumeAt, int attempts, String message,
                       Result result, Requester requestedBy, boolean index) {
    }

    private final UUID id = UUID.randomUUID();
    /** the company, or {@code null} for an index job */
    private final CompanyRef company;
    /** the index, or {@code null} for a company job */
    private final IndexRef index;
    private final boolean full;
    private final Requester requestedBy;
    private final Instant requestedAt = Instant.now();

    private Status status = Status.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant resumeAt;
    private int attempts;
    private String message;
    private Result result;

    /** @param requestedBy {@code null} for a scheduled run */
    PriceIngestionJob(CompanyRef company, boolean full, Requester requestedBy) {
        this(company, null, full, requestedBy);
    }

    /** @param requestedBy {@code null} for a scheduled run */
    PriceIngestionJob(IndexRef index, boolean full, Requester requestedBy) {
        this(null, index, full, requestedBy);
    }

    private PriceIngestionJob(CompanyRef company, IndexRef index, boolean full, Requester requestedBy) {
        if ((company == null) == (index == null)) {
            throw new IllegalArgumentException("a price ingestion job is of one company or of one index");
        }
        this.company = company;
        this.index = index;
        this.full = full;
        this.requestedBy = requestedBy;
    }

    public UUID id() {
        return id;
    }

    /** The company ({@code null} for an index job). */
    CompanyRef company() {
        return company;
    }

    /** The index ({@code null} for a company job). */
    IndexRef index() {
        return index;
    }

    /** Ticker of the company, or code of the index. */
    String ticker() {
        return company != null ? company.ticker() : index.code();
    }

    /** Whether both jobs ingest the same company, or the same index. */
    boolean sameTarget(PriceIngestionJob other) {
        if (company != null) {
            return other.company != null && other.company.companyId() == company.companyId();
        }
        return other.index != null && other.index.code().equals(index.code())
                && other.index.exchange().equals(index.exchange());
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
        String exchange = company != null ? company.exchange().code() : index.exchange().code();
        return new View(id, exchange, ticker(), full, status, requestedAt, startedAt, finishedAt, resumeAt, attempts,
                message, result, requestedBy, index != null);
    }
}
