package com.neracalab.backend.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.price.provider.DailyBar;

/** Cleaning rules, re-adjustment comparison and the last completed trading day. */
class PriceIngestionRulesTest {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 10, 2);

    private final PriceIngestionService service =
            new PriceIngestionService(null, null, null, null, TestPriceProperties.defaults());

    @Test
    void keepsNormalBars() {
        DailyBar bar = bar("2026-10-01", "2230", "2300", "2210", "2280", 11986000L);
        assertThat(PriceIngestionService.clean(List.of(bar), FROM, LAST)).containsExactly(bar);
    }

    @Test
    void dropsHolidayPlaceholdersAndZeroVolumeRowsRepeatingThePreviousClose() {
        DailyBar placeholder = new DailyBar(LocalDate.of(2026, 9, 2), null, null, null, null, null, null);
        DailyBar flatZeroVolume = bar("2026-09-03", "2280", "2280", "2280", "2280", 0L);
        assertThat(PriceIngestionService.clean(List.of(placeholder, flatZeroVolume), FROM, LAST)).isEmpty();
    }

    @Test
    void keepsAZeroVolumeRowWhosePricesMove() {
        DailyBar moved = bar("2026-09-03", "2280", "2300", "2270", "2290", 0L);
        assertThat(PriceIngestionService.clean(List.of(moved), FROM, LAST)).containsExactly(moved);
    }

    @Test
    void dropsBarsOutsideTheRangeIncludingTodaysUnfinishedBar() {
        DailyBar before = bar("2026-08-31", "1", "1", "1", "1", 5L);
        DailyBar unfinished = bar("2026-10-03", "2190", "2240", "2160", "2190", 4309100L);
        assertThat(PriceIngestionService.clean(List.of(before, unfinished), FROM, LAST)).isEmpty();
    }

    @Test
    void dropsRowsThePriceDailyChecksWouldReject() {
        DailyBar zeroClose = bar("2026-09-04", "1", "1", "1", "0", 5L);
        DailyBar highBelowLow = bar("2026-09-07", "10", "9", "11", "10", 5L);
        DailyBar negativeVolume = bar("2026-09-08", "10", "11", "9", "10", -1L);
        assertThat(PriceIngestionService.clean(List.of(zeroClose, highBelowLow, negativeVolume), FROM, LAST))
                .isEmpty();
    }

    @Test
    void keepsOneBarPerDateTheLastOneWinsAndSortsByDate() {
        DailyBar later = bar("2026-09-10", "10", "11", "9", "10", 5L);
        DailyBar first = bar("2026-09-09", "10", "11", "9", "10", 5L);
        DailyBar replaced = bar("2026-09-09", "10", "12", "9", "11", 6L);
        assertThat(PriceIngestionService.clean(List.of(later, first, replaced), FROM, LAST))
                .containsExactly(replaced, later);
    }

    @Test
    void comparesPricesNumerically() {
        assertThat(PriceIngestionService.differs(new BigDecimal("2280.0000"), new BigDecimal("2280.00000000"))).isFalse();
        assertThat(PriceIngestionService.differs(new BigDecimal("2270.0000"), new BigDecimal("2280"))).isTrue();
        assertThat(PriceIngestionService.differs(null, new BigDecimal("2280"))).isFalse();
        assertThat(PriceIngestionService.differs(new BigDecimal("2280"), null)).isTrue();
    }

    @Test
    void lastCompletedTradingDayFollowsTheCutoffAndSkipsWeekends() {
        // 2026-10-02 is a Friday
        assertThat(lastCompleted("2026-10-02T16:59")).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(lastCompleted("2026-10-02T17:00")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(lastCompleted("2026-10-03T10:00")).isEqualTo(LocalDate.of(2026, 10, 2));   // Saturday
        assertThat(lastCompleted("2026-10-04T20:00")).isEqualTo(LocalDate.of(2026, 10, 2));   // Sunday
        assertThat(lastCompleted("2026-10-05T09:30")).isEqualTo(LocalDate.of(2026, 10, 2));   // Monday, before the close
        assertThat(lastCompleted("2026-10-05T17:30")).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    private LocalDate lastCompleted(String jakartaLocalDateTime) {
        return service.lastCompletedTradingDay(ZonedDateTime.of(LocalDateTime.parse(jakartaLocalDateTime), JAKARTA));
    }

    private static DailyBar bar(String date, String open, String high, String low, String close, Long volume) {
        return new DailyBar(LocalDate.parse(date), new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), new BigDecimal(close), volume);
    }
}
