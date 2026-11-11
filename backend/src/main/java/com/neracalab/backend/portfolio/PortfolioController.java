package com.neracalab.backend.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.portfolio.PortfolioViews.Portfolio;
import com.neracalab.backend.portfolio.PortfolioViews.PositionRequest;
import com.neracalab.backend.portfolio.PortfolioViews.PositionUpdate;
import com.neracalab.backend.portfolio.PortfolioViews.SettingsRequest;
import com.neracalab.backend.portfolio.intraday.IntradayService;

import jakarta.validation.Valid;

/**
 * Portfolio &gt; Position of the signed-in user (permission COMPANIES; each user sees only their own portfolio). Every
 * call answers the whole portfolio as it now stands:
 * <ul>
 *   <li>{@code GET /api/v1/portfolio} - the positions with their Fundamentals columns, the metrics and the sector
 *       allocation</li>
 *   <li>{@code PUT /api/v1/portfolio/settings} - the cash and the total investment</li>
 *   <li>{@code POST /api/v1/portfolio/positions} - add a stock (409 when it is already held)</li>
 *   <li>{@code PUT / DELETE /api/v1/portfolio/positions/{id}} - edit or remove a position</li>
 *   <li>{@code POST /api/v1/portfolio/intraday/refresh} - read the intraday prices of the user's stocks now (the
 *       schedule does it every few minutes during the IDX session); writes no position</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/portfolio", produces = MediaType.APPLICATION_JSON_VALUE)
public class PortfolioController {

    private final PortfolioService service;
    private final IntradayService intraday;

    public PortfolioController(PortfolioService service, IntradayService intraday) {
        this.service = service;
        this.intraday = intraday;
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping
    public Portfolio portfolio(AuthenticatedUser user) {
        return service.portfolio(user.userId());
    }

    @RequiresPermission(Permission.COMPANIES)
    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Portfolio settings(@Valid @RequestBody SettingsRequest request, AuthenticatedUser user) {
        return service.saveSettings(user.userId(), request);
    }

    @RequiresPermission(Permission.COMPANIES)
    @PostMapping(path = "/positions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Portfolio add(@Valid @RequestBody PositionRequest request, AuthenticatedUser user) {
        return service.add(user.userId(), request);
    }

    @RequiresPermission(Permission.COMPANIES)
    @PutMapping(path = "/positions/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Portfolio update(@PathVariable("id") long id, @Valid @RequestBody PositionUpdate request,
                            AuthenticatedUser user) {
        return service.update(user.userId(), id, request);
    }

    @RequiresPermission(Permission.COMPANIES)
    @PostMapping("/intraday/refresh")
    public Portfolio refreshIntraday(AuthenticatedUser user) {
        intraday.refreshUser(user.userId());
        return service.portfolio(user.userId());
    }

    @RequiresPermission(Permission.COMPANIES)
    @DeleteMapping("/positions/{id}")
    public Portfolio delete(@PathVariable("id") long id, AuthenticatedUser user) {
        return service.delete(user.userId(), id);
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
}
