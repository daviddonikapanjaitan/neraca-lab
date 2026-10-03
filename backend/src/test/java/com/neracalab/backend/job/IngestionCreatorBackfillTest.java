package com.neracalab.backend.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-time attribution of existing ingestion jobs to the root user. Runs in a transaction that
 * is rolled back, so the database (and its migration record) is left as it was.
 */
@SpringBootTest
@Transactional
class IngestionCreatorBackfillTest {

    @Autowired
    private IngestionCreatorBackfill backfill;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void attributesExistingJobsToTheRootUserExactlyOnce() {
        long rootId = jdbc.sql("SELECT user_id FROM users WHERE root").query(Long.class).single();
        UUID old = insertJobWithoutRequester();
        jdbc.sql("DELETE FROM app_migration WHERE name = :n").param("n", IngestionCreatorBackfill.MIGRATION).update();

        assertThat(backfill.run()).isPositive();
        assertThat(requester(old)).isEqualTo(rootId + " admin");
        assertThat(jdbc.sql("SELECT details FROM app_migration WHERE name = :n").param("n", IngestionCreatorBackfill.MIGRATION)
                .query(String.class).single()).endsWith("attributed to the root user");

        // later jobs without a user (scheduled runs) are not attributed: the migration ran already
        UUID scheduled = insertJobWithoutRequester();
        assertThat(backfill.run()).isZero();
        assertThat(requester(scheduled)).isEqualTo("null null");
    }

    @Test
    void theMigrationIsRecordedAtStartup() {
        assertThat(jdbc.sql("SELECT count(*) FROM app_migration WHERE name = :n").param("n", IngestionCreatorBackfill.MIGRATION)
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(backfill.run()).isZero();
    }

    private UUID insertJobWithoutRequester() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO ingestion_job (job_id, job_type, status, stage) VALUES (:id, 'PRICE', 'SUCCEEDED', 'test')")
                .param("id", id).update();
        return id;
    }

    /** "<created_by> <created_by_username>", e.g. "1 admin" or "null null". */
    private String requester(UUID jobId) {
        return jdbc.sql("SELECT coalesce(created_by::text, 'null') || ' ' || coalesce(created_by_username, 'null') FROM ingestion_job WHERE job_id = :id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }
}
