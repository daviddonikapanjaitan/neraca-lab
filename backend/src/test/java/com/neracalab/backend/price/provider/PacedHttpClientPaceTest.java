package com.neracalab.backend.price.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.price.TestPriceProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * The pace towards the price provider since 2026-11-05: several requests in flight (not one at a time with a 1 - 2 s
 * pause, which made "Update all companies" of ~940 companies take 27 - 62 minutes), their starts apart, and after an
 * HTTP 429 the one-at-a-time pace again. Against a local HTTP server that answers after 300 ms (no network).
 */
class PacedHttpClientPaceTest {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final List<Long> starts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger tooMany = new AtomicInteger();
    private HttpServer server;
    private ExecutorService pool;

    @AfterEach
    void stop() {
        if (pool != null) {
            pool.shutdownNow();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            starts.add(System.nanoTime());
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            int status = tooMany.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 429 : 200;
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        pool = Executors.newCachedThreadPool();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void requests(PacedHttpClient client, String base, int n) throws Exception {
        List<Future<?>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = "r" + i;
            calls.add(pool.submit(() -> client.get(URI.create(base + name))));
        }
        for (Future<?> call : calls) {
            call.get(60, TimeUnit.SECONDS);
        }
    }

    @Test
    void severalRequestsAreInFlightButNeverMoreThanTheLimit() throws Exception {
        String base = start();
        PacedHttpClient client = new PacedHttpClient(
                TestPriceProperties.withThroughput(4, Duration.ofMillis(10), Duration.ofMinutes(30)));

        long t0 = System.nanoTime();
        requests(client, base, 12);
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(maxInFlight.get()).isEqualTo(4);
        // 12 requests of 300 ms, 4 at a time: about 0.9 s, not the 3.6 s of one at a time
        assertThat(took).isLessThan(2_500);
        assertThat(client.throttled()).isFalse();
    }

    @Test
    void theStartsAreAtLeastTheSpacingApart() throws Exception {
        String base = start();
        PacedHttpClient client = new PacedHttpClient(
                TestPriceProperties.withThroughput(8, Duration.ofMillis(100), Duration.ofMinutes(30)));

        requests(client, base, 6);

        List<Long> sorted = new ArrayList<>(starts);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            // the server sees the start a little after the client lets it go: allow 15 ms of jitter
            assertThat(TimeUnit.NANOSECONDS.toMillis(sorted.get(i) - sorted.get(i - 1))).isGreaterThanOrEqualTo(85);
        }
    }

    /** An HTTP 429 is never worked around: the client falls back to one request at a time for throttled-for. */
    @Test
    void anHttp429SwitchesToOneRequestAtATime() throws Exception {
        String base = start();
        PacedHttpClient client = new PacedHttpClient(
                TestPriceProperties.withThroughput(4, Duration.ZERO, Duration.ofMinutes(30)));
        tooMany.set(1);

        PacedHttpClient.Response first = client.get(URI.create(base + "first"));
        assertThat(first.status()).isEqualTo(429);
        assertThat(client.throttled()).isTrue();

        maxInFlight.set(0);
        requests(client, base, 5);
        assertThat(maxInFlight.get()).isEqualTo(1);
    }
}
