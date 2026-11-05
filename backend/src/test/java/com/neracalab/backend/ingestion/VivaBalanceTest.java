package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * VIVA's FY2022 total liabilities and non-controlling interests, which its FY2022 workbook files a thousand times too
 * small (V1.0.47__data_VIVA_balance.sql, run on every start before the derived data): total assets 8,873,182,411 =
 * liabilities 10,457,577,261 + equity -1,584,394,850 thousand; NCI = -1,584,394,850 - (-2,191,545,380) = 607,150,530
 * thousand. Against the Docker Postgres; skipped until FY2022 is stored.
 */
@SpringBootTest
class VivaBalanceTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theFy2022BalanceSheetBalances() {
        List<BigDecimal[]> row = jdbc.sql("""
                        SELECT b.total_assets, b.total_liabilities, b.total_equity, b.non_controlling_interest
                        FROM balance_sheet b
                        JOIN reporting_period rp ON rp.period_id = b.period_id
                        JOIN company c ON c.company_id = rp.company_id
                        WHERE c.ticker = 'VIVA' AND c.exchange = 'IDX' AND rp.fiscal_year = 2022 AND rp.period_type = 'FY'""")
                .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)})
                .list();
        assumeTrue(!row.isEmpty(), "VIVA FY2022 is not stored");
        BigDecimal[] r = row.getFirst();
        assertThat(r[1]).isEqualByComparingTo("10457577261000");
        assertThat(r[3]).isEqualByComparingTo("607150530000");
        assertThat(r[0]).isEqualByComparingTo(r[1].add(r[2]));
    }
}
