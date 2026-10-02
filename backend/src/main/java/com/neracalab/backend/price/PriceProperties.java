package com.neracalab.backend.price;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Daily price ingestion ({@code neracalab.prices.*}).
 *
 * @param provider          price source: {@code yahoo} (default) or {@code eodhd}; switching needs only this property
 *                          (plus {@code eodhd.api-token})
 * @param minDelay          minimum pause between two provider requests
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
        @DefaultValue Schedule schedule) {

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

    /** @param baseUrl chart API host; query2.finance.yahoo.com serves the same API */
    public record Yahoo(@DefaultValue("https://query1.finance.yahoo.com") String baseUrl) {
    }

    /** @param apiToken EODHD API token (required when provider = eodhd) */
    public record Eodhd(@DefaultValue("https://eodhd.com") String baseUrl, @DefaultValue("") String apiToken) {
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
}
