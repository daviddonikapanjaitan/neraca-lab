package com.neracalab.backend.rag;

import java.time.Instant;
import java.time.LocalDate;
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
import com.neracalab.backend.job.IngestionJobRepository.Snapshot;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.JobProperties;
import com.neracalab.backend.job.JobWorkerPool;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.rag.RagIngestionService.MacroMeta;
import com.neracalab.backend.rag.RagIngestionService.MacroPdfResult;
import com.neracalab.backend.rag.RagIngestionService.NewsResult;
import com.neracalab.backend.rag.RagIngestionService.PdfResult;
import com.neracalab.backend.rag.RagRepository.Company;

/**
 * Asynchronous RAG ingestions (job types RAG_PDF and RAG_NEWS in {@code ingestion_job}; a macro PDF of a country is a
 * RAG_PDF job of exchange {@link #MACRO_EXCHANGE}). A submit records a QUEUED job and returns at once; the worker
 * threads ({@code neracalab.jobs.workers}, 5) chunk, embed and store the documents, that many jobs at the same time.
 * At most one active job per company (or country) and source (an identical request while one is queued / running
 * returns that job), so two running jobs never write the same document; the news
 * sites are still read one request at a time per site ({@code NewsHttpClient}). The queue is in memory: jobs still
 * active when the application stops are failed at the next start.
 */
@Component
public class RagQueue implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RagQueue.class);

    public record Submission(IngestionJob job, boolean created) {
    }

    /**
     * Exchange of a macro PDF's job ({@code ingestion_job.exchange}, as the Macro jobs); its ticker is the country
     * (ID).
     */
    public static final String MACRO_EXCHANGE = "MACRO";

    private sealed interface Task permits PdfTask, NewsTask, MacroPdfTask {
    }

    private record PdfTask(Company company, long fileId, String fileName, String checksum) implements Task {
    }

    private record NewsTask(Company company, LocalDate from, LocalDate to) implements Task {
    }

    private record MacroPdfTask(MacroMeta meta, long fileId, String fileName, String checksum) implements Task {
    }

    private final RagIngestionService service;
    private final IngestionFileRepository files;
    private final IngestionJobRepository jobs;
    private final JobWorkerPool worker;
    private final Map<UUID, Task> tasks = new ConcurrentHashMap<>();

    public RagQueue(RagIngestionService service, IngestionFileRepository files, IngestionJobRepository jobs,
                    JobProperties properties) {
        this.service = service;
        this.files = files;
        this.jobs = jobs;
        this.worker = new JobWorkerPool("rag-ingestion", properties.workers(), jobs, this::process);
    }

    // ------------------------------------------------------------------ API

    /** Stores the PDF (or reuses the stored file with the same checksum) and queues its ingestion for the company. */
    public synchronized Submission submitPdf(Company company, byte[] content, String fileName, String contentType,
                                             Requester requestedBy) {
        StoredFile file = files.store(content, fileName, contentType);
        Optional<IngestionJob> active = jobs.activeFileJobOf(IngestionJobType.RAG_PDF, file.fileId(), company.exchange(),
                company.ticker()).flatMap(jobs::find);
        if (active.isPresent()) {
            return new Submission(active.get(), false);
        }
        UUID id = UUID.randomUUID();
        String name = fileName.length() <= IngestionFileRepository.NAME_LENGTH
                ? fileName : fileName.substring(0, IngestionFileRepository.NAME_LENGTH);
        jobs.insertFileJob(new NewUpload(id, file.fileId(), name, file.reused(), "Waiting in the RAG queue", requestedBy),
                IngestionJobType.RAG_PDF, company.exchange(), company.ticker());
        enqueue(id, new PdfTask(company, file.fileId(), name, file.checksumSha256()));
        log.info("RAG PDF job {} queued by {}: {} {} ({})", id, requester(requestedBy), company.ticker(), name, file.fileId());
        return new Submission(jobs.find(id).orElseThrow(), true);
    }

    /**
     * Stores the macro PDF (or reuses the stored file with the same checksum) and queues its ingestion for the
     * country; the same file already queued or running for the country returns that job.
     */
    public synchronized Submission submitMacroPdf(MacroMeta meta, byte[] content, String fileName, String contentType,
                                                  Requester requestedBy) {
        StoredFile file = files.store(content, fileName, contentType);
        Optional<IngestionJob> active = jobs.activeFileJobOf(IngestionJobType.RAG_PDF, file.fileId(), MACRO_EXCHANGE,
                meta.country()).flatMap(jobs::find);
        if (active.isPresent()) {
            return new Submission(active.get(), false);
        }
        UUID id = UUID.randomUUID();
        String name = fileName.length() <= IngestionFileRepository.NAME_LENGTH
                ? fileName : fileName.substring(0, IngestionFileRepository.NAME_LENGTH);
        jobs.insertFileJob(new NewUpload(id, file.fileId(), name, file.reused(),
                        "Waiting in the RAG queue (macro document of " + meta.countryName() + ")", requestedBy),
                IngestionJobType.RAG_PDF, MACRO_EXCHANGE, meta.country());
        enqueue(id, new MacroPdfTask(meta, file.fileId(), name, file.checksumSha256()));
        log.info("RAG macro PDF job {} queued by {}: {} {} ({}, {}, {})", id, requester(requestedBy), meta.country(), name,
                meta.documentType(), meta.publishedOn(), file.fileId());
        return new Submission(jobs.find(id).orElseThrow(), true);
    }

    /** Queues the news ingestion of the company for the date range (inclusive). */
    public synchronized Submission submitNews(Company company, LocalDate from, LocalDate to, Requester requestedBy) {
        Optional<IngestionJob> active = jobs.activeJobOf(IngestionJobType.RAG_NEWS, company.exchange(), company.ticker())
                .flatMap(jobs::find);
        if (active.isPresent()) {
            return new Submission(active.get(), false);
        }
        UUID id = UUID.randomUUID();
        jobs.save(new Snapshot(id, IngestionJobType.RAG_NEWS, IngestionJobStatus.QUEUED,
                "Waiting in the RAG queue (news " + from + " to " + to + ")", company.exchange(), company.ticker(),
                null, 0, null, Instant.now(), null, null, null, null, requestedBy));
        enqueue(id, new NewsTask(company, from, to));
        log.info("RAG news job {} queued by {}: {} {} .. {}", id, requester(requestedBy), company.ticker(), from, to);
        return new Submission(jobs.find(id).orElseThrow(), true);
    }

    public int pendingCount() {
        return worker.pendingCount();
    }

    private void enqueue(UUID id, Task task) {
        tasks.put(id, task);
        worker.enqueue(id);
    }

    private static String requester(Requester r) {
        return r == null ? "-" : r.username();
    }

    // ------------------------------------------------------------------ worker

    private void process(UUID id) {
        Task task = tasks.remove(id);
        if (task == null) {
            throw new IllegalStateException("RAG job " + id + " has no task");
        }
        switch (task) {
            case PdfTask pdf -> processPdf(id, pdf);
            case NewsTask news -> processNews(id, news);
            case MacroPdfTask macro -> processMacroPdf(id, macro);
        }
    }

    private void processMacroPdf(UUID id, MacroPdfTask task) {
        jobs.running(id, "Reading " + task.fileName());
        byte[] content = files.content(task.fileId())
                .orElseThrow(() -> new IllegalStateException("Stored file " + task.fileId() + " not found"));
        MacroPdfResult result;
        try {
            result = service.ingestMacroPdf(id, task.meta(), task.fileId(), task.fileName(), task.checksum(), content,
                    stage -> jobs.progress(id, stage, null, null));
        } catch (PdfText.PdfException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Invalid PDF", e.getMessage(), null);
            return;
        } catch (EmbeddingClient.EmbeddingException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Embedding failed", e.getMessage(), null);
            return;
        }
        jobs.finish(id, IngestionJobStatus.SUCCEEDED, "Stored " + result.chunks() + " chunks of " + result.pages()
                + " pages for " + task.meta().countryName() + " (macro document)", null, result);
    }

    private void processPdf(UUID id, PdfTask task) {
        jobs.running(id, "Reading " + task.fileName());
        byte[] content = files.content(task.fileId())
                .orElseThrow(() -> new IllegalStateException("Stored file " + task.fileId() + " not found"));
        PdfResult result;
        try {
            result = service.ingestPdf(id, task.company(), task.fileId(), task.fileName(), task.checksum(), content,
                    stage -> jobs.progress(id, stage, null, null));
        } catch (PdfText.PdfException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Invalid PDF", e.getMessage(), null);
            return;
        } catch (EmbeddingClient.EmbeddingException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Embedding failed", e.getMessage(), null);
            return;
        }
        jobs.finish(id, IngestionJobStatus.SUCCEEDED, "Stored " + result.chunks() + " chunks of " + result.pages()
                + " pages for " + task.company().ticker(), null, result);
    }

    private void processNews(UUID id, NewsTask task) {
        String range = task.from() + " to " + task.to();
        jobs.running(id, "Collecting " + task.company().ticker() + " news " + range);
        NewsResult result;
        try {
            result = service.ingestNews(id, task.company(), task.from(), task.to(), stage -> jobs.progress(id, stage, null, null));
        } catch (EmbeddingClient.EmbeddingException e) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Embedding failed", e.getMessage(), null);
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            jobs.finish(id, IngestionJobStatus.FAILED, "Interrupted", "Stopped: the application is shutting down", result);
            return;
        }
        boolean allSourcesFailed = !result.sources().isEmpty()
                && result.sources().stream().allMatch(s -> s.error() != null);
        String summary = result.stored() + " stored, " + result.alreadyStored() + " already stored"
                + (result.failed() > 0 ? ", " + result.failed() + " failed" : "")
                + (result.outOfRange() > 0 ? ", " + result.outOfRange() + " outside the range" : "");
        if (allSourcesFailed) {
            jobs.finish(id, IngestionJobStatus.FAILED, "No news source could be read",
                    String.join("; ", result.sources().stream().map(s -> s.source().label() + ": " + s.error()).toList()),
                    result);
        } else if (result.failed() > 0 || result.truncated()) {
            String message = (result.failed() > 0 ? result.failed() + " articles could not be read" : "")
                    + (result.failed() > 0 && result.truncated() ? "; " : "")
                    + (result.truncated() ? "only the newest " + result.articles().size() + " of "
                    + result.headlinesInRange() + " articles were read: narrow the range or run it again" : "");
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, task.company().ticker() + " news " + range + ": " + summary,
                    message, result);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, result.headlinesInRange() == 0
                    ? "No " + task.company().ticker() + " news found " + range
                    : task.company().ticker() + " news " + range + ": " + summary, null, result);
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
