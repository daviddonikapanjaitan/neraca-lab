package com.neracalab.backend.macro.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.neracalab.backend.analysis.Texts;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Final synthesis of a macro analysis (one call of the synthesis model, Opus, reasoning effort low): executive summary,
 * conviction, thesis, bull and bear case, risks, what to monitor, sector tilts, data gaps and an optional score
 * adjustment of at most +-5 points. Without the model (budget, failure) a deterministic summary is written instead.
 */
@Component
public class MacroSynthesisAgent {

    private static final Logger log = LoggerFactory.getLogger(MacroSynthesisAgent.class);
    static final String STAGE = "SYNTHESIS";
    static final double MAX_ADJUSTMENT = 5;
    private static final Set<String> CONVICTIONS = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> TILTS = Set.of("OVERWEIGHT", "NEUTRAL", "UNDERWEIGHT");

    /** One macro agent as the synthesis sees it. */
    public record AgentRow(String agent, String label, Double priorScore, Double aiScore, Double finalScore, String stance,
                           String thesis, List<String> supports, List<String> risks, List<String> reflection) {
    }

    /** A sector of the country's stock market the macro favours or not. */
    public record SectorTilt(String sector, String tilt, String why) {
    }

    /**
     * @param model    the model that wrote it; null for the deterministic fallback
     * @param fallback why the fallback was used (null: the model wrote it)
     */
    public record MacroSynthesis(String executiveSummary, String conviction, String thesis, List<String> bullCase,
                                 List<String> bearCase, List<String> keyRisks, List<String> monitor,
                                 List<SectorTilt> sectorTilts, List<String> dataGaps, Double adjustment,
                                 String adjustmentReason, String model, String fallback) {
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final JsonReplies json;

    public MacroSynthesisAgent(LlmGateway llm, ScreeningProperties screening, JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.json = new JsonReplies(mapper);
    }

    /**
     * @param input   country, index, the overall score, the agents, the regimes, the rules' conclusion and the brief
     * @param overall the overall score before the adjustment (null: no score)
     */
    public MacroSynthesis synthesize(UsageMeter meter, String subject, Map<String, Object> input, List<AgentRow> agents,
                                     MacroBrief brief, Double overall, boolean allowModel) {
        if (!allowModel) {
            return fallback(subject, agents, brief, overall, "The cost budget did not allow the synthesis model");
        }
        try {
            LlmGateway.Reply reply = llm.synthesis(meter, new Purpose(STAGE, "SYNTHESIS", subject), List.of(
                    new SystemMessage(MacroAnalysisPrompts.SYNTHESIS),
                    new UserMessage("MACRO ANALYSIS RESULT\n" + json.write(input))));
            return normalize(json.parse(reply.text(), MacroSynthesis.class), overall, models.synthesisModel());
        } catch (LlmException | InvalidReplyException e) {
            log.warn("macro synthesis of {} failed, deterministic summary used: {}", subject, e.getMessage());
            return fallback(subject, agents, brief, overall, "Synthesis model failed: " + e.getMessage());
        }
    }

    /** Bounded lists and lengths, a known conviction and tilts, an adjustment within +-5 (none without a score). */
    static MacroSynthesis normalize(MacroSynthesis s, Double overall, String model) {
        String conviction = s.conviction() == null ? null : s.conviction().trim().toUpperCase(Locale.ROOT);
        double adjustment = overall == null || s.adjustment() == null || !Double.isFinite(s.adjustment()) ? 0
                : Math.max(-MAX_ADJUSTMENT, Math.min(MAX_ADJUSTMENT, Math.round(s.adjustment())));
        String known = conviction != null && CONVICTIONS.contains(conviction) ? conviction : conviction(overall);
        List<SectorTilt> tilts = s.sectorTilts() == null ? List.of() : s.sectorTilts().stream().filter(Objects::nonNull)
                .filter(t -> t.sector() != null && !t.sector().isBlank())
                .map(t -> {
                    String tilt = t.tilt() == null ? "NEUTRAL" : t.tilt().trim().toUpperCase(Locale.ROOT);
                    return new SectorTilt(Texts.clip(t.sector(), 60), TILTS.contains(tilt) ? tilt : "NEUTRAL",
                            Texts.clipOrNull(t.why(), 200));
                })
                .limit(4).toList();
        return new MacroSynthesis(Texts.clip(s.executiveSummary(), 2400), known, Texts.clipOrNull(s.thesis(), 500),
                Texts.clip(s.bullCase(), 4, 200), Texts.clip(s.bearCase(), 4, 200), Texts.clip(s.keyRisks(), 4, 160),
                Texts.clip(s.monitor(), 4, 160), tilts, Texts.clip(s.dataGaps(), 3, 160), adjustment,
                adjustment == 0 ? null : Texts.clipOrNull(s.adjustmentReason(), 200), model, null);
    }

    /** HIGH from 70 or up to 30 (a clear call either way), MEDIUM from 60 or up to 40, otherwise LOW. */
    static String conviction(Double overall) {
        if (overall == null) {
            return null;
        }
        double distance = Math.abs(overall - 50);
        if (distance >= 20) {
            return "HIGH";
        }
        return distance >= 10 ? "MEDIUM" : "LOW";
    }

    /** Deterministic summary: the scores, where the agents agree and disagree, the brief's risks and catalysts. */
    static MacroSynthesis fallback(String subject, List<AgentRow> agents, MacroBrief brief, Double overall, String reason) {
        List<AgentRow> scored = agents.stream().filter(a -> a.finalScore() != null)
                .sorted(Comparator.comparingDouble(AgentRow::finalScore).reversed()).toList();
        String summary;
        if (overall == null || scored.isEmpty()) {
            summary = subject + ": no agent produced a score, so there is no overall view.";
        } else {
            summary = subject + ": investability " + Math.round(overall) + "/100. Agents: " + scored.stream()
                    .map(a -> a.label() + " " + Math.round(a.finalScore()) + (a.stance() == null ? "" : " (" + a.stance() + ")"))
                    .collect(Collectors.joining(", ")) + ". Most favourable: " + scored.getFirst().label()
                    + "; least favourable: " + scored.getLast().label() + ".";
        }
        if (brief != null && brief.regime() != null) {
            summary += " " + brief.regime();
        }
        summary += " This summary was written without the synthesis model.";
        List<String> bull = new ArrayList<>();
        List<String> bear = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentRow a : scored) {
            if (!a.supports().isEmpty() && seen.add(a.supports().getFirst())) {
                bull.add(a.label() + ": " + a.supports().getFirst());
            }
        }
        for (AgentRow a : scored.reversed()) {
            if (!a.risks().isEmpty() && seen.add(a.risks().getFirst())) {
                bear.add(a.label() + ": " + a.risks().getFirst());
            }
        }
        List<String> risks = brief == null ? List.of() : brief.risks();
        List<String> monitor = brief == null ? List.of() : brief.catalysts();
        return new MacroSynthesis(summary, conviction(overall), null, Texts.clip(bull, 4, 200), Texts.clip(bear, 4, 200),
                Texts.clip(risks, 4, 160), Texts.clip(monitor, 4, 160), List.of(), List.of(), 0d, null, null, reason);
    }
}
