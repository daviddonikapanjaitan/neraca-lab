package com.neracalab.backend.technical.screening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.neracalab.backend.technical.screening.TechnicalValidator.Issue;

/**
 * Reflexion memory of the technical agents ({@code technical_screening_lesson}): an issue kind the validator found at
 * least {@value #MIN_OCCURRENCES} times in an agent's answers of one run becomes a lesson that is added to that agent's
 * prompt in later runs; its occurrence count grows with every run that repeats it. Separate from the investor agents'
 * {@code screening_lesson}.
 */
@Component
public class TechnicalLessons {

    static final int MIN_OCCURRENCES = 2;
    /** Most lessons of an agent added to its prompt (the most frequent first). */
    static final int MAX_LESSONS = 3;

    /** A lesson learned in a run (for the report). */
    public record Learned(String agent, String lesson, int occurrencesInRun) {
    }

    private final JdbcClient jdbc;

    public TechnicalLessons(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The active lessons of an agent, the most frequent first. */
    public List<String> lessons(TechnicalAgent agent) {
        return jdbc.sql("""
                        SELECT lesson FROM technical_screening_lesson WHERE agent = :agent AND active
                        ORDER BY occurrences DESC, updated_at DESC LIMIT :n""")
                .param("agent", agent.name())
                .param("n", MAX_LESSONS)
                .query(String.class).list();
    }

    /** Stores a lesson for every issue kind an agent repeated in a run. */
    public List<Learned> learn(UUID runId, Map<TechnicalAgent, List<Issue>> issues) {
        List<Learned> learned = new ArrayList<>();
        for (Map.Entry<TechnicalAgent, List<Issue>> e : issues.entrySet()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Issue issue : e.getValue()) {
                counts.merge(issue.code(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                if (c.getValue() < MIN_OCCURRENCES) {
                    continue;
                }
                String lesson = lesson(e.getKey(), c.getKey());
                if (lesson == null) {
                    continue;
                }
                jdbc.sql("""
                                INSERT INTO technical_screening_lesson (agent, lesson, occurrences, source_run)
                                VALUES (:agent, :lesson, :n, :run)
                                ON CONFLICT (agent, lesson) DO UPDATE SET
                                    occurrences = technical_screening_lesson.occurrences + EXCLUDED.occurrences,
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
    static String lesson(TechnicalAgent agent, String code) {
        return switch (code) {
            case "DIVERGENCE" -> "Your scores often departed from your rules summary by more than "
                    + Math.round(TechnicalValidator.MAX_DIVERGENCE) + " points; depart from it only for reasons you can "
                    + "point to in the chart data.";
            case "STANCE" -> "Keep the stance inside its score band: BUY 65-100, ACCUMULATE 50-79, WAIT 35-64, AVOID 0-49.";
            case "LEVELS" -> "Give a complete trade plan with stopLoss < buy < sell: a LIMIT buy at or below the close, a "
                    + "STOP buy above it, at most 40% risk below the buy.";
            case "UNKNOWN_SIGNAL" -> "In signalsUsed cite only keys that appear in the stock dossier.";
            case "EMPTY" -> "Always give a score and a thesis.";
            case "RULE" -> switch (agent) {
                case TUDOR_JONES -> "Do not score 65+ or BUY while the close is below the 200-day average.";
                case MURPHY -> "Do not score 65+ or BUY while the close is below both the SMA 50 and the SMA 200.";
                case DRUCKENMILLER -> "Do not score 70+ for a laggard (6-month return below the median) while IHSG is "
                        + "below its 200-day average.";
                case GILL -> "Do not score 65+ or BUY near the 52-week high without a base.";
            };
            default -> null;
        };
    }
}
