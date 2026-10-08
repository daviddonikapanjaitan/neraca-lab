package com.neracalab.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.RagProperties;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.data.FundamentalEtlService;
import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An analysis of HRTA end to end against the Docker Postgres: its stored statements and PDF chunks, the real
 * pipeline (research agent with a tool call into the vector store, six investor agents, synthesis) with a model
 * that answers by role, stub embeddings and no Yahoo Finance request. Also the validation, the paging and the PDF.
 */
@SpringBootTest
class AnalysisControllerTest {

    private static final Pattern PRIOR = Pattern.compile("Quantitative scorecard \\(your prior\\): (\\d+)/100");

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
    private AnalysisRepository repository;

    @Autowired
    private IngestionJobRepository jobs;

    private MockMvc mvc;
    private String token;
    private final List<UUID> created = new ArrayList<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        float[] vector = new float[RagProperties.STORE_DIMENSIONS];
        Arrays.fill(vector, 0.01f);
        when(embeddings.embed(anyString())).thenReturn(vector);
        when(embeddings.model()).thenReturn("stub-embedding");
        when(etl.refreshFundamentals(any(), any(), any())).thenReturn(
                new FundamentalEtlService.RefreshResult(0, 0, 0, false, false, List.of()));
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> answer(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanup() {
        if (!created.isEmpty()) {
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id IN (:ids)").param("ids", created).update();
        }
        TestLogins.logout(context, token);
    }

    // ------------------------------------------------------------------ the scripted model

    /** Answers by the role in the system prompt; investor agents score at their prior (no reflection issue). */
    private ChatResponse answer(Prompt prompt) {
        prompts.add(prompt);
        List<Message> messages = prompt.getInstructions();
        String system = messages.stream().filter(m -> m instanceof SystemMessage).findFirst().map(Message::getText).orElse("");
        String last = messages.getLast().getText() == null ? "" : messages.getLast().getText();
        AssistantMessage reply;
        if (system.startsWith("You are the RESEARCH agent")) {
            boolean observed = messages.stream().anyMatch(m -> m instanceof ToolResponseMessage);
            reply = observed
                    ? new AssistantMessage("""
                            {"business":"Jewellery maker and gold trader","moat":"Scale in gold processing",\
                            "management":"Pays dividends","growth":"New stores","risks":["Gold price swings"],\
                            "catalysts":["Export growth"],"newsSentiment":"NONE","newsSummary":null,\
                            "evidence":[{"ref":"F1","fact":"Revenue comes from gold jewellery"},\
                            {"ref":"F99","fact":"A made-up excerpt"}]}""")
                    : AssistantMessage.builder().content("Thought: I need the revenue breakdown.")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "searchFilings",
                            "{\"query\":\"revenue by segment\"}")))
                    .build();
        } else if (system.startsWith("You are one of six independent equity analysts analysing ONE")) {
            Matcher m = PRIOR.matcher(messages.stream().map(Message::getText).reduce("", String::concat));
            int score = m.find() ? Math.min(Integer.parseInt(m.group(1)), 60) : 55;
            String verdict = score >= 45 ? "NEUTRAL" : score >= 30 ? "WEAK" : "REJECT";
            reply = new AssistantMessage("{\"score\":" + score + ",\"verdict\":\"" + verdict + "\",\"thesis\":\"Solid but "
                    + "cyclical (F1)\",\"strengths\":[\"Growing revenue\"],\"concerns\":[\"Gold price\"],"
                    + "\"metricsUsed\":[\"revenue\",\"roe_annualized\"]"
                    + (last.contains("REFLECTION REVIEW") ? ",\"note\":\"kept\"" : "") + "}");
        } else if (system.startsWith("You are the chief investment strategist reviewing")) {
            reply = new AssistantMessage("""
                    {"executiveSummary":"HRTA is a gold jewellery business; the agents are neutral.","conviction":"medium",\
                    "thesis":"Cyclical gold play","bullCase":["Exports"],"bearCase":["Gold price"],"keyRisks":["Leverage"],\
                    "monitor":["Gold price"],"dataGaps":["No news stored"],"adjustment":9,"adjustmentReason":"Strong growth"}""");
        } else {
            throw new IllegalStateException("unexpected prompt: " + system);
        }
        CompletionUsage usage = CompletionUsage.builder().promptTokens(1000).completionTokens(100).totalTokens(1100)
                .putAdditionalProperty("cost", JsonValue.from(0.0004)).build();
        return new ChatResponse(List.of(new Generation(reply)), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(1000, 100, 1100, usage)).build());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void options() throws Exception {
        mvc.perform(get("/api/v1/analyses/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.length()").value(6))
                .andExpect(jsonPath("$.companies[?(@.ticker == 'HRTA')].periods").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.greaterThan(0))))
                .andExpect(jsonPath("$.budgetUsd").value(0.2))
                .andExpect(jsonPath("$.synthesisModel").value("anthropic/claude-opus-5.5"));
    }

    @Test
    void analysesHrtaFromItsStoredDataEndToEnd() throws Exception {
        MockHttpServletResponse response = submit(Map.of("ticker", "hrta"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse();
        JsonNode run = json.readTree(response.getContentAsString());
        UUID id = UUID.fromString(run.path("id").asString());
        created.add(id);
        assertThat(run.path("ticker").asString()).isEqualTo("HRTA");
        assertThat(run.path("agents").size()).isEqualTo(6);
        assertThat(run.path("createdBy").path("username").asString()).isEqualTo("admin");

        JsonNode report = awaitFinished(id);
        JsonNode summary = report.path("run");
        assertThat(summary.path("status").asString()).as(summary.path("message").asString()).isEqualTo("SUCCEEDED");
        assertThat(summary.path("overallScore").isNumber()).isTrue();
        assertThat(summary.path("conviction").asString()).isEqualTo("MEDIUM");
        assertThat(summary.path("costUsd").asDouble()).isPositive();
        assertThat(summary.path("modelCalls").asInt()).isGreaterThanOrEqualTo(9);   // research 2, agents 6, synthesis 1 + search

        // the fact sheet from the stored filings: the latest interim period, its comparative, then fiscal years
        JsonNode periods = report.path("context").path("factSheet").path("periods");
        assertThat(periods.path(0).path("period").asString()).isEqualTo("2026 H1");
        assertThat(periods.path(1).path("period").asString()).isEqualTo("2025 H1");
        assertThat(periods.path(2).path("period").asString()).isEqualTo("2025 FY");
        assertThat(periods.path(2).path("income").path("revenue").isNumber()).isTrue();
        assertThat(report.path("context").path("factSheet").path("amountUnit").asString()).startsWith("IDR billions");

        // the research agent searched the stored PDF chunks; the made-up ref was dropped
        JsonNode research = report.path("research");
        assertThat(research.path("retrieved").size()).isPositive();
        assertThat(research.path("retrieved").path(0).path("ref").asString()).isEqualTo("F1");
        assertThat(research.path("retrieved").path(0).path("source").asString()).isEqualTo("PDF");
        assertThat(research.path("brief").path("evidence").size()).isEqualTo(1);
        assertThat(research.path("droppedRefs").asInt()).isEqualTo(1);
        assertThat(research.path("trace").path(0).path("tools").path(0).asString()).startsWith("searchFilings(");

        // six agents, each answered; the synthesis adjustment is capped at 5 points
        assertThat(report.path("agents").size()).isEqualTo(6);
        for (JsonNode agent : report.path("agents")) {
            assertThat(agent.path("status").asString()).isEqualTo("ASSESSED");
            assertThat(agent.path("llmScore").isNumber()).isTrue();
        }
        assertThat(report.path("agents").path(0).path("agent").asString()).isEqualTo("BUFFETT");
        assertThat(report.path("synthesisAdjustment").asDouble()).isEqualTo(5.0);
        assertThat(report.path("synthesis").path("bullCase").path(0).asString()).isEqualTo("Exports");

        // usage per stage: the embedding of the search is metered (estimated), the models as reported
        List<String> stages = new ArrayList<>();
        report.path("usage").forEach(u -> stages.add(u.path("stage").asString()));
        assertThat(stages).contains("RESEARCH", "RETRIEVAL", "AGENT", "SYNTHESIS");
        report.path("usage").forEach(u -> {
            if (u.path("stage").asString().equals("RETRIEVAL")) {
                assertThat(u.path("costEstimated").asBoolean()).isTrue();
            }
        });

        // the agents shared the dossier with the research brief; the synthesis ran on Opus with low effort
        Prompt agentPrompt = prompts.stream().filter(p -> p.getInstructions().getFirst().getText()
                .startsWith("You are one of six")).findFirst().orElseThrow();
        assertThat(agentPrompt.getInstructions().get(1).getText()).startsWith("COMPANY DOSSIER").contains("F1")
                .doesNotContain("F99");
        Prompt synthesisPrompt = prompts.stream().filter(p -> p.getInstructions().getFirst().getText()
                .startsWith("You are the chief")).findFirst().orElseThrow();
        assertThat(((OpenAiChatOptions) synthesisPrompt.getOptions()).getModel()).isEqualTo("anthropic/claude-opus-5.5");

        mvc.perform(get("/api/v1/analyses").param("ticker", "HRTA").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyses[0].id").value(id.toString()))
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.limit").value(5));

        MockHttpServletResponse pdf = mvc.perform(get("/api/v1/analyses/" + id + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse();
        assertThat(pdf.getHeader("Content-Disposition")).contains("attachment").contains("analysis-IDX-HRTA-");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void anActiveAnalysisOfTheSameCompanyIsReturnedNotQueuedAgain() throws Exception {
        UUID id = UUID.randomUUID();
        created.add(id);
        long companyId = jdbc.sql("SELECT company_id FROM company WHERE exchange = 'IDX' AND ticker = 'HRTA'")
                .query(Long.class).single();
        jobs.save(new IngestionJobRepository.Snapshot(id, IngestionJobType.ANALYSIS, IngestionJobStatus.RUNNING,
                "Investor agents", "IDX", "HRTA", null, 1, null, Instant.now(), Instant.now(), null, null, null, null));
        repository.insertRun(new AnalysisRepository.Parameters(id, companyId, "IDX", "HRTA", List.of(InvestorAgent.RISK),
                0.2), "PT Hartadinata Abadi Tbk");

        submit(Map.of("ticker", "HRTA", "agents", List.of("BUFFETT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/v1/analyses/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("RUNNING"))
                .andExpect(jsonPath("$.agents.length()").value(0));
        mvc.perform(get("/api/v1/analyses/" + id + "/pdf")).andExpect(status().isConflict());
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        submit(Map.of("agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose a stock (ticker)"));
        submit(Map.of("ticker", "ZZZZ")).andExpect(status().isNotFound());
        submit(Map.of("ticker", "HR TA")).andExpect(status().isBadRequest());
        submit(Map.of("ticker", "HRTA", "agents", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose at least one investor agent"));
        submit(Map.of("ticker", "HRTA", "agents", List.of("SOROS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Unknown agents: SOROS")));
        submit(Map.of("exchange", "NYSE", "ticker", "HRTA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported exchange"));
        mvc.perform(get("/api/v1/analyses").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analyses").param("offset", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/analyses/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    private ResultActions submit(Object body) throws Exception {
        return mvc.perform(post("/api/v1/analyses").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode awaitFinished(UUID id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            JsonNode report = json.readTree(mvc.perform(get("/api/v1/analyses/" + id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            String status = report.path("run").path("status").asString();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return report;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("analysis " + id + " did not finish: " + report.path("run"));
            }
            Thread.sleep(100);
        }
    }
}
