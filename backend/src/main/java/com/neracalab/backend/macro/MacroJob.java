package com.neracalab.backend.macro;

import java.time.Instant;
import java.util.UUID;

import com.neracalab.backend.job.Requester;
import com.neracalab.backend.macro.MacroIngestionService.Result;

/** One queued ingestion of one macro series. State changes come only from the worker thread that processes it. */
public final class MacroJob {

    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, FAILED;

        boolean active() {
            return this == QUEUED || this == RUNNING;
        }
    }

    /**
     * JSON view of a job.
     *
     * @param code        the series ({@code ingestion_job.ticker})
     * @param requestedBy user who requested it ({@code null}: scheduled run)
     */
    public record View(UUID id, String code, String name, String source, boolean full, Status status,
                       Instant requestedAt, Instant startedAt, Instant finishedAt, int attempts, String message,
                       Result result, Requester requestedBy) {
    }

    private final UUID id = UUID.randomUUID();
    private final MacroSeries series;
    private final boolean full;
    private final Requester requestedBy;
    private final Instant requestedAt = Instant.now();

    private Status status = Status.QUEUED;
    private Instant startedAt;
    private Instant finishedAt;
    private int attempts;
    private String message;
    private Result result;

    MacroJob(MacroSeries series, boolean full, Requester requestedBy) {
        this.series = series;
        this.full = full;
        this.requestedBy = requestedBy;
    }

    public UUID id() {
        return id;
    }

    MacroSeries series() {
        return series;
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
        startedAt = Instant.now();
    }

    synchronized void succeeded(Result result) {
        status = Status.SUCCEEDED;
        this.result = result;
        finishedAt = Instant.now();
    }

    synchronized void failed(String message) {
        status = Status.FAILED;
        this.message = message;
        finishedAt = Instant.now();
    }

    public synchronized View view() {
        return new View(id, series.code(), series.name(), series.source(), full, status, requestedAt, startedAt,
                finishedAt, attempts, message, result, requestedBy);
    }
}
