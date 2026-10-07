package com.neracalab.backend.ingestion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.ingestion.IngestionResponse.Status;
import com.neracalab.backend.ingestion.agent.AgentTrace;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.WriteResult;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/** Reads an uploaded workbook, maps it deterministically, then lets the agent store it. */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IdxWorkbookReader reader;
    private final IngestionAgent agent;
    private final IngestionRepository repository;
    private final WebShareCounts webShareCounts;

    public IngestionService(IdxWorkbookReader reader, IngestionAgent agent, IngestionRepository repository,
                            WebShareCounts webShareCounts) {
        this.reader = reader;
        this.agent = agent;
        this.repository = repository;
        this.webShareCounts = webShareCounts;
    }

    /**
     * Reads and checks a workbook: an IDX XBRL financial statement in a supported template.
     * Fast (no model call); the upload endpoint uses it to reject a wrong file before storing it.
     *
     * @throws IdxWorkbookException not an .xlsx, not an IDX XBRL workbook or an unsupported template
     */
    public IngestionSession prepare(byte[] content, String fileName) {
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
        if (!mapper.templateProblems().isEmpty()) {
            throw new IdxWorkbookException("Unsupported filing: " + String.join("; ", mapper.templateProblems()));
        }
        return new IngestionSession(mapper);
    }

    /** Lets the agent store a prepared workbook (takes minutes: model calls); an agent failure is returned as FAILED. */
    public IngestionResponse run(IngestionSession session) {
        FilingMapper mapper = session.mapper();
        long start = System.currentTimeMillis();
        log.info("ingestion {} started: {} {} {}", session.id(), session.info().fileName(), mapper.info().ticker(),
                mapper.info().current().key());
        try {
            webShareCounts.complete(session);
            IngestionAgent.Outcome outcome = agent.run(session);
            Status status = outcome.verification().complete() ? Status.COMPLETED : Status.INCOMPLETE;
            log.info("ingestion {} finished: {}", session.id(), status);
            return response(session, outcome, status, null, start);
        } catch (RuntimeException e) {
            log.error("ingestion {} failed", session.id(), e);
            return response(session, null, Status.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage(), start);
        }
    }

    private IngestionResponse response(IngestionSession session, IngestionAgent.Outcome outcome, Status status,
                                       String error, long start) {
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
        var metrics = new IngestionResponse.Metrics(System.currentTimeMillis() - start, trace.modelCalls(),
                trace.invocations().size(), (int) trace.invocations().stream().filter(AgentTrace.ToolInvocation::error).count(),
                trace.parallelGroups(), trace.modelRetries(), trace.modelsUsed());
        return new IngestionResponse(session.id(), status, info.fileName(), filing, company,
                outcome == null ? null : outcome.plan(), outcome != null && outcome.planFromModel(),
                outcome == null ? List.of() : outcome.rounds(), trace.steps(), trace.invocations(),
                statements, segments, outcome == null ? null : outcome.verification(), session.notes(), metrics, error);
    }

    private static String label(IngestionSession session, StatementColumn column) {
        return column + " (" + session.mapper().period(column).key() + ")";
    }
}
