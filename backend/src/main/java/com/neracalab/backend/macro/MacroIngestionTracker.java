package com.neracalab.backend.macro;

import org.springframework.stereotype.Component;

import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.Snapshot;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.macro.MacroIngestionService.Result;

/**
 * Records every state change of a macro job in {@code ingestion_job}: type MACRO, exchange MACRO, ticker the series code.
 */
@Component
public class MacroIngestionTracker implements MacroIngestionQueue.Listener {

    /** The exchange column of a macro job. */
    public static final String EXCHANGE = "MACRO";

    private final IngestionJobRepository jobs;

    public MacroIngestionTracker(IngestionJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public void changed(MacroJob.View job) {
        jobs.save(new Snapshot(job.id(), IngestionJobType.MACRO, status(job.status()), stage(job), EXCHANGE, job.code(),
                job.full(), job.attempts(), job.message(), job.requestedAt(), job.startedAt(), job.finishedAt(), null,
                job.result(), job.requestedBy()));
    }

    static IngestionJobStatus status(MacroJob.Status status) {
        return switch (status) {
            case QUEUED -> IngestionJobStatus.QUEUED;
            case RUNNING -> IngestionJobStatus.RUNNING;
            case SUCCEEDED -> IngestionJobStatus.SUCCEEDED;
            case FAILED -> IngestionJobStatus.FAILED;
        };
    }

    static String stage(MacroJob.View job) {
        return switch (job.status()) {
            case QUEUED -> "Waiting in the macro queue";
            case RUNNING -> "Fetching " + (job.full() ? "the full history of " : "the latest observations of ")
                    + job.name() + " from " + sourceLabel(job.source());
            case SUCCEEDED -> succeeded(job.result());
            case FAILED -> "Failed";
        };
    }

    /** "Stored 2 new and 1 revised observations, latest 2026-09-01 (2 warnings)" */
    static String succeeded(Result r) {
        if (r == null) {
            return "Done";
        }
        String latest = r.latestDate() == null ? "" : ", latest " + r.latestDate();
        String warnings = r.warnings().isEmpty() ? "" : " (" + r.warnings().size() + " large "
                + (r.warnings().size() == 1 ? "move" : "moves") + " to check)";
        if (r.inserted() + r.revised() == 0) {
            return "No new observation" + latest + warnings;
        }
        return "Stored " + r.inserted() + " new and " + r.revised() + " revised "
                + (r.inserted() + r.revised() == 1 ? "observation" : "observations") + latest + warnings;
    }

    /** "FRED", "Bank Indonesia", ... */
    public static String sourceLabel(String source) {
        return switch (source) {
            case "fred" -> "FRED";
            case "yahoo" -> "Yahoo Finance";
            case "bi" -> "Bank Indonesia";
            case "bi_seki" -> "Bank Indonesia SEKI";
            case "cnbc" -> "CNBC";
            case "cftc" -> "the CFTC";
            case "manual" -> "a CSV upload";
            default -> source;
        };
    }
}
