package com.neracalab.backend.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * A current-period segment save replaces the period's whole breakdown: a segment that only another
 * filing reported for the period (HRTA FY2024's comparative split FY2023 "Grosir" into "Grosir" +
 * "Ekspor") is removed, so revenue is not counted twice. Runs against the Docker Postgres; rolled back.
 */
@SpringBootTest
@Transactional
class IngestionRepositorySegmentsTest {

    private static final String STALE = "ZZ test segment of another filing";

    @Autowired
    private IngestionRepository repository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void currentPeriodBreakdownRemovesSegmentsOfOtherFilings() {
        List<Long> companies = jdbc.sql("SELECT company_id FROM company WHERE ticker = 'HRTA'").query(Long.class).list();
        assumeTrue(!companies.isEmpty(), "HRTA is not in the database");
        long company = companies.get(0);
        List<Long> periods = jdbc.sql("""
                        SELECT sf.period_id FROM segment_financial sf
                        WHERE sf.company_id = :c GROUP BY sf.period_id ORDER BY sf.period_id LIMIT 1""")
                .param("c", company).query(Long.class).list();
        assumeTrue(!periods.isEmpty(), "HRTA has no stored segments");
        long period = periods.get(0);
        List<Long> filed = repository.segmentsWithRevenue(period);
        BigDecimal filedTotal = total(period);

        long stale = repository.ensureSegment(company, "PRODUCT", STALE, STALE).segmentId();
        repository.writeSegmentRevenue(company, stale, period, new BigDecimal("1000"), false);
        assertThat(total(period)).isEqualByComparingTo(filedTotal.add(new BigDecimal("1000")));

        List<String> removed = repository.removeOtherSegmentRevenue(period, filed);

        assertThat(removed).containsExactly(STALE);
        assertThat(repository.segmentsWithRevenue(period)).containsExactlyElementsOf(filed);
        assertThat(total(period)).isEqualByComparingTo(filedTotal);
    }

    private BigDecimal total(long period) {
        return jdbc.sql("SELECT COALESCE(sum(revenue), 0) FROM segment_financial WHERE period_id = :p")
                .param("p", period).query(BigDecimal.class).single();
    }
}
