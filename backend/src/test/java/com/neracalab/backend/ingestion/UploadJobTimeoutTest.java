package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;

/**
 * An upload job that runs longer than {@code neracalab.ingestion.job-timeout} (here 3 seconds) is stopped and
 * FAILED. The agent is a mock that works for 4 s and then reaches its next step, where the real deadline
 * check stops it. Runs against the Docker Postgres; the job row is removed afterwards.
 */
@SpringBootTest(properties = "neracalab.ingestion.job-timeout=3s")
class UploadJobTimeoutTest {

    private static final Path WORKBOOK = Path.of("..", "data", "IDX_XBRL", "HRTA", "xlsx", "FinancialStatement-2026-II-HRTA.xlsx");

    @MockitoBean
    private IngestionAgent agent;

    @Autowired
    private FinancialStatementQueue queue;

    @Autowired
    private IngestionJobRepository jobs;

    @Autowired
    private JdbcClient jdbc;

    private UUID created;

    @AfterEach
    void removeJob() {
        if (created != null) {
            jdbc.sql("DELETE FROM ingestion_job WHERE job_id = :id").param("id", created).update();
        }
    }

    @Test
    void aJobOverTheLimitIsStoppedAndFailed() throws Exception {
        assumeTrue(Files.exists(WORKBOOK), "HRTA source data not available");
        when(agent.run(any())).thenAnswer(invocation -> {
            IngestionSession session = invocation.getArgument(0);
            Thread.sleep(4000);
            session.deadline().check("the next step (test)");
            throw new AssertionError("the deadline did not stop the agent");
        });

        var submission = queue.submit(Files.readAllBytes(WORKBOOK), WORKBOOK.getFileName().toString(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null);
        assertThat(submission.created()).as("no other job of this file may be active").isTrue();
        created = submission.job().id();
        IngestionJob job = awaitFinished(created);

        assertThat(job.status()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(job.stage()).startsWith("Stopped after the 3 seconds limit on HRTA 2026 H1");
        assertThat(job.message()).contains("exceeded its time limit of 3 seconds").contains("the next step (test)");
        assertThat(Duration.between(job.startedAt(), job.finishedAt())).isLessThan(Duration.ofSeconds(8));
    }

    private IngestionJob awaitFinished(UUID id) throws InterruptedException {
        Instant until = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(until)) {
            IngestionJob job = jobs.find(id).orElseThrow();
            if (!job.status().active()) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("upload job " + id + " did not finish");
    }
}
