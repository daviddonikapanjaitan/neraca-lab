package com.neracalab.backend.analysis;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.analysis.AnalysisViews.AnalysisSummary;
import com.neracalab.backend.analysis.AnalysisViews.CompanyOption;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningViews.AgentScoreView;
import com.neracalab.backend.screening.ScreeningViews.UsageRow;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code analysis_run}, {@code analysis_agent_score} and the analysis rows of {@code llm_usage}. Status and
 * progress of an analysis are those of its {@code ingestion_job} row (same id). JSON columns are written from
 * objects and read as trees.
 */
@Repository
public class AnalysisRepository {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRepository.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AnalysisRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Parameters of an analysis, read by the worker. */
    public record Parameters(UUID id, long companyId, String exchange, String ticker, List<InvestorAgent> agents,
                             double budgetUsd) {
    }

    /** The final result of an analysis. */
    public record Result(Double quantOverall, Double overallScore, Double adjustment, String verdict, String conviction,
                         Object synthesis, Object notes) {
    }

    // ------------------------------------------------------------------ writes

    public void insertRun(Parameters p, String companyName) {
        jdbc.sql("""
                        INSERT INTO analysis_run (analysis_id, company_id, exchange, ticker, company_name, agents, budget_usd)
                        VALUES (:id, :company, :exchange, :ticker, :name, CAST(:agents AS jsonb), :budget)""")
                .param("id", p.id())
                .param("company", p.companyId())
                .param("exchange", p.exchange())
                .param("ticker", p.ticker())
                .param("name", companyName, Types.VARCHAR)
                .param("agents", toJson(p.agents().stream().map(Enum::name).toList()))
                .param("budget", p.budgetUsd())
                .update();
    }

    public Optional<Parameters> parameters(UUID id) {
        return jdbc.sql("""
                        SELECT company_id, exchange, ticker, agents::text AS agents, budget_usd
                        FROM analysis_run WHERE analysis_id = :id""")
                .param("id", id)
                .query((rs, i) -> new Parameters(id, rs.getLong("company_id"), rs.getString("exchange"),
                        rs.getString("ticker"), agents(rs.getString("agents")), dbl(rs, "budget_usd")))
                .optional();
    }

    /** What the agents saw, and the date of the market data used. */
    public void saveContext(UUID id, Object context, LocalDate marketDataDate) {
        jdbc.sql("""
                        UPDATE analysis_run SET context = CAST(:context AS jsonb), market_data_date = :date, updated_at = now()
                        WHERE analysis_id = :id""")
                .param("id", id)
                .param("context", toJson(context), Types.VARCHAR)
                .param("date", marketDataDate, Types.DATE)
                .update();
    }

    public void saveResearch(UUID id, Object research) {
        jdbc.sql("UPDATE analysis_run SET research = CAST(:research AS jsonb), updated_at = now() WHERE analysis_id = :id")
                .param("id", id).param("research", toJson(research), Types.VARCHAR).update();
    }

    public void saveAgentScore(UUID id, InvestorAgent agent, Double quantScore, Object quantDetail, Double llmScore,
                               Double finalScore, String verdict, String thesis, Object strengths, Object concerns,
                               Object reflection, String status) {
        jdbc.sql("""
                        INSERT INTO analysis_agent_score (analysis_id, agent, quant_score, quant_detail, llm_score, final_score,
                            verdict, thesis, strengths, concerns, reflection, status)
                        VALUES (:id, :agent, :quant, CAST(:detail AS jsonb), :llm, :final, :verdict, :thesis,
                                CAST(:strengths AS jsonb), CAST(:concerns AS jsonb), CAST(:reflection AS jsonb), :status)
                        ON CONFLICT (analysis_id, agent) DO UPDATE SET
                            quant_score = EXCLUDED.quant_score, quant_detail = EXCLUDED.quant_detail,
                            llm_score = EXCLUDED.llm_score, final_score = EXCLUDED.final_score,
                            verdict = EXCLUDED.verdict, thesis = EXCLUDED.thesis, strengths = EXCLUDED.strengths,
                            concerns = EXCLUDED.concerns, reflection = EXCLUDED.reflection, status = EXCLUDED.status""")
                .param("id", id)
                .param("agent", agent.name())
                .param("quant", quantScore, Types.NUMERIC)
                .param("detail", toJson(quantDetail), Types.VARCHAR)
                .param("llm", llmScore, Types.NUMERIC)
                .param("final", finalScore, Types.NUMERIC)
                .param("verdict", verdict, Types.VARCHAR)
                .param("thesis", thesis, Types.VARCHAR)
                .param("strengths", toJson(strengths), Types.VARCHAR)
                .param("concerns", toJson(concerns), Types.VARCHAR)
                .param("reflection", toJson(reflection), Types.VARCHAR)
                .param("status", status)
                .update();
    }

    public void saveResult(UUID id, Result r) {
        jdbc.sql("""
                        UPDATE analysis_run SET quant_overall = :quant, overall_score = :overall,
                            synthesis_adjustment = :adjustment, verdict = :verdict, conviction = :conviction,
                            synthesis = CAST(:synthesis AS jsonb), notes = CAST(:notes AS jsonb), updated_at = now()
                        WHERE analysis_id = :id""")
                .param("id", id)
                .param("quant", r.quantOverall(), Types.NUMERIC)
                .param("overall", r.overallScore(), Types.NUMERIC)
                .param("adjustment", r.adjustment(), Types.NUMERIC)
                .param("verdict", r.verdict(), Types.VARCHAR)
                .param("conviction", r.conviction(), Types.VARCHAR)
                .param("synthesis", toJson(r.synthesis()), Types.VARCHAR)
                .param("notes", toJson(r.notes()), Types.VARCHAR)
                .update();
    }

    public void saveNotes(UUID id, Object notes) {
        jdbc.sql("UPDATE analysis_run SET notes = CAST(:notes AS jsonb), updated_at = now() WHERE analysis_id = :id")
                .param("id", id).param("notes", toJson(notes), Types.VARCHAR).update();
    }

    public void saveTotals(UUID id, UsageMeter.Totals totals) {
        jdbc.sql("""
                        UPDATE analysis_run SET cost_usd = :cost, prompt_tokens = :prompt, completion_tokens = :completion,
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
                        INSERT INTO llm_usage (analysis_id, stage, agent, ticker, model, prompt_tokens, completion_tokens,
                            reasoning_tokens, cached_tokens, cost_usd, cost_estimated, duration_ms, error)
                        VALUES (:analysis, :stage, :agent, :ticker, :model, :prompt, :completion, :reasoning, :cached, :cost,
                                :estimated, :ms, :error)""")
                .param("analysis", analysisId)
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

    /** Model and embedding usage of an analysis per stage and model, in order of first use. */
    public List<UsageRow> usage(UUID analysisId) {
        return jdbc.sql("""
                        SELECT stage, model, count(*) AS calls, sum(prompt_tokens) AS prompt, sum(completion_tokens) AS completion,
                               sum(reasoning_tokens) AS reasoning, sum(cached_tokens) AS cached, sum(cost_usd) AS cost,
                               bool_or(cost_estimated) AS estimated, count(*) FILTER (WHERE error IS NOT NULL) AS errors,
                               min(usage_id) AS first
                        FROM llm_usage WHERE analysis_id = :id
                        GROUP BY stage, model ORDER BY first""")
                .param("id", analysisId)
                .query((rs, i) -> new UsageRow(rs.getString("stage"), rs.getString("model"), rs.getInt("calls"),
                        rs.getLong("prompt"), rs.getLong("completion"), rs.getLong("reasoning"), rs.getLong("cached"),
                        dbl(rs, "cost"), rs.getBoolean("estimated"), rs.getInt("errors")))
                .list();
    }

    // ------------------------------------------------------------------ reads

    private static final String RUN_SELECT = """
            SELECT r.analysis_id, j.status, j.stage, j.message, r.exchange, r.ticker, r.company_name,
                   r.agents::text AS agents, r.overall_score, r.verdict, r.conviction, r.market_data_date, r.budget_usd,
                   r.cost_usd, r.prompt_tokens, r.completion_tokens, r.reasoning_tokens, r.cached_tokens, r.model_calls,
                   j.requested_at, j.started_at, j.finished_at, j.created_by, j.created_by_username,
                   u.full_name AS created_by_full_name
            FROM analysis_run r
            JOIN ingestion_job j ON j.job_id = r.analysis_id
            LEFT JOIN users u ON u.user_id = j.created_by""";

    /** One page of analyses, most recent first; of one ticker when given. */
    public List<AnalysisSummary> list(String ticker, int limit, int offset) {
        return jdbc.sql(RUN_SELECT + """

                        WHERE (CAST(:ticker AS varchar) IS NULL OR r.ticker = :ticker)
                        ORDER BY j.requested_at DESC, r.analysis_id
                        LIMIT :limit OFFSET :offset""")
                .param("ticker", ticker, Types.VARCHAR)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> summary(rs))
                .list();
    }

    public long count(String ticker) {
        return jdbc.sql("SELECT count(*) FROM analysis_run WHERE (CAST(:ticker AS varchar) IS NULL OR ticker = :ticker)")
                .param("ticker", ticker, Types.VARCHAR)
                .query(Long.class).single();
    }

    public Optional<AnalysisSummary> find(UUID id) {
        return jdbc.sql(RUN_SELECT + " WHERE r.analysis_id = :id")
                .param("id", id)
                .query((rs, i) -> summary(rs))
                .optional();
    }

    /** The stored result of an analysis besides its summary and agent scores. */
    public record Documents(Double quantOverall, Double synthesisAdjustment, JsonNode context, JsonNode research,
                            JsonNode synthesis, JsonNode notes) {

        static final Documents EMPTY = new Documents(null, null, null, null, null, null);
    }

    /** quant_overall, synthesis_adjustment and the context, research, synthesis and notes documents. */
    public Documents documents(UUID id) {
        return jdbc.sql("""
                        SELECT quant_overall, synthesis_adjustment, context::text AS context, research::text AS research,
                               synthesis::text AS synthesis, notes::text AS notes
                        FROM analysis_run WHERE analysis_id = :id""")
                .param("id", id)
                .query((rs, i) -> new Documents(dblOrNull(rs, "quant_overall"), dblOrNull(rs, "synthesis_adjustment"),
                        tree(rs.getString("context")), tree(rs.getString("research")), tree(rs.getString("synthesis")),
                        tree(rs.getString("notes"))))
                .optional().orElse(Documents.EMPTY);
    }

    /** The agents' scores in the order of the agents (Buffett ... Risk). */
    public List<AgentScoreView> agentScores(UUID id) {
        return jdbc.sql("""
                        SELECT agent, quant_score, quant_detail::text AS quant_detail, llm_score, final_score, verdict, thesis,
                               strengths::text AS strengths, concerns::text AS concerns, reflection::text AS reflection, status
                        FROM analysis_agent_score WHERE analysis_id = :id""")
                .param("id", id)
                .query((rs, i) -> {
                    InvestorAgent agent = InvestorAgent.valueOf(rs.getString("agent"));
                    return new AgentScoreView(agent, agent.label(), dblOrNull(rs, "quant_score"),
                            tree(rs.getString("quant_detail")), dblOrNull(rs, "llm_score"), dblOrNull(rs, "final_score"),
                            rs.getString("verdict"), rs.getString("thesis"), tree(rs.getString("strengths")),
                            tree(rs.getString("concerns")), tree(rs.getString("reflection")), rs.getString("status"));
                })
                .list().stream()
                .sorted(java.util.Comparator.comparingInt(s -> s.agent().ordinal()))
                .toList();
    }

    /** Every active company with what the database holds for it (the analysis form). */
    public List<CompanyOption> companies() {
        return jdbc.sql("""
                        SELECT c.exchange, c.ticker, c.company_name, c.sector,
                               (SELECT count(*) FROM reporting_period rp WHERE rp.company_id = c.company_id) AS periods,
                               (SELECT rp.fiscal_year || ' ' || rp.period_type FROM reporting_period rp
                                 WHERE rp.company_id = c.company_id
                                 ORDER BY rp.period_end DESC, rp.period_start ASC NULLS LAST, rp.period_id LIMIT 1) AS latest_period,
                               (SELECT count(*) FROM rag_document d
                                 WHERE d.company_id = c.company_id AND d.source_type = 'PDF' AND d.chunks > 0) AS pdfs,
                               (SELECT count(*) FROM rag_document d
                                 WHERE d.company_id = c.company_id AND d.source_type = 'NEWS' AND d.chunks > 0) AS news,
                               (SELECT max(s.snapshot_date) FROM fundamental_snapshot s
                                 JOIN stock_listing l ON l.listing_id = s.listing_id
                                 WHERE l.exchange = c.exchange AND l.ticker = c.ticker) AS market_date,
                               (SELECT max(pd.trading_date) FROM price_daily pd WHERE pd.company_id = c.company_id) AS price_date
                        FROM company c
                        WHERE c.active
                        ORDER BY c.exchange, c.ticker""")
                .query((rs, i) -> new CompanyOption(rs.getString("exchange"), rs.getString("ticker"),
                        rs.getString("company_name"), rs.getString("sector"), rs.getInt("periods"),
                        rs.getString("latest_period"), rs.getLong("pdfs"), rs.getLong("news"),
                        dateText(rs, "market_date"), dateText(rs, "price_date")))
                .list();
    }

    /** PDF and news documents of a company in the vector store. */
    public record StoredDocuments(long filings, long news, List<String> filingTitles, List<String> newsTitles) {
    }

    /** Counts and the latest titles of a company's stored documents (told to the research agent). */
    public StoredDocuments storedDocuments(long companyId, int titles) {
        long filings = documentCount(companyId, "PDF");
        long news = documentCount(companyId, "NEWS");
        List<String> filingTitles = jdbc.sql("""
                        SELECT COALESCE(file_name, title) FROM rag_document
                        WHERE company_id = :c AND source_type = 'PDF' AND chunks > 0
                        ORDER BY updated_at DESC, document_id DESC LIMIT :n""")
                .param("c", companyId).param("n", titles).query(String.class).list();
        List<String> newsTitles = jdbc.sql("""
                        SELECT COALESCE(to_char(published_at AT TIME ZONE 'Asia/Jakarta', 'YYYY-MM-DD') || ' ', '') || title
                        FROM rag_document
                        WHERE company_id = :c AND source_type = 'NEWS' AND chunks > 0
                        ORDER BY published_at DESC NULLS LAST, document_id DESC LIMIT :n""")
                .param("c", companyId).param("n", titles).query(String.class).list();
        return new StoredDocuments(filings, news, filingTitles, newsTitles);
    }

    private long documentCount(long companyId, String type) {
        return jdbc.sql("SELECT count(*) FROM rag_document WHERE company_id = :c AND source_type = :t AND chunks > 0")
                .param("c", companyId).param("t", type).query(Long.class).single();
    }

    private AnalysisSummary summary(ResultSet rs) throws SQLException {
        Long createdById = rs.getLong("created_by");
        if (rs.wasNull()) {
            createdById = null;
        }
        String username = rs.getString("created_by_username");
        IngestionJob.CreatedBy createdBy = username == null ? null
                : new IngestionJob.CreatedBy(createdById, username, rs.getString("created_by_full_name"));
        return new AnalysisSummary(rs.getObject("analysis_id", UUID.class),
                IngestionJobStatus.valueOf(rs.getString("status")), rs.getString("stage"), rs.getString("message"),
                rs.getString("exchange"), rs.getString("ticker"), rs.getString("company_name"),
                agents(rs.getString("agents")), dblOrNull(rs, "overall_score"), rs.getString("verdict"),
                rs.getString("conviction"), dateText(rs, "market_data_date"), dbl(rs, "budget_usd"), dbl(rs, "cost_usd"),
                rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("reasoning_tokens"),
                rs.getLong("cached_tokens"), rs.getInt("model_calls"), instant(rs, "requested_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), createdBy);
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

    private static String dateText(ResultSet rs, String column) throws SQLException {
        LocalDate date = rs.getObject(column, LocalDate.class);
        return date == null ? null : date.toString();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
