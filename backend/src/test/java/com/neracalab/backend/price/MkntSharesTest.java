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
 * MKNT's share count after its PMTHMETD of 2026-09-30 (V1.0.55__data_MKNT_shares.sql): 1,024,490,000,000 new shares at
 * Rp 1 beside the 5,500,000,000 its filings give; KSEI's holders of 2026-09-30 hold exactly the new shares. Against the
 * Docker Postgres; skipped until MKNT is stored.
 */
@SpringBootTest
class MkntSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void mkntsMarketCapUsesTheSharesAfterItsPlacement() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'MKNT' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "MKNT is not stored");
        long id = company.getFirst();

        BigDecimal after = jdbc.sql("""
                        SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c AND snapshot_date = DATE '2026-10-01'""")
                .param("c", id).query(BigDecimal.class).single();
        assertThat(after).isEqualByComparingTo("1029990000000");
        // the controller's 668,000,000,000 shares are the 64.85% KSEI lists
        assertThat(new BigDecimal("668000000000").multiply(BigDecimal.valueOf(10000))
                .divide(after, 0, java.math.RoundingMode.DOWN)).isEqualByComparingTo("6485");
        long placements = jdbc.sql("""
                        SELECT count(*) FROM corporate_action WHERE company_id = :c AND action_type = 'PRIVATE_PLACEMENT'
                          AND action_date = DATE '2026-09-30'""")
                .param("c", id).query(Long.class).single();
        assertThat(placements).isEqualTo(1);

        List<BigDecimal[]> latest = jdbc.sql("""
                        SELECT share_price, market_cap FROM market_snapshot WHERE company_id = :c AND snapshot_date >= DATE '2026-10-19'
                        ORDER BY snapshot_date DESC LIMIT 1""")
                .param("c", id).query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).list();
        assumeTrue(!latest.isEmpty(), "no MKNT prices after the placement");
        assertThat(latest.getFirst()[1]).isEqualByComparingTo(latest.getFirst()[0].multiply(after));
    }
}
