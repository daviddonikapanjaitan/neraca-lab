package com.neracalab.backend.macro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.neracalab.backend.job.Requester;
import com.neracalab.backend.job.WorkerThreads;

/**
 * In-memory queue of macro ingestions with one worker: the series are fetched one after the other (the crawler also
 * paces each host). At most one active job per series; jobs are lost on restart (submit again: what is stored is not
 * fetched twice). Every state change goes to the {@link Listener} ({@link MacroIngestionTracker} records it in
 * {@code ingestion_job}, type MACRO); a listener failure never affects the job.
 */
@Component
public class MacroIngestionQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MacroIngestionQueue.class);

    /** The job, and whether it was created by this call (false: an active job for the series already existed). */
    public record Submission(MacroJob job, boolean created) {
    }

    @FunctionalInterface
    public interface Listener {

        void changed(MacroJob.View job);
    }

    /** Runs one job (the ingestion service; a stub in tests). */
    @FunctionalInterface
    interface Runner {

        MacroIngestionService.Result run(MacroSeries series, boolean full, java.time.LocalDate resumeFrom);
    }

    /**
     * A continuation that read nothing is asked again at most this many times in a row (no endless loop); each such
     * wait doubles (a 429 after 15 minutes: 15, 30, 60, 120, 240, 480 minutes - a server's daily quota is waited out).
     */
    static final int MAX_IDLE_CONTINUATIONS = 6;

    private final Runner runner;
    private final Listener listener;
    private final int jobHistory;
    /** The series derived from a series (its SPI): queued when that series has read everything. */
    private final java.util.function.Function<MacroSeries, List<MacroSeries>> dependents;
    private final LinkedBlockingQueue<MacroJob> pending = new LinkedBlockingQueue<>();
    /** All known jobs in submission order; guarded by {@code this}. */
    private final Map<UUID, MacroJob> jobs = new LinkedHashMap<>();
    /** Series code -> continuations in a row that read nothing; guarded by {@code this}. */
    private final Map<String, Integer> idleContinuations = new java.util.HashMap<>();
    private final java.util.concurrent.ScheduledExecutorService later =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "macro-ingestion-later");
                t.setDaemon(true);
                return t;
            });
    private final WorkerThreads workers;

    @Autowired
    public MacroIngestionQueue(MacroIngestionService service, Listener listener, MacroProperties properties,
                               MacroRepository repository) {
        this(service::ingest, listener, properties.jobHistory(), s -> repository.series().stream()
                .filter(d -> "spi".equals(d.source()) && s.code().equals(d.sourceRef())).toList());
    }

    MacroIngestionQueue(Runner runner, Listener listener, int jobHistory) {
        this(runner, listener, jobHistory, s -> List.of());
    }

    MacroIngestionQueue(Runner runner, Listener listener, int jobHistory,
                        java.util.function.Function<MacroSeries, List<MacroSeries>> dependents) {
        this.runner = runner;
        this.listener = listener;
        this.jobHistory = jobHistory;
        this.dependents = dependents;
        this.workers = new WorkerThreads("macro-ingestion", 1, this::work);
    }

    /** Queues a series ({@code requestedBy} null: scheduled run); an active job of the series is returned instead. */
    public synchronized Submission submit(MacroSeries series, boolean full, Requester requestedBy) {
        return submit(series, full, requestedBy, null);
    }

    private synchronized Submission submit(MacroSeries series, boolean full, Requester requestedBy,
                                           java.time.LocalDate resumeFrom) {
        for (MacroJob existing : jobs.values()) {
            if (existing.series().code().equals(series.code()) && existing.isActive()) {
                return new Submission(existing, false);
            }
        }
        MacroJob job = new MacroJob(series, full, requestedBy, resumeFrom);
        jobs.put(job.id(), job);
        report(job);
        pending.add(job);
        forgetOldJobs();
        return new Submission(job, true);
    }

    public synchronized Optional<MacroJob> job(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** Jobs, most recent first. */
    public synchronized List<MacroJob> jobs() {
        List<MacroJob> list = new ArrayList<>(jobs.values());
        Collections.reverse(list);
        return list;
    }

    public int pendingCount() {
        return pending.size();
    }

    private void report(MacroJob job) {
        try {
            listener.changed(job.view());
        } catch (RuntimeException e) {
            log.warn("macro job {}: state change not recorded: {}", job.id(), e.getMessage());
        }
    }

    private void forgetOldJobs() {
        long finished = jobs.values().stream().filter(j -> !j.isActive()).count();
        Iterator<MacroJob> it = jobs.values().iterator();
        while (finished > jobHistory && it.hasNext()) {
            if (!it.next().isActive()) {
                it.remove();
                finished--;
            }
        }
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            MacroJob job;
            try {
                job = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            job.running();
            report(job);
            MacroIngestionService.Result result = null;
            try {
                result = runner.run(job.series(), job.full(), job.resumeFrom());
                job.succeeded(result);
            } catch (RuntimeException e) {
                String message = Thread.currentThread().isInterrupted() ? "Stopped: the application is shutting down"
                        : e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                log.warn("macro {}: ingestion failed: {}", job.series().code(), message);
                job.failed(message);
            }
            report(job);
            if (result != null) {
                followUp(job, result);
            }
        }
    }

    /**
     * After a job that succeeded: a fetch that stopped early is continued from where it stopped - at the end of the
     * queue (a history read in chunks) or later (the server answered HTTP 429); one that read everything queues the
     * series derived from it (a rainfall series' SPI).
     */
    private void followUp(MacroJob job, MacroIngestionService.Result result) {
        MacroSeries series = job.series();
        if (result.continueAfter() != null && result.resumeFrom() != null) {
            int idle;
            synchronized (this) {
                idle = result.kept() > 0 ? 0 : idleContinuations.getOrDefault(series.code(), 0) + 1;
                idleContinuations.put(series.code(), idle);
            }
            if (idle > MAX_IDLE_CONTINUATIONS) {
                log.warn("macro {}: {} continuations in a row read nothing; not asked again", series.code(), idle - 1);
                return;
            }
            Runnable next = () -> submit(series, job.full(), job.requestedBy(), result.resumeFrom());
            if (result.continueAfter().isZero()) {
                next.run();
            } else {
                long wait = result.continueAfter().toMillis() << Math.max(0, idle - 1);
                log.info("macro {}: asked again in {} minutes from {}", series.code(), wait / 60_000, result.resumeFrom());
                later.schedule(next, wait, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return;
        }
        synchronized (this) {
            idleContinuations.remove(series.code());
        }
        try {
            dependents.apply(series).forEach(d -> submit(d, false, job.requestedBy()));
        } catch (RuntimeException e) {
            log.warn("macro {}: the series derived from it were not queued: {}", series.code(), e.getMessage());
        }
    }

    @Override
    public void start() {
        workers.start();
    }

    @Override
    public void stop() {
        later.shutdownNow();
        workers.stop();
    }

    @Override
    public boolean isRunning() {
        return workers.isRunning();
    }
}
