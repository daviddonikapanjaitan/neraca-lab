package com.neracalab.backend.portfolio.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.TestAccounts;
import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.score.QuantScoreBoard;
import com.neracalab.backend.company.score.TechnicalScoreBoard;
import com.neracalab.backend.portfolio.PortfolioService;
import com.neracalab.backend.portfolio.PortfolioViews.PositionRequest;
import com.neracalab.backend.portfolio.PortfolioViews.SettingsRequest;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.screening.data.FundamentalEtlService;
import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Portfolio &gt; Analysis end to end against the Docker Postgres (the companies' stored scores and prices), with users
 * made for the test and a scripted chat model (every call metered): the two rules steps, the fundamental agent (one
 * answer revised by the critic), the technical agent only for the stocks the fundamental agent passed, the synthesis
 * proposing too much (capped by the limits), the report, and the API's refusals.
 */
@SpringBootTest
class PortfolioAnalysisControllerTest {

    // the same mocks as the other AI tests: they share one application context (and its connection pool)
    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private EmbeddingClient embeddings;

    @MockitoBean
    private FundamentalEtlService etl;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private PortfolioService portfolios;

    @Autowired
    private QuantScoreBoard quantBoard;

    @Autowired
    private TechnicalScoreBoard technicalBoard;

    private TestAccounts accounts;
    private long owner;
    private long stranger;
    private long companiesOnly;
    private final List<UUID> created = new ArrayList<>();
    private List<String> selected;
    private List<String> held;
    private String broken;
    private final List<String> synthesisPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        accounts = new TestAccounts(context);
        long full = accounts.role("analysis", Permission.COMPANIES, Permission.SCREENING);
        long portfolioOnly = accounts.role("portfolio-only", Permission.COMPANIES);
        owner = accounts.user("owner", full);
        stranger = accounts.user("stranger", full);
        companiesOnly = accounts.user("companies", portfolioOnly);

        // stocks with a good Fundamental (Score) and a Technical (Score): four selected, two held (one of them selected)
        Map<String, Double> technical = new LinkedHashMap<>();
        technicalBoard.board("IDX").scores().forEach(s -> {
            if (s.overall() != null) {
                technical.put(s.ticker(), s.overall());
            }
        });
        List<String> good = quantBoard.board("IDX").scores().stream()
                .filter(s -> s.overall() != null && s.overall() >= 55 && technical.getOrDefault(s.ticker(), 0.0) >= 50)
                .sorted((a, b) -> Double.compare(b.overall(), a.overall()))
                .map(QuantScoreBoard.Score::ticker).limit(6).toList();
        assertThat(good).as("six companies with both scores").hasSize(6);
        selected = good.subList(0, 4);
        held = List.of(good.get(0), good.get(4));
        broken = selected.get(1);

        portfolios.saveSettings(owner, new SettingsRequest(new BigDecimal("50000000"), new BigDecimal("60000000")));
        for (String t : held) {
            double price = jdbc.sql("""
                            SELECT p.close_price FROM price_daily p JOIN company c USING (company_id)
                            WHERE c.ticker = :t AND c.exchange = 'IDX' AND p.close_price IS NOT NULL
                            ORDER BY p.trading_date DESC LIMIT 1""").param("t", t).query(Double.class).single();
            portfolios.add(owner, new PositionRequest("IDX", t, BigDecimal.valueOf(price), new BigDecimal("1000"), null));
        }
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> answer(inv.getArgument(0)));
    }

    @AfterEach
    void cleanup() {
        if (!created.isEmpty()) {
            jdbc.sql("DELETE FROM portfolio_analysis_lesson WHERE first_run_id IN (:ids)").param("ids", created).update();
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id IN (:ids)").param("ids", created).update();
        }
        accounts.cleanup();
    }

    /** Answers by the role in the system prompt. */
    private ChatResponse answer(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        String system = messages.stream().filter(m -> m instanceof SystemMessage).findFirst().map(Message::getText)
                .orElse("");
        String user = messages.get(1).getText();
        Object answer;
        if (system.contains("ROLE: the reviewer")) {
            JsonNode dossier = json(user.substring(user.indexOf('\n') + 1, user.indexOf("\n\nREVIEW")));
            Map<String, Object> revised = user.contains("\"analyst\":\"FUNDAMENTAL\"") ? fundamental(dossier, false)
                    : technical(dossier);
            revised.put("note", "Verdict made consistent with the score");
            answer = revised;
        } else if (system.contains("ROLE: the FUNDAMENTAL analyst")) {
            JsonNode dossier = json(user.substring(user.indexOf('\n') + 1));
            answer = fundamental(dossier, dossier.path("ticker").asString().equals(broken));
        } else if (system.contains("ROLE: the TECHNICAL analyst")) {
            answer = technical(json(user.substring(user.indexOf('\n') + 1)));
        } else if (system.startsWith("You are the portfolio manager")) {
            synthesisPrompts.add(system);
            JsonNode input = json(user.substring(user.indexOf('\n') + 1));
            List<Map<String, Object>> positions = new ArrayList<>();
            for (JsonNode h : input.path("holdings")) {
                positions.add(Map.of("ticker", h.path("ticker").asString(), "action", "HOLD", "targetWeightPct",
                        h.path("weightPct").asDouble(), "conviction", "MEDIUM", "reason", "Kept"));
            }
            for (JsonNode i : input.path("ideas")) {
                // too much on purpose: the limits must cap it
                positions.add(Map.of("ticker", i.path("ticker").asString(), "action", "BUY", "targetWeightPct", 40,
                        "conviction", "HIGH", "reason", "Passed both analysts"));
            }
            answer = Map.of("executiveSummary", "Concentrate in the ideas that passed both analysts.",
                    "riskNotes", List.of("Check the sector limits"), "cashTargetPct", 5, "positions", positions);
        } else {
            throw new IllegalStateException("unexpected prompt: " + system);
        }
        CompletionUsage usage = CompletionUsage.builder().promptTokens(1000).completionTokens(100).totalTokens(1100)
                .putAdditionalProperty("cost", JsonValue.from(0.0004)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json.writeValueAsString(answer)))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 100, 1100, usage)).build());
    }

    /** The rules score as the agent's; the broken one answers PASS with a score far below it (flagged). */
    private Map<String, Object> fundamental(JsonNode dossier, boolean brokenAnswer) {
        double rules = dossier.path("fundamentalScore").asDouble();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("score", brokenAnswer ? 10 : rules);
        a.put("verdict", "PASS");
        a.put("thesis", "A profitable business with a sound balance sheet");
        a.put("strengths", List.of("ROE above 15%"));
        a.put("risks", List.of("Cyclical demand"));
        return a;
    }

    private Map<String, Object> technical(JsonNode dossier) {
        double rules = dossier.path("technicalScore").asDouble();
        double close = dossier.path("close").asDouble();
        Map<String, Object> a = new LinkedHashMap<>();
        // 20 points above the rules (no divergence), the stance inside its band (no stance issue)
        double score = Math.min(100, rules + 20);
        a.put("score", score);
        a.put("stance", score >= 65 ? "BUY" : score >= 50 ? "ACCUMULATE" : "WAIT");
        a.put("entry", close * 0.98);
        a.put("entryType", "LIMIT");
        a.put("stopLoss", close * 0.9);
        a.put("target", close * 1.3);
        a.put("thesis", "Pullback to support in an uptrend");
        a.put("risks", List.of("Market below its average"));
        return a;
    }

    private JsonNode json(String text) {
        return json.readTree(text);
    }

    private JsonNode report(MockMvc mvc, UUID id) throws Exception {
        String body = mvc.perform(get("/api/v1/portfolio/analyses/" + id)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return json(body);
    }

    @Test
    void aWholeRunFollowsTheStepsAndTheLimits() throws Exception {
        MockMvc mvc = TestLogins.mockMvc(context, accounts.token(owner));
        mvc.perform(get("/api/v1/portfolio/analyses/options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMaxStocks").value(25))
                .andExpect(jsonPath("$.maxPositionWeight").value(0.33))
                .andExpect(jsonPath("$.portfolio.positions").value(2))
                .andExpect(jsonPath("$.companies").isArray());

        String body = mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mode", "SELECTED", "maxStocks", 4, "tickers", selected,
                                "addedCapital", 10_000_000))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("SELECTED"))
                .andExpect(jsonPath("$.maxStocks").value(4))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json(body).path("id").asString());
        created.add(id);

        JsonNode r = report(mvc, id);
        for (int i = 0; i < 600 && List.of("QUEUED", "RUNNING").contains(r.path("run").path("status").asString()); i++) {
            Thread.sleep(500);
            r = report(mvc, id);
        }
        JsonNode run = r.path("run");
        assertThat(run.path("status").asString()).as(run.path("message").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("modelCalls").asInt()).isPositive();
        assertThat(run.path("costUsd").asDouble()).isPositive();

        // the capital: cash 50M + added 10M + the two positions
        assertThat(r.path("capital").path("addedCapital").asDouble()).isEqualTo(10_000_000);
        double capital = r.path("capital").path("total").asDouble();
        assertThat(capital).isGreaterThan(60_000_000);

        // the funnel: step 1 then step 2
        assertThat(r.path("funnel").path("fundamental").path("passed").asInt()).isPositive();
        assertThat(r.path("funnel").path("technical").path("inputs").asInt())
                .isEqualTo(r.path("funnel").path("fundamental").path("passed").asInt());

        Map<String, JsonNode> byTicker = new LinkedHashMap<>();
        r.path("candidates").forEach(c -> byTicker.put(c.path("ticker").asString(), c));
        // the holdings are in the report with their positions and an action
        for (String t : held) {
            JsonNode c = byTicker.get(t);
            assertThat(c).as(t).isNotNull();
            assertThat(c.path("held").asBoolean()).isTrue();
            assertThat(c.path("stage").asString()).isEqualTo("HELD");
            assertThat(c.path("position").path("shares").asDouble()).isEqualTo(1000);
            // the synthesis holds them: kept at their exact shares
            assertThat(c.path("action").asString()).isEqualTo("HOLD");
            assertThat(c.path("recommendation").path("tradeShares").asDouble()).isZero();
        }
        // Reflection: the broken fundamental answer was flagged and revised by the critic
        JsonNode b = byTicker.get(broken).path("fundamentalAi");
        assertThat(b.path("status").asString()).isEqualTo("REVISED");
        assertThat(b.path("issues").toString()).contains("DIVERGENCE");
        // fundamental first, technical after: every bought stock passed both agents (and some were bought)
        assertThat(r.path("candidates").toString()).contains("\"action\":\"BUY\"");
        r.path("candidates").forEach(c -> {
            if ("BUY".equals(c.path("action").asString())) {
                assertThat(c.path("fundamentalAi").path("verdict").asString()).isEqualTo("PASS");
                assertThat(c.path("technicalAi").path("stance").asString()).isIn("BUY", "ACCUMULATE");
                assertThat(c.path("kelly").path("weight").asDouble()).isPositive();
                assertThat(c.path("stage").asString()).isEqualTo("RECOMMENDED");
            }
        });

        // the limits: 40% asked, at most 33% per stock and per sector, at most 4 stocks, whole lots, cash >= 0
        JsonNode allocation = r.path("allocation");
        long stocks = 0;
        for (JsonNode c : r.path("candidates")) {
            JsonNode rec = c.path("recommendation");
            if (c.path("action").isNull() || c.path("action").isMissingNode()) {
                continue;
            }
            assertThat(rec.path("targetWeight").asDouble()).as(c.path("ticker").asString())
                    .isLessThanOrEqualTo(0.33 + 1e-6);
            if (List.of("BUY", "INCREASE", "DECREASE").contains(c.path("action").asString())) {
                assertThat(rec.path("tradeShares").asDouble() % 100).isZero();
            }
            if (rec.path("targetShares").asDouble() > 0) {
                stocks++;
            }
        }
        assertThat(stocks).isLessThanOrEqualTo(4);
        allocation.path("sectorsAfter").properties().forEach(e -> assertThat(e.getValue().asDouble())
                .as(e.getKey()).isLessThanOrEqualTo(0.33 + 1e-6));
        assertThat(allocation.path("cashAfter").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(allocation.path("cashWeightAfter").asDouble()).isGreaterThanOrEqualTo(0.05 - 1e-6);
        r.path("checks").forEach(c -> assertThat(c.path("ok").asBoolean()).as(c.path("name").asString()).isTrue());
        assertThat(r.path("synthesis").path("model").asString()).isNotBlank();
        assertThat(r.path("synthesis").path("fallback").isNull()).isTrue();
        assertThat(r.path("usage").toString()).contains("AGENT", "REFLECTION", "SYNTHESIS");
        assertThat(r.path("disclaimer").asString()).contains("not investment advice");

        // the list; another user sees none of it
        mvc.perform(get("/api/v1/portfolio/analyses")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
        MockMvc other = TestLogins.mockMvc(context, accounts.token(stranger));
        other.perform(get("/api/v1/portfolio/analyses/" + id)).andExpect(status().isNotFound());
        other.perform(get("/api/v1/portfolio/analyses")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void theFundamentalAnalysisOnly() throws Exception {
        MockMvc mvc = TestLogins.mockMvc(context, accounts.token(owner));
        String body = mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mode", "SELECTED", "steps", "FUNDAMENTAL",
                                "maxStocks", 4, "tickers", selected))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.steps").value("FUNDAMENTAL"))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json(body).path("id").asString());
        created.add(id);
        JsonNode r = report(mvc, id);
        for (int i = 0; i < 600 && List.of("QUEUED", "RUNNING").contains(r.path("run").path("status").asString()); i++) {
            Thread.sleep(500);
            r = report(mvc, id);
        }
        assertThat(r.path("run").path("status").asString()).as(r.path("run").path("message").asString())
                .isEqualTo("SUCCEEDED");
        assertThat(r.path("run").path("steps").asString()).isEqualTo("FUNDAMENTAL");
        // no technical step, no technical agent, the fundamental-only synthesis
        assertThat(r.path("funnel").path("technical").path("used").asBoolean()).isFalse();
        assertThat(r.path("funnel").path("steps").asString()).isEqualTo("FUNDAMENTAL");
        assertThat(jdbc.sql("SELECT count(*) FROM llm_usage WHERE portfolio_analysis_id = :id AND agent = 'TECHNICAL'")
                .param("id", id).query(Integer.class).single()).isZero();
        assertThat(synthesisPrompts).anyMatch(p -> p.contains("chose the FUNDAMENTAL analysis only"));
        boolean bought = false;
        for (JsonNode c : r.path("candidates")) {
            assertThat(c.path("technicalAi").isNull() || c.path("technicalAi").isMissingNode()).as(c.path("ticker")
                    .asString()).isTrue();
            assertThat(c.path("technicalRank").isNull()).isTrue();
            assertThat(c.path("stage").asString()).isNotIn("TECHNICAL_OUT", "TECHNICAL_AI_OUT");
            if ("BUY".equals(c.path("action").asString())) {
                bought = true;
                assertThat(c.path("fundamentalAi").path("verdict").asString()).isEqualTo("PASS");
                assertThat(c.path("kelly").path("payoff").asDouble()).isEqualTo(1.0);
                assertThat(c.path("recommendation").path("tradeShares").asDouble() % 100).isZero();
                assertThat(c.path("close").asDouble()).isPositive();
            }
            if (!c.path("action").isNull() && !c.path("action").isMissingNode()) {
                assertThat(c.path("recommendation").path("targetWeight").asDouble()).isLessThanOrEqualTo(0.33 + 1e-6);
            }
        }
        assertThat(bought).as("a stock with a fundamental PASS is bought").isTrue();
        r.path("checks").forEach(c -> assertThat(c.path("ok").asBoolean()).as(c.path("name").asString()).isTrue());
        // an unknown steps value is refused
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BULK\",\"maxStocks\":5,\"steps\":\"TECHNICAL\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theApiRefuses() throws Exception {
        MockMvc mvc = TestLogins.mockMvc(context, accounts.token(owner));
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"SOME\",\"maxStocks\":5}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"BULK\",\"maxStocks\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"BULK\",\"maxStocks\":51}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"SELECTED\",\"maxStocks\":5}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SELECTED\",\"maxStocks\":5,\"tickers\":[\"ZZZZQ\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ZZZZQ")));
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BULK\",\"maxStocks\":5,\"tickers\":[\"BBCA\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/portfolio/analyses").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"BULK\",\"maxStocks\":5,\"addedCapital\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/portfolio/analyses/not-a-uuid")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/portfolio/analyses/" + UUID.randomUUID())).andExpect(status().isNotFound());
        // a run needs SCREENING (the AI models) besides COMPANIES
        TestLogins.mockMvc(context, accounts.token(companiesOnly)).perform(post("/api/v1/portfolio/analyses")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"BULK\",\"maxStocks\":5}"))
                .andExpect(status().isForbidden());
        TestLogins.mockMvc(context, accounts.token(companiesOnly)).perform(get("/api/v1/portfolio/analyses"))
                .andExpect(status().isOk());
    }
}
