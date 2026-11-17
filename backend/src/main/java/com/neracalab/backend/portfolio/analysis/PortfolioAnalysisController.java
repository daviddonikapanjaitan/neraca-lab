package com.neracalab.backend.portfolio.analysis;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.company.score.QuantScoreBoard;
import com.neracalab.backend.company.score.TechnicalScoreBoard;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.portfolio.PortfolioService;
import com.neracalab.backend.portfolio.PortfolioViews.Portfolio;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisRepository.CompanyInfo;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisViews.Options;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisViews.Report;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisViews.RunSummary;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisViews.SelectableCompany;
import com.neracalab.backend.portfolio.analysis.PortfolioAnalysisViews.StartRequest;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.web.ApiException;

import jakarta.validation.Valid;

/**
 * Portfolio &gt; Analysis of the signed-in user (each user sees only their own runs):
 * <ul>
 *   <li>{@code GET /api/v1/portfolio/analyses/options} - the limits, models, the portfolio's capital and the companies
 *       that can be selected with their Fundamental (Score) and Technical (Score) (COMPANIES)</li>
 *   <li>{@code POST /api/v1/portfolio/analyses} - start a run: 202 with the queued run (COMPANIES and SCREENING: a run
 *       calls the AI models, as the Screening features do); {@code steps} FUNDAMENTAL_TECHNICAL (default) or
 *       FUNDAMENTAL</li>
 *   <li>{@code GET /api/v1/portfolio/analyses?limit=} - the user's runs, most recent first (COMPANIES)</li>
 *   <li>{@code GET /api/v1/portfolio/analyses/{id}} - a run's report, also while it runs (COMPANIES)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/portfolio/analyses", produces = MediaType.APPLICATION_JSON_VALUE)
public class PortfolioAnalysisController {

    static final String MODE_BULK = "BULK";
    static final String MODE_SELECTED = "SELECTED";
    static final String STEPS_FUNDAMENTAL = "FUNDAMENTAL";
    static final String STEPS_BOTH = "FUNDAMENTAL_TECHNICAL";

    private final PortfolioAnalysisQueue queue;
    private final PortfolioAnalysisRepository repository;
    private final PortfolioAnalysisProperties properties;
    private final ScreeningProperties screening;
    private final PortfolioService portfolios;
    private final QuantScoreBoard quantBoard;
    private final TechnicalScoreBoard technicalBoard;

    public PortfolioAnalysisController(PortfolioAnalysisQueue queue, PortfolioAnalysisRepository repository,
                                       PortfolioAnalysisProperties properties, ScreeningProperties screening,
                                       PortfolioService portfolios, QuantScoreBoard quantBoard,
                                       TechnicalScoreBoard technicalBoard) {
        this.queue = queue;
        this.repository = repository;
        this.properties = properties;
        this.screening = screening;
        this.portfolios = portfolios;
        this.quantBoard = quantBoard;
        this.technicalBoard = technicalBoard;
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/options")
    public Options options(@RequestParam(name = "exchange", defaultValue = "IDX") String exchangeCode,
                           AuthenticatedUser user) {
        Exchange exchange = Exchange.of(exchangeCode);
        Portfolio p = portfolios.portfolio(user.userId());
        Set<String> held = new LinkedHashSet<>();
        p.positions().forEach(x -> held.add(x.ticker()));
        QuantScoreBoard.Board f = quantBoard.board(exchange.code());
        TechnicalScoreBoard.Board t = technicalBoard.board(exchange.code());
        Map<String, QuantScoreBoard.Score> fs = new LinkedHashMap<>();
        f.scores().forEach(s -> fs.put(s.ticker(), s));
        Map<String, TechnicalScoreBoard.Score> ts = new LinkedHashMap<>();
        t.scores().forEach(s -> ts.put(s.ticker(), s));
        List<SelectableCompany> companies = new ArrayList<>();
        for (CompanyInfo c : repository.companies(exchange.code()).values()) {
            QuantScoreBoard.Score a = fs.get(c.ticker());
            TechnicalScoreBoard.Score b = ts.get(c.ticker());
            companies.add(new SelectableCompany(c.ticker(), c.companyName(), c.sector(), a == null ? null : a.overall(),
                    a == null ? null : a.verdict(), b == null ? null : b.overall(), b == null ? null : b.stance(),
                    held.contains(c.ticker())));
        }
        Map<String, Object> portfolio = new LinkedHashMap<>();
        portfolio.put("cash", p.summary().cash());
        portfolio.put("marketValue", p.summary().marketTotal());
        portfolio.put("total", p.summary().totalValue());
        portfolio.put("positions", p.summary().positions());
        portfolio.put("held", List.copyOf(held));
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("fundamentalScored", f.scored());
        scores.put("technicalScored", t.scored());
        scores.put("fundamentalComputedAt", f.computedAt());
        scores.put("technicalComputedAt", t.computedAt());
        return new Options(exchange.code(), properties.defaultMaxStocks(), properties.maxStocksLimit(),
                properties.maxSelected(), properties.minFundamentalScore(), properties.fundamentalMultiplier(),
                properties.maxFundamentalShortlist(), properties.technicalMultiplier(),
                properties.maxTechnicalShortlist(), properties.maxPositionWeight(), properties.maxSectorWeight(),
                properties.kellyFraction(), properties.lotSize(), properties.budgetUsd(),
                screening.llm().agentModel(), screening.llm().synthesisModel(), portfolio, companies, scores);
    }

    @RequiresPermission(Permission.SCREENING)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunSummary> start(@Valid @RequestBody StartRequest request, AuthenticatedUser user) {
        if (!user.has(Permission.COMPANIES)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden",
                    "Portfolio > Analysis needs the COMPANIES permission (your portfolio) and SCREENING (the AI models)");
        }
        Exchange exchange = Exchange.of(request.exchange() == null || request.exchange().isBlank() ? "IDX"
                : request.exchange());
        String mode = request.mode().trim().toUpperCase(Locale.ROOT);
        if (!MODE_BULK.equals(mode) && !MODE_SELECTED.equals(mode)) {
            throw ApiException.badRequest("mode must be BULK or SELECTED");
        }
        String steps = request.steps() == null || request.steps().isBlank() ? STEPS_BOTH
                : request.steps().trim().toUpperCase(Locale.ROOT);
        if (!STEPS_BOTH.equals(steps) && !STEPS_FUNDAMENTAL.equals(steps)) {
            throw ApiException.badRequest("steps must be FUNDAMENTAL_TECHNICAL (fundamental then technical) or "
                    + "FUNDAMENTAL (fundamental only)");
        }
        int max = request.maxStocks();
        if (max < 1 || max > properties.maxStocksLimit()) {
            throw ApiException.badRequest("maxStocks must be between 1 and " + properties.maxStocksLimit());
        }
        List<String> tickers = null;
        if (MODE_SELECTED.equals(mode)) {
            if (request.tickers() == null || request.tickers().isEmpty()) {
                throw ApiException.badRequest("Choose at least one stock");
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String t : request.tickers()) {
                if (t == null || t.isBlank()) {
                    continue;
                }
                unique.add(Tickers.normalize(t));
            }
            if (unique.isEmpty()) {
                throw ApiException.badRequest("Choose at least one stock");
            }
            if (unique.size() > properties.maxSelected()) {
                throw ApiException.badRequest("Choose at most " + properties.maxSelected() + " stocks (" + unique.size()
                        + " selected)");
            }
            Map<String, CompanyInfo> known = repository.companies(exchange.code());
            List<String> unknown = unique.stream().filter(t -> !known.containsKey(t)).toList();
            if (!unknown.isEmpty()) {
                throw ApiException.badRequest("Not in the " + exchange.code() + " companies table: "
                        + String.join(", ", unknown));
            }
            tickers = List.copyOf(unique);
        } else if (request.tickers() != null && !request.tickers().isEmpty()) {
            throw ApiException.badRequest("tickers are only for mode SELECTED");
        }
        double added = request.addedCapital() == null ? 0 : request.addedCapital().doubleValue();
        UUID id = queue.submit(user.userId(), exchange.code(), mode, steps, tickers, max, added,
                properties.budgetUsd(), Requester.of(user));
        RunSummary run = repository.find(user.userId(), id).orElseThrow();
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/portfolio/analyses/" + id))
                .body(run);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping
    public List<RunSummary> list(@RequestParam(name = "limit", defaultValue = "50") int limit, AuthenticatedUser user) {
        if (limit < 1 || limit > 200) {
            throw ApiException.badRequest("limit must be between 1 and 200");
        }
        return repository.list(user.userId(), limit);
    }

    @RequiresPermission(Permission.COMPANIES)
    @GetMapping("/{id}")
    public Report report(@PathVariable("id") String idText, AuthenticatedUser user) {
        UUID id;
        try {
            id = UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        RunSummary run = repository.find(user.userId(), id).orElseThrow(PortfolioAnalysisController::notFound);
        PortfolioAnalysisRepository.Documents d = repository.documents(id);
        return new Report(run, d.capital(), d.funnel(), d.synthesis(), d.allocation(), d.checks(), d.notes(),
                repository.candidates(id), repository.usage(id), PortfolioAnalysisService.DISCLAIMER);
    }

    private static ApiException notFound() {
        return ApiException.notFound("Analysis not found", "No such portfolio analysis of yours");
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
