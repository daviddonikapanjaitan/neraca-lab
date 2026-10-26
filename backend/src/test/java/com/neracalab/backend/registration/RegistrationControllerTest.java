package com.neracalab.backend.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.hamcrest.Matchers;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.RagProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * A registration report uploaded on Ingestion > PDF Documents (RAG) through the worker into the vector store and the
 * directors' and commissioners' shares, read back by Companies > Registration. A company of its own (ZZRG) and stub
 * embeddings (no provider call); everything is removed afterwards.
 */
@SpringBootTest
@DirtiesContext     // a context of its own (the stub embeddings): closed afterwards, so its connections do not pile up
class RegistrationControllerTest {

    @TestConfiguration
    static class StubEmbeddings {

        @Bean
        @Primary
        EmbeddingClient stubEmbeddingClient(RagProperties properties, JsonMapper json) {
            return new EmbeddingClient(properties, json, "http://localhost", "unused") {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(t -> {
                        float[] v = new float[RagProperties.STORE_DIMENSIONS];
                        v[Math.floorMod(t.hashCode(), v.length)] = 1;
                        return v;
                    }).toList();
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
    private final List<UUID> jobIds = new ArrayList<>();
    private final List<byte[]> uploaded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        jdbc.sql("DELETE FROM company WHERE exchange = 'IDX' AND ticker = 'ZZRG'").update();
        jdbc.sql("""
                INSERT INTO company (ticker, exchange, company_name, currency, active)
                VALUES ('ZZRG', 'IDX', 'PT Uji ZZRG Tbk', 'IDR', true)""").update();
    }

    @AfterEach
    void cleanUp() {
        // the company's documents and reports go with it (ON DELETE CASCADE); then the jobs and the files
        jdbc.sql("DELETE FROM company WHERE exchange = 'IDX' AND ticker = 'ZZRG'").update();
        for (UUID id : jobIds) {
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id = :id").param("id", id).update();
        }
        for (byte[] content : uploaded) {
            jdbc.sql("""
                    DELETE FROM ingestion_file f WHERE checksum_sha256 = :c
                      AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.file_id = f.file_id)
                      AND NOT EXISTS (SELECT 1 FROM rag_document d WHERE d.file_id = f.file_id)""")
                    .param("c", IngestionFileRepository.sha256(content)).update();
        }
        TestLogins.logout(context, token);
    }

    private IngestionJob upload(byte[] content, String fileName, String ticker, String documentType) throws Exception {
        uploaded.add(content);
        MockMultipartFile file = new MockMultipartFile("file", fileName, "application/pdf", content);
        var request = multipart("/api/v1/rag/pdf").file(file).param("ticker", ticker);
        if (documentType != null) {
            request.param("documentType", documentType);
        }
        String body = mvc.perform(request).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("id").asString());
        jobIds.add(id);
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

    /** A September 2026 report of ZZRG: Budi bought 2,500,000, Siti sold 1,000,000; a unique letter per call. */
    private static byte[] september(String filedAt) {
        return new LbrePdf("ZZRG", "September", 2026, filedAt, false)
                .row("BUDI SANTOSO", "Direktur Utama", "10.000.000", "1%", "12.500.000", "1,25%", false)
                .row("SITI AMINAH", "Komisaris", "5.000.000", "0,5%", "4.000.000", "0,4%", true)
                .bytes();
    }

    @Test
    void aRegistrationReportIsStoredAndReadByCompaniesRegistration() throws Exception {
        byte[] report = september("06-10-2026 09:30");
        IngestionJob job = upload(report, "registrasi_zzrg_september.pdf", "zzrg", "registration");
        assertThat(job.status()).as(job.stage() + " " + job.message()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(job.stage()).contains("registration 2026-09: 2 directors and commissioners, 2 changes");
        assertThat(job.result().path("documentType").asString()).isEqualTo("REGISTRATION");
        assertThat(job.result().path("registration").path("period").asString()).isEqualTo("2026-09");
        assertThat(job.result().path("registration").path("filedAt").asString()).isEqualTo("2026-10-06T09:30");
        assertThat(job.result().path("registration").path("outcome").asString()).isEqualTo("STORED");
        assertThat(job.result().path("chunks").asInt()).isPositive();

        // the PDF is in the vector store as a registration report of the company
        mvc.perform(get("/api/v1/rag/documents").param("ticker", "ZZRG").param("documentType", "REGISTRATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.documents[0].documentType").value("REGISTRATION"));

        mvc.perform(get("/api/v1/registrations").param("tickers", " zzrg, ZZRG "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.rows[0].holderName").value("BUDI SANTOSO"))
                .andExpect(jsonPath("$.rows[0].period").value("2026-09"))
                .andExpect(jsonPath("$.rows[0].position").value("Direktur Utama"))
                .andExpect(jsonPath("$.rows[0].role").value("DIRECTOR"))
                .andExpect(jsonPath("$.rows[0].sharesPrevious").value(10_000_000))
                .andExpect(jsonPath("$.rows[0].sharesCurrent").value(12_500_000))
                .andExpect(jsonPath("$.rows[0].change").value(2_500_000))
                .andExpect(jsonPath("$.rows[0].percentCurrent").value(1.25))
                .andExpect(jsonPath("$.rows[1].holderName").value("SITI AMINAH"))
                .andExpect(jsonPath("$.rows[1].change").value(-1_000_000))
                .andExpect(jsonPath("$.rows[1].controllerAffiliate").value(true));
        mvc.perform(get("/api/v1/registrations").param("tickers", "ZZRG").param("transaction", "buy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].holderName").value(Matchers.contains("BUDI SANTOSO")));
        mvc.perform(get("/api/v1/registrations").param("tickers", "ZZRG").param("transaction", "SELL")
                        .param("role", "commissioner").param("from", "2026-09").param("to", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].holderName").value(Matchers.contains("SITI AMINAH")));
        mvc.perform(get("/api/v1/registrations").param("tickers", "ZZRG").param("from", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/registrations/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[?(@.ticker == 'ZZRG')].reports").value(Matchers.contains(1)))
                .andExpect(jsonPath("$.transactions[*].code").value(Matchers.contains("ALL", "BUY", "SELL", "CHANGED")))
                .andExpect(jsonPath("$.roles[*].code").value(Matchers.contains("DIRECTOR", "COMMISSIONER", "OTHER")));
        mvc.perform(get("/api/v1/registrations/reports").param("ticker", "zzrg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].period").value("2026-09"))
                .andExpect(jsonPath("$[0].letterNumber").value("001/ZZRG/X/2026"))
                .andExpect(jsonPath("$[0].insiders").value(2))
                .andExpect(jsonPath("$[0].listedSharesCurrent").value(1_000_000_000))
                .andExpect(jsonPath("$[0].fileName").value("registrasi_zzrg_september.pdf"))
                .andExpect(jsonPath("$[0].holders").value(0))
                .andExpect(jsonPath("$[0].ownership.controller").doesNotExist());
        // the report has no table of the shareholders of 5% or more
        mvc.perform(get("/api/v1/registrations/holders").param("ticker", "zzrg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // the same file again as a financial statement (the default type): no longer a registration report
        IngestionJob again = upload(report, "registrasi_zzrg_september.pdf", "ZZRG", null);
        assertThat(again.file().fileId()).isEqualTo(job.file().fileId());
        assertThat(again.status()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(again.result().path("documentType").asString()).isEqualTo("FINANCIAL_STATEMENT");
        assertThat(again.result().path("registration").isNull() || again.result().path("registration").isMissingNode())
                .isTrue();
        mvc.perform(get("/api/v1/registrations").param("tickers", "ZZRG"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aPercentageThatDoesNotAddUpIsStoredAsIncomplete() throws Exception {
        byte[] october = new LbrePdf("ZZRG", "Oktober", 2026, "05-11-2026 10:00", false)
                .row("BUDI SANTOSO", "Direksi", "12.500.000", "1,25%", "12.500.000", "12,5%", false)
                .bytes();
        IngestionJob job = upload(october, "registrasi_zzrg_oktober.pdf", "ZZRG", "REGISTRATION");
        assertThat(job.status()).isEqualTo(IngestionJobStatus.INCOMPLETE);
        assertThat(job.stage()).contains("registration 2026-10: 1 director or commissioner, 0 changes, 0 shareholders of 5% or more");
        assertThat(job.message()).contains("12,500,000 shares are 1.2500 %", "prints 12.5 %");
        mvc.perform(get("/api/v1/registrations/reports").param("ticker", "ZZRG"))
                .andExpect(jsonPath("$[0].warnings[0]").value(Matchers.containsString("prints 12.5 %")));
    }

    @Test
    void aFileThatIsNotTheCompanysRegistrationReportIsRefused() throws Exception {
        byte[] report = september("06-10-2026 09:30");
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "r.pdf", "application/pdf", report))
                        .param("ticker", "HRTA").param("documentType", "REGISTRATION"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Not a readable registration report"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("The report is ZZRG's (Kode Emiten ZZRG), not HRTA's")));
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "s.pdf", "application/pdf",
                                textPdf("Laporan keuangan konsolidasian " + UUID.randomUUID())))
                        .param("ticker", "ZZRG").param("documentType", "REGISTRATION"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("Not a monthly registration report")));
        mvc.perform(multipart("/api/v1/rag/pdf").file(new MockMultipartFile("file", "r.pdf", "application/pdf", report))
                        .param("ticker", "ZZRG").param("documentType", "PROSPECTUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("FINANCIAL_STATEMENT, REGISTRATION")));
        mvc.perform(get("/api/v1/rag/documents").param("documentType", "PROSPECTUS"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rag/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyDocumentTypes[*].code").value(Matchers.contains("FINANCIAL_STATEMENT", "REGISTRATION")));
    }

    @Test
    void rejectsBadFilters() throws Exception {
        mvc.perform(get("/api/v1/registrations").param("transaction", "HOLD")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/registrations").param("role", "CEO")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/registrations").param("from", "2026-13")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/registrations").param("from", "2026-09").param("to", "2026-08"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The first month 2026-09 is after the last month 2026-08"));
        mvc.perform(get("/api/v1/registrations").param("tickers", "HR TA%")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/registrations").param("exchange", "NYSE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/registrations/reports").param("ticker", "")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/registrations/holders").param("ticker", "")).andExpect(status().isBadRequest());
    }

    /** A one-page text PDF. */
    private static byte[] textPdf(String text) throws IOException {
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
}
