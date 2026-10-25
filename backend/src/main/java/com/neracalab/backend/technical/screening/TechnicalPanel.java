package com.neracalab.backend.technical.screening;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import com.neracalab.backend.technical.screening.TechnicalValidator.Facts;
import com.neracalab.backend.technical.screening.TechnicalValidator.Issue;

import tools.jackson.databind.json.JsonMapper;

/**
 * The four technical agents (independent, one model call per agent and stock) and the Reflection critic, as the
 * stock screening's investor panel:
 * <ul>
 *   <li><b>Assess</b>: system prompt + chart dossier (shared prefix, cacheable) + the persona with its rules summary
 *       (the preset of Companies > Technicals: stance, score, trade plan, evidence) and its Reflexion lessons.</li>
 *   <li><b>Reflection</b>: {@link TechnicalValidator} checks the answer against the data; only a flagged answer goes to
 *       the critic, which sees its answer and the issues and keeps or revises it.</li>
 *   <li><b>Reflexion (within the run)</b>: an unusable reply is retried once with the problem as feedback.</li>
 * </ul>
 */
@Component
public class TechnicalPanel {

    private static final Logger log = LoggerFactory.getLogger(TechnicalPanel.class);
    static final String STAGE_AGENT = "TECHNICAL_AGENT";
    static final String STAGE_REFLECTION = "REFLECTION";

    /**
     * An agent's prior: its preset's rules summary.
     *
     * @param score   0-100: 50 + 50 x preset score / its maximum
     * @param summary the preset's summary as {@code PresetSummaries} wrote it (stance, score, buy / sell / stop, points)
     */
    public record Prior(double score, Map<String, Object> summary) {

        public String stance() {
            return String.valueOf(summary.get("stance"));
        }
    }

    /**
     * Outcome of one agent on one stock.
     *
     * @param original the first answer (null: no model answer, the prior only)
     * @param revised  the critic's answer (null: not reviewed, or the review failed)
     * @param issues   what the validator found in the original answer
     * @param error    why there is no model answer
     */
    public record Outcome(TechnicalAgent agent, TechnicalAssessment original, TechnicalAssessment revised,
                          List<Issue> issues, String error) {

        public TechnicalAssessment effective() {
            return revised != null ? revised : original;
        }
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final JsonReplies json;

    public TechnicalPanel(LlmGateway llm, ScreeningProperties screening, JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.json = new JsonReplies(mapper);
    }

    /** First answer of an agent (with one retry when unusable). */
    public Outcome assess(UsageMeter meter, String ticker, String dossier, TechnicalAgent agent, Prior prior, Facts facts,
                          List<String> lessons, Set<String> signalKeys, int maxTokens) {
        List<Message> messages = baseMessages(dossier, agent, prior, lessons);
        Purpose purpose = new Purpose(STAGE_AGENT, agent.name(), ticker);
        try {
            TechnicalAssessment a = ask(meter, purpose, messages, maxTokens);
            List<Issue> issues = TechnicalValidator.validate(agent, a, prior == null ? null : prior.score(), facts, signalKeys);
            return new Outcome(agent, a, null, issues, null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("{} on {}: no usable answer ({})", agent, ticker, e.getMessage());
            return new Outcome(agent, null, null, List.of(), e.getMessage());
        }
    }

    /** Reflection, step 2: the critic keeps or revises a flagged answer. A failed review keeps the original. */
    public Outcome reflect(UsageMeter meter, String ticker, String dossier, Prior prior, List<String> lessons,
                           Outcome outcome, int maxTokens) {
        if (outcome.original() == null || outcome.issues().isEmpty()) {
            return outcome;
        }
        List<Message> messages = baseMessages(dossier, outcome.agent(), prior, lessons);
        messages.add(new AssistantMessage(json.write(outcome.original())));
        StringBuilder review = new StringBuilder("REFLECTION REVIEW. A validator checked your answer against the data:\n");
        for (Issue issue : outcome.issues()) {
            review.append("- ").append(issue.message()).append('\n');
        }
        review.append("""
                Re-examine your answer critically. Keep your score, stance and levels only if the dossier clearly \
                supports them, otherwise revise them. Reply with the same JSON object plus "note": "<= 25 words: what \
                you changed and why, or why you kept it".""");
        messages.add(new UserMessage(review.toString()));
        try {
            TechnicalAssessment revised = ask(meter, new Purpose(STAGE_REFLECTION, outcome.agent().name(), ticker),
                    messages, maxTokens);
            return new Outcome(outcome.agent(), outcome.original(), revised, outcome.issues(), null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("reflection of {} on {} failed ({}), original kept", outcome.agent(), ticker, e.getMessage());
            return outcome;
        }
    }

    /**
     * One answer, retried once when unusable. The unusable reply is not sent back (a model shown a reply cut off at the
     * output limit continues it instead of starting again); the feedback names the problem.
     */
    private TechnicalAssessment ask(UsageMeter meter, Purpose purpose, List<Message> messages, int maxTokens) {
        LlmGateway.Reply reply = llm.worker(meter, purpose, models.agentModel(), messages, List.of(), maxTokens);
        try {
            return scored(reply.text());
        } catch (InvalidReplyException e) {
            List<Message> retry = new ArrayList<>(messages);
            retry.add(new UserMessage("Your answer was not usable: " + problem(reply, e, maxTokens)
                    + ". Reply again with the complete JSON object only, starting with {, and keep to the word limits "
                    + "(thesis at most 50 words, at most 3 supports and 3 risks)."));
            LlmGateway.Reply second = llm.worker(meter, purpose, models.agentModel(), retry, List.of(), maxTokens);
            return scored(second.text());
        }
    }

    static String problem(LlmGateway.Reply reply, InvalidReplyException e, int maxTokens) {
        if (reply.usage() != null && reply.usage().completionTokens() >= maxTokens) {
            return "it was cut off at the output limit of " + maxTokens + " tokens before the JSON object was complete";
        }
        return e.getMessage();
    }

    /** A usable answer: a score and at least one support or risk (the shape asked for). */
    private TechnicalAssessment scored(String text) {
        TechnicalAssessment a = json.parse(text, TechnicalAssessment.class).normalized();
        if (a.score() == null) {
            throw new InvalidReplyException("the reply has no score");
        }
        if (a.supports().isEmpty() && a.risks().isEmpty()) {
            throw new InvalidReplyException("the reply has no supports and no risks (use exactly the requested keys)");
        }
        return a;
    }

    private static List<Message> baseMessages(String dossier, TechnicalAgent agent, Prior prior, List<String> lessons) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(TechnicalPrompts.ANALYST));
        messages.add(new UserMessage(dossier));
        messages.add(new UserMessage(personaBlock(agent, prior, lessons)));
        return messages;
    }

    static String personaBlock(TechnicalAgent agent, Prior prior, List<String> lessons) {
        StringBuilder b = new StringBuilder();
        b.append("PERSONA: ").append(TechnicalPrompts.persona(agent)).append('\n');
        if (prior == null) {
            b.append("Rules summary: not available; judge from the dossier.\n");
        } else {
            Map<String, Object> s = prior.summary();
            b.append("Your rules summary (the ").append(s.get("name")).append(" preset of Companies > Technicals): score ")
                    .append(s.get("score")).append(" of +-").append(s.get("maxScore")).append(" = prior ")
                    .append(Math.round(prior.score())).append("/100, stance ").append(s.get("stance")).append(": ")
                    .append(s.get("stanceLabel")).append('\n');
            TradePlan plan = TradePlan.ofRules(s, agent.name());
            if (plan.complete()) {
                b.append("Rules trade plan: buy ").append(plan.buy().type().equals("STOP") ? "at or above " : "at or below ")
                        .append(TradePlan.fmt(plan.buy().price())).append(" (").append(plan.buy().basis()).append("), sell ")
                        .append(TradePlan.fmt(plan.sell().price())).append(" (").append(plan.sell().basis())
                        .append("), stop loss ").append(TradePlan.fmt(plan.stopLoss().price())).append(" (")
                        .append(plan.stopLoss().basis()).append(")")
                        .append(plan.rewardRisk() == null ? "" : ", reward / risk " + plan.rewardRisk()).append('\n');
            } else {
                b.append("Rules trade plan: none (too little history for an ATR); set the levels from the dossier.\n");
            }
            if (s.get("points") instanceof List<?> points && !points.isEmpty()) {
                b.append("Rules evidence:\n");
                for (Object p : points) {
                    if (p instanceof Map<?, ?> m) {
                        String tone = String.valueOf(m.get("tone"));
                        b.append(tone.equals("POSITIVE") ? "+ " : tone.equals("NEGATIVE") ? "- " : "o ")
                                .append(m.get("text")).append('\n');
                    }
                }
            }
        }
        if (lessons != null && !lessons.isEmpty()) {
            b.append("Lessons from your earlier runs (Reflexion memory):\n");
            lessons.forEach(l -> b.append("- ").append(l).append('\n'));
        }
        b.append("TASK: score 0-100 how attractive this chart is now through your style, give your stance and your trade "
                + "plan. Reply:\n").append(TechnicalPrompts.ASSESSMENT_FORMAT);
        return b.toString();
    }
}
