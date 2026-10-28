package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RANS's share counts around its IPO of 2026-07-10 (V1.0.37__data_RANS_shares.sql): its only filing gives none and
 * the Yahoo Finance count fits none of its EPS, so it had prices but no market cap and no valuation. Against the
 * Docker Postgres; skipped until RANS is stored.
 */
@SpringBootTest
class RansSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void ransHasItsShareCountsAMarketCapAndAValuation() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'RANS' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "RANS is not stored");
        long id = company.getFirst();

        Map<LocalDate, BigDecimal> shares = new java.util.TreeMap<>();
        jdbc.sql("SELECT snapshot_date, shares_outstanding FROM share_snapshot WHERE company_id = :c")
                .param("c", id)
                .query((rs, i) -> shares.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
        assertThat(shares.get(LocalDate.of(2025, 12, 31))).isEqualByComparingTo("10084250000");
        assertThat(shares.get(LocalDate.of(2026, 7, 10))).isEqualByComparingTo("12609250000");
        // the offering: 2,525,000,000 shares, 20.02% of those listed
        assertThat(new BigDecimal("2525000000").multiply(BigDecimal.valueOf(10000))
                .divide(new BigDecimal("12609250000"), 0, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("2002");

        List<BigDecimal[]> latest = jdbc.sql("""
                        SELECT share_price, market_cap FROM market_snapshot WHERE company_id = :c
                        ORDER BY snapshot_date DESC LIMIT 1""")
                .param("c", id).query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).list();
        assumeTrue(!latest.isEmpty(), "no RANS prices");
        assertThat(latest.getFirst()[1]).isEqualByComparingTo(latest.getFirst()[0].multiply(new BigDecimal("12609250000")));
        long valuations = jdbc.sql("SELECT count(*) FROM valuation_snapshot WHERE company_id = :c AND market_cap IS NOT NULL")
                .param("c", id).query(Long.class).single();
        assertThat(valuations).isPositive();
    }
}
