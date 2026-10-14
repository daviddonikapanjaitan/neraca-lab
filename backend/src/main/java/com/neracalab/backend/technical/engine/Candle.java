package com.neracalab.backend.technical.engine;

import java.time.LocalDate;

/**
 * One bar of a listing or index: daily, or a week / month aggregated from daily bars ({@link Aggregation}).
 * <p>
 * Prices are split-adjusted, as traded since the last split ({@code price_daily.close_price}; Yahoo Finance's close is
 * split-adjusted) and in the exchange currency. {@code adjClose} is also adjusted for cash dividends (total-return
 * basis); a chart may show the bars on that basis ({@link #onTotalReturnBasis()}), the levels and the snapshot never
 * do: they must be prices that can be traded.
 *
 * @param date   the trading day, or the first trading day of the week / month
 * @param volume shares traded (split-adjusted like the prices)
 * @param value  value traded: close x volume of each day (approximate - the exchange's own value is not stored)
 */
public record Candle(LocalDate date, double open, double high, double low, double close, double adjClose, double volume,
                     double value) {

    public Candle {
        if (!(close > 0)) {
            throw new IllegalArgumentException("a candle needs a positive close: " + date);
        }
    }

    /** A daily bar; value traded = close x volume. */
    public static Candle daily(LocalDate date, double open, double high, double low, double close, double adjClose,
                               double volume) {
        return new Candle(date, open, high, low, close, adjClose, volume, close * volume);
    }

    /** The bar with open, high, low and close scaled by adjClose / close (dividends reinvested). */
    public Candle onTotalReturnBasis() {
        double factor = adjClose > 0 ? adjClose / close : 1;
        return new Candle(date, open * factor, high * factor, low * factor, close * factor, adjClose, volume, value);
    }

    public boolean up(Candle previous) {
        return previous != null && close > previous.close;
    }

    public boolean down(Candle previous) {
        return previous != null && close < previous.close;
    }
}
