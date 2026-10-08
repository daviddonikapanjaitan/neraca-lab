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
 * A share split restated by a later filing's comparative (BMRI, 2:1 in 2023: its FY2022 filing states EPS 882.52,
 * its FY2023 filing's comparative 441.26 for the same profit). Before, the comparative filled FY2022's empty share
 * count with the post-split count beside the pre-split EPS. Runs against the Docker Postgres (FY2096 .. FY2098 of
 * HRTA); rolled back.
 */
@SpringBootTest
@Transactional
class ShareSplitTest {

    private static final PeriodRef FY2096 = PeriodRef.fullYearEnding(LocalDate.parse("2096-12-31"));
    private static final PeriodRef FY2097 = PeriodRef.fullYearEnding(LocalDate.parse("2097-12-31"));
    private static final PeriodRef FY2098 = PeriodRef.fullYearEnding(LocalDate.parse("2098-12-31"));

    @Autowired
    private IngestionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aSplitRestatedByAComparativeAdjustsThePeriodAndEarlierPeriodsOnTheOldBasisOnce() {
        long company = company();
        long fy2096 = repository.upsertPeriod(company, FY2096, "test-2096.xlsx", true, true);
        long fy2097 = repository.upsertPeriod(company, FY2097, "test-2097.xlsx", true, true);
        long fy2098 = repository.upsertPeriod(company, FY2098, "test-2098.xlsx", true, true);

        // own filings before the split (46.65 billion shares): FY2097 without a share count; FY2096 on another basis
        repository.writeStatement(company, fy2097, income(FY2097, "41170637000000", "882.52", null), true);
        repository.writeStatement(company, fy2096, income(FY2096, "28028155000000", "601.06", null), true);
        long other = repository.upsertPeriod(company, PeriodRef.fullYearEnding(LocalDate.parse("2095-12-31")), "t.xlsx", true, true);
        repository.writeStatement(company, other, income(PeriodRef.fullYearEnding(LocalDate.parse("2095-12-31")),
                "10000000000000", "107.15", null), true);    // 93.3 billion shares: not on the old basis

        // the FY2098 filing's comparative restates FY2097 for the 2:1 split, with the post-split share count
        var write = repository.writeStatement(company, fy2097, income(FY2097, "41170637000000", "441.26", "93333336000"), false);

        assertThat(write.outcome()).isEqualTo(WriteOutcome.SPLIT_ADJUSTED);
        assertThat(write.differences()).anyMatch(d -> d.startsWith("share split 2:1 restated by this comparative: basic_eps 882.52 -> 441.26"))
                .anyMatch(d -> d.contains("2096 FY basic_eps 601.06 -> 300.53"));
        assertThat(stored(fy2097, "basic_eps")).isEqualByComparingTo("441.26");
        assertThat(stored(fy2097, "basic_shares")).isEqualByComparingTo("93333336000");
        assertThat(stored(fy2096, "basic_eps")).isEqualByComparingTo("300.53");
        assertThat(stored(other, "basic_eps")).isEqualByComparingTo("107.15");

        // the same filing again: nothing is adjusted twice
        var again = repository.writeStatement(company, fy2097, income(FY2097, "41170637000000", "441.26", "93333336000"), false);
        assertThat(again.outcome()).isEqualTo(WriteOutcome.KEPT_EXISTING);
        assertThat(stored(fy2096, "basic_eps")).isEqualByComparingTo("300.53");

        // a comparative with another EPS (a restatement, not a split) never fills the share count of a stored EPS
        repository.writeStatement(company, fy2098, income(FY2098, "55782742000000", "602.41", null), true);
        var restated = repository.writeStatement(company, fy2098, income(FY2098, "55782742000000", "597.67", "93333336000"), false);
        assertThat(restated.outcome()).isEqualTo(WriteOutcome.KEPT_EXISTING);
        assertThat(stored(fy2098, "basic_eps")).isEqualByComparingTo("602.41");
        assertThat(stored(fy2098, "basic_shares")).isNull();
    }

    @Test
    void splitFactors() {
        assertThat(IngestionRepository.splitFactor(new BigDecimal("882.52"), new BigDecimal("441.26"))).isEqualByComparingTo("2");
        assertThat(IngestionRepository.splitFactor(new BigDecimal("100"), new BigDecimal("1000"))).isEqualByComparingTo("0.1");
        assertThat(IngestionRepository.splitFactor(new BigDecimal("602.41"), new BigDecimal("597.67"))).isNull();
        assertThat(IngestionRepository.splitFactor(new BigDecimal("150"), new BigDecimal("100"))).isNull();
    }

    private long company() {
        List<Long> companies = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'HRTA'").query(Long.class).list();
        assumeTrue(!companies.isEmpty(), "HRTA is not in the database");
        return companies.getFirst();
    }

    private static MappedStatement income(PeriodRef period, String profit, String eps, String shares) {
        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("net_income", new BigDecimal(profit));
        v.put("net_income_to_parent", new BigDecimal(profit));
        v.put("basic_eps", new BigDecimal(eps));
        v.put("basic_shares", shares == null ? null : new BigDecimal(shares));
        return new MappedStatement("income_statement", StatementColumn.CURRENT_PERIOD, period, "4322000", v, Map.of(),
                List.of(), List.of());
    }

    private BigDecimal stored(long period, String column) {
        return jdbc.sql("SELECT " + column + " FROM income_statement WHERE period_id = :p").param("p", period)
                .query((rs, i) -> rs.getBigDecimal(1)).list().getFirst();
    }
}
