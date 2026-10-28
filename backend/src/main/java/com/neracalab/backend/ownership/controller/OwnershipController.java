package com.neracalab.backend.ownership.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.neracalab.backend.ownership.OwnershipDigest;
import com.neracalab.backend.ownership.OwnershipDigest.Digest;
import com.neracalab.backend.ownership.OwnershipParser;
import com.neracalab.backend.ownership.OwnershipRepository;
import com.neracalab.backend.ownership.OwnershipRepository.Entity;
import com.neracalab.backend.ownership.OwnershipRepository.Filter;
import com.neracalab.backend.ownership.OwnershipRepository.HolderDay;
import com.neracalab.backend.ownership.OwnershipRepository.MovementPage;
import com.neracalab.backend.ownership.OwnershipRepository.MovementRow;
import com.neracalab.backend.ownership.OwnershipRepository.ReportPage;
import com.neracalab.backend.ownership.OwnershipRepository.ReportRow;
import com.neracalab.backend.ownership.OwnershipRepository.Sort;
import com.neracalab.backend.ownership.OwnershipRepository.TickerRow;
import com.neracalab.backend.ownership.OwnershipRepository.Transaction;
import com.neracalab.backend.rag.ExcelDocumentType;

/**
 * Companies > Ownership (>=5%) (permission COMPANIES): KSEI's daily lists of the shareholders of more than 5% of every
 * IDX company (uploaded on Ingestion > Excel Documents (RAG)): who holds what, who bought or sold, who crossed 5% and
 * who fell below it, day by day, and every company an investor holds.
 * <ul>
 *   <li>{@code GET /api/v1/ownership[?tickers=BBCA,ADRO&holder=lo kheng hong&transaction=ALL|BUY|SELL|CHANGED|ENTERED
 *       |EXITED|CROSSED|ACTIVITY&status=L|A&from=2026-10-19&to=2026-10-27&aliases=false&sort=DATE|CHANGE_DESC
 *       |CHANGE_ASC|POINTS_DESC|POINTS_ASC&limit=100&offset=0]} - one page of the investors of the lists, latest day
 *       first or by their change: {@code { total, limit, offset, rows }}</li>
 *   <li>{@code GET /api/v1/ownership/options} - the latest list with its largest movements, the dates stored, every
 *       company of the lists, the filter and sort options</li>
 *   <li>{@code GET /api/v1/ownership/highlights?date=2026-10-26} - the list of a day with its largest buys and sells
 *       and the investors that crossed 5% or fell below it (404 when that day is not stored)</li>
 *   <li>{@code GET /api/v1/ownership/holders?ticker=ADRO[&from&to]} - one company: every investor on every day with
 *       its accounts, and the digest the AI analysis reads</li>
 *   <li>{@code GET /api/v1/ownership/entities?q=lo kheng hong} - the investors whose name contains the words, each
 *       with every company it holds</li>
 *   <li>{@code GET /api/v1/ownership/reports} - the stored lists</li>
 * </ul>
 */
@RestController
@RequiresPermission(Permission.COMPANIES)
@RequestMapping(path = "/api/v1/ownership", produces = MediaType.APPLICATION_JSON_VALUE)
public class OwnershipController {

    /** Most tickers in one request. */
    static final int MAX_TICKERS = 50;
    /** Longest investor name searched. */
    static final int MAX_HOLDER_CHARS = 100;
    /** Movements of each kind in the latest list's highlights. */
    static final int HIGHLIGHTS = 8;

    private static final ExcelDocumentType TYPE = ExcelDocumentType.OWNERSHIP_5_PERCENT;

    public record Option(String code, String label) {
    }

    /**
     * @param latest        the latest list stored; null when none
     * @param dates         every date stored, latest first
     * @param tickers       every company of the lists
     * @param largestBuys   the latest list's largest buys (percentage points, then shares)
     * @param largestSells  its largest sells
     * @param entered       the investors that crossed 5% that day
     * @param exited        the investors that fell below 5% that day (in the list of the day before, not in it)
     */
    public record Options(ReportRow latest, List<LocalDate> dates, List<TickerRow> tickers,
                          List<Option> transactions, List<Option> statuses, List<Option> sorts,
                          List<MovementRow> largestBuys, List<MovementRow> largestSells, List<MovementRow> entered,
                          List<MovementRow> exited) {
    }

    /**
     * The movements of one day.
     *
     * @param report        the list of the day (its counts)
     * @param largestBuys   its largest buys (percentage points, then shares)
     * @param largestSells  its largest sells
     * @param entered       the investors that crossed 5% that day
     * @param exited        the investors that fell below 5% that day (in the list of the day before, not in it)
     */
    public record Highlights(ReportRow report, List<MovementRow> largestBuys, List<MovementRow> largestSells,
                             List<MovementRow> entered, List<MovementRow> exited) {
    }

    /** One company: its investors on every day (latest first) with their accounts, and its digest. */
    public record Company(String exchange, String ticker, String issuerName, Digest digest, List<HolderDay> days) {
    }

    private final OwnershipRepository repository;

    public OwnershipController(OwnershipRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public MovementPage movements(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                  @RequestParam(name = "tickers", required = false) String tickers,
                                  @RequestParam(name = "holder", required = false) String holder,
                                  @RequestParam(name = "transaction", required = false) String transaction,
                                  @RequestParam(name = "status", required = false) String status,
                                  @RequestParam(name = "from", required = false) String from,
                                  @RequestParam(name = "to", required = false) String to,
                                  @RequestParam(name = "aliases", defaultValue = "false") boolean aliases,
                                  @RequestParam(name = "sort", required = false) String sort,
                                  @RequestParam(name = "limit", defaultValue = "100") int limit,
                                  @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        Transaction t = Transaction.of(transaction).orElseThrow(() -> new InvalidRequestException(
                "Unknown transaction '" + transaction + "'; use ALL, BUY, SELL, CHANGED, ENTERED, EXITED, CROSSED or "
                        + "ACTIVITY"));
        Sort order = Sort.of(sort).orElseThrow(() -> new InvalidRequestException(
                "Unknown sort '" + sort + "'; use DATE, CHANGE_DESC, CHANGE_ASC, POINTS_DESC or POINTS_ASC"));
        LocalDate first = date(from, "from");
        LocalDate last = date(to, "to");
        if (first != null && last != null && first.isAfter(last)) {
            throw new InvalidRequestException("The first date " + first + " is after the last date " + last);
        }
        Filter filter = new Filter(Exchange.of(exchange).code(), TYPE, tickers(tickers), holder(holder), t,
                status(status), first, last, aliases);
        return repository.movements(filter, order, Math.clamp(limit, 1, 500), offset);
    }

    @GetMapping("/options")
    public Options options(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange) {
        String code = Exchange.of(exchange).code();
        List<ReportRow> latest = repository.reports(code, TYPE, 1, 0).reports();
        ReportRow report = latest.isEmpty() ? null : latest.getFirst();
        Highlights highlights = report == null ? null : highlights(code, report);
        return new Options(report, repository.dates(code, TYPE), repository.tickers(code, TYPE),
                List.of(new Option("ALL", "All"), new Option("BUY", "Buy"), new Option("SELL", "Sell"),
                        new Option("CHANGED", "Buy or sell"), new Option("ENTERED", "Crossed above 5%"),
                        new Option("EXITED", "Fell below 5%"), new Option("CROSSED", "Crossed 5% (either way)"),
                        new Option("ACTIVITY", "Any movement")),
                List.of(new Option("L", "Local"), new Option("A", "Foreign")),
                List.of(new Option("DATE", "Latest day first"), new Option("CHANGE_DESC", "Change: largest buy first"),
                        new Option("CHANGE_ASC", "Change: largest sell first"),
                        new Option("POINTS_DESC", "Change in % points: largest rise first"),
                        new Option("POINTS_ASC", "Change in % points: largest fall first")),
                highlights == null ? List.of() : highlights.largestBuys(),
                highlights == null ? List.of() : highlights.largestSells(),
                highlights == null ? List.of() : highlights.entered(),
                highlights == null ? List.of() : highlights.exited());
    }

    @GetMapping("/highlights")
    public ResponseEntity<Highlights> highlights(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                                 @RequestParam("date") String date) {
        String code = Exchange.of(exchange).code();
        LocalDate day = date(date, "date");
        if (day == null) {
            throw new InvalidRequestException("Give the date of a stored list (yyyy-mm-dd)");
        }
        return repository.report(code, TYPE, day).map(r -> ResponseEntity.ok(highlights(code, r)))
                .orElseThrow(() -> new ListNotFoundException(day));
    }

    private Highlights highlights(String exchange, ReportRow report) {
        LocalDate date = report.reportDate();
        return new Highlights(report, repository.largest(exchange, TYPE, date, "BUY", HIGHLIGHTS),
                repository.largest(exchange, TYPE, date, "SELL", HIGHLIGHTS),
                repository.largest(exchange, TYPE, date, "ENTERED", 50),
                repository.largest(exchange, TYPE, date, "EXITED", 50));
    }

    @GetMapping("/holders")
    public Company holders(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                           @RequestParam("ticker") String ticker,
                           @RequestParam(name = "from", required = false) String from,
                           @RequestParam(name = "to", required = false) String to) {
        String code = Exchange.of(exchange).code();
        String t = Tickers.normalize(ticker);
        LocalDate first = date(from, "from");
        LocalDate last = date(to, "to");
        List<HolderDay> days = repository.holders(code, TYPE, t, first, last);
        return new Company(code, t, days.isEmpty() ? null : days.getFirst().holder().issuerName(),
                OwnershipDigest.of(t, days), days);
    }

    @GetMapping("/entities")
    public List<Entity> entities(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                 @RequestParam("q") String q,
                                 @RequestParam(name = "limit", defaultValue = "10") int limit) {
        String query = holder(q);
        if (query == null || OwnershipParser.holderKey(query).length() < 3) {
            throw new InvalidRequestException("Give at least 3 letters or digits of the investor's name");
        }
        return repository.entities(Exchange.of(exchange).code(), TYPE, query, Math.clamp(limit, 1, 25));
    }

    @GetMapping("/reports")
    public ReportPage reports(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                              @RequestParam(name = "limit", defaultValue = "100") int limit,
                              @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        return repository.reports(Exchange.of(exchange).code(), TYPE, Math.clamp(limit, 1, 500), offset);
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

    /** L (local) or A (foreign, asing); "local" / "foreign" too. */
    private static String status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "L", "LOCAL" -> "L";
            case "A", "FOREIGN", "ASING" -> "A";
            default -> throw new InvalidRequestException("Unknown status '" + value + "'; use L (local) or A (foreign)");
        };
    }

    /** "2026-10-27", or null when not given. */
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
            super("No list of the shareholders of more than 5% is stored for " + date);
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
