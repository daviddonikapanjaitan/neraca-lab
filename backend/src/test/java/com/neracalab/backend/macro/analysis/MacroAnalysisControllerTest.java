package com.neracalab.backend.macro.analysis;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.ingestion.file.IngestionFileRepository.StoredFile;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.MacroDocumentType;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.TextChunker;
import com.neracalab.backend.screening.data.FundamentalEtlService;
import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A macro analysis of Indonesia (IHSG) end to end against the Docker Postgres: the stored macro series, indexes,
 * commodities and currency pairs, the real pipeline (research agent with tool calls into the stored histories, the four
 * macro agents, synthesis) with a model that answers by role. Also the validation, the paging, an active analysis and
 * the PDF.
 */
@SpringBootTest
class MacroAnalysisControllerTest {

    private static final Pattern PRIOR = Pattern.compile("Rules prior \\(your preset on the Macro page\\): (\\d+)/100");

    @MockitoBean
    private ChatModel chatModel;

    // the same mocks as AnalysisControllerTest: the two share one application context (and its connection pool)
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
    private MacroAnalysisRepository repository;

    @Autowired
    private IngestionJobRepository jobs;

    @Autowired
    private RagRepository rag;

    @Autowired
    private IngestionFileRepository files;

    private MockMvc mvc;
    private String token;
    private final List<UUID> created = new ArrayList<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> answer(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanup() {
        if (!created.isEmpty()) {
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id IN (:ids)").param("ids", created).update();
            jdbc.sql("DELETE FROM macro_analysis_lesson WHERE source_analysis IN (:ids)").param("ids", created).update();
        }
        TestLogins.logout(context, token);
    }

    // ------------------------------------------------------------------ the scripted model

    /** Answers by the role in the system prompt; macro agents score near their prior with the stance of the band. */
    private ChatResponse answer(Prompt prompt) {
        prompts.add(prompt);
        List<Message> messages = prompt.getInstructions();
        String system = messages.stream().filter(m -> m instanceof SystemMessage).findFirst().map(Message::getText).orElse("");
        String last = messages.getLast().getText() == null ? "" : messages.getLast().getText();
        AssistantMessage reply;
        if (system.startsWith("You are the RESEARCH agent of an AI macro analysis")) {
            boolean observed = messages.stream().anyMatch(m -> m instanceof ToolResponseMessage);
            reply = observed
                    ? new AssistantMessage("""
                            {"regime":"Tight money and a weak index","liquidity":"BI holding, Fed easing",\
                            "inflationRates":"Inflation inside the band","external":"Rupiah stable",\
                            "growth":"Slowing","risks":["Foreign outflows"],"catalysts":["BI cuts"],\
                            "evidence":[{"ref":"T1","fact":"The index fell over the window"},\
                            {"ref":"D1","fact":"BI held the rate to keep the rupiah stable"},\
                            {"ref":"T99","fact":"A made-up result"}]}""")
                    : AssistantMessage.builder().content("Thought: I need the index's history and BI's guidance.")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "history",
                                    "{\"code\":\"IHSG\",\"months\":24}"),
                            new AssistantMessage.ToolCall("call-2", "function", "searchDocuments",
                                    "{\"query\":\"Bank Indonesia guidance\"}")))
                    .build();
        } else if (system.startsWith("You are one of four independent macro strategists")) {
            Matcher m = PRIOR.matcher(messages.stream().map(Message::getText).reduce("", String::concat));
            int score = m.find() ? Math.min(Integer.parseInt(m.group(1)), 64) : 50;
            String stance = MacroAssessment.stanceOf(score);
            reply = new AssistantMessage("{\"score\":" + score + ",\"stance\":\"" + stance + "\",\"thesis\":\"Liquidity "
                    + "decides (T1)\",\"supports\":[\"Rupiah stable\"],\"risks\":[\"Outflows\"],\"buyWhen\":[\"BI cuts\"],"
                    + "\"sellWhen\":[\"Rupiah breaks\"],\"signalsUsed\":[\"liquidity\",\"BI_RATE\"]"
                    + (last.contains("REFLECTION REVIEW") ? ",\"note\":\"kept\"" : "") + "}");
        } else if (system.startsWith("You are the chief macro strategist")) {
            reply = new AssistantMessage("""
                    {"executiveSummary":"Indonesia is selective: tight money, a weak index.","conviction":"medium",\
                    "thesis":"Wait for BI to ease","bullCase":["Cheap valuations"],"bearCase":["Outflows"],\
                    "keyRisks":["Rupiah"],"monitor":["BI rate"],"sectorTilts":[{"sector":"Coal miners",\
                    "tilt":"overweight","why":"Coal prices up"},{"sector":"Banks","tilt":"SIDEWAYS","why":"Unclear"}],\
                    "dataGaps":["No CDS"],"adjustment":9,"adjustmentReason":"Turn ahead"}""");
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
        mvc.perform(get("/api/v1/macro/analyses/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.length()").value(4))
                .andExpect(jsonPath("$.agents[0].code").value("TUDOR_JONES"))
                .andExpect(jsonPath("$.countries[0].code").value("ID"))
                .andExpect(jsonPath("$.countries[0].available").value(true))
                .andExpect(jsonPath("$.countries[1].available").value(false))
                .andExpect(jsonPath("$.budgetUsd").value(0.2))
                .andExpect(jsonPath("$.data.macroSeriesTotal").isNumber())
                .andExpect(jsonPath("$.synthesisModel").value("anthropic/claude-opus-5.5"));
    }

    /** Unit vector along one axis (the stub embedding of every query, and of the test document's chunk). */
    private static float[] axis(int i) {
        float[] v = new float[1536];
        v[i] = 1f;
        return v;
    }

    /** A macro document of Indonesia in the vector store, one chunk along axis 1500; the document id. */
    private long storeMacroDocument(String title, LocalDate published, List<Long> fileIds) {
        StoredFile file = files.store(("%PDF-macro " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), title + ".pdf",
                "application/pdf");
        fileIds.add(file.fileId());
        return rag.storeMacro(new RagRepository.NewMacroDocument("ID", file.checksumSha256(), title, "Bank Indonesia",
                        MacroDocumentType.MONETARY_POLICY, published.atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant(),
                        file.fileId(), title + ".pdf", 1, 60, "stub-embedding", null),
                List.of(new TextChunker.Chunk(0, "Bank Indonesia held the BI-Rate to keep the rupiah stable.", 1, 1)),
                List.of(axis(1500)));
    }

    /**
     * The whole pipeline, with two macro documents of Indonesia stored: the one published by the as-of date is listed to
     * the research agent and found by its document search (ref D1), the one published after it is not read.
     */
    @Test
    void analysesIndonesiaFromTheStoredMacroDataEndToEnd() throws Exception {
        String readable = "Monetary Policy Review " + UUID.randomUUID();
        String later = "Monetary Policy Review of the future " + UUID.randomUUID();
        List<Long> documentIds = new ArrayList<>();
        List<Long> fileIds = new ArrayList<>();
        when(embeddings.embed(anyString())).thenReturn(axis(1500));
        when(embeddings.model()).thenReturn("stub-embedding");
        try {
            documentIds.add(storeMacroDocument(readable, LocalDate.of(2020, 1, 15), fileIds));
            documentIds.add(storeMacroDocument(later, LocalDate.of(2099, 1, 15), fileIds));
            runEndToEnd(readable, later);
        } finally {
            jdbc.sql("DELETE FROM rag_document WHERE document_id IN (:ids)").param("ids", documentIds).update();
            jdbc.sql("DELETE FROM ingestion_file WHERE file_id IN (:ids)").param("ids", fileIds).update();
        }
    }

    private void runEndToEnd(String readable, String later) throws Exception {
        MockHttpServletResponse response = submit(Map.of("country", "id", "index", "ihsg"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse();
        JsonNode run = json.readTree(response.getContentAsString());
        UUID id = UUID.fromString(run.path("id").asString());
        created.add(id);
        assertThat(run.path("country").asString()).isEqualTo("ID");
        assertThat(run.path("indexCode").asString()).isEqualTo("IHSG");
        assertThat(run.path("agents").size()).isEqualTo(4);
        assertThat(run.path("createdBy").path("username").asString()).isEqualTo("admin");

        JsonNode report = awaitFinished(id);
        JsonNode summary = report.path("run");
        assertThat(summary.path("status").asString()).as(summary.path("message").asString()).isEqualTo("SUCCEEDED");
        assertThat(summary.path("overallScore").isNumber()).isTrue();
        assertThat(summary.path("rulesScore").isNumber()).isTrue();
        assertThat(List.of("INVESTABLE", "SELECTIVE", "NOT_INVESTABLE")).contains(summary.path("verdict").asString());
        assertThat(List.of("BUY", "ACCUMULATE", "WAIT", "SELL")).contains(summary.path("action").asString());
        assertThat(summary.path("conviction").asString()).isEqualTo("MEDIUM");
        assertThat(summary.path("asOfDate").asString()).isNotBlank();
        assertThat(summary.path("costUsd").asDouble()).isPositive();
        assertThat(summary.path("modelCalls").asInt()).isGreaterThanOrEqualTo(7);   // research 2, agents 4, synthesis 1

        // what the agents saw: the rules' view without its chart points
        JsonNode ctx = report.path("context");
        assertThat(ctx.path("subject").path("index").asString()).isEqualTo("IHSG");
        assertThat(ctx.path("presets").size()).isEqualTo(4);
        assertThat(ctx.path("rulesConclusion").path("score").isNumber()).isTrue();
        assertThat(ctx.path("index").has("points")).isFalse();
        assertThat(ctx.path("series").path(0).has("points")).isFalse();
        assertThat(ctx.path("indexes").size()).isGreaterThanOrEqualTo(1);
        assertThat(ctx.path("markets").has("termsOfTrade")).isTrue();

        // the research agent read the stored history and the macro document published by the as-of date; the made-up
        // ref was dropped
        JsonNode research = report.path("research");
        assertThat(research.path("retrieved").path(0).path("ref").asString()).isEqualTo("T1");
        assertThat(research.path("retrieved").path(0).path("tool").asString()).isEqualTo("history");
        assertThat(research.path("retrieved").path(1).path("ref").asString()).isEqualTo("D1");
        assertThat(research.path("retrieved").path(1).path("tool").asString()).isEqualTo("searchDocuments");
        assertThat(research.path("retrieved").path(1).path("arguments").asString()).isEqualTo("Bank Indonesia guidance");
        assertThat(research.path("retrieved").path(1).path("summary").asString())
                .startsWith("Bank Indonesia - " + readable + " (2020-01-15, p. 1): Bank Indonesia held the BI-Rate");
        assertThat(research.toString()).doesNotContain(later);
        List<String> listed = new ArrayList<>();
        research.path("documents").forEach(d -> listed.add(d.path("title").asString()));
        assertThat(listed).contains(readable).doesNotContain(later);
        assertThat(research.path("brief").path("evidence").size()).isEqualTo(2);
        assertThat(research.path("brief").path("evidence").path(1).path("ref").asString()).isEqualTo("D1");
        assertThat(research.path("droppedRefs").asInt()).isEqualTo(1);
        assertThat(research.path("trace").path(0).path("tools").path(0).asString()).startsWith("history(");
        assertThat(research.path("trace").path(0).path("tools").path(1).asString()).startsWith("searchDocuments(");
        List<String> messages = new ArrayList<>();
        report.path("notes").path("messages").forEach(m -> messages.add(m.asString()));
        assertThat(messages).anyMatch(m -> m.contains("macro documents of Indonesia were published after")
                && m.endsWith("and were not read (point in time)."));
        Prompt researchPrompt = prompts.stream().filter(p -> p.getInstructions().getFirst().getText()
                .startsWith("You are the RESEARCH agent")).findFirst().orElseThrow();
        assertThat(researchPrompt.getInstructions().get(1).getText()).contains("Macro documents searchDocuments can read")
                .contains("2020-01-15 Bank Indonesia: " + readable + " (monetary policy)").doesNotContain(later);

        // four agents in their order, each answered; the synthesis adjustment is capped at 5 points
        assertThat(report.path("agents").size()).isEqualTo(4);
        assertThat(report.path("agents").path(0).path("agent").asString()).isEqualTo("TUDOR_JONES");
        assertThat(report.path("agents").path(3).path("agent").asString()).isEqualTo("DALIO");
        for (JsonNode agent : report.path("agents")) {
            assertThat(List.of("ASSESSED", "REVISED")).contains(agent.path("status").asString());
            assertThat(agent.path("aiScore").isNumber()).isTrue();
            assertThat(agent.path("finalScore").isNumber()).isTrue();
            assertThat(agent.path("buyWhen").path(0).asString()).isEqualTo("BI cuts");
        }
        assertThat(report.path("synthesisAdjustment").asDouble()).isEqualTo(5.0);
        assertThat(report.path("synthesis").path("sectorTilts").path(0).path("tilt").asString()).isEqualTo("OVERWEIGHT");
        assertThat(report.path("synthesis").path("sectorTilts").path(1).path("tilt").asString()).isEqualTo("NEUTRAL");
        double mean = report.path("synthesis").path("agentsMean").asDouble();
        double rules = summary.path("rulesScore").asDouble();
        assertThat(summary.path("overallScore").asDouble())
                .isCloseTo(Math.max(0, Math.min(100, Math.round((0.8 * mean + 0.2 * rules) * 10) / 10.0 + 5)),
                        org.assertj.core.data.Offset.offset(0.11));

        // usage per stage
        List<String> stages = new ArrayList<>();
        report.path("usage").forEach(u -> stages.add(u.path("stage").asString()));
        assertThat(stages).contains("RESEARCH", "RETRIEVAL", "AGENT", "SYNTHESIS");

        // the agents shared the dossier with the research brief; the synthesis ran on Opus
        Prompt agentPrompt = prompts.stream().filter(p -> p.getInstructions().getFirst().getText()
                .startsWith("You are one of four")).findFirst().orElseThrow();
        // no chart points ([[epoch, value], ...]); the presets' evidence lists are called "points" too
        assertThat(agentPrompt.getInstructions().get(1).getText()).startsWith("MACRO DOSSIER").contains("T1")
                .contains("\"ref\":\"D1\"").doesNotContain("T99").doesNotContain("\"points\":[[");
        Prompt synthesisPrompt = prompts.stream().filter(p -> p.getInstructions().getFirst().getText()
                .startsWith("You are the chief macro strategist")).findFirst().orElseThrow();
        assertThat(((OpenAiChatOptions) synthesisPrompt.getOptions()).getModel()).isEqualTo("anthropic/claude-opus-5.5");

        mvc.perform(get("/api/v1/macro/analyses").param("country", "ID").param("index", "IHSG").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyses[0].id").value(id.toString()))
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.limit").value(5));

        MockHttpServletResponse pdf = mvc.perform(get("/api/v1/macro/analyses/" + id + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse();
        assertThat(pdf.getHeader("Content-Disposition")).contains("attachment").contains("macro-analysis-ID-IHSG-");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void anActiveAnalysisOfTheSameIndexIsReturnedNotQueuedAgain() throws Exception {
        UUID id = UUID.randomUUID();
        created.add(id);
        jobs.save(new IngestionJobRepository.Snapshot(id, IngestionJobType.MACRO_ANALYSIS, IngestionJobStatus.RUNNING,
                "Macro agents", "IDX", "LQ45", null, 1, null, Instant.now(), Instant.now(), null, null, null, null));
        repository.insertRun(new MacroAnalysisRepository.Parameters(id, "ID", "LQ45", List.of(MacroAgent.SOROS), 0.2),
                "Indonesia", "LQ45");

        submit(Map.of("country", "ID", "index", "LQ45", "agents", List.of("DALIO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/v1/macro/analyses/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("RUNNING"))
                .andExpect(jsonPath("$.agents.length()").value(0));
        mvc.perform(get("/api/v1/macro/analyses/" + id + "/pdf")).andExpect(status().isConflict());
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        submit(Map.of("country", "US"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not available yet")));
        submit(Map.of("country", "ID", "index", "ZZZ")).andExpect(status().isBadRequest());
        submit(Map.of("country", "ID", "agents", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose at least one macro agent"));
        submit(Map.of("country", "ID", "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Unknown agents: BUFFETT")));
        mvc.perform(get("/api/v1/macro/analyses").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/macro/analyses").param("index", "IH SG")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/macro/analyses/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void agentNamesAreParsedByCodePresetOrName() {
        assertThat(MacroAgent.parse("ptj")).isEqualTo(MacroAgent.TUDOR_JONES);
        assertThat(MacroAgent.parse("Paul Tudor Jones")).isEqualTo(MacroAgent.TUDOR_JONES);
        assertThat(MacroAgent.parse("tudor jones")).isEqualTo(MacroAgent.TUDOR_JONES);
        assertThat(MacroAgent.parse("Stanley Druckenmiller")).isEqualTo(MacroAgent.DRUCKENMILLER);
        assertThat(MacroAgent.parse("soros")).isEqualTo(MacroAgent.SOROS);
        assertThat(MacroAgent.parse("DALIO")).isEqualTo(MacroAgent.DALIO);
        assertThat(MacroAgent.parse("Buffett")).isNull();
        assertThat(MacroAgent.parse(" ")).isNull();
    }

    private ResultActions submit(Object body) throws Exception {
        return mvc.perform(post("/api/v1/macro/analyses").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode awaitFinished(UUID id) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            JsonNode report = json.readTree(mvc.perform(get("/api/v1/macro/analyses/" + id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            String status = report.path("run").path("status").asString();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return report;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("macro analysis " + id + " did not finish: " + report.path("run"));
            }
            Thread.sleep(100);
        }
    }
}
