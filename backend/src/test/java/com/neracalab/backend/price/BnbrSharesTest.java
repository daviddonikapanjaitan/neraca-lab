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
 * BNBR's share counts from public sources (V1.0.31__data_BNBR_shares.sql, run on every start before the derived data):
 * its filings give none (five par-value series) and the Yahoo Finance count fits none of its EPS, so it had no market cap
 * and no valuation. Against the Docker Postgres; skipped until BNBR is stored.
 */
@SpringBootTest
class BnbrSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void bnbrHasItsShareCountsAMarketCapAndValuations() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'BNBR' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "BNBR is not stored");
        long id = company.getFirst();

        Map<LocalDate, BigDecimal> shares = new java.util.TreeMap<>();
        jdbc.sql("SELECT snapshot_date, shares_outstanding FROM share_snapshot WHERE company_id = :c")
                .param("c", id)
                .query((rs, i) -> shares.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
        assertThat(shares.get(LocalDate.of(2023, 12, 31))).isEqualByComparingTo("160057457509");
        assertThat(shares.get(LocalDate.of(2024, 12, 11))).isEqualByComparingTo("173416832509");
        assertThat(shares.get(LocalDate.of(2026, 7, 31))).isEqualByComparingTo("263336671587");

        // the market cap of a trading day uses the latest count on or before it
        assertMarketCap(id, LocalDate.of(2025, 12, 31), new BigDecimal("173416832509"));
        assertMarketCap(id, LocalDate.of(2026, 9, 30), new BigDecimal("263336671587"));
        long valuations = jdbc.sql("SELECT count(*) FROM valuation_snapshot WHERE company_id = :c AND market_cap IS NOT NULL")
                .param("c", id).query(Long.class).single();
        assertThat(valuations).isPositive();
        // the FY2025 EPS confirms the count: 493,852 million / 173,416,832,509 = 2.85
        assertThat(new BigDecimal("493852000000").divide(new BigDecimal("173416832509"), 2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("2.85");
    }

    private void assertMarketCap(long id, LocalDate onOrBefore, BigDecimal shares) {
        List<BigDecimal[]> row = jdbc.sql("""
                        SELECT share_price, market_cap FROM market_snapshot
                        WHERE company_id = :c AND snapshot_date <= :d
                        ORDER BY snapshot_date DESC LIMIT 1""")
                .param("c", id).param("d", onOrBefore)
                .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).list();
        assumeTrue(!row.isEmpty(), "no BNBR prices on or before " + onOrBefore);
        assertThat(row.getFirst()[1]).isNotNull();
        assertThat(row.getFirst()[1]).isEqualByComparingTo(row.getFirst()[0].multiply(shares));
    }
}
