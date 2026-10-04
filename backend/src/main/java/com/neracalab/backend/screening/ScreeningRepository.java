package com.neracalab.backend.screening;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.screening.ScreeningViews.AgentScoreView;
import com.neracalab.backend.screening.ScreeningViews.CandidateView;
import com.neracalab.backend.screening.ScreeningViews.RunSummary;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code screening_run}, {@code screening_candidate}, {@code screening_agent_score} and
 * {@code llm_usage}. Status and progress of a run are those of its {@code ingestion_job} row
 * (same id). JSON columns are written from objects and read as trees.
 */
@Repository
public class ScreeningRepository {

    private static final Logger log = LoggerFactory.getLogger(ScreeningRepository.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ScreeningRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Parameters of a run, read by the worker. */
    public record RunParameters(UUID id, String exchange, MarketCapTier tier, int topN, List<InvestorAgent> agents,
                                double budgetUsd) {
    }

    // ------------------------------------------------------------------ run

    public void insertRun(RunParameters p) {
        jdbc.sql("""
                        INSERT INTO screening_run (run_id, exchange, market_cap_tier, top_n, agents, budget_usd)
                        VALUES (:id, :exchange, :tier, :topN, CAST(:agents AS jsonb), :budget)""")
                .param("id", p.id())
                .param("exchange", p.exchange())
                .param("tier", p.tier().name())
                .param("topN", p.topN())
                .param("agents", toJson(p.agents().stream().map(Enum::name).toList()))
                .param("budget", p.budgetUsd())
                .update();
    }

    public Optional<RunParameters> parameters(UUID id) {
        return jdbc.sql("SELECT run_id, exchange, market_cap_tier, top_n, agents::text AS agents, budget_usd FROM screening_run WHERE run_id = :id")
                .param("id", id)
                .query((rs, i) -> new RunParameters(id, rs.getString("exchange"),
                        MarketCapTier.valueOf(rs.getString("market_cap_tier")), rs.getInt("top_n"),
                        agents(rs.getString("agents")), dbl(rs, "budget_usd")))
                .optional();
    }

    public void saveStage1(UUID id, LocalDate snapshotDate, int universe, int eligible, int shortlist, Object funnel) {
        jdbc.sql("""
                        UPDATE screening_run SET snapshot_date = :date, universe_count = :universe,
                            eligible_count = :eligible, shortlist_count = :shortlist, funnel = CAST(:funnel AS jsonb),
                            updated_at = now()
                        WHERE run_id = :id""")
                .param("id", id)
                .param("date", snapshotDate, Types.DATE)
                .param("universe", universe)
                .param("eligible", eligible)
                .param("shortlist", shortlist)
                .param("funnel", toJson(funnel))
                .update();
    }

    public void saveTotals(UUID id, UsageMeter.Totals totals) {
        jdbc.sql("""
                        UPDATE screening_run SET cost_usd = :cost, prompt_tokens = :prompt, completion_tokens = :completion,
                            reasoning_tokens = :reasoning, cached_tokens = :cached, model_calls = :calls, updated_at = now()
                        WHERE run_id = :id""")
                .param("id", id)
                .param("cost", totals.costUsd())
                .param("prompt", totals.promptTokens())
                .param("completion", totals.completionTokens())
                .param("reasoning", totals.reasoningTokens())
                .param("cached", totals.cachedTokens())
                .param("calls", totals.calls())
                .update();
    }

    public void saveResult(UUID id, int selected, Object synthesis, Object notes) {
        jdbc.sql("""
                        UPDATE screening_run SET selected_count = :selected, synthesis = CAST(:synthesis AS jsonb),
                            notes = CAST(:notes AS jsonb), updated_at = now()
                        WHERE run_id = :id""")
                .param("id", id)
                .param("selected", selected)
                .param("synthesis", toJson(synthesis), Types.VARCHAR)
                .param("notes", toJson(notes), Types.VARCHAR)
                .update();
    }

    public void saveNotes(UUID id, Object notes) {
        jdbc.sql("UPDATE screening_run SET notes = CAST(:notes AS jsonb), updated_at = now() WHERE run_id = :id")
                .param("id", id).param("notes", toJson(notes), Types.VARCHAR).update();
    }

    // ------------------------------------------------------------------ candidates

    public long insertCandidate(UUID runId, String ticker, String companyName, String sector, String industry,
                                Object metrics, double quantOverall, int quantRank) {
        return jdbc.sql("""
                        INSERT INTO screening_candidate (run_id, ticker, company_name, sector, industry, metrics,
                                                         quant_overall, quant_rank)
                        VALUES (:run, :ticker, :name, :sector, :industry, CAST(:metrics AS jsonb), :quant, :rank)
                        RETURNING candidate_id""")
                .param("run", runId)
                .param("ticker", ticker)
                .param("name", companyName)
                .param("sector", sector, Types.VARCHAR)
                .param("industry", industry, Types.VARCHAR)
                .param("metrics", toJson(metrics))
                .param("quant", quantOverall)
                .param("rank", quantRank)
                .query(Long.class).single();
    }

    public void saveNews(long candidateId, Object news) {
        jdbc.sql("UPDATE screening_candidate SET news = CAST(:news AS jsonb) WHERE candidate_id = :id")
                .param("id", candidateId).param("news", toJson(news), Types.VARCHAR).update();
    }

    public void saveAgentScore(long candidateId, InvestorAgent agent, double quantScore, Object quantDetail, Double llmScore,
                               double finalScore, String verdict, String thesis, Object strengths, Object concerns,
                               Object reflection, String status) {
        jdbc.sql("""
                        INSERT INTO screening_agent_score (candidate_id, agent, quant_score, quant_detail, llm_score,
                            final_score, verdict, thesis, strengths, concerns, reflection, status)
                        VALUES (:candidate, :agent, :quant, CAST(:detail AS jsonb), :llm, :final, :verdict, :thesis,
                                CAST(:strengths AS jsonb), CAST(:concerns AS jsonb), CAST(:reflection AS jsonb), :status)
                        ON CONFLICT (candidate_id, agent) DO UPDATE SET
                            quant_score = EXCLUDED.quant_score, quant_detail = EXCLUDED.quant_detail,
                            llm_score = EXCLUDED.llm_score, final_score = EXCLUDED.final_score,
                            verdict = EXCLUDED.verdict, thesis = EXCLUDED.thesis, strengths = EXCLUDED.strengths,
                            concerns = EXCLUDED.concerns, reflection = EXCLUDED.reflection, status = EXCLUDED.status""")
                .param("candidate", candidateId)
                .param("agent", agent.name())
                .param("quant", quantScore)
                .param("detail", toJson(quantDetail), Types.VARCHAR)
                .param("llm", llmScore, Types.NUMERIC)
                .param("final", finalScore)
                .param("verdict", verdict, Types.VARCHAR)
                .param("thesis", thesis, Types.VARCHAR)
                .param("strengths", toJson(strengths), Types.VARCHAR)
                .param("concerns", toJson(concerns), Types.VARCHAR)
                .param("reflection", toJson(reflection), Types.VARCHAR)
                .param("status", status)
                .update();
    }

    public void saveFinal(long candidateId, double overall, Double adjustment, Integer finalRank, boolean selected,
                          String conviction, String thesis, Object redFlags) {
        jdbc.sql("""
                        UPDATE screening_candidate SET overall_score = :overall, synthesis_adjustment = :adjustment,
                            final_rank = :rank, selected = :selected, conviction = :conviction, thesis = :thesis,
                            red_flags = CAST(:flags AS jsonb)
                        WHERE candidate_id = :id""")
                .param("id", candidateId)
                .param("overall", overall)
                .param("adjustment", adjustment, Types.NUMERIC)
                .param("rank", finalRank, Types.INTEGER)
                .param("selected", selected)
                .param("conviction", conviction, Types.VARCHAR)
                .param("thesis", thesis, Types.VARCHAR)
                .param("flags", toJson(redFlags), Types.VARCHAR)
                .update();
    }

    // ------------------------------------------------------------------ usage

    public void insertUsage(UUID runId, UsageMeter.Usage u) {
        jdbc.sql("""
                        INSERT INTO llm_usage (run_id, stage, agent, ticker, model, prompt_tokens, completion_tokens,
                            reasoning_tokens, cached_tokens, cost_usd, cost_estimated, duration_ms, error)
                        VALUES (:run, :stage, :agent, :ticker, :model, :prompt, :completion, :reasoning, :cached, :cost,
                                :estimated, :ms, :error)""")
                .param("run", runId)
                .param("stage", u.stage())
                .param("agent", u.agent(), Types.VARCHAR)
                .param("ticker", u.ticker(), Types.VARCHAR)
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

    public List<UsageRow> usage(UUID runId) {
        return jdbc.sql("""
                        SELECT stage, model, count(*) AS calls, sum(prompt_tokens) AS prompt, sum(completion_tokens) AS completion,
                               sum(reasoning_tokens) AS reasoning, sum(cached_tokens) AS cached, sum(cost_usd) AS cost,
                               bool_or(cost_estimated) AS estimated, count(*) FILTER (WHERE error IS NOT NULL) AS errors,
                               min(usage_id) AS first
                        FROM llm_usage WHERE run_id = :run
                        GROUP BY stage, model ORDER BY first""")
                .param("run", runId)
                .query((rs, i) -> new UsageRow(rs.getString("stage"), rs.getString("model"), rs.getInt("calls"),
                        rs.getLong("prompt"), rs.getLong("completion"), rs.getLong("reasoning"), rs.getLong("cached"),
                        dbl(rs, "cost"), rs.getBoolean("estimated"), rs.getInt("errors")))
                .list();
    }

    // ------------------------------------------------------------------ reads

    private static final String RUN_SELECT = """
            SELECT r.run_id, j.status, j.stage, j.message, r.exchange, r.market_cap_tier, r.top_n, r.agents::text AS agents,
                   r.snapshot_date, r.universe_count, r.eligible_count, r.shortlist_count, r.selected_count, r.budget_usd,
                   r.cost_usd, r.prompt_tokens, r.completion_tokens, r.reasoning_tokens, r.cached_tokens, r.model_calls,
                   j.requested_at, j.started_at, j.finished_at, j.created_by, j.created_by_username,
                   u.full_name AS created_by_full_name
            FROM screening_run r
            JOIN ingestion_job j ON j.job_id = r.run_id
            LEFT JOIN users u ON u.user_id = j.created_by""";

    public List<RunSummary> list(int limit) {
        return jdbc.sql(RUN_SELECT + " ORDER BY j.requested_at DESC, r.run_id LIMIT :limit")
                .param("limit", limit)
                .query((rs, i) -> summary(rs))
                .list();
    }

    public Optional<RunSummary> find(UUID id) {
        return jdbc.sql(RUN_SELECT + " WHERE r.run_id = :id")
                .param("id", id)
                .query((rs, i) -> summary(rs))
                .optional();
    }

    /** funnel, synthesis and notes of a run. */
    public Map<String, JsonNode> documents(UUID id) {
        return jdbc.sql("SELECT funnel::text AS funnel, synthesis::text AS synthesis, notes::text AS notes FROM screening_run WHERE run_id = :id")
                .param("id", id)
                .query((rs, i) -> {
                    Map<String, JsonNode> m = new LinkedHashMap<>();
                    m.put("funnel", tree(rs.getString("funnel")));
                    m.put("synthesis", tree(rs.getString("synthesis")));
                    m.put("notes", tree(rs.getString("notes")));
                    return m;
                })
                .optional().orElse(Map.of());
    }

    /** The shortlist of a run: final ranking first, then the other candidates by quantitative rank. */
    public List<CandidateView> candidates(UUID runId) {
        Map<Long, List<AgentScoreView>> scores = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT s.candidate_id, s.agent, s.quant_score, s.quant_detail::text AS quant_detail, s.llm_score,
                               s.final_score, s.verdict, s.thesis, s.strengths::text AS strengths,
                               s.concerns::text AS concerns, s.reflection::text AS reflection, s.status
                        FROM screening_agent_score s
                        JOIN screening_candidate c ON c.candidate_id = s.candidate_id
                        WHERE c.run_id = :run
                        ORDER BY s.candidate_id, s.score_id""")
                .param("run", runId)
                .query((RowCallbackHandler) rs -> {
                    InvestorAgent agent = InvestorAgent.valueOf(rs.getString("agent"));
                    scores.computeIfAbsent(rs.getLong("candidate_id"), k -> new ArrayList<>()).add(new AgentScoreView(
                            agent, agent.label(), dblOrNull(rs, "quant_score"), tree(rs.getString("quant_detail")),
                            dblOrNull(rs, "llm_score"), dblOrNull(rs, "final_score"), rs.getString("verdict"),
                            rs.getString("thesis"), tree(rs.getString("strengths")), tree(rs.getString("concerns")),
                            tree(rs.getString("reflection")), rs.getString("status")));
                });
        return jdbc.sql("""
                        SELECT candidate_id, ticker, company_name, sector, industry, quant_overall, quant_rank,
                               overall_score, synthesis_adjustment, final_rank, selected, conviction, thesis,
                               metrics::text AS metrics, news::text AS news, red_flags::text AS red_flags
                        FROM screening_candidate WHERE run_id = :run
                        ORDER BY final_rank NULLS LAST, quant_rank, ticker""")
                .param("run", runId)
                .query((rs, i) -> {
                    long id = rs.getLong("candidate_id");
                    Integer finalRank = rs.getInt("final_rank");
                    if (rs.wasNull()) {
                        finalRank = null;
                    }
                    Integer quantRank = rs.getInt("quant_rank");
                    if (rs.wasNull()) {
                        quantRank = null;
                    }
                    return new CandidateView(id, rs.getString("ticker"), rs.getString("company_name"),
                            rs.getString("sector"), rs.getString("industry"), dblOrNull(rs, "quant_overall"), quantRank,
                            dblOrNull(rs, "overall_score"), dblOrNull(rs, "synthesis_adjustment"), finalRank,
                            rs.getBoolean("selected"), rs.getString("conviction"), rs.getString("thesis"),
                            tree(rs.getString("metrics")), tree(rs.getString("news")), tree(rs.getString("red_flags")),
                            scores.getOrDefault(id, List.of()));
                })
                .list();
    }

    private RunSummary summary(ResultSet rs) throws SQLException {
        Long createdById = rs.getLong("created_by");
        if (rs.wasNull()) {
            createdById = null;
        }
        String username = rs.getString("created_by_username");
        IngestionJob.CreatedBy createdBy = username == null ? null
                : new IngestionJob.CreatedBy(createdById, username, rs.getString("created_by_full_name"));
        LocalDate snapshot = rs.getObject("snapshot_date", LocalDate.class);
        return new RunSummary(rs.getObject("run_id", UUID.class), IngestionJobStatus.valueOf(rs.getString("status")),
                rs.getString("stage"), rs.getString("message"), rs.getString("exchange"),
                MarketCapTier.valueOf(rs.getString("market_cap_tier")), rs.getInt("top_n"), agents(rs.getString("agents")),
                snapshot == null ? null : snapshot.toString(), intOrNull(rs, "universe_count"),
                intOrNull(rs, "eligible_count"), intOrNull(rs, "shortlist_count"), intOrNull(rs, "selected_count"),
                dbl(rs, "budget_usd"), dbl(rs, "cost_usd"), rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                rs.getLong("reasoning_tokens"), rs.getLong("cached_tokens"), rs.getInt("model_calls"),
                instant(rs, "requested_at"), instant(rs, "started_at"), instant(rs, "finished_at"), createdBy);
    }

    // ------------------------------------------------------------------ helpers

    private List<InvestorAgent> agents(String text) {
        if (text == null) {
            return List.of();
        }
        try {
            List<String> names = json.readValue(text, new TypeReference<List<String>>() { });
            return names.stream().map(InvestorAgent::valueOf).toList();
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("stored agents unreadable: {}", text);
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

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
