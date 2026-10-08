package com.neracalab.backend.screening.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.agent.ReflectionValidator.Issue;

/**
 * Reflexion: verbal lessons learned from the reflection of earlier runs ({@code screening_lesson}),
 * injected into the prompts of later runs so the agents do not repeat the same mistakes. A lesson is
 * derived when an agent was flagged for the same kind of issue at least {@value #MIN_OCCURRENCES}
 * times in a run; its occurrence count grows with every run that repeats it. The prompts get the
 * {@value #PER_AGENT} most frequent active lessons of an agent.
 */
@Component
public class ReflexionMemory {

    static final int MIN_OCCURRENCES = 2;
    static final int PER_AGENT = 3;

    private final JdbcClient jdbc;

    public ReflexionMemory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The active lessons of an agent (or "RESEARCH"), most frequent first. */
    public List<String> lessons(String agent) {
        return jdbc.sql("""
                        SELECT lesson FROM screening_lesson WHERE agent = :agent AND active
                        ORDER BY occurrences DESC, updated_at DESC LIMIT :limit""")
                .param("agent", agent).param("limit", PER_AGENT)
                .query(String.class).list();
    }

    /** A lesson learned in a run (for the report). */
    public record Learned(String agent, String lesson, int occurrencesInRun) {
    }

    /**
     * Derives lessons from the issues the validator found in a run and stores them.
     *
     * @param issues per agent, every issue found in the run
     */
    public List<Learned> learn(UUID runId, Map<InvestorAgent, List<Issue>> issues) {
        return learn(runId, issues, MIN_OCCURRENCES);
    }

    /**
     * Derives lessons from the issues found in a run; a kind of issue becomes a lesson once an agent repeated it
     * {@code minOccurrences} times (a single-stock analysis gives each agent one answer: 1).
     */
    public List<Learned> learn(UUID runId, Map<InvestorAgent, List<Issue>> issues, int minOccurrences) {
        List<Learned> learned = new ArrayList<>();
        for (Map.Entry<InvestorAgent, List<Issue>> e : issues.entrySet()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Issue issue : e.getValue()) {
                counts.merge(issue.code(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                if (c.getValue() < minOccurrences) {
                    continue;
                }
                String lesson = lesson(e.getKey(), c.getKey());
                if (lesson == null) {
                    continue;
                }
                jdbc.sql("""
                                INSERT INTO screening_lesson (agent, lesson, occurrences, source_run)
                                VALUES (:agent, :lesson, :n, :run)
                                ON CONFLICT (agent, lesson) DO UPDATE SET
                                    occurrences = screening_lesson.occurrences + EXCLUDED.occurrences,
                                    source_run = EXCLUDED.source_run, active = TRUE, updated_at = now()""")
                        .param("agent", e.getKey().name()).param("lesson", lesson).param("n", c.getValue())
                        .param("run", runId)
                        .update();
                learned.add(new Learned(e.getKey().name(), lesson, c.getValue()));
            }
        }
        return learned;
    }

    /** The lesson text of an issue kind; null for kinds that teach nothing. */
    static String lesson(InvestorAgent agent, String code) {
        return switch (code) {
            case "DIVERGENCE" -> "Your scores often departed from the quantitative scorecard by more than "
                    + Math.round(ReflectionValidator.MAX_DIVERGENCE) + " points; depart from it only for reasons you can point to in the data or the news.";
            case "VERDICT" -> "Keep the verdict inside its score band: " + ScreeningPrompts.VERDICTS + ".";
            case "UNKNOWN_METRIC" -> "In metricsUsed cite only metric keys that appear in the stock data.";
            case "EMPTY" -> "Always give a score and a thesis.";
            case "RULE" -> switch (agent) {
                case BUFFETT -> "Do not score 70+ when ROE is below 10%, earnings were negative in a recent year or debt/equity is above 1.5.";
                case MUNGER -> "Do not score 70+ when ROE is below 12% or the operating margin is below 8%.";
                case LYNCH -> "Do not score 70+ when the PEG is above 2, or the P/E is above 25 without growth to justify it.";
                case FISHER -> "Do not score 70+ when revenue grew less than 3% a year.";
                case GILL -> "Do not score 70+ when the stock is not cheap (P/B above 2.5 and little free cash flow).";
                case RISK -> "Do not give a safety score of 70+ with debt/equity above 2, Altman Z below 1.8, interest coverage below 1.5 or recent losses.";
            };
            default -> null;
        };
    }
}
