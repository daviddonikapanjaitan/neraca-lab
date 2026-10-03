package com.neracalab.backend.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * At startup, before the queues start and the web server accepts requests: fails every job still
 * active in {@code ingestion_job}. The upload and price queues live in memory, so such a job was
 * interrupted by the previous shutdown and would otherwise show as running forever.
 * <p>
 * The database is meant for one backend instance: a second instance starting on the same database
 * (for example the tests) also fails the jobs the first one is still running.
 */
@Component
public class IngestionJobRecovery implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobRecovery.class);

    static final String MESSAGE = "Interrupted: the backend restarted before the job finished. Submit it again "
            + "(an uploaded file is not stored twice).";

    private final IngestionJobRepository jobs;

    public IngestionJobRecovery(IngestionJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public void afterPropertiesSet() {
        int failed = jobs.failActive(MESSAGE);
        if (failed > 0) {
            log.warn("{} ingestion job(s) were still active from the previous run and are marked FAILED", failed);
        }
    }
}
