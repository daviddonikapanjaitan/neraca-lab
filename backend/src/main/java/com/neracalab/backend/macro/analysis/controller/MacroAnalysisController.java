package com.neracalab.backend.macro.analysis.controller;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.macro.analysis.MacroAgent;
import com.neracalab.backend.macro.analysis.MacroAnalysisProperties;
import com.neracalab.backend.macro.analysis.MacroAnalysisQueue;
import com.neracalab.backend.macro.analysis.MacroAnalysisRepository;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.Data;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAnalysisPage;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAnalysisReport;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAnalysisRequest;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.MacroAnalysisSummary;
import com.neracalab.backend.macro.analysis.MacroAnalysisViews.Options;
import com.neracalab.backend.macro.analysis.report.MacroAnalysisPdfRenderer;
import com.neracalab.backend.macro.outlook.MacroOutlookService;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningViews.Option;
import com.neracalab.backend.web.ApiException;

/**
 * AI macro analysis of a country's stock market (permission COMPANIES, as the Macro page; Macro > AI Analysis).
 * <ul>
 *   <li>{@code GET /api/v1/macro/analyses/options} - the countries and their indexes, the agents, the budget, the models
 *       and what the database holds</li>
 *   <li>{@code POST /api/v1/macro/analyses} with {@code {"country":"ID","index":"IHSG"[,"agents":[...]]}} - queue an
 *       analysis; 202 with the new one, 200 with the index's active one</li>
 *   <li>{@code GET /api/v1/macro/analyses[?country&index&limit=10&offset=0]} - one page, most recent first</li>
 *   <li>{@code GET /api/v1/macro/analyses/{id}} - the report (progress while it runs)</li>
 *   <li>{@code GET /api/v1/macro/analyses/{id}/pdf} - the report as PDF (finished analyses)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/macro/analyses", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.COMPANIES)
public class MacroAnalysisController {

    static final int MAX_LIMIT = 100;

    private final MacroAnalysisQueue queue;
    private final MacroAnalysisRepository repository;
    private final MacroOutlookService outlook;
    private final MacroAnalysisProperties properties;
    private final ScreeningProperties screening;
    private final MacroAnalysisPdfRenderer pdf;
    private final JdbcClient jdbc;
    private final RagRepository rag;

    public MacroAnalysisController(MacroAnalysisQueue queue, MacroAnalysisRepository repository, MacroOutlookService outlook,
                                   MacroAnalysisProperties properties, ScreeningProperties screening,
                                   MacroAnalysisPdfRenderer pdf, JdbcClient jdbc, RagRepository rag) {
        this.queue = queue;
        this.repository = repository;
        this.outlook = outlook;
        this.properties = properties;
        this.screening = screening;
        this.pdf = pdf;
        this.jdbc = jdbc;
        this.rag = rag;
    }

    @GetMapping("/options")
    public Options options() {
        List<Option> agents = Arrays.stream(MacroAgent.values())
                .map(a -> new Option(a.name(), a.label(), a.focus())).toList();
        Data data = jdbc.sql("""
                        SELECT (SELECT count(DISTINCT series_code) FROM macro_series) AS series,
                               (SELECT count(*) FROM macro_series_def) AS series_total,
                               (SELECT count(DISTINCT d.instrument_code) FROM instrument_daily d
                                  JOIN market_instrument i ON i.instrument_code = d.instrument_code
                                 WHERE i.active AND i.market = 'COMMODITY') AS commodities,
                               (SELECT count(DISTINCT d.instrument_code) FROM instrument_daily d
                                  JOIN market_instrument i ON i.instrument_code = d.instrument_code
                                 WHERE i.active AND i.market = 'FOREX') AS forex""")
                .query((rs, i) -> new Data(rs.getInt("series"), rs.getInt("series_total"), rs.getInt("commodities"),
                        rs.getInt("forex"), rag.macroDocumentCounts()))
                .single();
        return new Options(outlook.countries(), agents, properties.budgetUsd(), screening.llm().researchModel(),
                screening.llm().agentModel(), screening.llm().synthesisModel(), data);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MacroAnalysisSummary> submit(@RequestBody MacroAnalysisRequest request, AuthenticatedUser user) {
        if (request == null) {
            throw ApiException.badRequest("The request body is missing");
        }
        MacroOutlookService.Choice choice;
        try {
            choice = outlook.choice(request.country(), request.index());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        List<MacroAgent> agents = agents(request.agents());
        MacroAnalysisQueue.Submission submission = queue.submit(choice, agents, Requester.of(user));
        MacroAnalysisSummary run = repository.find(submission.id()).orElseThrow();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/macro/analyses/" + submission.id()))
                .body(run);
    }

    /** All four when none are given; at least one, known ones, in the fixed order without duplicates. */
    static List<MacroAgent> agents(List<String> names) {
        if (names == null) {
            return List.of(MacroAgent.values());
        }
        if (names.isEmpty()) {
            throw ApiException.badRequest("Choose at least one macro agent");
        }
        Set<MacroAgent> agents = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            MacroAgent agent = MacroAgent.parse(name);
            if (agent == null) {
                unknown.add(String.valueOf(name));
            } else {
                agents.add(agent);
            }
        }
        if (!unknown.isEmpty()) {
            throw ApiException.badRequest("Unknown agents: " + String.join(", ", unknown) + "; supported: "
                    + String.join(", ", Arrays.stream(MacroAgent.values()).map(Enum::name).toList()));
        }
        return Arrays.stream(MacroAgent.values()).filter(agents::contains).toList();
    }

    @GetMapping
    public MacroAnalysisPage list(@RequestParam(name = "country", required = false) String country,
                                  @RequestParam(name = "index", required = false) String index,
                                  @RequestParam(name = "limit", defaultValue = "10") int limit,
                                  @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw ApiException.badRequest("offset must be 0 or more");
        }
        String c = code(country, "country");
        String i = code(index, "index");
        return new MacroAnalysisPage(repository.count(c, i), limit, offset, repository.list(c, i, limit, offset));
    }

    /** An optional filter: null when blank, upper case, letters and digits only. */
    private static String code(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (!v.matches("[A-Z0-9]{1,20}")) {
            throw ApiException.badRequest("Invalid " + name + " '" + value + "'");
        }
        return v;
    }

    @GetMapping("/{id}")
    public MacroAnalysisReport report(@PathVariable("id") UUID id) {
        return load(id);
    }

    @GetMapping(path = "/{id}/pdf", produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<byte[]> pdf(@PathVariable("id") UUID id) {
        MacroAnalysisReport report = load(id);
        if (!report.run().finished()) {
            throw ApiException.conflict("The analysis is still running; the PDF is available when it has finished");
        }
        byte[] content = pdf.render(report);
        String name = "macro-analysis-" + report.run().country() + "-" + report.run().indexCode() + "-"
                + (report.run().requestedAt() == null ? id : report.run().requestedAt().toString().substring(0, 10)) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentLength(content.length)
                .body(content);
    }

    private MacroAnalysisReport load(UUID id) {
        MacroAnalysisSummary run = repository.find(id)
                .orElseThrow(() -> ApiException.notFound("Macro analysis not found", "No macro analysis " + id));
        MacroAnalysisRepository.Documents docs = repository.documents(id);
        return new MacroAnalysisReport(run, docs.synthesisAdjustment(), docs.context(), docs.research(), docs.synthesis(),
                docs.notes(), repository.agentScores(id), repository.usage(id));
    }
}
