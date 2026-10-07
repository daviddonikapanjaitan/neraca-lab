package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Transactional;

/**
 * V1.0.13__data_BNGA_shares.sql: BNGA's audited year-end share counts (note 33 of the annual reports);
 * outstanding + treasury = issued at every date; idempotent, and a count already stored is never
 * replaced. Runs against the Docker Postgres when BNGA has been uploaded; rolled back.
 */
@SpringBootTest
@Transactional
class BngaShareSeedTest {

    private static final BigDecimal ISSUED_BEFORE_2024 = new BigDecimal("25131606843");
    private static final BigDecimal ISSUED_FROM_2024 = new BigDecimal("25142205843");

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void fillsAuditedCountsOnceAndKeepsStoredOnes() {
        List<Long> ids = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'BNGA' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!ids.isEmpty(), "BNGA has not been uploaded");
        long bnga = ids.getFirst();
        jdbc.sql("DELETE FROM share_snapshot WHERE company_id = :c").param("c", bnga).update();
        // a count stored for one date (e.g. from a filing) must survive
        jdbc.sql("INSERT INTO share_snapshot (company_id, snapshot_date, shares_outstanding) VALUES (:c, DATE '2024-12-31', 123)")
                .param("c", bnga).update();

        seed();
        seed();   // idempotent

        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT snapshot_date::text AS d, shares_outstanding, treasury_shares FROM share_snapshot
                        WHERE company_id = :c ORDER BY snapshot_date""")
                .param("c", bnga).query().listOfRows();
        assertThat(rows).extracting(r -> r.get("d"))
                .containsExactly("2021-12-31", "2022-12-31", "2023-12-31", "2024-12-31", "2025-12-31");
        assertThat((BigDecimal) rows.get(3).get("shares_outstanding")).isEqualByComparingTo("123");
        for (Map<String, Object> r : List.of(rows.get(0), rows.get(1), rows.get(2), rows.get(4))) {
            BigDecimal issued = ((BigDecimal) r.get("shares_outstanding")).add((BigDecimal) r.get("treasury_shares"));
            assertThat(issued).as(r.get("d").toString())
                    .isEqualByComparingTo(r.get("d").toString().compareTo("2024") < 0 ? ISSUED_BEFORE_2024 : ISSUED_FROM_2024);
        }
        assertThat((BigDecimal) rows.get(4).get("shares_outstanding")).isEqualByComparingTo("25140519043");
    }

    /** Counts from the web only fill empty values; a filing's count replaces a stored one. */
    @Test
    void webCountsNeverReplaceAStoredCount() {
        List<Long> ids = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'BNGA' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!ids.isEmpty(), "BNGA has not been uploaded");
        long bnga = ids.getFirst();
        jdbc.sql("DELETE FROM share_snapshot WHERE company_id = :c").param("c", bnga).update();
        seed();
        java.time.LocalDate yearEnd = java.time.LocalDate.parse("2025-12-31");
        java.time.LocalDate quarter = java.time.LocalDate.parse("2026-03-31");

        repository.upsertShareSnapshot(bnga, share(yearEnd, "999"), true);
        repository.upsertShareSnapshot(bnga, share(quarter, "25142043843"), true);
        assertThat(count(bnga, yearEnd)).isEqualByComparingTo("25140519043");
        assertThat(count(bnga, quarter)).isEqualByComparingTo("25142043843");

        repository.upsertShareSnapshot(bnga, share(yearEnd, "999"), false);
        assertThat(count(bnga, yearEnd)).isEqualByComparingTo("999");
    }

    @Autowired
    private IngestionRepository repository;

    private static com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt share(java.time.LocalDate date, String outstanding) {
        return new com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt(date, null, new BigDecimal(outstanding),
                null, null, "test");
    }

    private BigDecimal count(long company, java.time.LocalDate date) {
        return jdbc.sql("SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c AND snapshot_date = :d")
                .param("c", company).param("d", date).query(BigDecimal.class).single();
    }

    private void seed() {
        new ResourceDatabasePopulator(new ClassPathResource("db/V1.0.13__data_BNGA_shares.sql")).execute(dataSource);
    }
}
