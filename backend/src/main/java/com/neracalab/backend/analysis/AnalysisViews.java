package com.neracalab.backend.analysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningViews.AgentScoreView;
import com.neracalab.backend.screening.ScreeningViews.Option;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;

import tools.jackson.databind.JsonNode;

/** Request and response bodies of the analysis API ({@code /api/v1/analyses}). */
public final class AnalysisViews {

    private AnalysisViews() {
    }

    /**
     * {@code POST /api/v1/analyses}.
     *
     * @param exchange IDX (default)
     * @param ticker   a company of the companies table
     * @param agents   investor agents: BUFFETT, MUNGER, LYNCH, FISHER, GILL, RISK (default: all six)
     */
    public record AnalysisRequest(String exchange, String ticker, List<String> agents) {
    }

    /**
     * One analysis as listed (also the head of the report).
     *
     * @param status         status of the job (QUEUED, RUNNING, SUCCEEDED, INCOMPLETE, FAILED)
     * @param stage          current step, or a one-line summary of a finished analysis
     * @param message        why the analysis failed or is incomplete
     * @param overallScore   0-100 after the synthesis adjustment (null until finished, or without any agent score)
     * @param verdict        band of the overall score: STRONG_FIT, FIT, NEUTRAL, WEAK, REJECT
     * @param marketDataDate date of the Yahoo Finance market data used (null: none)
     */
    public record AnalysisSummary(UUID id, IngestionJobStatus status, String stage, String message, String exchange,
                                  String ticker, String companyName, List<InvestorAgent> agents, Double overallScore,
                                  String verdict, String conviction, String marketDataDate, double budgetUsd,
                                  double costUsd, long promptTokens, long completionTokens, long reasoningTokens,
                                  long cachedTokens, int modelCalls, Instant requestedAt, Instant startedAt,
                                  Instant finishedAt, IngestionJob.CreatedBy createdBy) {

        public boolean finished() {
            return status != null && !status.active();
        }
    }

    /** {@code GET /api/v1/analyses}: one page, most recent first. */
    public record AnalysisPage(long total, int limit, int offset, List<AnalysisSummary> analyses) {
    }

    /**
     * The full report.
     *
     * @param quantOverall         the overall of the quantitative scorecards alone (null without market data)
     * @param synthesisAdjustment  points the synthesis added or removed (null: none)
     * @param context              what the agents saw: fact sheet, market data, stored documents
     * @param research             the research brief, the ReAct steps and the retrieved excerpts
     * @param synthesis            executive summary, conviction, thesis, bull and bear case, risks, model / fallback
     * @param notes                degraded steps, data gaps, Reflexion lessons applied and learned, budget
     */
    public record AnalysisReport(AnalysisSummary run, Double quantOverall, Double synthesisAdjustment, JsonNode context,
                                 JsonNode research, JsonNode synthesis, JsonNode notes, List<AgentScoreView> agents,
                                 List<UsageRow> usage) {
    }

    /**
     * A company that can be analysed, with what the database holds for it.
     *
     * @param periods          stored reporting periods
     * @param latestPeriod     the most recent one, e.g. "2026 H1"
     * @param pdfDocuments     PDF documents in the vector store
     * @param newsDocuments    news articles in the vector store
     * @param marketDataDate   latest Yahoo Finance snapshot (null: not in the screening data)
     * @param latestPriceDate  latest stored daily price
     */
    public record CompanyOption(String exchange, String ticker, String companyName, String sector, int periods,
                                String latestPeriod, long pdfDocuments, long newsDocuments, String marketDataDate,
                                String latestPriceDate) {
    }

    /** {@code GET /api/v1/analyses/options}. */
    public record Options(List<CompanyOption> companies, List<Option> agents, double budgetUsd, String researchModel,
                          String agentModel, String synthesisModel) {
    }
}
