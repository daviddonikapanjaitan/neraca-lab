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
 * ABDA's FY2021 and FY2022 general and administrative expenses, which its FY2022 filing tags as interest and finance
 * costs (V1.0.38__data_ABDA_expenses.sql, run on every start before the derived data): operating expenses, no interest,
 * operating income = profit before tax. Against the Docker Postgres; skipped until those years are stored.
 */
@SpringBootTest
class AbdaExpensesTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theGeneralAndAdministrativeExpensesAreOperatingExpenses() {
        assertYear(2021, "284745743000", "174699981000");
        assertYear(2022, "328420609000", "105991095000");
    }

    private void assertYear(int year, String expenses, String pretax) {
        List<BigDecimal[]> row = jdbc.sql("""
                        SELECT i.operating_expenses, i.interest_expense, i.operating_income, i.pretax_income
                        FROM income_statement i
                        JOIN reporting_period rp ON rp.period_id = i.period_id
                        JOIN company c ON c.company_id = rp.company_id
                        WHERE c.ticker = 'ABDA' AND c.exchange = 'IDX' AND rp.fiscal_year = :y AND rp.period_type = 'FY'""")
                .param("y", year)
                .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)})
                .list();
        assumeTrue(!row.isEmpty(), "ABDA FY" + year + " is not stored");
        BigDecimal[] r = row.getFirst();
        assertThat(r[0]).isEqualByComparingTo(expenses);
        assertThat(r[1]).isNull();
        assertThat(r[2]).isEqualByComparingTo(pretax);
        assertThat(r[3]).isEqualByComparingTo(pretax);
    }
}
