package com.neracalab.backend.technical.screening;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI technical screening ({@code neracalab.technical-screening.*}). Its models, their prices and call limits are those
 * of {@code neracalab.screening.llm} (DeepSeek for the agents and the reflection, Opus for the synthesis); the market-cap
 * tiers and the price, trading and liquidity minimums those of {@code neracalab.screening}.
 *
 * @param budgetUsd           hard cost cap of one run (all model calls); the run degrades (rules scores only,
 *                            deterministic summary) instead of exceeding it
 * @param synthesisReserveUsd part of the budget kept for the synthesis while the agents run
 * @param shortlistMultiplier Stage 1 shortlist = top N x this
 * @param maxShortlist        upper bound of the shortlist, whatever the top N; also the most stocks a selection takes
 * @param maxTopN             largest top N a user can request
 * @param llmWeight           weight of the model's score in an agent's final score (rest: its rules summary)
 * @param minBars             Stage 1: fewer stored daily bars = too little history for the indicators
 * @param maxBarAge           Stage 1: a last bar older than this before the as-of day is stale (a tier screening drops
 *                            it; a selected stock is kept and flagged)
 * @param agentMaxTokens      output limit of one agent answer
 * @param synthesisMaxTokens  output limit of the synthesis (thinking included)
 */
@ConfigurationProperties("neracalab.technical-screening")
public record TechnicalScreeningProperties(
        @DefaultValue("0.50") double budgetUsd,
        @DefaultValue("0.20") double synthesisReserveUsd,
        @DefaultValue("3") int shortlistMultiplier,
        @DefaultValue("100") int maxShortlist,
        @DefaultValue("50") int maxTopN,
        @DefaultValue("0.4") double llmWeight,
        @DefaultValue("120") int minBars,
        @DefaultValue("7d") Duration maxBarAge,
        @DefaultValue("700") int agentMaxTokens,
        @DefaultValue("16000") int synthesisMaxTokens,
        @DefaultValue PriceHistory priceHistory) {

    public TechnicalScreeningProperties {
        if (budgetUsd <= 0 || synthesisReserveUsd < 0 || synthesisReserveUsd >= budgetUsd) {
            throw new IllegalArgumentException("neracalab.technical-screening: need 0 <= synthesis-reserve-usd < budget-usd");
        }
        if (shortlistMultiplier < 1 || maxShortlist < 1 || maxTopN < 1 || maxTopN > 100) {
            throw new IllegalArgumentException(
                    "neracalab.technical-screening: shortlist-multiplier, max-shortlist >= 1, 1 <= max-top-n <= 100");
        }
        if (llmWeight < 0 || llmWeight > 1) {
            throw new IllegalArgumentException("neracalab.technical-screening: llm-weight must be between 0 and 1");
        }
        if (minBars < 20 || maxBarAge == null || maxBarAge.isNegative() || agentMaxTokens < 200 || synthesisMaxTokens < 1000) {
            throw new IllegalArgumentException("neracalab.technical-screening: min-bars >= 20, max-bar-age >= 0, "
                    + "agent-max-tokens >= 200, synthesis-max-tokens >= 1000");
        }
    }

    /**
     * The daily bars of the screening universe ({@code listing_price_daily}).
     *
     * @param years            how far back the first load of a listing reaches
     * @param refreshBeforeRun whether a tier screening first loads the bars of its listings that are behind
     */
    public record PriceHistory(@DefaultValue("5") int years, @DefaultValue("true") boolean refreshBeforeRun) {

        public PriceHistory {
            if (years < 1 || years > 40) {
                throw new IllegalArgumentException("neracalab.technical-screening.price-history.years must be 1 - 40");
            }
        }
    }
}
