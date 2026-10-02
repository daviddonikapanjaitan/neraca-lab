package com.neracalab.backend.price.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * EODHD end-of-day API ({@code GET /api/eod/HRTA.JK?from=..&to=..&period=d&fmt=json&api_token=..}),
 * the paid fallback when Yahoo blocks the server IP. Activate with
 * {@code neracalab.prices.provider=eodhd} and {@code neracalab.prices.eodhd.api-token}
 * (env {@code EODHD_API_TOKEN}).
 * <p>
 * Response: a JSON array of {@code {date, open, high, low, close, adjusted_close, volume}}.
 * Unlike Yahoo, EODHD's {@code close} is the raw close (not split-adjusted);
 * {@code adjusted_close} is split- and dividend-adjusted. The response has no currency.
 */
@Component
@ConditionalOnProperty(prefix = "neracalab.prices", name = "provider", havingValue = "eodhd")
public class EodhdPriceProvider implements PriceProvider {

    private final PacedHttpClient http;
    private final JsonMapper json;
    private final String baseUrl;
    private final String apiToken;

    public EodhdPriceProvider(PacedHttpClient http, JsonMapper json, PriceProperties properties) {
        if (properties.eodhd().apiToken().isBlank()) {
            throw new IllegalStateException(
                    "neracalab.prices.provider=eodhd needs neracalab.prices.eodhd.api-token (env EODHD_API_TOKEN)");
        }
        this.http = http;
        this.json = json;
        this.baseUrl = YahooPriceProvider.stripTrailingSlash(properties.eodhd().baseUrl());
        this.apiToken = properties.eodhd().apiToken();
    }

    @Override
    public String name() {
        return "eodhd";
    }

    /** EODHD symbol of a listing: ticker plus the exchange code, e.g. HRTA on IDX -> HRTA.JK. */
    static String symbol(Exchange exchange, String ticker) {
        return switch (exchange) {
            case IDX -> ticker + ".JK";
        };
    }

    @Override
    public PriceHistory fetch(Exchange exchange, String ticker, LocalDate from, LocalDate to) {
        String symbol = symbol(exchange, ticker);
        URI uri = URI.create(baseUrl + "/api/eod/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?from=" + from + "&to=" + to + "&period=d&order=a&fmt=json"
                + "&api_token=" + URLEncoder.encode(apiToken, StandardCharsets.UTF_8));

        PacedHttpClient.Response response = http.get(uri);
        switch (response.status()) {
            case 200 -> { }
            case 429 -> throw new RateLimitedException("EODHD answered HTTP 429 Too Many Requests for " + symbol,
                    response.retryAfter());
            case 404 -> throw new SymbolNotFoundException("EODHD has no data for " + symbol);
            case 401, 402, 403 -> throw new PriceProviderException("EODHD refused the request for " + symbol
                    + " (HTTP " + response.status() + "): check the API token and the plan's daily limit");
            default -> throw new PriceProviderException("EODHD answered HTTP " + response.status() + " for " + symbol);
        }

        JsonNode rows;
        try {
            rows = json.readTree(response.body());
        } catch (JacksonException e) {
            throw new PriceProviderException("EODHD answered with a body that is not JSON for " + symbol, e);
        }
        if (!rows.isArray()) {
            throw new PriceProviderException("EODHD response for " + symbol + " is not a JSON array");
        }
        List<DailyBar> bars = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            bars.add(new DailyBar(LocalDate.parse(row.path("date").asString()),
                    price(row.path("open")), price(row.path("high")), price(row.path("low")),
                    price(row.path("close")), price(row.path("adjusted_close")),
                    row.path("volume").isNumber() ? row.path("volume").longValue() : null));
        }
        return new PriceHistory(symbol, null, bars);
    }

    private static BigDecimal price(JsonNode node) {
        return node.isNumber()
                ? node.decimalValue().setScale(YahooPriceProvider.PRICE_SCALE, RoundingMode.HALF_UP)
                : null;
    }
}
