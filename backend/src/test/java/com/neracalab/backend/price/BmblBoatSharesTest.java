package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * BMBL's and BOAT's share counts (V1.0.43__data_BMBL_BOAT_shares.sql, run on every start before the derived data):
 * neither filing states a count and no Yahoo Finance count fits, so both had prices but no market cap. BMBL: common
 * stocks Rp 41,203,239,800 at par Rp 40 = 1,030,080,995; BOAT: 3,501,680,000 from its IPO of 2024-11-12. Against the
 * Docker Postgres; skipped until the companies are stored.
 */
@SpringBootTest
class BmblBoatSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void bmblHasItsShareCountsAndAMarketCap() {
        long id = company("BMBL");
        Map<LocalDate, BigDecimal> shares = shares(id);
        assertThat(shares.get(LocalDate.of(2023, 12, 31))).isEqualByComparingTo("1030078165");
        assertThat(shares.get(LocalDate.of(2026, 6, 30))).isEqualByComparingTo("1030080995");
        // the share capital at par Rp 40 and the H1 2026 EPS confirm the count: -1,081,369,015 / 1,030,080,995 = -1.05
        assertThat(new BigDecimal("41203239800").divide(new BigDecimal("40"))).isEqualByComparingTo("1030080995");
        assertThat(new BigDecimal("-1081369015").divide(new BigDecimal("1030080995"), 2, RoundingMode.HALF_UP))
                .isEqualByComparingTo("-1.05");
        assertMarketCap(id, LocalDate.of(2026, 9, 30), new BigDecimal("1030080995"));
    }

    @Test
    void boatHasItsShareCountsFromTheListingAndAMarketCap() {
        long id = company("BOAT");
        Map<LocalDate, BigDecimal> shares = shares(id);
        assertThat(shares.get(LocalDate.of(2023, 12, 31))).isEqualByComparingTo("2501200000");
        assertThat(shares.get(LocalDate.of(2024, 11, 12))).isEqualByComparingTo("3501680000");
        assertThat(shares.get(LocalDate.of(2026, 9, 30))).isEqualByComparingTo("3501680000");
        // the 9M 2026 EPS (USD) confirms it: 1,100,748 / 3,501,680,000 = 0.0003143
        assertThat(new BigDecimal("1100748").divide(new BigDecimal("3501680000"), 7, RoundingMode.HALF_UP))
                .isEqualByComparingTo("0.0003143");
        assertMarketCap(id, LocalDate.of(2026, 9, 30), new BigDecimal("3501680000"));
    }

    private long company(String ticker) {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = :t AND exchange = 'IDX'")
                .param("t", ticker).query(Long.class).list();
        assumeTrue(!company.isEmpty(), ticker + " is not stored");
        return company.getFirst();
    }

    private Map<LocalDate, BigDecimal> shares(long id) {
        Map<LocalDate, BigDecimal> shares = new java.util.TreeMap<>();
        jdbc.sql("SELECT snapshot_date, shares_outstanding FROM share_snapshot WHERE company_id = :c")
                .param("c", id)
                .query((rs, i) -> shares.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
        return shares;
    }

    private void assertMarketCap(long id, LocalDate onOrBefore, BigDecimal shares) {
        List<BigDecimal[]> row = jdbc.sql("""
                        SELECT share_price, market_cap FROM market_snapshot
                        WHERE company_id = :c AND snapshot_date <= :d
                        ORDER BY snapshot_date DESC LIMIT 1""")
                .param("c", id).param("d", onOrBefore)
                .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).list();
        assumeTrue(!row.isEmpty(), "no prices on or before " + onOrBefore);
        assertThat(row.getFirst()[1]).isNotNull();
        assertThat(row.getFirst()[1]).isEqualByComparingTo(row.getFirst()[0].multiply(shares));
    }
}
