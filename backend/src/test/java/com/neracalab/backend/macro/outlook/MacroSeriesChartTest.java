package com.neracalab.backend.macro.outlook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.macro.MacroRepository.Row;
import com.neracalab.backend.macro.outlook.MacroSeriesChart.Change;
import com.neracalab.backend.macro.outlook.MacroSeriesChart.Chart;
import com.neracalab.backend.macro.outlook.MacroSeriesChart.Kind;

/** The Macro › Data chart: the window, weekly drawing, statistics, changes by kind and the latest observations. */
class MacroSeriesChartTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Row row(String date, String value) {
        return new Row(d(date), new BigDecimal(value), d(date), null);
    }

    private static Change change(Chart c, String period) {
        return c.changes().stream().filter(x -> x.period().equals(period)).findFirst().orElse(null);
    }

    @Test
    void theKindFollowsTheUnitAndTheFlowSeries() {
        assertThat(MacroSeriesChart.kind("US_10Y", "%")).isEqualTo(Kind.RATE);
        assertThat(MacroSeriesChart.kind("ID_CDS_5Y", "bps")).isEqualTo(Kind.RATE);
        assertThat(MacroSeriesChart.kind("DXY", "index")).isEqualTo(Kind.LEVEL);
        assertThat(MacroSeriesChart.kind("ID_M2", "IDR bn")).isEqualTo(Kind.LEVEL);
        assertThat(MacroSeriesChart.kind("ID_FOREIGN_NET_BUY", "IDR bn")).isEqualTo(Kind.FLOW);
    }

    @Test
    void theWindowEndsAtTheLatestObservationNotToday() {
        // a monthly series published up to January: 6M is August - January, not the empty last six months
        List<Row> rows = new ArrayList<>();
        for (int m = 1; m <= 13; m++) {
            LocalDate month = d("2025-01-01").plusMonths(m - 1);
            rows.add(new Row(month, BigDecimal.valueOf(100 + m), month.withDayOfMonth(month.lengthOfMonth()),
                    month.plusMonths(1).withDayOfMonth(5)));
        }
        Chart c = MacroSeriesChart.of(rows, Kind.LEVEL, "6m");
        assertThat(c.range()).isEqualTo("6M");
        assertThat(c.to()).isEqualTo(d("2026-01-01"));
        assertThat(c.from()).isEqualTo(d("2025-07-01"));
        assertThat(c.points()).extracting(MacroSeriesChart.Point::date).first().isEqualTo(d("2025-07-01"));
        assertThat(c.points()).hasSize(7);
        // the release date travels with the point (the tooltip)
        assertThat(c.points().getLast().released()).isEqualTo(d("2026-02-05"));
        assertThat(c.sampled()).isNull();
    }

    @Test
    void aLevelChangesInPercentAndARateInPointsOnly() {
        List<Row> rows = List.of(row("2025-11-01", "100"), row("2026-08-01", "110"), row("2026-10-01", "120"),
                row("2026-11-01", "126"));
        Chart level = MacroSeriesChart.of(rows, Kind.LEVEL, "1Y");
        // 1M: from 2026-10-01 (on or before 2026-10-01)
        assertThat(change(level, "1M").from()).isEqualTo(d("2026-10-01"));
        assertThat(change(level, "1M").change()).isEqualByComparingTo("6");
        assertThat(change(level, "1M").pct()).isEqualByComparingTo("5");
        // 3M: the last observation on or before 2026-08-01
        assertThat(change(level, "3M").change()).isEqualByComparingTo("16");
        assertThat(change(level, "1Y").pct()).isEqualByComparingTo("26");
        // YTD from the last observation of 2025
        assertThat(change(level, "YTD").from()).isEqualTo(d("2025-11-01"));
        // the series does not reach back 3 years
        assertThat(change(level, "3Y")).isNull();
        assertThat(level.stats().changePct()).isEqualByComparingTo("26");
        assertThat(level.stats().high().value()).isEqualByComparingTo("126");
        assertThat(level.stats().low().date()).isEqualTo(d("2025-11-01"));
        assertThat(level.stats().average()).isEqualByComparingTo("114");

        Chart rate = MacroSeriesChart.of(rows, Kind.RATE, "1Y");
        assertThat(change(rate, "1M").change()).isEqualByComparingTo("6");
        assertThat(change(rate, "1M").pct()).isNull();
        assertThat(rate.stats().changePct()).isNull();
        assertThat(rate.stats().change()).isEqualByComparingTo("26");
    }

    @Test
    void aFlowSumsItsPeriods() {
        List<Row> rows = List.of(row("2026-09-30", "-50"), row("2026-10-15", "100"), row("2026-10-31", "-30"),
                row("2026-11-02", "20"), row("2026-11-03", "-5"));
        Chart c = MacroSeriesChart.of(rows, Kind.FLOW, "MAX");
        // 1M: after 2026-10-03
        assertThat(change(c, "1M").change()).isEqualByComparingTo("85");
        assertThat(change(c, "1M").from()).isNull();
        assertThat(c.stats().change()).isEqualByComparingTo("35");
        assertThat(c.recent()).allSatisfy(r -> assertThat(r.change()).isNull());
        assertThat(c.from()).isNull();

        // the net over a range equals the change of the same period: the first day is not in it
        List<Row> year = List.of(row("2025-11-03", "40"), row("2025-11-04", "7"), row("2026-11-03", "-5"));
        Chart oneYear = MacroSeriesChart.of(year, Kind.FLOW, "1Y");
        assertThat(oneYear.stats().change()).isEqualByComparingTo("2");
        assertThat(change(oneYear, "1Y").change()).isEqualByComparingTo(oneYear.stats().change());
        assertThat(oneYear.points()).hasSize(2);
    }

    @Test
    void moreThanTwoThousandObservationsAreDrawnWeeklyButCountedInFull() {
        List<Row> rows = new ArrayList<>();
        LocalDate day = d("2015-01-05"); // a Monday
        int n = 0;
        while (rows.size() < 2_600) {
            if (day.getDayOfWeek().getValue() <= 5) {
                rows.add(new Row(day, BigDecimal.ONE, day, null));
                n++;
            }
            day = day.plusDays(1);
        }
        Chart level = MacroSeriesChart.of(rows, Kind.LEVEL, "MAX");
        assertThat(level.sampled()).isEqualTo("WEEKLY");
        assertThat(level.points()).hasSize(520);
        // the last observation of a week, on its Friday
        assertThat(level.points().getFirst().date()).isEqualTo(d("2015-01-09"));
        assertThat(level.stats().observations()).isEqualTo(n);

        Chart flow = MacroSeriesChart.of(rows, Kind.FLOW, "MAX");
        assertThat(flow.points().getFirst().value()).isEqualByComparingTo("5");
        assertThat(flow.stats().change()).isEqualByComparingTo(String.valueOf(n));

        // a 1Y window of the same series is drawn in full
        assertThat(MacroSeriesChart.of(rows, Kind.LEVEL, "1Y").sampled()).isNull();
    }

    @Test
    void theLatestObservationsNewestFirstWithTheirChange() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            rows.add(row(d("2026-01-01").plusDays(i).toString(), String.valueOf(i * i)));
        }
        Chart c = MacroSeriesChart.of(rows, Kind.RATE, "1Y");
        assertThat(c.recent()).hasSize(12);
        assertThat(c.recent().getFirst().date()).isEqualTo(d("2026-01-15"));
        assertThat(c.recent().getFirst().change()).isEqualByComparingTo("27"); // 196 - 169
    }

    @Test
    void anEmptySeriesAndAnUnknownRange() {
        Chart c = MacroSeriesChart.of(List.of(), Kind.LEVEL, "1Y");
        assertThat(c.points()).isEmpty();
        assertThat(c.stats()).isNull();
        assertThat(c.to()).isNull();
        assertThatThrownBy(() -> MacroSeriesChart.of(List.of(), Kind.LEVEL, "2W"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
