package com.neracalab.backend.price.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * The close of a session Yahoo's daily chart has not written yet. Some hours after the IDX close Yahoo can still send
 * the day's daily bar with open, high, low and volume but {@code close = null} (2026-11-02: every IDX listing and
 * ^JKSE until the next morning), while {@code meta.regularMarketPrice} already holds the session's last price
 * ({@code regularMarketTime} 16:14 WIB, after the post-trading session that trades at the closing price). Without it
 * the day was dropped like a holiday placeholder and every listing stayed behind.
 * <p>
 * The meta describes the latest session only, so it is used for the last bar of a response only, and only when it
 * is proven to be that bar's session: {@code regularMarketTime} falls on the bar's date in the exchange time zone, the
 * price is positive and lies within the bar's high / low and the meta's day high / low (when given). Callers keep
 * dropping bars after the last completed trading day (before the session close cutoff), so an unfinished session is
 * never stored; when Yahoo later writes a different close, the next ingestion's overlap check re-fetches the history
 * and corrects the stored day.
 */
public final class YahooSessionClose {

    /**
     * @param price   the session's last price ({@code regularMarketPrice})
     * @param dayHigh {@code regularMarketDayHigh} ({@code null}: not given)
     * @param dayLow  {@code regularMarketDayLow} ({@code null}: not given)
     * @param volume  {@code regularMarketVolume} ({@code null}: not given)
     */
    public record Close(BigDecimal price, BigDecimal dayHigh, BigDecimal dayLow, Long volume) {
    }

    /** Decimals of every price returned, the scale the Yahoo bars are rounded to (2033.51953125 -> 2033.5195). */
    public static final int PRICE_SCALE = 4;

    private YahooSessionClose() {
    }

    /**
     * The meta's close of the session on {@code barDate}, or empty when the meta is not proven to describe it.
     *
     * @param meta    {@code chart.result[0].meta}
     * @param zone    the exchange time zone the bar dates are in
     * @param barHigh the bar's high at {@link #PRICE_SCALE} decimals ({@code null}: not given)
     * @param barLow  the bar's low at {@link #PRICE_SCALE} decimals ({@code null}: not given)
     */
    public static Optional<Close> of(JsonNode meta, ZoneId zone, LocalDate barDate, BigDecimal barHigh,
                                     BigDecimal barLow) {
        if (meta == null || barDate == null) {
            return Optional.empty();
        }
        JsonNode time = meta.path("regularMarketTime");
        BigDecimal price = decimal(meta.path("regularMarketPrice"));
        if (!time.isNumber() || price == null || price.signum() <= 0) {
            return Optional.empty();
        }
        LocalDate sessionDate = Instant.ofEpochSecond(time.asLong()).atZone(zone).toLocalDate();
        if (!sessionDate.equals(barDate)) {
            return Optional.empty();
        }
        BigDecimal dayHigh = decimal(meta.path("regularMarketDayHigh"));
        BigDecimal dayLow = decimal(meta.path("regularMarketDayLow"));
        if (!within(price, barLow, barHigh) || !within(price, dayLow, dayHigh)) {
            return Optional.empty();
        }
        JsonNode volume = meta.path("regularMarketVolume");
        return Optional.of(new Close(price, dayHigh, dayLow, volume.isNumber() ? volume.longValue() : null));
    }

    private static boolean within(BigDecimal price, BigDecimal low, BigDecimal high) {
        return (low == null || price.compareTo(low) >= 0) && (high == null || price.compareTo(high) <= 0);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber()
                ? node.decimalValue().setScale(PRICE_SCALE, RoundingMode.HALF_UP)
                : null;
    }
}
