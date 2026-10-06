package com.neracalab.backend.price.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.neracalab.backend.price.PriceProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Euro foreign exchange reference rates of the European Central Bank, through the Frankfurter API
 * ({@code GET /v1/2026-09-28..2026-10-05?base=USD&symbols=IDR}): free, no API key, daily since 1999.
 * The ECB publishes one rate per TARGET business day around 16:00 CET; Frankfurter derives USD/IDR
 * from EUR/USD and EUR/IDR. Response: {@code {"base":"USD","rates":{"2026-09-28":{"IDR":17977},...}}}.
 */
@Component
public class EcbFxRateProvider implements FxRateProvider {

    private final PacedHttpClient http;
    private final JsonMapper json;
    private final String baseUrl;

    public EcbFxRateProvider(PacedHttpClient http, JsonMapper json, PriceProperties properties) {
        this.http = http;
        this.json = json;
        this.baseUrl = YahooPriceProvider.stripTrailingSlash(properties.fx().baseUrl());
    }

    @Override
    public String name() {
        return "ecb";
    }

    @Override
    public List<FxRate> fetch(String base, String quote, LocalDate from, LocalDate to) {
        String pair = base + "/" + quote;
        URI uri = URI.create(baseUrl + "/" + from + ".." + to
                + "?base=" + URLEncoder.encode(base, StandardCharsets.UTF_8)
                + "&symbols=" + URLEncoder.encode(quote, StandardCharsets.UTF_8));
        PacedHttpClient.Response response = http.get(uri);
        switch (response.status()) {
            case 200 -> { }
            case 429 -> throw new RateLimitedException("ECB rates (Frankfurter) answered HTTP 429 for " + pair,
                    response.retryAfter());
            case 404, 422 -> throw new SymbolNotFoundException("ECB rates (Frankfurter) have no " + pair
                    + " (HTTP " + response.status() + ")");
            default -> throw new PriceProviderException("ECB rates (Frankfurter) answered HTTP " + response.status()
                    + " for " + pair);
        }
        try {
            return parse(json.readTree(response.body()), base, quote);
        } catch (JacksonException e) {
            throw new PriceProviderException("ECB rates (Frankfurter) answered with a body that is not JSON for " + pair, e);
        }
    }

    /** Rates of {@code quote} by date, oldest first; the response must be for {@code base}. */
    static List<FxRate> parse(JsonNode root, String base, String quote) {
        String pair = base + "/" + quote;
        if (!base.equalsIgnoreCase(root.path("base").asString(""))) {
            throw new PriceProviderException("ECB rates (Frankfurter) answered for base '"
                    + root.path("base").asString("") + "', expected " + pair);
        }
        JsonNode rates = root.path("rates");
        if (!rates.isObject()) {
            throw new PriceProviderException("ECB rates (Frankfurter) response for " + pair + " has no rates");
        }
        List<FxRate> result = new ArrayList<>();
        for (var entry : rates.properties()) {
            JsonNode rate = entry.getValue().path(quote);
            if (!rate.isNumber() || rate.decimalValue().signum() <= 0) {
                continue;
            }
            try {
                result.add(new FxRate(LocalDate.parse(entry.getKey()), new BigDecimal(rate.decimalValue().toPlainString())));
            } catch (DateTimeParseException e) {
                throw new PriceProviderException("ECB rates (Frankfurter) response for " + pair
                        + " has an invalid date '" + entry.getKey() + "'", e);
            }
        }
        result.sort(Comparator.comparing(FxRate::date));
        return result;
    }
}
