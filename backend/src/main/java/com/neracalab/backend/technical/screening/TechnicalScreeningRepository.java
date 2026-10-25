package com.neracalab.backend.technical.screening;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningRepository.Scope;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;
import com.neracalab.backend.screening.agent.UsageMeter;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.AgentScoreView;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.CandidateView;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.RunSummary;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.SelectableCompany;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code technical_screening_run}, {@code technical_screening_candidate}, {@code technical_screening_agent_score} and
 * the technical screening rows of {@code llm_usage}. Status and progress of a run are those of its
 * {@code ingestion_job} row (same id). JSON columns are written from objects and read as trees.
 */
@Repository
public class TechnicalScreeningRepository {

    private static final Logger log = LoggerFactory.getLogger(TechnicalScreeningRepository.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public TechnicalScreeningRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Parameters of a run, read by the worker.
     *
     * @param tier    the market-cap tier screened; null when {@code tickers} are
     * @param tickers the stocks selected from the companies table; null when a tier is screened
     */
    public record RunParameters(UUID id, String exchange, MarketCapTier tier, List<String> tickers, int topN,
                                List<TechnicalAgent> agents, double budgetUsd) {

        /** A screening of selected stocks (not of a market-cap tier). */
        public boolean selection() {
            return tickers != null;
        }
    }

    // ------------------------------------------------------------------ run

    public void insertRun(RunParameters p) {
        jdbc.sql("""
                        INSERT INTO technical_screening_run (run_id, exchange, market_cap_tier, tickers, top_n, agents,
                                                             budget_usd)
                        VALUES (:id, :exchange, :tier, CAST(:tickers AS jsonb), :topN, CAST(:agents AS jsonb), :budget)""")
                .param("id", p.id())
                .param("exchange", p.exchange())
                .param("tier", p.tier() == null ? null : p.tier().name(), Types.VARCHAR)
                .param("tickers", toJson(p.tickers()), Types.VARCHAR)
                .param("topN", p.topN())
                .param("agents", toJson(p.agents().stream().map(Enum::name).toList()))
                .param("budget", p.budgetUsd())
                .update();
    }

    public Optional<RunParameters> parameters(UUID id) {
        return jdbc.sql("""
                        SELECT exchange, market_cap_tier, tickers::text AS tickers, top_n, agents::text AS agents, budget_usd
                        FROM technical_screening_run WHERE run_id = :id""")
                .param("id", id)
                .query((rs, i) -> new RunParameters(id, rs.getString("exchange"), tier(rs), tickers(rs.getString("tickers")),
                        rs.getInt("top_n"), agents(rs.getString("agents")), dbl(rs, "budget_usd")))
                .optional();
    }

    public void saveStage1(UUID id, LocalDate asOfDate, int universe, int eligible, int shortlist, Object funnel,
                           Object market) {
        jdbc.sql("""
                        UPDATE technical_screening_run SET as_of_date = :date, universe_count = :universe,
                            eligible_count = :eligible, shortlist_count = :shortlist, funnel = CAST(:funnel AS jsonb),
                            market = CAST(:market AS jsonb), updated_at = now()
                        WHERE run_id = :id""")
                .param("id", id)
                .param("date", asOfDate, Types.DATE)
                .param("universe", universe)
                .param("eligible", eligible)
                .param("shortlist", shortlist)
                .param("funnel", toJson(funnel), Types.VARCHAR)
                .param("market", toJson(market), Types.VARCHAR)
                .update();
    }

    public void saveTotals(UUID id, UsageMeter.Totals totals) {
        jdbc.sql("""
                        UPDATE technical_screening_run SET cost_usd = :cost, prompt_tokens = :prompt,
                            completion_tokens = :completion, reasoning_tokens = :reasoning, cached_tokens = :cached,
                            model_calls = :calls, updated_at = now()
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
                        UPDATE technical_screening_run SET selected_count = :selected, synthesis = CAST(:synthesis AS jsonb),
                            notes = CAST(:notes AS jsonb), updated_at = now()
                        WHERE run_id = :id""")
                .param("id", id)
                .param("selected", selected)
                .param("synthesis", toJson(synthesis), Types.VARCHAR)
                .param("notes", toJson(notes), Types.VARCHAR)
                .update();
    }

    public void saveNotes(UUID id, Object notes) {
        jdbc.sql("UPDATE technical_screening_run SET notes = CAST(:notes AS jsonb), updated_at = now() WHERE run_id = :id")
                .param("id", id).param("notes", toJson(notes), Types.VARCHAR).update();
    }

    // ------------------------------------------------------------------ candidates

    public long insertCandidate(UUID runId, String ticker, String companyName, String sector, double close,
                                LocalDate asOfDate, Double marketCap, Object metrics, Object rules, double priorOverall,
                                int priorRank) {
        return jdbc.sql("""
                        INSERT INTO technical_screening_candidate (run_id, ticker, company_name, sector, close_price,
                            as_of_date, market_cap, metrics, rules, prior_overall, prior_rank)
                        VALUES (:run, :ticker, :name, :sector, :close, :date, :cap, CAST(:metrics AS jsonb),
                                CAST(:rules AS jsonb), :prior, :rank)
                        RETURNING candidate_id""")
                .param("run", runId)
                .param("ticker", ticker)
                .param("name", companyName)
                .param("sector", sector, Types.VARCHAR)
                .param("close", close)
                .param("date", asOfDate, Types.DATE)
                .param("cap", marketCap, Types.NUMERIC)
                .param("metrics", toJson(metrics))
                .param("rules", toJson(rules))
                .param("prior", priorOverall)
                .param("rank", priorRank)
                .query(Long.class).single();
    }

    public void saveAgentScore(long candidateId, TechnicalAgent agent, double priorScore, Double llmScore,
                               double finalScore, String stance, String thesis, Object supports, Object risks,
                               Object plan, Object reflection, String status) {
        jdbc.sql("""
                        INSERT INTO technical_screening_agent_score (candidate_id, agent, prior_score, llm_score, final_score,
                            stance, thesis, supports, risks, plan, reflection, status)
                        VALUES (:candidate, :agent, :prior, :llm, :final, :stance, :thesis, CAST(:supports AS jsonb),
                                CAST(:risks AS jsonb), CAST(:plan AS jsonb), CAST(:reflection AS jsonb), :status)
                        ON CONFLICT (candidate_id, agent) DO UPDATE SET
                            prior_score = EXCLUDED.prior_score, llm_score = EXCLUDED.llm_score,
                            final_score = EXCLUDED.final_score, stance = EXCLUDED.stance, thesis = EXCLUDED.thesis,
                            supports = EXCLUDED.supports, risks = EXCLUDED.risks, plan = EXCLUDED.plan,
                            reflection = EXCLUDED.reflection, status = EXCLUDED.status""")
                .param("candidate", candidateId)
                .param("agent", agent.name())
                .param("prior", priorScore)
                .param("llm", llmScore, Types.NUMERIC)
                .param("final", finalScore)
                .param("stance", stance, Types.VARCHAR)
                .param("thesis", thesis, Types.VARCHAR)
                .param("supports", toJson(supports), Types.VARCHAR)
                .param("risks", toJson(risks), Types.VARCHAR)
                .param("plan", toJson(plan), Types.VARCHAR)
                .param("reflection", toJson(reflection), Types.VARCHAR)
                .param("status", status)
                .update();
    }

    public void saveFinal(long candidateId, double overall, Double adjustment, Integer finalRank, boolean selected,
                          String action, String conviction, Object plan, String thesis, Object redFlags) {
        jdbc.sql("""
                        UPDATE technical_screening_candidate SET overall_score = :overall, synthesis_adjustment = :adjustment,
                            final_rank = :rank, selected = :selected, action = :action, conviction = :conviction,
                            plan = CAST(:plan AS jsonb), thesis = :thesis, red_flags = CAST(:flags AS jsonb)
                        WHERE candidate_id = :id""")
                .param("id", candidateId)
                .param("overall", overall)
                .param("adjustment", adjustment, Types.NUMERIC)
                .param("rank", finalRank, Types.INTEGER)
                .param("selected", selected)
                .param("action", action, Types.VARCHAR)
                .param("conviction", conviction, Types.VARCHAR)
                .param("plan", toJson(plan), Types.VARCHAR)
                .param("thesis", thesis, Types.VARCHAR)
                .param("flags", toJson(redFlags), Types.VARCHAR)
                .update();
    }

    // ------------------------------------------------------------------ usage

    public void insertUsage(UUID runId, UsageMeter.Usage u) {
        jdbc.sql("""
                        INSERT INTO llm_usage (technical_screening_id, stage, agent, ticker, model, prompt_tokens,
                            completion_tokens, reasoning_tokens, cached_tokens, cost_usd, cost_estimated, duration_ms, error)
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

    /** Model usage of a run per stage and model, in order of first use. */
    public List<UsageRow> usage(UUID runId) {
        return jdbc.sql("""
                        SELECT stage, model, count(*) AS calls, sum(prompt_tokens) AS prompt, sum(completion_tokens) AS completion,
                               sum(reasoning_tokens) AS reasoning, sum(cached_tokens) AS cached, sum(cost_usd) AS cost,
                               bool_or(cost_estimated) AS estimated, count(*) FILTER (WHERE error IS NOT NULL) AS errors,
                               min(usage_id) AS first
                        FROM llm_usage WHERE technical_screening_id = :run
                        GROUP BY stage, model ORDER BY first""")
                .param("run", runId)
                .query((rs, i) -> new UsageRow(rs.getString("stage"), rs.getString("model"), rs.getInt("calls"),
                        rs.getLong("prompt"), rs.getLong("completion"), rs.getLong("reasoning"), rs.getLong("cached"),
                        dbl(rs, "cost"), rs.getBoolean("estimated"), rs.getInt("errors")))
                .list();
    }

    // ------------------------------------------------------------------ reads

    private static final String RUN_SELECT = """
            SELECT r.run_id, j.status, j.stage, j.message, r.exchange, r.market_cap_tier, r.tickers::text AS tickers,
                   r.top_n, r.agents::text AS agents, r.as_of_date, r.universe_count, r.eligible_count, r.shortlist_count,
                   r.selected_count, r.budget_usd, r.cost_usd, r.prompt_tokens, r.completion_tokens, r.reasoning_tokens,
                   r.cached_tokens, r.model_calls, j.requested_at, j.started_at, j.finished_at, j.created_by,
                   j.created_by_username, u.full_name AS created_by_full_name
            FROM technical_screening_run r
            JOIN ingestion_job j ON j.job_id = r.run_id
            LEFT JOIN users u ON u.user_id = j.created_by""";

    /** The most recent runs of a scope. */
    public List<RunSummary> list(int limit, Scope scope) {
        String where = switch (scope) {
            case ALL -> "";
            case TIER -> " WHERE r.tickers IS NULL";
            case SELECTION -> " WHERE r.tickers IS NOT NULL";
        };
        return jdbc.sql(RUN_SELECT + where + " ORDER BY j.requested_at DESC, r.run_id LIMIT :limit")
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

    /** funnel, market, synthesis and notes of a run. */
    public Map<String, JsonNode> documents(UUID id) {
        return jdbc.sql("""
                        SELECT funnel::text AS funnel, market::text AS market, synthesis::text AS synthesis,
                               notes::text AS notes
                        FROM technical_screening_run WHERE run_id = :id""")
                .param("id", id)
                .query((rs, i) -> {
                    Map<String, JsonNode> m = new LinkedHashMap<>();
                    m.put("funnel", tree(rs.getString("funnel")));
                    m.put("market", tree(rs.getString("market")));
                    m.put("synthesis", tree(rs.getString("synthesis")));
                    m.put("notes", tree(rs.getString("notes")));
                    return m;
                })
                .optional().orElse(Map.of());
    }

    /** The shortlist of a run: final ranking first, then the other candidates by rules rank. */
    public List<CandidateView> candidates(UUID runId) {
        Map<Long, List<AgentScoreView>> scores = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT s.candidate_id, s.agent, s.prior_score, s.llm_score, s.final_score, s.stance, s.thesis,
                               s.supports::text AS supports, s.risks::text AS risks, s.plan::text AS plan,
                               s.reflection::text AS reflection, s.status
                        FROM technical_screening_agent_score s
                        JOIN technical_screening_candidate c ON c.candidate_id = s.candidate_id
                        WHERE c.run_id = :run
                        ORDER BY s.candidate_id, s.score_id""")
                .param("run", runId)
                .query((RowCallbackHandler) rs -> {
                    TechnicalAgent agent = TechnicalAgent.valueOf(rs.getString("agent"));
                    scores.computeIfAbsent(rs.getLong("candidate_id"), k -> new ArrayList<>()).add(new AgentScoreView(
                            agent, agent.label(), dblOrNull(rs, "prior_score"), dblOrNull(rs, "llm_score"),
                            dblOrNull(rs, "final_score"), rs.getString("stance"), rs.getString("thesis"),
                            tree(rs.getString("supports")), tree(rs.getString("risks")), tree(rs.getString("plan")),
                            tree(rs.getString("reflection")), rs.getString("status")));
                });
        return jdbc.sql("""
                        SELECT c.candidate_id, c.ticker, c.company_name, c.sector, c.close_price, c.as_of_date, c.market_cap,
                               EXISTS (SELECT 1 FROM company co WHERE co.exchange = r.exchange AND co.ticker = c.ticker
                                       AND co.active) AS in_companies,
                               c.metrics::text AS metrics, c.rules::text AS rules, c.prior_overall, c.prior_rank,
                               c.overall_score, c.synthesis_adjustment, c.final_rank, c.selected, c.action, c.conviction,
                               c.plan::text AS plan, c.thesis, c.red_flags::text AS red_flags
                        FROM technical_screening_candidate c
                        JOIN technical_screening_run r ON r.run_id = c.run_id
                        WHERE c.run_id = :run
                        ORDER BY c.final_rank NULLS LAST, c.prior_rank, c.ticker""")
                .param("run", runId)
                .query((rs, i) -> {
                    long id = rs.getLong("candidate_id");
                    LocalDate asOf = rs.getObject("as_of_date", LocalDate.class);
                    return new CandidateView(id, rs.getString("ticker"), rs.getString("company_name"),
                            rs.getString("sector"), dblOrNull(rs, "close_price"), asOf == null ? null : asOf.toString(),
                            dblOrNull(rs, "market_cap"), rs.getBoolean("in_companies"), tree(rs.getString("metrics")),
                            tree(rs.getString("rules")), dblOrNull(rs, "prior_overall"), intOrNull(rs, "prior_rank"),
                            dblOrNull(rs, "overall_score"), dblOrNull(rs, "synthesis_adjustment"),
                            intOrNull(rs, "final_rank"), rs.getBoolean("selected"), rs.getString("action"),
                            rs.getString("conviction"), tree(rs.getString("plan")), rs.getString("thesis"),
                            tree(rs.getString("red_flags")), scores.getOrDefault(id, List.of()));
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
        LocalDate asOf = rs.getObject("as_of_date", LocalDate.class);
        return new RunSummary(rs.getObject("run_id", UUID.class), IngestionJobStatus.valueOf(rs.getString("status")),
                rs.getString("stage"), rs.getString("message"), rs.getString("exchange"), tier(rs),
                tickers(rs.getString("tickers")), rs.getInt("top_n"), agents(rs.getString("agents")),
                asOf == null ? null : asOf.toString(), intOrNull(rs, "universe_count"), intOrNull(rs, "eligible_count"),
                intOrNull(rs, "shortlist_count"), intOrNull(rs, "selected_count"), dbl(rs, "budget_usd"),
                dbl(rs, "cost_usd"), rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"),
                rs.getLong("reasoning_tokens"), rs.getLong("cached_tokens"), rs.getInt("model_calls"),
                instant(rs, "requested_at"), instant(rs, "started_at"), instant(rs, "finished_at"), createdBy);
    }

    // ------------------------------------------------------------------ stock picker

    /**
     * The active companies of the exchange in the companies table with their stored daily prices and the latest market
     * cap of their listing in the screening universe.
     */
    public List<SelectableCompany> companies(String exchange) {
        return jdbc.sql("""
                        SELECT c.ticker, c.company_name, c.sector, p.days, p.first_date, p.last_date, s.market_cap
                        FROM company c
                        LEFT JOIN (SELECT company_id, count(*) AS days, min(trading_date) AS first_date,
                                          max(trading_date) AS last_date
                                   FROM price_daily WHERE close_price IS NOT NULL GROUP BY company_id) p
                               ON p.company_id = c.company_id
                        LEFT JOIN LATERAL (
                            SELECT f.market_cap
                            FROM stock_listing l
                            JOIN fundamental_snapshot f ON f.listing_id = l.listing_id
                            WHERE l.exchange = c.exchange AND l.ticker = c.ticker AND l.active
                            ORDER BY f.snapshot_date DESC
                            LIMIT 1
                        ) s ON true
                        WHERE c.exchange = :exchange AND c.active
                        ORDER BY c.ticker""")
                .param("exchange", exchange)
                .query((rs, i) -> {
                    LocalDate first = rs.getObject("first_date", LocalDate.class);
                    LocalDate last = rs.getObject("last_date", LocalDate.class);
                    return new SelectableCompany(rs.getString("ticker"), rs.getString("company_name"),
                            rs.getString("sector"), intOrNull(rs, "days"), first == null ? null : first.toString(),
                            last == null ? null : last.toString(), dblOrNull(rs, "market_cap"));
                })
                .list();
    }

    /** The given tickers that are not active companies of the exchange in the companies table. */
    public List<String> unknownCompanies(String exchange, List<String> tickers) {
        if (tickers.isEmpty()) {
            return List.of();
        }
        Set<String> known = new HashSet<>(jdbc.sql("""
                        SELECT ticker FROM company WHERE exchange = :exchange AND active AND ticker IN (:tickers)""")
                .param("exchange", exchange)
                .param("tickers", tickers)
                .query(String.class)
                .list());
        return tickers.stream().filter(t -> !known.contains(t)).toList();
    }

    // ------------------------------------------------------------------ helpers

    private static MarketCapTier tier(ResultSet rs) throws SQLException {
        String tier = rs.getString("market_cap_tier");
        return tier == null ? null : MarketCapTier.valueOf(tier);
    }

    private List<String> tickers(String text) {
        if (text == null) {
            return null;
        }
        try {
            return List.copyOf(json.readValue(text, new TypeReference<List<String>>() { }));
        } catch (JacksonException e) {
            log.warn("stored tickers unreadable: {}", text);
            return List.of();
        }
    }

    private List<TechnicalAgent> agents(String text) {
        if (text == null) {
            return List.of();
        }
        try {
            List<String> names = json.readValue(text, new TypeReference<List<String>>() { });
            return names.stream().map(TechnicalAgent::valueOf).toList();
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
