package com.neracalab.backend.price.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Yahoo Finance chart API ({@code GET /v8/finance/chart/HRTA.JK?period1=..&period2=..&interval=1d}).
 * Unofficial: no API key, no published limits; HTTP 429 when Yahoo throttles the IP.
 * <p>
 * Response: {@code chart.result[0]} with {@code meta} (currency, exchange time zone), one
 * {@code timestamp} per bar (session open, epoch seconds) and the parallel arrays
 * {@code indicators.quote[0].open/high/low/close/volume} and {@code indicators.adjclose[0].adjclose}.
 * Holiday placeholders have {@code null} in every array. {@code close} is split-adjusted,
 * {@code adjclose} split- and dividend-adjusted.
 * <p>
 * Prices are rounded to 4 decimals (Yahoo sends binary floats such as 2033.51953125).
 */
@Component
@ConditionalOnProperty(prefix = "neracalab.prices", name = "provider", havingValue = "yahoo", matchIfMissing = true)
public class YahooPriceProvider implements PriceProvider {

    static final int PRICE_SCALE = 4;

    private final PacedHttpClient http;
    private final JsonMapper json;
    private final String baseUrl;

    public YahooPriceProvider(PacedHttpClient http, JsonMapper json, PriceProperties properties) {
        this.http = http;
        this.json = json;
        this.baseUrl = stripTrailingSlash(properties.yahoo().baseUrl());
    }

    @Override
    public String name() {
        return "yahoo";
    }

    /** Yahoo symbol of a listing: ticker plus the exchange suffix, e.g. HRTA on IDX -> HRTA.JK. */
    static String symbol(Exchange exchange, String ticker) {
        return switch (exchange) {
            case IDX -> ticker + ".JK";
        };
    }

    @Override
    public PriceHistory fetch(Exchange exchange, String ticker, LocalDate from, LocalDate to) {
        String symbol = symbol(exchange, ticker);
        ZoneId zone = exchange.zone();
        long period1 = from.atStartOfDay(zone).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(zone).toEpochSecond();   // exclusive
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?period1=" + period1 + "&period2=" + period2
                + "&interval=1d&events=div%2Csplit&includeAdjustedClose=true");

        PacedHttpClient.Response response = http.get(uri);
        if (response.status() == 429) {
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests for " + symbol,
                    response.retryAfter());
        }
        JsonNode root = parse(response, symbol);
        JsonNode error = root.path("chart").path("error");
        if (response.status() == 404 || (!error.isMissingNode() && !error.isNull())) {
            String description = error.path("description").asString("HTTP " + response.status());
            if (response.status() == 404 || "Not Found".equals(error.path("code").asString(""))) {
                throw new SymbolNotFoundException("Yahoo Finance has no data for " + symbol + ": " + description);
            }
            throw new PriceProviderException("Yahoo Finance error for " + symbol + ": " + description);
        }
        if (response.status() != 200) {
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status() + " for " + symbol);
        }
        JsonNode result = root.path("chart").path("result").path(0);
        if (result.isMissingNode() || result.isNull()) {
            throw new PriceProviderException("Yahoo Finance response for " + symbol + " has no chart result");
        }
        return history(symbol, result, zone);
    }

    static PriceHistory history(String symbol, JsonNode result, ZoneId fallbackZone) {
        JsonNode meta = result.path("meta");
        ZoneId zone = zone(meta.path("exchangeTimezoneName").asString(null), fallbackZone);
        String currency = meta.path("currency").asString(null);

        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        JsonNode adjclose = result.path("indicators").path("adjclose").path(0).path("adjclose");
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(zone).toLocalDate();
            bars.add(new DailyBar(date,
                    price(quote.path("open").path(i)), price(quote.path("high").path(i)),
                    price(quote.path("low").path(i)), price(quote.path("close").path(i)),
                    price(adjclose.path(i)), volume(quote.path("volume").path(i))));
        }
        return new PriceHistory(symbol, currency, bars);
    }

    private JsonNode parse(PacedHttpClient.Response response, String symbol) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            if (response.status() == 404) {
                throw new SymbolNotFoundException("Yahoo Finance has no data for " + symbol);
            }
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status()
                    + " with an empty body for " + symbol);
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status()
                    + " with a body that is not JSON for " + symbol, e);
        }
    }

    private static BigDecimal price(JsonNode node) {
        return node.isNumber() ? node.decimalValue().setScale(PRICE_SCALE, RoundingMode.HALF_UP) : null;
    }

    private static Long volume(JsonNode node) {
        return node.isNumber() ? node.longValue() : null;
    }

    private static ZoneId zone(String id, ZoneId fallback) {
        if (id == null || id.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
