package com.neracalab.backend.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Screening API against the Docker Postgres. The pipeline ({@link ScreeningService}) is a mock that
 * writes a small report and finishes the job, so no market data, crawler or model is used.
 */
@SpringBootTest
class ScreeningControllerTest {

    @MockitoBean
    private ScreeningService service;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private ScreeningRepository repository;

    @Autowired
    private IngestionJobRepository jobs;

    private MockMvc mvc;
    private String token;
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void login() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            jobs.running(id, "Stage 1");
            repository.saveStage1(id, java.time.LocalDate.of(2026, 10, 2), 837, 40, 2,
                    List.of(Map.of("key", "universe", "label", "Active listings", "remaining", 837)));
            long a = repository.insertCandidate(id, "AAAA", "PT Aaaa Tbk", "Industrials", "Machinery",
                    Map.of("roe", 0.2, "pe", 10.0), 70.0, 1);
            long b = repository.insertCandidate(id, "BBBB", "PT Bbbb Tbk", "Financial Services", "Banks", Map.of("roe", 0.15),
                    60.0, 2);
            repository.saveAgentScore(a, InvestorAgent.BUFFETT, 70, null, 80.0, 74.0, "FIT", "Durable franchise",
                    List.of("High ROE"), List.of(), null, "ASSESSED");
            repository.saveAgentScore(b, InvestorAgent.BUFFETT, 60, null, null, 60.0, null, null, List.of(), List.of(),
                    Map.of("error", "Cost budget reached"), "QUANT_ONLY");
            repository.saveFinal(a, 74.0, null, 1, true, "HIGH", "Durable franchise at a fair price", List.of());
            repository.saveFinal(b, 60.0, null, 2, false, null, null, List.of("Limited data"));
            repository.saveResult(id, 1, Map.of("executiveSummary", "One stock selected.", "portfolioNotes", List.of("Note")),
                    Map.of("messages", List.of()));
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, "Top 1 of 2", null, null);
            return null;
        }).when(service).run(any());
    }

    @AfterEach
    void cleanup() {
        if (!created.isEmpty()) {
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id IN (:ids)").param("ids", created).update();
        }
        TestLogins.logout(context, token);
    }

    @Test
    void options() throws Exception {
        mvc.perform(get("/api/v1/screenings/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchanges[0].code").value("IDX"))
                .andExpect(jsonPath("$.marketCapTiers.length()").value(3))
                .andExpect(jsonPath("$.marketCapTiers[1].code").value("MID"))
                .andExpect(jsonPath("$.agents.length()").value(6))
                .andExpect(jsonPath("$.agents[4].label").value("Keith Gill (Roaring Kitty)"))
                .andExpect(jsonPath("$.defaultTopN").value(25))
                .andExpect(jsonPath("$.maxTopN").value(50))
                .andExpect(jsonPath("$.data.exchange").value("IDX"));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        submit(Map.of("marketCapTier", "HUGE", "topN", 25, "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("marketCapTier must be one of LARGE, MID, SMALL"));
        submit(Map.of("marketCapTier", "LARGE", "topN", 0, "agents", List.of("BUFFETT"))).andExpect(status().isBadRequest());
        submit(Map.of("marketCapTier", "LARGE", "topN", 51, "agents", List.of("BUFFETT"))).andExpect(status().isBadRequest());
        submit(Map.of("marketCapTier", "LARGE", "topN", 25, "agents", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose at least one investor agent"));
        submit(Map.of("marketCapTier", "LARGE", "topN", 25, "agents", List.of("SOROS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Unknown agents: SOROS")));
        submit(Map.of("exchange", "NYSE", "marketCapTier", "LARGE", "topN", 25, "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported exchange"));
        mvc.perform(get("/api/v1/screenings?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/screenings/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void runIsQueuedProcessedAndReportedAsJsonAndPdf() throws Exception {
        MockHttpServletResponse response = submit(Map.of("exchange", "idx", "marketCapTier", "medium", "topN", 10,
                "agents", List.of("RISK", "Warren Buffett", "BUFFETT")))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse();
        JsonNode run = json.readTree(response.getContentAsString());
        UUID id = UUID.fromString(run.path("id").asString());
        created.add(id);
        assertThat(run.path("marketCapTier").asString()).isEqualTo("MID");
        assertThat(run.path("topN").asInt()).isEqualTo(10);
        // duplicates removed, fixed order
        assertThat(run.path("agents").toString()).isEqualTo("[\"BUFFETT\",\"RISK\"]");
        assertThat(run.path("createdBy").path("username").asString()).isEqualTo("admin");

        JsonNode report = awaitFinished(id);
        assertThat(report.path("run").path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(report.path("run").path("universeCount").asInt()).isEqualTo(837);
        assertThat(report.path("candidates").size()).isEqualTo(2);
        JsonNode first = report.path("candidates").path(0);
        assertThat(first.path("ticker").asString()).isEqualTo("AAAA");
        assertThat(first.path("selected").asBoolean()).isTrue();
        assertThat(first.path("agents").path(0).path("label").asString()).isEqualTo("Warren Buffett");
        assertThat(report.path("candidates").path(1).path("agents").path(0).path("status").asString()).isEqualTo("QUANT_ONLY");
        assertThat(report.path("synthesis").path("executiveSummary").asString()).isEqualTo("One stock selected.");
        assertThat(report.path("funnel").path(0).path("remaining").asInt()).isEqualTo(837);

        mvc.perform(get("/api/v1/screenings?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));

        MockHttpServletResponse pdf = mvc.perform(get("/api/v1/screenings/" + id + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse();
        assertThat(pdf.getHeader("Content-Disposition")).contains("attachment").contains("screening-IDX-mid-top10-");
        byte[] bytes = pdf.getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void companiesThatCanBeSelected() throws Exception {
        mvc.perform(get("/api/v1/screenings/companies?exchange=idx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchange").value("IDX"))
                .andExpect(jsonPath("$.maxSelected").value(100))
                .andExpect(jsonPath("$.companies[?(@.ticker == 'HRTA')].companyName").exists());
        mvc.perform(get("/api/v1/screenings/companies?exchange=NYSE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported exchange"));
    }

    @Test
    void invalidSelectionsAreRejected() throws Exception {
        submit(Map.of("tickers", List.of(), "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose at least one stock"));
        submit(Map.of("marketCapTier", "LARGE", "tickers", List.of("HRTA"), "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Give either marketCapTier or tickers, not both"));
        submit(Map.of("tickers", List.of("HRTA", "ZZZZ9"), "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Not in the IDX companies table: ZZZZ9"));
        submit(Map.of("tickers", List.of("HR TA"), "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Invalid ticker 'HR TA'")));
        // top N at most the number of selected stocks
        submit(Map.of("tickers", List.of("HRTA"), "topN", 2, "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("topN must be between 1 and 1"));
        submit(Map.of("tickers", List.of("HRTA"), "agents", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose at least one investor agent"));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add("T" + i);
        }
        submit(Map.of("tickers", tooMany, "agents", List.of("BUFFETT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose at most 100 stocks (101 selected)"));
        mvc.perform(get("/api/v1/screenings?scope=OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("scope must be one of ALL, TIER, SELECTION"));
    }

    @Test
    void selectionRunIsQueuedProcessedAndListedByScope() throws Exception {
        MockHttpServletResponse response = submit(Map.of("exchange", "IDX", "tickers", List.of(" hrta", "HRTA"),
                "agents", List.of("LYNCH")))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        JsonNode run = json.readTree(response.getContentAsString());
        UUID id = UUID.fromString(run.path("id").asString());
        created.add(id);
        assertThat(run.path("marketCapTier").isNull()).isTrue();
        // normalized, duplicates removed
        assertThat(run.path("tickers").toString()).isEqualTo("[\"HRTA\"]");
        // default top N: at most the number of selected stocks
        assertThat(run.path("topN").asInt()).isEqualTo(1);
        assertThat(repository.parameters(id).orElseThrow().selection()).isTrue();

        JsonNode report = awaitFinished(id);
        assertThat(report.path("run").path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(report.path("run").path("tickers").path(0).asString()).isEqualTo("HRTA");

        mvc.perform(get("/api/v1/screenings?limit=5&scope=selection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()));
        JsonNode tierRuns = json.readTree(mvc.perform(get("/api/v1/screenings?limit=200&scope=TIER"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        tierRuns.forEach(r -> assertThat(r.path("id").asString()).isNotEqualTo(id.toString()));

        MockHttpServletResponse pdf = mvc.perform(get("/api/v1/screenings/" + id + "/pdf"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(pdf.getHeader("Content-Disposition")).contains("screening-IDX-selection-top1-");
    }

    @Test
    void aRunScreensEitherATierOrSelectedStocks() {
        UUID id = UUID.randomUUID();
        created.add(id);
        jobs.save(new IngestionJobRepository.Snapshot(id, com.neracalab.backend.job.IngestionJobType.SCREENING,
                IngestionJobStatus.QUEUED, "Waiting", "IDX", null, null, 0, null, Instant.now(), null, null, null, null,
                null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.insertRun(new ScreeningRepository.RunParameters(
                        id, "IDX", MarketCapTier.LARGE, List.of("HRTA"), 1, List.of(InvestorAgent.LYNCH), 0.45)))
                .hasMessageContaining("ck_screening_run_scope");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.insertRun(new ScreeningRepository.RunParameters(
                        id, "IDX", null, null, 1, List.of(InvestorAgent.LYNCH), 0.45)))
                .hasMessageContaining("ck_screening_run_scope");
    }

    @Test
    void pdfOfAnActiveRunIsAConflict() throws Exception {
        UUID id = UUID.randomUUID();
        created.add(id);
        jobs.save(new IngestionJobRepository.Snapshot(id, com.neracalab.backend.job.IngestionJobType.SCREENING,
                IngestionJobStatus.RUNNING, "Investor agents", "IDX", null, null, 1, null, Instant.now(), Instant.now(),
                null, null, null, null));
        repository.insertRun(new ScreeningRepository.RunParameters(id, "IDX", MarketCapTier.LARGE, null, 25,
                List.of(InvestorAgent.LYNCH), 0.45));
        mvc.perform(get("/api/v1/screenings/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("RUNNING"))
                .andExpect(jsonPath("$.run.stage").value("Investor agents"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
        mvc.perform(get("/api/v1/screenings/" + id + "/pdf")).andExpect(status().isConflict());
    }

    private ResultActions submit(Object body) throws Exception {
        return mvc.perform(post("/api/v1/screenings").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode awaitFinished(UUID id) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            JsonNode report = json.readTree(mvc.perform(get("/api/v1/screenings/" + id)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            String status = report.path("run").path("status").asString();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return report;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("run " + id + " did not finish: " + report.path("run"));
            }
            Thread.sleep(100);
        }
    }
}
