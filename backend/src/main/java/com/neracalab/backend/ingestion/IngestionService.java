package com.neracalab.backend.ingestion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.ingestion.IngestionResponse.Fallback;
import com.neracalab.backend.ingestion.IngestionResponse.Status;
import com.neracalab.backend.ingestion.agent.AgentTrace;
import com.neracalab.backend.ingestion.agent.FullTextExtractor;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.agent.IngestionVerifier;
import com.neracalab.backend.ingestion.agent.JobDeadline;
import com.neracalab.backend.ingestion.error.IngestionErrorRepository;
import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.WriteResult;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Reads an uploaded workbook and stores it in two stages:
 * <ol>
 *   <li>the <b>standard extraction</b>: the workbook is mapped deterministically and the AI agent stores it;</li>
 *   <li>the <b>full-text extraction</b> ({@link FullTextExtractor}), when the standard extraction does not get
 *       there: the workbook is not one it can map (e.g. "No balance sheet data"), its result is INCOMPLETE (a
 *       statement fails a validation), it failed, or it ran longer than {@code neracalab.ingestion.fallback.after}
 *       (3 minutes). The model then maps the text of the whole workbook to the database fields.</li>
 * </ol>
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IdxWorkbookReader reader;
    private final IngestionAgent agent;
    private final IngestionRepository repository;
    private final WebShareCounts webShareCounts;
    private final FullTextExtractor fullText;

    public IngestionService(IdxWorkbookReader reader, IngestionAgent agent, IngestionRepository repository,
                            WebShareCounts webShareCounts, FullTextExtractor fullText) {
        this.reader = reader;
        this.agent = agent;
        this.repository = repository;
        this.webShareCounts = webShareCounts;
        this.fullText = fullText;
    }

    /**
     * Reads a workbook: an .xlsx that is an IDX XBRL financial statement (sheet 1000000 with the company and the
     * periods). Fast (no model call). The statements may still be in a form the standard extraction cannot map
     * ({@code session.mapper().templateProblems()}): such a workbook goes to the full-text extraction.
     *
     * @throws IdxWorkbookException not an .xlsx or not an IDX XBRL workbook: nothing can be extracted from it
     */
    public IngestionSession open(byte[] content, String fileName) {
        IdxWorkbook workbook;
        try (InputStream in = new ByteArrayInputStream(content)) {
            workbook = reader.read(in, fileName);
        } catch (IOException e) {
            throw new IdxWorkbookException("Cannot read the upload: " + e.getMessage(), e);
        }
        FilingMapper mapper;
        try {
            mapper = new FilingMapper(workbook);
        } catch (IdxWorkbookException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IdxWorkbookException("Cannot map '" + fileName + "': " + e.getMessage(), e);
        }
        return new IngestionSession(mapper);
    }

    /**
     * {@link #open} for the upload endpoint, which rejects a file before it is stored: also a workbook the
     * standard extraction cannot map, when the full-text extraction is switched off (nothing could store it).
     *
     * @throws IdxWorkbookException the upload is refused
     */
    public IngestionSession check(byte[] content, String fileName) {
        IngestionSession session = open(content, fileName);
        if (!fullText.properties().enabled() && !session.mapper().templateProblems().isEmpty()) {
            throw new IdxWorkbookException(unsupported(session));
        }
        return session;
    }

    private static String unsupported(IngestionSession session) {
        return "Unsupported filing: " + String.join("; ", session.mapper().templateProblems());
    }

    /**
     * Longest the standard extraction may run: the job limit, or the moment the full-text extraction takes over
     * when that is earlier.
     */
    public Duration standardLimit(Duration jobTimeout) {
        Duration after = fullText.properties().after();
        return fullText.properties().enabled() && after.compareTo(jobTimeout) < 0 ? after : jobTimeout;
    }

    public boolean fallbackEnabled() {
        return fullText.properties().enabled();
    }

    /**
     * Stores a workbook (takes minutes: model calls). The standard extraction runs within the session's deadline;
     * when it does not store the workbook, the full-text extraction takes over with a deadline of its own
     * ({@code neracalab.ingestion.fallback.timeout}). A failure is returned as FAILED, never thrown.
     *
     * @param progress told when the full-text extraction starts (the job stage)
     */
    public IngestionResponse run(IngestionSession session, Consumer<String> progress) {
        FilingMapper mapper = session.mapper();
        long start = System.currentTimeMillis();
        log.info("ingestion {} started: {} {} {}", session.id(), session.info().fileName(), mapper.info().ticker(),
                mapper.info().current().key());
        IngestionAgent.Outcome outcome = null;
        IngestionVerifier.Verification verification = null;
        Status status;
        String error = null;
        IngestionErrorRepository.Type trigger = null;
        String reason = null;
        if (!mapper.templateProblems().isEmpty()) {
            // not a workbook the standard extraction can map: the agent is not started
            status = Status.FAILED;
            error = unsupported(session);
            trigger = IngestionErrorRepository.Type.UNSUPPORTED_WORKBOOK;
            reason = error;
            try {
                webShareCounts.complete(session);      // share counts are not rows of the statements
            } catch (RuntimeException e) {
                log.warn("ingestion {}: web share counts not available: {}", session.id(), e.getMessage());
            }
        } else {
            try {
                webShareCounts.complete(session);
                session.deadline().check("the AI agent started");
                outcome = agent.run(session);
                verification = outcome.verification();
                status = verification.complete() ? Status.COMPLETED : Status.INCOMPLETE;
                log.info("ingestion {} finished: {}", session.id(), status);
                if (status == Status.INCOMPLETE) {
                    trigger = IngestionErrorRepository.Type.INCOMPLETE;
                    reason = openItems(verification);
                }
            } catch (JobDeadline.JobTimeoutException e) {
                log.warn("ingestion {} stopped: {}", session.id(), e.getMessage());
                status = Status.FAILED;
                error = e.getMessage();
                trigger = IngestionErrorRepository.Type.SLOW_EXTRACTION;
                reason = "The standard extraction ran longer than " + JobDeadline.describe(session.deadline().limit());
            } catch (RuntimeException e) {
                log.error("ingestion {} failed", session.id(), e);
                status = Status.FAILED;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                trigger = IngestionErrorRepository.Type.EXTRACTION_FAILED;
                reason = error;
            }
        }

        Fallback fallback = null;
        int fallbackModelCalls = 0;
        if (trigger != null && fullText.properties().enabled() && !Thread.currentThread().isInterrupted()) {
            Duration limit = fullText.properties().timeout();
            progress.accept("Full-text extraction of " + mapper.info().ticker() + " " + mapper.info().current().key()
                    + ": the model maps the text of the whole workbook (" + describe(trigger, session) + "; stopped after "
                    + JobDeadline.describe(limit) + ")");
            session.deadline(JobDeadline.after(limit));
            try {
                FullTextExtractor.Result result = fullText.run(session, reason);
                verification = result.verification();
                status = verification.complete() ? Status.COMPLETED : Status.INCOMPLETE;
                error = null;
                fallbackModelCalls = result.report().modelCalls();
                fallback = new Fallback(trigger.name(), reason, result.report().columns(), result.report().modelCalls(),
                        result.report().mapped(), result.report().problems(), result.steps(), null);
                log.info("ingestion {}: full-text extraction finished: {} (columns {}, statements {})", session.id(), status,
                        result.report().columns(), result.report().mapped());
            } catch (JobDeadline.JobTimeoutException e) {
                log.warn("ingestion {}: full-text extraction stopped: {}", session.id(), e.getMessage());
                status = Status.FAILED;
                error = e.getMessage();
                fallback = new Fallback(trigger.name(), reason, List.of(), 0, List.of(), List.of(), List.of(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("ingestion {}: full-text extraction failed", session.id(), e);
                // what the standard extraction reached stays: an INCOMPLETE upload remains INCOMPLETE
                String failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (status != Status.INCOMPLETE) {
                    status = Status.FAILED;
                    error = error == null ? failure : error;
                }
                fallback = new Fallback(trigger.name(), reason, List.of(), 0, List.of(), List.of(), List.of(), failure);
            }
        }
        if (status == Status.FAILED) {
            refreshAfterFailure(session);
        }
        return response(session, outcome, verification, status, error, fallback, fallbackModelCalls, start);
    }

    private static String describe(IngestionErrorRepository.Type trigger, IngestionSession session) {
        return switch (trigger) {
            case UNSUPPORTED_WORKBOOK -> "the standard extraction cannot read this workbook";
            case INCOMPLETE -> "the standard extraction left open items";
            case SLOW_EXTRACTION -> "the standard extraction took longer than " + JobDeadline.describe(session.deadline().limit());
            default -> "the standard extraction failed";
        };
    }

    /** Pending items and problems of a verification. */
    static String openItems(IngestionVerifier.Verification verification) {
        if (verification == null) {
            return "The verification did not run";
        }
        List<String> items = new ArrayList<>(verification.pending());
        items.addAll(verification.problems());
        return items.isEmpty() ? "The verification reported the filing as incomplete" : String.join("; ", items);
    }

    /** Keeps market / valuation data consistent with what was saved before a failure. */
    private void refreshAfterFailure(IngestionSession session) {
        if (session.hasWrites() && !session.isDerivedCurrent()) {
            try {
                repository.refreshDerivedData();
                session.derivedRefreshed();
            } catch (RuntimeException refresh) {
                log.error("ingestion {}: derived data refresh after the failure failed", session.id(), refresh);
            }
        }
    }

    private IngestionResponse response(IngestionSession session, IngestionAgent.Outcome outcome,
                                       IngestionVerifier.Verification verification, Status status, String error,
                                       Fallback fallback, int fallbackModelCalls, long start) {
        FilingInfo info = session.info();
        var filing = new IngestionResponse.Filing(info.ticker(), info.legalName(), info.submission(),
                info.current().periodType(), info.current().fiscalYear(), info.current().start().toString(),
                info.current().end().toString(), info.audited(), info.currency(), session.mapper().unit(),
                session.mapper().columns().stream().map(c -> c + " = " + session.mapper().period(c).key()).toList());
        var company = session.company().or(() -> repository.findCompany(info.ticker()))
                .map(c -> new IngestionResponse.Company(c.companyId(), c.ticker(), c.companyName()))
                .orElse(new IngestionResponse.Company(null, info.ticker(), null));
        Map<String, List<WriteResult>> statements = new LinkedHashMap<>();
        session.savedStatements().forEach((c, w) -> statements.put(label(session, c), w));
        Map<String, List<String>> segments = new LinkedHashMap<>();
        session.savedSegments().forEach((c, s) -> segments.put(label(session, c), s));
        AgentTrace trace = outcome == null ? new AgentTrace() : outcome.trace();
        var metrics = new IngestionResponse.Metrics(System.currentTimeMillis() - start, trace.modelCalls() + fallbackModelCalls,
                trace.invocations().size(), (int) trace.invocations().stream().filter(AgentTrace.ToolInvocation::error).count(),
                trace.parallelGroups(), trace.modelRetries(), trace.modelsUsed());
        return new IngestionResponse(session.id(), status, info.fileName(), filing, company,
                outcome == null ? null : outcome.plan(), outcome != null && outcome.planFromModel(),
                outcome == null ? List.of() : outcome.rounds(), trace.steps(), trace.invocations(),
                statements, segments, verification, session.notes(), metrics, error, fallback);
    }

    private static String label(IngestionSession session, StatementColumn column) {
        return column + " (" + session.mapper().period(column).key() + ")";
    }
}
