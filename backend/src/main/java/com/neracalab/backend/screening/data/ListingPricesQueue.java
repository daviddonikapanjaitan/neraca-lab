package com.neracalab.backend.screening.data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.neracalab.backend.job.JobProperties;
import com.neracalab.backend.job.JobWorkerPool;
import com.neracalab.backend.job.Requester;

/**
 * Background runs of the price history of the screening universe ({@code ingestion_job} type LISTING_PRICES): the daily
 * bars of every active listing of an exchange up to the last completed trading day ({@link ListingPriceService}). At
 * most one active run per exchange (a second request returns it); its requests to the provider stay paced, one at a
 * time, with every other Yahoo Finance request of the application.
 */
@Component
public class ListingPricesQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ListingPricesQueue.class);

    public record Submission(IngestionJob job, boolean created) {
    }

    private record Task(Exchange exchange, boolean full) {
    }

    private final ListingPriceService service;
    private final IngestionJobRepository jobs;
    private final JobWorkerPool worker;
    private final Map<UUID, Task> tasks = new ConcurrentHashMap<>();

    public ListingPricesQueue(ListingPriceService service, IngestionJobRepository jobs, JobProperties properties) {
        this.service = service;
        this.jobs = jobs;
        this.worker = new JobWorkerPool("listing-prices", properties.workers(), jobs, this::process);
    }

    /**
     * Queues a run of the exchange, started by {@code requestedBy}.
     *
     * @param full fetch the whole history window of every listing again, not only the days since its last bar
     */
    public synchronized Submission submit(Exchange exchange, boolean full, Requester requestedBy) {
        Optional<UUID> active = jobs.activeJobOf(IngestionJobType.LISTING_PRICES, exchange.code());
        if (active.isPresent()) {
            Optional<IngestionJob> job = jobs.find(active.get());
            if (job.isPresent()) {
                return new Submission(job.get(), false);
            }
        }
        UUID id = UUID.randomUUID();
        jobs.save(new Snapshot(id, IngestionJobType.LISTING_PRICES, IngestionJobStatus.QUEUED,
                "Waiting in the price history queue", exchange.code(), null, full, 0, null, Instant.now(), null, null,
                null, null, requestedBy));
        tasks.put(id, new Task(exchange, full));
        worker.enqueue(id);
        log.info("listing price history {} queued for {} (full={}) by {}", id, exchange.code(), full,
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
        jobs.running(id, "Loading the price history of the " + exchange.code() + " listings");
        List<ListingPriceService.Target> targets = service.targets(exchange);
        if (targets.isEmpty()) {
            jobs.finish(id, IngestionJobStatus.FAILED, "No listings", "No " + exchange.code()
                    + " listings are stored yet: run 'Update screening data' first", null);
            return;
        }
        ListingPriceService.Result r = service.update(exchange, targets, task.full(), (done, total, ticker) -> {
            if (ticker != null) {
                jobs.progress(id, "Price history " + (done + 1) + " of " + total + " (" + ticker
                        + "), about 1.5 s per listing that is behind", null, null);
            }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listings", r.requested());
        result.put("loaded", r.loaded());
        result.put("upToDate", r.upToDate());
        result.put("notFound", r.notFound());
        result.put("failed", r.failed());
        result.put("reAdjusted", r.reAdjusted());
        result.put("barsWritten", r.barsWritten());
        result.put("lastCompletedDay", r.lastCompletedDay().toString());
        result.put("rateLimited", r.rateLimited());
        result.put("errors", r.errors());

        String summary = r.loaded() + " of " + r.requested() + " listings loaded, " + r.upToDate() + " already up to "
                + r.lastCompletedDay() + ", " + r.barsWritten() + " bars written";
        if (r.interrupted()) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Interrupted", "Stopped: the application is shutting down", result);
        } else if (r.rateLimited()) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary,
                    "Rate limit of the price provider: the remaining listings are loaded by the next run", result);
        } else if (r.failed() > 0 || r.notFound() > 0) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, (r.notFound() + r.failed())
                    + " listings without bars (see errors): " + String.join("; ", r.errors()), result);
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
