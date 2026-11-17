package com.neracalab.backend.crawl;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The polite HTTP client of the crawlers ({@code neracalab.crawl.*}): the macro sources (FRED, Bank Indonesia, CNBC,
 * CFTC, the weather sources) and the World Bank commodity prices.
 *
 * @param minDelay       pause between two requests to the same host
 * @param userAgent      User-Agent sent with every request (names the application; a crawler does not pose as a browser)
 * @param connectTimeout HTTP connect timeout
 * @param requestTimeout HTTP timeout of one request (the World Bank workbook is about 1 MB)
 * @param maxBytes       largest response body accepted
 * @param hostDelays     a longer pause for a host that throttles a bulk download (host, lower case -> pause); a host
 *                       not listed waits {@code minDelay}
 */
@ConfigurationProperties("neracalab.crawl")
public record CrawlProperties(
        @DefaultValue("2s") Duration minDelay,
        @DefaultValue("Mozilla/5.0 (compatible; NeracaLab/1.0; +https://github.com/daviddonikapanjaitan/neraca-lab)")
        String userAgent,
        @DefaultValue("15s") Duration connectTimeout,
        @DefaultValue("90s") Duration requestTimeout,
        @DefaultValue("20000000") long maxBytes,
        Map<String, Duration> hostDelays) {

    public CrawlProperties {
        if (minDelay.isNegative()) {
            throw new IllegalArgumentException("neracalab.crawl.min-delay must not be negative");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("neracalab.crawl.max-bytes must be positive");
        }
        hostDelays = hostDelays == null ? Map.of() : Map.copyOf(hostDelays);
        hostDelays.forEach((host, pause) -> {
            if (pause.isNegative()) {
                throw new IllegalArgumentException("neracalab.crawl.host-delays." + host + " must not be negative");
            }
        });
    }

    /** The pause after a request to {@code host}: its own when listed and longer, else {@code minDelay}. */
    public Duration delay(String host) {
        Duration own = hostDelays.get(host == null ? "" : host.toLowerCase(Locale.ROOT));
        return own != null && own.compareTo(minDelay) > 0 ? own : minDelay;
    }
}
