package com.neracalab.backend.portfolio.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisViews.Candidate;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisViews.RunSummary;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Portfolio &gt; Analysis: the runs, their stocks and their model calls ({@code portfolio_analysis_run},
 * {@code portfolio_analysis_candidate}, {@code llm_usage.portfolio_analysis_id}; V1.0.60), and the company names and
 * sectors the steps show.
 */
@Repository
public class PortfolioAnalysisRepository {

    /**
     * What a run was asked for.
     *
     * @param steps FUNDAMENTAL_TECHNICAL (the fundamental step then the technical step) or FUNDAMENTAL (only the
     *              fundamental step and agent)
     */
    public record RunParameters(UUID id, long userId, String exchange, String mode, List<String> tickers,
                                int maxStocks, double addedCapital, double budgetUsd, String steps) {

        public boolean selected() {
            return "SELECTED".equals(mode);
        }

        /** The technical step and agent are part of the run. */
        public boolean technical() {
            return !"FUNDAMENTAL".equals(steps);
        }
    }

    /** A company's name and sector (IDX-IC sector when Yahoo's is missing). */
    public record CompanyInfo(String ticker, String companyName, String sector) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public PortfolioAnalysisRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ writes

    public void insertRun(RunParameters p) {
        jdbc.sql("""
                        INSERT INTO portfolio_analysis_run (run_id, user_id, exchange, mode, tickers, max_stocks,
                            added_capital, budget_usd, steps)
                        VALUES (:id, :user, :exchange, :mode, CAST(:tickers AS jsonb), :max, :added, :budget, :steps)""")
                .param("id", p.id()).param("user", p.userId()).param("exchange", p.exchange())
                .param("mode", p.mode()).param("tickers", p.tickers() == null ? null : write(p.tickers()), Types.VARCHAR)
                .param("max", p.maxStocks()).param("added", p.addedCapital()).param("budget", p.budgetUsd())
                .param("steps", p.steps())
                .update();
    }

    public Optional<RunParameters> parameters(UUID id) {
        return jdbc.sql("""
                        SELECT user_id, exchange, mode, tickers::text AS tickers, max_stocks, added_capital, budget_usd,
                               steps
                        FROM portfolio_analysis_run WHERE run_id = :id""")
                .param("id", id)
                .query((rs, i) -> new RunParameters(id, rs.getLong("user_id"), rs.getString("exchange"),
                        rs.getString("mode"), rs.getString("tickers") == null ? null
                        : json.readValue(rs.getString("tickers"), STRINGS), rs.getInt("max_stocks"),
                        rs.getDouble("added_capital"), rs.getDouble("budget_usd"), rs.getString("steps")))
                .optional();
    }

    public void saveStart(UUID id, Object capital, Object funnel) {
        jdbc.sql("""
                        UPDATE portfolio_analysis_run SET capital = CAST(:capital AS jsonb), funnel = CAST(:funnel AS jsonb)
                        WHERE run_id = :id""")
                .param("id", id).param("capital", write(capital)).param("funnel", write(funnel))
                .update();
    }

    /** Inserts a stock of the run; returns its id. */
    public long insertCandidate(UUID runId, String ticker, String companyName, String sector, boolean held,
                                Object position, Double close, LocalDate priceDate, Double fundamentalScore,
                                String fundamentalVerdict, Integer fundamentalRank, Double technicalScore,
                                String technicalStance, Integer technicalRank, String stage, List<String> redFlags) {
        return jdbc.sql("""
                        INSERT INTO portfolio_analysis_candidate (run_id, ticker, company_name, sector, held, position,
                            close_price, price_date, fundamental_score, fundamental_verdict, fundamental_rank,
                            technical_score, technical_stance, technical_rank, stage, red_flags)
                        VALUES (:run, :ticker, :name, :sector, :held, CAST(:position AS jsonb), :close, :date, :fs,
                            :fv, :fr, :ts, :tst, :tr, :stage, CAST(:flags AS jsonb))
                        RETURNING candidate_id""")
                .param("run", runId).param("ticker", ticker).param("name", companyName, Types.VARCHAR)
                .param("sector", sector, Types.VARCHAR).param("held", held)
                .param("position", position == null ? null : write(position), Types.VARCHAR)
                .param("close", close, Types.NUMERIC).param("date", priceDate, Types.DATE)
                .param("fs", fundamentalScore, Types.NUMERIC).param("fv", fundamentalVerdict, Types.VARCHAR)
                .param("fr", fundamentalRank, Types.INTEGER).param("ts", technicalScore, Types.NUMERIC)
                .param("tst", technicalStance, Types.VARCHAR).param("tr", technicalRank, Types.INTEGER)
                .param("stage", stage).param("flags", write(redFlags == null ? List.of() : redFlags))
                .query(Long.class).single();
    }

    /** The agents' answers, and the last close the technical step read (a holding keeps its position's price). */
    public void saveAgents(long candidateId, Object fundamentalAi, Object technicalAi, Double close, LocalDate date) {
        jdbc.sql("""
                        UPDATE portfolio_analysis_candidate SET fundamental_ai = CAST(:f AS jsonb),
                            technical_ai = CAST(:t AS jsonb), close_price = coalesce(close_price, :close),
                            price_date = coalesce(price_date, :date)
                        WHERE candidate_id = :id""")
                .param("id", candidateId).param("f", fundamentalAi == null ? null : write(fundamentalAi), Types.VARCHAR)
                .param("t", technicalAi == null ? null : write(technicalAi), Types.VARCHAR)
                .param("close", close, Types.NUMERIC).param("date", date, Types.DATE)
                .update();
    }

    public void saveFinal(long candidateId, String stage, Object kelly, String action, Object recommendation,
                          String conviction, String thesis, List<String> redFlags, Integer finalRank) {
        jdbc.sql("""
                        UPDATE portfolio_analysis_candidate SET stage = :stage, kelly = CAST(:kelly AS jsonb),
                            action = :action, recommendation = CAST(:rec AS jsonb), conviction = :conviction,
                            thesis = :thesis, red_flags = CAST(:flags AS jsonb), final_rank = :rank
                        WHERE candidate_id = :id""")
                .param("id", candidateId).param("stage", stage)
                .param("kelly", kelly == null ? null : write(kelly), Types.VARCHAR)
                .param("action", action, Types.VARCHAR)
                .param("rec", recommendation == null ? null : write(recommendation), Types.VARCHAR)
                .param("conviction", conviction, Types.VARCHAR).param("thesis", thesis, Types.VARCHAR)
                .param("flags", write(redFlags == null ? List.of() : redFlags)).param("rank", finalRank, Types.INTEGER)
                .update();
    }

    public void saveResult(UUID id, Object synthesis, Object allocation, Object checks, Object notes) {
        jdbc.sql("""
                        UPDATE portfolio_analysis_run SET synthesis = CAST(:s AS jsonb), allocation = CAST(:a AS jsonb),
                            checks = CAST(:c AS jsonb), notes = CAST(:n AS jsonb)
                        WHERE run_id = :id""")
                .param("id", id).param("s", synthesis == null ? null : write(synthesis), Types.VARCHAR)
                .param("a", allocation == null ? null : write(allocation), Types.VARCHAR)
                .param("c", checks == null ? null : write(checks), Types.VARCHAR)
                .param("n", notes == null ? null : write(notes), Types.VARCHAR)
                .update();
    }

    public void saveNotes(UUID id, Object notes) {
        jdbc.sql("UPDATE portfolio_analysis_run SET notes = CAST(:n AS jsonb) WHERE run_id = :id")
                .param("id", id).param("n", write(notes)).update();
    }

    public void saveTotals(UUID id, UsageMeter.Totals t) {
        jdbc.sql("""
                        UPDATE portfolio_analysis_run SET model_calls = :calls, prompt_tokens = :prompt,
                            completion_tokens = :completion, cost_usd = :cost
                        WHERE run_id = :id""")
                .param("id", id).param("calls", t.calls()).param("prompt", t.promptTokens())
                .param("completion", t.completionTokens()).param("cost", t.costUsd())
                .update();
    }

    public void insertUsage(UUID runId, UsageMeter.Usage u) {
        jdbc.sql("""
                        INSERT INTO llm_usage (portfolio_analysis_id, stage, agent, ticker, model, prompt_tokens,
                            completion_tokens, reasoning_tokens, cached_tokens, cost_usd, cost_estimated, duration_ms, error)
                        VALUES (:run, :stage, :agent, :ticker, :model, :prompt, :completion, :reasoning, :cached, :cost,
                                :estimated, :ms, :error)""")
                .param("run", runId).param("stage", u.stage()).param("agent", u.agent(), Types.VARCHAR)
                .param("ticker", u.ticker(), Types.VARCHAR).param("model", u.model())
                .param("prompt", u.promptTokens()).param("completion", u.completionTokens())
                .param("reasoning", u.reasoningTokens()).param("cached", u.cachedTokens())
                .param("cost", u.costUsd()).param("estimated", u.costEstimated())
                .param("ms", (int) Math.min(Integer.MAX_VALUE, u.durationMs()))
                .param("error", u.error(), Types.VARCHAR)
                .update();
    }

    // ------------------------------------------------------------------ reads

    /** Name and sector of every company of an exchange. */
    public Map<String, CompanyInfo> companies(String exchange) {
        Map<String, CompanyInfo> out = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                        SELECT ticker, company_name, coalesce(nullif(sector, ''), idx_sector) AS sector
                        FROM company WHERE exchange = :e ORDER BY ticker""")
                .param("e", exchange)
                .query((rs, i) -> new CompanyInfo(rs.getString("ticker"), rs.getString("company_name"),
                        rs.getString("sector")))
                .list().forEach(c -> out.put(c.ticker(), c));
        return out;
    }

    private static final String RUN_SELECT = """
            SELECT r.run_id, j.status, j.stage, j.message, r.exchange, r.mode, r.steps, r.tickers::text AS tickers, r.max_stocks,
                   r.added_capital, r.budget_usd, r.cost_usd, r.model_calls, r.prompt_tokens, r.completion_tokens,
                   j.requested_at, j.started_at, j.finished_at, j.created_by, j.created_by_username,
                   u.full_name AS created_by_full_name, (r.capital ->> 'total')::numeric AS capital_total,
                   (SELECT count(*) FROM portfolio_analysis_candidate c
                    WHERE c.run_id = r.run_id AND c.action IS NOT NULL AND c.action <> 'SELL') AS recommended
            FROM portfolio_analysis_run r
            JOIN ingestion_job j ON j.job_id = r.run_id
            LEFT JOIN users u ON u.user_id = j.created_by""";

    /** A user's most recent runs. */
    public List<RunSummary> list(long userId, int limit) {
        return jdbc.sql(RUN_SELECT + " WHERE r.user_id = :u ORDER BY j.requested_at DESC, r.run_id LIMIT :limit")
                .param("u", userId).param("limit", limit)
                .query((rs, i) -> summary(rs)).list();
    }

    /** A user's run (empty: none, or another user's). */
    public Optional<RunSummary> find(long userId, UUID id) {
        return jdbc.sql(RUN_SELECT + " WHERE r.run_id = :id AND r.user_id = :u")
                .param("id", id).param("u", userId)
                .query((rs, i) -> summary(rs)).optional();
    }

    public record Documents(Map<String, Object> capital, Map<String, Object> funnel, Map<String, Object> synthesis,
                            Map<String, Object> allocation, List<Map<String, Object>> checks, Map<String, Object> notes) {
    }

    public Documents documents(UUID id) {
        return jdbc.sql("""
                        SELECT capital::text AS capital, funnel::text AS funnel, synthesis::text AS synthesis,
                               allocation::text AS allocation, checks::text AS checks, notes::text AS notes
                        FROM portfolio_analysis_run WHERE run_id = :id""")
                .param("id", id)
                .query((rs, i) -> new Documents(map(rs, "capital"), map(rs, "funnel"), map(rs, "synthesis"),
                        map(rs, "allocation"), rs.getString("checks") == null ? null
                        : json.readValue(rs.getString("checks"), MAPS), map(rs, "notes")))
                .single();
    }

    public List<Candidate> candidates(UUID runId) {
        return jdbc.sql("""
                        SELECT candidate_id, ticker, company_name, sector, held, position::text AS position, close_price,
                               price_date, fundamental_score, fundamental_verdict, fundamental_rank, technical_score,
                               technical_stance, technical_rank, stage, fundamental_ai::text AS fundamental_ai,
                               technical_ai::text AS technical_ai, kelly::text AS kelly, action,
                               recommendation::text AS recommendation, conviction, thesis, red_flags::text AS red_flags,
                               final_rank
                        FROM portfolio_analysis_candidate WHERE run_id = :run
                        ORDER BY final_rank NULLS LAST, technical_rank NULLS LAST, fundamental_rank NULLS LAST, ticker""")
                .param("run", runId)
                .query((rs, i) -> new Candidate(rs.getLong("candidate_id"), rs.getString("ticker"),
                        rs.getString("company_name"), rs.getString("sector"), rs.getBoolean("held"),
                        map(rs, "position"), dbl(rs, "close_price"),
                        rs.getObject("price_date", LocalDate.class) == null ? null
                                : rs.getObject("price_date", LocalDate.class).toString(),
                        dbl(rs, "fundamental_score"), rs.getString("fundamental_verdict"),
                        rs.getObject("fundamental_rank", Integer.class), dbl(rs, "technical_score"),
                        rs.getString("technical_stance"), rs.getObject("technical_rank", Integer.class),
                        rs.getString("stage"), map(rs, "fundamental_ai"), map(rs, "technical_ai"), map(rs, "kelly"),
                        rs.getString("action"), map(rs, "recommendation"), rs.getString("conviction"),
                        rs.getString("thesis"), rs.getString("red_flags") == null ? List.of()
                        : json.readValue(rs.getString("red_flags"), STRINGS),
                        rs.getObject("final_rank", Integer.class)))
                .list();
    }

    public List<UsageRow> usage(UUID runId) {
        return jdbc.sql("""
                        SELECT stage, model, count(*) AS calls, sum(prompt_tokens) AS prompt, sum(completion_tokens) AS completion,
                               sum(reasoning_tokens) AS reasoning, sum(cached_tokens) AS cached, sum(cost_usd) AS cost,
                               bool_or(cost_estimated) AS estimated, count(*) FILTER (WHERE error IS NOT NULL) AS errors,
                               min(usage_id) AS first
                        FROM llm_usage WHERE portfolio_analysis_id = :run
                        GROUP BY stage, model ORDER BY first""")
                .param("run", runId)
                .query((rs, i) -> new UsageRow(rs.getString("stage"), rs.getString("model"), rs.getInt("calls"),
                        rs.getLong("prompt"), rs.getLong("completion"), rs.getLong("reasoning"), rs.getLong("cached"),
                        rs.getDouble("cost"), rs.getBoolean("estimated"), rs.getInt("errors")))
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
        return new RunSummary(rs.getObject("run_id", UUID.class).toString(), rs.getString("status"),
                rs.getString("stage"), rs.getString("message"), rs.getString("exchange"), rs.getString("mode"),
                rs.getString("steps"), rs.getString("tickers") == null ? null : json.readValue(rs.getString("tickers"), STRINGS),
                rs.getInt("max_stocks"), rs.getDouble("added_capital"), rs.getDouble("budget_usd"),
                rs.getDouble("cost_usd"), rs.getInt("model_calls"), rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"), instant(rs, "requested_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), createdBy, rs.getInt("recommended"), dbl(rs, "capital_total"));
    }

    private Map<String, Object> map(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return text == null ? null : json.readValue(text, MAP);
    }

    private static Double dbl(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private String write(Object value) {
        return json.writeValueAsString(value);
    }
}
