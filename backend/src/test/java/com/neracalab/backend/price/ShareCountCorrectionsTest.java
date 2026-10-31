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
 * The share counts of V1.0.44__data_MSIN_RMKE_SMGA_WIFI_INET_PACK_shares.sql (run on every start and after every
 * upload): MSIN's count of a par value read from an EPS filed ten times too large, SMGA's of a wrong par value, RMKE's
 * split 1:5 of 2026-07-17, and the counts after the share issues of WIFI, INET and PACK. Each latest count agrees with
 * KSEI's 1% list of 2026-09-30. Against the Docker Postgres; a company not stored is skipped.
 */
@SpringBootTest
class ShareCountCorrectionsTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void msinHasTheCountOfItsParValueOfRp10() {
        Map<LocalDate, BigDecimal> shares = shares("MSIN");
        assertThat(shares.get(LocalDate.of(2025, 12, 31))).isEqualByComparingTo("60676178205");
        assertThat(shares.get(LocalDate.of(2026, 6, 30))).isEqualByComparingTo("60676178205");
        assertThat(shares.values()).noneMatch(s -> s != null && s.compareTo(new BigDecimal("6067620000")) == 0);
        // the H1 2026 EPS filed as 46.9 is 284,347 million / 60.68 billion = 4.69
        List<BigDecimal> eps = jdbc.sql("""
                        SELECT i.basic_eps FROM income_statement i JOIN reporting_period rp ON rp.period_id = i.period_id
                        JOIN company c ON c.company_id = rp.company_id
                        WHERE c.ticker = 'MSIN' AND rp.fiscal_year = 2026 AND rp.period_type = 'H1'""")
                .query(BigDecimal.class).list();
        assumeTrue(!eps.isEmpty(), "MSIN H1 2026 is not stored");
        assertThat(eps.getFirst()).isEqualByComparingTo("4.69");
    }

    @Test
    void smgaHasItsListingCountOfAParValueOfRp20() {
        Map<LocalDate, BigDecimal> shares = shares("SMGA");
        assertThat(shares.get(LocalDate.of(2023, 12, 31))).isEqualByComparingTo("7000000000");
        assertThat(shares.get(LocalDate.of(2024, 12, 31))).isEqualByComparingTo("8750000000");
        assertThat(shares.get(LocalDate.of(2026, 6, 30))).isEqualByComparingTo("8750000000");
    }

    @Test
    void rmkeIsStoredSplitAdjusted() {
        Map<LocalDate, BigDecimal> shares = shares("RMKE");
        assertThat(shares.get(LocalDate.of(2025, 12, 31))).isEqualByComparingTo("21875000000");
        assertThat(shares.get(LocalDate.of(2026, 7, 17))).isEqualByComparingTo("21875000000");
        assertThat(shares.values()).noneMatch(s -> s != null && s.compareTo(new BigDecimal("4375000000")) == 0);
        long splits = jdbc.sql("""
                        SELECT count(*) FROM corporate_action ca JOIN company c USING (company_id)
                        WHERE c.ticker = 'RMKE' AND ca.action_type = 'STOCK_SPLIT' AND ca.action_date = DATE '2026-07-17'
                          AND ca.description LIKE 'Recorded from public sources: %'""")
                .query(Long.class).single();
        assertThat(splits).isEqualTo(1);
    }

    @Test
    void theCountsAfterTheShareIssuesAreStored() {
        assertThat(shares("WIFI").get(LocalDate.of(2026, 6, 30))).isEqualByComparingTo("5308549015");
        assertThat(shares("INET").get(LocalDate.of(2026, 6, 30))).isEqualByComparingTo("22374111088");
        assertThat(shares("PACK").get(LocalDate.of(2026, 6, 30))).isEqualByComparingTo("32379253695");
        // share capital / the par value of the earlier filings
        assertThat(new BigDecimal("530854901500").divide(new BigDecimal("100"))).isEqualByComparingTo("5308549015");
        assertThat(new BigDecimal("223741110880").divide(BigDecimal.TEN)).isEqualByComparingTo("22374111088");
        assertThat(new BigDecimal("323792536950").divide(BigDecimal.TEN)).isEqualByComparingTo("32379253695");
    }

    private Map<LocalDate, BigDecimal> shares(String ticker) {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = :t AND exchange = 'IDX'")
                .param("t", ticker).query(Long.class).list();
        assumeTrue(!company.isEmpty(), ticker + " is not stored");
        Map<LocalDate, BigDecimal> shares = new java.util.TreeMap<>();
        jdbc.sql("SELECT snapshot_date, shares_outstanding FROM share_snapshot WHERE company_id = :c")
                .param("c", company.getFirst())
                .query((rs, i) -> shares.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
        return shares;
    }
}
