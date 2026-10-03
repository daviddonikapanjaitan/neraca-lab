package com.neracalab.backend.price;

import org.springframework.stereotype.Component;

import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.Snapshot;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.price.PriceIngestionService.Result;

/** Records every state change of a price ingestion job in {@code ingestion_job} (type PRICE). */
@Component
public class PriceIngestionTracker implements PriceIngestionQueue.Listener {

    private final IngestionJobRepository jobs;
    private final PriceIngestionService service;

    public PriceIngestionTracker(IngestionJobRepository jobs, PriceIngestionService service) {
        this.jobs = jobs;
        this.service = service;
    }

    @Override
    public void changed(PriceIngestionJob.View job) {
        jobs.save(new Snapshot(job.id(), IngestionJobType.PRICE, status(job.status()), stage(job), job.exchange(),
                job.ticker(), job.full(), job.attempts(), job.message(), job.requestedAt(), job.startedAt(),
                job.finishedAt(), job.resumeAt(), job.result(), job.requestedBy()));
    }

    static IngestionJobStatus status(PriceIngestionJob.Status status) {
        return switch (status) {
            case QUEUED -> IngestionJobStatus.QUEUED;
            case RUNNING -> IngestionJobStatus.RUNNING;
            case WAITING_RATE_LIMIT -> IngestionJobStatus.WAITING_RATE_LIMIT;
            case SUCCEEDED -> IngestionJobStatus.SUCCEEDED;
            case FAILED -> IngestionJobStatus.FAILED;
        };
    }

    private String stage(PriceIngestionJob.View job) {
        String provider = providerLabel(service.providerName());
        return switch (job.status()) {
            case QUEUED -> "Waiting in the price queue";
            case RUNNING -> "Fetching " + (job.full() ? "the full price history" : "daily prices") + " from "
                    + provider + " and refreshing the valuation";
            case WAITING_RATE_LIMIT -> provider + " answered HTTP 429 (rate limit); paused, then retried";
            case SUCCEEDED -> succeeded(job.result());
            case FAILED -> "Failed";
        };
    }

    /** "Stored 2 new and 1 updated price days, latest 2026-10-02" */
    static String succeeded(Result result) {
        if (result == null) {
            return "Done";
        }
        String latest = result.latestTradingDate() == null ? "" : ", latest " + result.latestTradingDate();
        if (result.requests() == 0) {
            return "Already up to date" + latest;
        }
        return "Stored " + result.inserted() + " new and " + result.updated() + " updated price "
                + (result.inserted() + result.updated() == 1 ? "day" : "days") + latest;
    }

    static String providerLabel(String name) {
        return switch (name) {
            case "yahoo" -> "Yahoo Finance";
            case "eodhd" -> "EODHD";
            default -> name;
        };
    }
}
