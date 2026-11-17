package com.neracalab.backend.portfolio.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.neracalab.backend.portfolio.analysis.PortfolioAgents.Issue;

/**
 * Reflexion memory of the portfolio agents ({@code portfolio_analysis_lesson}): an issue kind the validator found at
 * least {@value #MIN_OCCURRENCES} times in an agent's answers of one run becomes a lesson added to that agent's prompt
 * in later runs; its occurrence count grows with every run that repeats it.
 */
@Component
public class PortfolioLessons {

    static final int MIN_OCCURRENCES = 2;
    /** Most lessons of an agent added to its prompt (the most frequent first). */
    static final int MAX_LESSONS = 3;

    /** A lesson learned in a run (for the report). */
    public record Learned(String agent, String lesson, int occurrencesInRun) {
    }

    private final JdbcClient jdbc;

    public PortfolioLessons(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The active lessons of an agent (FUNDAMENTAL or TECHNICAL), the most frequent first. */
    public List<String> lessons(String agent) {
        return jdbc.sql("""
                        SELECT lesson FROM portfolio_analysis_lesson WHERE agent = :agent AND active
                        ORDER BY occurrences DESC, updated_at DESC LIMIT :n""")
                .param("agent", agent).param("n", MAX_LESSONS)
                .query(String.class).list();
    }

    /** Stores a lesson for every issue kind an agent repeated in a run. */
    public List<Learned> learn(UUID runId, Map<String, List<Issue>> issues) {
        List<Learned> learned = new ArrayList<>();
        for (Map.Entry<String, List<Issue>> e : issues.entrySet()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            e.getValue().forEach(i -> counts.merge(i.kind(), 1, Integer::sum));
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                String lesson = lesson(e.getKey(), c.getKey());
                if (c.getValue() < MIN_OCCURRENCES || lesson == null) {
                    continue;
                }
                jdbc.sql("""
                                INSERT INTO portfolio_analysis_lesson (agent, issue_kind, lesson, occurrences,
                                    first_run_id, last_run_id)
                                VALUES (:agent, :kind, :lesson, :n, :run, :run)
                                ON CONFLICT (agent, issue_kind) DO UPDATE SET lesson = EXCLUDED.lesson,
                                    occurrences = portfolio_analysis_lesson.occurrences + EXCLUDED.occurrences,
                                    last_run_id = EXCLUDED.last_run_id, updated_at = now()""")
                        .param("agent", e.getKey()).param("kind", c.getKey()).param("lesson", lesson)
                        .param("n", c.getValue()).param("run", runId)
                        .update();
                learned.add(new Learned(e.getKey(), lesson, c.getValue()));
            }
        }
        return learned;
    }

    /** The lesson text of an issue kind; null for kinds that teach nothing. */
    static String lesson(String agent, String kind) {
        boolean fundamental = PortfolioAgents.FUNDAMENTAL.equals(agent);
        return switch (kind) {
            case "DIVERGENCE" -> "Your scores often departed from the rules " + (fundamental ? "Fundamental" : "Technical")
                    + " (Score) by more than " + Math.round(PortfolioAgents.MAX_DIVERGENCE) + " points; depart from it "
                    + "only for reasons you can point to in the dossier.";
            case "VERDICT" -> "Keep the verdict consistent with the score: PASS from 50, FAIL below 65.";
            case "RULE" -> "Do not PASS a company whose Risk scorecard is below 30.";
            case "STANCE" -> "Keep the stance inside its score band: BUY 65-100, ACCUMULATE 50-79, WAIT 35-64, AVOID 0-49.";
            case "LEVELS" -> "Give a complete plan with stopLoss < entry < target: a LIMIT entry at or below the close, a "
                    + "STOP entry above it, at most 40% risk below the entry.";
            case "EMPTY" -> fundamental ? "Always give a score, a verdict PASS or FAIL and a thesis."
                    : "Always give a score, a stance and a thesis.";
            default -> null;
        };
    }
}
