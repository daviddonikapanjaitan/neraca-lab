package com.neracalab.backend.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.auth.AuthBootstrap;

/**
 * One-time data migration at startup: ingestion jobs recorded before {@code ingestion_job.created_by}
 * existed are attributed to the root user ({@value AuthBootstrap#ROOT_USERNAME}).
 * <p>
 * It runs exactly once per database ({@code app_migration} row {@value #MIGRATION}), so jobs
 * recorded later without a user (scheduled price runs) stay unattributed. It needs the root user,
 * hence the dependency on {@link AuthBootstrap}, which creates it first.
 */
@Component
public class IngestionCreatorBackfill implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IngestionCreatorBackfill.class);

    static final String MIGRATION = "ingestion_job.created_by: existing jobs -> root user";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    /** @param bootstrap only to run after it (the root user must exist) */
    public IngestionCreatorBackfill(JdbcClient jdbc, TransactionTemplate transaction, AuthBootstrap bootstrap) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public void afterPropertiesSet() {
        run();
    }

    /** Applies the migration unless it was applied before; returns the number of jobs attributed. */
    public int run() {
        Integer updated = transaction.execute(status -> {
            boolean first = jdbc.sql("""
                            INSERT INTO app_migration (name, details) VALUES (:name, 'pending')
                            ON CONFLICT (name) DO NOTHING
                            RETURNING name""")
                    .param("name", MIGRATION)
                    .query(String.class)
                    .optional()
                    .isPresent();
            if (!first) {
                return 0;
            }
            int count = jdbc.sql("""
                            UPDATE ingestion_job j
                            SET created_by = u.user_id, created_by_username = u.username
                            FROM users u
                            WHERE u.root AND j.created_by IS NULL AND j.created_by_username IS NULL""")
                    .update();
            jdbc.sql("UPDATE app_migration SET details = :d WHERE name = :name")
                    .param("d", count + " existing job(s) attributed to the root user")
                    .param("name", MIGRATION)
                    .update();
            return count;
        });
        int count = updated == null ? 0 : updated;
        if (count > 0) {
            log.info("{} existing ingestion job(s) attributed to the root user {}", count, AuthBootstrap.ROOT_USERNAME);
        }
        return count;
    }
}
