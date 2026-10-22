package com.neracalab.backend.macro.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.Snapshot;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.JobWorkerPool;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.macro.outlook.MacroOutlookService.Choice;

/**
 * Macro analyses run as background jobs ({@code ingestion_job} type MACRO_ANALYSIS, exchange = the country's exchange,
 * ticker = the index), one at a time on one worker thread (each calls several models in parallel itself).
 * {@link #submit} records the job (QUEUED) and its parameters and returns at once; the report page follows the job
 * until it is SUCCEEDED, INCOMPLETE or FAILED. An index already queued or being analysed is not queued twice: its active
 * analysis is returned.
 */
@Component
public class MacroAnalysisQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MacroAnalysisQueue.class);

    /** @param created false: the index's active analysis was returned */
    public record Submission(UUID id, boolean created) {
    }

    private final MacroAnalysisRepository repository;
    private final IngestionJobRepository jobs;
    private final MacroAnalysisProperties properties;
    private final TransactionTemplate transaction;
    private final JobWorkerPool worker;

    public MacroAnalysisQueue(MacroAnalysisService service, MacroAnalysisRepository repository, IngestionJobRepository jobs,
                              MacroAnalysisProperties properties, TransactionTemplate transaction) {
        this.repository = repository;
        this.jobs = jobs;
        this.properties = properties;
        this.transaction = transaction;
        this.worker = new JobWorkerPool("macro-analysis", 1, jobs, service::run);
    }

    /** Queues a macro analysis of a country's index; the agents are validated by the caller. */
    public synchronized Submission submit(Choice choice, List<MacroAgent> agents, Requester requestedBy) {
        Optional<UUID> active = jobs.activeJobOf(IngestionJobType.MACRO_ANALYSIS, choice.exchange(), choice.index());
        if (active.isPresent()) {
            return new Submission(active.get(), false);
        }
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jobs.save(new Snapshot(id, IngestionJobType.MACRO_ANALYSIS, IngestionJobStatus.QUEUED,
                    "Waiting in the macro analysis queue", choice.exchange(), choice.index(), null, 0, null, Instant.now(),
                    null, null, null, null, requestedBy));
            repository.insertRun(new MacroAnalysisRepository.Parameters(id, choice.country(), choice.index(), agents,
                    properties.budgetUsd()), choice.countryName(), choice.indexName());
        });
        worker.enqueue(id);
        log.info("macro analysis {} of {} {} queued by {}: {}", id, choice.country(), choice.index(),
                requestedBy == null ? "-" : requestedBy.username(), agents);
        return new Submission(id, true);
    }

    /** Analyses waiting (excluding the running one). */
    public int pendingCount() {
        return worker.pendingCount();
    }

    @Override
    public void start() {
        worker.start();
    }

    @Override
    public void stop() {
        worker.stop();
    }

    @Override
    public boolean isRunning() {
        return worker.isRunning();
    }
}
