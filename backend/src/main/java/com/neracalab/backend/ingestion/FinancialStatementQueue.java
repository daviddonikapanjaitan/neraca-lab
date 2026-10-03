package com.neracalab.backend.ingestion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.NewUpload;
import com.neracalab.backend.job.IngestionJobStatus;

/**
 * Asynchronous financial statement uploads. {@link #submit} stores the workbook in
 * {@code ingestion_file} (once per checksum: a known file is reused, not saved again), records a
 * QUEUED job in {@code ingestion_job} and returns at once; ONE worker thread then reads the stored
 * workbook and lets the AI agent store it, writing its progress to the job row.
 * <p>
 * One worker: filings are stored one after the other, so two filings of the same company never
 * race on the company row, and the AI provider sees one agent at a time. At most one active job
 * per stored file (an identical upload while it is queued / running returns that job). The queue
 * is in memory: jobs still active when the application stops are failed at the next start
 * ({@code IngestionJobRecovery}); upload the file again (the stored file is reused).
 */
@Component
public class FinancialStatementQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FinancialStatementQueue.class);

    /** The job, and whether it was created by this call (false: the same file was already queued / running). */
    public record Submission(IngestionJob job, boolean created) {
    }

    private record Task(UUID jobId, long fileId, String fileName) {
    }

    private final IngestionService service;
    private final IngestionFileRepository files;
    private final IngestionJobRepository jobs;
    private final LinkedBlockingQueue<Task> pending = new LinkedBlockingQueue<>();
    private volatile Thread worker;

    public FinancialStatementQueue(IngestionService service, IngestionFileRepository files, IngestionJobRepository jobs) {
        this.service = service;
        this.files = files;
        this.jobs = jobs;
    }

    // ------------------------------------------------------------------ API

    /** Stores the file (or reuses the stored one with the same checksum) and queues its ingestion. */
    public synchronized Submission submit(byte[] content, String fileName, String contentType) {
        StoredFile file = files.store(content, fileName, contentType);
        Optional<UUID> active = jobs.activeUploadOf(file.fileId());
        if (active.isPresent()) {
            Optional<IngestionJob> job = jobs.find(active.get());
            if (job.isPresent()) {
                return new Submission(job.get(), false);
            }
        }
        UUID id = UUID.randomUUID();
        String name = fileName.length() <= IngestionFileRepository.NAME_LENGTH
                ? fileName : fileName.substring(0, IngestionFileRepository.NAME_LENGTH);
        jobs.insertUpload(new NewUpload(id, file.fileId(), name, file.reused(), "Waiting in the upload queue"));
        pending.add(new Task(id, file.fileId(), name));
        log.info("upload job {} queued: {} (file {}{})", id, name, file.fileId(), file.reused() ? ", reused" : "");
        return new Submission(jobs.find(id).orElseThrow(), true);
    }

    /** Jobs waiting in the queue (excluding the running one). */
    public int pendingCount() {
        return pending.size();
    }

    // ------------------------------------------------------------------ worker

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            Task task;
            try {
                task = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                process(task);
            } catch (RuntimeException e) {
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                log.error("upload job {} failed", task.jobId(), e);
                finish(task.jobId(), IngestionJobStatus.FAILED, "Failed", message, null);
            }
        }
    }

    private void process(Task task) {
        UUID id = task.jobId();
        jobs.running(id, "Reading the workbook");
        byte[] content = files.content(task.fileId())
                .orElseThrow(() -> new IllegalStateException("Stored file " + task.fileId() + " not found"));

        IngestionSession session;
        try {
            session = service.prepare(content, task.fileName());
        } catch (IdxWorkbookException e) {
            finish(id, IngestionJobStatus.FAILED, "Invalid workbook", e.getMessage(), null);
            return;
        }
        FilingInfo info = session.info();
        String filing = info.ticker() + " " + info.current().key();
        String ticker = info.ticker() != null && info.ticker().length() <= 20 ? info.ticker() : null;
        jobs.progress(id, "AI agent is storing " + filing + " (usually 1-4 minutes)", Exchange.IDX.code(), ticker);

        IngestionResponse response = service.run(session);
        if (Thread.currentThread().isInterrupted()) {
            finish(id, IngestionJobStatus.FAILED, "Interrupted", "Stopped: the application is shutting down", response);
            return;
        }
        switch (response.status()) {
            case COMPLETED -> finish(id, IngestionJobStatus.SUCCEEDED,
                    "Stored and verified " + filing + summary(response), null, response);
            case INCOMPLETE -> finish(id, IngestionJobStatus.INCOMPLETE,
                    "Stored " + filing + " with open items" + summary(response), openItems(response), response);
            case FAILED -> finish(id, IngestionJobStatus.FAILED, "AI agent failed on " + filing, response.error(), response);
        }
    }

    /** " (12 statement rows, 4 segments)" */
    private static String summary(IngestionResponse response) {
        int statements = response.savedStatements().values().stream().mapToInt(List::size).sum();
        int segments = response.savedSegments().values().stream().mapToInt(List::size).sum();
        return " (" + statements + " statement " + (statements == 1 ? "row" : "rows") + ", "
                + segments + " segment " + (segments == 1 ? "row" : "rows") + ")";
    }

    /** Pending items and problems of the verification, for the job message. */
    private static String openItems(IngestionResponse response) {
        if (response.verification() == null) {
            return "The verification did not run";
        }
        List<String> items = new ArrayList<>();
        Stream.concat(response.verification().pending().stream(), response.verification().problems().stream())
                .forEach(items::add);
        return items.isEmpty() ? "The verification reported the filing as incomplete" : String.join("; ", items);
    }

    /** Writes the final state; a database failure here is logged (the job then stays RUNNING until the next start). */
    private void finish(UUID id, IngestionJobStatus status, String stage, String message, Object result) {
        try {
            jobs.finish(id, status, stage, message, result);
        } catch (RuntimeException e) {
            log.error("upload job {}: cannot record final status {}", id, status, e);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        Thread thread = new Thread(this::work, "financial-statement-ingestion");
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
