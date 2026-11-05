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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neracalab.backend.price.PriceProperties;

/**
 * The one HTTP client of the price providers.
 * <ul>
 *   <li>one {@link HttpClient} with a cookie store for the whole application, so cookies a
 *       provider sets are sent back on later requests</li>
 *   <li>a normal browser User-Agent on every request</li>
 *   <li><b>normal pace</b>: at most {@code neracalab.prices.throughput.concurrent-requests} (8) requests in flight, their
 *       starts at least {@code request-spacing} (150 ms) apart - at most ~6.7 requests a second. Until 2026-11-05 every
 *       request waited for the previous one plus a 1 - 2 s pause: "Update all companies" of ~940 IDX companies took
 *       27 - 62 minutes, the pause alone ~1.5 s a company, while Yahoo answered no HTTP 429 in five days of runs</li>
 *   <li><b>throttled pace</b> after an HTTP 429 (for {@code throttled-for}, 30 min), and always when concurrent-requests
 *       is 1: one request at a time and a random pause of {@code neracalab.prices.min-delay}..{@code max-delay}
 *       (1 - 2 s) after each - the pace Yahoo accepted for months. The provider's 429 is never worked around: the
 *       client slows down, and {@code PriceIngestionQueue} still pauses the queue as before</li>
 *   <li>a request made {@link #ahead} (an upload waiting for a company's share counts) starts before the
 *       background requests waiting at that moment (the price jobs the uploads queue), still within the pace: 50
 *       uploads at once waited up to 53 s behind them on 2026-10-31</li>
 * </ul>
 * The screening data ETL (Yahoo Finance screener and fundamentals) uses the same client, so price
 * ingestion and ETL together never exceed the pace towards Yahoo.
 * Error messages name host and path only, never the query string (it may carry an API token).
 */
@Component
public class PacedHttpClient {

    private static final Logger log = LoggerFactory.getLogger(PacedHttpClient.class);

    /** Status, body and Retry-After of a response. */
    public record Response(int status, String body, Duration retryAfter) {
    }

    /** Set on a thread while it makes requests {@link #ahead} of the background ones. */
    private static final ThreadLocal<Boolean> AHEAD = new ThreadLocal<>();

    private final HttpClient http;
    private final PriceProperties properties;
    /** Guards the pace state below; waiting threads take their turn in order of arrival as far as the pace allows. */
    private final ReentrantLock turn = new ReentrantLock(true);
    /** Signalled when a request starts or ends: waiting requests check their turn again. */
    private final Condition changed = turn.newCondition();
    /** Requests made {@link #ahead} that wait for their turn. */
    private final AtomicInteger aheadWaiting = new AtomicInteger();
    /** Requests in flight; guarded by {@code turn}. */
    private int inFlight;
    /** No request starts before this instant; guarded by {@code turn}. */
    private Instant nextRequestAt = Instant.MIN;
    /** The throttled pace lasts until this instant after an HTTP 429; guarded by {@code turn}. */
    private Instant throttledUntil = Instant.MIN;

    public PacedHttpClient(PriceProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET {@code uri} once, when the pace allows it.
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
     * Runs {@code requests} with every request of this client it makes on this thread started before the background
     * requests that wait at that moment - for a caller someone is waiting on (an upload asking for a company's share
     * counts). The pace stays: the provider sees the same rate.
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

    /** Whether the client is in the throttled pace now (after an HTTP 429, or configured one at a time). */
    public boolean throttled() {
        turn.lock();
        try {
            return throttled(Instant.now());
        } finally {
            turn.unlock();
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
        boolean throttledStart = acquire(uri, Boolean.TRUE.equals(AHEAD.get()));
        Integer status = null;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            return new Response(response.statusCode(), response.body(),
                    retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
        } catch (IOException e) {
            throw new PriceProviderException("Request to " + describe(uri) + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceProviderException("Interrupted while requesting " + describe(uri), e);
        } finally {
            release(uri, throttledStart, status);
        }
    }

    /**
     * Waits until this request may start: a free place within the pace's limit of requests in flight, the start
     * instant reached, and no request made {@link #ahead} waiting (unless this is one). Returns whether it starts in
     * the throttled pace.
     */
    private boolean acquire(URI uri, boolean ahead) {
        if (ahead) {
            aheadWaiting.incrementAndGet();
        }
        boolean waitingAhead = ahead;
        try {
            turn.lockInterruptibly();
            try {
                while (true) {
                    Instant now = Instant.now();
                    boolean throttled = throttled(now);
                    int limit = throttled ? 1 : properties.throughput().concurrentRequests();
                    if ((!ahead && aheadWaiting.get() > 0) || inFlight >= limit) {
                        changed.await();        // a request someone waits on goes first / no free place
                        continue;
                    }
                    Duration wait = Duration.between(now, nextRequestAt);
                    if (wait.isPositive()) {
                        changed.awaitNanos(wait.toNanos());
                        continue;
                    }
                    inFlight++;
                    if (waitingAhead) {
                        aheadWaiting.decrementAndGet();
                        waitingAhead = false;
                    }
                    if (!throttled) {
                        nextRequestAt = now.plus(properties.throughput().requestSpacing());
                    }
                    changed.signalAll();
                    return throttled;
                }
            } finally {
                turn.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PriceProviderException("Interrupted while requesting " + describe(uri), e);
        } finally {
            if (waitingAhead) {             // interrupted while waiting: let the background requests go on
                aheadWaiting.decrementAndGet();
                turn.lock();
                try {
                    changed.signalAll();
                } finally {
                    turn.unlock();
                }
            }
        }
    }

    /**
     * Ends a request: in the throttled pace the next one waits a random min..max pause from now; an HTTP 429 switches
     * to the throttled pace for {@code throttled-for}.
     */
    private void release(URI uri, boolean throttledStart, Integer status) {
        turn.lock();
        try {
            inFlight--;
            Instant now = Instant.now();
            if (status != null && status == 429) {
                if (!throttled(now)) {
                    log.warn("{} answered HTTP 429: throttled pace (one request at a time, {} - {} apart) for {}",
                            describe(uri), properties.minDelay(), properties.maxDelay(),
                            properties.throughput().throttledFor());
                }
                throttledUntil = now.plus(properties.throughput().throttledFor());
            }
            if (throttledStart || throttled(now)) {
                Instant afterPause = now.plus(randomPause());
                if (afterPause.isAfter(nextRequestAt)) {
                    nextRequestAt = afterPause;
                }
            }
            changed.signalAll();
        } finally {
            turn.unlock();
        }
    }

    /** Whether the throttled pace applies at {@code now}: after an HTTP 429, or always with one request at a time. */
    private boolean throttled(Instant now) {
        return properties.throughput().concurrentRequests() == 1 || now.isBefore(throttledUntil);
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
