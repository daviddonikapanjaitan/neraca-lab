package com.neracalab.backend.price;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.neracalab.backend.job.JobProperties;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.job.WorkerThreads;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.provider.RateLimitedException;

/**
 * In-memory queue of price ingestions with several worker threads ({@code neracalab.jobs.workers}, 5):
 * that many companies are ingested at the same time. The provider still sees one request at a time,
 * with the pause between two requests: {@code PacedHttpClient} is shared by all workers.
 * <p>
 * HTTP 429: the whole queue (every worker) pauses for the next wait of {@code neracalab.prices.backoff}
 * (15m, 30m, 60m; longer when Retry-After asks for it), then the job is retried, which starts again
 * from MAX(trading_date) - nothing of it was written. Jobs running at that moment that are answered
 * 429 too share the pause: it counts once. A success resets the sequence. A 429 after the last wait
 * stops the run: the job, every job answered 429 with it and every queued job fail, and the next
 * submission starts fresh.
 * <p>
 * At most one active job per company; jobs are lost on restart (re-submit; nothing is fetched twice).
 * Every state change is also reported to the {@link Listener} ({@link PriceIngestionTracker} records
 * it in {@code ingestion_job}); a listener failure never affects the job.
 */
@Component
public class PriceIngestionQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PriceIngestionQueue.class);

    /** The job, and whether it was created by this call (false: an active job for the company already existed). */
    public record Submission(PriceIngestionJob job, boolean created) {
    }

    /** Receives every state change of a job (submitted, running, waiting, finished), on the changing thread. */
    @FunctionalInterface
    public interface Listener {

        void changed(PriceIngestionJob.View job);
    }

    private final PriceIngestionService service;
    private final PriceProperties properties;
    private final Listener listener;
    private final LinkedBlockingQueue<PriceIngestionJob> pending = new LinkedBlockingQueue<>();
    /** All known jobs in submission order; guarded by {@code this}. */
    private final Map<UUID, PriceIngestionJob> jobs = new LinkedHashMap<>();
    private final WorkerThreads workers;

    /** Guards the rate-limit state below, which all workers share. */
    private final Object rateLimit = new Object();
    /** Pauses since the last success (index of the next wait in {@code backoff}). */
    private int rateLimitStreak;
    /** Pauses and run stops so far: an attempt that began before the latest one does not count its 429 again. */
    private long rateLimitEvents;
    /** Value of {@link #rateLimitEvents} after the last run stop (0: none yet). */
    private long runStoppedAt;
    /** End of the current pause ({@code null}: none). */
    private Instant pausedUntil;
    /** The 429 that started the current pause, with the wait: "...; queue paused for 15m (wait 1 of 3)". */
    private String pauseReason;

    @Autowired
    public PriceIngestionQueue(PriceIngestionService service, PriceProperties properties, Listener listener,
                               JobProperties jobProperties) {
        this(service, properties, listener, jobProperties.workers());
    }

    /** One worker, no listener (tests). */
    PriceIngestionQueue(PriceIngestionService service, PriceProperties properties) {
        this(service, properties, job -> { }, 1);
    }

    PriceIngestionQueue(PriceIngestionService service, PriceProperties properties, Listener listener, int workers) {
        this.service = service;
        this.properties = properties;
        this.listener = listener;
        this.workers = new WorkerThreads("price-ingestion", workers, this::work);
    }

    // ------------------------------------------------------------------ API

    /** A scheduled run (no user). */
    public Submission submit(CompanyRef company, boolean full) {
        return submit(company, full, null);
    }

    /**
     * Queues an ingestion requested by a user ({@code null}: scheduled run). When the company already
     * has an active job, that job is returned (it keeps its own requester).
     */
    public synchronized Submission submit(CompanyRef company, boolean full, Requester requestedBy) {
        for (PriceIngestionJob job : jobs.values()) {
            if (job.company().companyId() == company.companyId() && job.isActive()) {
                return new Submission(job, false);
            }
        }
        PriceIngestionJob job = new PriceIngestionJob(company, full, requestedBy);
        jobs.put(job.id(), job);
        report(job);   // before the worker can see the job, so QUEUED is never recorded after RUNNING
        pending.add(job);
        forgetOldJobs();
        return new Submission(job, true);
    }

    public synchronized Optional<PriceIngestionJob> job(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** Jobs, most recent first. */
    public synchronized List<PriceIngestionJob> jobs() {
        List<PriceIngestionJob> list = new ArrayList<>(jobs.values());
        Collections.reverse(list);
        return list;
    }

    public int pendingCount() {
        return pending.size();
    }

    public String providerName() {
        return service.providerName();
    }

    private void report(PriceIngestionJob job) {
        try {
            listener.changed(job.view());
        } catch (RuntimeException e) {
            log.warn("price ingestion job {}: state change not recorded: {}", job.id(), e.getMessage());
        }
    }

    /** Drops the oldest finished jobs beyond {@code job-history}; active jobs are always kept. */
    private void forgetOldJobs() {
        long finished = jobs.values().stream().filter(j -> !j.isActive()).count();
        Iterator<PriceIngestionJob> it = jobs.values().iterator();
        while (finished > properties.jobHistory() && it.hasNext()) {
            if (!it.next().isActive()) {
                it.remove();
                finished--;
            }
        }
    }

    // ------------------------------------------------------------------ worker

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            PriceIngestionJob job;
            try {
                job = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            process(job);
        }
    }

    private void process(PriceIngestionJob job) {
        CompanyRef company = job.company();
        boolean attempted = false;
        while (true) {
            long eventsBefore = awaitResume(job, attempted);
            if (eventsBefore < 0) {
                job.failed("Stopped: the application is shutting down");
                report(job);
                return;
            }
            attempted = true;
            job.running();
            report(job);
            try {
                job.succeeded(service.ingest(company, job.full()));
                report(job);
                synchronized (rateLimit) {
                    if (rateLimitEvents == eventsBefore) {
                        rateLimitStreak = 0;
                    }
                }
                return;
            } catch (RateLimitedException e) {
                if (!rateLimited(job, e, eventsBefore)) {
                    return;
                }
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    job.failed("Stopped: the application is shutting down");
                    report(job);
                    return;
                }
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                log.warn("{} {}: price ingestion failed: {}", company.exchange(), company.ticker(), message, e);
                job.failed(message);
                report(job);
                return;
            }
        }
    }

    /**
     * Waits until the queue is not paused. A job that has run before shows WAITING_RATE_LIMIT meanwhile; a
     * job that has not stays QUEUED.
     *
     * @return {@link #rateLimitEvents} at the moment the job may run; -1 when the thread was interrupted
     */
    private long awaitResume(PriceIngestionJob job, boolean attempted) {
        while (true) {
            Instant until;
            String reason;
            synchronized (rateLimit) {
                if (pausedUntil == null || !Instant.now().isBefore(pausedUntil)) {
                    pausedUntil = null;
                    return rateLimitEvents;
                }
                until = pausedUntil;
                reason = pauseReason;
            }
            if (attempted) {
                job.waitingForRateLimit(until, reason + ", then " + job.company().ticker() + " is retried");
                report(job);
            }
            Duration wait = Duration.between(Instant.now(), until);
            if (wait.isPositive()) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
    }

    /**
     * The provider answered HTTP 429 for {@code job}: pauses the queue for the next back-off wait, or stops the
     * run after the last one. A 429 of an attempt that began before the latest pause or run stop belongs to
     * that one (the job ran beside the job that caused it) and is not counted again.
     *
     * @param eventsBefore {@link #rateLimitEvents} when the attempt began
     * @return true: the job is retried after the pause; false: it has failed
     */
    private boolean rateLimited(PriceIngestionJob job, RateLimitedException e, long eventsBefore) {
        synchronized (rateLimit) {
            if (rateLimitEvents != eventsBefore) {
                if (runStoppedAt > eventsBefore) {
                    job.failed("Run stopped: " + providerName() + " kept answering HTTP 429; submit again later");
                    report(job);
                    return false;
                }
                return true;
            }
            rateLimitEvents++;
            rateLimitStreak++;
            List<Duration> backoff = properties.backoff();
            if (rateLimitStreak > backoff.size()) {
                rateLimitStreak = 0;
                runStoppedAt = rateLimitEvents;
                pausedUntil = null;
                stopRun(job, e);
                return false;
            }
            Duration wait = backoff.get(rateLimitStreak - 1);
            if (e.retryAfter() != null && e.retryAfter().compareTo(wait) > 0) {
                wait = e.retryAfter();
            }
            pausedUntil = Instant.now().plus(wait);
            pauseReason = e.getMessage() + "; queue paused for " + format(wait) + " (wait "
                    + rateLimitStreak + " of " + backoff.size() + ")";
            log.warn("{}, then {} is retried", pauseReason, job.company().ticker());
            return true;
        }
    }

    /** HTTP 429 after the last back-off wait: fail this job and everything queued. */
    private void stopRun(PriceIngestionJob job, RateLimitedException e) {
        String waits = properties.backoff().stream().map(PriceIngestionQueue::format).collect(Collectors.joining(", "));
        job.failed(e.getMessage() + " again after waiting " + waits + "; run stopped, submit again later");
        report(job);
        List<PriceIngestionJob> dropped = new ArrayList<>();
        pending.drainTo(dropped);
        for (PriceIngestionJob queued : dropped) {
            queued.failed("Run stopped: " + providerName() + " kept answering HTTP 429; submit again later");
            report(queued);
        }
        log.error("Price ingestion run stopped: {} kept answering HTTP 429 after waiting {}; {} queued job(s) dropped",
                providerName(), waits, dropped.size());
    }

    /** 15m, 90s, 250ms */
    static String format(Duration duration) {
        if (duration.toMillis() % 60_000 == 0) {
            return duration.toMinutes() + "m";
        }
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        workers.start();
    }

    @Override
    public void stop() {
        workers.stop();
    }

    @Override
    public boolean isRunning() {
        return workers.isRunning();
    }
}
