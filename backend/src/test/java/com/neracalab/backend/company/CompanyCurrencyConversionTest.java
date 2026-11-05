package com.neracalab.backend.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.neracalab.backend.company.CompanyDetailResponse.CurrencyConversion;
import com.neracalab.backend.company.CompanyDetailResponse.Period;
import com.neracalab.backend.company.CompanyDetailResponse.PeriodRate;

/**
 * The USD -> IDR rates of the company detail (Companies > Fundamentals, the USD | IDR toggle) from the Forex pair
 * USDIDR ({@code instrument_daily}): per period the close of the period end (balance sheet) and the average of the
 * period's closes (flows), checked against the stored closes. Against the Docker Postgres; skipped when SMDR (a USD
 * reporter on IDX) or the pair is not stored.
 */
@SpringBootTest
class CompanyCurrencyConversionTest {

    @Autowired
    private CompanyService companies;

    @Autowired
    private JdbcClient jdbc;

    private boolean stored(String sql) {
        return jdbc.sql(sql).query(Long.class).single() > 0;
    }

    @Test
    void aUsdReporterGetsTheClosingAndAverageRateOfEveryPeriod() {
        assumeTrue(stored("SELECT count(*) FROM company WHERE ticker = 'SMDR' AND exchange = 'IDX' AND currency = 'USD'"),
                "SMDR is not stored as a USD reporter");
        assumeTrue(stored("SELECT count(*) FROM instrument_daily WHERE instrument_code = 'USDIDR'"), "no USD/IDR closes");

        CompanyDetailResponse detail = companies.detail("IDX", "SMDR");
        CurrencyConversion conversion = detail.conversion();

        assertThat(conversion).isNotNull();
        assertThat(conversion.from()).isEqualTo("USD");
        assertThat(conversion.to()).isEqualTo("IDR");
        assertThat(conversion.instrument()).isEqualTo("USDIDR");
        Map<Long, PeriodRate> rates = conversion.rates().stream()
                .collect(Collectors.toMap(PeriodRate::periodId, Function.identity()));
        List<Period> periods = detail.periods();
        assertThat(periods).isNotEmpty();
        for (Period p : periods) {
            PeriodRate rate = rates.get(p.periodId());
            assertThat(rate).as(p.period()).isNotNull();

            // closing: the last close on or before the period end
            Map<String, Object> close = jdbc.sql("""
                            SELECT close_price, trading_date FROM instrument_daily
                            WHERE instrument_code = 'USDIDR' AND trading_date <= :end
                            ORDER BY trading_date DESC LIMIT 1""")
                    .param("end", p.periodEnd()).query().singleRow();
            assertThat(rate.closingRate()).as(p.period()).isEqualByComparingTo((BigDecimal) close.get("close_price"));
            LocalDate closeDate = ((java.sql.Date) close.get("trading_date")).toLocalDate();
            assertThat(rate.closingRateDate()).as(p.period()).isEqualTo(closeDate);
            assertThat(closeDate).isAfter(p.periodEnd().minusDays(10));

            // average: the mean of the closes from period start to end
            Map<String, Object> avg = jdbc.sql("""
                            SELECT avg(close_price) AS a, count(*) AS n FROM instrument_daily
                            WHERE instrument_code = 'USDIDR' AND trading_date BETWEEN :start AND :end""")
                    .param("start", p.periodStart()).param("end", p.periodEnd()).query().singleRow();
            assertThat(rate.averageDays()).as(p.period()).isEqualTo(((Number) avg.get("n")).intValue());
            assertThat(rate.averageRate()).as(p.period())
                    .isEqualByComparingTo(((BigDecimal) avg.get("a")).setScale(8, RoundingMode.HALF_UP));
        }
    }

    /** SMDR FY2025 (2025-01-01 - 2025-12-31): close 16,709 on 2025-12-31, average 16,451.41 of 256 closes. */
    @Test
    void smdrFy2025() {
        assumeTrue(stored("SELECT count(*) FROM instrument_daily WHERE instrument_code = 'USDIDR' "
                + "AND trading_date = DATE '2025-12-31' AND close_price = 16709"), "USD/IDR of 2025-12-31 not as known");
        assumeTrue(stored("SELECT count(*) FROM company WHERE ticker = 'SMDR' AND exchange = 'IDX' AND currency = 'USD'"),
                "SMDR is not stored as a USD reporter");

        CompanyDetailResponse detail = companies.detail("IDX", "SMDR");
        Period fy2025 = detail.periods().stream().filter(p -> p.period().equals("2025 FY")).findFirst().orElse(null);
        assumeTrue(fy2025 != null, "SMDR FY2025 is not stored");
        PeriodRate rate = detail.conversion().rates().stream()
                .filter(r -> r.periodId() == fy2025.periodId()).findFirst().orElseThrow();

        assertThat(rate.closingRate()).isEqualByComparingTo("16709");
        assertThat(rate.closingRateDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        // 257 closes since the Forex days Yahoo sent with a close outside their range carry the ECB rate (2025-06-17
        // 16,297, 06-19 16,384, 10-31 16,662; InstrumentIngestionService.referenceBars); 16,451.41 of 256 before
        assertThat(rate.averageRate().setScale(2, RoundingMode.HALF_UP)).isEqualByComparingTo("16455.07");
        assertThat(rate.averageDays()).isEqualTo(257);
    }

    /**
     * The Valuation tab converts a row's amounts at the close of its valuation date (one rate per row: its ratios stay
     * the same in either currency): every valuation date of SMDR has the pair's last close on or before it.
     */
    @Test
    void everyValuationDateGetsTheCloseOnOrBeforeIt() {
        assumeTrue(stored("SELECT count(*) FROM company WHERE ticker = 'SMDR' AND exchange = 'IDX' AND currency = 'USD'"),
                "SMDR is not stored as a USD reporter");
        assumeTrue(stored("SELECT count(*) FROM instrument_daily WHERE instrument_code = 'USDIDR'"), "no USD/IDR closes");

        CompanyDetailResponse detail = companies.detail("IDX", "SMDR");
        assumeTrue(!detail.valuations().isEmpty(), "SMDR has no valuations");
        Map<LocalDate, CompanyDetailResponse.DateRate> rates = detail.conversion().valuationRates().stream()
                .collect(Collectors.toMap(CompanyDetailResponse.DateRate::date, Function.identity()));

        for (CompanyDetailResponse.Valuation v : detail.valuations()) {
            CompanyDetailResponse.DateRate rate = rates.get(v.valuationDate());
            assertThat(rate).as(v.valuationDate().toString()).isNotNull();
            Map<String, Object> close = jdbc.sql("""
                            SELECT close_price, trading_date FROM instrument_daily
                            WHERE instrument_code = 'USDIDR' AND trading_date <= :d
                            ORDER BY trading_date DESC LIMIT 1""")
                    .param("d", v.valuationDate()).query().singleRow();
            assertThat(rate.rate()).isEqualByComparingTo((BigDecimal) close.get("close_price"));
            assertThat(rate.rateDate()).isEqualTo(((java.sql.Date) close.get("trading_date")).toLocalDate());
        }
    }

    @Test
    void aCompanyReportingInItsExchangesCurrencyHasNoConversion() {
        assumeTrue(stored("SELECT count(*) FROM company WHERE ticker = 'BBCA' AND exchange = 'IDX' AND currency = 'IDR'"),
                "BBCA is not stored");

        assertThat(companies.detail("IDX", "BBCA").conversion()).isNull();
    }
}
