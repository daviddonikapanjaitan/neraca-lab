package com.neracalab.backend.ownership;

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

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * Two daily lists of the shareholders of more than 5% uploaded on Ingestion > Excel Documents (RAG) through the worker
 * into the ownership tables and the vector store, read back by Companies > Ownership (>=5%). Lists of January 2001, a
 * stored company of its own (ZZOW) and one that is not stored (ZZNS), stub embeddings (no provider call); everything is
 * removed afterwards.
 */
@SpringBootTest
@DirtiesContext     // a context of its own (the stub embeddings): closed afterwards, so its connections do not pile up
class OwnershipControllerTest {

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
        cleanData();
        jdbc.sql("""
                INSERT INTO company (ticker, exchange, company_name, currency, active)
                VALUES ('ZZOW', 'IDX', 'PT Uji Kepemilikan Tbk', 'IDR', true)""").update();
    }

    private void cleanData() {
        jdbc.sql("DELETE FROM ownership_report WHERE exchange = 'IDX' AND report_date < DATE '2002-01-01'").update();
        jdbc.sql("DELETE FROM company WHERE exchange = 'IDX' AND ticker = 'ZZOW'").update();
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
                      AND NOT EXISTS (SELECT 1 FROM ownership_report r WHERE r.file_id = f.file_id)""")
                    .param("c", IngestionFileRepository.sha256(content)).update();
        }
        TestLogins.logout(context, token);
    }

    // ------------------------------------------------------------------ the workbooks

    private static final Object[] SUB = {null, null, null, null, null, null, null, null, null, null, null,
            "Jumlah Saham", "Saham Gabungan Per Investor", "Persentase Kepemilikan Per Investor (%)", "Jumlah Saham",
            "Saham Gabungan Per Investor", "Persentase Kepemilikan Per Investor (%)", null};

    private static Object[] header(String previous, String current) {
        return new Object[] {"No", "Kode Efek", "Nama Emiten", "Nama Pemegang Rekening Efek", "Nama Pemegang Saham",
                "Nama Rekening Efek", "Alamat", "Alamat (Lanjutan)", "Kebangsaan", "Domisili", "Status (Lokal/Asing)",
                "Kepemilikan Per " + previous, null, null, "Kepemilikan Per " + current, null, null, "Perubahan"};
    }

    private static Object[] row(Object no, String ticker, String participant, String holder, String status,
                                String previous, String previousTotal, String previousPct, String current,
                                String currentTotal, String currentPct) {
        return new Object[] {no, ticker, no == null ? null : "PT UJI " + ticker + " Tbk", participant,
                no == null ? null : holder, holder, "Data tidak ditampilkan", "Data tidak ditampilkan", null,
                "A".equals(status) ? "SINGAPORE" : "INDONESIA", status, previous, previousTotal, previousPct, current,
                currentTotal, currentPct, 0};
    }

    private static byte[] workbook(String day, Object[] header, Object[]... rows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Balance_SID_Publik");
            List<Object[]> all = new ArrayList<>();
            all.add(new Object[] {"KEPEMILIKAN EFEK DIATAS 5% BERDASARKAN SID (PUBLIK) per tanggal " + day});
            all.add(new Object[] {});
            all.add(header);
            all.add(SUB);
            all.addAll(List.of(rows));
            all.add(new Object[] {});
            all.add(new Object[] {"Keterangan"});
            for (int r = 0; r < all.size(); r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < all.get(r).length; c++) {
                    Object v = all.get(r)[c];
                    if (v instanceof String s) {
                        row.createCell(c).setCellValue(s);
                    } else if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    }
                }
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    /** 3 Jan 2001: ZZOW's ALFA unchanged, BETA (foreign) bought; ZZNS (not stored) one holder. */
    private static byte[] january3() throws IOException {
        return workbook("3 Jan 2001", header("02-JAN-2001", "03-JAN-2001"),
                row(1, "ZZOW", "BROKER A", "PT ALFA", "L", "600", "600", "60.00", "600", "600", "60.00"),
                row(2, "ZZOW", "CUSTODIAN", "BETA", "A", "100", "100", "10.00", "120", "120", "12.00"),
                row(3, "ZZNS", "BROKER A", "PT OMEGAZZ", "L", "500", "500", "50.00", "500", "500", "50.00"));
    }

    /** 4 Jan 2001: BETA sold below 5% (not listed), GAMMA crossed 5%. */
    private static byte[] january4() throws IOException {
        return workbook("4 Jan 2001", header("03-JAN-2001", "04-JAN-2001"),
                row(1, "ZZOW", "BROKER A", "PT ALFA", "L", "600", "600", "60.00", "600", "600", "60.00"),
                row(2, "ZZOW", "BROKER G", "GAMMA", "L", "-", "-", "-", "70", "70", "7.00"),
                row(3, "ZZNS", "BROKER A", "PT OMEGAZZ", "L", "500", "500", "50.00", "500", "500", "50.00"));
    }

    private IngestionJob upload(byte[] content, String fileName) throws Exception {
        uploaded.add(content);
        MockMultipartFile file = new MockMultipartFile("file", fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content);
        String body = mvc.perform(multipart("/api/v1/rag/xlsx").file(file).param("exchange", "idx")
                        .param("documentType", "ownership-5-percent"))
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
    void twoListsAreStoredEmbeddedAndReadByCompaniesOwnership() throws Exception {
        IngestionJob first = upload(january3(), "peng-2001-01-03-00001-lima-persen.xlsx");
        assertThat(first.status()).as(first.stage() + " " + first.message()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(first.type().name()).isEqualTo("RAG_XLSX");
        assertThat(first.stage()).contains("List of 2001-01-03 stored: 3 investors of 2 companies; 1 buy",
                "the list of 2001-01-02 is not stored", "1 ownership documents");
        assertThat(first.result().path("ragDocuments").asInt()).isEqualTo(1);
        assertThat(first.result().path("companiesNotStored").asInt()).isEqualTo(1);
        assertThat(first.result().path("announcementNumber").asString()).isEqualTo("00001");

        IngestionJob second = upload(january4(), "peng-2001-01-04-00002-lima-persen.xlsx");
        assertThat(second.status()).as(second.stage() + " " + second.message()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(second.result().path("exited").asInt()).isEqualTo(1);
        assertThat(second.result().path("entered").asInt()).isEqualTo(1);

        mvc.perform(get("/api/v1/ownership").param("tickers", "zzow").param("transaction", "EXITED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].holderName").value("BETA"))
                .andExpect(jsonPath("$.rows[0].sharesPrevious").value(120))
                .andExpect(jsonPath("$.rows[0].leftList").value(true))
                .andExpect(jsonPath("$.rows[0].reportDate").value("2001-01-04"));
        mvc.perform(get("/api/v1/ownership").param("tickers", "ZZOW").param("holder", "gamma"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].movement").value("ENTERED"));

        mvc.perform(get("/api/v1/ownership/holders").param("ticker", "ZZOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuerName").value("PT UJI ZZOW Tbk"))
                .andExpect(jsonPath("$.digest.days").value(2))
                .andExpect(jsonPath("$.digest.holders[0].name").value("PT ALFA"))
                .andExpect(jsonPath("$.digest.formerHolders[0].name").value("BETA"))
                .andExpect(jsonPath("$.days[0].accounts[0].participant").value("BROKER A"));

        mvc.perform(get("/api/v1/ownership/entities").param("q", "omegazz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.holderKey == 'OMEGAZZ')].holdings[0].ticker").value("ZZNS"));

        // one document for the stored company, written again from both days
        mvc.perform(get("/api/v1/rag/documents").param("ticker", "ZZOW").param("source", "XLSX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.documents[0].title").value(Matchers.containsString("2001-01-03 to 2001-01-04, 2 days")))
                .andExpect(jsonPath("$.documents[0].chunks").value(Matchers.greaterThan(0)));
        mvc.perform(get("/api/v1/rag/search").param("ticker", "ZZOW").param("source", "XLSX").param("q", "BETA sold"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value(Matchers.containsString("BETA fell below 5%")));
        // a company that is not stored has no document (it is still on Companies > Ownership (>=5%))
        mvc.perform(get("/api/v1/rag/documents").param("ticker", "ZZNS").param("source", "XLSX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/v1/rag/xlsx/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[?(@.reportDate == '2001-01-04')].exited").value(1));
        mvc.perform(get("/api/v1/ownership/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickers[?(@.ticker == 'ZZOW')].holders").value(2))
                .andExpect(jsonPath("$.transactions[0].code").value("ALL"));
        // by change, largest buy first; the highlights of another day
        mvc.perform(get("/api/v1/ownership").param("tickers", "ZZOW").param("sort", "change_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].holderName").value("BETA"))
                .andExpect(jsonPath("$.rows[0].change").value(20));
        mvc.perform(get("/api/v1/ownership/highlights").param("date", "2001-01-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.reportDate").value("2001-01-03"))
                .andExpect(jsonPath("$.largestBuys[?(@.ticker == 'ZZOW')].holderName").value("BETA"));
        mvc.perform(get("/api/v1/ownership/highlights").param("date", "2001-01-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exited[?(@.ticker == 'ZZOW')].holderName").value("BETA"))
                .andExpect(jsonPath("$.entered[?(@.ticker == 'ZZOW')].holderName").value("GAMMA"));
        mvc.perform(get("/api/v1/ownership/highlights").param("date", "2001-01-02"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ownership/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sorts[1].code").value("CHANGE_DESC"));
        mvc.perform(get("/api/v1/rag/xlsx/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchanges[0].label").value("IDX - Indonesia Stock Exchange"))
                .andExpect(jsonPath("$.exchanges[0].documentTypes[0].code").value("OWNERSHIP_5_PERCENT"))
                .andExpect(jsonPath("$.exchanges[0].documentTypes[1].code").value("OWNERSHIP_1_PERCENT"))
                .andExpect(jsonPath("$.exchanges[0].documentTypes[1].available").value(true))
                .andExpect(jsonPath("$.stored[0].reports").value(Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void refusesWhatCannotBeStored() throws Exception {
        byte[] list = january3();
        mvc.perform(multipart("/api/v1/rag/xlsx").file(new MockMultipartFile("file", "list.pdf", null, list))
                        .param("documentType", "OWNERSHIP_5_PERCENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only .xlsx workbooks are accepted"));
        mvc.perform(multipart("/api/v1/rag/xlsx").file(new MockMultipartFile("file", "list.xlsx", null, "text".getBytes()))
                        .param("documentType", "OWNERSHIP_5_PERCENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("no ZIP header")));
        mvc.perform(multipart("/api/v1/rag/xlsx").file(new MockMultipartFile("file", "list.xlsx", null, list))
                        .param("documentType", "OWNERSHIP_1_PERCENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("shareholders of more than 1%")));
        mvc.perform(multipart("/api/v1/rag/xlsx").file(new MockMultipartFile("file", "list.xlsx", null, list)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose the type of the document"));
        byte[] other = workbook("3 Jan 2001", new Object[] {"No", "Nama"}, new Object[] {1, "x"});
        mvc.perform(multipart("/api/v1/rag/xlsx").file(new MockMultipartFile("file", "other.xlsx", null, other))
                        .param("documentType", "OWNERSHIP_5_PERCENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Not a readable ownership list"));

        mvc.perform(get("/api/v1/ownership").param("transaction", "SOMETIMES")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership").param("from", "2026-10-27").param("to", "2026-10-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership").param("status", "X")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership").param("sort", "sideways")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/highlights").param("date", "27-10-2026")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/entities").param("q", "ab")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ownership/holders").param("ticker", "BAD TICKER!")).andExpect(status().isBadRequest());
    }
}
