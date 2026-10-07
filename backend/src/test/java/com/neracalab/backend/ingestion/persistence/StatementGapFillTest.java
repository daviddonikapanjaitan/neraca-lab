package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.PeriodRef;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.WriteOutcome;

/**
 * A filing that does not tag a field (SIMP FY2023: only gross profit, no revenue) and another filing's
 * comparative that does: the comparative fills the empty field, never changes a stored value, and the
 * period's own filing does not wipe it again. Runs against the Docker Postgres (FY2099 of HRTA); rolled back.
 */
@SpringBootTest
@Transactional
class StatementGapFillTest {

    private static final PeriodRef FY2099 = PeriodRef.fullYearEnding(LocalDate.parse("2099-12-31"));

    @Autowired
    private IngestionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void comparativeFillsGapsAndNothingElse() {
        List<Long> companies = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'HRTA'").query(Long.class).list();
        assumeTrue(!companies.isEmpty(), "HRTA is not in the database");
        long company = companies.getFirst();
        long period = repository.upsertPeriod(company, FY2099, "test-own-filing.xlsx", true, true);

        // the period's own filing: gross profit only
        var own = repository.writeStatement(company, period, income(null, "300", "100"), true);
        assertThat(own.outcome()).isEqualTo(WriteOutcome.INSERTED);
        assertThat(stored(period, "revenue")).isNull();

        // a later filing's comparative: fills revenue, keeps the stored gross profit
        var comparative = repository.writeStatement(company, period, income("1000", "999", "100"), false);
        assertThat(comparative.outcome()).isEqualTo(WriteOutcome.FILLED_GAPS);
        assertThat(comparative.differences()).contains("revenue: filled 1000")
                .anyMatch(d -> d.startsWith("gross_profit: stored 300"));
        assertThat(stored(period, "revenue")).isEqualByComparingTo("1000");
        assertThat(stored(period, "gross_profit")).isEqualByComparingTo("300");

        // nothing left to fill: kept
        assertThat(repository.writeStatement(company, period, income("2000", null, "100"), false).outcome())
                .isEqualTo(WriteOutcome.KEPT_EXISTING);
        assertThat(stored(period, "revenue")).isEqualByComparingTo("1000");

        // the own filing again: its values replace stored ones, but its untagged revenue does not wipe the filled one
        assertThat(repository.writeStatement(company, period, income(null, "301", "100"), true).outcome())
                .isEqualTo(WriteOutcome.UPDATED);
        assertThat(stored(period, "revenue")).isEqualByComparingTo("1000");
        assertThat(stored(period, "gross_profit")).isEqualByComparingTo("301");
    }

    private static MappedStatement income(String revenue, String grossProfit, String netIncome) {
        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("revenue", revenue == null ? null : new BigDecimal(revenue));
        v.put("gross_profit", grossProfit == null ? null : new BigDecimal(grossProfit));
        v.put("net_income", new BigDecimal(netIncome));
        return new MappedStatement("income_statement", StatementColumn.CURRENT_PERIOD, FY2099, "1311000", v, Map.of(),
                List.of(), List.of());
    }

    private BigDecimal stored(long period, String column) {
        List<BigDecimal> values = jdbc.sql("SELECT " + column + " FROM income_statement WHERE period_id = :p")
                .param("p", period).query((rs, i) -> rs.getBigDecimal(1)).list();
        assertThat(values).hasSize(1);
        return values.getFirst();
    }
}
