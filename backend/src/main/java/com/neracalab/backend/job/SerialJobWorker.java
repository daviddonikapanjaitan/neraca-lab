package com.neracalab.backend.job;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One worker thread that processes queued jobs one after the other (the screening data ETL and
 * the screening runs each have one). A job that throws is failed in {@code ingestion_job} with
 * the exception message. The queue lives in memory: jobs still active at shutdown are failed at
 * the next start ({@link IngestionJobRecovery}).
 */
public final class SerialJobWorker {

    private static final Logger log = LoggerFactory.getLogger(SerialJobWorker.class);

    private final String name;
    private final IngestionJobRepository jobs;
    private final Consumer<UUID> processor;
    private final LinkedBlockingQueue<UUID> pending = new LinkedBlockingQueue<>();
    private volatile Thread thread;

    /**
     * @param processor processes one job (its row exists, status QUEUED); it records RUNNING and the
     *                  final status itself
     */
    public SerialJobWorker(String name, IngestionJobRepository jobs, Consumer<UUID> processor) {
        this.name = name;
        this.jobs = jobs;
        this.processor = processor;
    }

    public void enqueue(UUID jobId) {
        pending.add(jobId);
    }

    /** Jobs waiting (excluding the running one). */
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
        Thread t = new Thread(this::work, name);
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return thread != null;
    }
}
