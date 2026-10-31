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
 * The share counts of V1.0.45__data_KOIN_HILL_shares.sql (run on every start and after every upload): KOIN's 980,843,732
 * shares (share capital Rp 98,084 million at par Rp 100; KSEI 980,839,205) instead of the par values Rp 10,000 / Rp 10 its
 * EPS filed a hundred times too large fitted, without the reverse split 100:1 the upload detection recorded between
 * them; HILL's 14,741,500,000 (Rp 294,830 million at par Rp 20; KSEI) at 2024-12-31 and 2025-12-31 instead of twice
 * that. Against the Docker Postgres; a company not stored is skipped.
 */
@SpringBootTest
class KoinHillSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void koinHasTheCountOfItsShareCapitalAtParRp100() {
        Map<LocalDate, BigDecimal> shares = shares("KOIN");
        assertThat(shares.get(LocalDate.of(2021, 12, 31))).isEqualByComparingTo("980843732");
        assertThat(shares.get(LocalDate.of(2025, 12, 31))).isEqualByComparingTo("980843732");
        assertThat(shares.get(LocalDate.of(2026, 9, 30))).isEqualByComparingTo("980843732");
        assertThat(shares.values()).noneMatch(s -> s != null && (s.compareTo(new BigDecimal("9808400000")) == 0
                || s.compareTo(new BigDecimal("9808437")) == 0));
        long falseSplit = jdbc.sql("""
                        SELECT count(*) FROM corporate_action ca JOIN company c ON c.company_id = ca.company_id
                        WHERE c.ticker = 'KOIN' AND ca.action_type = 'REVERSE_SPLIT' AND ca.action_date = DATE '2023-01-01'""")
                .query(Long.class).single();
        assertThat(falseSplit).isZero();
    }

    @Test
    void hillHasTheCountOfItsShareCapitalAtParRp20() {
        Map<LocalDate, BigDecimal> shares = shares("HILL");
        assertThat(shares.get(LocalDate.of(2024, 12, 31))).isEqualByComparingTo("14741500000");
        assertThat(shares.get(LocalDate.of(2025, 12, 31))).isEqualByComparingTo("14741500000");
        assertThat(new BigDecimal("294830000000").divide(new BigDecimal("20"))).isEqualByComparingTo("14741500000");
    }

    @Test
    void flmcHasTheCountOfItsShareCapitalAtParRp80() {
        Map<LocalDate, BigDecimal> shares = shares("FLMC");
        assertThat(shares.get(LocalDate.of(2025, 12, 31))).isEqualByComparingTo("781250000");
        assertThat(shares.get(LocalDate.of(2026, 6, 30))).isEqualByComparingTo("781250000");
        assertThat(shares.values()).noneMatch(s -> s != null && s.compareTo(BigDecimal.ONE) == 0);
        assertThat(new BigDecimal("62500000000").divide(new BigDecimal("80"))).isEqualByComparingTo("781250000");
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
