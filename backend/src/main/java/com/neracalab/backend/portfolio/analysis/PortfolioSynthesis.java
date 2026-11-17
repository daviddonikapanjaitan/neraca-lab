package com.neracalab.backend.portfolio.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.neracalab.backend.analysis.Texts;
import com.neracalab.backend.portfolio.analysis.PortfolioAllocator.Proposal;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.json.JsonMapper;

/**
 * The synthesis of Portfolio &gt; Analysis (one call of the synthesis model, Opus): an executive summary, risk notes,
 * the cash target and, for every holding and new idea it keeps, an action with a target weight. Its reply is a proposal
 * only - {@link PortfolioAllocator} applies the limits. Without the model (budget, failure, an unusable reply) the
 * deterministic {@link PortfolioAllocator#fallback} proposes instead.
 */
@Component
public class PortfolioSynthesis {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSynthesis.class);
    static final Set<String> ACTIONS = Set.of("BUY", "INCREASE", "HOLD", "DECREASE", "SELL");
    static final Set<String> CONVICTIONS = Set.of("HIGH", "MEDIUM", "LOW");

    /**
     * @param cashTarget the cash weight wanted (0 - 1); null: what is left
     * @param model      the model that wrote it; null for the fallback
     * @param fallback   why the fallback was used (null: the model wrote it)
     */
    public record Result(String executiveSummary, List<String> riskNotes, Double cashTarget,
                         Map<String, Proposal> proposals, String model, String fallback) {
    }

    /** The model's reply. */
    public record Reply(String executiveSummary, List<String> riskNotes, Double cashTargetPct, List<PositionReply> positions) {
    }

    public record PositionReply(String ticker, String action, Double targetWeightPct, String conviction, String reason) {
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final JsonReplies json;

    public PortfolioSynthesis(LlmGateway llm, ScreeningProperties screening, JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.json = new JsonReplies(mapper);
    }

    public String model() {
        return models.synthesisModel();
    }

    /**
     * @param input   the run as the model reads it (capital, limits, holdings, ideas)
     * @param known   the tickers the model may name (holdings and ideas)
     * @param held    the held tickers (a holding the reply leaves out is kept as it is)
     * @param fallback the deterministic proposal, used without the model
     */
    public Result synthesize(UsageMeter meter, Map<String, Object> input, Set<String> known, Set<String> held,
                             Map<String, Proposal> fallback, boolean allowModel, int maxTokens) {
        return synthesize(meter, input, known, held, fallback, allowModel, maxTokens, true);
    }

    /**
     * @param technical false: the run has the fundamental analysis only (its own prompt: no timing, a PASS buys)
     */
    public Result synthesize(UsageMeter meter, Map<String, Object> input, Set<String> known, Set<String> held,
                             Map<String, Proposal> fallback, boolean allowModel, int maxTokens, boolean technical) {
        if (!allowModel) {
            return deterministic(fallback, "The cost budget did not allow the synthesis model", technical);
        }
        try {
            LlmGateway.Reply reply = llm.synthesis(meter, new Purpose("SYNTHESIS", "SYNTHESIS", null), List.of(
                    new SystemMessage(technical ? PortfolioPrompts.SYNTHESIS : PortfolioPrompts.SYNTHESIS_FUNDAMENTAL),
                    new UserMessage("PORTFOLIO ANALYSIS\n" + json.write(input))), maxTokens);
            return normalize(json.parse(reply.text(), Reply.class), known, held, models.synthesisModel());
        } catch (LlmException | InvalidReplyException e) {
            log.warn("portfolio synthesis failed, deterministic proposal used: {}", e.getMessage());
            return deterministic(fallback, "Synthesis model failed: " + e.getMessage(), technical);
        }
    }

    /** Known tickers only, one entry each, a known action, weights as fractions; a held stock left out is held. */
    static Result normalize(Reply r, Set<String> known, Set<String> held, String model) {
        if (r.positions() == null || r.positions().isEmpty()) {
            throw new InvalidReplyException("the reply has no positions");
        }
        Map<String, Proposal> proposals = new LinkedHashMap<>();
        for (PositionReply p : r.positions()) {
            if (p == null || p.ticker() == null) {
                continue;
            }
            String ticker = p.ticker().trim().toUpperCase(Locale.ROOT);
            if (!known.contains(ticker) || proposals.containsKey(ticker)) {
                continue;
            }
            String action = p.action() == null ? null : p.action().trim().toUpperCase(Locale.ROOT);
            if (action == null || !ACTIONS.contains(action)) {
                continue;
            }
            double weight = "SELL".equals(action) ? 0
                    : p.targetWeightPct() == null || !Double.isFinite(p.targetWeightPct()) ? -1
                    : Math.max(0, Math.min(100, p.targetWeightPct())) / 100;
            if (weight < 0) {
                continue;
            }
            if ("BUY".equals(action) && held.contains(ticker)) {
                action = "INCREASE";
            }
            String conviction = p.conviction() == null ? null : p.conviction().trim().toUpperCase(Locale.ROOT);
            proposals.put(ticker, new Proposal(ticker, action, weight,
                    conviction != null && CONVICTIONS.contains(conviction) ? conviction : null,
                    Texts.clipOrNull(p.reason(), 300)));
        }
        Double cash = r.cashTargetPct() == null || !Double.isFinite(r.cashTargetPct()) ? null
                : Math.max(0, Math.min(100, r.cashTargetPct())) / 100;
        return new Result(Texts.clipOrNull(r.executiveSummary(), 2000), Texts.clip(r.riskNotes(), 5, 300), cash,
                proposals, model, null);
    }

    static Result deterministic(Map<String, Proposal> fallback, String reason) {
        return deterministic(fallback, reason, true);
    }

    static Result deterministic(Map<String, Proposal> fallback, String reason, boolean technical) {
        long buys = fallback.values().stream().filter(p -> "BUY".equals(p.action())).count();
        long sells = fallback.values().stream().filter(p -> "SELL".equals(p.action())).count();
        long decreases = fallback.values().stream().filter(p -> "DECREASE".equals(p.action())).count();
        long increases = fallback.values().stream().filter(p -> "INCREASE".equals(p.action())).count();
        String summary = technical
                ? "Recommendations by fixed rules (no synthesis model): " + buys + " new stock(s) bought at their "
                + "half-Kelly weight in the order of the technical step, " + increases + " holding(s) increased, "
                + decreases + " decreased and " + sells + " sold (a fundamental FAIL and a technical AVOID sells, either "
                + "one halves the position); the rest is kept, the remainder stays in cash."
                : "Recommendations by fixed rules (no synthesis model, fundamental analysis only): " + buys + " new "
                + "stock(s) with a fundamental PASS bought at their half-Kelly weight, best fundamental score first, "
                + increases + " holding(s) increased and " + sells + " sold (a fundamental FAIL sells); the rest is "
                + "kept, the remainder stays in cash.";
        return new Result(summary, List.of(), null, fallback, null, reason);
    }
}
