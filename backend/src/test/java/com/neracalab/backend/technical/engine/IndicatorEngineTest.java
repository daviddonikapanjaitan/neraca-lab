package com.neracalab.backend.technical.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.TechnicalTestData;

/**
 * The ta4j-backed indicators against independent reference implementations of the textbook formulas (written here,
 * not with ta4j), on a 400-bar synthetic series; recursive indicators (EMA, RSI, ATR, ADX) are compared after their
 * start-up effect has died out.
 */
class IndicatorEngineTest {

    private final List<Candle> bars = TechnicalTestData.synthetic(400);
    private final IndicatorSet ind = IndicatorEngine.compute(bars, Interval.D);
    private final double[] close = bars.stream().mapToDouble(Candle::close).toArray();

    @Test
    void simpleMovingAveragesAndWarmUp() {
        double[] sma50 = ind.get("sma50");
        assertThat(sma50[48]).isNaN();
        assertThat(sma50[49]).isCloseTo(mean(close, 0, 49), within(1e-9));
        assertThat(sma50[399]).isCloseTo(mean(close, 350, 399), within(1e-9));
        assertThat(ind.get("sma200")[198]).isNaN();
        assertThat(ind.last("sma200")).isCloseTo(mean(close, 200, 399), within(1e-9));
    }

    @Test
    void exponentialMovingAverage() {
        // EMA seeded with the first close, alpha = 2 / (n + 1)
        double alpha = 2.0 / 21;
        double ema = close[0];
        for (int i = 1; i < close.length; i++) {
            ema = alpha * close[i] + (1 - alpha) * ema;
        }
        assertThat(ind.last("ema20")).isCloseTo(ema, within(1e-6));
        assertThat(ind.get("ema20")[18]).isNaN();
    }

    @Test
    void rsiWilder() {
        // Wilder's RSI (14): averages of gains and losses smoothed with alpha 1 / 14, seeded with the first 14 changes
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= 14; i++) {
            double d = close[i] - close[i - 1];
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        gain /= 14;
        loss /= 14;
        for (int i = 15; i < close.length; i++) {
            double d = close[i] - close[i - 1];
            gain = (gain * 13 + Math.max(d, 0)) / 14;
            loss = (loss * 13 + Math.max(-d, 0)) / 14;
        }
        double rsi = 100 - 100 / (1 + gain / loss);
        assertThat(ind.last("rsi14")).isCloseTo(rsi, within(1e-3));
        assertThat(ind.last("rsi14")).isBetween(0.0, 100.0);
    }

    @Test
    void macdLineSignalHistogram() {
        double[] e12 = ema(close, 12);
        double[] e26 = ema(close, 26);
        double[] line = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            line[i] = e12[i] - e26[i];
        }
        double[] signal = ema(line, 9);
        int last = close.length - 1;
        assertThat(ind.last("macd.line")).isCloseTo(line[last], within(1e-6));
        assertThat(ind.last("macd.signal")).isCloseTo(signal[last], within(1e-6));
        assertThat(ind.last("macd.histogram")).isCloseTo(line[last] - signal[last], within(1e-6));
    }

    @Test
    void bollingerBandsWithPopulationStandardDeviation() {
        int last = close.length - 1;
        double m = mean(close, last - 19, last);
        double var = 0;
        for (int i = last - 19; i <= last; i++) {
            var += (close[i] - m) * (close[i] - m);
        }
        double sd = Math.sqrt(var / 20);
        assertThat(ind.last("bb.middle")).isCloseTo(m, within(1e-9));
        assertThat(ind.last("bb.upper")).isCloseTo(m + 2 * sd, within(1e-6));
        assertThat(ind.last("bb.lower")).isCloseTo(m - 2 * sd, within(1e-6));
        assertThat(ind.last("bb.width")).isCloseTo(4 * sd / m * 100, within(1e-6));
    }

    @Test
    void atrWilderAndAtrPercent() {
        double atr = 0;
        for (int i = 0; i < bars.size(); i++) {
            Candle b = bars.get(i);
            double tr = i == 0 ? b.high() - b.low() : Math.max(b.high() - b.low(), Math.max(Math.abs(b.high() - bars.get(i - 1).close()),
                    Math.abs(b.low() - bars.get(i - 1).close())));
            atr = i == 0 ? tr : (atr * 13 + tr) / 14;
        }
        assertThat(ind.last("atr14")).isCloseTo(atr, within(1e-6));
        assertThat(ind.last("atrPct")).isCloseTo(atr / close[close.length - 1] * 100, within(1e-6));
    }

    @Test
    void stochasticSlow14_3_3() {
        double[] fastK = new double[close.length];
        for (int i = 13; i < close.length; i++) {
            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            for (int j = i - 13; j <= i; j++) {
                hh = Math.max(hh, bars.get(j).high());
                ll = Math.min(ll, bars.get(j).low());
            }
            fastK[i] = (close[i] - ll) / (hh - ll) * 100;
        }
        int last = close.length - 1;
        double slowK = (fastK[last] + fastK[last - 1] + fastK[last - 2]) / 3;
        double slowKPrev = (fastK[last - 1] + fastK[last - 2] + fastK[last - 3]) / 3;
        double slowKPrev2 = (fastK[last - 2] + fastK[last - 3] + fastK[last - 4]) / 3;
        assertThat(ind.last("stoch.k")).isCloseTo(slowK, within(1e-6));
        assertThat(ind.last("stoch.d")).isCloseTo((slowK + slowKPrev + slowKPrev2) / 3, within(1e-6));
    }

    @Test
    void onBalanceVolumeRateOfChangeChannelsAndMomentum() {
        double obv = 0;
        for (int i = 1; i < bars.size(); i++) {
            obv += close[i] > close[i - 1] ? bars.get(i).volume() : close[i] < close[i - 1] ? -bars.get(i).volume() : 0;
        }
        int last = close.length - 1;
        assertThat(ind.last("obv")).isCloseTo(obv, within(1e-3));
        assertThat(ind.last("roc21")).isCloseTo((close[last] / close[last - 21] - 1) * 100, within(1e-9));
        assertThat(ind.last("roc252")).isCloseTo((close[last] / close[last - 252] - 1) * 100, within(1e-9));
        assertThat(ind.get("roc252")[251]).isNaN();
        double hh = bars.subList(last - 19, last + 1).stream().mapToDouble(Candle::high).max().orElseThrow();
        assertThat(ind.last("channel20.high")).isEqualTo(hh);
        assertThat(ind.last("mom12_1")).isCloseTo((close[last - 21] / close[last - 252] - 1) * 100, within(1e-9));
        assertThat(ind.last("volSma50")).isCloseTo(bars.subList(last - 49, last + 1).stream().mapToDouble(Candle::volume)
                .average().orElseThrow(), within(1e-6));
    }

    @Test
    void adxAndDirectionalIndicatorsAreBounded() {
        assertThat(ind.get("adx.adx")[26]).isNaN();
        assertThat(ind.last("adx.adx")).isBetween(0.0, 100.0);
        assertThat(ind.last("adx.plusDi")).isBetween(0.0, 100.0);
        assertThat(ind.last("adx.minusDi")).isBetween(0.0, 100.0);
    }

    @Test
    void weeklyAndMonthlyAggregation() {
        List<Candle> daily = List.of(
                Candle.daily(LocalDate.of(2026, 9, 28), 100, 110, 95, 105, 104, 10),   // Monday, ISO week 40
                Candle.daily(LocalDate.of(2026, 9, 30), 105, 120, 100, 118, 117, 20),
                Candle.daily(LocalDate.of(2026, 10, 2), 118, 119, 90, 92, 91, 30),     // Friday, same week
                Candle.daily(LocalDate.of(2026, 10, 5), 92, 99, 91, 98, 97, 40));      // next week
        List<Candle> weekly = Aggregation.aggregate(daily, Interval.W);
        assertThat(weekly).hasSize(2);
        Candle w = weekly.getFirst();
        assertThat(w.date()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(w.open()).isEqualTo(100);
        assertThat(w.high()).isEqualTo(120);
        assertThat(w.low()).isEqualTo(90);
        assertThat(w.close()).isEqualTo(92);
        assertThat(w.adjClose()).isEqualTo(91);
        assertThat(w.volume()).isEqualTo(60);
        assertThat(w.value()).isEqualTo(105 * 10 + 118 * 20 + 92 * 30);
        List<Candle> monthly = Aggregation.aggregate(daily, Interval.M);
        assertThat(monthly).extracting(Candle::date).containsExactly(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 2));
        assertThat(monthly.get(0).close()).isEqualTo(118);
        assertThat(monthly.get(1).open()).isEqualTo(118);
        assertThat(monthly.get(1).high()).isEqualTo(119);
        assertThat(monthly.get(1).low()).isEqualTo(90);
        assertThat(monthly.get(1).close()).isEqualTo(98);
        assertThat(Aggregation.aggregate(daily, Interval.D)).isSameAs(daily);
    }

    @Test
    void anIsoWeekAcrossTheYearEndIsOneBar() {
        List<Candle> daily = List.of(
                Candle.daily(LocalDate.of(2025, 12, 29), 10, 11, 9, 10, 10, 1),   // ISO week 2026-W01
                Candle.daily(LocalDate.of(2026, 1, 2), 10, 12, 9, 11, 11, 1));
        assertThat(Aggregation.aggregate(daily, Interval.W)).hasSize(1);
        assertThat(Aggregation.aggregate(daily, Interval.M)).hasSize(2);
    }

    private static double mean(double[] v, int from, int to) {
        double s = 0;
        for (int i = from; i <= to; i++) {
            s += v[i];
        }
        return s / (to - from + 1);
    }

    private static double[] ema(double[] v, int n) {
        double alpha = 2.0 / (n + 1);
        double[] out = new double[v.length];
        out[0] = v[0];
        for (int i = 1; i < v.length; i++) {
            out[i] = alpha * v[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }
}
