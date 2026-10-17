package com.neracalab.backend.technical.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.technical.TechnicalService;
import com.neracalab.backend.technical.TechnicalService.ListingNotFoundException;
import com.neracalab.backend.technical.TechnicalService.MonthlySeriesException;
import com.neracalab.backend.technical.TechnicalService.NoPricesException;
import com.neracalab.backend.technical.TechnicalService.RefreshStatus;

import tools.jackson.databind.JsonNode;

/**
 * Technical analysis charts (Companies > Technicals). Read-only, deterministic data: no model is called. The preset
 * summaries give a stance with buy / sell prices by fixed rules over the indicators; everything else is data. Exchange
 * and ticker codes are case-insensitive; the ticker IHSG (or ^JKSE) is the exchange's index. Besides an exchange, the
 * "exchange" may be a market: FOREX (USDIDR, USDJPY) or COMMODITY (BRENT, NATURAL_GAS, GOLD, COPPER, PALM_OIL, CORN,
 * ...: the catalog market_instrument). Series are compact arrays of {@code [time, value]}, time in seconds since the
 * epoch at 00:00 UTC of the trading day.
 * <ul>
 *   <li>{@code GET /api/v1/technical/markets} - the exchanges and markets of the page's dropdown</li>
 *   <li>{@code GET /api/v1/technical/tickers?exchange=IDX&query=} - ticker search (code and company name; a market:
 *       its currencies / commodities)</li>
 *   <li>{@code GET /api/v1/technical/{exchange}/{ticker}/ohlcv?interval=D|W|M&range=|from=&to=&adjustment=split|total}</li>
 *   <li>{@code GET /api/v1/technical/{exchange}/{ticker}/indicators?interval=&range=|from=&to=&set=sma50,sma200,rsi14,...}</li>
 *   <li>{@code GET /api/v1/technical/{exchange}/{ticker}/levels} - zones, retracements, swings, volume profile, 52-week / ATH</li>
 *   <li>{@code GET /api/v1/technical/{exchange}/{ticker}/snapshot} - the agent-ready IndicatorSnapshot (versioned JSON)</li>
 *   <li>{@code GET /api/v1/technical/{exchange}/{ticker}/summary} - the preset summaries (stance, buy / sell / stop,
 *       thesis by fixed rules)</li>
 *   <li>{@code GET /api/v1/technical/{exchange}/{ticker}/events} - corporate events for chart markers</li>
 *   <li>{@code GET /api/v1/technical/market?exchange=IDX&range=} - index regime and breadth (no macro data: the
 *       Macro page has them)</li>
 *   <li>{@code GET / PUT /api/v1/technical/settings} - the user's last chart settings</li>
 *   <li>{@code POST /api/v1/technical/refresh?exchange=IDX&ticker=&forceEvents=} (INGESTION) - refresh the index, the
 *       corporate events and the snapshots in the background; {@code GET} the same path for its status</li>
 * </ul>
 * Access: COMPANIES (the refresh: INGESTION).
 */
@RestController
@RequestMapping(path = "/api/v1/technical", produces = MediaType.APPLICATION_JSON_VALUE)
public class TechnicalController {

    private final TechnicalService service;

    public TechnicalController(TechnicalService service) {
        this.service = service;
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/markets")
    public List<Map<String, Object>> markets() {
        return service.markets();
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/tickers")
    public List<Map<String, Object>> tickers(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                             @RequestParam(name = "query", required = false) String query,
                                             @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return service.tickers(exchange, query, limit);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/ohlcv")
    public Map<String, Object> ohlcv(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker,
                                     @RequestParam(name = "interval", defaultValue = "D") String interval,
                                     @RequestParam(name = "range", required = false) String range,
                                     @RequestParam(name = "from", required = false) String from,
                                     @RequestParam(name = "to", required = false) String to,
                                     @RequestParam(name = "adjustment", required = false) String adjustment,
                                     @RequestParam(name = "adjusted", required = false) Boolean adjusted) {
        LocalDate[] window = window(exchange, ticker, range, from, to);
        return service.ohlcv(exchange, ticker, interval, window[0], window[1], adjustment(adjustment, adjusted));
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/indicators")
    public Map<String, Object> indicators(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker,
                                          @RequestParam(name = "interval", defaultValue = "D") String interval,
                                          @RequestParam(name = "range", required = false) String range,
                                          @RequestParam(name = "from", required = false) String from,
                                          @RequestParam(name = "to", required = false) String to,
                                          @RequestParam(name = "set") String set,
                                          @RequestParam(name = "adjustment", required = false) String adjustment,
                                          @RequestParam(name = "adjusted", required = false) Boolean adjusted) {
        LocalDate[] window = window(exchange, ticker, range, from, to);
        return service.indicators(exchange, ticker, interval, window[0], window[1], set, adjustment(adjustment, adjusted));
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/levels")
    public Map<String, Object> levels(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker) {
        return service.levels(exchange, ticker);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/snapshot")
    public ResponseEntity<String> snapshot(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.snapshot(exchange, ticker));
    }

    /** The preset summaries (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller): stance, buy / sell / stop, thesis. */
    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/summary")
    public Map<String, Object> summary(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker) {
        return service.summary(exchange, ticker);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/events")
    public Map<String, Object> events(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker) {
        return service.events(exchange, ticker);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/market")
    public Map<String, Object> market(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                      @RequestParam(name = "range", defaultValue = "1Y") String range) {
        LocalDate from = rangeStart(range, LocalDate.now());
        return service.market(exchange, from);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/settings")
    public ResponseEntity<JsonNode> settings(AuthenticatedUser user) {
        JsonNode settings = service.chartSettings(user.userId());
        return settings == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(settings);
    }

    @RequiresPermission(Permission.COMPANIES)
    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode saveSettings(@RequestBody JsonNode settings, AuthenticatedUser user) {
        return service.saveChartSettings(user.userId(), settings);
    }

    @RequiresPermission(Permission.INGESTION)
    @PostMapping("/refresh")
    public ResponseEntity<RefreshStatus> refresh(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                                 @RequestParam(name = "ticker", required = false) String ticker,
                                                 @RequestParam(name = "forceEvents", defaultValue = "false") boolean forceEvents) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startRefresh(exchange, ticker, forceEvents));
    }

    @RequiresPermission(Permission.INGESTION)
    @GetMapping("/refresh")
    public RefreshStatus refreshStatus() {
        return service.refreshStatus();
    }

    // ------------------------------------------------------------------ parameters

    /** {@code from} / {@code to} as given; else {@code range} counted back from the listing's last trading day. */
    private LocalDate[] window(String exchange, String ticker, String range, String from, String to) {
        LocalDate f = date("from", from);
        LocalDate t = date("to", to);
        if (f == null && range != null && !range.isBlank()) {
            String last = (String) service.tickers(exchange, ticker, 50).stream()
                    .filter(m -> ticker.trim().equalsIgnoreCase((String) m.get("ticker"))).findFirst()
                    .map(m -> m.get("lastDate")).orElse(null);
            f = rangeStart(range, last == null ? LocalDate.now() : LocalDate.parse(last));
        }
        if (f != null && t != null && f.isAfter(t)) {
            throw new IllegalArgumentException("from (" + f + ") is after to (" + t + ")");
        }
        return new LocalDate[] {f, t};
    }

    /** Start of a range ending at {@code end}: 1M 3M 6M YTD 1Y 3Y 5Y 10Y, MAX / ALL = no start. */
    static LocalDate rangeStart(String range, LocalDate end) {
        String r = range == null ? "MAX" : range.trim().toUpperCase(Locale.ROOT);
        return switch (r) {
            case "1M" -> end.minusMonths(1);
            case "3M" -> end.minusMonths(3);
            case "6M" -> end.minusMonths(6);
            case "YTD" -> end.withDayOfYear(1);
            case "1Y" -> end.minusYears(1);
            case "3Y" -> end.minusYears(3);
            case "5Y" -> end.minusYears(5);
            case "10Y" -> end.minusYears(10);
            case "MAX", "ALL", "" -> null;
            default -> throw new IllegalArgumentException("Unknown range '" + range + "': use 1M, 3M, 6M, YTD, 1Y, 3Y, 5Y, 10Y or MAX");
        };
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date like 2026-01-31, not '" + value + "'");
        }
    }

    /**
     * {@code adjustment=split} (default: as traded since the last split) or {@code total} (dividends reinvested).
     * {@code adjusted=true} is accepted as {@code split}; {@code adjusted=false} (raw, unadjusted prices) is refused: the
     * price provider delivers split-adjusted prices only, so raw prices of before a split are not stored.
     */
    private static String adjustment(String adjustment, Boolean adjusted) {
        if (adjustment != null) {
            return adjustment;
        }
        if (Boolean.FALSE.equals(adjusted)) {
            throw new IllegalArgumentException("Raw (unadjusted) prices are not stored: the provider's prices are "
                    + "split-adjusted. Use adjustment=split (as traded since the last split) or adjustment=total");
        }
        return "split";
    }

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(UnsupportedExchangeException.class)
    ProblemDetail unsupportedExchange(UnsupportedExchangeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported exchange");
        problem.setProperty("supportedExchanges", e.supported());
        return problem;
    }

    @ExceptionHandler(InvalidTickerException.class)
    ProblemDetail invalidTicker(InvalidTickerException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid ticker");
        return problem;
    }

    @ExceptionHandler(ListingNotFoundException.class)
    ProblemDetail notFound(ListingNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle(e.title());
        return problem;
    }

    /** Levels, the snapshot and the summaries of a monthly series (coal): 422, the chart itself works. */
    @ExceptionHandler(MonthlySeriesException.class)
    ProblemDetail monthly(MonthlySeriesException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Monthly series");
        return problem;
    }

    @ExceptionHandler(NoPricesException.class)
    ProblemDetail noPrices(NoPricesException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("No prices");
        return problem;
    }
}
