package com.neracalab.backend.macro.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.screening.ScreeningViews.Option;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;

import tools.jackson.databind.JsonNode;

/** Request and response bodies of the macro analysis API ({@code /api/v1/macro/analyses}). */
public final class MacroAnalysisViews {

    private MacroAnalysisViews() {
    }

    /**
     * {@code POST /api/v1/macro/analyses}.
     *
     * @param country ID (default; the United States and China come later)
     * @param index   an index of the country: IHSG (default) or LQ45
     * @param agents  macro agents: TUDOR_JONES, DRUCKENMILLER, SOROS, DALIO (default: all four)
     */
    public record MacroAnalysisRequest(String country, String index, List<String> agents) {
    }

    /**
     * One macro analysis as listed (also the head of the report).
     *
     * @param status       status of the job (QUEUED, RUNNING, SUCCEEDED, INCOMPLETE, FAILED)
     * @param stage        current step, or a one-line summary of a finished analysis
     * @param message      why the analysis failed or is incomplete
     * @param overallScore investability 0-100 after the synthesis adjustment (null until finished, or without a score)
     * @param rulesScore   the Macro page's rules score as of the analysis
     * @param verdict      INVESTABLE (60+), SELECTIVE (40-59), NOT_INVESTABLE (below 40)
     * @param action       BUY, ACCUMULATE, WAIT or SELL
     */
    public record MacroAnalysisSummary(UUID id, IngestionJobStatus status, String stage, String message, String country,
                                       String countryName, String indexCode, String indexName, List<MacroAgent> agents,
                                       Double overallScore, Double rulesScore, String verdict, String action,
                                       String conviction, String asOfDate, double budgetUsd, double costUsd,
                                       long promptTokens, long completionTokens, long reasoningTokens, long cachedTokens,
                                       int modelCalls, Instant requestedAt, Instant startedAt, Instant finishedAt,
                                       IngestionJob.CreatedBy createdBy) {

        public boolean finished() {
            return status != null && !status.active();
        }
    }

    /** {@code GET /api/v1/macro/analyses}: one page, most recent first. */
    public record MacroAnalysisPage(long total, int limit, int offset, List<MacroAnalysisSummary> analyses) {
    }

    /**
     * One macro agent's view.
     *
     * @param priorScore  the agent's rules preset as 0-100 (null: the preset has too few signals)
     * @param priorDetail the preset: stance, its label, its score
     * @param status      ASSESSED, REVISED (after the reflection review), PRIOR_ONLY (no model answer), NO_SCORE
     */
    public record MacroAgentView(MacroAgent agent, String label, String focus, Double priorScore, JsonNode priorDetail,
                                 Double aiScore, Double finalScore, String stance, String thesis, JsonNode supports,
                                 JsonNode risks, JsonNode buyWhen, JsonNode sellWhen, JsonNode reflection, String status) {
    }

    /**
     * The full report.
     *
     * @param synthesisAdjustment points the synthesis added or removed (null: none)
     * @param context             what the agents saw: the rules' outlook, the indexes, the commodities and currency pairs
     * @param research            the brief, the ReAct steps and the tool results
     * @param synthesis           executive summary, conviction, thesis, bull and bear case, risks, sector tilts
     * @param notes               degraded steps, data gaps, Reflexion lessons applied and learned, budget
     */
    public record MacroAnalysisReport(MacroAnalysisSummary run, Double synthesisAdjustment, JsonNode context,
                                      JsonNode research, JsonNode synthesis, JsonNode notes, List<MacroAgentView> agents,
                                      List<UsageRow> usage) {
    }

    /**
     * {@code GET /api/v1/macro/analyses/options}.
     *
     * @param countries the countries and their indexes (the outlook's list: only Indonesia is available yet)
     * @param data      what the database holds: macro series with data, commodities and currency pairs with prices
     */
    public record Options(List<Map<String, Object>> countries, List<Option> agents, double budgetUsd,
                          String researchModel, String agentModel, String synthesisModel, Data data) {
    }

    /**
     * @param macroSeries   macro series with stored observations (of {@code macroSeriesTotal} in the catalog)
     * @param commodities   commodities with stored prices
     * @param currencyPairs currency pairs with stored prices
     */
    public record Data(int macroSeries, int macroSeriesTotal, int commodities, int currencyPairs) {
    }
}
