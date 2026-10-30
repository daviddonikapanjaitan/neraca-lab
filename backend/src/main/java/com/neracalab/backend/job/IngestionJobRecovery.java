package com.neracalab.backend.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * At startup, before the queues start and the web server accepts requests: fails every job still
 * active in {@code ingestion_job}. The upload and price queues live in memory, so such a job was
 * interrupted by the previous shutdown and would otherwise show as running forever.
 * <p>
 * The database is meant for one backend instance: a second instance starting on the same database also fails the jobs
 * the first one is still running. The tests are such an instance (they share the live database): a suite run on
 * 2026-10-31 failed 170 of the uploads Hermes had just queued on the running backend. They switch it off
 * ({@code neracalab.jobs.recover-on-start=false} in the test properties).
 */
@Component
public class IngestionJobRecovery implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobRecovery.class);

    static final String MESSAGE = "Interrupted: the backend restarted before the job finished. Submit it again "
            + "(an uploaded file is not stored twice).";

    private final IngestionJobRepository jobs;
    private final boolean enabled;

    public IngestionJobRecovery(IngestionJobRepository jobs,
                                @Value("${neracalab.jobs.recover-on-start:true}") boolean enabled) {
        this.jobs = jobs;
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            return;
        }
        int failed = jobs.failActive(MESSAGE);
        if (failed > 0) {
            log.warn("{} ingestion job(s) were still active from the previous run and are marked FAILED", failed);
        }
    }
}
