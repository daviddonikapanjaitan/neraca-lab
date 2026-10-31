package com.neracalab.backend.ingestion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.ingestion.agent.AgentTrace;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionVerifier;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.WriteResult;
import com.neracalab.backend.rag.XbrlRagService;

/**
 * Result of one upload.
 *
 * @param status COMPLETED (everything stored and verified), INCOMPLETE (something is pending or
 *               failed validation; see verification) or FAILED (the agent could not run)
 * @param rag    the workbook's text in the RAG vector store (written by the upload job after the statements, whatever
 *               their outcome; never changes it); {@code null} when not written (switched off, the job interrupted)
 */
public record IngestionResponse(
        String ingestionId,
        Status status,
        String fileName,
        Filing filing,
        Company company,
        IngestionAgent.IngestionPlan plan,
        boolean planFromModel,
        List<IngestionAgent.Round> rounds,
        List<AgentTrace.Step> steps,
        List<AgentTrace.ToolInvocation> toolCalls,
        Map<String, List<WriteResult>> savedStatements,
        Map<String, List<String>> savedSegments,
        IngestionVerifier.Verification verification,
        List<String> notes,
        Metrics metrics,
        String error,
        Fallback fallback,
        XbrlRagService.Result rag) {

    /** Without the vector store part (the upload job adds it, {@link #withRag}). */
    public IngestionResponse(String ingestionId, Status status, String fileName, Filing filing, Company company,
                             IngestionAgent.IngestionPlan plan, boolean planFromModel, List<IngestionAgent.Round> rounds,
                             List<AgentTrace.Step> steps, List<AgentTrace.ToolInvocation> toolCalls,
                             Map<String, List<WriteResult>> savedStatements, Map<String, List<String>> savedSegments,
                             IngestionVerifier.Verification verification, List<String> notes, Metrics metrics,
                             String error, Fallback fallback) {
        this(ingestionId, status, fileName, filing, company, plan, planFromModel, rounds, steps, toolCalls,
                savedStatements, savedSegments, verification, notes, metrics, error, fallback, null);
    }

    /** This result with the workbook's document in the vector store. */
    public IngestionResponse withRag(XbrlRagService.Result result) {
        return new IngestionResponse(ingestionId, status, fileName, filing, company, plan, planFromModel, rounds, steps,
                toolCalls, savedStatements, savedSegments, verification, notes, metrics, error, fallback, result);
    }

    public enum Status { COMPLETED, INCOMPLETE, FAILED }

    /**
     * The full-text extraction that took over from the standard extraction; {@code null} when it was not needed.
     *
     * @param trigger    UNSUPPORTED_WORKBOOK, INCOMPLETE, SLOW_EXTRACTION or EXTRACTION_FAILED
     * @param reason     what went wrong in the standard extraction
     * @param model      the model that mapped the workbook ({@code neracalab.ingestion.fallback.model})
     * @param columns    columns whose statements the model mapped from the text of the workbook
     * @param statements statements it provided, e.g. "PRIOR_PERIOD 2024 FY income_statement"
     * @param problems   what it could not provide
     * @param steps      the saves done after it
     * @param error      why it stopped, when it did not finish
     */
    public record Fallback(String trigger, String reason, String model, List<String> columns, int modelCalls, List<String> statements,
                           List<String> problems, List<String> steps, String error) {
    }

    public record Filing(String ticker, String legalName, String submission, String periodType, int fiscalYear,
                         String periodStart, String periodEnd, boolean audited, String currency,
                         BigDecimal unitMultiplier, List<String> columns) {
    }

    public record Company(Long companyId, String ticker, String companyName) {
    }

    public record Metrics(long durationMs, int modelCalls, int toolCalls, int toolErrors, int parallelToolGroups,
                          int modelRetries, List<String> models) {
    }
}
