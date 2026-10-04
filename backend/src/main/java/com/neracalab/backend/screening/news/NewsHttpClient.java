package com.neracalab.backend.screening.news;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import com.neracalab.backend.price.PriceProperties;
import com.neracalab.backend.screening.ScreeningProperties;

/**
 * HTTP client of the news crawler: a browser User-Agent and, per host, one request at a time with
 * a random pause of {@code neracalab.screening.news.min-delay}..{@code max-delay} between two
 * requests (different sites are fetched in parallel). Only pages the sites' robots.txt allow are
 * requested (tag pages, news listings and articles).
 */
@Component
public class NewsHttpClient {

    /** Larger pages are cut (no news page needs more). */
    static final int MAX_BODY_CHARS = 2_000_000;

    public static class NewsFetchException extends RuntimeException {

        public NewsFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record Page(int status, String body) {
    }

    private final HttpClient http;
    private final String userAgent;
    private final ScreeningProperties.News properties;
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, Instant> nextRequestAt = new ConcurrentHashMap<>();

    public NewsHttpClient(ScreeningProperties properties, PriceProperties priceProperties) {
        this.properties = properties.news();
        this.userAgent = priceProperties.userAgent();
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET a page, paced per host.
     *
     * @throws NewsFetchException on a network error or interruption (the interrupt flag is restored)
     */
    public Page get(URI uri) {
        String host = uri.getHost();
        synchronized (hostLocks.computeIfAbsent(host, h -> new Object())) {
            try {
                Duration wait = Duration.between(Instant.now(), nextRequestAt.getOrDefault(host, Instant.MIN));
                if (wait.isPositive()) {
                    Thread.sleep(wait);
                }
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
                        .GET()
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                if (body != null && body.length() > MAX_BODY_CHARS) {
                    body = body.substring(0, MAX_BODY_CHARS);
                }
                return new Page(response.statusCode(), body);
            } catch (IOException e) {
                throw new NewsFetchException("Request to " + host + uri.getRawPath() + " failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NewsFetchException("Interrupted while requesting " + host, e);
            } finally {
                nextRequestAt.put(host, Instant.now().plus(pause()));
            }
        }
    }

    private Duration pause() {
        long min = properties.minDelay().toMillis();
        long max = properties.maxDelay().toMillis();
        return Duration.ofMillis(min >= max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
    }
}
