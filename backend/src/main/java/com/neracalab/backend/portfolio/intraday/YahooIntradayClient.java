package com.neracalab.backend.portfolio.intraday;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.neracalab.backend.price.PriceProperties;
import com.neracalab.backend.price.provider.PacedHttpClient;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.price.provider.SymbolNotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The intraday chart of an IDX listing from Yahoo Finance ({@code GET /v8/finance/chart/HRTA.JK?range=1d&interval=5m}):
 * the latest session's bars and its quote (meta: last price and time, previous close, day high / low, volume, the
 * regular trading period). Through {@link PacedHttpClient}, so the daily price ingestion and the intraday refresh
 * together keep the pace towards Yahoo; an HTTP 429 is never worked around.
 */
@Component
public class YahooIntradayClient {

    /** Decimals of the prices kept (as the daily bars). */
    static final int PRICE_SCALE = 4;

    /** A bar: its start, OHLC and volume (listing currency). */
    public record Bar(Instant time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume) {
    }

    /**
     * The latest session of a listing.
     *
     * @param sessionDate   the trading day of {@code marketTime} (Asia/Jakarta)
     * @param previousClose the close of the session before (the base of the day's change)
     * @param bars          the session's bars, oldest first (only bars of {@code sessionDate}, with a close)
     */
    public record Session(String symbol, String currency, LocalDate sessionDate, BigDecimal price,
                          BigDecimal previousClose, BigDecimal dayOpen, BigDecimal dayHigh, BigDecimal dayLow,
                          Long volume, Instant marketTime, Instant periodStart, Instant periodEnd, List<Bar> bars) {
    }

    private final PacedHttpClient http;
    private final JsonMapper json;
    private final String baseUrl;
    private final String interval;

    public YahooIntradayClient(PacedHttpClient http, JsonMapper json, PriceProperties prices,
                               @Value("${neracalab.intraday.interval:5m}") String interval) {
        this.http = http;
        this.json = json;
        String url = prices.yahoo().baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.interval = interval;
    }

    /** Yahoo symbol of an IDX ticker: HRTA -> HRTA.JK. */
    public static String symbol(String ticker) {
        return ticker + ".JK";
    }

    /**
     * The latest session of an IDX ticker.
     *
     * @throws RateLimitedException    on HTTP 429 (the caller stops its round)
     * @throws SymbolNotFoundException Yahoo has no such symbol
     * @throws PriceProviderException  any other failure
     */
    public Session fetch(String ticker) {
        String symbol = symbol(ticker);
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?range=1d&interval=" + URLEncoder.encode(interval, StandardCharsets.UTF_8) + "&includePrePost=false");
        PacedHttpClient.Response response = PacedHttpClient.ahead(() -> http.get(uri));
        if (response.status() == 429) {
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests for " + symbol,
                    response.retryAfter());
        }
        JsonNode root;
        try {
            root = json.readTree(response.body() == null ? "" : response.body());
        } catch (RuntimeException e) {
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status()
                    + " with a body that is not JSON for " + symbol, e);
        }
        JsonNode error = root.path("chart").path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String code = error.path("code").asText("");
            if ("Not Found".equalsIgnoreCase(code)) {
                throw new SymbolNotFoundException("Yahoo Finance has no data for " + symbol);
            }
            throw new PriceProviderException("Yahoo Finance error for " + symbol + ": "
                    + error.path("description").asText(code));
        }
        if (response.status() != 200) {
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status() + " for " + symbol);
        }
        JsonNode result = root.path("chart").path("result").path(0);
        if (result.isMissingNode()) {
            throw new PriceProviderException("Yahoo Finance response for " + symbol + " has no chart result");
        }
        return parse(symbol, result);
    }

    /** A chart result as the latest session; its last price must be positive and timed. */
    static Session parse(String symbol, JsonNode result) {
        JsonNode meta = result.path("meta");
        BigDecimal price = decimal(meta.path("regularMarketPrice"));
        JsonNode time = meta.path("regularMarketTime");
        if (price == null || price.signum() <= 0 || !time.isNumber()) {
            throw new PriceProviderException("Yahoo Finance response for " + symbol + " has no last price");
        }
        Instant marketTime = Instant.ofEpochSecond(time.asLong());
        LocalDate sessionDate = marketTime.atZone(IdxSession.ZONE).toLocalDate();
        BigDecimal previous = decimal(meta.path("chartPreviousClose"));
        if (previous == null) {
            previous = decimal(meta.path("previousClose"));
        }
        JsonNode regular = meta.path("currentTradingPeriod").path("regular");
        Instant start = regular.path("start").isNumber() ? Instant.ofEpochSecond(regular.path("start").asLong()) : null;
        Instant end = regular.path("end").isNumber() ? Instant.ofEpochSecond(regular.path("end").asLong()) : null;

        List<Bar> bars = new ArrayList<>();
        JsonNode stamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        for (int i = 0; i < stamps.size(); i++) {
            BigDecimal close = decimal(quote.path("close").path(i));
            if (close == null || close.signum() <= 0) {
                continue;
            }
            Instant at = Instant.ofEpochSecond(stamps.path(i).asLong());
            if (!at.atZone(IdxSession.ZONE).toLocalDate().equals(sessionDate)) {
                continue;
            }
            JsonNode v = quote.path("volume").path(i);
            bars.add(new Bar(at, decimal(quote.path("open").path(i)), decimal(quote.path("high").path(i)),
                    decimal(quote.path("low").path(i)), close, v.isNumber() ? v.longValue() : null));
        }
        BigDecimal open = bars.isEmpty() ? null : bars.getFirst().open();
        JsonNode volume = meta.path("regularMarketVolume");
        return new Session(symbol, meta.path("currency").asText(null), sessionDate, price, previous, open,
                decimal(meta.path("regularMarketDayHigh")), decimal(meta.path("regularMarketDayLow")),
                volume.isNumber() ? volume.longValue() : null, marketTime, start, end, List.copyOf(bars));
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber() && Double.isFinite(node.asDouble())
                ? node.decimalValue().setScale(PRICE_SCALE, RoundingMode.HALF_UP)
                : null;
    }
}
