package com.neracalab.backend.screening.controller;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningQueue;
import com.neracalab.backend.screening.ScreeningRepository;
import com.neracalab.backend.screening.ScreeningViews.Data;
import com.neracalab.backend.screening.ScreeningViews.Option;
import com.neracalab.backend.screening.ScreeningViews.Options;
import com.neracalab.backend.screening.ScreeningViews.RunSummary;
import com.neracalab.backend.screening.ScreeningViews.ScreeningReport;
import com.neracalab.backend.screening.ScreeningViews.ScreeningRequest;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.FundamentalRepository.DataStatus;
import com.neracalab.backend.screening.report.ScreeningPdfRenderer;
import com.neracalab.backend.web.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * AI stock screening (SCREENING permission).
 * <ul>
 *   <li>{@code GET /api/v1/screenings/options} - exchanges, market-cap tiers, agents, limits, stored data</li>
 *   <li>{@code POST /api/v1/screenings} - start a run; 202 with the queued run</li>
 *   <li>{@code GET /api/v1/screenings[?limit=20]} - runs, most recent first</li>
 *   <li>{@code GET /api/v1/screenings/{id}} - the report (progress while the run is active)</li>
 *   <li>{@code GET /api/v1/screenings/{id}/pdf} - the report as PDF (finished runs)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/screenings", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.SCREENING)
public class ScreeningController {

    static final int MAX_LIMIT = 200;
    static final int DEFAULT_TOP_N = 25;

    private final ScreeningQueue queue;
    private final ScreeningRepository repository;
    private final FundamentalRepository fundamentals;
    private final IngestionJobRepository jobs;
    private final ScreeningProperties properties;
    private final ScreeningPdfRenderer pdf;

    public ScreeningController(ScreeningQueue queue, ScreeningRepository repository, FundamentalRepository fundamentals,
                               IngestionJobRepository jobs, ScreeningProperties properties, ScreeningPdfRenderer pdf) {
        this.queue = queue;
        this.repository = repository;
        this.fundamentals = fundamentals;
        this.jobs = jobs;
        this.properties = properties;
        this.pdf = pdf;
    }

    @GetMapping("/options")
    public Options options() {
        List<Option> exchanges = Arrays.stream(Exchange.values())
                .map(e -> new Option(e.code(), e.displayName(), e.country())).toList();
        List<Option> tiers = Arrays.stream(MarketCapTier.values())
                .map(t -> new Option(t.name(), t.label(), tierDescription(t))).toList();
        List<Option> agents = Arrays.stream(InvestorAgent.values())
                .map(a -> new Option(a.name(), a.label(), a.focus())).toList();
        Exchange exchange = Exchange.IDX;
        DataStatus s = fundamentals.status(exchange);
        Data data = new Data(exchange.code(), s.listings(),
                s.latestSnapshotDate() == null ? null : s.latestSnapshotDate().toString(), s.withFundamentals(),
                jobs.activeJobOf(IngestionJobType.FUNDAMENTALS, exchange.code()).orElse(null));
        return new Options(exchanges, tiers, agents, Math.min(DEFAULT_TOP_N, properties.maxTopN()), properties.maxTopN(),
                properties.shortlistMultiplier(), properties.maxShortlist(), properties.budgetUsd(), data);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunSummary> submit(@RequestBody ScreeningRequest request, AuthenticatedUser user) {
        if (request == null) {
            throw ApiException.badRequest("The request body is missing");
        }
        Exchange exchange;
        try {
            exchange = Exchange.of(request.exchange() == null ? "IDX" : request.exchange());
        } catch (Exchange.UnsupportedExchangeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported exchange", e.getMessage());
        }
        MarketCapTier tier = MarketCapTier.parse(request.marketCapTier());
        if (tier == null) {
            throw ApiException.badRequest("marketCapTier must be one of LARGE, MID, SMALL");
        }
        int topN = request.topN() == null ? DEFAULT_TOP_N : request.topN();
        if (topN < 1 || topN > properties.maxTopN()) {
            throw ApiException.badRequest("topN must be between 1 and " + properties.maxTopN());
        }
        if (request.agents() == null || request.agents().isEmpty()) {
            throw ApiException.badRequest("Choose at least one investor agent");
        }
        Set<InvestorAgent> agents = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : request.agents()) {
            InvestorAgent agent = InvestorAgent.parse(name);
            if (agent == null) {
                unknown.add(String.valueOf(name));
            } else {
                agents.add(agent);
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Unknown agents: " + String.join(", ", unknown) + "; supported: "
                    + String.join(", ", Arrays.stream(InvestorAgent.values()).map(Enum::name).toList()));
        }
        List<InvestorAgent> ordered = Arrays.stream(InvestorAgent.values()).filter(agents::contains).toList();
        UUID id = queue.submit(exchange, tier, topN, ordered, Requester.of(user));
        RunSummary run = repository.find(id).orElseThrow();
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/api/v1/screenings/" + id)).body(run);
    }

    @GetMapping
    public List<RunSummary> list(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        return repository.list(limit);
    }

    @GetMapping("/{id}")
    public ScreeningReport report(@PathVariable("id") UUID id) {
        return load(id);
    }

    @GetMapping(path = "/{id}/pdf", produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> pdf(@PathVariable("id") UUID id) {
        ScreeningReport report = load(id);
        if (!report.run().finished()) {
            throw ApiException.conflict("The screening is still running; the PDF is available when it has finished");
        }
        byte[] content = pdf.render(report);
        String name = "screening-" + report.run().exchange() + "-" + report.run().marketCapTier().name().toLowerCase()
                + "-top" + report.run().topN() + "-" + (report.run().requestedAt() == null ? id
                : report.run().requestedAt().toString().substring(0, 10)) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentLength(content.length)
                .body(content);
    }

    private ScreeningReport load(UUID id) {
        RunSummary run = repository.find(id)
                .orElseThrow(() -> ApiException.notFound("Screening not found", "No screening " + id));
        Map<String, JsonNode> docs = repository.documents(id);
        return new ScreeningReport(run, docs.get("funnel"), docs.get("synthesis"), docs.get("notes"),
                repository.candidates(id), repository.usage(id));
    }

    private String tierDescription(MarketCapTier tier) {
        double low = tier.lowerBound(properties) / 1e12;
        double high = tier.upperBound(properties) / 1e12;
        return switch (tier) {
            case LARGE -> "Market cap at least Rp " + trim(low) + "T";
            case MID -> "Market cap Rp " + trim(low) + "T to " + trim(high) + "T";
            case SMALL -> "Market cap below Rp " + trim(high) + "T";
        };
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
