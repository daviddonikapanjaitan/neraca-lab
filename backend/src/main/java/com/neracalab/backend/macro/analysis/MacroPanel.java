package com.neracalab.backend.macro.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.neracalab.backend.macro.analysis.MacroValidator.Facts;
import com.neracalab.backend.macro.analysis.MacroValidator.Issue;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.json.JsonMapper;

/**
 * The four macro agents (independent, one model call each) and the Reflection critic, as the stock analysis' investor
 * panel:
 * <ul>
 *   <li><b>Assess</b>: system prompt + macro dossier (shared prefix, cacheable) + the persona with its rules prior (the
 *       investor's preset on the Macro page) and its Reflexion lessons.</li>
 *   <li><b>Reflection</b>: {@link MacroValidator} checks the answer against the dossier; only a flagged answer goes to
 *       the critic, which sees its answer and the issues and keeps or revises it.</li>
 *   <li><b>Reflexion (within the run)</b>: an unusable reply is retried once with the problem as feedback.</li>
 * </ul>
 */
@Component
public class MacroPanel {

    private static final Logger log = LoggerFactory.getLogger(MacroPanel.class);
    static final String STAGE_AGENT = "AGENT";
    static final String STAGE_REFLECTION = "REFLECTION";

    /**
     * The agent's prior from the rules: its preset's stance and score on the Macro page.
     *
     * @param score      0-100 (null: the preset has no score, too few signals)
     * @param stance     the preset's stance: BUY, ACCUMULATE, WAIT, AVOID
     * @param presetText the preset's score as the page shows it, e.g. "-3 of 6"
     */
    public record Prior(Double score, String stance, String stanceLabel, String presetText) {
    }

    /**
     * Outcome of one agent.
     *
     * @param original the first answer (null: no model answer, the prior only)
     * @param revised  the critic's answer (null: not reviewed, or the review failed)
     * @param issues   what the validator found in the original answer
     * @param error    why there is no model answer
     */
    public record Outcome(MacroAgent agent, MacroAssessment original, MacroAssessment revised, List<Issue> issues,
                          String error) {

        public MacroAssessment effective() {
            return revised != null ? revised : original;
        }
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final JsonReplies json;

    public MacroPanel(LlmGateway llm, ScreeningProperties screening, JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.json = new JsonReplies(mapper);
    }

    /** First answer of an agent (with one retry when unusable). */
    public Outcome assess(UsageMeter meter, String subject, String dossier, MacroAgent agent, Prior prior, Facts facts,
                          List<String> lessons, Set<String> signalKeys, int maxTokens) {
        List<Message> messages = baseMessages(dossier, agent, prior, lessons);
        Purpose purpose = new Purpose(STAGE_AGENT, agent.name(), subject);
        try {
            MacroAssessment a = ask(meter, purpose, messages, maxTokens);
            List<Issue> issues = MacroValidator.validate(agent, a, prior == null ? null : prior.score(), facts, signalKeys);
            return new Outcome(agent, a, null, issues, null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("{} on {}: no usable answer ({})", agent, subject, e.getMessage());
            return new Outcome(agent, null, null, List.of(), e.getMessage());
        }
    }

    /** Reflection, step 2: the critic keeps or revises a flagged answer. A failed review keeps the original. */
    public Outcome reflect(UsageMeter meter, String subject, String dossier, Prior prior, List<String> lessons,
                           Outcome outcome, int maxTokens) {
        if (outcome.original() == null || outcome.issues().isEmpty()) {
            return outcome;
        }
        List<Message> messages = baseMessages(dossier, outcome.agent(), prior, lessons);
        messages.add(new AssistantMessage(json.write(outcome.original())));
        StringBuilder review = new StringBuilder("REFLECTION REVIEW. A validator checked your answer against the dossier:\n");
        for (Issue issue : outcome.issues()) {
            review.append("- ").append(issue.message()).append('\n');
        }
        review.append("""
                Re-examine your answer critically. Keep your score and stance only if the dossier clearly supports them, \
                otherwise revise them. Reply with the same JSON object plus "note": "<= 25 words: what you changed and \
                why, or why you kept it".""");
        messages.add(new UserMessage(review.toString()));
        try {
            MacroAssessment revised = ask(meter, new Purpose(STAGE_REFLECTION, outcome.agent().name(), subject), messages,
                    maxTokens);
            return new Outcome(outcome.agent(), outcome.original(), revised, outcome.issues(), null);
        } catch (LlmException | InvalidReplyException e) {
            log.info("reflection of {} on {} failed ({}), original kept", outcome.agent(), subject, e.getMessage());
            return outcome;
        }
    }

    /**
     * One answer, retried once when unusable. The unusable reply is not sent back (a model shown a reply cut off at the
     * output limit continues it instead of starting again); the feedback names the problem.
     */
    private MacroAssessment ask(UsageMeter meter, Purpose purpose, List<Message> messages, int maxTokens) {
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
    private MacroAssessment scored(String text) {
        MacroAssessment a = json.parse(text, MacroAssessment.class).normalized();
        if (a.score() == null) {
            throw new InvalidReplyException("the reply has no score");
        }
        if (a.supports().isEmpty() && a.risks().isEmpty()) {
            throw new InvalidReplyException("the reply has no supports and no risks (use exactly the requested keys)");
        }
        return a;
    }

    private static List<Message> baseMessages(String dossier, MacroAgent agent, Prior prior, List<String> lessons) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(MacroAnalysisPrompts.ANALYST));
        messages.add(new UserMessage(dossier));
        messages.add(new UserMessage(personaBlock(agent, prior, lessons)));
        return messages;
    }

    static String personaBlock(MacroAgent agent, Prior prior, List<String> lessons) {
        StringBuilder b = new StringBuilder();
        b.append("PERSONA: ").append(MacroAnalysisPrompts.persona(agent)).append('\n');
        if (prior == null || prior.score() == null) {
            b.append("Rules prior: your preset has too few signals for a score")
                    .append(prior == null || prior.stance() == null ? "" : " (its stance: " + prior.stance() + ")")
                    .append("; judge from the dossier.\n");
        } else {
            b.append("Rules prior (your preset on the Macro page): ").append(Math.round(prior.score())).append("/100 (preset ")
                    .append(prior.presetText()).append("), stance ").append(prior.stance()).append(": ")
                    .append(prior.stanceLabel()).append('\n');
        }
        if (lessons != null && !lessons.isEmpty()) {
            b.append("Lessons from your earlier analyses (Reflexion memory):\n");
            lessons.forEach(l -> b.append("- ").append(l).append('\n'));
        }
        b.append("TASK: score 0-100 how investable this country's stock market is now through your lens, and give your "
                + "stance. Reply:\n").append(MacroAnalysisPrompts.ASSESSMENT_FORMAT);
        return b.toString();
    }
}
