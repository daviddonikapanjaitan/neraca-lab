package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The EBITDA of EV / EBITDA (V1.0.36, ValuationRepository as V1.0.6): the trailing twelve months when derivable
 * (ebitda_basis TTM), else, for an interim period, the full year inside its trailing window (FY 2025 for H1 2026: most
 * companies' H1 2025 has no depreciation until their own H1 2025 filing is uploaded). Against the Docker Postgres, the
 * refresh rolled back; skipped when no stored company is in the case.
 */
@SpringBootTest
@Transactional
class ValuationEbitdaBasisTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ValuationRepository valuations;

    /** The latest valuation of a company after its refresh. */
    private Map<String, Object> latest(long companyId) {
        valuations.refresh(companyId);
        return jdbc.sql("""
                        SELECT vs.enterprise_value, vs.ebitda_ttm, vs.ev_ebitda, vs.ebitda_basis, rp.fiscal_year,
                               rp.period_type
                        FROM valuation_snapshot vs JOIN reporting_period rp ON rp.period_id = vs.period_id
                        WHERE vs.company_id = :c ORDER BY vs.valuation_date DESC LIMIT 1""")
                .param("c", companyId).query().singleRow();
    }

    private List<Long> companies(String sql) {
        return jdbc.sql(sql).query(Long.class).list();
    }

    @Test
    void anInterimPeriodWithoutTrailingEbitdaTakesTheFullYearInsideItsWindow() {
        List<Long> ids = companies("""
                SELECT DISTINCT ON (vs.company_id) vs.company_id FROM valuation_snapshot vs
                JOIN reporting_period rp ON rp.period_id = vs.period_id
                WHERE vs.ebitda_basis LIKE 'FY %' AND vs.ebitda_ttm IS NULL AND rp.period_type <> 'FY'
                ORDER BY vs.company_id, vs.valuation_date DESC LIMIT 5""");
        assumeTrue(!ids.isEmpty(), "no company values an interim period on a full year");
        for (long id : ids) {
            Map<String, Object> v = latest(id);
            if (v.get("ebitda_ttm") != null) {
                continue;   // refreshed from newer data: its trailing twelve months are derivable now
            }
            int year = ((Number) v.get("fiscal_year")).intValue() - 1;
            BigDecimal fy = jdbc.sql("""
                            SELECT i.ebitda FROM reporting_period rp JOIN income_statement i ON i.period_id = rp.period_id
                            WHERE rp.company_id = :c AND rp.period_type = 'FY' AND rp.fiscal_year = :y""")
                    .param("c", id).param("y", year).query(BigDecimal.class).optional().orElse(null);
            if (fy == null || fy.signum() == 0) {
                assertThat(v.get("ev_ebitda")).as("company " + id).isNull();
                continue;
            }
            assertThat(v.get("ebitda_basis")).as("company " + id).isEqualTo("FY " + year);
            BigDecimal expected = ((BigDecimal) v.get("enterprise_value")).divide(fy, 8, RoundingMode.HALF_UP);
            assertThat((BigDecimal) v.get("ev_ebitda")).as("company " + id).isEqualByComparingTo(expected);
        }
    }

    @Test
    void derivableTrailingEbitdaIsUsedAsBefore() {
        List<Long> ids = companies("""
                SELECT DISTINCT ON (vs.company_id) vs.company_id FROM valuation_snapshot vs
                WHERE vs.ebitda_basis = 'TTM' AND vs.ebitda_ttm IS NOT NULL AND vs.ebitda_ttm <> 0
                  AND vs.enterprise_value IS NOT NULL
                ORDER BY vs.company_id, vs.valuation_date DESC LIMIT 5""");
        assumeTrue(!ids.isEmpty(), "no company with trailing EBITDA");
        for (long id : ids) {
            Map<String, Object> v = latest(id);
            if (v.get("ebitda_ttm") == null || v.get("enterprise_value") == null) {
                continue;
            }
            assertThat(v.get("ebitda_basis")).isEqualTo("TTM");
            BigDecimal expected = ((BigDecimal) v.get("enterprise_value"))
                    .divide((BigDecimal) v.get("ebitda_ttm"), 8, RoundingMode.HALF_UP);
            assertThat((BigDecimal) v.get("ev_ebitda")).isEqualByComparingTo(expected);
        }
    }

    /** A bank has no EBITDA: no EV / EBITDA and no basis. */
    @Test
    void aBankHasNone() {
        List<Long> ids = companies("SELECT company_id FROM company WHERE ticker = 'BBCA' AND exchange = 'IDX'");
        assumeTrue(!ids.isEmpty(), "BBCA is not stored");
        Map<String, Object> v = latest(ids.getFirst());
        assertThat(v.get("ev_ebitda")).isNull();
        assertThat(v.get("ebitda_basis")).isNull();
    }
}
