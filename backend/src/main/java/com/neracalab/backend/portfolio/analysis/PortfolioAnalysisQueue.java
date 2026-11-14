package com.neracalab.backend.portfolio.analysis;

import java.time.Instant;
import java.util.List;
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
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisRepository.RunParameters;

/**
 * Portfolio analyses as background jobs ({@code ingestion_job} type PORTFOLIO_ANALYSIS), processed one at a time by one
 * worker thread (a run calls many models). {@link #submit} records the job (QUEUED) and the run parameters and returns
 * at once; the page follows the job's stage until it is SUCCEEDED, INCOMPLETE or FAILED.
 */
@Component
public class PortfolioAnalysisQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAnalysisQueue.class);

    private final PortfolioAnalysisRepository repository;
    private final IngestionJobRepository jobs;
    private final TransactionTemplate transaction;
    private final JobWorkerPool worker;

    public PortfolioAnalysisQueue(PortfolioAnalysisService service, PortfolioAnalysisRepository repository,
                                  IngestionJobRepository jobs, TransactionTemplate transaction) {
        this.repository = repository;
        this.jobs = jobs;
        this.transaction = transaction;
        this.worker = new JobWorkerPool("portfolio-analysis", 1, jobs, service::run);
    }

    /** Queues a run; the parameters are validated by the caller. */
    public UUID submit(long userId, String exchange, String mode, String steps, List<String> tickers, int maxStocks,
                       double addedCapital, double budgetUsd, Requester requestedBy) {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jobs.save(new Snapshot(id, IngestionJobType.PORTFOLIO_ANALYSIS, IngestionJobStatus.QUEUED,
                    "Waiting in the portfolio analysis queue", exchange, null, null, 0, null, Instant.now(), null, null,
                    null, null, requestedBy));
            repository.insertRun(new RunParameters(id, userId, exchange, mode, tickers == null ? null
                    : List.copyOf(tickers), maxStocks, addedCapital, budgetUsd, steps));
        });
        worker.enqueue(id);
        log.info("portfolio analysis {} queued by {}: {} {} {} max {} stocks", id,
                requestedBy == null ? "-" : requestedBy.username(), exchange, steps, tickers == null ? mode : tickers,
                maxStocks);
        return id;
    }

    /** Runs waiting (excluding the running one). */
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
