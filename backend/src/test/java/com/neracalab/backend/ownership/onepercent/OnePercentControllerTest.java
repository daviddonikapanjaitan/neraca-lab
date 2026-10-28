package com.neracalab.backend.ownership.onepercent;

import static com.neracalab.backend.ownership.onepercent.OnePercentBooks.holding;
import static com.neracalab.backend.ownership.onepercent.OnePercentBooks.workbook;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * Two monthly lists of the shareholders of more than 1% uploaded on Ingestion > Excel Documents (RAG) through the
 * worker into the ownership tables and the vector store, read back by Companies > Ownership (>=1%). Lists of January
 * and February 2001 (February uploaded first), a stored company of its own (ZZOP) and one that is not stored (ZZNP),
 * stub embeddings (no provider call); everything is removed afterwards, and a stored list after them is compared
 * again with the lists before it.
 */
@SpringBootTest
@DirtiesContext     // a context of its own (the stub embeddings): closed afterwards, so its connections do not pile up
class OnePercentControllerTest {

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

    private static final LocalDate JAN = LocalDate.of(2001, 1, 31);
    private static final LocalDate FEB = LocalDate.of(2001, 2, 28);
    /**
     * The reads end with 2001: a stored list of a later year is compared with February 2001 while the test runs, so
     * ZZOP's investors are no longer above 1% there.
     */
    private static final String LAST_2001 = "2001-12-31";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IngestionJobRepository jobs;

    @Autowired
    private OnePercentRepository repository;

    private MockMvc mvc;
    private String token;
    private final List<UUID> jobIds = new ArrayList<>();
    private final List<byte[]> uploaded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
        cleanData();
        jdbc.sql("""
                INSERT INTO company (ticker, exchange, company_name, currency, active)
                VALUES ('ZZOP', 'IDX', 'PT Uji Kepemilikan Satu Persen Tbk', 'IDR', true)""").update();
    }

    /** The lists of 2001, removed one by one: the stored list after them is compared with the one before again. */
    private void cleanData() {
        List<LocalDate> dates = jdbc.sql("""
                        SELECT report_date FROM ownership_one_report
                        WHERE exchange = 'IDX' AND report_date < DATE '2002-01-01' ORDER BY report_date""")
                .query(LocalDate.class).list();
        dates.forEach(d -> repository.delete("IDX", d));
        jdbc.sql("DELETE FROM company WHERE exchange = 'IDX' AND ticker = 'ZZOP'").update();
    }

    @AfterEach
    void cleanUp() {
        // the company's ownership document goes with it (ON DELETE CASCADE); then the jobs and the files
        cleanData();
        for (UUID id : jobIds) {
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id = :id").param("id", id).update();
        }
        for (byte[] content : uploaded) {
            jdbc.sql("""
                    DELETE FROM ingestion_file f WHERE checksum_sha256 = :c
                      AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.file_id = f.file_id)
                      AND NOT EXISTS (SELECT 1 FROM rag_document d WHERE d.file_id = f.file_id)
                      AND NOT EXISTS (SELECT 1 FROM ownership_report r WHERE r.file_id = f.file_id)
                      AND NOT EXISTS (SELECT 1 FROM ownership_one_report r WHERE r.file_id = f.file_id)""")
                    .param("c", IngestionFileRepository.sha256(content)).update();
        }
        TestLogins.logout(context, token);
    }

    /** January: ZZOP's ALFA, BETA, GAMMA FUND and DELTA BANK; ZZNP (not stored) one holder. */
    private static byte[] january() throws IOException {
        return workbook(JAN, true,
                holding("ZZOP", "PT ALFAQZ", "CP", "L", 600, 0, 600, 60.0),
                holding("ZZOP", "BETAQZ", "ID", "L", 100, 0, 100, 10.0),
                holding("ZZOP", "GAMMA FUND", "MF", "A", 50, 0, 50, 5.0),
                holding("ZZOP", "PT DELTA BANK", "IB", "L", 40, 0, 40, 4.0),
                holding("ZZNP", "PT OMEGAQZ", "CP", "L", 500, 0, 500, 50.0));
    }

    /** February: ALFA bought, BETA sold, GAMMA no longer above 1%, DELTA BANK's shares as PANIN DELTA, EPSILON new. */
    private static byte[] february() throws IOException {
        return workbook(FEB, false,
                holding("ZZOP", "PT ALFAQZ", "Corporate", "L", 650, 0, 650, 65.0),
                holding("ZZOP", "BETAQZ", "Individual", "L", 80, 0, 80, 8.0),
                holding("ZZOP", "PANIN DELTA", "Bank", "L", 40, 0, 40, 4.0),
                holding("ZZOP", "EPSILON CAPITAL", "Private Equity", "F", 30, 0, 30, 3.0),
                holding("ZZNP", "PT OMEGAQZ", "Corporate", "L", 500, 0, 500, 50.0));
    }

    private IngestionJob upload(byte[] content, String fileName) throws Exception {
        uploaded.add(content);
        MockMultipartFile file = new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
        String body = mvc.perform(multipart("/api/v1/rag/xlsx").file(file).param("exchange", "idx")
                        .param("documentType", "ownership-1-percent"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
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

    @Test
    void twoMonthsAreStoredComparedEmbeddedAndReadByCompaniesOwnership() throws Exception {
        IngestionJob first = upload(february(), "peng-2001-02-00002-satu-persen.xlsx");
        assertThat(first.status()).as(first.stage() + " " + first.message()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(first.type().name()).isEqualTo("RAG_XLSX");
        assertThat(first.stage()).contains("List of 2001-02-28 stored: 5 investors of 2 companies",
                "no earlier list stored", "1 ownership documents");
        assertThat(first.result().path("documentType").asString()).isEqualTo("OWNERSHIP_1_PERCENT");
        assertThat(first.result().path("companiesNotStored").asInt()).isEqualTo(1);
        assertThat(first.result().path("announcementNumber").asString()).isEqualTo("00002");

        // January after February: February is compared with it
        IngestionJob second = upload(january(), "peng-01-00001-satu-persen.xlsx");
        assertThat(second.status()).as(second.stage() + " " + second.message()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(second.result().path("nextDate").asString()).isEqualTo("2001-02-28");
        assertThat(second.stage()).contains("the list of 2001-02-28 compared with it again");

        mvc.perform(get("/api/v1/ownership/one-percent").param("to", LAST_2001).param("tickers", "zzop").param("transaction", "EXITED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].holderName").value("GAMMA FUND"))
                .andExpect(jsonPath("$.rows[0].sharesPrevious").value(50))
                .andExpect(jsonPath("$.rows[0].leftList").value(true))
                .andExpect(jsonPath("$.rows[0].reportDate").value("2001-02-28"))
                .andExpect(jsonPath("$.rows[0].previousDate").value("2001-01-31"));
        mvc.perform(get("/api/v1/ownership/one-percent").param("to", LAST_2001).param("tickers", "ZZOP").param("transaction", "RENAMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].holderName").value("PANIN DELTA"))
                .andExpect(jsonPath("$.rows[0].matchKind").value("MOVED"))
                .andExpect(jsonPath("$.rows[0].previousName").value("PT DELTA BANK"));
        mvc.perform(get("/api/v1/ownership/one-percent").param("to", LAST_2001).param("tickers", "ZZOP").param("group", "fund")
                        .param("status", "F").param("from", "2001-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].holderName").value(Matchers.contains("EPSILON CAPITAL", "GAMMA FUND")))
                .andExpect(jsonPath("$.rows[0].movement").value("ENTERED"))
                .andExpect(jsonPath("$.rows[0].investorType").value("Private Equity"));
        mvc.perform(get("/api/v1/ownership/one-percent").param("to", LAST_2001).param("tickers", "ZZOP").param("sort", "change_asc")
                        .param("transaction", "CHANGED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].holderName").value("BETAQZ"))
                .andExpect(jsonPath("$.rows[0].change").value(-20));

        mvc.perform(get("/api/v1/ownership/one-percent/holders").param("ticker", "ZZOP").param("to", LAST_2001))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuerName").value("PT UJI ZZOP Tbk"))
                .andExpect(jsonPath("$.digest.months").value(2))
                .andExpect(jsonPath("$.digest.holders[0].name").value("PT ALFAQZ"))
                .andExpect(jsonPath("$.digest.holders[0].netChange").value(50))
                .andExpect(jsonPath("$.digest.formerHolders[0].name").value("GAMMA FUND"))
                .andExpect(jsonPath("$.digest.byType[0].group").value("CORPORATE"))
                .andExpect(jsonPath("$.rows.length()").value(9));

        mvc.perform(get("/api/v1/ownership/one-percent/entities").param("q", "omegaqz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.holderKey == 'OMEGAQZ')].holdings[0].ticker").value("ZZNP"));

        // one 1% document for the stored company, written again from both months
        mvc.perform(get("/api/v1/rag/documents").param("ticker", "ZZOP").param("source", "XLSX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.documents[0].title").value(Matchers.containsString("2001-01-31 to 2001-02-28, 2 months")))
                .andExpect(jsonPath("$.documents[0].chunks").value(Matchers.greaterThan(0)));
        mvc.perform(get("/api/v1/rag/search").param("ticker", "ZZOP").param("source", "XLSX").param("q", "GAMMA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].content").value(Matchers.hasItem(Matchers.containsString("GAMMA FUND no longer above 1%"))));
        mvc.perform(get("/api/v1/rag/documents").param("ticker", "ZZNP").param("source", "XLSX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/v1/rag/xlsx/reports").param("documentType", "OWNERSHIP_1_PERCENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[?(@.reportDate == '2001-02-28')].exited").value(1))
                .andExpect(jsonPath("$.reports[?(@.reportDate == '2001-02-28')].moved").value(1))
                .andExpect(jsonPath("$.reports[?(@.reportDate == '2001-02-28')].previousDate").value("2001-01-31"));
        mvc.perform(get("/api/v1/ownership/one-percent/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickers[?(@.ticker == 'ZZOP')].holders").value(4))
                .andExpect(jsonPath("$.tickers[?(@.ticker == 'ZZOP')].stored").value(true))
                .andExpect(jsonPath("$.transactions[0].code").value("ALL"))
                .andExpect(jsonPath("$.groups[0].code").value("INDIVIDUAL"))
                .andExpect(jsonPath("$.sorts[1].code").value("CHANGE_DESC"))
                .andExpect(jsonPath("$.highlights.report").exists());
        mvc.perform(get("/api/v1/ownership/one-percent/highlights").param("date", "2001-02-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.reportDate").value("2001-02-28"))
                .andExpect(jsonPath("$.largestBuys[?(@.ticker == 'ZZOP')].holderName").value("PT ALFAQZ"))
                .andExpect(jsonPath("$.largestSells[?(@.ticker == 'ZZOP')].holderName").value("BETAQZ"))
                .andExpect(jsonPath("$.exited[?(@.ticker == 'ZZOP')].holderName").value("GAMMA FUND"))
                .andExpect(jsonPath("$.entered[?(@.ticker == 'ZZOP')].holderName").value("EPSILON CAPITAL"))
                .andExpect(jsonPath("$.renamed[?(@.ticker == 'ZZOP')].holderName").value("PANIN DELTA"));
        mvc.perform(get("/api/v1/ownership/one-percent/highlights").param("date", "2001-01-01"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ownership/one-percent/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[?(@.reportDate == '2001-02-28')].buys").value(1));
        mvc.perform(get("/api/v1/rag/xlsx/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchanges[0].documentTypes[1].code").value("OWNERSHIP_1_PERCENT"))
                .andExpect(jsonPath("$.exchanges[0].documentTypes[1].label").value("Ownership (>=1%)"))
                .andExpect(jsonPath("$.exchanges[0].documentTypes[1].available").value(true))
                .andExpect(jsonPath("$.stored[1].documentType").value("OWNERSHIP_1_PERCENT"))
                .andExpect(jsonPath("$.stored[1].reports").value(Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.stored[1].documents").value(Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void refusesWhatCannotBeStored() throws Exception {
        // a 1% list is not a 5% list, and a sheet without the header is neither
        mvc.perform(multipart("/api/v1/rag/xlsx").file(new MockMultipartFile("file", "list.xlsx", null, january()))
                        .param("documentType", "OWNERSHIP_5_PERCENT"))
                .andExpect(status().isUnprocessableContent());
        byte[] empty = workbook(JAN, true);
        mvc.perform(multipart("/api/v1/rag/xlsx").file(new MockMultipartFile("file", "list.xlsx", null, empty))
                        .param("documentType", "OWNERSHIP_1_PERCENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Not a readable ownership list"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("no holding")));

        mvc.perform(get("/api/v1/ownership/one-percent").param("transaction", "SOMETIMES"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/one-percent").param("group", "ROBOTS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/one-percent").param("status", "X")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/one-percent").param("sort", "sideways")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/one-percent").param("from", "2026-09-30").param("to", "2026-02-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/one-percent/highlights").param("date", "30-09-2026"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/one-percent/entities").param("q", "ab")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/one-percent/holders").param("ticker", "BAD TICKER!"))
                .andExpect(status().isBadRequest());
    }
}
