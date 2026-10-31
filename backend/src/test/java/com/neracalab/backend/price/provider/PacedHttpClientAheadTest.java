package com.neracalab.backend.price.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.price.TestPriceProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * An upload waiting for a company's share counts asks Yahoo {@link PacedHttpClient#ahead} of the background price
 * requests waiting in the same one-at-a-time line (50 uploads at once waited up to 53 s behind them, 2026-10-31): it is
 * sent next, the others after it, one at a time. Against a local HTTP server (no network, no pause).
 */
class PacedHttpClientAheadTest {

    @Test
    void aRequestMadeAheadIsSentBeforeTheWaitingBackgroundRequests() throws Exception {
        List<String> served = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstArrived = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String name = exchange.getRequestURI().getPath().substring(1);
            served.add(name);
            if (name.equals("first")) {
                firstArrived.countDown();
                try {
                    Thread.sleep(600);          // the others queue up meanwhile
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            PacedHttpClient client = new PacedHttpClient(TestPriceProperties.defaults());
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            List<Future<?>> calls = new ArrayList<>();
            calls.add(pool.submit(() -> client.get(URI.create(base + "first"))));
            assertThat(firstArrived.await(10, TimeUnit.SECONDS)).isTrue();
            for (String name : List.of("background-1", "background-2", "background-3")) {
                calls.add(pool.submit(() -> client.get(URI.create(base + name))));
            }
            Thread.sleep(150);                  // the background requests wait for their turn
            calls.add(pool.submit(() -> PacedHttpClient.ahead(() -> client.get(URI.create(base + "upload")))));
            for (Future<?> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            server.stop(0);
        }

        assertThat(served).hasSize(5);
        assertThat(served.subList(0, 2)).containsExactly("first", "upload");
        assertThat(served.subList(2, 5)).containsExactlyInAnyOrder("background-1", "background-2", "background-3");
    }

    @Test
    void aheadIsOnlyForTheCallItWraps() {
        PacedHttpClient.ahead(() -> "inside");
        // nothing left on the thread: an ordinary request afterwards is a background one again (no exception, no state)
        assertThat(PacedHttpClient.ahead(() -> PacedHttpClient.ahead(() -> 1))).isEqualTo(1);
    }
}
