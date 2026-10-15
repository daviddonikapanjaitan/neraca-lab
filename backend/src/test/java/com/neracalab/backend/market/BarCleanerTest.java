package com.neracalab.backend.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.engine.Candle;

/**
 * The cleaning of the Yahoo Finance currency / futures bars, on the bad prints found in the real USD/IDR and USD/JPY
 * histories: a close that jumps away and straight back is dropped (one to three days), a real move is kept, an
 * impossible wick is cut back.
 */
class BarCleanerTest {

    private static List<Candle> closes(double... closes) {
        List<Candle> bars = new ArrayList<>();
        LocalDate d = LocalDate.of(2013, 11, 4);
        for (double c : closes) {
            bars.add(Candle.daily(d, c, c * 1.002, c * 0.998, c, c, 0));
            d = d.plusDays(1);
        }
        return bars;
    }

    private static List<Double> closeValues(List<Candle> bars) {
        return bars.stream().map(Candle::close).toList();
    }

    @Test
    void aDecimalShiftOfOneDayIsDropped() {
        // USD/IDR 2010-11-01: 888.11 between 8,923.80 and 8,906.70
        BarCleaner.Cleaned cleaned = BarCleaner.clean(closes(8910, 8923.8, 888.11, 8906.7, 8915), 0.03);
        assertThat(closeValues(cleaned.bars())).containsExactly(8910.0, 8923.8, 8906.7, 8915.0);
        assertThat(cleaned.dropped()).containsExactly(LocalDate.of(2013, 11, 6));
    }

    @Test
    void aStaleValueOverThreeDaysIsDropped() {
        // USD/IDR 2013-11-29 .. 2013-12-03: 9,612.45 three times between 11,880 and 11,885
        BarCleaner.Cleaned cleaned = BarCleaner.clean(closes(11750, 11880, 9612.45, 9612.45, 9612.45, 11885, 11972), 0.03);
        assertThat(closeValues(cleaned.bars())).containsExactly(11750.0, 11880.0, 11885.0, 11972.0);
        assertThat(cleaned.dropped()).hasSize(3);
    }

    @Test
    void repeatedSpikesAreAllDropped() {
        BarCleaner.Cleaned cleaned = BarCleaner.clean(closes(101.46, 110.09, 99.51, 100.2, 92.82, 109.24, 92.3), 0.05);
        assertThat(closeValues(cleaned.bars())).containsExactly(101.46, 99.51, 100.2, 92.82, 92.3);
    }

    @Test
    void aRealMoveThatDoesNotComeBackIsKept() {
        // a level shift of 8 %: the close after the jump stays at the new level
        List<Candle> bars = closes(10900, 10950, 11850, 11900, 11870);
        BarCleaner.Cleaned cleaned = BarCleaner.clean(bars, 0.03);
        assertThat(cleaned.dropped()).isEmpty();
        assertThat(closeValues(cleaned.bars())).containsExactlyElementsOf(closeValues(bars));
    }

    @Test
    void aSmallerSwingThanTheThresholdIsKept() {
        BarCleaner.Cleaned cleaned = BarCleaner.clean(closes(100, 102.5, 100.1), 0.03);
        assertThat(cleaned.dropped()).isEmpty();
    }

    @Test
    void theFirstAndLastBarAreNeverJudged() {
        // no neighbour on one side: the next run re-reads the last days and judges the last bar then
        assertThat(BarCleaner.clean(closes(888.11, 8923.8, 8906.7, 8915), 0.03).dropped()).isEmpty();
        assertThat(BarCleaner.clean(closes(8910, 8923.8, 8906.7, 888.0), 0.03).dropped()).isEmpty();
    }

    @Test
    void anImpossibleWickIsCutBackToTheBody() {
        // USD/IDR 2024-12-25: low 14,210.90 with open 15,970.80 and close 15,970.80
        List<Candle> bars = List.of(
                Candle.daily(LocalDate.of(2024, 12, 24), 16217.6, 16220, 16135.4, 16217.6, 16217.6, 0),
                Candle.daily(LocalDate.of(2024, 12, 25), 15970.8, 16243, 14210.9, 15970.8, 15970.8, 0));
        BarCleaner.Cleaned cleaned = BarCleaner.clean(bars, 0.03);
        Candle fixed = cleaned.bars().get(1);
        assertThat(fixed.low()).isEqualTo(15970.8);
        assertThat(fixed.high()).isEqualTo(16243.0);   // 1.7 % above the body: kept
        assertThat(cleaned.wicksCut()).isEqualTo(1);
    }

    @Test
    void anOpenFarFromTheCloseAndThePreviousCloseBecomesTheClose() {
        List<Candle> bars = List.of(
                Candle.daily(LocalDate.of(2010, 10, 29), 8920, 8930, 8910, 8923.8, 8923.8, 0),
                Candle.daily(LocalDate.of(2010, 11, 2), 891.9, 8932.9, 885.78, 8906.7, 8906.7, 0));
        Candle fixed = BarCleaner.clean(bars, 0.03).bars().get(1);
        assertThat(fixed.open()).isEqualTo(8906.7);
        assertThat(fixed.low()).isEqualTo(8906.7);
        assertThat(fixed.high()).isEqualTo(8932.9);
    }
}
