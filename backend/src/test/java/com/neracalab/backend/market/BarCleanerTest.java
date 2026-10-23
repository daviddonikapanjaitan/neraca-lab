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

    // ------------------------------------------------------------------ the pairs added on 2026-10-23 (V1.0.26)

    /** The real Yahoo prints each pair's max_spike was set on: the bad ones dropped, the real days kept. */
    @Test
    void theBadPrintsOfTheAsianPeersAreDroppedAndTheirRealDaysKept() {
        // USD/CNY 2011-07-18 (2 %): 5.863 between 6.4338 and 6.4688
        assertThat(closeValues(BarCleaner.clean(closes(6.45, 6.4338, 5.863, 6.4688, 6.47), 0.02).bars()))
                .containsExactly(6.45, 6.4338, 6.4688, 6.47);
        // USD/INR 2012-01-27 and -31 (3.5 %): a stale 52.493 twice, the real 49.388 between them kept; at 2 % the
        // cleaner would have dropped 49.388 instead
        List<Candle> inr = closes(49.9, 50.0, 52.493, 49.388, 52.493, 49.5, 49.6);
        assertThat(closeValues(BarCleaner.clean(inr, 0.035).bars())).containsExactly(49.9, 50.0, 49.388, 49.5, 49.6);
        assertThat(closeValues(BarCleaner.clean(inr, 0.02).bars())).doesNotContain(49.388);
        // USD/PHP (2.7 %): 2016-11-17's 43.703 between 49.038 and 49.46 dropped; 2012-01-30's 42.619 between two
        // stale 43.714 kept
        assertThat(closeValues(BarCleaner.clean(closes(48.9, 49.038, 43.703, 49.46, 49.5), 0.027).bars()))
                .containsExactly(48.9, 49.038, 49.46, 49.5);
        assertThat(BarCleaner.clean(closes(43.6, 43.714, 42.619, 43.714, 43.5), 0.027).dropped()).isEmpty();
        // USD/MYR 2006-06-05 (4 %): 3.4609 between 3.6375 and 3.634
        assertThat(closeValues(BarCleaner.clean(closes(3.64, 3.6375, 3.4609, 3.634, 3.63), 0.04).bars()))
                .containsExactly(3.64, 3.6375, 3.634, 3.63);
        // USD/KRW 2008-09-16 (6 %): the real 1,160 of Lehman week comes back 5.6 % the next day and is kept; at 4.5 %
        // it would be dropped
        List<Candle> krw = closes(1090, 1098.8, 1160, 1099.6, 1105);
        assertThat(BarCleaner.clean(krw, 0.06).dropped()).isEmpty();
        assertThat(BarCleaner.clean(krw, 0.045).dropped()).hasSize(1);
    }

    @Test
    void theDollarsBadPrintsAreDroppedAndTheCarryTradeCrashKept() {
        // EUR/USD 2008-12-08 (4 %): 1.4918 between 1.2717 and 1.2926 dropped; 2009-02-20's real 1.2933 kept
        assertThat(closeValues(BarCleaner.clean(closes(1.27, 1.2717, 1.4918, 1.2926, 1.28), 0.04).bars()))
                .containsExactly(1.27, 1.2717, 1.2926, 1.28);
        assertThat(BarCleaner.clean(closes(1.27, 1.2659, 1.2933, 1.2678, 1.26), 0.04).dropped()).isEmpty();
        // AUD/JPY October 2008 (10 %): the carry-trade crash and the rescue rally of 13-14 October are real; at 9 %
        // the rally would be dropped
        List<Candle> audJpy = closes(81.858, 73.45, 72.06, 66.44, 67.943, 64.753, 72.03, 71.442, 65.384, 69.29);
        assertThat(BarCleaner.clean(audJpy, 0.10).dropped()).isEmpty();
        assertThat(closeValues(BarCleaner.clean(audJpy, 0.09).bars())).doesNotContain(72.03, 71.442);
    }
}
