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
 * ENRG's share counts from public sources (V1.0.35__data_ENRG_shares.sql, run on every start before the derived data):
 * its USD filings give none and the Yahoo Finance count fits none of its EPS, so it had prices but no market cap and no
 * valuation. Against the Docker Postgres; skipped until ENRG is stored.
 */
@SpringBootTest
class EnrgSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void enrgHasItsShareCountsAMarketCapAndValuations() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'ENRG' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "ENRG is not stored");
        long id = company.getFirst();

        Map<LocalDate, BigDecimal> shares = new java.util.TreeMap<>();
        jdbc.sql("SELECT snapshot_date, shares_outstanding FROM share_snapshot WHERE company_id = :c")
                .param("c", id)
                .query((rs, i) -> shares.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
        assertThat(shares.get(LocalDate.of(2025, 12, 31))).isEqualByComparingTo("26346230250");
        assertThat(shares.get(LocalDate.of(2026, 6, 12))).isEqualByComparingTo("26564543750");
        // the private placement of June 2026: 218,313,500 new shares
        assertThat(new BigDecimal("26564543750").subtract(new BigDecimal("26346230250")))
                .isEqualByComparingTo("218313500");

        // the market cap of a trading day uses the latest count on or before it
        assertMarketCap(id, LocalDate.of(2026, 3, 31), new BigDecimal("26346230250"));
        assertMarketCap(id, LocalDate.of(2026, 9, 30), new BigDecimal("26564543750"));
        long valuations = jdbc.sql("SELECT count(*) FROM valuation_snapshot WHERE company_id = :c AND market_cap IS NOT NULL")
                .param("c", id).query(Long.class).single();
        assertThat(valuations).isPositive();
        long placements = jdbc.sql("""
                        SELECT count(*) FROM corporate_action
                        WHERE company_id = :c AND action_type = 'PRIVATE_PLACEMENT' AND action_date = DATE '2026-06-12'
                          AND shares_issued = 218313500""")
                .param("c", id).query(Long.class).single();
        assertThat(placements).isEqualTo(1);
    }

    private void assertMarketCap(long id, LocalDate onOrBefore, BigDecimal shares) {
        List<BigDecimal[]> row = jdbc.sql("""
                        SELECT share_price, market_cap FROM market_snapshot
                        WHERE company_id = :c AND snapshot_date <= :d
                        ORDER BY snapshot_date DESC LIMIT 1""")
                .param("c", id).param("d", onOrBefore)
                .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).list();
        assumeTrue(!row.isEmpty(), "no ENRG prices on or before " + onOrBefore);
        assertThat(row.getFirst()[1]).isNotNull();
        assertThat(row.getFirst()[1]).isEqualByComparingTo(row.getFirst()[0].multiply(shares).setScale(4,
                java.math.RoundingMode.HALF_UP));
    }
}
