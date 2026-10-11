package com.neracalab.backend.job;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker threads that process queued jobs in submission order: with one worker one job after the other (the
 * screening runs, the analyses), with several that many jobs at the same time (the RAG ingestions, the
 * screening data ETL). A job that throws is failed in {@code ingestion_job} with the exception message. The
 * queue lives in memory: jobs still active at shutdown are failed at the next start
 * ({@link IngestionJobRecovery}).
 */
public final class JobWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(JobWorkerPool.class);

    private final String name;
    private final IngestionJobRepository jobs;
    private final Consumer<UUID> processor;
    private final LinkedBlockingQueue<UUID> pending = new LinkedBlockingQueue<>();
    private final WorkerThreads threads;

    /**
     * @param workers   jobs processed at the same time (1: strictly one after the other)
     * @param processor processes one job (its row exists, status QUEUED); it records RUNNING and the
     *                  final status itself. With several workers it is called from several threads at once.
     */
    public JobWorkerPool(String name, int workers, IngestionJobRepository jobs, Consumer<UUID> processor) {
        this.name = name;
        this.jobs = jobs;
        this.processor = processor;
        this.threads = new WorkerThreads(name, workers, this::work);
    }

    public void enqueue(UUID jobId) {
        pending.add(jobId);
    }

    /** Jobs waiting (excluding the running ones). */
    public int pendingCount() {
        return pending.size();
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            UUID id;
            try {
                id = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                processor.accept(id);
            } catch (RuntimeException e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                log.error("{} job {} failed", name, id, e);
                try {
                    jobs.finish(id, IngestionJobStatus.FAILED, "Failed", message, null);
                } catch (RuntimeException f) {
                    log.error("{} job {}: cannot record the failure", name, id, f);
                }
            }
        }
    }

    public void start() {
        threads.start();
    }

    public void stop() {
        threads.stop();
    }

    public boolean isRunning() {
        return threads.isRunning();
    }
}
