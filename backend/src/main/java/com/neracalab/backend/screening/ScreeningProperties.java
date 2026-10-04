package com.neracalab.backend.screening;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI stock screening ({@code neracalab.screening.*}).
 *
 * @param budgetUsd            hard cost cap of one screening run (all model calls); the run degrades
 *                             (quantitative scores only, deterministic summary) instead of exceeding it
 * @param synthesisReserveUsd  part of the budget kept for the final synthesis while Stage 2 runs
 * @param shortlistMultiplier  Stage 1 shortlist = top N x this (3: 75 stocks for a top 25)
 * @param maxShortlist         upper bound of the shortlist, whatever the top N
 * @param maxTopN              largest top N a user can request
 * @param llmWeight            weight of the model's score in an agent's final score (rest: quantitative score)
 * @param riskWeight           weight of the Risk agent in the overall score when it is selected (rest:
 *                             the average of the selected investor agents)
 * @param largeCapMin          market cap (listing currency) from which a stock is large cap
 * @param midCapMin            market cap from which a stock is mid cap (below: small cap)
 * @param minPrice             Stage 1: lowest share price (IDX: stocks below Rp 50 trade on the
 *                             special-monitoring / full-call-auction board)
 * @param minAvgDailyValueLarge Stage 1: lowest average traded value per day of a large cap
 * @param minAvgDailyValueMid  ... of a mid cap
 * @param minAvgDailyValueSmall ... of a small cap
 * @param maxTradeAge          Stage 1: a stock whose last trade is older is treated as suspended
 * @param requirePositiveEarnings Stage 1: only stocks with positive trailing earnings
 * @param excludedTickers      Stage 1: tickers never screened (e.g. known watchlist-board stocks)
 * @param minFundamentalsCoverage Stage 1: share of the scoring metrics that must be known
 */
@ConfigurationProperties("neracalab.screening")
public record ScreeningProperties(
        @DefaultValue("0.45") double budgetUsd,
        @DefaultValue("0.15") double synthesisReserveUsd,
        @DefaultValue("3") int shortlistMultiplier,
        @DefaultValue("100") int maxShortlist,
        @DefaultValue("50") int maxTopN,
        @DefaultValue("0.4") double llmWeight,
        @DefaultValue("0.2") double riskWeight,
        @DefaultValue("10000000000000") double largeCapMin,
        @DefaultValue("1000000000000") double midCapMin,
        @DefaultValue("50") double minPrice,
        @DefaultValue("5000000000") double minAvgDailyValueLarge,
        @DefaultValue("1000000000") double minAvgDailyValueMid,
        @DefaultValue("200000000") double minAvgDailyValueSmall,
        @DefaultValue("10d") Duration maxTradeAge,
        @DefaultValue("true") boolean requirePositiveEarnings,
        @DefaultValue({}) List<String> excludedTickers,
        @DefaultValue("0.5") double minFundamentalsCoverage,
        @DefaultValue Llm llm,
        @DefaultValue News news,
        @DefaultValue Etl etl) {

    public ScreeningProperties {
        if (budgetUsd <= 0 || synthesisReserveUsd < 0 || synthesisReserveUsd >= budgetUsd) {
            throw new IllegalArgumentException("neracalab.screening: need 0 <= synthesis-reserve-usd < budget-usd");
        }
        if (shortlistMultiplier < 1 || maxShortlist < 1 || maxTopN < 1 || maxTopN > 100) {
            throw new IllegalArgumentException("neracalab.screening: shortlist-multiplier, max-shortlist >= 1, 1 <= max-top-n <= 100");
        }
        if (llmWeight < 0 || llmWeight > 1 || riskWeight < 0 || riskWeight > 1) {
            throw new IllegalArgumentException("neracalab.screening: llm-weight and risk-weight must be between 0 and 1");
        }
        if (midCapMin <= 0 || largeCapMin <= midCapMin) {
            throw new IllegalArgumentException("neracalab.screening: need 0 < mid-cap-min < large-cap-min");
        }
    }

    /** Minimum average traded value per day of a tier. */
    public double minAvgDailyValue(MarketCapTier tier) {
        return switch (tier) {
            case LARGE -> minAvgDailyValueLarge;
            case MID -> minAvgDailyValueMid;
            case SMALL -> minAvgDailyValueSmall;
        };
    }

    /**
     * Models (OpenRouter ids) and call limits.
     *
     * @param researchModel  research agent (ReAct tool calling over the news tools)
     * @param agentModel     the six investor agents and the reflection critic
     * @param synthesisModel final synthesis of the top N
     * @param synthesisEffort reasoning effort of the synthesis model (low keeps it cheap)
     * @param providerSort   OpenRouter provider routing of the agent / research calls ({@code price}: the
     *                       cheapest provider; empty: OpenRouter's default)
     * @param concurrency    model calls in flight at once
     * @param researchIterations most model turns of the research agent per stock (ReAct loop guard)
     * @param agentMaxTokens output limit of one investor assessment
     * @param synthesisMaxTokens output limit of the synthesis (thinking included)
     * @param prices         USD per million tokens per model, used only when OpenRouter reports no cost
     *                       for a call (default: the OpenRouter list prices of the default models)
     */
    public record Llm(@DefaultValue("deepseek/deepseek-v4-flash-0731") String researchModel,
                      @DefaultValue("deepseek/deepseek-v4-flash-0731") String agentModel,
                      @DefaultValue("anthropic/claude-opus-5.5") String synthesisModel,
                      @DefaultValue("low") String synthesisEffort,
                      @DefaultValue("price") String providerSort,
                      @DefaultValue("8") int concurrency,
                      @DefaultValue("3") int researchIterations,
                      @DefaultValue("450") int agentMaxTokens,
                      @DefaultValue("12000") int synthesisMaxTokens,
                      List<ModelPrice> prices) {

        public Llm {
            if (prices == null || prices.isEmpty()) {
                prices = List.of(new ModelPrice("deepseek/deepseek-v4-flash-0731", 0.06, 1.28),
                        new ModelPrice("anthropic/claude-opus-5.5", 4, 20));
            }
            if (concurrency < 1 || researchIterations < 1) {
                throw new IllegalArgumentException("neracalab.screening.llm: concurrency and research-iterations must be >= 1");
            }
        }

        /** Price of a model; an unknown model is priced like Opus (conservative). */
        public ModelPrice price(String model) {
            return prices.stream().filter(p -> p.model().equals(model)).findFirst()
                    .orElse(new ModelPrice(model, 4, 20));
        }
    }

    /**
     * @param inputPerMillion  USD per million prompt tokens
     * @param outputPerMillion USD per million completion tokens (reasoning included)
     */
    public record ModelPrice(String model, double inputPerMillion, double outputPerMillion) {

        public double cost(long promptTokens, long completionTokens) {
            return (promptTokens * inputPerMillion + completionTokens * outputPerMillion) / 1_000_000d;
        }
    }

    /**
     * News tools of the research agent.
     *
     * @param tavilyApiKey     Tavily search API key (empty: the Tavily tool is unavailable)
     * @param tavilyMaxResults results per Tavily search
     * @param cacheTtl         a news source is asked about a ticker again only after this time
     * @param maxAge           articles older than this are ignored
     * @param headlinesPerStock most headlines given to the research agent per stock
     * @param articleChars     most characters of an article body passed to the model
     * @param minDelay         minimum pause between two requests to the same news site
     * @param maxDelay         maximum pause (random between min and max)
     */
    public record News(@DefaultValue("") String tavilyApiKey,
                       @DefaultValue("https://api.tavily.com") String tavilyBaseUrl,
                       @DefaultValue("5") int tavilyMaxResults,
                       @DefaultValue("12h") Duration cacheTtl,
                       @DefaultValue("120d") Duration maxAge,
                       @DefaultValue("8") int headlinesPerStock,
                       @DefaultValue("1500") int articleChars,
                       @DefaultValue("1s") Duration minDelay,
                       @DefaultValue("2s") Duration maxDelay) {

        public boolean tavilyEnabled() {
            return tavilyApiKey != null && !tavilyApiKey.isBlank();
        }
    }

    /**
     * Daily ETL of the screening data.
     *
     * @param fundamentalsMaxAge fundamentals older than this are fetched again (the market data is
     *                           refreshed on every run)
     * @param schedule           daily run
     */
    public record Etl(@DefaultValue("7d") Duration fundamentalsMaxAge,
                      @DefaultValue Schedule schedule) {
    }

    /**
     * @param cron Spring cron in {@code zone}; default 18:00 Monday-Friday, after the IDX close
     */
    public record Schedule(@DefaultValue("true") boolean enabled,
                           @DefaultValue("0 0 18 * * MON-FRI") String cron,
                           @DefaultValue("Asia/Jakarta") String zone,
                           @DefaultValue("IDX") String exchange) {
    }
}
