package com.neracalab.backend.macro.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI macro analysis of a country ({@code neracalab.macro-analysis.*}). The models, their options and prices and the
 * model concurrency are the screening's ({@code neracalab.screening.llm}).
 *
 * @param budgetUsd           hard cost cap of one analysis; optional calls are skipped instead of exceeding it
 * @param synthesisReserveUsd part of the budget kept for the synthesis while the other agents run
 * @param researchIterations  most model turns of the research agent (ReAct loop guard)
 * @param maxToolCalls        most tool calls of the research agent (history lookups and correlations)
 * @param historyMonths       most months a history lookup returns
 * @param agentMaxTokens      output limit of one macro agent answer or review
 * @param priorWeight         weight of the agent's rules preset in its score (the rest is its AI judgement), as the
 *                            screening blends the quantitative scorecard
 * @param rulesWeight         weight of the Macro page's rules score in the overall score (the rest: the agents' mean)
 */
@ConfigurationProperties("neracalab.macro-analysis")
public record MacroAnalysisProperties(
        @DefaultValue("0.20") double budgetUsd,
        @DefaultValue("0.10") double synthesisReserveUsd,
        @DefaultValue("4") int researchIterations,
        @DefaultValue("8") int maxToolCalls,
        @DefaultValue("36") int historyMonths,
        @DefaultValue("1000") int agentMaxTokens,
        @DefaultValue("0.6") double priorWeight,
        @DefaultValue("0.2") double rulesWeight) {

    public MacroAnalysisProperties {
        if (budgetUsd <= 0 || synthesisReserveUsd < 0 || synthesisReserveUsd >= budgetUsd) {
            throw new IllegalArgumentException("neracalab.macro-analysis: need 0 <= synthesis-reserve-usd < budget-usd");
        }
        if (researchIterations < 1 || maxToolCalls < 0 || historyMonths < 6 || agentMaxTokens < 200) {
            throw new IllegalArgumentException("neracalab.macro-analysis: research-iterations >= 1, max-tool-calls >= 0, "
                    + "history-months >= 6, agent-max-tokens >= 200");
        }
        if (priorWeight < 0 || priorWeight > 1 || rulesWeight < 0 || rulesWeight > 1) {
            throw new IllegalArgumentException("neracalab.macro-analysis: prior-weight and rules-weight in 0 .. 1");
        }
    }
}
