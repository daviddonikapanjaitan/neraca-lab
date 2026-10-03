package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.ResultMatcher;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.TestAccounts;
import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.file.IngestionFileRepository;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;

import tools.jackson.databind.json.JsonMapper;

/**
 * Asynchronous upload against the Docker Postgres: the workbook is checked and stored once per
 * checksum, the job is recorded in ingestion_job and processed by the background worker. The AI
 * agent is a mock that fails (no model call, nothing written to the company tables).
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinancialStatementUploadTest {

    private static final Path WORKBOOK = Path.of("..", "data", "HRTA", "xlsx", "FinancialStatement-2026-II-HRTA.xlsx");

    @MockitoBean
    private IngestionAgent agent;

    @Autowired
    private WebApplicationContext context;

    /** Sends the bearer token of a root session with every request. */
    private MockMvc mvc;
    private String token;

    @BeforeEach
    void loginAsRoot() {
        token = TestLogins.rootToken(context);
        mvc = TestLogins.mockMvc(context, token);
    }

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    /** Jobs and newly stored files of the test, removed afterwards so the database keeps only real uploads. */
    private final List<UUID> createdJobs = new ArrayList<>();
    private final List<Long> createdFiles = new ArrayList<>();

    @BeforeEach
    void agentIsOffline() {
        when(agent.run(any())).thenThrow(new IllegalStateException("model offline (test)"));
    }

    @AfterEach
    void removeTestRows() throws Exception {
        try {
            for (UUID id : createdJobs) {
                awaitFinished(id);   // through the API, so before the logout below
            }
            if (!createdJobs.isEmpty()) {
                jdbc.sql("DELETE FROM ingestion_job WHERE job_id IN (:ids)").param("ids", createdJobs).update();
            }
            if (!createdFiles.isEmpty()) {
                jdbc.sql("""
                                DELETE FROM ingestion_file f WHERE f.file_id IN (:ids)
                                AND NOT EXISTS (SELECT 1 FROM ingestion_job j WHERE j.file_id = f.file_id)""")
                        .param("ids", createdFiles).update();
            }
        } finally {
            TestLogins.logout(context, token);
        }
    }

    @Test
    void storesTheFileOnceAndIngestsInTheBackground() throws Exception {
        byte[] content = Files.readAllBytes(WORKBOOK);
        String checksum = IngestionFileRepository.sha256(content);

        IngestionJob first = upload(content, status().isAccepted());
        assertThat(first.type()).isEqualTo(IngestionJobType.FINANCIAL_STATEMENT);
        assertThat(first.file().fileName()).isEqualTo("FinancialStatement-2026-II-HRTA.xlsx");
        assertThat(first.file().checksumSha256()).isEqualTo(checksum);
        assertThat(first.file().sizeBytes()).isEqualTo(content.length);
        // recorded as uploaded by the logged-in user (root)
        long rootId = jdbc.sql("SELECT user_id FROM users WHERE root").query(Long.class).single();
        assertThat(first.createdBy().userId()).isEqualTo(rootId);
        assertThat(first.createdBy().username()).isEqualTo("admin");

        IngestionJob finished = awaitFinished(first.id());
        assertThat(finished.createdBy()).isEqualTo(first.createdBy());
        assertThat(finished.status()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(finished.message()).isEqualTo("IllegalStateException: model offline (test)");
        assertThat(finished.stage()).startsWith("AI agent failed on HRTA");
        assertThat(finished.exchange()).isEqualTo("IDX");
        assertThat(finished.ticker()).isEqualTo("HRTA");
        assertThat(finished.attempts()).isEqualTo(1);
        assertThat(finished.startedAt()).isNotNull();
        assertThat(finished.finishedAt()).isNotNull();
        assertThat(finished.result().get("status").asString()).isEqualTo("FAILED");
        assertThat(finished.result().get("filing").get("ticker").asString()).isEqualTo("HRTA");

        // same content again: the stored file is reused, a new job extracts it again
        IngestionJob second = upload(content, status().isAccepted());
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.file().reused()).isTrue();
        assertThat(second.file().fileId()).isEqualTo(first.file().fileId());
        assertThat(awaitFinished(second.id()).status()).isEqualTo(IngestionJobStatus.FAILED);

        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_file WHERE checksum_sha256 = :c")
                .param("c", checksum).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void rejectsWrongFilesWithoutStoringThem() throws Exception {
        byte[] garbage = ("not a workbook " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        mvc.perform(multipart("/api/v1/financial-statements/upload")
                        .file(new MockMultipartFile("file", "report.xlsx", "application/octet-stream", garbage)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Invalid financial statement workbook"));
        mvc.perform(multipart("/api/v1/financial-statements/upload")
                        .file(new MockMultipartFile("file", "report.csv", "text/csv", garbage)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Only .xlsx files are accepted")));
        mvc.perform(multipart("/api/v1/financial-statements/upload")
                        .file(new MockMultipartFile("file", "empty.xlsx", "application/octet-stream", new byte[0])))
                .andExpect(status().isUnprocessableContent());

        assertThat(jdbc.sql("SELECT count(*) FROM ingestion_file WHERE checksum_sha256 = :c")
                .param("c", IngestionFileRepository.sha256(garbage)).query(Long.class).single()).isZero();
    }

    @Test
    void listsJobsWithCountsAndFilters() throws Exception {
        IngestionJob job = upload(Files.readAllBytes(WORKBOOK), status().is2xxSuccessful());
        awaitFinished(job.id());

        mvc.perform(get("/api/v1/ingestions").param("type", "financial_statement").param("status", "failed, succeeded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.FAILED").isNumber())
                .andExpect(jsonPath("$.counts.QUEUED").isNumber())
                .andExpect(jsonPath("$.active").isNumber())
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.jobs[?(@.type != 'FINANCIAL_STATEMENT')]").isEmpty())
                .andExpect(jsonPath("$.jobs[?(@.status == 'QUEUED')]").isEmpty())
                .andExpect(jsonPath("$.jobs[?(@.id == '" + job.id() + "')]").exists())
                .andExpect(jsonPath("$.jobs[0].result").doesNotExist());
        mvc.perform(get("/api/v1/ingestions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs.length()").value(1));

        mvc.perform(get("/api/v1/ingestions").param("status", "DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid parameter"));
        mvc.perform(get("/api/v1/ingestions").param("type", "PDF"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ingestions").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ingestions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ingestion job not found"));
    }

    @Test
    void downloadsTheStoredFileUnderTheNameOfEachUpload() throws Exception {
        byte[] content = Files.readAllBytes(WORKBOOK);
        IngestionJob original = upload(content, status().is2xxSuccessful());
        awaitFinished(original.id());
        // same content under another name: one stored file, each job downloads under its own name
        IngestionJob renamed = upload(content, "HRTA laporan Q2 2026.xlsx", status().isAccepted());
        assertThat(renamed.file().fileId()).isEqualTo(original.file().fileId());
        awaitFinished(renamed.id());

        assertDownload(original, content, original.file().fileName());
        assertDownload(renamed, content, "HRTA laporan Q2 2026.xlsx");

        mvc.perform(get("/api/v1/ingestions/" + UUID.randomUUID() + "/file"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("File not found"));
        mvc.perform(get("/api/v1/ingestions/not-a-uuid/file"))
                .andExpect(status().isBadRequest());
    }

    private void assertDownload(IngestionJob job, byte[] expected, String fileName) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/ingestions/" + job.id() + "/file"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("X-Checksum-SHA256", IngestionFileRepository.sha256(expected)))
                .andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEqualTo(expected);
        assertThat(response.getContentLength()).isEqualTo(expected.length);
        ContentDisposition disposition = ContentDisposition.parse(response.getHeader("Content-Disposition"));
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo(fileName);
    }

    @Test
    void recordsTheUserWhoUploadsAndKeepsTheUsernameAfterTheUserIsDeleted() throws Exception {
        TestAccounts accounts = new TestAccounts(context);
        try {
            long userId = accounts.user("uploader", accounts.role("ingest", Permission.INGESTION));
            String userToken = accounts.token(userId);
            String body = mvc.perform(multipart("/api/v1/financial-statements/upload")
                            .file(new MockMultipartFile("file", WORKBOOK.getFileName().toString(),
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    Files.readAllBytes(WORKBOOK)))
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().is2xxSuccessful())
                    .andReturn().getResponse().getContentAsString();
            IngestionJob job = json.readValue(body, IngestionJob.class);
            createdJobs.add(job.id());
            if (!job.file().reused()) {
                createdFiles.add(job.file().fileId());
            }
            if (job.createdBy().userId() != userId) {
                // the same file was still being processed for another user: that job was returned
                awaitFinished(job.id());
                return;
            }
            assertThat(job.createdBy().username()).isEqualTo(accounts.name("uploader"));
            awaitFinished(job.id());

            jdbc.sql("DELETE FROM users WHERE user_id = :id").param("id", userId).update();
            IngestionJob afterDelete = json.readValue(mvc.perform(get("/api/v1/ingestions/" + job.id()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), IngestionJob.class);
            assertThat(afterDelete.createdBy().userId()).isNull();
            assertThat(afterDelete.createdBy().username()).isEqualTo(accounts.name("uploader"));
        } finally {
            accounts.cleanup();
        }
    }

    private IngestionJob upload(byte[] content, ResultMatcher expected) throws Exception {
        return upload(content, WORKBOOK.getFileName().toString(), expected);
    }

    private IngestionJob upload(byte[] content, String fileName, ResultMatcher expected) throws Exception {
        String body = mvc.perform(multipart("/api/v1/financial-statements/upload")
                        .file(new MockMultipartFile("file", fileName,
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", content)))
                .andExpect(expected)
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/ingestions/")))
                .andReturn().getResponse().getContentAsString();
        IngestionJob job = json.readValue(body, IngestionJob.class);
        createdJobs.add(job.id());
        if (!job.file().reused()) {
            createdFiles.add(job.file().fileId());
        }
        return job;
    }

    private IngestionJob awaitFinished(UUID id) throws Exception {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            String body = mvc.perform(get("/api/v1/ingestions/" + id))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            IngestionJob job = json.readValue(body, IngestionJob.class);
            if (!job.status().active()) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("upload job " + id + " did not finish");
    }
}
