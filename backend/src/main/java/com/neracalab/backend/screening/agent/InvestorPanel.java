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

    /** First answer of an agent (with one JSON retry). */
    public Outcome assess(UsageMeter meter, String ticker, String dossier, InvestorAgent agent, AgentScore quant,
                          StockProfile profile, List<String> lessons) {
        List<Message> messages = baseMessages(dossier, agent, quant, lessons);
        Purpose purpose = new Purpose(STAGE_AGENT, agent.name(), ticker);
        try {
            Assessment a = ask(meter, purpose, messages);
            List<Issue> issues = ReflectionValidator.validate(agent, a, quant.score(), profile, metricKeys(profile));
            return new Outcome(agent, a, null, issues, null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("{} on {}: no usable answer ({})", agent, ticker, e.getMessage());
            return new Outcome(agent, null, null, List.of(), e.getMessage());
        }
    }

    /** Reflection, step 2: the critic reviews a flagged answer. A failed review keeps the original. */
    public Outcome reflect(UsageMeter meter, String ticker, String dossier, AgentScore quant, StockProfile profile,
                           List<String> lessons, Outcome outcome) {
        if (outcome.original() == null || outcome.issues().isEmpty()) {
            return outcome;
        }
        List<Message> messages = baseMessages(dossier, outcome.agent(), quant, lessons);
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
            Assessment revised = ask(meter, new Purpose(STAGE_REFLECTION, outcome.agent().name(), ticker), messages);
            return new Outcome(outcome.agent(), outcome.original(), revised, outcome.issues(), null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("reflection of {} on {} failed ({}), original kept", outcome.agent(), ticker, e.getMessage());
            return outcome;
        }
    }

    private Assessment ask(UsageMeter meter, Purpose purpose, List<Message> messages) {
        LlmGateway.Reply reply = llm.worker(meter, purpose, properties.agentModel(), messages, List.of(),
                properties.agentMaxTokens());
        try {
            return scored(reply.text());
        } catch (InvalidReplyException e) {
            List<Message> retry = new ArrayList<>(messages);
            retry.add(reply.message());
            retry.add(new UserMessage("Your answer was not usable: " + e.getMessage()
                    + ". Reply again with the JSON object only."));
            LlmGateway.Reply second = llm.worker(meter, purpose, properties.agentModel(), retry, List.of(),
                    properties.agentMaxTokens());
            return scored(second.text());
        }
    }

    private Assessment scored(String text) {
        Assessment a = json.parse(text, Assessment.class).normalized();
        if (a.score() == null) {
            throw new InvalidReplyException("the reply has no score");
        }
        return a;
    }

    private List<Message> baseMessages(String dossier, InvestorAgent agent, AgentScore quant, List<String> lessons) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(ScreeningPrompts.ANALYST));
        messages.add(new UserMessage(dossier));
        messages.add(new UserMessage(personaBlock(agent, quant, lessons)));
        return messages;
    }

    static String personaBlock(InvestorAgent agent, AgentScore quant, List<String> lessons) {
        StringBuilder b = new StringBuilder();
        b.append("PERSONA: ").append(ScreeningPrompts.persona(agent)).append('\n');
        b.append("Quantitative scorecard (your prior): ").append(Math.round(quant.score())).append("/100, data coverage ")
                .append(Math.round(quant.coverage() * 100)).append("%.\n");
        for (Part part : quant.parts()) {
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
                "risks", "liquidity", "sector", "industry", "tier"));
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
