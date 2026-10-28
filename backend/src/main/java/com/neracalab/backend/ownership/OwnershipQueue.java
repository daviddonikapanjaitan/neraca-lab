package com.neracalab.backend.ownership;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.NewUpload;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.JobWorkerPool;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.ownership.OwnershipIngestionService.OwnershipResult;
import com.neracalab.backend.ownership.onepercent.OnePercentIngestionService;
import com.neracalab.backend.ownership.onepercent.OnePercentIngestionService.OnePercentResult;
import com.neracalab.backend.ownership.onepercent.OnePercentParser;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository;
import com.neracalab.backend.rag.ExcelDocumentType;

/**
 * Asynchronous Excel uploads of Ingestion > Excel Documents (RAG) (job type RAG_XLSX in {@code ingestion_job}, exchange
 * IDX, no ticker: a list covers every company): the daily lists of Ownership (>=5%) ({@link OwnershipIngestionService})
 * and the monthly lists of Ownership (>=1%) ({@link OnePercentIngestionService}). One worker: the lists are stored one
 * after the other, so the ownership document of a company is always written from every list stored before it (several
 * lists uploaded at once never write it from an incomplete history). The same file already queued or running returns that job. The queue is
 * in memory: jobs still active when the application stops are failed at the next start.
 */
@Component
public class OwnershipQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OwnershipQueue.class);
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public record Submission(IngestionJob job, boolean created) {
    }

    private record Task(String exchange, ExcelDocumentType documentType, long fileId, String fileName) {
    }

    private final OwnershipIngestionService service;
    private final OnePercentIngestionService onePercent;
    private final IngestionFileRepository files;
    private final IngestionJobRepository jobs;
    private final JobWorkerPool worker;
    private final Map<UUID, Task> tasks = new ConcurrentHashMap<>();

    public OwnershipQueue(OwnershipIngestionService service, OnePercentIngestionService onePercent,
                          IngestionFileRepository files, IngestionJobRepository jobs) {
        this.service = service;
        this.onePercent = onePercent;
        this.files = files;
        this.jobs = jobs;
        this.worker = new JobWorkerPool("ownership-ingestion", 1, jobs, this::process);
    }

    /** Stores the workbook (or reuses the stored file with the same checksum) and queues it. */
    public synchronized Submission submit(String exchange, ExcelDocumentType documentType, byte[] content,
                                          String fileName, Requester requestedBy) {
        StoredFile file = files.store(content, fileName, XLSX);
        Optional<IngestionJob> active = jobs.activeFileJobOf(IngestionJobType.RAG_XLSX, file.fileId()).flatMap(jobs::find);
        if (active.isPresent()) {
            return new Submission(active.get(), false);
        }
        UUID id = UUID.randomUUID();
        String name = fileName.length() <= IngestionFileRepository.NAME_LENGTH
                ? fileName : fileName.substring(0, IngestionFileRepository.NAME_LENGTH);
        jobs.insertFileJob(new NewUpload(id, file.fileId(), name, file.reused(), "Waiting in the Excel queue ("
                + documentType.label() + ")", requestedBy), IngestionJobType.RAG_XLSX, exchange, null);
        tasks.put(id, new Task(exchange, documentType, file.fileId(), name));
        worker.enqueue(id);
        log.info("Excel job {} queued by {}: {} {} ({})", id, requestedBy == null ? "-" : requestedBy.username(),
                documentType, name, file.fileId());
        return new Submission(jobs.find(id).orElseThrow(), true);
    }

    public int pendingCount() {
        return worker.pendingCount();
    }

    private void process(UUID id) {
        Task task = tasks.remove(id);
        if (task == null) {
            throw new IllegalStateException("Excel job " + id + " has no task");
        }
        jobs.running(id, "Reading " + task.fileName());
        byte[] content = files.content(task.fileId())
                .orElseThrow(() -> new IllegalStateException("Stored file " + task.fileId() + " not found"));
        if (task.documentType() == ExcelDocumentType.OWNERSHIP_1_PERCENT) {
            processOnePercent(id, task, content);
            return;
        }
        OwnershipResult r;
        try {
            r = service.ingest(id, task.exchange(), task.documentType(), task.fileId(), task.fileName(), content,
                    stage -> jobs.progress(id, stage, null, null));
        } catch (OwnershipParser.OwnershipFormatException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Not KSEI's list of the shareholders of more than 5%",
                    e.getMessage(), null);
            return;
        }
        String summary = summary(r);
        if (r.ragError() != null) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, "The list is stored, but the vector store could not "
                    + "be written: " + r.ragError() + ". Upload the file again to retry.", r);
        } else if (!r.warnings().isEmpty()) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, String.join("; ", r.warnings()), r);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, summary, null, r);
        }
    }

    /** A monthly list of the shareholders of more than 1%. */
    private void processOnePercent(UUID id, Task task, byte[] content) {
        OnePercentResult r;
        try {
            r = onePercent.ingest(id, task.exchange(), task.fileId(), task.fileName(), content,
                    stage -> jobs.progress(id, stage, null, null));
        } catch (OnePercentParser.OnePercentFormatException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Not KSEI's list of the shareholders of more than 1%",
                    e.getMessage(), null);
            return;
        }
        String summary = summary(r);
        if (r.ragError() != null) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, "The list is stored, but the vector store could not "
                    + "be written: " + r.ragError() + ". Upload the file again to retry.", r);
        } else if (!r.warnings().isEmpty()) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, String.join("; ", r.warnings()), r);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, summary, null, r);
        }
    }

    /**
     * "List of 2026-09-30 stored: 7,034 investors of 961 companies, compared with 2026-08-31: 120 buys, 98 sells, ..."
     */
    static String summary(OnePercentResult r) {
        StringBuilder b = new StringBuilder("List of ").append(r.reportDate())
                .append(r.outcome() == OnePercentRepository.Outcome.REPLACED ? " stored again: " : " stored: ")
                .append(String.format(java.util.Locale.ROOT, "%,d", r.holders())).append(" investors of ")
                .append(r.issuers()).append(" companies");
        if (r.previousDate() == null) {
            b.append(" (no earlier list stored: no movements)");
        } else {
            b.append(", compared with ").append(r.previousDate()).append(": ").append(r.buys())
                    .append(r.buys() == 1 ? " buy, " : " buys, ").append(r.sells())
                    .append(r.sells() == 1 ? " sell, " : " sells, ").append(r.entered()).append(" new above 1%, ")
                    .append(r.exited()).append(" no longer above 1%");
            if (r.renamed() + r.moved() > 0) {
                b.append(", ").append(r.renamed()).append(" renamed, ").append(r.moved())
                        .append(r.moved() == 1 ? " block moved" : " blocks moved");
            }
        }
        if (r.nextDate() != null) {
            b.append("; the list of ").append(r.nextDate()).append(" compared with it again");
        }
        if (r.ragError() == null) {
            b.append("; ").append(r.ragDocuments()).append(" ownership documents (").append(r.chunks())
                    .append(" chunks) in the vector store");
        }
        return b.toString();
    }

    /** "List of 2026-10-27 stored: 1,921 investors of 843 companies; 12 buys, 9 sells, 3 crossed 5%, ..." */
    static String summary(OwnershipResult r) {
        StringBuilder b = new StringBuilder("List of ").append(r.reportDate())
                .append(r.outcome() == OwnershipRepository.Outcome.REPLACED ? " stored again: " : " stored: ")
                .append(String.format(java.util.Locale.ROOT, "%,d", r.holders())).append(" investors of ").append(r.issuers())
                .append(" companies; ").append(r.buys()).append(r.buys() == 1 ? " buy, " : " buys, ")
                .append(r.sells()).append(r.sells() == 1 ? " sell, " : " sells, ").append(r.entered())
                .append(" crossed 5%");
        b.append(r.exited() == null ? " (the list of " + r.previousDate() + " is not stored: no exits)"
                : ", " + r.exited() + " fell below 5%");
        if (r.ragError() == null) {
            b.append("; ").append(r.ragDocuments()).append(" ownership documents (").append(r.chunks())
                    .append(" chunks) in the vector store");
        }
        return b.toString();
    }

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
