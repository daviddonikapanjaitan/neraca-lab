package com.neracalab.backend.technical.screening;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.Snapshot;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.JobWorkerPool;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.technical.screening.TechnicalScreeningRepository.RunParameters;

/**
 * Technical screenings as background jobs ({@code ingestion_job} type TECHNICAL_SCREENING), processed one at a time by
 * one worker thread: a run calls many models, so runs never overlap. {@link #submit} records the job (QUEUED) and the
 * run parameters and returns at once; the page follows the job's stage until it is SUCCEEDED, INCOMPLETE or FAILED.
 */
@Component
public class TechnicalScreeningQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TechnicalScreeningQueue.class);

    private final TechnicalScreeningRepository repository;
    private final IngestionJobRepository jobs;
    private final TechnicalScreeningProperties properties;
    private final TransactionTemplate transaction;
    private final JobWorkerPool worker;

    public TechnicalScreeningQueue(TechnicalScreeningService service, TechnicalScreeningRepository repository,
                                   IngestionJobRepository jobs, TechnicalScreeningProperties properties,
                                   TransactionTemplate transaction) {
        this.repository = repository;
        this.jobs = jobs;
        this.properties = properties;
        this.transaction = transaction;
        this.worker = new JobWorkerPool("technical-screening", 1, jobs, service::run);
    }

    /** Queues a technical screening of a market-cap tier; the parameters are validated by the caller. */
    public UUID submit(Exchange exchange, MarketCapTier tier, int topN, List<TechnicalAgent> agents, Requester requestedBy) {
        return submit(exchange, tier, null, topN, agents, requestedBy);
    }

    /** Queues a technical screening of selected stocks (tickers of the companies table); validated by the caller. */
    public UUID submitSelection(Exchange exchange, List<String> tickers, int topN, List<TechnicalAgent> agents,
                                Requester requestedBy) {
        return submit(exchange, null, List.copyOf(tickers), topN, agents, requestedBy);
    }

    private UUID submit(Exchange exchange, MarketCapTier tier, List<String> tickers, int topN, List<TechnicalAgent> agents,
                        Requester requestedBy) {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jobs.save(new Snapshot(id, IngestionJobType.TECHNICAL_SCREENING, IngestionJobStatus.QUEUED,
                    "Waiting in the technical screening queue", exchange.code(), null, null, 0, null, Instant.now(), null,
                    null, null, null, requestedBy));
            repository.insertRun(new RunParameters(id, exchange.code(), tier, tickers, topN, agents, properties.budgetUsd()));
        });
        worker.enqueue(id);
        log.info("technical screening {} queued by {}: {} {} top {} {}", id,
                requestedBy == null ? "-" : requestedBy.username(), exchange.code(), tier != null ? tier : tickers, topN,
                agents);
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
