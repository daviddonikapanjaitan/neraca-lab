package com.neracalab.backend.screening.data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.Snapshot;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.job.SerialJobWorker;
import com.neracalab.backend.price.provider.PriceProviderException;

/**
 * Background runs of the screening data ETL ({@code ingestion_job} type FUNDAMENTALS), one at a
 * time: today's market data of every listing, then the fundamentals that are due (or all with
 * {@code full}). At most one active run per exchange; a second request returns it.
 */
@Component
public class FundamentalsQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsQueue.class);

    public record Submission(IngestionJob job, boolean created) {
    }

    private record Task(Exchange exchange, boolean full) {
    }

    private final FundamentalEtlService service;
    private final IngestionJobRepository jobs;
    private final SerialJobWorker worker;
    private final Map<UUID, Task> tasks = new ConcurrentHashMap<>();

    public FundamentalsQueue(FundamentalEtlService service, IngestionJobRepository jobs) {
        this.service = service;
        this.jobs = jobs;
        this.worker = new SerialJobWorker("screening-data-etl", jobs, this::process);
    }

    /**
     * Queues an ETL run of the exchange, started by {@code requestedBy} (null: the schedule).
     *
     * @param full refresh every listing's fundamentals, not only those older than the maximum age
     */
    public synchronized Submission submit(Exchange exchange, boolean full, Requester requestedBy) {
        Optional<UUID> active = jobs.activeJobOf(IngestionJobType.FUNDAMENTALS, exchange.code());
        if (active.isPresent()) {
            Optional<IngestionJob> job = jobs.find(active.get());
            if (job.isPresent()) {
                return new Submission(job.get(), false);
            }
        }
        UUID id = UUID.randomUUID();
        jobs.save(new Snapshot(id, IngestionJobType.FUNDAMENTALS, IngestionJobStatus.QUEUED,
                "Waiting in the screening data queue", exchange.code(), null, full, 0, null, Instant.now(), null, null,
                null, null, requestedBy));
        tasks.put(id, new Task(exchange, full));
        worker.enqueue(id);
        log.info("screening data ETL {} queued for {} (full={}) by {}", id, exchange.code(), full,
                requestedBy == null ? "the schedule" : requestedBy.username());
        return new Submission(jobs.find(id).orElseThrow(), true);
    }

    private void process(UUID id) {
        Task task = tasks.remove(id);
        if (task == null) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Failed", "The job parameters were lost", null);
            return;
        }
        Exchange exchange = task.exchange();
        jobs.running(id, "Loading the " + exchange.code() + " universe and market data (Yahoo Finance)");
        Map<String, Object> result = new LinkedHashMap<>();
        FundamentalEtlService.UniverseResult universe;
        try {
            universe = service.syncUniverse(exchange);
        } catch (PriceProviderException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Market data not loaded", e.getMessage(), null);
            return;
        }
        result.put("snapshotDate", universe.snapshotDate().toString());
        result.put("listings", universe.listings());

        FundamentalEtlService.RefreshResult refresh = service.refreshFundamentals(exchange, null,
                task.full() ? Instant.now() : Instant.now().minus(service.fundamentalsMaxAge()),
                (done, total, ticker) -> {
                    if (ticker != null && (done % 5 == 0 || done == total - 1)) {
                        jobs.progress(id, "Fundamentals " + (done + 1) + " of " + total + " (" + ticker
                                + "), about 3 s per stock", null, null);
                    }
                });
        result.put("fundamentalsRequested", refresh.requested());
        result.put("fundamentalsRefreshed", refresh.refreshed());
        result.put("fundamentalsFailed", refresh.failed());
        result.put("rateLimited", refresh.rateLimited());
        result.put("errors", refresh.errors());

        String summary = universe.listings() + " listings, " + refresh.refreshed() + " of " + refresh.requested()
                + " fundamentals refreshed";
        if (refresh.interrupted()) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Interrupted", "Stopped: the application is shutting down", result);
        } else if (refresh.rateLimited()) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary,
                    "Yahoo Finance rate limit: the remaining fundamentals are refreshed by the next run", result);
        } else if (refresh.failed() > 0) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, refresh.failed()
                    + " stocks without fundamentals (see errors): " + String.join("; ", refresh.errors()), result);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, summary, null, result);
        }
    }

    // ------------------------------------------------------------------ lifecycle

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
