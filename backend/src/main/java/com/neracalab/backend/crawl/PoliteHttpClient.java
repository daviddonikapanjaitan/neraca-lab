package com.neracalab.backend.crawl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * The HTTP client of the crawlers (macro sources, World Bank commodity prices).
 * <ul>
 *   <li>a User-Agent that names the application ({@code neracalab.crawl.user-agent})</li>
 *   <li>at most one request per host at a time, and {@code min-delay} (2 s) between two requests to the same host</li>
 *   <li>a cookie store (the export of an ASP.NET page needs the session cookie of the page it was read from)</li>
 *   <li>no retries and no work-arounds: a refused request (403, 429, a bot challenge) fails the job with its status</li>
 * </ul>
 * Error messages name host and path only, never the query string.
 */
@Component
public class PoliteHttpClient {

    /** Status, body and content type of a response. */
    public record Response(int status, byte[] body, String contentType) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http;
    private final CrawlProperties properties;
    /** host -> earliest time of its next request; guarded by itself */
    private final Map<String, Instant> nextRequestAt = new HashMap<>();
    /** host -> lock, so that one host is asked one request at a time */
    private final Map<String, Object> hostLocks = new HashMap<>();

    public PoliteHttpClient(CrawlProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Response get(URI uri) {
        return send(builder(uri).GET().build());
    }

    /** POST of an HTML form ({@code application/x-www-form-urlencoded}). */
    public Response postForm(URI uri, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(builder(uri).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", uri.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /** GET that must answer 2xx; the body as text. */
    public String getText(URI uri) {
        Response response = get(uri);
        requireOk(uri, response);
        return response.text();
    }

    /** Fails with the status (and a hint for a bot challenge) unless the response is 2xx. */
    public static void requireOk(URI uri, Response response) {
        if (response.ok()) {
            return;
        }
        String hint = response.status() == 403 || response.status() == 503 && response.text().contains("challenge")
                ? " (the site refused an automated request; enter the data by CSV upload instead)"
                : response.status() == 429 ? " (rate limit; try again later)" : "";
        throw new SourceException(describe(uri) + " answered HTTP " + response.status() + hint);
    }

    private HttpRequest.Builder builder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9,id;q=0.8");
    }

    private Response send(HttpRequest request) {
        URI uri = request.uri();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        Object lock;
        synchronized (hostLocks) {
            lock = hostLocks.computeIfAbsent(host, h -> new Object());
        }
        synchronized (lock) {
            try {
                waitForTurn(host);
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                byte[] body = read(uri, response.body());
                return new Response(response.statusCode(), body,
                        response.headers().firstValue("Content-Type").orElse(""));
            } catch (IOException e) {
                throw new SourceException("Request to " + describe(uri) + " failed: " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException("Interrupted while requesting " + describe(uri), e);
            } finally {
                synchronized (nextRequestAt) {
                    nextRequestAt.put(host, Instant.now().plus(properties.minDelay()));
                }
            }
        }
    }

    private byte[] read(URI uri, InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > properties.maxBytes()) {
                    throw new SourceException(describe(uri) + " sent more than " + properties.maxBytes() + " bytes");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private void waitForTurn(String host) throws InterruptedException {
        Instant next;
        synchronized (nextRequestAt) {
            next = nextRequestAt.getOrDefault(host, Instant.MIN);
        }
        Duration wait = Duration.between(Instant.now(), next);
        if (wait.isPositive()) {
            Thread.sleep(wait);
        }
    }

    /** Host and path of a request, without the query string. */
    public static String describe(URI uri) {
        return uri.getHost() + uri.getRawPath();
    }
}
