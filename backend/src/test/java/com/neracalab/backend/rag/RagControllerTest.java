package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;

import tools.jackson.databind.json.JsonMapper;

/**
 * RAG API. The embedding model is a deterministic stub (no provider call); a queued PDF really runs through the
 * worker and is stored in the vector store, and is removed again afterwards.
 */
@SpringBootTest
class RagControllerTest {

    /** Same text, same vector: the vector of a text sets the axis of its first word's hash. */
    @TestConfiguration
    static class StubEmbeddings {

        @Bean
        @Primary
        EmbeddingClient stubEmbeddingClient(JsonMapper json) {
            return new EmbeddingClient(EmbeddingClientTest.properties(), json, "http://localhost", "unused") {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(t -> RagRepositoryTest.axis(
                            Math.floorMod(t.trim().split("\\s+")[0].hashCode(), RagProperties.STORE_DIMENSIONS))).toList();
                }

                @Override
                public String model() {
                    return "stub-embedding";
                }
            };
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionJobRepository jobs;

    private MockMvc mvc;
    private String token;

    @BeforeEach
    void loginAsRoot() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
    }

    @AfterEach
    void logout() {
        TestLogins.logout(context, token);
    }

    /** A one-page text PDF, unique per call (so it is a new stored file). */
    private static byte[] pdf(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private IngestionJob awaitFinished(UUID id) throws InterruptedException {
        Instant until = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(until)) {
            IngestionJob job = jobs.find(id).orElseThrow();
            if (!job.status().active()) {
                return job;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("job " + id + " did not finish");
    }

    @Test
    void storesAnUploadedPdfOfTheCompanyAndFindsIt() throws Exception {
        String marker = "Zamrud" + UUID.randomUUID().toString().replace("-", "");
        byte[] content = pdf(marker + " penjualan emas perhiasan naik");
        MockMultipartFile file = new MockMultipartFile("file", "C:\\fakepath\\Laporan-HRTA.pdf", "application/pdf", content);
        UUID id = null;
        try {
            String body = mvc.perform(multipart("/api/v1/rag/pdf").file(file).param("exchange", "idx").param("ticker", " hrta"))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/ingestions/")))
                    .andExpect(jsonPath("$.type").value("RAG_PDF"))
                    .andExpect(jsonPath("$.exchange").value("IDX"))
                    .andExpect(jsonPath("$.ticker").value("HRTA"))
                    .andExpect(jsonPath("$.file.fileName").value("Laporan-HRTA.pdf"))
                    .andReturn().getResponse().getContentAsString();
            id = UUID.fromString(json.readTree(body).path("id").asString());

            IngestionJob job = awaitFinished(id);
            assertThat(job.status()).as(job.message()).isEqualTo(IngestionJobStatus.SUCCEEDED);
            assertThat(job.type()).isEqualTo(IngestionJobType.RAG_PDF);
            assertThat(job.createdBy().username()).isEqualTo("admin");
            assertThat(job.result().path("chunks").asInt()).isEqualTo(1);
            assertThat(job.result().path("pages").asInt()).isEqualTo(1);
            assertThat(job.result().path("embeddingModel").asString()).isEqualTo("stub-embedding");

            mvc.perform(get("/api/v1/rag/documents").param("ticker", "HRTA").param("source", "pdf"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents[?(@.fileName == 'Laporan-HRTA.pdf')].chunks").value(1));
            mvc.perform(get("/api/v1/rag/documents").param("tickerPrefix", "hr").param("source", "PDF"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.documents[?(@.fileName == 'Laporan-HRTA.pdf')]").exists())
                    .andExpect(jsonPath("$.documents[?(@.ticker =~ /^(?!HR).*/)]").isEmpty());
            mvc.perform(get("/api/v1/rag/search").param("q", marker + " apa saja").param("ticker", "HRTA").param("limit", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].ticker").value("HRTA"))
                    .andExpect(jsonPath("$[0].sourceType").value("PDF"))
                    .andExpect(jsonPath("$[0].pageFrom").value(1))
                    .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.startsWith(marker)));
        } finally {
            if (id != null) {
                Long fileId = jobs.find(id).map(j -> j.file() == null ? null : j.file().fileId()).orElse(null);
                jdbc.sql("DELETE FROM rag_document WHERE job_id = :id").param("id", id).update();
                jdbc.sql("DELETE FROM ingestion_job WHERE job_id = :id").param("id", id).update();
                if (fileId != null) {
                    jdbc.sql("DELETE FROM ingestion_file WHERE file_id = :f AND checksum_sha256 = :c")
                            .param("f", fileId).param("c", IngestionFileRepository.sha256(content)).update();
                }
            }
        }
    }

    @Test
    void rejectsWrongPdfUploads() throws Exception {
        byte[] text = "not a pdf".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "a.txt", "text/plain", text))
                        .param("ticker", "HRTA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only .pdf files are accepted"));
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "a.pdf", "application/pdf", text))
                        .param("ticker", "HRTA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The file is not a PDF (no %PDF header)"));
        byte[] blank;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            blank = out.toByteArray();
        }
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "scan.pdf", "application/pdf", blank))
                        .param("ticker", "HRTA"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Invalid PDF"));
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf("x")))
                        .param("ticker", "NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Company not found"));
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf("x")))
                        .param("exchange", "NYSE").param("ticker", "HRTA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unsupported exchange"));
    }

    @Test
    void rejectsWrongNewsRanges() throws Exception {
        LocalDate today = LocalDate.now(Exchange.IDX.zone());
        mvc.perform(post("/api/v1/rag/news").param("ticker", "HRTA")
                        .param("from", today.toString()).param("to", today.minusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is after the end date")));
        mvc.perform(post("/api/v1/rag/news").param("ticker", "HRTA")
                        .param("from", today.toString()).param("to", today.plusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is in the future")));
        mvc.perform(post("/api/v1/rag/news").param("ticker", "HRTA")
                        .param("from", today.minusDays(366).toString()).param("to", today.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The range is longer than 366 days"));
        mvc.perform(post("/api/v1/rag/news").param("ticker", "NOPE")
                        .param("from", today.toString()).param("to", today.toString()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/rag/news").param("ticker", "HRTA").param("from", "2026-13-01").param("to", today.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validatesSearchAndListing() throws Exception {
        mvc.perform(get("/api/v1/rag/search").param("q", "  "))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rag/documents").param("source", "VIDEO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown source 'VIDEO'; use PDF or NEWS"));
        mvc.perform(get("/api/v1/rag/documents").param("limit", "5").param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.offset").value(5))
                .andExpect(jsonPath("$.documents").isArray());
        mvc.perform(get("/api/v1/rag/documents").param("offset", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rag/documents").param("tickerPrefix", "H%"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsTheSettings() throws Exception {
        mvc.perform(get("/api/v1/rag/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingModel").value("stub-embedding"))
                .andExpect(jsonPath("$.embeddingDimensions").value(1536))
                .andExpect(jsonPath("$.maxRangeDays").value(366))
                .andExpect(jsonPath("$.newsMaxArticles").value(60))
                .andExpect(jsonPath("$.tavilyEnabled").doesNotExist())
                .andExpect(jsonPath("$.stored.chunks").isNumber());
    }

    @Test
    void needsTheIngestionPermission() throws Exception {
        org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/api/v1/rag/documents"))
                .andExpect(status().isUnauthorized());
    }
}
