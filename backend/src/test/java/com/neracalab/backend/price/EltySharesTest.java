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
 * ELTY's share count (V1.0.57__data_ELTY_shares.sql): 43,521,913,019 shares of two par series (1,400,000,000 at Rp 500
 * + 42,121,913,019 at Rp 100 = the filed Rp 4,912,191 million), not the 49,121,910,000 one par value of Rp 100 gave.
 * Against the Docker Postgres; skipped until ELTY is stored.
 */
@SpringBootTest
class EltySharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void eltysCountIsTheTwoSeriesCount() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'ELTY' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "ELTY is not stored");
        long id = company.getFirst();

        assertThat(new BigDecimal("1400000000").multiply(BigDecimal.valueOf(500))
                .add(new BigDecimal("42121913019").multiply(BigDecimal.valueOf(100))))
                .isEqualByComparingTo("4912191301900");
        List<BigDecimal> counts = jdbc.sql("""
                        SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c AND shares_outstanding IS NOT NULL
                          AND snapshot_date <= DATE '2026-09-30'""")
                .param("c", id).query(BigDecimal.class).list();
        assertThat(counts).isNotEmpty().allMatch(s -> s.compareTo(new BigDecimal("43521913019")) == 0);
        long wrong = jdbc.sql("""
                        SELECT count(*) FROM balance_sheet WHERE company_id = :c AND shares_outstanding = 49121910000""")
                .param("c", id).query(Long.class).single();
        assertThat(wrong).isZero();
    }
}
