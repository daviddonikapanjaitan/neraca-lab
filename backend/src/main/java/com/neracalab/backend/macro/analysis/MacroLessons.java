package com.neracalab.backend.macro.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.neracalab.backend.macro.analysis.MacroValidator.Issue;

/**
 * Reflexion memory of the macro agents ({@code macro_analysis_lesson}): an issue kind the validator found in an
 * agent's answer becomes a lesson that is added to that agent's prompt in later analyses (one answer per agent and
 * analysis, so one occurrence suffices). Separate from the stock agents' {@code screening_lesson}.
 */
@Component
public class MacroLessons {

    /** Most lessons of an agent added to its prompt (the most frequent first). */
    static final int MAX_LESSONS = 4;

    /** A lesson learned in an analysis (for the report). */
    public record Learned(String agent, String lesson) {
    }

    private final JdbcClient jdbc;

    public MacroLessons(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The active lessons of an agent, the most frequent first. */
    public List<String> lessons(MacroAgent agent) {
        return jdbc.sql("""
                        SELECT lesson FROM macro_analysis_lesson WHERE agent = :agent AND active
                        ORDER BY occurrences DESC, updated_at DESC LIMIT :n""")
                .param("agent", agent.name())
                .param("n", MAX_LESSONS)
                .query(String.class).list();
    }

    /** Stores a lesson for every issue kind an agent showed in an analysis. */
    public List<Learned> learn(UUID analysisId, Map<MacroAgent, List<Issue>> issues) {
        List<Learned> learned = new ArrayList<>();
        for (Map.Entry<MacroAgent, List<Issue>> e : issues.entrySet()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Issue issue : e.getValue()) {
                counts.merge(issue.code(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> c : counts.entrySet()) {
                String lesson = lesson(e.getKey(), c.getKey());
                if (lesson == null) {
                    continue;
                }
                jdbc.sql("""
                                INSERT INTO macro_analysis_lesson (agent, lesson, occurrences, source_analysis)
                                VALUES (:agent, :lesson, :n, :analysis)
                                ON CONFLICT (agent, lesson) DO UPDATE SET
                                    occurrences = macro_analysis_lesson.occurrences + EXCLUDED.occurrences,
                                    source_analysis = EXCLUDED.source_analysis, active = TRUE, updated_at = now()""")
                        .param("agent", e.getKey().name()).param("lesson", lesson).param("n", c.getValue())
                        .param("analysis", analysisId)
                        .update();
                learned.add(new Learned(e.getKey().name(), lesson));
            }
        }
        return learned;
    }

    /** The lesson text of an issue kind; null for kinds that teach nothing. */
    static String lesson(MacroAgent agent, String code) {
        return switch (code) {
            case "DIVERGENCE" -> "Your score departed from your rules prior by more than "
                    + Math.round(MacroValidator.MAX_DIVERGENCE) + " points; depart from it only for reasons you can point "
                    + "to in the dossier or the research brief.";
            case "STANCE" -> "Keep the stance inside its score band: BUY 65-100, ACCUMULATE 50-79, WAIT 35-64, AVOID 0-49.";
            case "UNKNOWN_SIGNAL" -> "In signalsUsed cite only keys and codes that appear in the macro dossier.";
            case "EMPTY" -> "Always give a score and a thesis.";
            case "RULE" -> switch (agent) {
                case TUDOR_JONES -> "Do not score 65+ or BUY while the index is below its 200-day average.";
                case DRUCKENMILLER -> "Do not score 70+ while the liquidity regime is tightening unless money is visibly turning easier.";
                case SOROS -> "Do not score 70+ while the reflexive loop is vicious unless it is visibly turning.";
                case DALIO -> "Do not score 65+ or BUY in stagflation.";
            };
            default -> null;
        };
    }
}
