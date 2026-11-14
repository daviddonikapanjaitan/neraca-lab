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
 * CMNP's count from 2025-12-31 (V1.0.58__data_CMNP_shares.sql): common stock Rp 3,348,177,195 thousand / par Rp 500 =
 * 6,696,354,390, inside KSEI's range of 2026-10-30. Against the Docker Postgres; skipped until CMNP is stored.
 */
@SpringBootTest
class CmnpSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void cmnpsCountAfterItsShareIssueIsStored() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'CMNP' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "CMNP is not stored");
        assertThat(new BigDecimal("3348177195000").divide(BigDecimal.valueOf(500))).isEqualByComparingTo("6696354390");
        BigDecimal count = jdbc.sql("""
                        SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c AND snapshot_date = DATE '2026-09-30'""")
                .param("c", company.getFirst()).query(BigDecimal.class).single();
        assertThat(count).isEqualByComparingTo("6696354390");
    }
}
