package com.neracalab.backend.price;

import java.time.Duration;
import java.util.List;

/** PriceProperties with the application defaults, no pause between requests and the given back-off waits. */
public final class TestPriceProperties {

    private TestPriceProperties() {
    }

    public static PriceProperties withBackoff(Duration... backoff) {
        return new PriceProperties("stub", Duration.ZERO, Duration.ZERO, List.of(backoff), "17:00", "1990-01-01",
                "Mozilla/5.0 test", Duration.ofSeconds(10), Duration.ofSeconds(30), 500,
                new PriceProperties.Yahoo("https://query1.finance.yahoo.com"),
                new PriceProperties.Eodhd("https://eodhd.com", ""),
                new PriceProperties.Fx("https://api.frankfurter.dev/v1"),
                new PriceProperties.Schedule(false, "0 30 17 * * MON-FRI", "Asia/Jakarta", "IDX"));
    }

    public static PriceProperties defaults() {
        return withBackoff(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(60));
    }

    /** The defaults with another pace: requests in flight, the interval between their starts, the throttled time. */
    public static PriceProperties withThroughput(int concurrentRequests, Duration requestSpacing, Duration throttledFor) {
        PriceProperties d = defaults();
        return new PriceProperties(d.provider(), d.minDelay(), d.maxDelay(), d.backoff(), d.sessionCloseCutoff(),
                d.fullHistoryFrom(), d.userAgent(), d.connectTimeout(), d.requestTimeout(), d.jobHistory(), d.yahoo(),
                d.eodhd(), d.fx(), d.schedule(),
                new PriceProperties.Throughput(concurrentRequests, requestSpacing, throttledFor, Duration.ofDays(7)));
    }
}
