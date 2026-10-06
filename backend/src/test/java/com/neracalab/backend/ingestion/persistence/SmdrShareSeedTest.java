package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Transactional;

/**
 * V1.0.12__data_SMDR_shares.sql: SMDR's split-adjusted share counts (16,375,600,000 from 2020-12-31) and
 * the 2023-01-31 1:5 split; idempotent, and a count already stored (from a filing) is never replaced.
 * Runs against the Docker Postgres when SMDR has been uploaded; rolled back.
 */
@SpringBootTest
@Transactional
class SmdrShareSeedTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void fillsSplitAdjustedCountsOnceAndKeepsStoredOnes() {
        List<Long> ids = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'SMDR' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!ids.isEmpty(), "SMDR has not been uploaded");
        long smdr = ids.getFirst();
        jdbc.sql("DELETE FROM share_snapshot WHERE company_id = :c").param("c", smdr).update();
        jdbc.sql("DELETE FROM corporate_action WHERE company_id = :c").param("c", smdr).update();
        // a count a filing gave for one date must survive
        jdbc.sql("INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding) VALUES (:c, DATE '2024-12-31', 123)")
                .param("c", smdr).update();

        seed();
        seed();   // idempotent

        List<BigDecimal> counts = jdbc.sql("SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c ORDER BY snapshot_date")
                .param("c", smdr).query(BigDecimal.class).list();
        assertThat(counts).hasSize(8);
        assertThat(counts).filteredOn(n -> n.compareTo(new BigDecimal("16375600000")) == 0).hasSize(7);
        assertThat(jdbc.sql("SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c AND snapshot_date = DATE '2024-12-31'")
                .param("c", smdr).query(BigDecimal.class).single()).isEqualByComparingTo("123");
        assertThat(jdbc.sql("SELECT min(snapshot_date)::text FROM share_snapshot WHERE company_id = :c")
                .param("c", smdr).query(String.class).single()).isEqualTo("2020-12-31");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM corporate_action
                        WHERE company_id = :c AND action_type = 'STOCK_SPLIT' AND action_date = DATE '2023-01-31'
                          AND ratio_from = 1 AND ratio_to = 5""")
                .param("c", smdr).query(Long.class).single()).isEqualTo(1L);
    }

    private void seed() {
        new ResourceDatabasePopulator(new ClassPathResource("db/V1.0.12__data_SMDR_shares.sql")).execute(dataSource);
    }
}
