package com.neracalab.backend.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.analysis.AnalysisRepository.Parameters;
import com.neracalab.backend.analysis.AnalysisRepository.StoredDocuments;
import com.neracalab.backend.analysis.AnalysisSynthesisAgent.AgentRow;
import com.neracalab.backend.analysis.AnalysisSynthesisAgent.Synthesis;
import com.neracalab.backend.company.CompanyDetailResponse;
import com.neracalab.backend.company.CompanyService;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningProperties.ModelPrice;
import com.neracalab.backend.screening.agent.Assessment;
import com.neracalab.backend.screening.agent.InvestorPanel;
import com.neracalab.backend.screening.agent.InvestorPanel.Outcome;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.ReflectionValidator.Issue;
import com.neracalab.backend.screening.agent.ReflexionMemory;
import com.neracalab.backend.screening.agent.UsageMeter;
import com.neracalab.backend.screening.data.FundamentalEtlService;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.quant.QuantScorer;
import com.neracalab.backend.screening.quant.QuantScorer.AgentScore;
import com.neracalab.backend.screening.quant.StockProfile;

import tools.jackson.databind.json.JsonMapper;

/**
 * One analysis of one stock, end to end (called by the {@link AnalysisQueue} worker):
 * <ol>
 *   <li><b>Company data</b> (no model): the fact sheet from the stored statements, metrics, prices and valuations
 *       ({@link FactSheet}); the Yahoo Finance market data (its fundamentals refreshed when stale) and the
 *       quantitative scorecard of every agent ({@link QuantScorer}, as in the screening).</li>
 *   <li><b>Research agent</b> (ReAct + tool calling over the company's stored PDF documents and news,
 *       {@link AnalysisResearchAgent}): a brief with references.</li>
 *   <li><b>Investor agents</b>: one independent call each, sharing the system prompt and the dossier (cacheable
 *       prefix); <b>Reflection</b>: the validator checks every answer, a critic reviews the flagged ones;
 *       <b>Reflexion</b>: lessons of earlier runs are in the prompts, the issues of this one become lessons.</li>
 *   <li><b>Score</b>: per agent {@code (1 - llm-weight) x quantitative + llm-weight x model} (the model score alone
 *       without market data); overall = investor average blended with the Risk agent.</li>
 *   <li><b>Synthesis</b> (Opus): summary, conviction, bull / bear case, risks, at most +-5 points.</li>
 * </ol>
 * Every model and embedding call is metered; the analysis stays within {@code neracalab.analysis.budget-usd} by
 * skipping optional calls (an agent without call keeps its quantitative score).
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    /** Titles of the stored documents named to the research agent. */
    private static final int DOCUMENT_TITLES = 8;

    private final CompanyService companies;
    private final FundamentalEtlService etl;
    private final FundamentalRepository fundamentals;
    private final AnalysisResearchAgent research;
    private final InvestorPanel panel;
    private final AnalysisSynthesisAgent synthesis;
    private final ReflexionMemory memory;
    private final AnalysisRepository repository;
    private final IngestionJobRepository jobs;
    private final EmbeddingClient embeddings;
    private final RagRepository rag;
    private final ScreeningProperties screening;
    private final AnalysisProperties properties;
    private final JsonReplies json;

    public AnalysisService(CompanyService companies, FundamentalEtlService etl, FundamentalRepository fundamentals,
                           AnalysisResearchAgent research, InvestorPanel panel, AnalysisSynthesisAgent synthesis,
                           ReflexionMemory memory, AnalysisRepository repository, IngestionJobRepository jobs,
                           EmbeddingClient embeddings, RagRepository rag, ScreeningProperties screening,
                           AnalysisProperties properties, JsonMapper mapper) {
        this.companies = companies;
        this.etl = etl;
        this.fundamentals = fundamentals;
        this.research = research;
        this.panel = panel;
        this.synthesis = synthesis;
        this.memory = memory;
        this.repository = repository;
        this.jobs = jobs;
        this.embeddings = embeddings;
        this.rag = rag;
        this.screening = screening;
        this.properties = properties;
        this.json = new JsonReplies(mapper);
    }

    /** Stopped by the shutdown of the application. */
    static class InterruptedRunException extends RuntimeException {

        InterruptedRunException() {
            super("Stopped: the application is shutting down");
        }
    }

    /** The market data of the stock: snapshot, metrics and the quantitative scorecards (null: not available). */
    private record Market(StockSnapshot snapshot, StockProfile profile, Map<InvestorAgent, AgentScore> scores) {

        AgentScore score(InvestorAgent agent) {
            return scores.get(agent);
        }
    }

    public void run(UUID id) {
        Parameters p = repository.parameters(id)
                .orElseThrow(() -> new IllegalStateException("Analysis " + id + " not found"));
        UsageMeter meter = new UsageMeter(id, p.budgetUsd(), repository::insertUsage);
        Notes notes = new Notes();
        try {
            execute(p, meter, notes);
        } catch (InterruptedRunException e) {
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Interrupted", e.getMessage(), null);
        } catch (RuntimeException e) {
            log.error("analysis {} failed", id, e);
            notes.add("The analysis failed: " + message(e));
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Failed", message(e), null);
        }
    }

    private void execute(Parameters p, UsageMeter meter, Notes notes) {
        UUID id = p.id();
        String ticker = p.ticker();

        // ---- company data from the database
        jobs.running(id, "Reading " + ticker + "'s statements, metrics and prices from the database");
        CompanyDetailResponse detail = companies.detail(p.exchange(), ticker);
        FactSheet facts = FactSheet.of(detail, properties.annualPeriods());
        if (detail.periods().isEmpty()) {
            notes.add("No financial statements are stored for " + ticker
                    + "; upload its IDX XBRL filings (Ingestion > IDX XBRL) for a full analysis.");
        }
        checkInterrupted();

        // ---- market data and quantitative scorecards (Yahoo Finance, as the screening)
        jobs.progress(id, "Loading " + ticker + "'s market data (Yahoo Finance)", null, null);
        Market market = market(Exchange.of(p.exchange()), ticker, p.agents(), notes);
        StoredDocuments documents = repository.storedDocuments(p.companyId(), DOCUMENT_TITLES);
        Map<String, Object> marketData = marketData(market);
        repository.saveContext(id, context(facts, marketData, documents, market),
                market == null ? null : market.snapshot().snapshotDate());
        checkInterrupted();

        double reserve = properties.synthesisReserveUsd();
        ModelPrice researchPrice = screening.llm().price(screening.llm().researchModel());
        ModelPrice agentPrice = screening.llm().price(screening.llm().agentModel());

        // ---- research agent (ReAct + tools over the stored filings and news)
        progress(id, meter, "Research agent: reading " + ticker + "'s filings and news");
        ContextTools tools = new ContextTools(p.companyId(), ticker, facts, embeddings, rag, meter, properties,
                documents.filings() > 0, documents.news() > 0);
        AnalysisResearchAgent.Subject subject = new AnalysisResearchAgent.Subject(ticker,
                detail.company().companyName(), detail.company().sector(), facts.periodNames(), documents.filingTitles(),
                documents.filings(), documents.newsTitles(), documents.news());
        boolean allowResearch = meter.allows(researchPrice.cost(30_000, 3_000), reserve);
        AnalysisResearchAgent.Result r = research.research(meter, subject, tools, allowResearch);
        boolean researchDegraded = false;
        if (documents.filings() == 0 && documents.news() == 0) {
            notes.add("No PDF documents or news are stored for " + ticker + "; upload them (Ingestion > PDF Documents "
                    + "(RAG), News (RAG)) so the research agent can read them.");
        } else if (r.note() != null) {
            notes.add("Research: " + r.note() + ".");
            researchDegraded = true;
        }
        if (r.droppedRefs() > 0) {
            notes.add(r.droppedRefs() + " research evidence items cited excerpts that were never retrieved and were dropped.");
        }
        repository.saveResearch(id, researchDocument(r));
        checkInterrupted();

        // ---- the dossier every investor agent shares (cacheable prefix)
        Map<String, Object> dossier = new LinkedHashMap<>();
        dossier.put("financials", facts.overview());
        dossier.put("marketData", marketData == null ? "not available (the stock is not in the screening data)" : marketData);
        dossier.put("research", r.brief());
        String dossierText = "COMPANY DOSSIER\n" + json.write(dossier);
        Set<String> metricKeys = new LinkedHashSet<>(StockProfile.METRIC_KEYS);
        collectKeys(json.tree(json.write(dossier)), metricKeys);
        StockProfile ruleProfile = market == null ? null : market.profile();

        // ---- investor agents (independent, one call each)
        Map<InvestorAgent, List<String>> lessons = new EnumMap<>(InvestorAgent.class);
        for (InvestorAgent agent : p.agents()) {
            lessons.put(agent, memory.lessons(agent.name()));
        }
        AtomicInteger budgetSkipped = new AtomicInteger();
        List<Outcome> outcomes = parallel(p.agents(), agent -> {
            if (!meter.allows(agentPrice.cost(10_000, properties.agentMaxTokens()), reserve)) {
                budgetSkipped.incrementAndGet();
                return new Outcome(agent, null, null, List.of(), "Cost budget reached");
            }
            return panel.assess(meter, ticker, AnalysisPrompts.ANALYST, dossierText, agent, quant(market, agent),
                    ruleProfile, lessons.get(agent), metricKeys, properties.agentMaxTokens());
        }, done -> progress(id, meter, "Investor agents: " + done + " of " + p.agents().size() + " assessments"));
        Map<InvestorAgent, Outcome> byAgent = new EnumMap<>(InvestorAgent.class);
        for (int i = 0; i < p.agents().size(); i++) {
            byAgent.put(p.agents().get(i), outcomes.get(i));
        }

        // ---- Reflection: the critic reviews the flagged answers
        List<InvestorAgent> flagged = p.agents().stream()
                .filter(a -> byAgent.get(a).original() != null && !byAgent.get(a).issues().isEmpty()).toList();
        AtomicInteger unreviewed = new AtomicInteger();
        List<Outcome> reviewed = parallel(flagged, agent -> {
            Outcome o = byAgent.get(agent);
            if (!meter.allows(agentPrice.cost(11_000, properties.agentMaxTokens()), reserve)) {
                unreviewed.incrementAndGet();
                return o;
            }
            return panel.reflect(meter, ticker, AnalysisPrompts.ANALYST, dossierText, quant(market, agent),
                    lessons.get(agent), o, properties.agentMaxTokens());
        }, done -> progress(id, meter, "Reflection: reviewing " + flagged.size() + " flagged assessments (" + done + "/"
                + flagged.size() + ")"));
        for (int i = 0; i < flagged.size(); i++) {
            byAgent.put(flagged.get(i), reviewed.get(i));
        }
        if (budgetSkipped.get() > 0) {
            notes.add(budgetSkipped.get() + " agents were not asked (cost budget reached).");
        }
        long failed = byAgent.values().stream().filter(o -> o.original() == null && o.error() != null
                && !"Cost budget reached".equals(o.error())).count();
        if (failed > 0) {
            notes.add(failed + " agents gave no usable answer.");
        }
        if (unreviewed.get() > 0) {
            notes.add(unreviewed.get() + " flagged assessments were not reviewed (cost budget reached).");
        }
        checkInterrupted();

        // ---- scores
        double w = screening.llmWeight();
        Map<InvestorAgent, Double> finals = new EnumMap<>(InvestorAgent.class);
        Map<InvestorAgent, Double> quants = new EnumMap<>(InvestorAgent.class);
        for (InvestorAgent agent : p.agents()) {
            AgentScore q = quant(market, agent);
            Assessment a = byAgent.get(agent).effective();
            Double fin;
            if (a != null && q != null) {
                fin = round1((1 - w) * q.score() + w * a.score());
            } else if (a != null) {
                fin = a.score();
            } else {
                fin = q == null ? null : q.score();
            }
            if (fin != null) {
                finals.put(agent, fin);
            }
            if (q != null) {
                quants.put(agent, q.score());
            }
        }
        Double overall = finals.isEmpty() ? null : QuantScorer.overall(finals, screening.riskWeight());
        Double quantOverall = quants.isEmpty() ? null : QuantScorer.overall(quants, screening.riskWeight());

        // ---- synthesis (Opus)
        progress(id, meter, "Synthesis (" + screening.llm().synthesisModel() + ")");
        List<AgentRow> rows = new ArrayList<>();
        for (InvestorAgent agent : p.agents()) {
            Outcome o = byAgent.get(agent);
            Assessment a = o.effective();
            AgentScore q = quant(market, agent);
            rows.add(new AgentRow(agent.name(), agent.label(), q == null ? null : q.score(),
                    a == null ? null : a.score(), finals.get(agent), a == null ? null : a.verdict(),
                    a == null ? null : a.thesis(), a == null ? List.of() : a.strengths(),
                    a == null ? List.of() : a.concerns(), o.issues().stream().map(Issue::message).toList()));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("ticker", ticker);
        company.put("name", detail.company().companyName());
        company.put("sector", detail.company().sector());
        company.put("industry", detail.company().industry());
        input.put("company", company);
        input.put("overallScore", overall);
        input.put("overallVerdict", overall == null ? null : band(overall));
        input.put("quantitativeOverall", quantOverall);
        input.put("agents", rows);
        input.put("keyFigures", keyFigures(facts.overview()));
        input.put("research", r.brief());
        input.put("dataNotes", List.copyOf(notes.messages));
        ModelPrice synthesisPrice = screening.llm().price(screening.llm().synthesisModel());
        boolean allowSynthesis = meter.allows(synthesisPrice.cost(7_000, 3_000), 0);
        Synthesis syn = synthesis.synthesize(meter, ticker, input, rows, r.brief(), overall, allowSynthesis);
        if (syn.fallback() != null) {
            notes.add("Summary written without the synthesis model: " + syn.fallback());
        }
        double adjustment = syn.adjustment() == null ? 0 : syn.adjustment();
        Double finalScore = overall == null ? null : clamp(overall + adjustment);

        // ---- save the report
        jobs.progress(id, "Saving the report", null, null);
        Map<InvestorAgent, List<Issue>> issues = new EnumMap<>(InvestorAgent.class);
        for (InvestorAgent agent : p.agents()) {
            Outcome o = byAgent.get(agent);
            issues.put(agent, o.issues());
            saveAgent(id, agent, o, quant(market, agent), finals.get(agent));
        }
        List<ReflexionMemory.Learned> learned = memory.learn(id, issues, 1);
        notes.learned(learned);
        notes.applied(lessons);
        Map<String, Object> synthesisDoc = new LinkedHashMap<>();
        synthesisDoc.put("executiveSummary", syn.executiveSummary());
        synthesisDoc.put("conviction", syn.conviction());
        synthesisDoc.put("thesis", syn.thesis());
        synthesisDoc.put("bullCase", syn.bullCase());
        synthesisDoc.put("bearCase", syn.bearCase());
        synthesisDoc.put("keyRisks", syn.keyRisks());
        synthesisDoc.put("monitor", syn.monitor());
        synthesisDoc.put("dataGaps", syn.dataGaps());
        synthesisDoc.put("adjustmentReason", syn.adjustmentReason());
        synthesisDoc.put("model", syn.model());
        synthesisDoc.put("fallback", syn.fallback());
        String verdict = finalScore == null ? null : band(finalScore);
        repository.saveResult(id, new AnalysisRepository.Result(quantOverall, finalScore, adjustment == 0 ? null : adjustment,
                verdict, syn.conviction(), synthesisDoc, notes.document(meter, flagged.size())));
        repository.saveTotals(id, meter.total());

        String summary = ticker + ": " + (finalScore == null ? "no score"
                : "overall " + String.format(Locale.ROOT, "%.1f", finalScore) + " (" + verdict + ")")
                + (syn.conviction() == null ? "" : ", conviction " + syn.conviction()) + ", $" + cents(meter.spentUsd());
        boolean degraded = syn.fallback() != null || budgetSkipped.get() > 0 || failed > 0 || researchDegraded
                || finalScore == null;
        if (degraded) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, String.join(" ", notes.messages), null);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, summary, null, null);
        }
        log.info("analysis {} finished: {}", id, summary);
    }

    // ------------------------------------------------------------------ data

    /**
     * The stock's Yahoo Finance data: its fundamentals are refreshed when older than the screening's maximum age
     * (two requests), then the latest snapshot is scored. Null when the stock is not in the screening data.
     */
    private Market market(Exchange exchange, String ticker, List<InvestorAgent> agents, Notes notes) {
        try {
            FundamentalEtlService.RefreshResult refresh = etl.refreshFundamentals(exchange, List.of(ticker),
                    (done, total, t) -> { });
            if (refresh.interrupted()) {
                throw new InterruptedRunException();
            }
            if (refresh.rateLimited() || refresh.failed() > 0) {
                notes.add("Yahoo Finance could not refresh " + ticker + "'s fundamentals ("
                        + String.join("; ", refresh.errors()) + "); the stored ones were used.");
            }
        } catch (PriceProviderException e) {
            notes.add("Yahoo Finance could not be reached (" + e.getMessage() + "); the stored market data was used.");
        }
        StockSnapshot snapshot = fundamentals.latestSnapshots(exchange, List.of(ticker)).stream().findFirst().orElse(null);
        if (snapshot == null) {
            notes.add(ticker + " is not in the screening data (Ingestion > Screening Data IDX): no market data and no "
                    + "quantitative scorecards; the agents judged from the company data alone.");
            return null;
        }
        if (!snapshot.hasFundamentals()) {
            notes.add("Yahoo Finance has no fundamentals for " + ticker + ": no quantitative scorecards.");
        }
        StockProfile profile = StockProfile.of(snapshot);
        Map<InvestorAgent, AgentScore> scores = new EnumMap<>(InvestorAgent.class);
        if (snapshot.hasFundamentals()) {
            for (InvestorAgent agent : agents) {
                AgentScore score = QuantScorer.score(agent, profile);
                // a scorecard without enough known metrics is no prior (as the screening's coverage filter)
                if (score.coverage() >= screening.minFundamentalsCoverage()) {
                    scores.put(agent, score);
                }
            }
            if (scores.size() < agents.size()) {
                notes.add((agents.size() - scores.size()) + " agents have no quantitative scorecard (fewer than "
                        + Math.round(screening.minFundamentalsCoverage() * 100) + "% of their metrics known).");
            }
        }
        return new Market(snapshot, profile, scores);
    }

    private static AgentScore quant(Market market, InvestorAgent agent) {
        return market == null ? null : market.score(agent);
    }

    private static Map<String, Object> marketData(Market market) {
        if (market == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "Yahoo Finance");
        m.put("date", market.snapshot().snapshotDate() == null ? null : market.snapshot().snapshotDate().toString());
        m.put("metrics", market.profile().asMap());
        return m;
    }

    private static Map<String, Object> context(FactSheet facts, Map<String, Object> marketData, StoredDocuments documents,
                                               Market market) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("factSheet", facts.overview());
        c.put("marketData", marketData);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("pdfDocuments", documents.filings());
        d.put("newsArticles", documents.news());
        d.put("pdfTitles", documents.filingTitles());
        d.put("latestNews", documents.newsTitles());
        c.put("documents", d);
        c.put("periods", facts.periodNames());
        c.put("quantitativeScorecards", market != null && !market.scores().isEmpty());
        return c;
    }

    /** The figures of the overview the synthesis needs: per period a few lines and ratios, the valuation. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> keyFigures(Map<String, Object> overview) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amountUnit", overview.get("amountUnit"));
        List<Map<String, Object>> periods = new ArrayList<>();
        Object list = overview.get("periods");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                Map<String, Object> period = (Map<String, Object>) item;
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("period", period.get("period"));
                copy(period.get("income"), k, "revenue", "netIncomeToParent");
                copy(period.get("balance"), k, "totalEquity");
                copy(period.get("metrics"), k, "roe_annualized", "net_margin", "operating_margin", "debt_to_equity",
                        "equity_to_assets");
                periods.add(k);
            }
        }
        out.put("periods", periods);
        if (overview.get("latestValuation") != null) {
            out.put("latestValuation", overview.get("latestValuation"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void copy(Object source, Map<String, Object> target, String... keys) {
        if (source instanceof Map<?, ?> m) {
            for (String key : keys) {
                Object value = ((Map<String, Object>) m).get(key);
                if (value != null) {
                    target.put(key, value);
                }
            }
        }
    }

    /** Every field name of a JSON tree (the keys an agent may cite in metricsUsed). */
    static void collectKeys(tools.jackson.databind.JsonNode node, Set<String> keys) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, tools.jackson.databind.JsonNode> e : node.properties()) {
                keys.add(e.getKey());
                collectKeys(e.getValue(), keys);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    private Map<String, Object> researchDocument(AnalysisResearchAgent.Result r) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("brief", r.brief());
        doc.put("trace", r.trace());
        doc.put("retrieved", r.retrieved());
        doc.put("modelUsed", r.modelUsed());
        doc.put("droppedRefs", r.droppedRefs());
        doc.put("note", r.note());
        return doc;
    }

    private void saveAgent(UUID id, InvestorAgent agent, Outcome o, AgentScore quant, Double finalScore) {
        Assessment a = o.effective();
        String status = a != null ? (o.revised() != null ? "REVISED" : "ASSESSED") : quant != null ? "QUANT_ONLY" : "NO_SCORE";
        Map<String, Object> reflection = null;
        if (!o.issues().isEmpty() || o.error() != null) {
            reflection = new LinkedHashMap<>();
            reflection.put("issues", o.issues());
            if (o.original() != null) {
                reflection.put("original", Map.of("score", o.original().score(), "verdict", String.valueOf(o.original().verdict())));
            }
            if (o.revised() != null) {
                reflection.put("revised", Map.of("score", o.revised().score(), "verdict", String.valueOf(o.revised().verdict())));
                reflection.put("note", o.revised().note());
                reflection.put("changed", !o.revised().score().equals(o.original().score())
                        || !String.valueOf(o.revised().verdict()).equals(String.valueOf(o.original().verdict())));
            }
            if (o.error() != null) {
                reflection.put("error", o.error());
            }
        }
        repository.saveAgentScore(id, agent, quant == null ? null : quant.score(), quant, a == null ? null : a.score(),
                finalScore, a == null ? null : a.verdict(), a == null ? null : a.thesis(),
                a == null ? List.of() : a.strengths(), a == null ? List.of() : a.concerns(), reflection, status);
    }

    // ------------------------------------------------------------------ helpers

    /** Runs {@code fn} on every item, at most {@code llm.concurrency} at once; results in item order. */
    private <T, R> List<R> parallel(List<T> items, Function<T, R> fn, IntConsumer progress) {
        if (items.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(screening.llm().concurrency());
        AtomicInteger done = new AtomicInteger();
        List<Future<R>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (T item : items) {
                futures.add(pool.submit(() -> {
                    permits.acquire();
                    try {
                        return fn.apply(item);
                    } finally {
                        permits.release();
                        int n = done.incrementAndGet();
                        try {
                            progress.accept(n);
                        } catch (RuntimeException e) {
                            log.debug("progress not recorded: {}", e.getMessage());
                        }
                    }
                }));
            }
            List<R> results = new ArrayList<>(items.size());
            for (Future<R> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (InterruptedException e) {
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new InterruptedRunException();
        } catch (ExecutionException | CancellationException e) {
            futures.forEach(f -> f.cancel(true));
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Stage of the job and the tokens and cost so far (the report page shows both while the analysis runs). */
    private synchronized void progress(UUID id, UsageMeter meter, String stage) {
        jobs.progress(id, stage, null, null);
        repository.saveTotals(id, meter.total());
    }

    private void saveQuietly(UUID id, UsageMeter meter, Notes notes) {
        try {
            repository.saveNotes(id, notes.document(meter, 0));
            repository.saveTotals(id, meter.total());
        } catch (RuntimeException e) {
            log.warn("analysis {}: totals not saved: {}", id, e.getMessage());
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedRunException();
        }
    }

    /** The verdict band of a score (as the investor agents' verdicts). */
    static String band(double score) {
        if (score >= 80) {
            return "STRONG_FIT";
        }
        if (score >= 65) {
            return "FIT";
        }
        if (score >= 45) {
            return "NEUTRAL";
        }
        return score >= 30 ? "WEAK" : "REJECT";
    }

    private static double clamp(double score) {
        return round1(Math.max(0, Math.min(100, score)));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String cents(double usd) {
        return String.format(Locale.ROOT, "%.3f", usd);
    }

    private static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Notes of an analysis: messages, lessons learned and applied, budget use. */
    private static final class Notes {
        final List<String> messages = new CopyOnWriteArrayList<>();
        List<ReflexionMemory.Learned> learned = List.of();
        Map<String, List<String>> applied = Map.of();

        void add(String message) {
            messages.add(message);
        }

        void learned(List<ReflexionMemory.Learned> learned) {
            this.learned = learned;
        }

        void applied(Map<InvestorAgent, List<String>> lessons) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            lessons.forEach((agent, list) -> {
                if (!list.isEmpty()) {
                    m.put(agent.name(), list);
                }
            });
            this.applied = m;
        }

        Map<String, Object> document(UsageMeter meter, int flagged) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("messages", List.copyOf(messages));
            doc.put("lessonsLearned", learned);
            doc.put("lessonsApplied", applied);
            Map<String, Object> budget = new LinkedHashMap<>();
            budget.put("budgetUsd", meter.budgetUsd());
            budget.put("spentUsd", meter.spentUsd());
            budget.put("flaggedAssessments", flagged);
            budget.put("finishedAt", Instant.now().toString());
            doc.put("budget", budget);
            return doc;
        }
    }
}
