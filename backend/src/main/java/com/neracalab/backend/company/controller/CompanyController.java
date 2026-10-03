package com.neracalab.backend.company.controller;

import com.neracalab.backend.company.CompanyDetailResponse;
import com.neracalab.backend.company.CompanyListResponse;
import com.neracalab.backend.company.CompanyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.company.CompanyService.CompanyNotFoundException;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers.InvalidTickerException;

/**
 * Companies stored in the database.
 * <ul>
 *   <li>{@code GET /api/v1/companies?exchange=IDX} - companies of an exchange (default IDX)</li>
 *   <li>{@code GET /api/v1/companies/{exchange}/{ticker}} - everything stored for one company</li>
 * </ul>
 * Exchange and ticker codes are case-insensitive.
 */
@RestController
@RequestMapping(path = "/api/v1/companies", produces = MediaType.APPLICATION_JSON_VALUE)
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    @GetMapping
    public CompanyListResponse list(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange) {
        return service.list(exchange);
    }

    @GetMapping("/{exchange}/{ticker}")
    public CompanyDetailResponse detail(@PathVariable("exchange") String exchange,
                                        @PathVariable("ticker") String ticker) {
        return service.detail(exchange, ticker);
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

    @ExceptionHandler(CompanyNotFoundException.class)
    ProblemDetail notFound(CompanyNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Company not found");
        return problem;
    }
}
