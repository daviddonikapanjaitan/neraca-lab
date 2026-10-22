package com.neracalab.backend.macro.analysis;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAgentView;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAnalysisSummary;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code macro_analysis_run}, {@code macro_analysis_agent_score} and the macro analysis rows of {@code llm_usage}.
 * Status and progress of an analysis are those of its {@code ingestion_job} row (same id). JSON columns are written from
 * objects and read as trees.
 */
@Repository
public class MacroAnalysisRepository {

    private static final Logger log = LoggerFactory.getLogger(MacroAnalysisRepository.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public MacroAnalysisRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Parameters of an analysis, read by the worker. */
    public record Parameters(UUID id, String country, String indexCode, List<MacroAgent> agents, double budgetUsd) {
    }

    /** The final result of an analysis. */
    public record Result(Double rulesScore, Double overallScore, Double adjustment, String verdict, String action,
                         String conviction, Object synthesis, Object notes) {
    }

    /** One agent's row. */
    public record AgentRow(MacroAgent agent, Double priorScore, Object priorDetail, Double llmScore, Double finalScore,
                           String stance, String thesis, Object supports, Object risks, Object buyWhen, Object sellWhen,
                           Object reflection, String status) {
    }

    // ------------------------------------------------------------------ writes

    public void insertRun(Parameters p, String countryName, String indexName) {
        jdbc.sql("""
                        INSERT INTO macro_analysis_run (analysis_id, country, country_name, index_code, index_name, agents,
                                                        budget_usd)
                        VALUES (:id, :country, :countryName, :index, :indexName, CAST(:agents AS jsonb), :budget)""")
                .param("id", p.id())
                .param("country", p.country())
                .param("countryName", countryName, Types.VARCHAR)
                .param("index", p.indexCode())
                .param("indexName", indexName, Types.VARCHAR)
                .param("agents", toJson(p.agents().stream().map(Enum::name).toList()))
                .param("budget", p.budgetUsd())
                .update();
    }

    public Optional<Parameters> parameters(UUID id) {
        return jdbc.sql("""
                        SELECT country, index_code, agents::text AS agents, budget_usd
                        FROM macro_analysis_run WHERE analysis_id = :id""")
                .param("id", id)
                .query((rs, i) -> new Parameters(id, rs.getString("country"), rs.getString("index_code"),
                        agents(rs.getString("agents")), dbl(rs, "budget_usd")))
                .optional();
    }

    /** What the agents saw, the as-of date and the rules score. */
    public void saveContext(UUID id, Object context, LocalDate asOf, Double rulesScore) {
        jdbc.sql("""
                        UPDATE macro_analysis_run SET context = CAST(:context AS jsonb), as_of_date = :asOf,
                            rules_score = :rules, updated_at = now()
                        WHERE analysis_id = :id""")
                .param("id", id)
                .param("context", toJson(context), Types.VARCHAR)
                .param("asOf", asOf, Types.DATE)
                .param("rules", rulesScore, Types.NUMERIC)
                .update();
    }

    public void saveResearch(UUID id, Object research) {
        jdbc.sql("UPDATE macro_analysis_run SET research = CAST(:research AS jsonb), updated_at = now() WHERE analysis_id = :id")
                .param("id", id).param("research", toJson(research), Types.VARCHAR).update();
    }

    public void saveAgentScore(UUID id, AgentRow a) {
        jdbc.sql("""
                        INSERT INTO macro_analysis_agent_score (analysis_id, agent, prior_score, prior_detail, llm_score,
                            final_score, stance, thesis, supports, risks, buy_when, sell_when, reflection, status)
                        VALUES (:id, :agent, :prior, CAST(:detail AS jsonb), :llm, :final, :stance, :thesis,
                                CAST(:supports AS jsonb), CAST(:risks AS jsonb), CAST(:buy AS jsonb), CAST(:sell AS jsonb),
                                CAST(:reflection AS jsonb), :status)
                        ON CONFLICT (analysis_id, agent) DO UPDATE SET
                            prior_score = EXCLUDED.prior_score, prior_detail = EXCLUDED.prior_detail,
                            llm_score = EXCLUDED.llm_score, final_score = EXCLUDED.final_score, stance = EXCLUDED.stance,
                            thesis = EXCLUDED.thesis, supports = EXCLUDED.supports, risks = EXCLUDED.risks,
                            buy_when = EXCLUDED.buy_when, sell_when = EXCLUDED.sell_when,
                            reflection = EXCLUDED.reflection, status = EXCLUDED.status""")
                .param("id", id)
                .param("agent", a.agent().name())
                .param("prior", a.priorScore(), Types.NUMERIC)
                .param("detail", toJson(a.priorDetail()), Types.VARCHAR)
                .param("llm", a.llmScore(), Types.NUMERIC)
                .param("final", a.finalScore(), Types.NUMERIC)
                .param("stance", a.stance(), Types.VARCHAR)
                .param("thesis", a.thesis(), Types.VARCHAR)
                .param("supports", toJson(a.supports()), Types.VARCHAR)
                .param("risks", toJson(a.risks()), Types.VARCHAR)
                .param("buy", toJson(a.buyWhen()), Types.VARCHAR)
                .param("sell", toJson(a.sellWhen()), Types.VARCHAR)
                .param("reflection", toJson(a.reflection()), Types.VARCHAR)
                .param("status", a.status())
                .update();
    }

    public void saveResult(UUID id, Result r) {
        jdbc.sql("""
                        UPDATE macro_analysis_run SET rules_score = :rules, overall_score = :overall,
                            synthesis_adjustment = :adjustment, verdict = :verdict, action = :action, conviction = :conviction,
                            synthesis = CAST(:synthesis AS jsonb), notes = CAST(:notes AS jsonb), updated_at = now()
                        WHERE analysis_id = :id""")
                .param("id", id)
                .param("rules", r.rulesScore(), Types.NUMERIC)
                .param("overall", r.overallScore(), Types.NUMERIC)
                .param("adjustment", r.adjustment(), Types.NUMERIC)
                .param("verdict", r.verdict(), Types.VARCHAR)
                .param("action", r.action(), Types.VARCHAR)
                .param("conviction", r.conviction(), Types.VARCHAR)
                .param("synthesis", toJson(r.synthesis()), Types.VARCHAR)
                .param("notes", toJson(r.notes()), Types.VARCHAR)
                .update();
    }

    public void saveNotes(UUID id, Object notes) {
        jdbc.sql("UPDATE macro_analysis_run SET notes = CAST(:notes AS jsonb), updated_at = now() WHERE analysis_id = :id")
                .param("id", id).param("notes", toJson(notes), Types.VARCHAR).update();
    }

    public void saveTotals(UUID id, UsageMeter.Totals totals) {
        jdbc.sql("""
                        UPDATE macro_analysis_run SET cost_usd = :cost, prompt_tokens = :prompt, completion_tokens = :completion,
                            reasoning_tokens = :reasoning, cached_tokens = :cached, model_calls = :calls, updated_at = now()
                        WHERE analysis_id = :id""")
                .param("id", id)
                .param("cost", totals.costUsd())
                .param("prompt", totals.promptTokens())
                .param("completion", totals.completionTokens())
                .param("reasoning", totals.reasoningTokens())
                .param("cached", totals.cachedTokens())
                .param("calls", totals.calls())
                .update();
    }

    // ------------------------------------------------------------------ usage

    public void insertUsage(UUID analysisId, UsageMeter.Usage u) {
        jdbc.sql("""
                        INSERT INTO llm_usage (macro_analysis_id, stage, agent, ticker, model, prompt_tokens, completion_tokens,
                            reasoning_tokens, cached_tokens, cost_usd, cost_estimated, duration_ms, error)
                        VALUES (:analysis, :stage, :agent, :ticker, :model, :prompt, :completion, :reasoning, :cached, :cost,
                                :estimated, :ms, :error)""")
                .param("analysis", analysisId)
                .param("stage", u.stage())
                .param("agent", u.agent(), Types.VARCHAR)
                .param("ticker", u.ticker() == null ? null : u.ticker().length() > 20 ? u.ticker().substring(0, 20) : u.ticker(),
                        Types.VARCHAR)
                .param("model", u.model())
                .param("prompt", u.promptTokens())
                .param("completion", u.completionTokens())
                .param("reasoning", u.reasoningTokens())
                .param("cached", u.cachedTokens())
                .param("cost", u.costUsd())
                .param("estimated", u.costEstimated())
                .param("ms", (int) Math.min(Integer.MAX_VALUE, u.durationMs()))
                .param("error", u.error(), Types.VARCHAR)
                .update();
    }

    /** Model usage of an analysis per stage and model, in order of first use. */
    public List<UsageRow> usage(UUID analysisId) {
        return jdbc.sql("""
                        SELECT stage, model, count(*) AS calls, sum(prompt_tokens) AS prompt, sum(completion_tokens) AS completion,
                               sum(reasoning_tokens) AS reasoning, sum(cached_tokens) AS cached, sum(cost_usd) AS cost,
                               bool_or(cost_estimated) AS estimated, count(*) FILTER (WHERE error IS NOT NULL) AS errors,
                               min(usage_id) AS first
                        FROM llm_usage WHERE macro_analysis_id = :id
                        GROUP BY stage, model ORDER BY first""")
                .param("id", analysisId)
                .query((rs, i) -> new UsageRow(rs.getString("stage"), rs.getString("model"), rs.getInt("calls"),
                        rs.getLong("prompt"), rs.getLong("completion"), rs.getLong("reasoning"), rs.getLong("cached"),
                        dbl(rs, "cost"), rs.getBoolean("estimated"), rs.getInt("errors")))
                .list();
    }

    // ------------------------------------------------------------------ reads

    private static final String RUN_SELECT = """
            SELECT r.analysis_id, j.status, j.stage, j.message, r.country, r.country_name, r.index_code, r.index_name,
                   r.agents::text AS agents, r.overall_score, r.rules_score, r.verdict, r.action, r.conviction, r.as_of_date,
                   r.budget_usd, r.cost_usd, r.prompt_tokens, r.completion_tokens, r.reasoning_tokens, r.cached_tokens,
                   r.model_calls, j.requested_at, j.started_at, j.finished_at, j.created_by, j.created_by_username,
                   u.full_name AS created_by_full_name
            FROM macro_analysis_run r
            JOIN ingestion_job j ON j.job_id = r.analysis_id
            LEFT JOIN users u ON u.user_id = j.created_by""";

    /** One page of analyses, most recent first; of one country and index when given. */
    public List<MacroAnalysisSummary> list(String country, String index, int limit, int offset) {
        return jdbc.sql(RUN_SELECT + """

                        WHERE (CAST(:country AS varchar) IS NULL OR r.country = :country)
                          AND (CAST(:index AS varchar) IS NULL OR r.index_code = :index)
                        ORDER BY j.requested_at DESC, r.analysis_id
                        LIMIT :limit OFFSET :offset""")
                .param("country", country, Types.VARCHAR)
                .param("index", index, Types.VARCHAR)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> summary(rs))
                .list();
    }

    public long count(String country, String index) {
        return jdbc.sql("""
                        SELECT count(*) FROM macro_analysis_run
                        WHERE (CAST(:country AS varchar) IS NULL OR country = :country)
                          AND (CAST(:index AS varchar) IS NULL OR index_code = :index)""")
                .param("country", country, Types.VARCHAR)
                .param("index", index, Types.VARCHAR)
                .query(Long.class).single();
    }

    public Optional<MacroAnalysisSummary> find(UUID id) {
        return jdbc.sql(RUN_SELECT + " WHERE r.analysis_id = :id")
                .param("id", id)
                .query((rs, i) -> summary(rs))
                .optional();
    }

    /** The stored result of an analysis besides its summary and agent scores. */
    public record Documents(Double synthesisAdjustment, JsonNode context, JsonNode research, JsonNode synthesis,
                            JsonNode notes) {

        static final Documents EMPTY = new Documents(null, null, null, null, null);
    }

    public Documents documents(UUID id) {
        return jdbc.sql("""
                        SELECT synthesis_adjustment, context::text AS context, research::text AS research,
                               synthesis::text AS synthesis, notes::text AS notes
                        FROM macro_analysis_run WHERE analysis_id = :id""")
                .param("id", id)
                .query((rs, i) -> new Documents(dblOrNull(rs, "synthesis_adjustment"), tree(rs.getString("context")),
                        tree(rs.getString("research")), tree(rs.getString("synthesis")), tree(rs.getString("notes"))))
                .optional().orElse(Documents.EMPTY);
    }

    /** The agents' views in the order of the agents (Tudor Jones ... Dalio). */
    public List<MacroAgentView> agentScores(UUID id) {
        return jdbc.sql("""
                        SELECT agent, prior_score, prior_detail::text AS prior_detail, llm_score, final_score, stance, thesis,
                               supports::text AS supports, risks::text AS risks, buy_when::text AS buy_when,
                               sell_when::text AS sell_when, reflection::text AS reflection, status
                        FROM macro_analysis_agent_score WHERE analysis_id = :id""")
                .param("id", id)
                .query((rs, i) -> {
                    MacroAgent agent = MacroAgent.valueOf(rs.getString("agent"));
                    return new MacroAgentView(agent, agent.label(), agent.focus(), dblOrNull(rs, "prior_score"),
                            tree(rs.getString("prior_detail")), dblOrNull(rs, "llm_score"), dblOrNull(rs, "final_score"),
                            rs.getString("stance"), rs.getString("thesis"), tree(rs.getString("supports")),
                            tree(rs.getString("risks")), tree(rs.getString("buy_when")), tree(rs.getString("sell_when")),
                            tree(rs.getString("reflection")), rs.getString("status"));
                })
                .list().stream()
                .sorted(Comparator.comparingInt(s -> s.agent().ordinal()))
                .toList();
    }

    private MacroAnalysisSummary summary(ResultSet rs) throws SQLException {
        Long createdById = rs.getLong("created_by");
        if (rs.wasNull()) {
            createdById = null;
        }
        String username = rs.getString("created_by_username");
        IngestionJob.CreatedBy createdBy = username == null ? null
                : new IngestionJob.CreatedBy(createdById, username, rs.getString("created_by_full_name"));
        LocalDate asOf = rs.getObject("as_of_date", LocalDate.class);
        return new MacroAnalysisSummary(rs.getObject("analysis_id", UUID.class),
                IngestionJobStatus.valueOf(rs.getString("status")), rs.getString("stage"), rs.getString("message"),
                rs.getString("country"), rs.getString("country_name"), rs.getString("index_code"),
                rs.getString("index_name"), agents(rs.getString("agents")), dblOrNull(rs, "overall_score"),
                dblOrNull(rs, "rules_score"), rs.getString("verdict"), rs.getString("action"), rs.getString("conviction"),
                asOf == null ? null : asOf.toString(), dbl(rs, "budget_usd"), dbl(rs, "cost_usd"),
                rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("reasoning_tokens"),
                rs.getLong("cached_tokens"), rs.getInt("model_calls"), instant(rs, "requested_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), createdBy);
    }

    // ------------------------------------------------------------------ helpers

    private List<MacroAgent> agents(String text) {
        if (text == null) {
            return List.of();
        }
        try {
            List<String> names = json.readValue(text, new TypeReference<List<String>>() { });
            return names.stream().map(MacroAgent::valueOf).toList();
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("stored macro agents unreadable: {}", text);
            return List.of();
        }
    }

    String toJson(Object value) {
        if (value == null) {
            return null;
        }
        String text = json.writeValueAsString(value);
        return text.indexOf("\\u0000") < 0 ? text : text.replace("\\u0000", "");
    }

    private JsonNode tree(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static double dbl(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? 0 : value.doubleValue();
    }

    private static Double dblOrNull(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
