package com.neracalab.backend.price;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Daily price ingestion ({@code neracalab.prices.*}).
 *
 * @param provider          price source: {@code yahoo} (default) or {@code eodhd}; switching needs only this property
 *                          (plus {@code eodhd.api-token})
 * @param minDelay          minimum pause between two provider requests in the <em>throttled</em> pace (one request at a
 *                          time; also the pace of every request when {@code throughput.concurrent-requests} is 1)
 * @param maxDelay          maximum pause; each pause is a random value between minDelay and maxDelay
 * @param backoff           waits after consecutive HTTP 429 responses (15m, 30m, 60m); a 429 after the last
 *                          wait stops the run
 * @param sessionCloseCutoff exchange-local time after which today's bar is final and stored (IDX closes 16:00,
 *                          post-closing ends 16:15); before it today's bar is an unfinished intraday row
 * @param fullHistoryFrom   start date of a full-history fetch (first ingestion of a company, {@code full=true},
 *                          or after the provider re-adjusted its history for a dividend / split)
 * @param userAgent         browser User-Agent sent with every request
 * @param connectTimeout    HTTP connect timeout
 * @param requestTimeout    HTTP timeout of one request
 * @param jobHistory        finished jobs kept in memory for {@code GET /api/v1/prices/ingestions}
 * @param throughput        how many provider requests may be in flight, and when the client falls back to the
 *                          throttled pace ({@link Throughput})
 */
@ConfigurationProperties("neracalab.prices")
public record PriceProperties(
        @DefaultValue("yahoo") String provider,
        @DefaultValue("1s") Duration minDelay,
        @DefaultValue("2s") Duration maxDelay,
        @DefaultValue({"15m", "30m", "60m"}) List<Duration> backoff,
        @DefaultValue("17:00") String sessionCloseCutoff,
        @DefaultValue("1990-01-01") String fullHistoryFrom,
        @DefaultValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/140.0.0.0 Safari/537.36") String userAgent,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("30s") Duration requestTimeout,
        @DefaultValue("500") int jobHistory,
        @DefaultValue Yahoo yahoo,
        @DefaultValue Eodhd eodhd,
        @DefaultValue Fx fx,
        @DefaultValue Schedule schedule,
        @DefaultValue Throughput throughput) {

    /** The properties without {@code throughput}: its defaults (tests and older callers). */
    public PriceProperties(String provider, Duration minDelay, Duration maxDelay, List<Duration> backoff,
                           String sessionCloseCutoff, String fullHistoryFrom, String userAgent, Duration connectTimeout,
                           Duration requestTimeout, int jobHistory, Yahoo yahoo, Eodhd eodhd, Fx fx, Schedule schedule) {
        this(provider, minDelay, maxDelay, backoff, sessionCloseCutoff, fullHistoryFrom, userAgent, connectTimeout,
                requestTimeout, jobHistory, yahoo, eodhd, fx, schedule, Throughput.DEFAULTS);
    }

    @ConstructorBinding
    public PriceProperties {
        if (minDelay.isNegative() || maxDelay.compareTo(minDelay) < 0) {
            throw new IllegalArgumentException("neracalab.prices: need 0 <= min-delay <= max-delay");
        }
        if (backoff.isEmpty()) {
            throw new IllegalArgumentException("neracalab.prices.backoff must list at least one wait");
        }
        // fail at startup on malformed values instead of at the first ingestion
        LocalTime.parse(sessionCloseCutoff);
        LocalDate.parse(fullHistoryFrom);
    }

    public LocalTime sessionCloseCutoffTime() {
        return LocalTime.parse(sessionCloseCutoff);
    }

    public LocalDate fullHistoryFromDate() {
        return LocalDate.parse(fullHistoryFrom);
    }

    /**
     * The latest trading day whose bar is final at exchange-local time {@code now}: today from the session close cutoff
     * on, otherwise the previous day; weekends step back to Friday. Exchange holidays are not known here: a holiday
     * simply returns no bar.
     */
    public LocalDate lastCompletedTradingDay(ZonedDateTime now) {
        LocalDate day = now.toLocalTime().isBefore(sessionCloseCutoffTime())
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return day;
    }

    /** @param baseUrl chart API host; query2.finance.yahoo.com serves the same API */
    public record Yahoo(@DefaultValue("https://query1.finance.yahoo.com") String baseUrl) {
    }

    /** @param apiToken EODHD API token (required when provider = eodhd) */
    public record Eodhd(@DefaultValue("https://eodhd.com") String baseUrl, @DefaultValue("") String apiToken) {
    }

    /**
     * Exchange rates for companies that report in another currency than their listing trades in.
     *
     * @param baseUrl Frankfurter API (ECB reference rates)
     */
    public record Fx(@DefaultValue("https://api.frankfurter.dev/v1") String baseUrl) {
    }

    /**
     * Optional evening run that queues every active company of {@code exchange}.
     *
     * @param cron Spring cron in {@code zone}; default 17:30 Monday-Friday, after the IDX close
     */
    public record Schedule(@DefaultValue("false") boolean enabled,
                           @DefaultValue("0 30 17 * * MON-FRI") String cron,
                           @DefaultValue("Asia/Jakarta") String zone,
                           @DefaultValue("IDX") String exchange) {
    }

    /**
     * The pace towards the price provider (Yahoo Finance; the screening ETL shares the client).
     *
     * @param concurrentRequests requests in flight at the same time in the normal pace (8); 1 = the old one-at-a-time
     *                           pace with the {@code min-delay}..{@code max-delay} pause after every request
     * @param requestSpacing     minimum interval between the starts of two requests in the normal pace (150 ms: at most
     *                           ~6.7 requests a second; 2026-11-05 runs of ~940 companies took 27 - 62 min one at a time)
     * @param throttledFor       after an HTTP 429 the client falls back to the throttled pace (one request at a time,
     *                           the min..max pause) for this long, then returns to the normal pace
     * @param noDataRetryAfter   "Update all companies" skips a company whose last price job found no data at the provider
     *                           (delisted / suspended symbol: "No data found, symbol may be delisted") within this time;
     *                           a single-company ingestion still asks
     */
    public record Throughput(@DefaultValue("8") int concurrentRequests,
                             @DefaultValue("150ms") Duration requestSpacing,
                             @DefaultValue("30m") Duration throttledFor,
                             @DefaultValue("7d") Duration noDataRetryAfter) {

        public static final Throughput DEFAULTS = new Throughput(8, Duration.ofMillis(150), Duration.ofMinutes(30),
                Duration.ofDays(7));

        public Throughput {
            if (concurrentRequests < 1) {
                throw new IllegalArgumentException("neracalab.prices.throughput.concurrent-requests must be at least 1");
            }
            if (requestSpacing.isNegative() || throttledFor.isNegative() || noDataRetryAfter.isNegative()) {
                throw new IllegalArgumentException("neracalab.prices.throughput: durations must not be negative");
            }
        }
    }
}
