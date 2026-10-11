package com.neracalab.backend.analysis;

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
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.screening.InvestorAgent;

/**
 * Analyses run as background jobs ({@code ingestion_job} type ANALYSIS), one at a time on one worker thread (each
 * calls several models in parallel itself). {@link #submit} records the job (QUEUED) and its parameters and
 * returns at once; the report page follows the job until it is SUCCEEDED, INCOMPLETE or FAILED. A company already
 * queued or being analysed is not queued twice: its active analysis is returned.
 */
@Component
public class AnalysisQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AnalysisQueue.class);

    /** @param created false: the company's active analysis was returned */
    public record Submission(UUID id, boolean created) {
    }

    private final AnalysisRepository repository;
    private final IngestionJobRepository jobs;
    private final AnalysisProperties properties;
    private final TransactionTemplate transaction;
    private final JobWorkerPool worker;

    public AnalysisQueue(AnalysisService service, AnalysisRepository repository, IngestionJobRepository jobs,
                         AnalysisProperties properties, TransactionTemplate transaction) {
        this.repository = repository;
        this.jobs = jobs;
        this.properties = properties;
        this.transaction = transaction;
        this.worker = new JobWorkerPool("analysis", 1, jobs, service::run);
    }

    /** Queues an analysis of the company; the agents are validated by the caller. */
    public synchronized Submission submit(Company company, List<InvestorAgent> agents, Requester requestedBy) {
        Optional<UUID> active = jobs.activeJobOf(IngestionJobType.ANALYSIS, company.exchange(), company.ticker());
        if (active.isPresent()) {
            return new Submission(active.get(), false);
        }
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jobs.save(new Snapshot(id, IngestionJobType.ANALYSIS, IngestionJobStatus.QUEUED,
                    "Waiting in the analysis queue", company.exchange(), company.ticker(), null, 0, null, Instant.now(),
                    null, null, null, null, requestedBy));
            repository.insertRun(new AnalysisRepository.Parameters(id, company.companyId(), company.exchange(),
                    company.ticker(), agents, properties.budgetUsd()), company.companyName());
        });
        worker.enqueue(id);
        log.info("analysis {} of {} queued by {}: {}", id, company.ticker(),
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
