package com.neracalab.backend.technical.screening;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningViews.Option;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;

import tools.jackson.databind.JsonNode;

/** Request and response bodies of the technical screening API ({@code /api/v1/technical-screenings}). */
public final class TechnicalScreeningViews {

    private TechnicalScreeningViews() {
    }

    /**
     * {@code POST /api/v1/technical-screenings}: either a market-cap tier or selected stocks.
     *
     * @param marketCapTier LARGE, MID (or MEDIUM) or SMALL; omitted when {@code tickers} are given
     * @param tickers       stocks of the companies table (Screening > Technicals (Selected)); omitted for a tier
     * @param topN          stocks in the final ranking (1..max-top-n, at most the number of selected stocks)
     * @param agents        technical agents: GILL, MURPHY, TUDOR_JONES, DRUCKENMILLER (at least one)
     */
    public record TechnicalScreeningRequest(String exchange, String marketCapTier, List<String> tickers, Integer topN,
                                            List<String> agents) {
    }

    /**
     * One run as listed (also the head of the report).
     *
     * @param status        status of the job (QUEUED, RUNNING, SUCCEEDED, INCOMPLETE, FAILED)
     * @param stage         current step, or a one-line summary of a finished run
     * @param message       why the run failed or is incomplete
     * @param marketCapTier the tier screened; null for selected stocks
     * @param tickers       the selected stocks; null for a tier
     * @param asOfDate      the latest trading day of the bars the run read
     */
    public record RunSummary(UUID id, IngestionJobStatus status, String stage, String message, String exchange,
                             MarketCapTier marketCapTier, List<String> tickers, int topN, List<TechnicalAgent> agents,
                             String asOfDate, Integer universeCount, Integer eligibleCount, Integer shortlistCount,
                             Integer selectedCount, double budgetUsd, double costUsd, long promptTokens,
                             long completionTokens, long reasoningTokens, long cachedTokens, int modelCalls,
                             Instant requestedAt, Instant startedAt, Instant finishedAt, IngestionJob.CreatedBy createdBy) {

        public boolean finished() {
            return status != null && !status.active();
        }
    }

    /**
     * One technical agent's view of one candidate.
     *
     * @param priorScore its preset's rules summary as 0-100
     * @param plan       its trade plan (its own valid levels, else its preset's)
     * @param status     ASSESSED, REVISED (after the reflection) or PRIOR_ONLY (no model answer)
     */
    public record AgentScoreView(TechnicalAgent agent, String label, Double priorScore, Double llmScore, Double finalScore,
                                 String stance, String thesis, JsonNode supports, JsonNode risks, JsonNode plan,
                                 JsonNode reflection, String status) {
    }

    /**
     * A candidate of the shortlist; {@code selected} = in the final top N.
     *
     * @param inCompanies whether it is in the companies table (its chart opens on Companies > Technicals)
     * @param metrics     key technicals
     * @param rules       the four preset summaries of Companies > Technicals
     * @param action      BUY, ACCUMULATE, WAIT or AVOID
     * @param plan        the final trade plan (buy, sell, stop loss, reward / risk, who set it)
     */
    public record CandidateView(long id, String ticker, String companyName, String sector, Double close, String asOfDate,
                                Double marketCap, boolean inCompanies, JsonNode metrics, JsonNode rules,
                                Double priorOverall, Integer priorRank, Double overallScore, Double synthesisAdjustment,
                                Integer finalRank, boolean selected, String action, String conviction, JsonNode plan,
                                String thesis, JsonNode redFlags, List<AgentScoreView> agents) {
    }

    /**
     * The full report.
     *
     * @param funnel     Stage 1 filters with the stocks left after each
     * @param market     IHSG against its 200-day average and the breadth, as the agents saw them
     * @param synthesis  executive summary, market notes, model / fallback
     * @param notes      degraded steps, data gaps, Reflexion lessons
     * @param candidates the shortlist, final ranking first (selected), then the others
     */
    public record TechnicalScreeningReport(RunSummary run, JsonNode funnel, JsonNode market, JsonNode synthesis,
                                           JsonNode notes, List<CandidateView> candidates, List<UsageRow> usage) {
    }

    /**
     * {@code GET /api/v1/technical-screenings/options}.
     *
     * @param data what the database holds for the default exchange
     */
    public record Options(List<Option> exchanges, List<Option> marketCapTiers, List<Option> agents, int defaultTopN,
                          int maxTopN, int shortlistMultiplier, int maxShortlist, double budgetUsd, String agentModel,
                          String synthesisModel, int minBars, Data data) {
    }

    /**
     * @param listings          active listings of the screening universe
     * @param withBars          of them with stored daily bars (listing_price_daily)
     * @param latestBarDate     the newest stored bar
     * @param historyYears      how far back the first load of a listing reaches
     * @param marketDataDate    the newest market data of the universe (market cap, liquidity)
     * @param activePricesJobId the queued / running price history run of the exchange, if any
     */
    public record Data(String exchange, int listings, int withBars, String latestBarDate, int historyYears,
                       String marketDataDate, UUID activePricesJobId) {
    }

    /**
     * A company of the companies table with its stored daily prices (the stock picker of Technicals (Selected)).
     *
     * @param tradingDays stored daily bars (null: none)
     * @param marketCap   the latest market cap of its listing in the screening universe, if any
     */
    public record SelectableCompany(String ticker, String companyName, String sector, Integer tradingDays,
                                    String firstDate, String lastDate, Double marketCap) {
    }

    /**
     * {@code GET /api/v1/technical-screenings/companies}.
     *
     * @param maxSelected most stocks one run takes ({@code max-shortlist})
     * @param minBars     fewest daily bars a stock needs
     */
    public record SelectableCompanies(String exchange, int maxSelected, int minBars, List<SelectableCompany> companies) {
    }
}
