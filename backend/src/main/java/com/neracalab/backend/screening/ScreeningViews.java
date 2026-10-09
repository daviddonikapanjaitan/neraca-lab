package com.neracalab.backend.screening;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;

import tools.jackson.databind.JsonNode;

/** Request and response bodies of the screening API ({@code /api/v1/screenings}). */
public final class ScreeningViews {

    private ScreeningViews() {
    }

    /**
     * {@code POST /api/v1/screenings}: either a market-cap tier or selected stocks.
     *
     * @param marketCapTier LARGE, MID (or MEDIUM) or SMALL; omitted when {@code tickers} are given
     * @param tickers       stocks of the companies table to screen (Screening > Selected Stocks); omitted for a tier
     * @param topN          stocks in the final ranking (1..max-top-n, at most the number of selected stocks)
     * @param agents        investor agents: BUFFETT, MUNGER, LYNCH, FISHER, GILL, RISK (at least one)
     */
    public record ScreeningRequest(String exchange, String marketCapTier, List<String> tickers, Integer topN,
                                   List<String> agents) {
    }

    /**
     * One run as listed (also the head of the report).
     *
     * @param status        status of the job (QUEUED, RUNNING, SUCCEEDED, INCOMPLETE, FAILED)
     * @param stage         current step, or a one-line summary of a finished run
     * @param message       why the run failed or is incomplete
     * @param marketCapTier the tier screened; null for a screening of selected stocks
     * @param tickers       the selected stocks; null for a screening of a tier
     */
    public record RunSummary(UUID id, IngestionJobStatus status, String stage, String message, String exchange,
                             MarketCapTier marketCapTier, List<String> tickers, int topN, List<InvestorAgent> agents,
                             String snapshotDate,
                             Integer universeCount, Integer eligibleCount, Integer shortlistCount, Integer selectedCount,
                             double budgetUsd, double costUsd, long promptTokens, long completionTokens,
                             long reasoningTokens, long cachedTokens, int modelCalls, Instant requestedAt,
                             Instant startedAt, Instant finishedAt, IngestionJob.CreatedBy createdBy) {

        public boolean finished() {
            return status != null && !status.active();
        }

        /** "Large cap", or "5 selected stocks" for a screening of selected stocks. */
        public String scopeLabel() {
            if (marketCapTier != null) {
                return marketCapTier.label();
            }
            int n = tickers == null ? 0 : tickers.size();
            return n + (n == 1 ? " selected stock" : " selected stocks");
        }
    }

    /** Score of one investor agent for one candidate. */
    public record AgentScoreView(InvestorAgent agent, String label, Double quantScore, JsonNode quantDetail,
                                 Double llmScore, Double finalScore, String verdict, String thesis, JsonNode strengths,
                                 JsonNode concerns, JsonNode reflection, String status) {
    }

    /** A candidate of the shortlist; {@code selected} = in the final top N. */
    public record CandidateView(long id, String ticker, String companyName, String sector, String industry,
                                Double quantOverall, Integer quantRank, Double overallScore, Double synthesisAdjustment,
                                Integer finalRank, boolean selected, String conviction, String thesis, JsonNode metrics,
                                JsonNode news, JsonNode redFlags, List<AgentScoreView> agents) {
    }

    /** Model usage of a run per stage and model. */
    public record UsageRow(String stage, String model, int calls, long promptTokens, long completionTokens,
                           long reasoningTokens, long cachedTokens, double costUsd, boolean costEstimated, int errors) {
    }

    /**
     * The full report.
     *
     * @param funnel     Stage 1 filters with the stocks left after each
     * @param synthesis  executive summary, portfolio notes, model / fallback
     * @param notes      degraded steps, data gaps, Reflexion lessons learned
     * @param candidates the shortlist, final ranking first (selected), then the others
     */
    public record ScreeningReport(RunSummary run, JsonNode funnel, JsonNode synthesis, JsonNode notes,
                                  List<CandidateView> candidates, List<UsageRow> usage) {
    }

    /** Choices of the screening form. */
    public record Option(String code, String label, String description) {
    }

    /**
     * {@code GET /api/v1/screenings/options}.
     *
     * @param data what the database holds for the default exchange
     */
    public record Options(List<Option> exchanges, List<Option> marketCapTiers, List<Option> agents, int defaultTopN,
                          int maxTopN, int shortlistMultiplier, int maxShortlist, double budgetUsd, Data data) {
    }

    public record Data(String exchange, int listings, String latestSnapshotDate, int withFundamentals,
                       UUID activeEtlJobId) {
    }

    /**
     * {@code GET /api/v1/screenings/companies}: the stocks that can be selected.
     *
     * @param maxSelected most stocks one screening of selected stocks takes ({@code max-shortlist})
     */
    public record SelectableCompanies(String exchange, int maxSelected, List<ScreeningRepository.SelectableCompany> companies) {
    }
}
