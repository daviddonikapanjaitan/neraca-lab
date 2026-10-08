package com.neracalab.backend.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Final synthesis of an analysis (one call of the synthesis model, Opus, reasoning effort low): executive summary,
 * conviction, thesis, bull and bear case, risks, what to monitor, data gaps and an optional score adjustment of at
 * most +-5 points. Without the model (budget, failure) a deterministic summary is written instead.
 */
@Component
public class AnalysisSynthesisAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalysisSynthesisAgent.class);
    static final String STAGE = "SYNTHESIS";
    static final double MAX_ADJUSTMENT = 5;
    private static final Set<String> CONVICTIONS = Set.of("HIGH", "MEDIUM", "LOW");

    /** One investor agent as the synthesis sees it. */
    public record AgentRow(String agent, String label, Double quantScore, Double aiScore, Double finalScore,
                           String verdict, String thesis, List<String> strengths, List<String> concerns,
                           List<String> reflection) {
    }

    /**
     * @param model    the model that wrote it; null for the deterministic fallback
     * @param fallback why the fallback was used (null: the model wrote it)
     */
    public record Synthesis(String executiveSummary, String conviction, String thesis, List<String> bullCase,
                            List<String> bearCase, List<String> keyRisks, List<String> monitor, List<String> dataGaps,
                            Double adjustment, String adjustmentReason, String model, String fallback) {
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final JsonReplies json;

    public AnalysisSynthesisAgent(LlmGateway llm, ScreeningProperties properties, JsonMapper mapper) {
        this.llm = llm;
        this.models = properties.llm();
        this.json = new JsonReplies(mapper);
    }

    /**
     * @param input   company, overall score, agents, key figures and the research brief
     * @param overall the overall score before the adjustment (null: no agent produced a score)
     */
    public Synthesis synthesize(UsageMeter meter, String ticker, Map<String, Object> input, List<AgentRow> agents,
                                ResearchBrief brief, Double overall, boolean allowModel) {
        if (!allowModel) {
            return fallback(ticker, agents, brief, overall, "The cost budget did not allow the synthesis model");
        }
        try {
            LlmGateway.Reply reply = llm.synthesis(meter, new Purpose(STAGE, "SYNTHESIS", ticker), List.of(
                    new SystemMessage(AnalysisPrompts.SYNTHESIS),
                    new UserMessage("ANALYSIS RESULT\n" + json.write(input))));
            return normalize(json.parse(reply.text(), Synthesis.class), overall, models.synthesisModel());
        } catch (LlmException | InvalidReplyException e) {
            log.warn("analysis synthesis of {} failed, deterministic summary used: {}", ticker, e.getMessage());
            return fallback(ticker, agents, brief, overall, "Synthesis model failed: " + e.getMessage());
        }
    }

    /** Bounded lists and lengths, a known conviction, an adjustment within +-5 (none without an overall score). */
    static Synthesis normalize(Synthesis s, Double overall, String model) {
        String conviction = s.conviction() == null ? null : s.conviction().trim().toUpperCase(Locale.ROOT);
        double adjustment = overall == null || s.adjustment() == null || !Double.isFinite(s.adjustment()) ? 0
                : Math.max(-MAX_ADJUSTMENT, Math.min(MAX_ADJUSTMENT, Math.round(s.adjustment())));
        // Set.of(...).contains(null) throws: a missing conviction is filled in from the score
        String known = conviction != null && CONVICTIONS.contains(conviction) ? conviction : conviction(overall);
        return new Synthesis(Texts.clip(s.executiveSummary(), 2400), known, Texts.clipOrNull(s.thesis(), 500),
                Texts.clip(s.bullCase(), 4, 200), Texts.clip(s.bearCase(), 4, 200), Texts.clip(s.keyRisks(), 4, 160),
                Texts.clip(s.monitor(), 4, 160), Texts.clip(s.dataGaps(), 3, 160), adjustment,
                adjustment == 0 ? null : Texts.clipOrNull(s.adjustmentReason(), 200), model, null);
    }

    /** HIGH from 70, MEDIUM from 55, otherwise LOW (null without a score). */
    static String conviction(Double overall) {
        if (overall == null) {
            return null;
        }
        if (overall >= 70) {
            return "HIGH";
        }
        return overall >= 55 ? "MEDIUM" : "LOW";
    }

    /** Deterministic summary: the scores, where the agents agree and disagree, the brief's risks and catalysts. */
    static Synthesis fallback(String ticker, List<AgentRow> agents, ResearchBrief brief, Double overall, String reason) {
        List<AgentRow> scored = agents.stream().filter(a -> a.finalScore() != null)
                .sorted(Comparator.comparingDouble(AgentRow::finalScore).reversed()).toList();
        String summary;
        if (overall == null || scored.isEmpty()) {
            summary = ticker + ": no agent produced a score, so there is no overall view.";
        } else {
            summary = ticker + ": overall score " + Math.round(overall) + "/100. Agents: " + scored.stream()
                    .map(a -> a.label() + " " + Math.round(a.finalScore()) + (a.verdict() == null ? "" : " (" + a.verdict() + ")"))
                    .collect(Collectors.joining(", ")) + ". Most favourable: " + scored.getFirst().label()
                    + "; least favourable: " + scored.getLast().label() + ".";
        }
        if (brief != null && brief.business() != null) {
            summary += " " + brief.business();
        }
        summary += " This summary was written without the synthesis model.";
        List<String> bull = new ArrayList<>();
        List<String> bear = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentRow a : scored) {
            if (!a.strengths().isEmpty() && seen.add(a.strengths().getFirst())) {
                bull.add(a.label() + ": " + a.strengths().getFirst());
            }
        }
        for (AgentRow a : scored.reversed()) {
            if (!a.concerns().isEmpty() && seen.add(a.concerns().getFirst())) {
                bear.add(a.label() + ": " + a.concerns().getFirst());
            }
        }
        List<String> risks = brief == null ? List.of() : brief.risks();
        List<String> monitor = brief == null ? List.of() : brief.catalysts();
        return new Synthesis(summary, conviction(overall), null, Texts.clip(bull, 4, 200), Texts.clip(bear, 4, 200),
                Texts.clip(risks, 4, 160), Texts.clip(monitor, 4, 160), List.of(), 0d, null, null, reason);
    }
}
