package com.neracalab.backend.ingestion;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.agent.JobDeadline;
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.NewUpload;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.JobProperties;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.job.WorkerThreads;

/**
 * Asynchronous financial statement uploads. {@link #submit} stores the workbook in
 * {@code ingestion_file} (once per checksum: a known file is reused, not saved again), records a
 * QUEUED job in {@code ingestion_job} and returns at once; a worker thread then reads the stored
 * workbook and lets the AI agent store it, writing its progress to the job row.
 * <p>
 * Several workers ({@code neracalab.jobs.workers}, 5): that many filings are stored at the same time,
 * each by its own agent, also filings of the same company (five years of one company uploaded
 * together). Their write steps never overlap ({@code IngestionRepository.companyLock}), so the stored
 * data is what storing them one after the other in some order gives.
 * <p>
 * With {@code neracalab.ingestion.same-company-in-order=true} filings of the SAME company are stored
 * one after the other in upload order instead (other companies still beside them): only the first
 * waiting filing of a company is offered to the workers, the next one follows when it is done. The
 * stored data does not depend on it (also a share split restated by a later filing ends the same in
 * either order); in order, a job never verifies a period another filing is writing. At most one
 * active job per stored file (an identical upload while it is queued / running returns that job).
 * The queue is in memory: jobs still active when the application stops are failed at the next start
 * ({@code IngestionJobRecovery}); upload the file again (the stored file is reused).
 */
@Component
public class FinancialStatementQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FinancialStatementQueue.class);

    /** The job, and whether it was created by this call (false: the same file was already queued / running). */
    public record Submission(IngestionJob job, boolean created) {
    }

    /** @param company the filing's company (ticker, upper case; "" when the workbook does not name one) */
    private record Task(UUID jobId, long fileId, String fileName, String company) {
    }

    private final IngestionService service;
    private final IngestionFileRepository files;
    private final IngestionJobRepository jobs;
    private final Duration jobTimeout;
    private final boolean sameCompanyInOrder;
    /** Filings a worker may take; with {@link #sameCompanyInOrder} at most one per company. */
    private final LinkedBlockingQueue<Task> pending = new LinkedBlockingQueue<>();
    /**
     * Only with {@link #sameCompanyInOrder}: companies with a filing in {@link #pending} or being stored, each
     * with its filings waiting behind that one (upload order); guarded by itself.
     */
    private final Map<String, Deque<Task>> companies = new HashMap<>();
    private final WorkerThreads workers;

    /**
     * @param jobTimeout         longest a job may run, from the moment it starts running (time waiting in the queue
     *                           does not count); a job still running then is stopped and FAILED
     * @param sameCompanyInOrder store the filings of one company one after the other in upload order instead of
     *                           at the same time
     */
    public FinancialStatementQueue(IngestionService service, IngestionFileRepository files, IngestionJobRepository jobs,
                                   @Value("${neracalab.ingestion.job-timeout:5m}") Duration jobTimeout,
                                   @Value("${neracalab.ingestion.same-company-in-order:false}") boolean sameCompanyInOrder,
                                   JobProperties properties) {
        this.service = service;
        this.files = files;
        this.jobs = jobs;
        this.jobTimeout = jobTimeout;
        this.sameCompanyInOrder = sameCompanyInOrder;
        this.workers = new WorkerThreads("financial-statement-ingestion", properties.workers(), this::work);
    }

    // ------------------------------------------------------------------ API

    /** As {@link #submit(byte[], String, String, String, Requester)}; the company is read from the workbook. */
    public Submission submit(byte[] content, String fileName, String contentType, Requester requestedBy) {
        String ticker;
        try {
            ticker = service.prepare(content, fileName).info().ticker();
        } catch (IdxWorkbookException e) {
            ticker = null;      // the job reports the invalid workbook
        }
        return submit(content, fileName, contentType, ticker, requestedBy);
    }

    /**
     * Stores the file (or reuses the stored one with the same checksum) and queues its ingestion,
     * recorded as started by {@code requestedBy}. When the same file is already queued / running,
     * that job is returned (it keeps its own requester).
     *
     * @param ticker the filing's company ({@code IngestionService.prepare}); with
     *               {@code same-company-in-order} its filings are stored one after the other, in upload order
     */
    public synchronized Submission submit(byte[] content, String fileName, String contentType, String ticker,
                                          Requester requestedBy) {
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
        jobs.insertUpload(new NewUpload(id, file.fileId(), name, file.reused(), "Waiting in the upload queue", requestedBy));
        Task task = new Task(id, file.fileId(), name, ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT));
        synchronized (companies) {
            Deque<Task> behind = sameCompanyInOrder ? companies.get(task.company()) : null;
            if (behind != null) {
                behind.add(task);       // offered to the workers when the company's filings before it are done
            } else {
                if (sameCompanyInOrder) {
                    companies.put(task.company(), new ArrayDeque<>());
                }
                pending.add(task);
            }
        }
        log.info("upload job {} queued by {}: {} (file {}{})", id, requestedBy == null ? "-" : requestedBy.username(),
                name, file.fileId(), file.reused() ? ", reused" : "");
        return new Submission(jobs.find(id).orElseThrow(), true);
    }

    /** Jobs waiting in the queue (excluding the running ones). */
    public int pendingCount() {
        synchronized (companies) {
            return pending.size() + companies.values().stream().mapToInt(Deque::size).sum();
        }
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
            } finally {
                offerNextOf(task.company());
            }
        }
    }

    /** The company's filing is done: its next waiting filing (same-company-in-order) may be taken by a worker. */
    private void offerNextOf(String company) {
        synchronized (companies) {
            Deque<Task> behind = companies.get(company);
            Task next = behind == null ? null : behind.poll();
            if (next == null) {
                companies.remove(company);
            } else {
                pending.add(next);
            }
        }
    }

    private void process(Task task) {
        UUID id = task.jobId();
        JobDeadline deadline = JobDeadline.after(jobTimeout);
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
        session.deadline(deadline);
        FilingInfo info = session.info();
        String filing = info.ticker() + " " + info.current().key();
        String ticker = info.ticker() != null && info.ticker().length() <= 20 ? info.ticker() : null;
        jobs.progress(id, "AI agent is storing " + filing + " (usually 1-2 minutes, stopped after "
                + minutes(jobTimeout) + ")", Exchange.IDX.code(), ticker);

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
            case FAILED -> finish(id, IngestionJobStatus.FAILED, deadline.passed()
                    ? "Stopped after the " + minutes(jobTimeout) + " limit on " + filing + summary(response)
                    : "AI agent failed on " + filing, response.error(), response);
        }
    }

    /** "5 minutes", "1 minute", "90 seconds", "1 second" */
    static String minutes(Duration d) {
        return JobDeadline.describe(d);
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
