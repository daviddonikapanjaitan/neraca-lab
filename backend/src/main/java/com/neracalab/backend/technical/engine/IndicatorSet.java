package com.neracalab.backend.technical.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The indicators of a bar series: each series has one value per bar (NaN where undefined). Names are the keys of the
 * {@code /indicators} API: {@code sma20 sma50 sma100 sma200 ema20 ema50 bb.upper bb.middle bb.lower bb.width rsi14
 * macd.line macd.signal macd.histogram stoch.k stoch.d adx.adx adx.plusDi adx.minusDi atr14 atrPct atrBands.upper
 * atrBands.lower obv volSma20 volSma50 valueSma20 valueSma50 roc21 roc63 roc126 roc252 channel20.high channel20.low
 * channel50.high channel50.low mom12_1}.
 */
public record IndicatorSet(List<Candle> bars, Map<String, double[]> series) {

    public IndicatorSet {
        series = Map.copyOf(series);
    }

    public double[] get(String name) {
        double[] values = series.get(name);
        if (values == null) {
            throw new IllegalArgumentException("Unknown indicator '" + name + "'");
        }
        return values;
    }

    public Set<String> names() {
        return series.keySet();
    }

    /** Value of {@code name} at the last bar (NaN when undefined or no bars). */
    public double last(String name) {
        double[] v = get(name);
        return v.length == 0 ? Double.NaN : v[v.length - 1];
    }

    /** Value of {@code name} {@code barsAgo} bars before the last one. */
    public double ago(String name, int barsAgo) {
        double[] v = get(name);
        int i = v.length - 1 - barsAgo;
        return i < 0 ? Double.NaN : v[i];
    }
}
