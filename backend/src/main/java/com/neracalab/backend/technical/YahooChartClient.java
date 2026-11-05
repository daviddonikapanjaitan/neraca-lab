package com.neracalab.backend.technical;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neracalab.backend.price.PriceProperties;
import com.neracalab.backend.price.provider.PacedHttpClient;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.price.provider.SymbolNotFoundException;
import com.neracalab.backend.price.provider.YahooSessionClose;
import com.neracalab.backend.technical.TechnicalRepository.Event;
import com.neracalab.backend.technical.engine.Candle;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Yahoo Finance chart API for what the technical analysis needs beyond the daily prices: the bars of an index (IHSG =
 * {@code ^JKSE}) and the dividends and splits of a listing ({@code events=div,split}; one monthly-interval request over
 * the whole history). Unofficial and unauthenticated like the price provider: requests go through the same
 * {@link PacedHttpClient}, so they are paced together with the price ingestion, and a failure is reported, never
 * guessed around.
 */
@Component
public class YahooChartClient {

    static final String SOURCE = "yahoo";

    private static final Logger log = LoggerFactory.getLogger(YahooChartClient.class);

    private final PacedHttpClient http;
    private final JsonMapper json;
    private final String baseUrl;

    public YahooChartClient(PacedHttpClient http, JsonMapper json, PriceProperties properties) {
        this.http = http;
        this.json = json;
        String url = properties.yahoo().baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Daily bars of {@code symbol} from {@code from} to {@code to} (inclusive), oldest first, without empty days. */
    public List<Candle> dailyBars(String symbol, ZoneId zone, LocalDate from, LocalDate to) {
        return dailyBars(symbol, zone, from, to, false);
    }

    /**
     * Daily bars as {@link #dailyBars(String, ZoneId, LocalDate, LocalDate)}; with {@code sessionClose} a last bar
     * Yahoo has not given its close yet takes the session's close of the quote ({@link YahooSessionClose}) instead of
     * being dropped. Only for a caller that drops the bars after the last completed trading day itself (the index
     * ingestion), since the quote of a session still trading is its current price.
     */
    public List<Candle> dailyBars(String symbol, ZoneId zone, LocalDate from, LocalDate to, boolean sessionClose) {
        return dailyBars(symbol, zone, from, to, sessionClose, false).bars();
    }

    /**
     * Daily bars without the ones Yahoo sends inconsistent: a close more than {@value #INCONSISTENT_CLOSE} (a fraction)
     * outside the day's own high-low range as sent (both present). Yahoo's USD/IDR (IDR=X) of 2026-11-03 closed at
     * 17,472.20 under its low of 17,786.60, of 2026-11-04 at 17,536.10 under its low of 17,957.60, while the ECB rate
     * stayed at 18,137 / 18,129 and the next day closed at 18,049: a rupiah 3.5 % stronger for two days that never
     * was, which the spike rule ({@code market.BarCleaner}, 3 %) did not catch (the two days do not return within half
     * of it). Such a bar is dropped - its close is not known - and its date reported, so a stored copy can be deleted.
     * Only judged when the range lies on the scale of the previous close (a range of 9.0 - 9.0 after a close of 9,300 is
     * the range's error, not the close's) - so never the first bar of a response.
     * For the Forex pairs only ({@code InstrumentIngestionService}): over the whole Yahoo history (2001 - 2026) a close
     * more than 1 % outside its range is rare for currency pairs (IDR=X 43 of 6,404 days, PHP=X 26, INR=X 4, CNY=X /
     * KRW=X / EURUSD=X 1, JPY=X / MYR=X / AUDJPY=X 0), but common for futures, whose settlement lies outside the
     * electronic session's range (ZW=F 380, ZM=F 248, ZC=F 279 of ~6,450) - there it is no error.
     */
    public Bars dailyBarsChecked(String symbol, ZoneId zone, LocalDate from, LocalDate to) {
        return dailyBars(symbol, zone, from, to, false, true);
    }

    /** A close more than this fraction under the day's low or over its high is not a close of that day. */
    static final double INCONSISTENT_CLOSE = 0.01;

    /** Daily bars, and the dates of the bars dropped as inconsistent ({@link #dailyBarsChecked}). */
    public record Bars(List<Candle> bars, List<LocalDate> inconsistent) {
    }

    private Bars dailyBars(String symbol, ZoneId zone, LocalDate from, LocalDate to, boolean sessionClose,
                           boolean dropInconsistent) {
        List<LocalDate> inconsistent = new ArrayList<>();
        JsonNode result = chart(symbol, from.atStartOfDay(zone).toEpochSecond(),
                to.plusDays(1).atStartOfDay(zone).toEpochSecond(), "1d", "");
        ZoneId z = zone(result, zone);
        JsonNode timestamps = result.path("timestamp");
        JsonNode quote = result.path("indicators").path("quote").path(0);
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(z).toLocalDate();
            Double close = number(quote.path("close").path(i));
            Double volume = number(quote.path("volume").path(i));
            if (close == null && sessionClose && i == timestamps.size() - 1) {
                Optional<YahooSessionClose.Close> session = YahooSessionClose.of(result.path("meta"), z, date,
                        decimal(quote.path("high").path(i)), decimal(quote.path("low").path(i)));
                if (session.isPresent()) {
                    close = session.get().price().doubleValue();
                    if (volume == null && session.get().volume() != null) {
                        volume = session.get().volume().doubleValue();
                    }
                    log.info("{} {}: daily bar without a close, the session's close {} taken from the quote", symbol,
                            date, session.get().price().toPlainString());
                }
            }
            if (close == null || close <= 0) {
                continue;   // holiday placeholder
            }
            Double sentHigh = number(quote.path("high").path(i));
            Double sentLow = number(quote.path("low").path(i));
            // judged only when the range itself is on the scale of the previous close (within half to double): IDR=X of
            // 2006-06-27 sends open, high and low of 9.0 beside a close of 9,241 after 9,300 - the range is wrong there,
            // not the close
            Double previous = bars.isEmpty() ? null : bars.getLast().close();
            boolean plausibleRange = previous != null && sentLow != null && sentHigh != null
                    && sentLow >= previous * 0.5 && sentHigh <= previous * 2;
            if (dropInconsistent && plausibleRange && sentLow > 0
                    && (close < sentLow * (1 - INCONSISTENT_CLOSE) || close > sentHigh * (1 + INCONSISTENT_CLOSE))) {
                log.warn("{} {}: close {} outside the day's range {} - {}: dropped", symbol, date, close, sentLow,
                        sentHigh);
                inconsistent.add(date);
                continue;
            }
            double open = orElse(number(quote.path("open").path(i)), close);
            double high = Math.max(orElse(number(quote.path("high").path(i)), close), Math.max(open, close));
            double low = Math.min(orElse(number(quote.path("low").path(i)), close), Math.min(open, close));
            bars.add(Candle.daily(date, round4(open), round4(high), round4(low), round4(close), round4(close),
                    volume == null ? 0 : volume));
        }
        // Yahoo may send the same day twice (an intraday row of today): keep the last
        List<Candle> unique = new ArrayList<>();
        for (Candle bar : bars) {
            if (!unique.isEmpty() && unique.getLast().date().equals(bar.date())) {
                unique.set(unique.size() - 1, bar);
            } else {
                unique.add(bar);
            }
        }
        // a date dropped once and sent again consistently (Yahoo's intraday row of today) is a bar, not a bad one
        List<LocalDate> kept = unique.stream().map(Candle::date).toList();
        return new Bars(unique, inconsistent.stream().filter(d -> !kept.contains(d)).distinct().toList());
    }

    /** Every dividend and split Yahoo reports for {@code symbol}, oldest first. */
    public List<Event> events(String symbol, ZoneId zone) {
        JsonNode result = chart(symbol, 0, Instant.now().getEpochSecond() + 86_400, "1mo", "&events=div%2Csplit");
        ZoneId z = zone(result, zone);
        String url = "https://finance.yahoo.com/quote/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "/history";
        String currency = result.path("meta").path("currency").asString("");
        List<Event> events = new ArrayList<>();
        for (JsonNode div : result.path("events").path("dividends")) {
            Double amount = number(div.path("amount"));
            if (amount == null) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(div.path("date").asLong()).atZone(z).toLocalDate();
            BigDecimal value = BigDecimal.valueOf(amount).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
            events.add(new Event(date, "DIVIDEND", value, "Cash dividend " + value.toPlainString() + " " + currency
                    + " per share (ex-date)", SOURCE, url));
        }
        for (JsonNode split : result.path("events").path("splits")) {
            Double numerator = number(split.path("numerator"));
            Double denominator = number(split.path("denominator"));
            if (numerator == null || denominator == null || numerator <= 0 || denominator <= 0) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(split.path("date").asLong()).atZone(z).toLocalDate();
            BigDecimal ratio = BigDecimal.valueOf(numerator / denominator).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
            boolean reverse = numerator < denominator;
            events.add(new Event(date, reverse ? "REVERSE_SPLIT" : "STOCK_SPLIT", ratio,
                    (reverse ? "Reverse split " : "Stock split ") + split.path("splitRatio").asString(
                            fmt(numerator) + ":" + fmt(denominator)) + " (new:old shares)", SOURCE, url));
        }
        events.sort(java.util.Comparator.comparing(Event::date));
        return events;
    }

    private JsonNode chart(String symbol, long period1, long period2, String interval, String extra) {
        URI uri = URI.create(baseUrl + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?period1=" + period1 + "&period2=" + period2 + "&interval=" + interval + extra);
        PacedHttpClient.Response response = http.get(uri);
        if (response.status() == 429) {
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests for " + symbol, response.retryAfter());
        }
        JsonNode root;
        try {
            root = json.readTree(response.body() == null ? "" : response.body());
        } catch (JacksonException e) {
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status() + " with a body that is not JSON for " + symbol, e);
        }
        JsonNode error = root.path("chart").path("error");
        if (response.status() == 404 || (!error.isMissingNode() && !error.isNull())) {
            throw new SymbolNotFoundException("Yahoo Finance has no data for " + symbol + ": "
                    + error.path("description").asString("HTTP " + response.status()));
        }
        JsonNode result = root.path("chart").path("result").path(0);
        if (response.status() != 200 || result.isMissingNode() || result.isNull()) {
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status() + " without a chart for " + symbol);
        }
        return result;
    }

    private static ZoneId zone(JsonNode result, ZoneId fallback) {
        String id = result.path("meta").path("exchangeTimezoneName").asString("");
        try {
            return id.isBlank() ? fallback : ZoneId.of(id);
        } catch (java.time.DateTimeException e) {
            return fallback;
        }
    }

    private static Double number(JsonNode node) {
        return node != null && node.isNumber() ? node.doubleValue() : null;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node != null && node.isNumber()
                ? node.decimalValue().setScale(YahooSessionClose.PRICE_SCALE, RoundingMode.HALF_UP)
                : null;
    }

    private static double orElse(Double value, double fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
