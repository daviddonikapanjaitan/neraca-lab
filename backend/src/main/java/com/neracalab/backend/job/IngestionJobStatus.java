package com.neracalab.backend.job;

import java.util.Arrays;
import java.util.List;

/** {@code ingestion_job.status}. QUEUED, RUNNING and WAITING_RATE_LIMIT are active, the others final. */
public enum IngestionJobStatus {

    /** waiting for the worker */
    QUEUED,
    /** being processed */
    RUNNING,
    /** price provider answered HTTP 429; the price queue is paused until {@code resumeAt}, then the job is retried */
    WAITING_RATE_LIMIT,
    /** everything stored */
    SUCCEEDED,
    /** filing stored, but the database verification found something pending or wrong (see the result) */
    INCOMPLETE,
    FAILED;

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == WAITING_RATE_LIMIT;
    }

    public static List<IngestionJobStatus> activeValues() {
        return Arrays.stream(values()).filter(IngestionJobStatus::active).toList();
    }
}
