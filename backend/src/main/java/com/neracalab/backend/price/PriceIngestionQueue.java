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
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.provider.RateLimitedException;

/**
 * In-memory queue of price ingestions with ONE worker thread, so the provider sees one request at
 * a time (the pause between requests is applied by {@code PacedHttpClient}).
 * <p>
 * HTTP 429: the worker pauses the whole queue for the next wait of {@code neracalab.prices.backoff}
 * (15m, 30m, 60m; longer when Retry-After asks for it), then retries the same job, which starts again
 * from MAX(trading_date) - nothing of it was written. A success resets the sequence. A 429 after the
 * last wait stops the run: the job and every queued job fail, and the next submission starts fresh.
 * <p>
 * At most one active job per company; jobs are lost on restart (re-submit; nothing is fetched twice).
 */
@Component
public class PriceIngestionQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PriceIngestionQueue.class);

    /** The job, and whether it was created by this call (false: an active job for the company already existed). */
    public record Submission(PriceIngestionJob job, boolean created) {
    }

    private final PriceIngestionService service;
    private final PriceProperties properties;
    private final LinkedBlockingQueue<PriceIngestionJob> pending = new LinkedBlockingQueue<>();
    /** All known jobs in submission order; guarded by {@code this}. */
    private final Map<UUID, PriceIngestionJob> jobs = new LinkedHashMap<>();
    private volatile Thread worker;
    /** Consecutive HTTP 429 answers; worker thread only. */
    private int rateLimitStreak;

    public PriceIngestionQueue(PriceIngestionService service, PriceProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ API

    public synchronized Submission submit(CompanyRef company, boolean full) {
        for (PriceIngestionJob job : jobs.values()) {
            if (job.company().companyId() == company.companyId() && job.isActive()) {
                return new Submission(job, false);
            }
        }
        PriceIngestionJob job = new PriceIngestionJob(company, full);
        jobs.put(job.id(), job);
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
        while (true) {
            job.running();
            try {
                job.succeeded(service.ingest(company, job.full()));
                rateLimitStreak = 0;
                return;
            } catch (RateLimitedException e) {
                rateLimitStreak++;
                List<Duration> backoff = properties.backoff();
                if (rateLimitStreak > backoff.size()) {
                    rateLimitStreak = 0;
                    stopRun(job, e);
                    return;
                }
                Duration wait = backoff.get(rateLimitStreak - 1);
                if (e.retryAfter() != null && e.retryAfter().compareTo(wait) > 0) {
                    wait = e.retryAfter();
                }
                Instant resumeAt = Instant.now().plus(wait);
                String message = e.getMessage() + "; queue paused for " + format(wait) + " (wait "
                        + rateLimitStreak + " of " + backoff.size() + "), then " + company.ticker() + " is retried";
                job.waitingForRateLimit(resumeAt, message);
                log.warn(message);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    job.failed("Stopped: the application is shutting down");
                    return;
                }
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    job.failed("Stopped: the application is shutting down");
                    return;
                }
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                log.warn("{} {}: price ingestion failed: {}", company.exchange(), company.ticker(), message, e);
                job.failed(message);
                return;
            }
        }
    }

    /** HTTP 429 after the last back-off wait: fail this job and everything queued. */
    private void stopRun(PriceIngestionJob job, RateLimitedException e) {
        String waits = properties.backoff().stream().map(PriceIngestionQueue::format).collect(Collectors.joining(", "));
        job.failed(e.getMessage() + " again after waiting " + waits + "; run stopped, submit again later");
        List<PriceIngestionJob> dropped = new ArrayList<>();
        pending.drainTo(dropped);
        for (PriceIngestionJob queued : dropped) {
            queued.failed("Run stopped: " + providerName() + " kept answering HTTP 429; submit again later");
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
        Thread thread = new Thread(this::work, "price-ingestion");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    @Override
    public void stop() {
        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return worker != null;
    }
}
