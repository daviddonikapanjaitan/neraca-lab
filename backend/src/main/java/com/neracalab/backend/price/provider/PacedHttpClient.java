package com.neracalab.backend.price.provider;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.neracalab.backend.price.PriceProperties;

/**
 * The one HTTP client of the price providers.
 * <ul>
 *   <li>one {@link HttpClient} with a cookie store for the whole application, so cookies a
 *       provider sets are sent back on later requests</li>
 *   <li>a normal browser User-Agent on every request</li>
 *   <li>one request at a time, and between two requests a random pause of
 *       {@code neracalab.prices.min-delay}..{@code max-delay} (default 1-2 s)</li>
 *   <li>a request made {@link #ahead} (an upload waiting for a company's share counts) is sent before the
 *       background requests waiting at that moment (the price jobs the uploads queue), still with the pause: 50
 *       uploads at once waited up to 53 s behind them on 2026-10-31</li>
 * </ul>
 * The screening data ETL (Yahoo Finance screener and fundamentals) uses the same client, so price
 * ingestion and ETL together never exceed the pacing towards Yahoo.
 * Error messages name host and path only, never the query string (it may carry an API token).
 */
@Component
public class PacedHttpClient {

    /** Status, body and Retry-After of a response. */
    public record Response(int status, String body, Duration retryAfter) {
    }

    /** Set on a thread while it makes requests {@link #ahead} of the background ones. */
    private static final ThreadLocal<Boolean> AHEAD = new ThreadLocal<>();

    private final HttpClient http;
    private final PriceProperties properties;
    /** One request at a time, waiting threads in order of arrival. */
    private final ReentrantLock turn = new ReentrantLock(true);
    /** Signalled when a request is done: a background request that stepped aside tries again. */
    private final Condition done = turn.newCondition();
    /** Requests made {@link #ahead} that wait for their turn or are being sent. */
    private final AtomicInteger aheadWaiting = new AtomicInteger();
    private Instant nextRequestAt = Instant.MIN;

    public PacedHttpClient(PriceProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET {@code uri} once, after the pause since the previous request has elapsed.
     *
     * @throws PriceProviderException on a network error or when the thread is interrupted
     *                                (the interrupt flag is restored)
     */
    public Response get(URI uri) {
        return send(builder(uri).GET().build());
    }

    /**
     * POST {@code jsonBody} ({@code Content-Type: application/json}) to {@code uri} once, paced like
     * {@link #get} (the Yahoo Finance screener of the stock screening).
     */
    public Response postJson(URI uri, String jsonBody) {
        return send(builder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    /**
     * Runs {@code requests} with every request of this client it makes on this thread sent before the background
     * requests that wait at that moment - for a caller someone is waiting on (an upload asking for a company's share
     * counts). The pause between two requests stays: the provider sees the same pace.
     */
    public static <T> T ahead(Supplier<T> requests) {
        Boolean before = AHEAD.get();
        AHEAD.set(Boolean.TRUE);
        try {
            return requests.get();
        } finally {
            if (before == null) {
                AHEAD.remove();
            } else {
                AHEAD.set(before);
            }
        }
    }

    private HttpRequest.Builder builder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "application/json,text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9");
    }

    private Response send(HttpRequest request) {
        URI uri = request.uri();
        boolean ahead = Boolean.TRUE.equals(AHEAD.get());
        if (ahead) {
            aheadWaiting.incrementAndGet();
        }
        try {
            turn.lockInterruptibly();
        } catch (InterruptedException e) {
            if (ahead) {
                aheadWaiting.decrementAndGet();
            }
            Thread.currentThread().interrupt();
            throw new PriceProviderException("Interrupted while requesting " + describe(uri), e);
        }
        try {
            while (!ahead && aheadWaiting.get() > 0) {
                done.await();           // a request someone waits on goes first
            }
            waitForTurn();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
        } catch (IOException e) {
            throw new PriceProviderException("Request to " + describe(uri) + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceProviderException("Interrupted while requesting " + describe(uri), e);
        } finally {
            nextRequestAt = Instant.now().plus(randomPause());
            if (ahead) {
                aheadWaiting.decrementAndGet();
            }
            done.signalAll();
            turn.unlock();
        }
    }

    private void waitForTurn() throws InterruptedException {
        Duration wait = Duration.between(Instant.now(), nextRequestAt);
        if (wait.isPositive()) {
            Thread.sleep(wait);
        }
    }

    private Duration randomPause() {
        long min = properties.minDelay().toMillis();
        long max = properties.maxDelay().toMillis();
        return Duration.ofMillis(min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    /** Retry-After in seconds; HTTP-date values and garbage are ignored. */
    static Duration retryAfter(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Host and path of a request, without the query string. */
    public static String describe(URI uri) {
        return uri.getHost() + uri.getRawPath();
    }
}
