package com.neracalab.backend.portfolio.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Portfolio &gt; Analysis ({@code neracalab.portfolio-analysis.*}). The models, their prices and call limits are those of
 * {@code neracalab.screening.llm}: DeepSeek for the fundamental and technical agents and their critic, Opus for the
 * synthesis.
 *
 * @param budgetUsd               hard cost cap of one run; optional calls are skipped instead of exceeding it
 * @param synthesisReserveUsd     part of the budget kept free for the synthesis while the agents run
 * @param defaultMaxStocks        the form's default of the most stocks to hold
 * @param maxStocksLimit          the largest "most stocks to hold" a user can ask for
 * @param maxSelected             the most tickers a SELECTED run takes
 * @param minFundamentalScore     step 1: the lowest Fundamental (Score) that passes (0 - 100)
 * @param fundamentalMultiplier   step 1 keeps the best {@code max stocks x this} by Fundamental (Score)
 * @param maxFundamentalShortlist step 1 keeps at most this many
 * @param technicalMultiplier     step 2 keeps, of step 1's stocks, the best {@code max stocks x this} by Technical
 *                                (Score)
 * @param maxTechnicalShortlist   step 2 keeps at most this many (the AI agents read these and the holdings)
 * @param llmWeight               an agent's final score = (1 - this) x the rules score + this x the AI's score
 * @param maxPositionWeight       no stock above this share of the capital (0.33)
 * @param maxSectorWeight         no sector above this share of the capital (0.33)
 * @param kellyFraction           the share of the Kelly fraction used (0.5: half-Kelly)
 * @param minPositionWeight       a new position below this share of the capital is not opened
 * @param lotSize                 shares per lot (IDX: 100)
 * @param agentMaxTokens          output limit of one agent answer
 * @param synthesisMaxTokens      output limit of the synthesis (thinking included)
 */
@ConfigurationProperties("neracalab.portfolio-analysis")
public record PortfolioAnalysisProperties(
        @DefaultValue("0.75") double budgetUsd,
        @DefaultValue("0.35") double synthesisReserveUsd,
        @DefaultValue("25") int defaultMaxStocks,
        @DefaultValue("50") int maxStocksLimit,
        @DefaultValue("100") int maxSelected,
        @DefaultValue("50") double minFundamentalScore,
        @DefaultValue("3") int fundamentalMultiplier,
        @DefaultValue("120") int maxFundamentalShortlist,
        @DefaultValue("2") int technicalMultiplier,
        @DefaultValue("60") int maxTechnicalShortlist,
        @DefaultValue("0.4") double llmWeight,
        @DefaultValue("0.33") double maxPositionWeight,
        @DefaultValue("0.33") double maxSectorWeight,
        @DefaultValue("0.5") double kellyFraction,
        @DefaultValue("0.01") double minPositionWeight,
        @DefaultValue("100") int lotSize,
        @DefaultValue("700") int agentMaxTokens,
        @DefaultValue("20000") int synthesisMaxTokens) {

    public PortfolioAnalysisProperties {
        if (budgetUsd <= 0 || synthesisReserveUsd < 0 || synthesisReserveUsd >= budgetUsd) {
            throw new IllegalArgumentException("portfolio-analysis: budget-usd > synthesis-reserve-usd >= 0");
        }
        if (maxStocksLimit < 1 || maxStocksLimit > 100 || defaultMaxStocks < 1 || defaultMaxStocks > maxStocksLimit) {
            throw new IllegalArgumentException("portfolio-analysis: 1 <= default-max-stocks <= max-stocks-limit <= 100");
        }
        if (maxPositionWeight <= 0 || maxPositionWeight > 1 || maxSectorWeight <= 0 || maxSectorWeight > 1) {
            throw new IllegalArgumentException("portfolio-analysis: weights between 0 and 1");
        }
        if (kellyFraction <= 0 || kellyFraction > 1 || lotSize < 1 || llmWeight < 0 || llmWeight > 1) {
            throw new IllegalArgumentException("portfolio-analysis: kelly-fraction in (0, 1], lot-size >= 1, "
                    + "llm-weight in [0, 1]");
        }
    }
}
