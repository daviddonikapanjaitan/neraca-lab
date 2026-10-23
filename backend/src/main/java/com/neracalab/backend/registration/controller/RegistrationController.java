package com.neracalab.backend.registration.controller;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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
import com.neracalab.backend.registration.InsiderRole;
import com.neracalab.backend.registration.RegistrationRepository;
import com.neracalab.backend.registration.RegistrationRepository.CompanyReports;
import com.neracalab.backend.registration.RegistrationRepository.Filter;
import com.neracalab.backend.registration.RegistrationRepository.InsiderPage;
import com.neracalab.backend.registration.RegistrationRepository.ReportRow;
import com.neracalab.backend.registration.RegistrationRepository.Transaction;

/**
 * Companies > Registration (permission COMPANIES): the shares of the directors and commissioners of the companies,
 * month by month, from their monthly registration reports of securities holders (uploaded on Ingestion > PDF
 * Documents (RAG) as REGISTRATION documents).
 * <ul>
 *   <li>{@code GET /api/v1/registrations[?exchange=IDX&tickers=HRTA,BBRI&transaction=ALL|BUY|SELL|CHANGED
 *       &role=DIRECTOR|COMMISSIONER|OTHER&from=2026-05&to=2026-08&limit=100&offset=0]} - one page of the holdings,
 *       latest month first: {@code { total, limit, offset, rows }}; a row's {@code change} is the shares bought (+) or
 *       sold (-) during the month</li>
 *   <li>{@code GET /api/v1/registrations/companies} - the companies with reports (number, first and last month)</li>
 *   <li>{@code GET /api/v1/registrations/reports?exchange=IDX&ticker=HRTA} - the company's reports, latest first</li>
 * </ul>
 */
@RestController
@RequiresPermission(Permission.COMPANIES)
@RequestMapping(path = "/api/v1/registrations", produces = MediaType.APPLICATION_JSON_VALUE)
public class RegistrationController {

    /** Most tickers in one request. */
    static final int MAX_TICKERS = 50;

    /** The filter options of the page. */
    public record Option(String code, String label) {
    }

    /**
     * @param companies    the companies with reports
     * @param transactions the transaction filter (ALL, BUY, SELL, CHANGED)
     * @param roles        the title filter (DIRECTOR, COMMISSIONER, OTHER)
     */
    public record Options(List<CompanyReports> companies, List<Option> transactions, List<Option> roles) {
    }

    private final RegistrationRepository repository;

    public RegistrationController(RegistrationRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public InsiderPage insiders(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                @RequestParam(name = "tickers", required = false) String tickers,
                                @RequestParam(name = "transaction", required = false) String transaction,
                                @RequestParam(name = "role", required = false) String role,
                                @RequestParam(name = "from", required = false) String from,
                                @RequestParam(name = "to", required = false) String to,
                                @RequestParam(name = "limit", defaultValue = "100") int limit,
                                @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        Transaction t = Transaction.of(transaction).orElseThrow(() -> new InvalidRequestException(
                "Unknown transaction '" + transaction + "'; use ALL, BUY, SELL or CHANGED"));
        InsiderRole r = role == null || role.isBlank() ? null : InsiderRole.of(role).orElseThrow(() ->
                new InvalidRequestException("Unknown title '" + role + "'; use DIRECTOR, COMMISSIONER or OTHER"));
        YearMonth first = month(from, "from");
        YearMonth last = month(to, "to");
        if (first != null && last != null && first.isAfter(last)) {
            throw new InvalidRequestException("The first month " + first + " is after the last month " + last);
        }
        Filter filter = new Filter(Exchange.of(exchange).code(), tickers(tickers), t, r, first, last);
        return repository.insiders(filter, Math.clamp(limit, 1, 500), offset);
    }

    @GetMapping("/companies")
    public Options companies() {
        return new Options(repository.companies(),
                List.of(new Option("ALL", "All"), new Option("BUY", "Buy"), new Option("SELL", "Sell"),
                        new Option("CHANGED", "Buy or sell")),
                Arrays.stream(InsiderRole.values()).map(x -> new Option(x.name(), x.label())).toList());
    }

    @GetMapping("/reports")
    public List<ReportRow> reports(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                   @RequestParam("ticker") String ticker) {
        return repository.reports(Exchange.of(exchange).code(), Tickers.normalize(ticker));
    }

    /** "HRTA, bbri HRTA" -> [HRTA, BBRI]; null / blank -> every company. */
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

    /** "2026-06" (a month), or null when not given. */
    private static YearMonth month(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("'" + value + "' (" + name + ") is not a month (yyyy-mm)");
        }
    }

    // ------------------------------------------------------------------ errors

    static class InvalidRequestException extends RuntimeException {

        InvalidRequestException(String message) {
            super(message);
        }
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
