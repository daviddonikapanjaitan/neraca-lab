package com.neracalab.backend.ingestion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.ingestion.agent.AgentTrace;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionVerifier;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.WriteResult;

/**
 * Result of one upload.
 *
 * @param status COMPLETED (everything stored and verified), INCOMPLETE (something is pending or
 *               failed validation; see verification) or FAILED (the agent could not run)
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
        String error) {

    public enum Status { COMPLETED, INCOMPLETE, FAILED }

    public record Filing(String ticker, String legalName, String submission, String periodType, int fiscalYear,
                         String periodStart, String periodEnd, boolean audited, String currency,
                         BigDecimal unitMultiplier, List<String> columns) {
    }

    public record Company(Long companyId, String ticker, String companyName) {
    }

    public record Metrics(long durationMs, int modelCalls, int toolCalls, int toolErrors, int parallelToolGroups) {
    }
}
