package com.neracalab.backend.technical.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.screening.data.FundamentalEtlService;
import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The technical screening API and a whole run of selected stocks against the Docker Postgres: the rules read the stored
 * daily prices of three companies of the companies table (as Companies > Technicals does), the chat model is scripted.
 * The Keith Gill agent's first answer on the first stock has its levels out of order, so the validator flags it and the
 * critic revises it; every other answer is a valid trade plan around the close.
 */
@SpringBootTest
class TechnicalScreeningControllerTest {

    // the same mocks as AnalysisControllerTest and MacroAnalysisControllerTest: the three share one application context
    // (and its connection pool; a context of its own exceeded the database's connections in the full test run)
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

    private MockMvc mvc;
    private String token;
    private final List<UUID> created = new ArrayList<>();
    private List<String> tickers;

    @BeforeEach
    void login() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        // three companies with a year of stored daily prices
        tickers = jdbc.sql("""
                        SELECT c.ticker FROM company c
                        WHERE c.exchange = 'IDX' AND c.active
                          AND (SELECT count(*) FROM price_daily p WHERE p.company_id = c.company_id
                               AND p.close_price IS NOT NULL) >= 300
                        ORDER BY c.ticker LIMIT 3""")
                .query(String.class).list();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> answer(inv.getArgument(0)));
    }

    /** Answers by the role in the system prompt: a technical agent (or its critic), or the synthesis. */
    private ChatResponse answer(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        String system = messages.stream().filter(m -> m instanceof SystemMessage).findFirst().map(Message::getText).orElse("");
        String last = messages.getLast().getText() == null ? "" : messages.getLast().getText();
        Object answer;
        if (system.startsWith("You are one of four independent technical analysts")) {
            JsonNode dossier = json(messages.get(1).getText());
            double close = dossier.path("price").path("close").asDouble();
            String persona = messages.get(2).getText();
            boolean review = last.contains("REFLECTION REVIEW");
            boolean broken = !review && persona.startsWith("PERSONA: Keith Gill")
                    && dossier.path("ticker").asString().equals(tickers.getFirst());
            // the agent keeps its rules prior: no divergence, stance or rule issue, so no lesson is learned
            double prior = prior(persona);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("score", prior);
            a.put("stance", TechnicalAssessment.stanceOf(prior));
            a.put("thesis", "A constructive chart");
            a.put("supports", List.of("Close above a support zone"));
            a.put("risks", List.of("IHSG below its 200-day average"));
            a.put("buy", Map.of("price", close * 0.97, "type", "LIMIT"));
            a.put("sell", broken ? close * 0.9 : close * 1.15);
            a.put("stopLoss", close * 0.9);
            a.put("signalsUsed", List.of("momentum.rsi14", "sma200", "rules"));
            if (review) {
                a.put("note", "Target set above the buy");
            }
            answer = a;
        } else if (system.startsWith("You are the chief technical strategist")) {
            JsonNode input = json(messages.get(1).getText());
            List<Map<String, Object>> stocks = new ArrayList<>();
            for (JsonNode c : input.path("candidates")) {
                double close = c.path("close").asDouble();
                stocks.add(Map.of("ticker", c.path("ticker").asString(), "action", "ACCUMULATE", "conviction", "MEDIUM",
                        "thesis", "Pullback entry in a mixed market", "buy", Map.of("price", close * 0.98, "type", "LIMIT"),
                        "sell", close * 1.12, "stopLoss", close * 0.92, "adjustment", 0));
            }
            answer = Map.of("executiveSummary", "Three charts read; accumulate on pullbacks.",
                    "marketNotes", List.of("IHSG trend checked"), "stocks", stocks);
        } else {
            throw new IllegalStateException("unexpected prompt: " + system);
        }
        CompletionUsage usage = CompletionUsage.builder().promptTokens(1000).completionTokens(100).totalTokens(1100)
                .putAdditionalProperty("cost", JsonValue.from(0.0004)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json.writeValueAsString(answer)))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 100, 1100, usage)).build());
    }

    @AfterEach
    void cleanup() {
        if (!created.isEmpty()) {
            jdbc.sql("DELETE FROM technical_screening_lesson WHERE source_run IN (:ids)").param("ids", created).update();
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id IN (:ids)").param("ids", created).update();
        }
        TestLogins.logout(context, token);
    }

    @Test
    void options() throws Exception {
        mvc.perform(get("/api/v1/technical-screenings/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchanges[0].code").value("IDX"))
                .andExpect(jsonPath("$.marketCapTiers.length()").value(3))
                .andExpect(jsonPath("$.marketCapTiers[0].description").value("Market cap at least Rp 10T"))
                .andExpect(jsonPath("$.agents.length()").value(4))
                .andExpect(jsonPath("$.agents[0].code").value("GILL"))
                .andExpect(jsonPath("$.agents[1].code").value("MURPHY"))
                .andExpect(jsonPath("$.agents[2].label").value("Paul Tudor Jones"))
                .andExpect(jsonPath("$.agents[3].code").value("DRUCKENMILLER"))
                .andExpect(jsonPath("$.defaultTopN").value(25))
                .andExpect(jsonPath("$.agentModel").value("deepseek/deepseek-v4.1-flash-1017"))
                .andExpect(jsonPath("$.synthesisModel").value("anthropic/claude-opus-5.5"))
                .andExpect(jsonPath("$.data.exchange").value("IDX"))
                .andExpect(jsonPath("$.data.historyYears").value(5));
        mvc.perform(get("/api/v1/listing-prices/status?exchange=idx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andExpect(jsonPath("$.historyYears").value(5));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        submit(Map.of("marketCapTier", "HUGE", "agents", List.of("GILL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("marketCapTier must be one of LARGE, MID, SMALL"));
        submit(Map.of("marketCapTier", "LARGE", "topN", 51, "agents", List.of("GILL"))).andExpect(status().isBadRequest());
        submit(Map.of("marketCapTier", "LARGE", "agents", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose at least one technical agent"));
        submit(Map.of("marketCapTier", "LARGE", "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Unknown agents: BUFFETT")));
        submit(Map.of("tickers", List.of("HRTA", "ZZZZ9"), "agents", List.of("GILL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Not in the IDX companies table: ZZZZ9"));
        submit(Map.of("marketCapTier", "LARGE", "tickers", List.of("HRTA"), "agents", List.of("GILL")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Give either marketCapTier or tickers, not both"));
        mvc.perform(get("/api/v1/technical-screenings?scope=OTHER")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/technical-screenings/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void companiesThatCanBeSelected() throws Exception {
        mvc.perform(get("/api/v1/technical-screenings/companies?exchange=idx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andExpect(jsonPath("$.maxSelected").value(100))
                .andExpect(jsonPath("$.minBars").value(120))
                .andExpect(jsonPath("$.companies[?(@.ticker == 'HRTA')].tradingDays").exists());
    }

    @Test
    void aSelectionIsScreenedByTheRulesTheAgentsAndTheSynthesis() throws Exception {
        assertThat(tickers).hasSize(3);
        JsonNode run = json.readTree(submit(Map.of("tickers", tickers, "topN", 2,
                "agents", List.of("DRUCKENMILLER", "gill", "Paul Tudor Jones", "MURPHY")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(run.path("id").asString());
        created.add(id);
        // the agents in their fixed order
        assertThat(run.path("agents").toString()).isEqualTo("[\"GILL\",\"MURPHY\",\"TUDOR_JONES\",\"DRUCKENMILLER\"]");

        JsonNode report = awaitFinished(id);
        assertThat(report.path("run").path("status").asString()).as(report.path("run").toString()).isEqualTo("SUCCEEDED");
        assertThat(report.path("run").path("selectedCount").asInt()).isEqualTo(2);
        assertThat(report.path("synthesis").path("executiveSummary").asString()).startsWith("Three charts read");
        assertThat(report.path("synthesis").path("model").asString()).isEqualTo("anthropic/claude-opus-5.5");
        // every call metered at the reported $0.0004: 12 answers, at least the one review, 1 synthesis
        int calls = report.path("run").path("modelCalls").asInt();
        assertThat(calls).isGreaterThanOrEqualTo(14);
        assertThat(report.path("run").path("costUsd").asDouble())
                .isCloseTo(calls * 0.0004, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(report.path("usage").toString()).contains("TECHNICAL_AGENT").contains("REFLECTION").contains("SYNTHESIS");
        assertThat(report.path("market").path("index").asString()).isEqualTo("IHSG");
        assertThat(report.path("funnel").path(0).path("remaining").asInt()).isEqualTo(3);
        JsonNode candidates = report.path("candidates");
        assertThat(candidates.size()).isEqualTo(3);
        int selected = 0;
        for (JsonNode c : candidates) {
            selected += c.path("selected").asBoolean() ? 1 : 0;
            assertThat(c.path("inCompanies").asBoolean()).isTrue();
            assertThat(c.path("action").asString()).isEqualTo("ACCUMULATE");
            assertThat(c.path("rules").path("summaries").size()).isEqualTo(4);
            JsonNode plan = c.path("plan");
            assertThat(plan.path("source").asString()).isEqualTo("SYNTHESIS");
            double buy = plan.path("buy").path("price").asDouble();
            assertThat(plan.path("stopLoss").path("price").asDouble()).isLessThan(buy);
            assertThat(plan.path("sell").path("price").asDouble()).isGreaterThan(buy);
            assertThat(c.path("agents").size()).isEqualTo(4);
            for (JsonNode a : c.path("agents")) {
                assertThat(a.path("plan").path("source").asString()).isEqualTo("AI");
                double fin = a.path("finalScore").asDouble();
                double expected = Math.round((0.6 * a.path("priorScore").asDouble() + 0.4 * a.path("llmScore").asDouble())
                        * 10) / 10.0;
                assertThat(fin).isEqualTo(expected);
            }
        }
        assertThat(selected).isEqualTo(2);
        // the broken first answer was flagged and revised by the critic
        JsonNode first = null;
        for (JsonNode c : candidates) {
            if (c.path("ticker").asString().equals(tickers.getFirst())) {
                first = c;
            }
        }
        assertThat(first).isNotNull();
        JsonNode gill = first.path("agents").path(0);
        assertThat(gill.path("agent").asString()).isEqualTo("GILL");
        assertThat(gill.path("status").asString()).isEqualTo("REVISED");
        assertThat(gill.path("reflection").path("issues").toString()).contains("LEVELS");
        assertThat(gill.path("reflection").path("note").asString()).isEqualTo("Target set above the buy");
        // one issue per agent and run teaches no lesson
        assertThat(report.path("notes").path("lessonsLearned").size()).isZero();

        mvc.perform(get("/api/v1/technical-screenings?limit=5&scope=SELECTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    // ------------------------------------------------------------------ helpers

    /** The prior of the persona block: "... = prior 57/100, stance ...". */
    private static double prior(String persona) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("= prior (\\d+)/100").matcher(persona);
        assertThat(m.find()).as(persona).isTrue();
        return Double.parseDouble(m.group(1));
    }

    /** The JSON of a message that starts with a title line ("STOCK CHART DOSSIER", "TECHNICAL SCREENING RESULT"). */
    private JsonNode json(String text) {
        return json.readTree(text.substring(text.indexOf('{')));
    }

    private ResultActions submit(Object body) throws Exception {
        return mvc.perform(post("/api/v1/technical-screenings").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode awaitFinished(UUID id) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            JsonNode report = json.readTree(mvc.perform(get("/api/v1/technical-screenings/" + id))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String status = report.path("run").path("status").asString();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return report;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("run " + id + " did not finish: " + report.path("run"));
            }
            Thread.sleep(200);
        }
    }
}
