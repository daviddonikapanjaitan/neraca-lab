package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * HILL's stock split 1:5 of 2025-03-14 (V1.0.56__data_HILL_split.sql): the counts and EPS of 2021 - 2023 are stored
 * split-adjusted, like the prices. Against the Docker Postgres; skipped until HILL is stored.
 */
@SpringBootTest
class HillSplitTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void hillsCountsBeforeTheSplitAreSplitAdjusted() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'HILL' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "HILL is not stored");
        long id = company.getFirst();

        List<BigDecimal> before = jdbc.sql("""
                        SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c AND snapshot_date < DATE '2025-03-14'
                          AND shares_outstanding IS NOT NULL""")
                .param("c", id).query(BigDecimal.class).list();
        assumeTrue(!before.isEmpty(), "no HILL counts before the split");
        // 2,506,000,000 x 5 before the IPO of 2023, 2,948,300,000 x 5 after it: never the old basis
        assertThat(before).allMatch(s -> s.compareTo(new BigDecimal("12530000000")) == 0
                || s.compareTo(new BigDecimal("14741500000")) == 0);
        long splits = jdbc.sql("""
                        SELECT count(*) FROM corporate_action WHERE company_id = :c AND action_type = 'STOCK_SPLIT'
                          AND action_date = DATE '2025-03-14' AND ratio_from = 1 AND ratio_to = 5""")
                .param("c", id).query(Long.class).single();
        assertThat(splits).isEqualTo(1);
    }
}
