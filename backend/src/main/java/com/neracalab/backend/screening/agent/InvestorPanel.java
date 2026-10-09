package com.neracalab.backend.screening.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.ReflectionValidator.Issue;
import com.neracalab.backend.screening.quant.QuantScorer.AgentScore;
import com.neracalab.backend.screening.quant.QuantScorer.Part;
import com.neracalab.backend.screening.quant.StockProfile;

import tools.jackson.databind.json.JsonMapper;

/**
 * The six investor agents (independent, one model call per agent and stock) and the Reflection
 * critic.
 * <ul>
 *   <li><b>Assess</b>: system prompt + stock dossier (shared prefix, cacheable) + the persona with its
 *       quantitative scorecard and Reflexion lessons.</li>
 *   <li><b>Reflection</b>: {@link ReflectionValidator} checks the answer against the data; only a
 *       flagged answer goes to the critic, which sees the original answer and the issues and keeps or
 *       revises it.</li>
 *   <li><b>Reflexion (within the run)</b>: a reply that is not valid JSON is retried once with the
 *       parse error as verbal feedback.</li>
 * </ul>
 */
@Component
public class InvestorPanel {

    private static final Logger log = LoggerFactory.getLogger(InvestorPanel.class);
    static final String STAGE_AGENT = "AGENT";
    static final String STAGE_REFLECTION = "REFLECTION";

    /**
     * Outcome of one agent on one stock.
     *
     * @param original  the first answer (null: no model answer, quantitative score only)
     * @param revised   the critic's answer (null: not reviewed, or the review failed)
     * @param issues    what the validator found in the original answer
     * @param error     why there is no model answer
     */
    public record Outcome(InvestorAgent agent, Assessment original, Assessment revised, List<Issue> issues,
                         String error) {

        public Assessment effective() {
            return revised != null ? revised : original;
        }
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm properties;
    private final JsonReplies json;

    public InvestorPanel(LlmGateway llm, ScreeningProperties properties, JsonMapper mapper) {
        this.llm = llm;
        this.properties = properties.llm();
        this.json = new JsonReplies(mapper);
    }

    /** First answer of an agent in a screening (with one JSON retry). */
    public Outcome assess(UsageMeter meter, String ticker, String dossier, InvestorAgent agent, AgentScore quant,
                          StockProfile profile, List<String> lessons) {
        return assess(meter, ticker, ScreeningPrompts.ANALYST, dossier, agent, quant, profile, lessons,
                metricKeys(profile), properties.agentMaxTokens());
    }

    /**
     * First answer of an agent (with one JSON retry).
     *
     * @param system     the shared system prompt (screening or single-stock analysis)
     * @param quant      the agent's quantitative scorecard (null: none, the agent judges from the data alone)
     * @param profile    the stock's metrics for the validator's philosophy rules (null: none)
     * @param metricKeys the keys an answer may cite in {@code metricsUsed}
     * @param maxTokens  output limit of one answer
     */
    public Outcome assess(UsageMeter meter, String ticker, String system, String dossier, InvestorAgent agent,
                          AgentScore quant, StockProfile profile, List<String> lessons, Set<String> metricKeys,
                          int maxTokens) {
        List<Message> messages = baseMessages(system, dossier, agent, quant, lessons);
        Purpose purpose = new Purpose(STAGE_AGENT, agent.name(), ticker);
        try {
            Assessment a = ask(meter, purpose, messages, maxTokens);
            List<Issue> issues = ReflectionValidator.validate(agent, a, quant == null ? null : quant.score(), profile,
                    metricKeys);
            return new Outcome(agent, a, null, issues, null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("{} on {}: no usable answer ({})", agent, ticker, e.getMessage());
            return new Outcome(agent, null, null, List.of(), e.getMessage());
        }
    }

    /** Reflection, step 2 in a screening: the critic reviews a flagged answer. A failed review keeps the original. */
    public Outcome reflect(UsageMeter meter, String ticker, String dossier, AgentScore quant, StockProfile profile,
                           List<String> lessons, Outcome outcome) {
        return reflect(meter, ticker, ScreeningPrompts.ANALYST, dossier, quant, lessons, outcome,
                properties.agentMaxTokens());
    }

    /**
     * Reflection, step 2: the critic sees the same prompt, its answer and the validator's issues, and keeps or
     * revises the answer. A failed review keeps the original.
     *
     * @param maxTokens output limit of the review
     */
    public Outcome reflect(UsageMeter meter, String ticker, String system, String dossier, AgentScore quant,
                           List<String> lessons, Outcome outcome, int maxTokens) {
        if (outcome.original() == null || outcome.issues().isEmpty()) {
            return outcome;
        }
        List<Message> messages = baseMessages(system, dossier, outcome.agent(), quant, lessons);
        messages.add(new AssistantMessage(json.write(outcome.original())));
        StringBuilder review = new StringBuilder("REFLECTION REVIEW. A validator checked your answer against the data:\n");
        for (Issue issue : outcome.issues()) {
            review.append("- ").append(issue.message()).append('\n');
        }
        review.append("""
                Re-examine your answer critically. Keep your score only if the data clearly supports it, \
                otherwise revise it. Reply with the same JSON object plus "note": "<= 25 words: what you \
                changed and why, or why you kept it".""");
        messages.add(new UserMessage(review.toString()));
        try {
            Assessment revised = ask(meter, new Purpose(STAGE_REFLECTION, outcome.agent().name(), ticker), messages,
                    maxTokens);
            return new Outcome(outcome.agent(), outcome.original(), revised, outcome.issues(), null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("reflection of {} on {} failed ({}), original kept", outcome.agent(), ticker, e.getMessage());
            return outcome;
        }
    }

    /**
     * One answer, retried once when unusable (Reflexion within the run). The unusable reply is not sent back: a
     * model shown a reply cut off at the output limit continues it instead of starting again, and the fragment has
     * no score. The feedback names the problem (cut off at the limit, or the parse error).
     */
    private Assessment ask(UsageMeter meter, Purpose purpose, List<Message> messages, int maxTokens) {
        LlmGateway.Reply reply = llm.worker(meter, purpose, properties.agentModel(), messages, List.of(), maxTokens);
        try {
            return scored(reply.text());
        } catch (InvalidReplyException e) {
            List<Message> retry = new ArrayList<>(messages);
            retry.add(new UserMessage("Your answer was not usable: " + problem(reply, e, maxTokens)
                    + ". Reply again with the complete JSON object only, starting with {, and keep to the word "
                    + "limits (thesis at most 40 words, at most 3 strengths and 3 concerns)."));
            LlmGateway.Reply second = llm.worker(meter, purpose, properties.agentModel(), retry, List.of(), maxTokens);
            return scored(second.text());
        }
    }

    /** What was wrong with a reply: cut off at the output limit, or the parse error. */
    static String problem(LlmGateway.Reply reply, InvalidReplyException e, int maxTokens) {
        if (reply.usage() != null && reply.usage().completionTokens() >= maxTokens) {
            return "it was cut off at the output limit of " + maxTokens + " tokens before the JSON object was complete";
        }
        return e.getMessage();
    }

    /**
     * A usable answer: a score and at least one strength or concern. An answer with neither came from a model that
     * ignored the requested shape (HRTA, 2026-10-08: a degraded provider wrote other keys and ran to the output
     * limit); it is asked again instead of being shown without its reasons.
     */
    private Assessment scored(String text) {
        Assessment a = json.parse(text, Assessment.class).normalized();
        if (a.score() == null) {
            throw new InvalidReplyException("the reply has no score");
        }
        if (a.strengths().isEmpty() && a.concerns().isEmpty()) {
            throw new InvalidReplyException("the reply has no strengths and no concerns (use exactly the requested keys)");
        }
        return a;
    }

    private List<Message> baseMessages(String system, String dossier, InvestorAgent agent, AgentScore quant,
                                       List<String> lessons) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        messages.add(new UserMessage(dossier));
        messages.add(new UserMessage(personaBlock(agent, quant, lessons)));
        return messages;
    }

    static String personaBlock(InvestorAgent agent, AgentScore quant, List<String> lessons) {
        StringBuilder b = new StringBuilder();
        b.append("PERSONA: ").append(ScreeningPrompts.persona(agent)).append('\n');
        if (quant == null) {
            b.append("Quantitative scorecard: not available (no market data for this stock); judge from the company data.\n");
        } else {
            b.append("Quantitative scorecard (your prior): ").append(Math.round(quant.score())).append("/100, data coverage ")
                    .append(Math.round(quant.coverage() * 100)).append("%.\n");
        }
        for (Part part : quant == null ? List.<Part>of() : quant.parts()) {
            b.append("- ").append(part.label()).append(" [").append(part.key()).append("]: ");
            if (part.value() == null) {
                b.append("unknown");
            } else {
                b.append(round(part.value())).append(" -> ").append(Math.round(part.points() * 100)).append("% of weight ")
                        .append(part.weight());
            }
            b.append('\n');
        }
        if (lessons != null && !lessons.isEmpty()) {
            b.append("Lessons from your earlier runs (Reflexion memory):\n");
            lessons.forEach(l -> b.append("- ").append(l).append('\n'));
        }
        b.append("TASK: score 0-100 how well this stock fits your philosophy today")
                .append(agent == InvestorAgent.RISK ? " (100 = safest)" : "")
                .append(". Reply:\n").append(ScreeningPrompts.ASSESSMENT_FORMAT);
        return b.toString();
    }

    static Set<String> metricKeys(StockProfile profile) {
        Set<String> keys = new HashSet<>(StockProfile.METRIC_KEYS);
        keys.addAll(Set.of("revenue", "netIncome", "operatingIncome", "freeCashFlow", "totalDebt", "equity",
                "totalAssets", "fiscalYearEnds", "annualIdrBillions", "news", "sentiment", "summary", "catalysts",
                "risks", "liquidity", "sector", "industry", "tier", "tradabilityWarnings"));
        return keys;
    }

    private static double round(double v) {
        double abs = Math.abs(v);
        if (abs >= 1000) {
            return Math.round(v);
        }
        return abs >= 10 ? Math.round(v * 100) / 100.0 : Math.round(v * 10000) / 10000.0;
    }
}
