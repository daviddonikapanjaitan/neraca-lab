package com.neracalab.backend.macro;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Period end, release date (point in time) and "up to date" of a macro series by its frequency and publication lag. */
class MacroSeriesTest {

    static MacroSeries series(String code, String frequency, int lag) {
        return new MacroSeries(code, code, "US", "RATES", "%", frequency, "fred", code, null, lag, 1,
                "DRUCKENMILLER", new BigDecimal("-100"), new BigDecimal("1000"), BigDecimal.ONE,
                LocalDate.of(2000, 1, 1), null);
    }

    @Test
    void aMonthIsPublicItsLagAfterItsLastDay() {
        MacroSeries cpi = series("US_CPI_INDEX", "MONTHLY", 15);
        assertThat(cpi.periodEnd(LocalDate.of(2026, 9, 1))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(cpi.releaseDate(LocalDate.of(2026, 9, 1))).isEqualTo(LocalDate.of(2026, 10, 15));
        // on 14 Oct September is not out yet: August is the latest that should be stored
        assertThat(cpi.expectedLatest(LocalDate.of(2026, 10, 14))).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(cpi.expectedLatest(LocalDate.of(2026, 10, 15))).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(cpi.upToDate(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 14))).isTrue();
        assertThat(cpi.upToDate(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 10, 15))).isFalse();
        assertThat(cpi.upToDate(null, LocalDate.of(2026, 10, 15))).isFalse();
    }

    @Test
    void aDailySeriesSkipsTheWeekend() {
        MacroSeries y10 = series("US_10Y", "DAILY", 1);
        assertThat(y10.releaseDate(LocalDate.of(2026, 10, 13))).isEqualTo(LocalDate.of(2026, 10, 14));
        // Monday 19 Oct: Thursday's value is the latest that must be out (Friday's comes on Monday)
        assertThat(y10.expectedLatest(LocalDate.of(2026, 10, 19))).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(y10.expectedLatest(LocalDate.of(2026, 10, 18))).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void weeklyQuarterlyAndEventSeries() {
        MacroSeries fed = series("US_FED_ASSETS", "WEEKLY", 1);
        assertThat(fed.upToDate(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 15))).isTrue();
        assertThat(fed.upToDate(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 16))).isFalse();
        MacroSeries ca = series("ID_CURRENT_ACCOUNT", "QUARTERLY", 50);
        assertThat(ca.periodEnd(LocalDate.of(2026, 4, 1))).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(ca.expectedLatest(LocalDate.of(2026, 10, 16))).isEqualTo(LocalDate.of(2026, 4, 1));
        MacroSeries bi = series("BI_RATE", "EVENT", 0);
        assertThat(bi.upToDate(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 10, 16))).isTrue();
        assertThat(bi.upToDate(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 10, 25))).isFalse();
    }
}
