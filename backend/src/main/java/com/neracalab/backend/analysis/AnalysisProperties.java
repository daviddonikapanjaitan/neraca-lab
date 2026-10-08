package com.neracalab.backend.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI analysis of one stock ({@code neracalab.analysis.*}). The models, their options and prices, the score
 * blending and the investor personas are those of the screening ({@code neracalab.screening}).
 *
 * @param budgetUsd                 hard cost cap of one analysis (all model and embedding calls); optional calls
 *                                  are skipped instead of exceeding it
 * @param synthesisReserveUsd       part of the budget kept for the synthesis while the other agents run
 * @param researchIterations        most model turns of the research agent (ReAct loop guard)
 * @param maxSearches               most document / news searches of the research agent
 * @param maxStatementLookups       most full-statement lookups of the research agent
 * @param searchResults             chunks returned per search
 * @param excerptChars              longest chunk excerpt returned to the model
 * @param annualPeriods             full fiscal years in the fact sheet (plus the latest interim period and its
 *                                  prior-year comparative)
 * @param embeddingPricePerMillion  USD per million tokens of the embedding model (search queries; the embedding
 *                                  API reports no cost, so it is estimated)
 * @param agentMaxTokens            output limit of one investor agent answer or review; higher than the
 *                                  screening's ({@code neracalab.screening.llm.agent-max-tokens}): with the
 *                                  statements, the research brief and its references the answers are longer
 */
@ConfigurationProperties("neracalab.analysis")
public record AnalysisProperties(
        @DefaultValue("0.20") double budgetUsd,
        @DefaultValue("0.10") double synthesisReserveUsd,
        @DefaultValue("4") int researchIterations,
        @DefaultValue("6") int maxSearches,
        @DefaultValue("3") int maxStatementLookups,
        @DefaultValue("4") int searchResults,
        @DefaultValue("800") int excerptChars,
        @DefaultValue("4") int annualPeriods,
        @DefaultValue("0.02") double embeddingPricePerMillion,
        @DefaultValue("1000") int agentMaxTokens) {

    public AnalysisProperties {
        if (budgetUsd <= 0 || synthesisReserveUsd < 0 || synthesisReserveUsd >= budgetUsd) {
            throw new IllegalArgumentException("neracalab.analysis: need 0 <= synthesis-reserve-usd < budget-usd");
        }
        if (researchIterations < 1 || maxSearches < 0 || maxStatementLookups < 0 || searchResults < 1
                || excerptChars < 100 || annualPeriods < 1 || agentMaxTokens < 200) {
            throw new IllegalArgumentException("neracalab.analysis: research-iterations, search-results, annual-periods "
                    + ">= 1, max-searches and max-statement-lookups >= 0, excerpt-chars >= 100, agent-max-tokens >= 200");
        }
    }
}
