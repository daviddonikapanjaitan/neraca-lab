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
 * SOUL's share count after its IPO of 2023-01-06 (V1.0.51__data_SOUL_shares.sql): its filings end with the pre-IPO
 * count of 812,500,000 at 2022-12-31, so its market cap left out the 270,000,000 shares of the offering. Against the
 * Docker Postgres; skipped until SOUL is stored.
 */
@SpringBootTest
class SoulSharesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void soulsMarketCapUsesTheSharesAfterItsIpo() {
        List<Long> company = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'SOUL' AND exchange = 'IDX'")
                .query(Long.class).list();
        assumeTrue(!company.isEmpty(), "SOUL is not stored");
        long id = company.getFirst();

        BigDecimal after = jdbc.sql("""
                        SELECT shares_outstanding FROM share_snapshot WHERE company_id = :c AND snapshot_date = DATE '2023-01-06'""")
                .param("c", id).query(BigDecimal.class).single();
        assertThat(after).isEqualByComparingTo("1082500000");
        // the offering: 270,000,000 of 1,082,500,000 = 24.94%
        assertThat(new BigDecimal("270000000").multiply(BigDecimal.valueOf(10000))
                .divide(after, 0, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("2494");
        long ipo = jdbc.sql("SELECT count(*) FROM corporate_action WHERE company_id = :c AND action_type = 'IPO'")
                .param("c", id).query(Long.class).single();
        assertThat(ipo).isEqualTo(1);

        List<BigDecimal[]> latest = jdbc.sql("""
                        SELECT share_price, market_cap FROM market_snapshot WHERE company_id = :c AND snapshot_date >= DATE '2023-01-06'
                        ORDER BY snapshot_date DESC LIMIT 1""")
                .param("c", id).query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).list();
        assumeTrue(!latest.isEmpty(), "no SOUL prices after its IPO");
        assertThat(latest.getFirst()[1]).isEqualByComparingTo(latest.getFirst()[0].multiply(after));
    }
}
