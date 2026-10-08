package com.neracalab.backend.screening.news;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.ScreeningProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tavily search API ({@code POST /search}, topic "news", basic depth = 1 API credit per search).
 * The key is {@code neracalab.screening.news.tavily-api-key} ({@code TAVILY_API_KEY}); without it
 * the tool reports itself unavailable.
 */
@Component
public class TavilyClient {

    /** Longest snippet kept per result. */
    static final int SNIPPET_CHARS = 400;

    public static class TavilyException extends RuntimeException {

        public TavilyException(String message) {
            super(message);
        }
    }

    private final ScreeningProperties.News properties;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TavilyClient(ScreeningProperties properties, JsonMapper json) {
        this.properties = properties.news();
        this.json = json;
    }

    public boolean enabled() {
        return properties.tavilyEnabled();
    }

    /**
     * News search.
     *
     * @param days only results of the last days
     * @throws TavilyException when the API is not configured, unreachable or answers with an error
     */
    public List<Headline> search(String query, int days) {
        if (!enabled()) {
            throw new TavilyException("Tavily search is not configured (TAVILY_API_KEY)");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("topic", "news");
        body.put("days", Math.max(1, days));
        body.put("max_results", properties.tavilyMaxResults());
        body.put("search_depth", "basic");
        body.put("include_answer", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(stripSlash(properties.tavilyBaseUrl()) + "/search"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.tavilyApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new TavilyException("Tavily is unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TavilyException("Interrupted while calling Tavily");
        }
        if (response.statusCode() != 200) {
            throw new TavilyException("Tavily answered HTTP " + response.statusCode()
                    + (response.statusCode() == 432 || response.statusCode() == 433 ? " (plan / credit limit reached)" : ""));
        }
        try {
            return results(json.readTree(response.body()));
        } catch (JacksonException e) {
            throw new TavilyException("Tavily answered with a body that is not JSON");
        }
    }

    static List<Headline> results(JsonNode root) {
        List<Headline> out = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String url = r.path("url").asString(null);
            String title = r.path("title").asString(null);
            if (url == null || title == null || !url.startsWith("http")) {
                continue;
            }
            String content = r.path("content").asString("").replaceAll("[#|*_>`\\-]{2,}", " ").replaceAll("\\s+", " ").trim();
            if (content.length() > SNIPPET_CHARS) {
                content = content.substring(0, SNIPPET_CHARS) + "…";
            }
            out.add(new Headline(url, NewsSource.TAVILY, title.trim(), content.isEmpty() ? null : content,
                    publishedDate(r.path("published_date").asString(null))));
        }
        return out;
    }

    /** RFC 1123 ("Wed, 01 Oct 2026 09:00:00 GMT") or ISO dates. */
    static Instant publishedDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return NewsParsers.isoInstant(text);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
