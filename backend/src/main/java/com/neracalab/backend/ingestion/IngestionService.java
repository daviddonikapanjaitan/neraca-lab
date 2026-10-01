package com.neracalab.backend.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

/** Reads the upload, maps it deterministically, then lets the agent store it. */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IdxWorkbookReader reader;
    private final IngestionAgent agent;
    private final IngestionRepository repository;

    public IngestionService(IdxWorkbookReader reader, IngestionAgent agent, IngestionRepository repository) {
        this.reader = reader;
        this.agent = agent;
        this.repository = repository;
    }

    public IngestionResponse ingest(MultipartFile file) {
        String fileName = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        IdxWorkbook workbook;
        try (InputStream in = file.getInputStream()) {
            workbook = reader.read(in, fileName);
        } catch (IOException e) {
            throw new IdxWorkbookException("Cannot read the upload: " + e.getMessage(), e);
        }
        FilingMapper mapper = new FilingMapper(workbook);
        if (!mapper.templateProblems().isEmpty()) {
            throw new IdxWorkbookException("Unsupported filing: " + String.join("; ", mapper.templateProblems()));
        }
        IngestionSession session = new IngestionSession(mapper);
        long start = System.currentTimeMillis();
        log.info("ingestion {} started: {} {} {}", session.id(), fileName, mapper.info().ticker(), mapper.info().current().key());
        try {
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
                info.current().end().toString(), info.audited(), info.currency(), info.unitMultiplier(),
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
                trace.parallelGroups());
        return new IngestionResponse(session.id(), status, info.fileName(), filing, company,
                outcome == null ? null : outcome.plan(), outcome != null && outcome.planFromModel(),
                outcome == null ? List.of() : outcome.rounds(), trace.steps(), trace.invocations(),
                statements, segments, outcome == null ? null : outcome.verification(), session.notes(), metrics, error);
    }

    private static String label(IngestionSession session, StatementColumn column) {
        return column + " (" + session.mapper().period(column).key() + ")";
    }
}
