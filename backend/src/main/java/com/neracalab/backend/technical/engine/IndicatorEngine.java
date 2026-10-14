package com.neracalab.backend.technical.engine;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.ROCIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.num.DoubleNumFactory;
import org.ta4j.core.num.Num;

/**
 * The standard indicators of a bar series, computed with ta4j (deterministic, in Java; the browser never computes an
 * indicator). Each series is aligned with the bars; the bars before an indicator is defined (its warm-up, e.g. the first
 * 199 bars of SMA 200) are NaN rather than ta4j's partial values, so a chart or a snapshot never shows an average of
 * fewer bars than its name says.
 * <p>
 * Definitions (periods in bars of the interval):
 * <ul>
 *   <li>SMA, EMA of the close; Bollinger Bands (20, 2): SMA 20 +/- 2 population standard deviations, band width =
 *       (upper - lower) / middle in %</li>
 *   <li>RSI (14, Wilder); MACD (12, 26, 9): EMA 12 - EMA 26, signal EMA 9 of it, histogram = line - signal</li>
 *   <li>Stochastic (14, 3, 3): slow %K = SMA 3 of the fast %K (14), %D = SMA 3 of the slow %K</li>
 *   <li>ADX (14) with +DI / -DI (14), Wilder's smoothing; ATR (14), ATR% = ATR / close in %</li>
 *   <li>OBV; rate of change over n bars in %; average volume and value traded (SMA)</li>
 *   <li>Donchian channels: highest high / lowest low of the last 20 / 50 bars (the bar itself included)</li>
 *   <li>ATR bands (Keltner): EMA 20 +/- 2 x ATR 14</li>
 *   <li>12-1 month momentum: close a month ago / close a year ago - 1, in %</li>
 * </ul>
 */
public final class IndicatorEngine {

    private IndicatorEngine() {
    }

    /** Every indicator of {@code bars} (oldest first), keyed by its series name (see {@link IndicatorSet}). */
    public static IndicatorSet compute(List<Candle> bars, Interval interval) {
        int n = bars.size();
        Map<String, double[]> out = new LinkedHashMap<>();
        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] volume = new double[n];
        double[] value = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = bars.get(i).close();
            high[i] = bars.get(i).high();
            low[i] = bars.get(i).low();
            volume[i] = bars.get(i).volume();
            value[i] = bars.get(i).value();
        }
        if (n == 0) {
            return new IndicatorSet(bars, out);
        }
        BarSeries series = series(bars);
        ClosePriceIndicator c = new ClosePriceIndicator(series);

        for (int p : new int[] {20, 50, 100, 200}) {
            out.put("sma" + p, values(new SMAIndicator(c, p), n, p - 1));
        }
        for (int p : new int[] {20, 50}) {
            out.put("ema" + p, values(new EMAIndicator(c, p), n, p - 1));
        }

        SMAIndicator bbMiddle = new SMAIndicator(c, 20);
        StandardDeviationIndicator sd = StandardDeviationIndicator.ofPopulation(c, 20);
        double[] middle = values(bbMiddle, n, 19);
        double[] deviation = values(sd, n, 19);
        double[] upper = new double[n];
        double[] lower = new double[n];
        double[] width = new double[n];
        for (int i = 0; i < n; i++) {
            upper[i] = middle[i] + 2 * deviation[i];
            lower[i] = middle[i] - 2 * deviation[i];
            width[i] = middle[i] > 0 ? (upper[i] - lower[i]) / middle[i] * 100 : Double.NaN;
        }
        out.put("bb.upper", upper);
        out.put("bb.middle", middle);
        out.put("bb.lower", lower);
        out.put("bb.width", width);

        out.put("rsi14", values(new RSIIndicator(c, 14), n, 14));

        MACDIndicator macd = new MACDIndicator(c, 12, 26);
        double[] macdLine = values(macd, n, 25);
        double[] signal = values(new EMAIndicator(macd, 9), n, 33);
        double[] histogram = new double[n];
        for (int i = 0; i < n; i++) {
            histogram[i] = macdLine[i] - signal[i];
        }
        out.put("macd.line", macdLine);
        out.put("macd.signal", signal);
        out.put("macd.histogram", histogram);

        StochasticOscillatorKIndicator fastK = new StochasticOscillatorKIndicator(series, 14);
        SMAIndicator slowK = new SMAIndicator(fastK, 3);
        out.put("stoch.k", values(slowK, n, 15));
        out.put("stoch.d", values(new SMAIndicator(slowK, 3), n, 17));

        out.put("adx.adx", values(new ADXIndicator(series, 14), n, 27));
        out.put("adx.plusDi", values(new PlusDIIndicator(series, 14), n, 14));
        out.put("adx.minusDi", values(new MinusDIIndicator(series, 14), n, 14));

        double[] atr = values(new ATRIndicator(series, 14), n, 13);
        double[] atrPct = new double[n];
        for (int i = 0; i < n; i++) {
            atrPct[i] = atr[i] / close[i] * 100;
        }
        out.put("atr14", atr);
        out.put("atrPct", atrPct);

        double[] ema20 = out.get("ema20");
        double[] atrUpper = new double[n];
        double[] atrLower = new double[n];
        for (int i = 0; i < n; i++) {
            atrUpper[i] = ema20[i] + 2 * atr[i];
            atrLower[i] = ema20[i] - 2 * atr[i];
        }
        out.put("atrBands.upper", atrUpper);
        out.put("atrBands.lower", atrLower);

        out.put("obv", values(new OnBalanceVolumeIndicator(series), n, 0));
        VolumeIndicator vol = new VolumeIndicator(series);
        out.put("volSma20", values(new SMAIndicator(vol, 20), n, 19));
        out.put("volSma50", values(new SMAIndicator(vol, 50), n, 49));
        out.put("valueSma20", sma(value, 20));
        out.put("valueSma50", sma(value, 50));

        for (int p : new int[] {21, 63, 126, 252}) {
            out.put("roc" + p, values(new ROCIndicator(c, p), n, p));
        }

        for (int p : new int[] {20, 50}) {
            out.put("channel" + p + ".high", highest(high, p));
            out.put("channel" + p + ".low", lowest(low, p));
        }

        double[] momentum = new double[n];
        for (int i = 0; i < n; i++) {
            int month = i - interval.monthBars();
            int year = i - interval.yearBars();
            momentum[i] = year >= 0 ? Stats.pctChange(close[month], close[year]) : Double.NaN;
        }
        out.put("mom12_1", momentum);
        return new IndicatorSet(bars, out);
    }

    /** A ta4j bar series of the bars (double precision; dates as one-day bars ending at UTC midnight). */
    static BarSeries series(List<Candle> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("bars").withNumFactory(DoubleNumFactory.getInstance()).build();
        for (Candle bar : bars) {
            series.barBuilder()
                    .timePeriod(Duration.ofDays(1))
                    .endTime(bar.date().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
                    .openPrice(bar.open()).highPrice(bar.high()).lowPrice(bar.low()).closePrice(bar.close())
                    .volume(bar.volume()).amount(bar.value())
                    .add();
        }
        return series;
    }

    /** The values of an indicator; NaN before {@code firstIndex} and where ta4j has none. */
    static double[] values(Indicator<Num> indicator, int n, int firstIndex) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < firstIndex) {
                v[i] = Double.NaN;
                continue;
            }
            Num num = indicator.getValue(i);
            v[i] = Num.isNaNOrNull(num) ? Double.NaN : num.doubleValue();
            if (Double.isInfinite(v[i])) {
                v[i] = Double.NaN;
            }
        }
        return v;
    }

    static double[] sma(double[] values, int period) {
        double[] out = new double[values.length];
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            out[i] = i >= period - 1 ? sum / period : Double.NaN;
        }
        return out;
    }

    static double[] highest(double[] values, int period) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            if (i < period - 1) {
                out[i] = Double.NaN;
                continue;
            }
            double max = Double.NEGATIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                max = Math.max(max, values[j]);
            }
            out[i] = max;
        }
        return out;
    }

    static double[] lowest(double[] values, int period) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            if (i < period - 1) {
                out[i] = Double.NaN;
                continue;
            }
            double min = Double.POSITIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                min = Math.min(min, values[j]);
            }
            out[i] = min;
        }
        return out;
    }
}
