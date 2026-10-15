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

        MacroIngestionService.Result run(MacroSeries series, boolean full);
    }

    private final Runner runner;
    private final Listener listener;
    private final int jobHistory;
    private final LinkedBlockingQueue<MacroJob> pending = new LinkedBlockingQueue<>();
    /** All known jobs in submission order; guarded by {@code this}. */
    private final Map<UUID, MacroJob> jobs = new LinkedHashMap<>();
    private final WorkerThreads workers;

    @Autowired
    public MacroIngestionQueue(MacroIngestionService service, Listener listener, MacroProperties properties) {
        this(service::ingest, listener, properties.jobHistory());
    }

    MacroIngestionQueue(Runner runner, Listener listener, int jobHistory) {
        this.runner = runner;
        this.listener = listener;
        this.jobHistory = jobHistory;
        this.workers = new WorkerThreads("macro-ingestion", 1, this::work);
    }

    /** Queues a series ({@code requestedBy} null: scheduled run); an active job of the series is returned instead. */
    public synchronized Submission submit(MacroSeries series, boolean full, Requester requestedBy) {
        for (MacroJob existing : jobs.values()) {
            if (existing.series().code().equals(series.code()) && existing.isActive()) {
                return new Submission(existing, false);
            }
        }
        MacroJob job = new MacroJob(series, full, requestedBy);
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
            try {
                job.succeeded(runner.run(job.series(), job.full()));
            } catch (RuntimeException e) {
                String message = Thread.currentThread().isInterrupted() ? "Stopped: the application is shutting down"
                        : e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                log.warn("macro {}: ingestion failed: {}", job.series().code(), message);
                job.failed(message);
            }
            report(job);
        }
    }

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
