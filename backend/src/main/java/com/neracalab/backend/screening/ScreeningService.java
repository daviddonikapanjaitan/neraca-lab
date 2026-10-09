package com.neracalab.backend.screening;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.screening.ScreeningProperties.ModelPrice;
import com.neracalab.backend.screening.ScreeningRepository.RunParameters;
import com.neracalab.backend.screening.agent.Assessment;
import com.neracalab.backend.screening.agent.Dossier;
import com.neracalab.backend.screening.agent.InvestorPanel;
import com.neracalab.backend.screening.agent.InvestorPanel.Outcome;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.NewsBrief;
import com.neracalab.backend.screening.agent.ReflectionValidator.Issue;
import com.neracalab.backend.screening.agent.ReflexionMemory;
import com.neracalab.backend.screening.agent.ResearchAgent;
import com.neracalab.backend.screening.agent.SynthesisAgent;
import com.neracalab.backend.screening.agent.SynthesisAgent.Row;
import com.neracalab.backend.screening.agent.SynthesisAgent.StockView;
import com.neracalab.backend.screening.agent.SynthesisAgent.Synthesis;
import com.neracalab.backend.screening.agent.UsageMeter;
import com.neracalab.backend.screening.data.FundamentalEtlService;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.quant.QuantScorer;
import com.neracalab.backend.screening.quant.QuantScorer.AgentScore;
import com.neracalab.backend.screening.quant.QuantScreener;
import com.neracalab.backend.screening.quant.QuantScreener.Candidate;

import tools.jackson.databind.json.JsonMapper;

/**
 * One screening run, end to end (called by the {@link ScreeningQueue} worker):
 * A run screens a market-cap tier (Screening Stocks) or the stocks the user selected from the companies table
 * (Selected Stocks; {@link RunParameters#selection()}); both take the same steps:
 * <ol>
 *   <li><b>Data</b>: today's market data (Yahoo screener) unless stored, and fresh fundamentals for the
 *       stocks that pass the market-data filters of the tier (or of the selection).</li>
 *   <li><b>Stage 1</b> ({@link QuantScreener}, no model): filters, quantitative score per agent, shortlist.</li>
 *   <li><b>Stage 2</b>: the research agent (news brief per stock), the selected investor agents (one
 *       independent call each), Reflection (validator + critic on flagged answers).</li>
 *   <li><b>Ranking</b>: per agent {@code (1 - llm-weight) x quantitative + llm-weight x model} score;
 *       overall = investor average blended with the Risk agent.</li>
 *   <li><b>Synthesis</b> of the top N (Opus): summary, conviction, thesis, +-5 point adjustments.</li>
 *   <li><b>Report</b> saved; Reflexion lessons learned from the run's issues.</li>
 * </ol>
 * Every model call is metered; the run stays within {@code budget-usd} by skipping optional calls
 * (an agent without call keeps its quantitative score).
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);
    private static final int ALTERNATES = 5;

    private final FundamentalEtlService etl;
    private final FundamentalRepository fundamentals;
    private final QuantScreener screener;
    private final ResearchAgent research;
    private final InvestorPanel panel;
    private final SynthesisAgent synthesis;
    private final ReflexionMemory memory;
    private final ScreeningRepository repository;
    private final IngestionJobRepository jobs;
    private final ScreeningProperties properties;
    private final JsonReplies json;

    public ScreeningService(FundamentalEtlService etl, FundamentalRepository fundamentals, QuantScreener screener,
                            ResearchAgent research, InvestorPanel panel, SynthesisAgent synthesis, ReflexionMemory memory,
                            ScreeningRepository repository, IngestionJobRepository jobs, ScreeningProperties properties,
                            JsonMapper mapper) {
        this.etl = etl;
        this.fundamentals = fundamentals;
        this.screener = screener;
        this.research = research;
        this.panel = panel;
        this.synthesis = synthesis;
        this.memory = memory;
        this.repository = repository;
        this.jobs = jobs;
        this.properties = properties;
        this.json = new JsonReplies(mapper);
    }

    /** Stopped by the shutdown of the application. */
    static class InterruptedRunException extends RuntimeException {

        InterruptedRunException() {
            super("Stopped: the application is shutting down");
        }
    }

    /** A candidate of the shortlist while the run works on it. */
    private static final class Work {
        final Candidate candidate;
        final long candidateId;
        /** screening of selected stocks: tradability steps it failed but was kept for (e.g. low liquidity) */
        final List<String> flags;
        ResearchAgent.Result research;
        String dossier;
        final Map<InvestorAgent, Outcome> outcomes = new EnumMap<>(InvestorAgent.class);
        final Map<InvestorAgent, Double> finals = new EnumMap<>(InvestorAgent.class);
        double overall;
        double adjustment;
        StockView view;

        Work(Candidate candidate, long candidateId, List<String> flags) {
            this.candidate = candidate;
            this.candidateId = candidateId;
            this.flags = flags;
        }

        String ticker() {
            return candidate.ticker();
        }
    }

    public void run(UUID id) {
        RunParameters p = repository.parameters(id)
                .orElseThrow(() -> new IllegalStateException("Screening run " + id + " not found"));
        UsageMeter meter = new UsageMeter(id, p.budgetUsd(), repository::insertUsage);
        Notes notes = new Notes();
        try {
            execute(p, meter, notes);
        } catch (InterruptedRunException e) {
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Interrupted", e.getMessage(), null);
        } catch (RuntimeException e) {
            log.error("screening run {} failed", id, e);
            notes.add("The run failed: " + message(e));
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Failed", message(e), null);
        }
    }

    private void execute(RunParameters p, UsageMeter meter, Notes notes) {
        UUID id = p.id();
        Exchange exchange = Exchange.of(p.exchange());
        ZoneId zone = exchange.zone();
        LocalDate today = LocalDate.now(zone);

        // ---- data: today's market data and fresh fundamentals for the tier's candidates
        jobs.running(id, "Loading today's " + exchange.code() + " market data (Yahoo Finance)");
        if (!etl.universeIsCurrent(exchange)) {
            try {
                etl.syncUniverse(exchange);
            } catch (PriceProviderException e) {
                notes.add("Today's market data could not be loaded (" + e.getMessage() + "); the latest stored data was used.");
            }
        }
        checkInterrupted();
        List<StockSnapshot> universe = snapshots(exchange, p);
        if (universe.isEmpty()) {
            finish(id, meter, notes, IngestionJobStatus.FAILED, "No market data", p.selection()
                    ? "None of the " + p.tickers().size() + " selected stocks has " + exchange.code()
                    + " market data stored, and Yahoo Finance could not be reached or does not list them"
                    : "No " + exchange.code() + " market data is stored and Yahoo Finance could not be reached");
            return;
        }
        List<String> tickers = screener.marketFiltered(universe, p.tier(), zone).stream().map(StockSnapshot::ticker).toList();
        Throttle throttle = new Throttle();
        FundamentalEtlService.RefreshResult refresh = etl.refreshFundamentals(exchange, tickers, (done, total, ticker) -> {
            if (ticker != null && throttle.due()) {
                jobs.progress(id, "Loading fundamentals of " + total + " candidates (" + (done + 1) + "/" + total + ", "
                        + ticker + ")", null, null);
            }
        });
        if (refresh.interrupted()) {
            throw new InterruptedRunException();
        }
        if (refresh.rateLimited()) {
            notes.add("Yahoo Finance rate limit: " + (refresh.requested() - refresh.refreshed())
                    + " candidates were screened with older or no fundamentals.");
        } else if (refresh.failed() > 0) {
            notes.add(refresh.failed() + " candidates have no fundamentals on Yahoo Finance and were filtered out.");
        }
        universe = snapshots(exchange, p);

        // ---- Stage 1
        QuantScreener.Result s1;
        if (p.selection()) {
            jobs.progress(id, "Stage 1: quantitative pre-screen of " + p.tickers().size() + " selected stocks", null, null);
            s1 = screener.screenSelection(p.tickers(), universe, p.agents(), zone);
        } else {
            jobs.progress(id, "Stage 1: quantitative pre-screen of " + universe.size() + " listings", null, null);
            s1 = screener.screen(universe, p.tier(), p.agents(), p.topN(), zone);
        }
        LocalDate snapshotDate = universe.stream().map(StockSnapshot::snapshotDate).max(Comparator.naturalOrder()).orElse(today);
        repository.saveStage1(id, snapshotDate, s1.universe(), s1.eligible().size(), s1.shortlist().size(), s1.funnel());
        if (!s1.excluded().isEmpty()) {
            notes.add("Filtered out in Stage 1: " + s1.excluded().entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining("; ")) + ".");
        }
        if (!s1.flags().isEmpty()) {
            notes.add("Kept although they failed a tradability check (the agents see it; red flag in the report): "
                    + s1.flags().entrySet().stream().map(e -> e.getKey() + " (" + String.join("; ", e.getValue()) + ")")
                    .collect(Collectors.joining("; ")) + ".");
        }
        if (s1.shortlist().isEmpty()) {
            notes.add("No stock passed the Stage 1 filters.");
            finish(id, meter, notes, IngestionJobStatus.INCOMPLETE, "No stock passed the Stage 1 filters", p.selection()
                    ? "None of the " + p.tickers().size() + " selected stocks passed the filters"
                    : "No " + p.tier().label().toLowerCase() + " stock passed the filters");
            return;
        }
        if (s1.eligible().size() < p.topN()) {
            notes.add("Only " + s1.eligible().size() + " stocks passed the filters (top " + p.topN() + " requested).");
        }
        List<Work> works = new ArrayList<>();
        for (int i = 0; i < s1.shortlist().size(); i++) {
            Candidate c = s1.shortlist().get(i);
            Map<String, Object> metrics = new LinkedHashMap<>(c.profile().asMap());
            Map<String, Object> quant = new LinkedHashMap<>();
            c.scores().forEach((agent, score) -> quant.put(agent.name(), score.score()));
            metrics.put("quantScores", quant);
            metrics.put("coverage", c.coverage());
            long candidateId = repository.insertCandidate(id, c.ticker(), c.snapshot().companyName(), c.snapshot().sector(),
                    c.snapshot().industry(), metrics, c.overall(), i + 1);
            works.add(new Work(c, candidateId, s1.flags(c.ticker())));
        }
        checkInterrupted();

        double reserve = properties.synthesisReserveUsd();
        ModelPrice researchPrice = properties.llm().price(properties.llm().researchModel());
        ModelPrice agentPrice = properties.llm().price(properties.llm().agentModel());

        // ---- Stage 2a: research agent (news brief per stock)
        List<String> researchLessons = memory.lessons("RESEARCH");
        AtomicInteger noModel = new AtomicInteger();
        parallel(works, w -> {
            boolean allow = meter.allows(researchPrice.cost(10_000, 1_400), reserve);
            if (!allow) {
                noModel.incrementAndGet();
            }
            w.research = research.research(meter, exchange.code(), w.ticker(), w.candidate.snapshot().companyName(),
                    w.candidate.snapshot().sector(), today, allow, researchLessons);
            repository.saveNews(w.candidateId, newsDocument(w.research));
            w.dossier = Dossier.of(json, w.candidate, p.tier() != null ? p.tier()
                    : MarketCapTier.of(w.candidate.snapshot().marketCap(), properties), w.flags, w.research.brief());
            return null;
        }, done -> progress(id, meter, "Research agent: news of " + works.size() + " stocks (" + done + "/"
                + works.size() + ")"));
        if (noModel.get() > 0) {
            notes.add(noModel.get() + " news briefs were made without the model (cost budget).");
        }

        // ---- Stage 2b: investor agents (independent, one call per agent and stock)
        Map<InvestorAgent, List<String>> lessons = new EnumMap<>(InvestorAgent.class);
        for (InvestorAgent agent : p.agents()) {
            lessons.put(agent, memory.lessons(agent.name()));
        }
        record Task(Work work, InvestorAgent agent) {
        }
        List<Task> tasks = new ArrayList<>();
        for (Work w : works) {
            for (InvestorAgent agent : p.agents()) {
                tasks.add(new Task(w, agent));
            }
        }
        AtomicInteger quantOnly = new AtomicInteger();
        List<Outcome> outcomes = parallel(tasks, t -> {
            AgentScore quant = t.work().candidate.scores().get(t.agent());
            if (!meter.allows(agentPrice.cost(3_500, 400), reserve)) {
                quantOnly.incrementAndGet();
                return new Outcome(t.agent(), null, null, List.of(), "Cost budget reached");
            }
            return panel.assess(meter, t.work().ticker(), t.work().dossier, t.agent(), quant, t.work().candidate.profile(),
                    lessons.get(t.agent()));
        }, done -> progress(id, meter, "Investor agents: " + tasks.size() + " assessments (" + done + "/"
                + tasks.size() + ")"));
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).work().outcomes.put(tasks.get(i).agent(), outcomes.get(i));
        }

        // ---- Stage 2c: Reflection (critic on the answers the validator flagged)
        List<Task> flagged = tasks.stream().filter(t -> {
            Outcome o = t.work().outcomes.get(t.agent());
            return o.original() != null && !o.issues().isEmpty();
        }).toList();
        AtomicInteger unreviewed = new AtomicInteger();
        List<Outcome> reviewed = parallel(flagged, t -> {
            Outcome o = t.work().outcomes.get(t.agent());
            if (!meter.allows(agentPrice.cost(4_500, 450), reserve)) {
                unreviewed.incrementAndGet();
                return o;
            }
            return panel.reflect(meter, t.work().ticker(), t.work().dossier, t.work().candidate.scores().get(t.agent()),
                    t.work().candidate.profile(), lessons.get(t.agent()), o);
        }, done -> progress(id, meter, "Reflection: reviewing " + flagged.size() + " flagged assessments (" + done + "/"
                + flagged.size() + ")"));
        for (int i = 0; i < flagged.size(); i++) {
            flagged.get(i).work().outcomes.put(flagged.get(i).agent(), reviewed.get(i));
        }
        if (quantOnly.get() > 0) {
            notes.add(quantOnly.get() + " assessments use the quantitative score only (cost budget reached).");
        }
        long failed = outcomes.stream().filter(o -> o.original() == null && o.error() != null
                && !"Cost budget reached".equals(o.error())).count();
        if (failed > 0) {
            notes.add(failed + " assessments use the quantitative score only (no usable model answer).");
        }
        if (unreviewed.get() > 0) {
            notes.add(unreviewed.get() + " flagged assessments were not reviewed (cost budget reached).");
        }

        // ---- scores and ranking
        double w = properties.llmWeight();
        for (Work work : works) {
            for (InvestorAgent agent : p.agents()) {
                AgentScore quant = work.candidate.scores().get(agent);
                Assessment a = work.outcomes.get(agent).effective();
                double fin = a == null || a.score() == null ? quant.score() : round1((1 - w) * quant.score() + w * a.score());
                work.finals.put(agent, fin);
            }
            work.overall = QuantScorer.overall(work.finals, properties.riskWeight());
        }
        works.sort(Comparator.comparingDouble((Work x) -> x.overall).reversed()
                .thenComparing(Comparator.comparingDouble((Work x) -> x.candidate.overall()).reversed())
                .thenComparing(Work::ticker));
        checkInterrupted();

        // ---- synthesis of the top N (+ alternates)
        int topN = Math.min(p.topN(), works.size());
        List<Work> considered = List.copyOf(works.subList(0, Math.min(works.size(), topN + ALTERNATES)));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < considered.size(); i++) {
            rows.add(row(i + 1, considered.get(i)));
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("exchange", exchange.code());
        if (p.selection()) {
            context.put("universe", "Stocks selected by the user (" + p.tickers().size() + "), of any market cap");
        } else {
            context.put("marketCapTier", p.tier().label());
        }
        context.put("topN", topN);
        context.put("agents", p.agents().stream().map(InvestorAgent::label).toList());
        context.put("shortlisted", works.size());
        context.put("eligible", s1.eligible().size());
        progress(id, meter, "Synthesis of the top " + topN + " (" + properties.llm().synthesisModel() + ")");
        ModelPrice synthesisPrice = properties.llm().price(properties.llm().synthesisModel());
        boolean allowSynthesis = meter.allows(synthesisPrice.cost(4_000 + rows.size() * 400L, 1_500 + rows.size() * 120L), 0);
        Synthesis syn = synthesis.synthesize(meter, context, rows, allowSynthesis);
        if (syn.fallback() != null) {
            notes.add("Summary written without the synthesis model: " + syn.fallback());
        }
        Map<String, StockView> views = new LinkedHashMap<>();
        syn.stocks().forEach(v -> views.put(v.ticker(), v));
        for (Work work : considered) {
            work.view = views.get(work.ticker());
            work.adjustment = work.view == null || work.view.adjustment() == null ? 0 : work.view.adjustment();
        }
        works.sort(Comparator.comparingDouble((Work x) -> clamp(x.overall + x.adjustment)).reversed()
                .thenComparing(Comparator.comparingDouble((Work x) -> x.overall).reversed())
                .thenComparing(Work::ticker));

        // ---- save the report
        jobs.progress(id, "Saving the report", null, null);
        Map<InvestorAgent, List<Issue>> issues = new EnumMap<>(InvestorAgent.class);
        for (int i = 0; i < works.size(); i++) {
            Work work = works.get(i);
            for (InvestorAgent agent : p.agents()) {
                Outcome o = work.outcomes.get(agent);
                issues.computeIfAbsent(agent, k -> new ArrayList<>()).addAll(o.issues());
                saveAgent(work, agent, o);
            }
            int rank = i + 1;
            boolean selected = rank <= topN;
            String conviction = work.view != null ? work.view.conviction() : null;
            String thesis = work.view != null && work.view.thesis() != null && !work.view.thesis().isBlank()
                    ? work.view.thesis() : bestThesis(work);
            repository.saveFinal(work.candidateId, clamp(work.overall + work.adjustment),
                    work.adjustment == 0 ? null : work.adjustment, rank, selected, conviction, thesis, redFlags(work));
        }
        List<ReflexionMemory.Learned> learned = memory.learn(id, issues);
        notes.learned(learned);
        notes.applied(lessons, researchLessons);
        Map<String, Object> synthesisDoc = new LinkedHashMap<>();
        synthesisDoc.put("executiveSummary", syn.executiveSummary());
        synthesisDoc.put("portfolioNotes", syn.portfolioNotes());
        synthesisDoc.put("model", syn.model());
        synthesisDoc.put("fallback", syn.fallback());
        repository.saveResult(id, topN, synthesisDoc, notes.document(meter, works.size(), flagged.size()));
        repository.saveTotals(id, meter.total());

        String summary = p.selection()
                ? "Top " + topN + " of " + p.tickers().size() + " selected stocks (" + s1.eligible().size()
                        + " eligible), $" + cents(meter.spentUsd())
                : "Top " + topN + " of " + works.size() + " shortlisted " + p.tier().label().toLowerCase()
                        + " stocks (" + s1.eligible().size() + " eligible), $" + cents(meter.spentUsd());
        boolean degraded = syn.fallback() != null || quantOnly.get() > 0 || failed > 0 || refresh.rateLimited()
                || s1.eligible().size() < p.topN();
        if (degraded) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, String.join(" ", notes.messages), null);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, summary, null, null);
        }
        log.info("screening run {} finished: {}", id, summary);
    }

    // ------------------------------------------------------------------ report pieces

    private void saveAgent(Work work, InvestorAgent agent, Outcome o) {
        AgentScore quant = work.candidate.scores().get(agent);
        Assessment a = o.effective();
        String status = a == null ? "QUANT_ONLY" : o.revised() != null ? "REVISED" : "ASSESSED";
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
        repository.saveAgentScore(work.candidateId, agent, quant.score(), quant, a == null ? null : a.score(),
                work.finals.get(agent), a == null ? null : a.verdict(), a == null ? null : a.thesis(),
                a == null ? List.of() : a.strengths(), a == null ? List.of() : a.concerns(), reflection, status);
    }

    private Row row(int rank, Work w) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        List<String> concerns = new ArrayList<>();
        w.finals.forEach((agent, score) -> {
            scores.put(agent.name(), score);
            Assessment a = w.outcomes.get(agent).effective();
            if (a != null) {
                verdicts.put(agent.name(), a.verdict());
                if (!a.concerns().isEmpty() && concerns.size() < 3) {
                    concerns.add(agent.name() + ": " + a.concerns().get(0));
                }
            }
        });
        Map<String, Object> m = w.candidate.profile().asMap();
        Map<String, Object> key = new LinkedHashMap<>();
        for (String k : List.of("marketCap", "pe", "pb", "roe", "debtToEquity", "revenueCagr", "netIncomeCagr", "fcfYield",
                "dividendYield", "peg", "altmanZ")) {
            if (m.containsKey(k)) {
                key.put(k, k.equals("marketCap") ? Math.round(((Number) m.get(k)).doubleValue() / 1e9) + " bn" : m.get(k));
            }
        }
        NewsBrief brief = w.research == null ? null : w.research.brief();
        return new Row(rank, w.ticker(), w.candidate.snapshot().companyName(), w.candidate.snapshot().sector(), w.overall,
                scores, verdicts, key, brief == null ? null : brief.sentiment(), brief == null ? null : brief.summary(),
                concerns);
    }

    private Map<String, Object> newsDocument(ResearchAgent.Result r) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("brief", r.brief());
        List<Map<String, Object>> headlines = new ArrayList<>();
        for (Headline h : r.headlines()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", h.title());
            item.put("url", h.url());
            item.put("source", h.source().label());
            item.put("publishedAt", h.publishedAt() == null ? null : h.publishedAt().toString());
            headlines.add(item);
        }
        doc.put("headlines", headlines);
        doc.put("sources", r.sources());
        doc.put("trace", r.trace());
        doc.put("modelUsed", r.modelUsed());
        doc.put("fromCache", r.fromCache());
        doc.put("note", r.note());
        return doc;
    }

    private static List<String> redFlags(Work w) {
        List<String> flags = new ArrayList<>(w.flags);
        NewsBrief brief = w.research == null ? null : w.research.brief();
        if (brief != null && ("NEGATIVE".equals(brief.sentiment()) || "MIXED".equals(brief.sentiment()))
                && !brief.risks().isEmpty()) {
            flags.add((brief.sentiment().equals("NEGATIVE") ? "Negative news: " : "Mixed news: ") + brief.risks().get(0));
        }
        Double risk = w.finals.get(InvestorAgent.RISK);
        if (risk != null && risk < 45) {
            Assessment a = w.outcomes.get(InvestorAgent.RISK).effective();
            flags.add("Low safety score " + Math.round(risk) + (a != null && !a.concerns().isEmpty() ? ": " + a.concerns().get(0) : ""));
        }
        if (w.candidate.coverage() < 0.7) {
            flags.add("Limited data: " + Math.round(w.candidate.coverage() * 100) + "% of the scoring metrics known");
        }
        return flags;
    }

    private static String bestThesis(Work w) {
        return w.outcomes.entrySet().stream()
                .filter(e -> e.getValue().effective() != null && e.getValue().effective().thesis() != null
                        && !e.getValue().effective().thesis().isBlank())
                .max(Comparator.comparingDouble(e -> w.finals.getOrDefault(e.getKey(), 0d)))
                .map(e -> e.getKey().label() + ": " + e.getValue().effective().thesis())
                .orElse(null);
    }

    // ------------------------------------------------------------------ helpers

    /** The latest snapshots of every listing of the exchange, or of the selected stocks only. */
    private List<StockSnapshot> snapshots(Exchange exchange, RunParameters p) {
        return p.selection() ? fundamentals.latestSnapshots(exchange, p.tickers()) : fundamentals.latestSnapshots(exchange);
    }

    /** Runs {@code fn} on every item, at most {@code llm.concurrency} at once; results in item order. */
    private <T, R> List<R> parallel(List<T> items, Function<T, R> fn, IntConsumer progress) {
        if (items.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(properties.llm().concurrency());
        AtomicInteger done = new AtomicInteger();
        Throttle throttle = new Throttle();
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
                        if (throttle.due() || n == items.size()) {
                            try {
                                progress.accept(n);
                            } catch (RuntimeException e) {
                                log.debug("progress not recorded: {}", e.getMessage());
                            }
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

    /** Stage of the job and the run's tokens and cost so far (the report page shows both while the run is active). */
    private void progress(UUID id, UsageMeter meter, String stage) {
        jobs.progress(id, stage, null, null);
        repository.saveTotals(id, meter.total());
    }

    /** At most one progress write every 2 seconds. */
    private static final class Throttle {
        private final AtomicLong last = new AtomicLong();

        boolean due() {
            long now = System.currentTimeMillis();
            long previous = last.get();
            return now - previous >= 2000 && last.compareAndSet(previous, now);
        }
    }

    private void finish(UUID id, UsageMeter meter, Notes notes, IngestionJobStatus status, String stage, String message) {
        repository.saveNotes(id, notes.document(meter, 0, 0));
        repository.saveTotals(id, meter.total());
        jobs.finish(id, status, stage, message, null);
    }

    private void saveQuietly(UUID id, UsageMeter meter, Notes notes) {
        try {
            repository.saveNotes(id, notes.document(meter, 0, 0));
            repository.saveTotals(id, meter.total());
        } catch (RuntimeException e) {
            log.warn("screening run {}: totals not saved: {}", id, e.getMessage());
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedRunException();
        }
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

    /** Notes of a run: messages, lessons learned and applied, budget use. */
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

        void applied(Map<InvestorAgent, List<String>> lessons, List<String> research) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            if (!research.isEmpty()) {
                m.put("RESEARCH", research);
            }
            lessons.forEach((agent, list) -> {
                if (!list.isEmpty()) {
                    m.put(agent.name(), list);
                }
            });
            this.applied = m;
        }

        Map<String, Object> document(UsageMeter meter, int shortlist, int flagged) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("messages", List.copyOf(messages));
            doc.put("lessonsLearned", learned);
            doc.put("lessonsApplied", applied);
            Map<String, Object> budget = new LinkedHashMap<>();
            budget.put("budgetUsd", meter.budgetUsd());
            budget.put("spentUsd", meter.spentUsd());
            budget.put("shortlist", shortlist);
            budget.put("flaggedAssessments", flagged);
            budget.put("finishedAt", Instant.now().toString());
            doc.put("budget", budget);
            return doc;
        }
    }
}
