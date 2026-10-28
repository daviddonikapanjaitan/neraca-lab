package com.neracalab.backend.ownership.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.ownership.onepercent.OnePercentDigest;
import com.neracalab.backend.ownership.onepercent.OnePercentDigest.Digest;
import com.neracalab.backend.ownership.onepercent.OnePercentParser;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Entity;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Filter;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.MovementPage;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.MovementRow;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.ReportPage;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.ReportRow;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Sort;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.TickerRow;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.TopInvestor;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository.Transaction;

/**
 * Companies > Ownership (>=1%) (permission COMPANIES): KSEI's monthly lists of the shareholders of more than 1% of
 * every IDX company (uploaded on Ingestion > Excel Documents (RAG)): who holds what at each month end, by type of
 * investor, who bought or sold since the month before, who is new above 1% and who is no longer, which names changed,
 * and every company an investor holds.
 * <ul>
 *   <li>{@code GET /api/v1/ownership/one-percent[?tickers=BBCA,ADRO&holder=lo kheng hong&transaction=ALL|BUY|SELL
 *       |CHANGED|ENTERED|EXITED|CROSSED|RENAMED|ACTIVITY&status=L|A&group=INDIVIDUAL|CORPORATE|FUND|FINANCIAL
 *       |GOVERNMENT|OTHER&from=2026-02-01&to=2026-09-30&sort=DATE|CHANGE_DESC|CHANGE_ASC|POINTS_DESC|POINTS_ASC
 *       &limit=100&offset=0]} - one page of the investors of the lists, latest month first or by their change:
 *       {@code { total, limit, offset, rows }}</li>
 *   <li>{@code GET /api/v1/ownership/one-percent/options} - the latest list with its highlights, the dates stored,
 *       every company of the lists, the filter and sort options</li>
 *   <li>{@code GET /api/v1/ownership/one-percent/highlights?date=2026-08-31} - the list of a month with its largest
 *       buys and sells, the investors new above 1% and no longer above it, the names that changed and the investors in
 *       the most companies (404 when that date is not stored)</li>
 *   <li>{@code GET /api/v1/ownership/one-percent/holders?ticker=ADRO[&from&to]} - one company: every investor in every
 *       month, and the digest the AI analysis reads</li>
 *   <li>{@code GET /api/v1/ownership/one-percent/entities?q=lo kheng hong} - the investors whose name contains the
 *       words, each with every company it holds</li>
 *   <li>{@code GET /api/v1/ownership/one-percent/reports} - the stored lists</li>
 * </ul>
 */
@RestController
@RequiresPermission(Permission.COMPANIES)
@RequestMapping(path = "/api/v1/ownership/one-percent", produces = MediaType.APPLICATION_JSON_VALUE)
public class OnePercentController {

    /** Most tickers in one request. */
    static final int MAX_TICKERS = 50;
    /** Longest investor name searched. */
    static final int MAX_HOLDER_CHARS = 100;
    /** Movements of each kind in a month's highlights. */
    static final int HIGHLIGHTS = 10;
    /** Investors in the most companies in a month's highlights. */
    static final int TOP_INVESTORS = 10;

    public record Option(String code, String label) {
    }

    /**
     * The movements of one month.
     *
     * @param report       the list of the month (its counts)
     * @param largestBuys  its largest buys (percentage points, then shares)
     * @param largestSells its largest sells
     * @param entered      the investors new above 1% that month, largest first
     * @param exited       the investors of the month before no longer above 1%, largest first
     * @param renamed      the holdings listed under another name than the month before (renamed or moved)
     * @param topInvestors the investors listed for the most companies that month
     */
    public record Highlights(ReportRow report, List<MovementRow> largestBuys, List<MovementRow> largestSells,
                             List<MovementRow> entered, List<MovementRow> exited, List<MovementRow> renamed,
                             List<TopInvestor> topInvestors) {
    }

    /**
     * @param latest     the latest list stored; null when none
     * @param dates      every date stored, latest first
     * @param tickers    every company of the lists
     * @param highlights the latest list's highlights; null when none is stored
     */
    public record Options(ReportRow latest, List<LocalDate> dates, List<TickerRow> tickers, List<Option> transactions,
                          List<Option> statuses, List<Option> groups, List<Option> sorts, Highlights highlights) {
    }

    /** One company: its investors in every month (latest first), and its digest. */
    public record Company(String exchange, String ticker, String issuerName, Digest digest, List<MovementRow> rows) {
    }

    private final OnePercentRepository repository;

    public OnePercentController(OnePercentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public MovementPage movements(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                  @RequestParam(name = "tickers", required = false) String tickers,
                                  @RequestParam(name = "holder", required = false) String holder,
                                  @RequestParam(name = "transaction", required = false) String transaction,
                                  @RequestParam(name = "status", required = false) String status,
                                  @RequestParam(name = "group", required = false) String group,
                                  @RequestParam(name = "from", required = false) String from,
                                  @RequestParam(name = "to", required = false) String to,
                                  @RequestParam(name = "sort", required = false) String sort,
                                  @RequestParam(name = "limit", defaultValue = "100") int limit,
                                  @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        Transaction t = Transaction.of(transaction).orElseThrow(() -> new InvalidRequestException(
                "Unknown transaction '" + transaction + "'; use ALL, BUY, SELL, CHANGED, ENTERED, EXITED, CROSSED, "
                        + "RENAMED or ACTIVITY"));
        Sort order = Sort.of(sort).orElseThrow(() -> new InvalidRequestException(
                "Unknown sort '" + sort + "'; use DATE, CHANGE_DESC, CHANGE_ASC, POINTS_DESC or POINTS_ASC"));
        LocalDate first = date(from, "from");
        LocalDate last = date(to, "to");
        if (first != null && last != null && first.isAfter(last)) {
            throw new InvalidRequestException("The first date " + first + " is after the last date " + last);
        }
        Filter filter = new Filter(Exchange.of(exchange).code(), tickers(tickers), holder(holder), t, status(status),
                group(group), first, last);
        return repository.movements(filter, order, Math.clamp(limit, 1, 500), offset);
    }

    @GetMapping("/options")
    public Options options(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange) {
        String code = Exchange.of(exchange).code();
        List<ReportRow> latest = repository.reports(code, 1, 0).reports();
        ReportRow report = latest.isEmpty() ? null : latest.getFirst();
        return new Options(report, repository.dates(code), repository.tickers(code),
                List.of(new Option("ALL", "All"), new Option("BUY", "Buy"), new Option("SELL", "Sell"),
                        new Option("CHANGED", "Buy or sell"), new Option("ENTERED", "New above 1%"),
                        new Option("EXITED", "No longer above 1%"), new Option("CROSSED", "New or no longer above 1%"),
                        new Option("RENAMED", "Renamed or block moved"), new Option("ACTIVITY", "Any movement")),
                List.of(new Option("L", "Local"), new Option("A", "Foreign")),
                List.of(new Option("INDIVIDUAL", "Individuals"), new Option("CORPORATE", "Corporates"),
                        new Option("FUND", "Funds (mutual, pension, private equity ...)"),
                        new Option("FINANCIAL", "Banks, insurers, brokers, custodians"),
                        new Option("GOVERNMENT", "Government and state-owned"), new Option("OTHER", "Other")),
                List.of(new Option("DATE", "Latest month first"), new Option("CHANGE_DESC", "Change: largest buy first"),
                        new Option("CHANGE_ASC", "Change: largest sell first"),
                        new Option("POINTS_DESC", "Change in % points: largest rise first"),
                        new Option("POINTS_ASC", "Change in % points: largest fall first")),
                report == null ? null : highlights(code, report));
    }

    @GetMapping("/highlights")
    public Highlights highlights(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                 @RequestParam("date") String date) {
        String code = Exchange.of(exchange).code();
        LocalDate day = date(date, "date");
        if (day == null) {
            throw new InvalidRequestException("Give the date of a stored list (yyyy-mm-dd)");
        }
        return repository.report(code, day).map(r -> highlights(code, r))
                .orElseThrow(() -> new ListNotFoundException(day));
    }

    private Highlights highlights(String exchange, ReportRow report) {
        LocalDate date = report.reportDate();
        return new Highlights(report, repository.largest(exchange, date, Transaction.BUY, HIGHLIGHTS),
                repository.largest(exchange, date, Transaction.SELL, HIGHLIGHTS),
                repository.largest(exchange, date, Transaction.ENTERED, 50),
                repository.largest(exchange, date, Transaction.EXITED, 50),
                repository.largest(exchange, date, Transaction.RENAMED, 50),
                repository.topInvestors(exchange, date, TOP_INVESTORS));
    }

    @GetMapping("/holders")
    public Company holders(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                           @RequestParam("ticker") String ticker,
                           @RequestParam(name = "from", required = false) String from,
                           @RequestParam(name = "to", required = false) String to) {
        String code = Exchange.of(exchange).code();
        String t = Tickers.normalize(ticker);
        List<MovementRow> rows = repository.holders(code, t, date(from, "from"), date(to, "to"));
        return new Company(code, t, rows.isEmpty() ? null : rows.getFirst().issuerName(), OnePercentDigest.of(t, rows),
                rows);
    }

    @GetMapping("/entities")
    public List<Entity> entities(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                 @RequestParam("q") String q,
                                 @RequestParam(name = "limit", defaultValue = "10") int limit) {
        String query = holder(q);
        if (query == null || OnePercentParser.holderKey(query).length() < 3) {
            throw new InvalidRequestException("Give at least 3 letters or digits of the investor's name");
        }
        return repository.entities(Exchange.of(exchange).code(), query, Math.clamp(limit, 1, 25));
    }

    @GetMapping("/reports")
    public ReportPage reports(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                              @RequestParam(name = "limit", defaultValue = "100") int limit,
                              @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        return repository.reports(Exchange.of(exchange).code(), Math.clamp(limit, 1, 500), offset);
    }

    /** "BBCA, adro BBCA" -> [BBCA, ADRO]; null / blank -> every company. */
    static List<String> tickers(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : value.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                out.add(Tickers.normalize(part));
            }
        }
        if (out.size() > MAX_TICKERS) {
            throw new InvalidRequestException("At most " + MAX_TICKERS + " tickers");
        }
        return new ArrayList<>(out);
    }

    private static String holder(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > MAX_HOLDER_CHARS) {
            throw new InvalidRequestException("The investor's name has at most " + MAX_HOLDER_CHARS + " characters");
        }
        return v;
    }

    /** L (local) or A (foreign, asing); "local" / "foreign" / "F" too. */
    private static String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "L", "LOCAL" -> "L";
            case "A", "F", "FOREIGN", "ASING" -> "A";
            default -> throw new InvalidRequestException("Unknown status '" + value + "'; use L (local) or A (foreign)");
        };
    }

    /** A type group (INDIVIDUAL, CORPORATE, FUND, FINANCIAL, GOVERNMENT, OTHER). */
    private static String group(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String g = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("INDIVIDUAL", "CORPORATE", "FUND", "FINANCIAL", "GOVERNMENT", "OTHER").contains(g)) {
            throw new InvalidRequestException("Unknown investor type '" + value + "'; use INDIVIDUAL, CORPORATE, FUND, "
                    + "FINANCIAL, GOVERNMENT or OTHER");
        }
        return g;
    }

    /** "2026-09-30", or null when not given. */
    private static LocalDate date(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("'" + value + "' (" + name + ") is not a date (yyyy-mm-dd)");
        }
    }

    // ------------------------------------------------------------------ errors

    static class InvalidRequestException extends RuntimeException {

        InvalidRequestException(String message) {
            super(message);
        }
    }

    static class ListNotFoundException extends RuntimeException {

        ListNotFoundException(LocalDate date) {
            super("No list of the shareholders of more than 1% is stored for " + date);
        }
    }

    @ExceptionHandler(ListNotFoundException.class)
    ProblemDetail listNotFound(ListNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("List not found");
        return problem;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(InvalidTickerException.class)
    ProblemDetail invalidTicker(InvalidTickerException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid ticker");
        return problem;
    }

    @ExceptionHandler(UnsupportedExchangeException.class)
    ProblemDetail unsupportedExchange(UnsupportedExchangeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported exchange");
        problem.setProperty("supportedExchanges", e.supported());
        return problem;
    }
}
