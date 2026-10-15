package com.neracalab.backend.price;

import java.time.Instant;
import java.util.UUID;

import com.neracalab.backend.job.Requester;
import com.neracalab.backend.price.IndexIngestion.IndexRef;
import com.neracalab.backend.price.InstrumentIngestion.InstrumentRef;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceIngestionService.Result;

/**
 * One queued ingestion of one company, of an exchange's index (IHSG), or of a Forex pair / commodity. State changes come
 * only from the worker thread that processes it.
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
     * @param instrument  the job ingests a Forex pair or commodity ({@code exchange} = its market FOREX / COMMODITY,
     *                    {@code ticker} = its code, e.g. USDIDR)
     * @param monthly     the instrument has one bar per month (coal)
     */
    public record View(UUID id, String exchange, String ticker, boolean full, Status status, Instant requestedAt,
                       Instant startedAt, Instant finishedAt, Instant resumeAt, int attempts, String message,
                       Result result, Requester requestedBy, boolean index, boolean instrument, boolean monthly) {
    }

    private final UUID id = UUID.randomUUID();
    /** the company, or {@code null} for an index / instrument job */
    private final CompanyRef company;
    /** the index, or {@code null} for a company / instrument job */
    private final IndexRef index;
    /** the Forex pair / commodity, or {@code null} for a company / index job */
    private final InstrumentRef instrument;
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
        this(company, null, null, full, requestedBy);
    }

    /** @param requestedBy {@code null} for a scheduled run */
    PriceIngestionJob(IndexRef index, boolean full, Requester requestedBy) {
        this(null, index, null, full, requestedBy);
    }

    /** @param requestedBy {@code null} for a scheduled run */
    PriceIngestionJob(InstrumentRef instrument, boolean full, Requester requestedBy) {
        this(null, null, instrument, full, requestedBy);
    }

    private PriceIngestionJob(CompanyRef company, IndexRef index, InstrumentRef instrument, boolean full,
                              Requester requestedBy) {
        int targets = (company == null ? 0 : 1) + (index == null ? 0 : 1) + (instrument == null ? 0 : 1);
        if (targets != 1) {
            throw new IllegalArgumentException("a price ingestion job is of one company, one index or one instrument");
        }
        this.company = company;
        this.index = index;
        this.instrument = instrument;
        this.full = full;
        this.requestedBy = requestedBy;
    }

    public UUID id() {
        return id;
    }

    /** The company ({@code null} for an index / instrument job). */
    CompanyRef company() {
        return company;
    }

    /** The index ({@code null} for a company / instrument job). */
    IndexRef index() {
        return index;
    }

    /** The Forex pair / commodity ({@code null} for a company / index job). */
    InstrumentRef instrument() {
        return instrument;
    }

    /** Ticker of the company, or code of the index / instrument. */
    String ticker() {
        return company != null ? company.ticker() : index != null ? index.code() : instrument.code();
    }

    /** Exchange of the company / index, or market of the instrument (FOREX, COMMODITY). */
    String exchange() {
        return company != null ? company.exchange().code() : index != null ? index.exchange().code() : instrument.market();
    }

    /** Whether both jobs ingest the same company, the same index or the same instrument. */
    boolean sameTarget(PriceIngestionJob other) {
        if (company != null) {
            return other.company != null && other.company.companyId() == company.companyId();
        }
        if (index != null) {
            return other.index != null && other.index.code().equals(index.code())
                    && other.index.exchange().equals(index.exchange());
        }
        return other.instrument != null && other.instrument.code().equals(instrument.code())
                && other.instrument.market().equals(instrument.market());
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
        return new View(id, exchange(), ticker(), full, status, requestedAt, startedAt, finishedAt, resumeAt, attempts,
                message, result, requestedBy, index != null, instrument != null,
                instrument != null && instrument.monthly());
    }
}
