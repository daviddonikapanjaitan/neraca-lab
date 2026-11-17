package com.neracalab.backend.portfolio.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
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
import com.neracalab.backend.technical.screening.TradePlan;

import tools.jackson.databind.json.JsonMapper;

/**
 * The two AI agents of Portfolio &gt; Analysis (the worker model, DeepSeek; one independent call per stock):
 * <ul>
 *   <li>the <b>fundamental</b> agent judges the business (score, PASS / FAIL) from the six rules scorecards;</li>
 *   <li>the <b>technical</b> agent judges the timing (score, stance, entry / stop loss / target) from the four rules
 *       summaries - run only for the stocks the fundamental agent passed, and for the holdings.</li>
 * </ul>
 * The agentic patterns of the screening: an unusable reply (no JSON, no score) is asked again once with the problem;
 * <b>Reflection</b>: a validator checks every answer without a model ({@link #checkFundamental},
 * {@link #checkTechnical}) and a critic reviews the flagged ones; <b>Reflexion</b>: the lessons of earlier runs are
 * part of the prompts ({@link PortfolioLessons}).
 */
@Component
public class PortfolioAgents {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAgents.class);

    public static final String FUNDAMENTAL = "FUNDAMENTAL";
    public static final String TECHNICAL = "TECHNICAL";
    static final Set<String> STANCES = Set.of("BUY", "ACCUMULATE", "WAIT", "AVOID");
    static final Set<String> VERDICTS = Set.of("PASS", "FAIL");
    /** The answer is this many points from the rules score or more: flagged. */
    static final double MAX_DIVERGENCE = 35;
    /** A stance outside its band by more than this: flagged. */
    static final double STANCE_TOLERANCE = 5;

    /** The fundamental agent's answer. */
    public record FundamentalAnswer(Double score, String verdict, String thesis, List<String> strengths,
                                    List<String> risks, String note) {
    }

    /** The technical agent's answer. */
    public record TechnicalAnswer(Double score, String stance, Double entry, String entryType, Double stopLoss,
                                  Double target, String thesis, List<String> risks, String note) {
    }

    /** A problem the validator found. */
    public record Issue(String kind, String message) {
    }

    /**
     * What an agent said about a stock.
     *
     * @param original  its first usable answer (null: none)
     * @param revised   the critic's answer (null: not reviewed, or unchanged)
     * @param issues    the validator's findings on the original
     * @param remaining the findings left on the effective answer
     * @param error     why there is no usable answer (budget, model failure)
     */
    public record Outcome<T>(String agent, T original, T revised, List<Issue> issues, List<Issue> remaining,
                             String error) {

        public T effective() {
            return revised != null ? revised : original;
        }

        Outcome<T> reviewed(T answer, List<Issue> left) {
            return new Outcome<>(agent, original, answer, issues, left, error);
        }

        static <T> Outcome<T> none(String agent, String error) {
            return new Outcome<>(agent, null, null, List.of(), List.of(), error);
        }
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final JsonReplies json;

    public PortfolioAgents(LlmGateway llm, ScreeningProperties screening, JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.json = new JsonReplies(mapper);
    }

    // ------------------------------------------------------------------ fundamental

    public Outcome<FundamentalAnswer> fundamental(UsageMeter meter, String ticker, String dossier, double rulesScore,
                                                  Double riskScore, List<String> lessons, int maxTokens) {
        String system = PortfolioPrompts.withLessons(PortfolioPrompts.FUNDAMENTAL, lessons);
        FundamentalAnswer a;
        try {
            a = ask(meter, FUNDAMENTAL, ticker, system, dossier, FundamentalAnswer.class, maxTokens);
        } catch (LlmException | InvalidReplyException e) {
            log.warn("portfolio analysis: fundamental agent on {} failed: {}", ticker, e.getMessage());
            return Outcome.none(FUNDAMENTAL, "No usable answer: " + e.getMessage());
        }
        a = normalize(a);
        List<Issue> issues = checkFundamental(a, rulesScore, riskScore);
        return new Outcome<>(FUNDAMENTAL, a, null, issues, issues, null);
    }

    public Outcome<FundamentalAnswer> reviewFundamental(UsageMeter meter, String ticker, String dossier,
                                                        double rulesScore, Double riskScore,
                                                        Outcome<FundamentalAnswer> o, int maxTokens) {
        try {
            FundamentalAnswer r = normalize(critic(meter, FUNDAMENTAL, ticker, dossier, o.original(), o.issues(),
                    FundamentalAnswer.class, maxTokens));
            return o.reviewed(r, checkFundamental(r, rulesScore, riskScore));
        } catch (LlmException | InvalidReplyException e) {
            log.warn("portfolio analysis: review of the fundamental answer on {} failed: {}", ticker, e.getMessage());
            return o;
        }
    }

    static FundamentalAnswer normalize(FundamentalAnswer a) {
        String verdict = a.verdict() == null ? null : a.verdict().trim().toUpperCase(Locale.ROOT);
        Double score = a.score() == null || !Double.isFinite(a.score()) ? null
                : Math.max(0, Math.min(100, a.score()));
        return new FundamentalAnswer(score, verdict != null && VERDICTS.contains(verdict) ? verdict : null,
                Texts.clipOrNull(a.thesis(), 400), Texts.clip(a.strengths(), 3, 200), Texts.clip(a.risks(), 3, 200),
                Texts.clipOrNull(a.note(), 300));
    }

    /** The validator of a fundamental answer (no model). */
    static List<Issue> checkFundamental(FundamentalAnswer a, double rulesScore, Double riskScore) {
        List<Issue> out = new ArrayList<>();
        if (a.score() == null || a.thesis() == null || a.verdict() == null) {
            out.add(new Issue("EMPTY", "The answer needs a score, a verdict PASS or FAIL and a thesis."));
            return out;
        }
        if (Math.abs(a.score() - rulesScore) > MAX_DIVERGENCE) {
            out.add(new Issue("DIVERGENCE", String.format(Locale.ROOT, "Score %.0f is more than %.0f points from the "
                    + "rules Fundamental (Score) %.1f.", a.score(), MAX_DIVERGENCE, rulesScore)));
        }
        if ("PASS".equals(a.verdict()) && a.score() < 50) {
            out.add(new Issue("VERDICT", String.format(Locale.ROOT, "PASS needs a score of 50 or more (got %.0f).",
                    a.score())));
        }
        if ("FAIL".equals(a.verdict()) && a.score() >= 65) {
            out.add(new Issue("VERDICT", String.format(Locale.ROOT, "FAIL with a score of %.0f: a FAIL scores below 65.",
                    a.score())));
        }
        if ("PASS".equals(a.verdict()) && riskScore != null && riskScore < 30) {
            out.add(new Issue("RULE", String.format(Locale.ROOT, "PASS although the Risk scorecard is %.1f (below 30).",
                    riskScore)));
        }
        return out;
    }

    // ------------------------------------------------------------------ technical

    public Outcome<TechnicalAnswer> technical(UsageMeter meter, String ticker, String dossier, double rulesScore,
                                              double close, List<String> lessons, int maxTokens) {
        String system = PortfolioPrompts.withLessons(PortfolioPrompts.TECHNICAL, lessons);
        TechnicalAnswer a;
        try {
            a = ask(meter, TECHNICAL, ticker, system, dossier, TechnicalAnswer.class, maxTokens);
        } catch (LlmException | InvalidReplyException e) {
            log.warn("portfolio analysis: technical agent on {} failed: {}", ticker, e.getMessage());
            return Outcome.none(TECHNICAL, "No usable answer: " + e.getMessage());
        }
        a = normalize(a);
        List<Issue> issues = checkTechnical(a, rulesScore, close);
        return new Outcome<>(TECHNICAL, a, null, issues, issues, null);
    }

    public Outcome<TechnicalAnswer> reviewTechnical(UsageMeter meter, String ticker, String dossier, double rulesScore,
                                                    double close, Outcome<TechnicalAnswer> o, int maxTokens) {
        try {
            TechnicalAnswer r = normalize(critic(meter, TECHNICAL, ticker, dossier, o.original(), o.issues(),
                    TechnicalAnswer.class, maxTokens));
            return o.reviewed(r, checkTechnical(r, rulesScore, close));
        } catch (LlmException | InvalidReplyException e) {
            log.warn("portfolio analysis: review of the technical answer on {} failed: {}", ticker, e.getMessage());
            return o;
        }
    }

    static TechnicalAnswer normalize(TechnicalAnswer a) {
        String stance = a.stance() == null ? null : a.stance().trim().toUpperCase(Locale.ROOT);
        String type = a.entryType() == null ? null : a.entryType().trim().toUpperCase(Locale.ROOT);
        Double score = a.score() == null || !Double.isFinite(a.score()) ? null
                : Math.max(0, Math.min(100, a.score()));
        return new TechnicalAnswer(score, stance != null && STANCES.contains(stance) ? stance : null,
                finite(a.entry()), "STOP".equals(type) || "LIMIT".equals(type) ? type : null, finite(a.stopLoss()),
                finite(a.target()), Texts.clipOrNull(a.thesis(), 400), Texts.clip(a.risks(), 3, 200),
                Texts.clipOrNull(a.note(), 300));
    }

    /** The validator of a technical answer (no model). */
    static List<Issue> checkTechnical(TechnicalAnswer a, double rulesScore, double close) {
        List<Issue> out = new ArrayList<>();
        if (a.score() == null || a.thesis() == null || a.stance() == null) {
            out.add(new Issue("EMPTY", "The answer needs a score, a stance and a thesis."));
            return out;
        }
        if (Math.abs(a.score() - rulesScore) > MAX_DIVERGENCE) {
            out.add(new Issue("DIVERGENCE", String.format(Locale.ROOT, "Score %.0f is more than %.0f points from the "
                    + "rules Technical (Score) %.1f.", a.score(), MAX_DIVERGENCE, rulesScore)));
        }
        double low = stanceLow(a.stance());
        double high = stanceHigh(a.stance());
        if (a.score() < low - STANCE_TOLERANCE || a.score() > high + STANCE_TOLERANCE) {
            out.add(new Issue("STANCE", String.format(Locale.ROOT, "%s fits scores %.0f-%.0f, not %.0f.", a.stance(), low,
                    high, a.score())));
        }
        List<String> problems = TradePlan.problems(a.entry(), a.entryType(), a.target(), a.stopLoss(), close);
        if (!problems.isEmpty()) {
            out.add(new Issue("LEVELS", "The trade plan: " + String.join("; ", problems) + "."));
        }
        return out;
    }

    static double stanceLow(String stance) {
        return switch (stance) {
            case "BUY" -> 65;
            case "ACCUMULATE" -> 50;
            case "WAIT" -> 35;
            default -> 0;
        };
    }

    static double stanceHigh(String stance) {
        return switch (stance) {
            case "BUY" -> 100;
            case "ACCUMULATE" -> 79;
            case "WAIT" -> 64;
            default -> 49;
        };
    }

    // ------------------------------------------------------------------ calls

    /** One call; an unusable reply is asked again once with the problem (the reply itself is not sent back). */
    private <T> T ask(UsageMeter meter, String agent, String ticker, String system, String dossier, Class<T> type,
                      int maxTokens) {
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage(system), new UserMessage(dossier)));
        Purpose purpose = new Purpose("AGENT", agent, ticker);
        String text = llm.worker(meter, purpose, models.agentModel(), messages, List.of(), maxTokens).text();
        try {
            return usable(json.parse(text, type));
        } catch (InvalidReplyException first) {
            messages.add(new UserMessage("Your reply could not be used (" + first.getMessage() + "). Answer again with "
                    + "the complete JSON object only, as specified."));
            String again = llm.worker(meter, new Purpose("AGENT_RETRY", agent, ticker), models.agentModel(), messages,
                    List.of(), maxTokens).text();
            return usable(json.parse(again, type));
        }
    }

    private <T> T critic(UsageMeter meter, String agent, String ticker, String dossier, Object answer,
                         List<Issue> issues, Class<T> type, int maxTokens) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("analyst", agent);
        review.put("answer", answer);
        review.put("problems", issues.stream().map(i -> i.kind() + ": " + i.message()).toList());
        List<Message> messages = List.of(new SystemMessage(PortfolioPrompts.CRITIC + "\n\nTHE ANALYST'S INSTRUCTIONS:\n"
                        + (FUNDAMENTAL.equals(agent) ? PortfolioPrompts.FUNDAMENTAL : PortfolioPrompts.TECHNICAL)),
                new UserMessage(dossier + "\n\nREVIEW\n" + json.write(review)));
        String text = llm.worker(meter, new Purpose("REFLECTION", agent, ticker), models.agentModel(), messages,
                List.of(), maxTokens).text();
        return usable(json.parse(text, type));
    }

    /** A reply with no score at all is not usable. */
    private static <T> T usable(T value) {
        Double score = value instanceof FundamentalAnswer f ? f.score() : value instanceof TechnicalAnswer t ? t.score()
                : null;
        if (score == null) {
            throw new InvalidReplyException("the reply has no score");
        }
        return value;
    }

    private static Double finite(Double v) {
        return v == null || !Double.isFinite(v) || v <= 0 ? null : v;
    }
}
