package com.neracalab.backend.company.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.CompanyService.CompanyNotFoundException;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.company.score.ScorecardService;
import com.neracalab.backend.company.score.ScorecardService.Scorecards;

/**
 * {@code GET /api/v1/companies/{exchange}/{ticker}/scorecards} - the Quant Score tab of a company: the six
 * fundamental agents' scorecards from preset rules on the stored data (no model call, nothing stored). COMPANIES.
 */
@RestController
@RequestMapping(path = "/api/v1/companies", produces = MediaType.APPLICATION_JSON_VALUE)
public class CompanyScorecardController {

    private final ScorecardService service;

    public CompanyScorecardController(ScorecardService service) {
        this.service = service;
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{exchange}/{ticker}/scorecards")
    public Scorecards scorecards(@PathVariable("exchange") String exchange, @PathVariable("ticker") String ticker) {
        return service.scorecards(exchange, ticker);
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
