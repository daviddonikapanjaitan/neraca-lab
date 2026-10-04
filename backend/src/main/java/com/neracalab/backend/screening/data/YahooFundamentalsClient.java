package com.neracalab.backend.screening.data;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceProperties;
import com.neracalab.backend.price.provider.PacedHttpClient;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.price.provider.SymbolNotFoundException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Yahoo Finance as the source of the screening data. Unofficial APIs (no key; a session cookie
 * and a "crumb" token for the screener and quoteSummary), all requests through the shared
 * {@link PacedHttpClient} (1-2 s apart, together with the price ingestion).
 * <ul>
 *   <li>{@link #universe}: {@code POST /v1/finance/screener} - every equity of the exchange with price,
 *       market cap, volumes and headline ratios, 250 per page (IDX: about 840 listings, 4 requests)</li>
 *   <li>{@link #fundamentals}: {@code GET /v10/finance/quoteSummary} (financialData, defaultKeyStatistics,
 *       summaryDetail, assetProfile) plus {@code GET /ws/fundamentals-timeseries} (four fiscal years of
 *       annual figures) - 2 requests per stock</li>
 * </ul>
 */
@Component
public class YahooFundamentalsClient {

    private static final Logger log = LoggerFactory.getLogger(YahooFundamentalsClient.class);

    static final int PAGE_SIZE = 250;
    private static final int MAX_PAGES = 20;

    /** Annual time series types and the key they are stored under in {@code fundamental_snapshot.annual}. */
    static final Map<String, String> ANNUAL_TYPES = new LinkedHashMap<>();

    static {
        ANNUAL_TYPES.put("annualTotalRevenue", "revenue");
        ANNUAL_TYPES.put("annualNetIncomeCommonStockholders", "netIncome");
        ANNUAL_TYPES.put("annualOperatingIncome", "operatingIncome");
        ANNUAL_TYPES.put("annualGrossProfit", "grossProfit");
        ANNUAL_TYPES.put("annualEBIT", "ebit");
        ANNUAL_TYPES.put("annualEBITDA", "ebitda");
        ANNUAL_TYPES.put("annualInterestExpense", "interestExpense");
        ANNUAL_TYPES.put("annualDilutedEPS", "dilutedEps");
        ANNUAL_TYPES.put("annualOperatingCashFlow", "operatingCashFlow");
        ANNUAL_TYPES.put("annualCapitalExpenditure", "capex");
        ANNUAL_TYPES.put("annualFreeCashFlow", "freeCashFlow");
        ANNUAL_TYPES.put("annualTotalAssets", "totalAssets");
        ANNUAL_TYPES.put("annualTotalLiabilitiesNetMinorityInterest", "totalLiabilities");
        ANNUAL_TYPES.put("annualStockholdersEquity", "equity");
        ANNUAL_TYPES.put("annualTotalDebt", "totalDebt");
        ANNUAL_TYPES.put("annualWorkingCapital", "workingCapital");
        ANNUAL_TYPES.put("annualRetainedEarnings", "retainedEarnings");
        ANNUAL_TYPES.put("annualCashAndCashEquivalents", "cash");
        ANNUAL_TYPES.put("annualCurrentAssets", "currentAssets");
        ANNUAL_TYPES.put("annualCurrentLiabilities", "currentLiabilities");
        ANNUAL_TYPES.put("annualOrdinarySharesNumber", "shares");
    }

    private final PacedHttpClient http;
    private final JsonMapper json;
    private final String baseUrl;
    private volatile String crumb;

    public YahooFundamentalsClient(PacedHttpClient http, JsonMapper json, PriceProperties properties) {
        this.http = http;
        this.json = json;
        String url = properties.yahoo().baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ------------------------------------------------------------------ symbols

    /** Yahoo symbol of a listing: HRTA on IDX -> HRTA.JK. */
    public static String symbol(Exchange exchange, String ticker) {
        return switch (exchange) {
            case IDX -> ticker + ".JK";
        };
    }

    /** Yahoo screener exchange code. */
    static String screenerExchange(Exchange exchange) {
        return switch (exchange) {
            case IDX -> "JKT";
        };
    }

    /** Ticker of a Yahoo symbol of the exchange: BBCA.JK -> BBCA; null when the symbol is not of the exchange. */
    static String ticker(Exchange exchange, String symbol) {
        String suffix = switch (exchange) {
            case IDX -> ".JK";
        };
        if (symbol == null || !symbol.endsWith(suffix) || symbol.length() == suffix.length()) {
            return null;
        }
        String ticker = symbol.substring(0, symbol.length() - suffix.length());
        return ticker.matches("[A-Z0-9][A-Z0-9.-]*") && ticker.length() <= 20 ? ticker : null;
    }

    // ------------------------------------------------------------------ universe

    /** One listing of the screener with its market data. */
    public record ListingQuote(String symbol, String ticker, String name, String currency, LocalDate firstTradeDate,
                               Double price, Double marketCap, Double sharesOutstanding, Double avgVolume3m,
                               Double avgVolume10d, Double fiftyTwoWeekHigh, Double fiftyTwoWeekLow, Double trailingPe,
                               Double forwardPe, Double priceToBook, Double epsTtm, Double bookValuePerShare,
                               Double dividendYield, Instant lastTradeAt) {
    }

    /**
     * Every equity Yahoo lists on the exchange, largest market cap first.
     *
     * @throws RateLimitedException  HTTP 429
     * @throws PriceProviderException any other failure
     */
    public List<ListingQuote> universe(Exchange exchange) {
        List<ListingQuote> quotes = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        for (int page = 0; page < MAX_PAGES && page * PAGE_SIZE < total; page++) {
            String body = """
                    {"size":%d,"offset":%d,"sortField":"intradaymarketcap","sortType":"DESC","quoteType":"EQUITY",\
                    "query":{"operator":"AND","operands":[{"operator":"EQ","operands":["exchange","%s"]}]},\
                    "userId":"","userIdType":"guid"}""".formatted(PAGE_SIZE, page * PAGE_SIZE, screenerExchange(exchange));
            JsonNode result = withCrumb(c -> http.postJson(URI.create(baseUrl + "/v1/finance/screener?crumb="
                    + encode(c) + "&lang=en-US&region=ID&formatted=false"), body), "the screener")
                    .path("finance").path("result").path(0);
            if (result.isMissingNode() || result.isNull()) {
                throw new PriceProviderException("Yahoo Finance screener answered without a result");
            }
            total = result.path("total").asInt(0);
            JsonNode items = result.path("quotes");
            for (JsonNode q : items) {
                ListingQuote quote = quote(exchange, q);
                if (quote != null) {
                    quotes.add(quote);
                }
            }
            if (items.isEmpty()) {
                break;
            }
        }
        log.info("Yahoo screener: {} {} listings", quotes.size(), exchange.code());
        return quotes;
    }

    static ListingQuote quote(Exchange exchange, JsonNode q) {
        String symbol = q.path("symbol").asString(null);
        String ticker = ticker(exchange, symbol);
        if (ticker == null || !"EQUITY".equals(q.path("quoteType").asString("EQUITY"))) {
            return null;
        }
        String name = text(q, "longName");
        if (name == null) {
            name = text(q, "shortName");
        }
        long firstTrade = q.path("firstTradeDateMilliseconds").asLong(0);
        long marketTime = q.path("regularMarketTime").asLong(0);
        return new ListingQuote(symbol, ticker, name == null ? ticker : name, text(q, "currency"),
                firstTrade > 0 ? Instant.ofEpochMilli(firstTrade).atOffset(ZoneOffset.UTC).toLocalDate() : null,
                number(q, "regularMarketPrice"), number(q, "marketCap"), number(q, "sharesOutstanding"),
                number(q, "averageDailyVolume3Month"), number(q, "averageDailyVolume10Day"),
                number(q, "fiftyTwoWeekHigh"), number(q, "fiftyTwoWeekLow"), number(q, "trailingPE"),
                number(q, "forwardPE"), number(q, "priceToBook"), number(q, "epsTrailingTwelveMonths"),
                number(q, "bookValue"), number(q, "trailingAnnualDividendYield"),
                marketTime > 0 ? Instant.ofEpochSecond(marketTime) : null);
    }

    // ------------------------------------------------------------------ fundamentals

    /**
     * Annual figures of four fiscal years, oldest first. {@code values} has one list per key of
     * {@link #ANNUAL_TYPES} (aligned with {@code years}, null = not reported).
     */
    public record AnnualFigures(List<String> years, Map<String, List<Double>> values) {

        public static AnnualFigures empty() {
            return new AnnualFigures(List.of(), Map.of());
        }

        /** Values of a key, oldest first (empty when unknown). */
        public List<Double> series(String key) {
            List<Double> s = values.get(key);
            return s == null ? List.of() : s;
        }

        /** Latest non-null value of a key. */
        public Double latest(String key) {
            List<Double> s = series(key);
            for (int i = s.size() - 1; i >= 0; i--) {
                if (s.get(i) != null) {
                    return s.get(i);
                }
            }
            return null;
        }
    }

    /** Fundamentals of one stock. Ratios are fractions (0.25 = 25%), debt / equity included. */
    public record Fundamentals(String sector, String industry, Double revenueTtm, Double netIncomeTtm, Double ebitdaTtm,
                               Double grossMargin, Double operatingMargin, Double profitMargin, Double ebitdaMargin,
                               Double returnOnEquity, Double returnOnAssets, Double debtToEquity, Double currentRatio,
                               Double quickRatio, Double totalCash, Double totalDebt, Double freeCashFlow,
                               Double operatingCashFlow, Double revenueGrowth, Double earningsGrowth,
                               Double enterpriseValue, Double evToEbitda, Double evToRevenue, Double pegRatio,
                               Double beta, Double insiderOwnership, Double institutionOwnership, AnnualFigures annual) {
    }

    /**
     * quoteSummary and annual time series of one stock.
     *
     * @throws SymbolNotFoundException Yahoo has no data for the symbol
     * @throws RateLimitedException    HTTP 429
     * @throws PriceProviderException  any other failure
     */
    public Fundamentals fundamentals(Exchange exchange, String ticker) {
        String symbol = symbol(exchange, ticker);
        JsonNode root = withCrumb(c -> http.get(URI.create(baseUrl + "/v10/finance/quoteSummary/" + encode(symbol)
                + "?modules=financialData%2CdefaultKeyStatistics%2CsummaryDetail%2CassetProfile&formatted=false&crumb="
                + encode(c))), symbol);
        JsonNode result = root.path("quoteSummary").path("result").path(0);
        if (result.isMissingNode() || result.isNull()) {
            throw new SymbolNotFoundException("Yahoo Finance has no fundamentals for " + symbol);
        }
        AnnualFigures annual = annual(symbol);
        return fundamentals(result, annual);
    }

    static Fundamentals fundamentals(JsonNode result, AnnualFigures annual) {
        JsonNode fd = result.path("financialData");
        JsonNode ks = result.path("defaultKeyStatistics");
        JsonNode sd = result.path("summaryDetail");
        JsonNode ap = result.path("assetProfile");
        Double debtToEquity = number(fd, "debtToEquity");
        Double beta = number(sd, "beta");
        if (beta == null) {
            beta = number(ks, "beta");
        }
        return new Fundamentals(text(ap, "sector"), text(ap, "industry"),
                number(fd, "totalRevenue"), number(ks, "netIncomeToCommon"), number(fd, "ebitda"),
                nonZero(number(fd, "grossMargins")), nonZero(number(fd, "operatingMargins")),
                number(fd, "profitMargins"), nonZero(number(fd, "ebitdaMargins")),
                number(fd, "returnOnEquity"), number(fd, "returnOnAssets"),
                debtToEquity == null ? null : debtToEquity / 100d,
                number(fd, "currentRatio"), number(fd, "quickRatio"), number(fd, "totalCash"), number(fd, "totalDebt"),
                number(fd, "freeCashflow"), number(fd, "operatingCashflow"), number(fd, "revenueGrowth"),
                number(fd, "earningsGrowth"), number(ks, "enterpriseValue"), number(ks, "enterpriseToEbitda"),
                number(ks, "enterpriseToRevenue"), number(ks, "pegRatio"), beta, number(ks, "heldPercentInsiders"),
                number(ks, "heldPercentInstitutions"), annual);
    }

    private AnnualFigures annual(String symbol) {
        long now = Instant.now().getEpochSecond();
        long sixYearsAgo = now - 6L * 366 * 24 * 3600;
        URI uri = URI.create(baseUrl + "/ws/fundamentals-timeseries/v1/finance/timeseries/" + encode(symbol)
                + "?type=" + String.join("%2C", ANNUAL_TYPES.keySet()) + "&period1=" + sixYearsAgo + "&period2=" + now);
        PacedHttpClient.Response response = http.get(uri);
        if (response.status() == 429) {
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 for the annual figures of " + symbol,
                    response.retryAfter());
        }
        if (response.status() != 200) {
            // the quoteSummary already worked: keep the stock, without history
            log.warn("Yahoo annual figures of {}: HTTP {}", symbol, response.status());
            return AnnualFigures.empty();
        }
        return annual(parse(response.body(), symbol));
    }

    /** Parses a time series response; keeps the latest four fiscal years. */
    static AnnualFigures annual(JsonNode root) {
        Map<String, Map<String, Double>> byKey = new LinkedHashMap<>();
        TreeSet<String> years = new TreeSet<>();
        for (JsonNode series : root.path("timeseries").path("result")) {
            String type = series.path("meta").path("type").path(0).asString(null);
            String key = type == null ? null : ANNUAL_TYPES.get(type);
            if (key == null) {
                continue;
            }
            for (JsonNode point : series.path(type)) {
                String date = point.path("asOfDate").asString(null);
                JsonNode raw = point.path("reportedValue").path("raw");
                if (date == null || !raw.isNumber()) {
                    continue;
                }
                years.add(date);
                byKey.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(date, raw.asDouble());
            }
        }
        List<String> kept = new ArrayList<>(years);
        if (kept.size() > 4) {
            kept = kept.subList(kept.size() - 4, kept.size());
        }
        Map<String, List<Double>> values = new LinkedHashMap<>();
        for (String key : ANNUAL_TYPES.values()) {
            Map<String, Double> points = byKey.get(key);
            if (points == null) {
                continue;
            }
            List<Double> list = new ArrayList<>();
            for (String year : kept) {
                list.add(points.get(year));
            }
            values.put(key, list);
        }
        return new AnnualFigures(List.copyOf(kept), values);
    }

    // ------------------------------------------------------------------ crumb

    @FunctionalInterface
    private interface CrumbRequest {
        PacedHttpClient.Response send(String crumb);
    }

    /**
     * Sends a crumb-authenticated request; on "Invalid Crumb" (HTTP 401 / 403) a fresh session and
     * crumb are fetched and the request is sent once more.
     */
    private JsonNode withCrumb(CrumbRequest request, String what) {
        String current = crumb();
        PacedHttpClient.Response response = request.send(current);
        if (response.status() == 401 || response.status() == 403) {
            log.info("Yahoo Finance rejected the crumb for {} (HTTP {}); refreshing the session", what, response.status());
            crumb = null;
            response = request.send(crumb());
        }
        if (response.status() == 429) {
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 Too Many Requests for " + what,
                    response.retryAfter());
        }
        JsonNode root = parse(response.body(), what);
        if (response.status() == 404) {
            String description = root.path("quoteSummary").path("error").path("description").asString("HTTP 404");
            throw new SymbolNotFoundException("Yahoo Finance has no data for " + what + ": " + description);
        }
        if (response.status() != 200) {
            throw new PriceProviderException("Yahoo Finance answered HTTP " + response.status() + " for " + what);
        }
        return root;
    }

    private synchronized String crumb() {
        if (crumb != null) {
            return crumb;
        }
        // the session cookie (fc.yahoo.com answers 404 but sets it), then the crumb bound to it
        http.get(URI.create("https://fc.yahoo.com/"));
        PacedHttpClient.Response response = http.get(URI.create(baseUrl + "/v1/test/getcrumb"));
        if (response.status() == 429) {
            throw new RateLimitedException("Yahoo Finance answered HTTP 429 for the session crumb", response.retryAfter());
        }
        String body = response.body() == null ? "" : response.body().trim();
        if (response.status() != 200 || body.isEmpty() || body.length() > 64 || body.contains("<")) {
            throw new PriceProviderException("Yahoo Finance gave no session crumb (HTTP " + response.status() + ")");
        }
        crumb = body;
        return body;
    }

    // ------------------------------------------------------------------ parsing helpers

    private JsonNode parse(String body, String what) {
        if (body == null || body.isBlank()) {
            throw new PriceProviderException("Yahoo Finance answered with an empty body for " + what);
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw new PriceProviderException("Yahoo Finance answered with a body that is not JSON for " + what, e);
        }
    }

    /** A number field; also reads {"raw": n} objects (formatted responses). Null when absent or not finite. */
    static Double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isObject()) {
            value = value.path("raw");
        }
        if (!value.isNumber()) {
            return null;
        }
        double d = value.asDouble();
        return Double.isFinite(d) ? d : null;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            return null;
        }
        String s = value.asString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Yahoo reports 0.0 for margins it does not know (banks: gross / EBITDA margin). */
    private static Double nonZero(Double value) {
        return value == null || value == 0d ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
