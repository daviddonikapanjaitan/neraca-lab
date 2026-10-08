package com.neracalab.backend.analysis.controller;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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

import com.neracalab.backend.analysis.AnalysisProperties;
import com.neracalab.backend.analysis.AnalysisQueue;
import com.neracalab.backend.analysis.AnalysisRepository;
import com.neracalab.backend.analysis.AnalysisViews.AnalysisPage;
import com.neracalab.backend.analysis.AnalysisViews.AnalysisReport;
import com.neracalab.backend.analysis.AnalysisViews.AnalysisRequest;
import com.neracalab.backend.analysis.AnalysisViews.AnalysisSummary;
import com.neracalab.backend.analysis.AnalysisViews.Options;
import com.neracalab.backend.analysis.report.AnalysisPdfRenderer;
import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningViews.Option;
import com.neracalab.backend.web.ApiException;

/**
 * AI analysis of one stock (SCREENING permission; Screening > Analysis).
 * <ul>
 *   <li>{@code GET /api/v1/analyses/options} - the companies (with their stored data), agents, budget, models</li>
 *   <li>{@code POST /api/v1/analyses} with {@code {"ticker":"HRTA"[,"exchange":"IDX","agents":[...]]}} - queue an
 *       analysis; 202 with the new one, 200 with the company's active one</li>
 *   <li>{@code GET /api/v1/analyses[?ticker&limit=10&offset=0]} - one page of analyses, most recent first</li>
 *   <li>{@code GET /api/v1/analyses/{id}} - the report (progress while it runs)</li>
 *   <li>{@code GET /api/v1/analyses/{id}/pdf} - the report as PDF (finished analyses)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/analyses", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.SCREENING)
public class AnalysisController {

    static final int MAX_LIMIT = 100;

    private final AnalysisQueue queue;
    private final AnalysisRepository repository;
    private final RagRepository companies;
    private final AnalysisProperties properties;
    private final ScreeningProperties screening;
    private final AnalysisPdfRenderer pdf;

    public AnalysisController(AnalysisQueue queue, AnalysisRepository repository, RagRepository companies,
                              AnalysisProperties properties, ScreeningProperties screening, AnalysisPdfRenderer pdf) {
        this.queue = queue;
        this.repository = repository;
        this.companies = companies;
        this.properties = properties;
        this.screening = screening;
        this.pdf = pdf;
    }

    @GetMapping("/options")
    public Options options() {
        List<Option> agents = Arrays.stream(InvestorAgent.values())
                .map(a -> new Option(a.name(), a.label(), a.focus())).toList();
        return new Options(repository.companies(), agents, properties.budgetUsd(), screening.llm().researchModel(),
                screening.llm().agentModel(), screening.llm().synthesisModel());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisSummary> submit(@RequestBody AnalysisRequest request, AuthenticatedUser user) {
        if (request == null) {
            throw ApiException.badRequest("The request body is missing");
        }
        Exchange exchange;
        try {
            exchange = Exchange.of(request.exchange() == null || request.exchange().isBlank() ? "IDX" : request.exchange());
        } catch (Exchange.UnsupportedExchangeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported exchange", e.getMessage());
        }
        if (request.ticker() == null || request.ticker().isBlank()) {
            throw ApiException.badRequest("Choose a stock (ticker)");
        }
        String ticker;
        try {
            ticker = Tickers.normalize(request.ticker());
        } catch (Tickers.InvalidTickerException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        Company company = companies.company(exchange.code(), ticker)
                .orElseThrow(() -> ApiException.notFound("Company not found",
                        "No company " + ticker + " on " + exchange.code() + " in the companies table"));
        List<InvestorAgent> agents = agents(request.agents());
        AnalysisQueue.Submission submission = queue.submit(company, agents, Requester.of(user));
        AnalysisSummary run = repository.find(submission.id()).orElseThrow();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/analyses/" + submission.id()))
                .body(run);
    }

    /** All six when none are given; at least one, known ones, in the fixed order without duplicates. */
    static List<InvestorAgent> agents(List<String> names) {
        if (names == null) {
            return List.of(InvestorAgent.values());
        }
        if (names.isEmpty()) {
            throw ApiException.badRequest("Choose at least one investor agent");
        }
        Set<InvestorAgent> agents = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
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
        return Arrays.stream(InvestorAgent.values()).filter(agents::contains).toList();
    }

    @GetMapping
    public AnalysisPage list(@RequestParam(name = "ticker", required = false) String ticker,
                             @RequestParam(name = "limit", defaultValue = "10") int limit,
                             @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw ApiException.badRequest("offset must be 0 or more");
        }
        String t;
        try {
            t = ticker == null || ticker.isBlank() ? null : Tickers.normalize(ticker);
        } catch (Tickers.InvalidTickerException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return new AnalysisPage(repository.count(t), limit, offset, repository.list(t, limit, offset));
    }

    @GetMapping("/{id}")
    public AnalysisReport report(@PathVariable("id") UUID id) {
        return load(id);
    }

    @GetMapping(path = "/{id}/pdf", produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> pdf(@PathVariable("id") UUID id) {
        AnalysisReport report = load(id);
        if (!report.run().finished()) {
            throw ApiException.conflict("The analysis is still running; the PDF is available when it has finished");
        }
        byte[] content = pdf.render(report);
        String name = "analysis-" + report.run().exchange() + "-" + report.run().ticker() + "-"
                + (report.run().requestedAt() == null ? id : report.run().requestedAt().toString().substring(0, 10)) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentLength(content.length)
                .body(content);
    }

    private AnalysisReport load(UUID id) {
        AnalysisSummary run = repository.find(id)
                .orElseThrow(() -> ApiException.notFound("Analysis not found", "No analysis " + id));
        AnalysisRepository.Documents docs = repository.documents(id);
        return new AnalysisReport(run, docs.quantOverall(), docs.synthesisAdjustment(), docs.context(), docs.research(),
                docs.synthesis(), docs.notes(), repository.agentScores(id), repository.usage(id));
    }
}
