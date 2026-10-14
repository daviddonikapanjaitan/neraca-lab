package com.neracalab.backend.technical.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalTestData;
import com.neracalab.backend.technical.idx.IdxTickSize;

/** Pivots, zones, the volume profile, retracements, bases and extremes on hand-made series. */
class StructureTest {

    private final TechnicalProperties properties = TechnicalTestData.properties();
    private final IdxTickSize ticks = new IdxTickSize(properties.tickSizes());
    private final Structure structure = new Structure(properties, ticks);

    /** Bars from closes: high = close + 5, low = close - 5, open = previous close. */
    private static List<Candle> bars(double... closes) {
        List<Candle> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < closes.length; i++) {
            double open = i == 0 ? closes[0] : closes[i - 1];
            out.add(Candle.daily(d.plusDays(i), open, Math.max(open, closes[i]) + 5, Math.min(open, closes[i]) - 5, closes[i],
                    closes[i], 1000));
        }
        return out;
    }

    @Test
    void fiveBarFractals() {
        List<Candle> b = bars(100, 110, 120, 110, 100, 90, 80, 90, 100, 110, 100);
        List<Structure.Pivot> pivots = structure.pivots(b);
        assertThat(pivots).extracting(Structure.Pivot::index, Structure.Pivot::high)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, true), org.assertj.core.groups.Tuple.tuple(6, false));
        assertThat(pivots.getFirst().price()).isEqualTo(125);   // the bar's high
        assertThat(pivots.get(1).price()).isEqualTo(75);        // the bar's low
    }

    @Test
    void theLastBarsCannotBeConfirmed() {
        List<Candle> b = bars(100, 110, 120, 130, 140);
        assertThat(structure.pivots(b)).isEmpty();
    }

    @Test
    void swingPointsWithinTheToleranceFormOneZone() {
        // swing highs at about 1,000, 1,010 and 1,015 (within 2 %), lows at 900 and 905
        List<Candle> b = bars(950, 970, 995, 970, 950, 930, 895, 930, 960, 985, 1005, 980, 950, 920, 900, 920, 960, 990,
                1010, 990, 960, 955, 958);
        List<Structure.Pivot> pivots = structure.pivots(b);
        List<Structure.Zone> zones = structure.zones(b, pivots);
        assertThat(zones).hasSize(2);
        Structure.Zone top = zones.getFirst();
        assertThat(top.type()).isEqualTo("RESISTANCE");
        assertThat(top.touches()).isEqualTo(3);
        assertThat(top.low()).isLessThanOrEqualTo(1000);
        assertThat(top.high()).isGreaterThanOrEqualTo(1015);
        Structure.Zone bottom = zones.get(1);
        assertThat(bottom.type()).isEqualTo("SUPPORT");
        assertThat(bottom.touches()).isEqualTo(2);
        for (Structure.Zone z : zones) {
            assertThat(ticks.isValid(BigDecimal.valueOf(z.low()))).isTrue();
            assertThat(ticks.isValid(BigDecimal.valueOf(z.high()))).isTrue();
            assertThat(ticks.isValid(BigDecimal.valueOf(z.mid()))).isTrue();
            assertThat(z.low()).isLessThanOrEqualTo(z.mid());
            assertThat(z.mid()).isLessThanOrEqualTo(z.high());
        }
    }

    @Test
    void volumeProfileSpreadsEachBarOverItsRange() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        List<Candle> b = List.of(
                Candle.daily(d, 100, 124, 100, 110, 110, 2400),           // spans the whole range: 100 per bucket
                Candle.daily(d.plusDays(1), 102, 103, 101, 102, 102, 500), // inside the first bucket (100 .. 101 .. 102 ..)
                Candle.daily(d.plusDays(2), 120, 120, 120, 120, 120, 300)); // no range: all at its close
        Structure.VolumeProfile p = structure.volumeProfile(b);
        assertThat(p.buckets()).hasSize(24);
        double total = p.buckets().stream().mapToDouble(Structure.ProfileBucket::volume).sum();
        assertThat(total).isCloseTo(3200, within(1e-6));
        assertThat(p.buckets().getFirst().volume()).isCloseTo(100, within(1e-6));      // 100 .. 101
        assertThat(p.buckets().get(1).volume()).isCloseTo(100 + 250, within(1e-6));    // 101 .. 102
        assertThat(p.buckets().get(2).volume()).isCloseTo(100 + 250, within(1e-6));    // 102 .. 103
        assertThat(p.buckets().get(20).volume()).isCloseTo(100 + 300, within(1e-6));   // 120 .. 121
        assertThat(p.topNodes()).hasSize(3);
        assertThat(p.topNodes().getFirst().volume()).isCloseTo(400, within(1e-6));
        assertThat(p.topNodes().getFirst().price()).isEqualTo(121);                     // middle 120.5, rounded half up
    }

    @Test
    void retracementOfTheLastMajorSwing() {
        // a rise from 1,000 to 2,000 (the low and high bars at 995 / 2,005), then a pullback to 1,700: with ATR 150 a
        // major swing spans 450, so the pullback (310) is not one and the rise is the last major swing
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            closes.add(1000.0 + i * 50);
        }
        for (int i = 1; i <= 6; i++) {
            closes.add(2000.0 - i * 50);
        }
        List<Candle> b = bars(closes.stream().mapToDouble(Double::doubleValue).toArray());
        Structure.Retracement r = structure.retracement(b, 150);    // threshold 3 x 150 = 450
        assertThat(r).isNotNull();
        assertThat(r.direction()).isEqualTo("UP");
        assertThat(r.start().price()).isEqualTo(995);
        assertThat(r.end().price()).isEqualTo(2010);                // 2,005 rounded to the 10-step (half up)
        double amplitude = 2005 - 995;
        assertThat(r.levels()).extracting(Structure.Level::ratio).containsExactly(0.33, 0.382, 0.5, 0.618, 0.66);
        assertThat(r.levels().get(2).price()).isEqualTo(ticks.round(2005 - 0.5 * amplitude));   // 1,500
        assertThat(r.levels().get(3).price()).isEqualTo(ticks.round(2005 - 0.618 * amplitude));
        for (Structure.Level l : r.levels()) {
            assertThat(ticks.isValid(BigDecimal.valueOf(l.price()))).isTrue();
        }
    }

    @Test
    void aPullbackLargeEnoughIsItselfTheLastMajorSwing() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            closes.add(1000.0 + i * 50);
        }
        for (int i = 1; i <= 6; i++) {
            closes.add(2000.0 - i * 50);
        }
        Structure.Retracement r = structure.retracement(bars(closes.stream().mapToDouble(Double::doubleValue).toArray()), 40);
        assertThat(r.direction()).isEqualTo("DOWN");
        assertThat(r.start().price()).isEqualTo(2010);              // 2,005 rounded
        assertThat(r.end().price()).isEqualTo(1695);
        // retracements of a fall are measured up from its low
        assertThat(r.levels().get(2).price()).isEqualTo(ticks.round(1695 + 0.5 * (2005 - 1695)));
    }

    @Test
    void noSwingWithoutEnoughMovement() {
        List<Candle> b = bars(1000, 1001, 1002, 1001, 1000, 1002);
        assertThat(structure.retracement(b, 40)).isNull();
    }

    @Test
    void baseIsATightRangeWithContractingVolatility() {
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            closes.add(500.0 + i * 8);                               // a run-up to about 980
        }
        for (int i = 0; i < 90; i++) {
            closes.add(1000 + (i % 2 == 0 ? 1 : -1) * Math.max(4, 40 - i * 0.4));   // a range that narrows
        }
        List<Candle> b = bars(closes.stream().mapToDouble(Double::doubleValue).toArray());
        double[] atrPct = IndicatorEngine.compute(b, Interval.D).get("atrPct");
        Structure.Base base = structure.base(b, atrPct);
        assertThat(base).isNotNull();
        assertThat(base.days()).isBetween(60, 120);
        assertThat(base.depthPct()).isLessThanOrEqualTo(25);
        assertThat(base.atrPctEnd()).isLessThan(base.atrPctStart() * 0.9);
        assertThat(ticks.isValid(BigDecimal.valueOf(base.high()))).isTrue();
        // a steady trend is no base: every window of 60 .. 120 bars is deeper than 25 %
        double[] trend = new double[130];
        for (int i = 0; i < trend.length; i++) {
            trend[i] = 500 + i * 10;
        }
        List<Candle> t = bars(trend);
        assertThat(structure.base(t, IndicatorEngine.compute(t, Interval.D).get("atrPct"))).isNull();
    }

    @Test
    void extremesOfTheLastYearAndAllHistory() {
        List<Candle> b = TechnicalTestData.synthetic(600);
        Structure.Extremes e = structure.extremes(b);
        double high52 = b.subList(600 - 252, 600).stream().mapToDouble(Candle::high).max().orElseThrow();
        double ath = b.stream().mapToDouble(Candle::high).max().orElseThrow();
        assertThat(e.week52High()).isEqualTo(ticks.round(high52));
        assertThat(e.allTimeHigh()).isEqualTo(ticks.round(ath));
        assertThat(e.drawdownPct()).isLessThanOrEqualTo(0.5);
    }
}
