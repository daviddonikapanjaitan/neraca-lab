package com.neracalab.backend.technical.screening.controller;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningRepository.Scope;
import com.neracalab.backend.screening.ScreeningViews.Option;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.ListingPriceRepository;
import com.neracalab.backend.technical.screening.TechnicalAgent;
import com.neracalab.backend.technical.screening.TechnicalScreener;
import com.neracalab.backend.technical.screening.TechnicalScreeningProperties;
import com.neracalab.backend.technical.screening.TechnicalScreeningQueue;
import com.neracalab.backend.technical.screening.TechnicalScreeningRepository;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.Data;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.Options;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.RunSummary;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.SelectableCompanies;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.TechnicalScreeningReport;
import com.neracalab.backend.technical.screening.TechnicalScreeningViews.TechnicalScreeningRequest;
import com.neracalab.backend.web.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * AI technical screening (SCREENING permission): Screening > Technicals (All) of a market-cap tier and Technicals
 * (Selected) of stocks of the companies table.
 * <ul>
 *   <li>{@code GET /api/v1/technical-screenings/options} - exchanges, market-cap tiers, agents, limits, stored data</li>
 *   <li>{@code POST /api/v1/technical-screenings} - start a run (a market-cap tier or selected stocks); 202 with the
 *       queued run</li>
 *   <li>{@code GET /api/v1/technical-screenings/companies[?exchange=IDX]} - the companies that can be selected, with
 *       their stored daily prices</li>
 *   <li>{@code GET /api/v1/technical-screenings[?limit=50][&scope=TIER|SELECTION]} - runs, most recent first</li>
 *   <li>{@code GET /api/v1/technical-screenings/{id}} - the report (progress while the run is active)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/technical-screenings", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.SCREENING)
public class TechnicalScreeningController {

    static final int MAX_LIMIT = 200;
    static final int DEFAULT_TOP_N = 25;

    private final TechnicalScreeningQueue queue;
    private final TechnicalScreeningRepository repository;
    private final TechnicalScreener screener;
    private final ListingPriceRepository listingPrices;
    private final FundamentalRepository fundamentals;
    private final IngestionJobRepository jobs;
    private final TechnicalScreeningProperties properties;
    private final ScreeningProperties screening;

    public TechnicalScreeningController(TechnicalScreeningQueue queue, TechnicalScreeningRepository repository,
                                        TechnicalScreener screener, ListingPriceRepository listingPrices,
                                        FundamentalRepository fundamentals, IngestionJobRepository jobs,
                                        TechnicalScreeningProperties properties, ScreeningProperties screening) {
        this.queue = queue;
        this.repository = repository;
        this.screener = screener;
        this.listingPrices = listingPrices;
        this.fundamentals = fundamentals;
        this.jobs = jobs;
        this.properties = properties;
        this.screening = screening;
    }

    @GetMapping("/options")
    public Options options() {
        List<Option> exchanges = Arrays.stream(Exchange.values())
                .map(e -> new Option(e.code(), e.displayName(), e.country())).toList();
        List<Option> tiers = screener.tiers().entrySet().stream()
                .map(e -> new Option(e.getKey().name(), e.getKey().label(), e.getValue())).toList();
        List<Option> agents = Arrays.stream(TechnicalAgent.values())
                .map(a -> new Option(a.name(), a.label(), a.focus())).toList();
        Exchange exchange = Exchange.IDX;
        ListingPriceRepository.Status s = listingPrices.status(exchange);
        Data data = new Data(exchange.code(), s.listings(), s.withBars(),
                s.latestDate() == null ? null : s.latestDate().toString(), properties.priceHistory().years(),
                fundamentals.latestSnapshotDate(exchange).map(Object::toString).orElse(null),
                jobs.activeJobOf(IngestionJobType.LISTING_PRICES, exchange.code()).orElse(null));
        return new Options(exchanges, tiers, agents, Math.min(DEFAULT_TOP_N, properties.maxTopN()), properties.maxTopN(),
                properties.shortlistMultiplier(), properties.maxShortlist(), properties.budgetUsd(),
                screening.llm().agentModel(), screening.llm().synthesisModel(), properties.minBars(), data);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunSummary> submit(@RequestBody TechnicalScreeningRequest request, AuthenticatedUser user) {
        if (request == null) {
            throw ApiException.badRequest("The request body is missing");
        }
        Exchange exchange = exchange(request.exchange() == null ? "IDX" : request.exchange());
        UUID id;
        if (request.tickers() != null) {
            if (request.marketCapTier() != null) {
                throw ApiException.badRequest("Give either marketCapTier or tickers, not both");
            }
            List<String> tickers = selectedTickers(exchange, request.tickers());
            int maxTopN = Math.min(properties.maxTopN(), tickers.size());
            int topN = topN(request.topN() == null ? Math.min(DEFAULT_TOP_N, tickers.size()) : request.topN(), maxTopN);
            id = queue.submitSelection(exchange, tickers, topN, agents(request.agents()), Requester.of(user));
        } else {
            MarketCapTier tier = MarketCapTier.parse(request.marketCapTier());
            if (tier == null) {
                throw ApiException.badRequest("marketCapTier must be one of LARGE, MID, SMALL");
            }
            int topN = topN(request.topN() == null ? DEFAULT_TOP_N : request.topN(), properties.maxTopN());
            id = queue.submit(exchange, tier, topN, agents(request.agents()), Requester.of(user));
        }
        RunSummary run = repository.find(id).orElseThrow();
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/technical-screenings/" + id))
                .body(run);
    }

    /** The companies of the exchange that can be selected, with their stored daily prices. */
    @GetMapping("/companies")
    public SelectableCompanies companies(@RequestParam(name = "exchange", defaultValue = "IDX") String code) {
        Exchange exchange = exchange(code);
        return new SelectableCompanies(exchange.code(), properties.maxShortlist(), properties.minBars(),
                repository.companies(exchange.code()));
    }

    /** @param scope ALL (default), TIER (Technicals (All)) or SELECTION (Technicals (Selected)) */
    @GetMapping
    public List<RunSummary> list(@RequestParam(name = "limit", defaultValue = "50") int limit,
                                 @RequestParam(name = "scope", defaultValue = "ALL") String scope) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        Scope parsed = Scope.parse(scope);
        if (parsed == null) {
            throw ApiException.badRequest("scope must be one of ALL, TIER, SELECTION");
        }
        return repository.list(limit, parsed);
    }

    @GetMapping("/{id}")
    public TechnicalScreeningReport report(@PathVariable("id") UUID id) {
        RunSummary run = repository.find(id)
                .orElseThrow(() -> ApiException.notFound("Technical screening not found", "No technical screening " + id));
        Map<String, JsonNode> docs = repository.documents(id);
        return new TechnicalScreeningReport(run, docs.get("funnel"), docs.get("market"), docs.get("synthesis"),
                docs.get("notes"), repository.candidates(id), repository.usage(id));
    }

    private static Exchange exchange(String code) {
        try {
            return Exchange.of(code);
        } catch (Exchange.UnsupportedExchangeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported exchange", e.getMessage());
        }
    }

    private static int topN(int topN, int max) {
        if (topN < 1 || topN > max) {
            throw ApiException.badRequest("topN must be between 1 and " + max);
        }
        return topN;
    }

    /** The agents in their fixed order, duplicates dropped; at least one, all known. */
    private static List<TechnicalAgent> agents(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw ApiException.badRequest("Choose at least one technical agent");
        }
        Set<TechnicalAgent> agents = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            TechnicalAgent agent = TechnicalAgent.parse(name);
            if (agent == null) {
                unknown.add(String.valueOf(name));
            } else {
                agents.add(agent);
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Unknown agents: " + String.join(", ", unknown) + "; supported: "
                    + String.join(", ", Arrays.stream(TechnicalAgent.values()).map(Enum::name).toList()));
        }
        return Arrays.stream(TechnicalAgent.values()).filter(agents::contains).toList();
    }

    /** The selected tickers normalized, duplicates dropped (selection order kept); all in the companies table. */
    private List<String> selectedTickers(Exchange exchange, List<String> tickers) {
        Set<String> selected = new LinkedHashSet<>();
        for (String ticker : tickers) {
            try {
                selected.add(Tickers.normalize(ticker));
            } catch (Tickers.InvalidTickerException e) {
                throw ApiException.badRequest(e.getMessage());
            }
        }
        if (selected.isEmpty()) {
            throw ApiException.badRequest("Choose at least one stock");
        }
        if (selected.size() > properties.maxShortlist()) {
            throw ApiException.badRequest("Choose at most " + properties.maxShortlist() + " stocks (" + selected.size()
                    + " selected)");
        }
        List<String> list = List.copyOf(selected);
        List<String> unknown = repository.unknownCompanies(exchange.code(), list);
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Not in the " + exchange.code() + " companies table: " + String.join(", ", unknown));
        }
        return list;
    }
}
