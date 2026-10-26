package com.neracalab.backend.technical.screening;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningProperties.ModelPrice;
import com.neracalab.backend.screening.agent.UsageMeter;
import com.neracalab.backend.screening.data.FundamentalEtlService;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.ListingPriceService;
import com.neracalab.backend.technical.screening.TechnicalPanel.Outcome;
import com.neracalab.backend.technical.screening.TechnicalPanel.Prior;
import com.neracalab.backend.technical.screening.TechnicalScreener.Evaluation;
import com.neracalab.backend.technical.screening.TechnicalScreeningRepository.RunParameters;
import com.neracalab.backend.technical.screening.TechnicalSynthesisAgent.Fallback;
import com.neracalab.backend.technical.screening.TechnicalSynthesisAgent.Row;
import com.neracalab.backend.technical.screening.TechnicalSynthesisAgent.StockView;
import com.neracalab.backend.technical.screening.TechnicalSynthesisAgent.Synthesis;
import com.neracalab.backend.technical.screening.TechnicalValidator.Facts;
import com.neracalab.backend.technical.screening.TechnicalValidator.Issue;

import tools.jackson.databind.json.JsonMapper;

/**
 * One technical screening run, end to end (called by the {@link TechnicalScreeningQueue} worker). A run screens a
 * market-cap tier of the screening universe (Technicals (All)) or stocks of the companies table (Technicals (Selected));
 * both take the same steps:
 * <ol>
 *   <li><b>Data</b> (a tier): today's market data (Yahoo screener) unless stored, then the daily bars of the listings
 *       passing the tradability filters that are behind ({@code price-history.refresh-before-run}). Selected stocks
 *       read the stored daily prices of the companies table.</li>
 *   <li><b>Stage 1</b> ({@link TechnicalScreener}, no model): the indicator snapshot and the four preset summaries of
 *       Companies > Technicals per stock; each agent's prior is its preset's score, the shortlist the best priors.</li>
 *   <li><b>Stage 2</b>: the selected technical agents (DeepSeek, one independent call per agent and stock: score,
 *       stance, trade plan), Reflection (validator + critic on flagged answers).</li>
 *   <li><b>Ranking</b>: per agent {@code (1 - llm-weight) x prior + llm-weight x model} score; overall = the average of
 *       the selected agents.</li>
 *   <li><b>Synthesis</b> of the top N (Opus): summary, market notes, action, conviction, one trade plan per stock,
 *       +-5 point adjustments.</li>
 *   <li><b>Report</b> saved; Reflexion lessons learned from the run's issues.</li>
 * </ol>
 * Every model call is metered; the run stays within {@code budget-usd} by skipping optional calls (an agent without a
 * call keeps its rules prior and its preset's trade plan).
 */
@Service
public class TechnicalScreeningService {

    private static final Logger log = LoggerFactory.getLogger(TechnicalScreeningService.class);
    private static final int ALTERNATES = 5;

    private final FundamentalEtlService etl;
    private final FundamentalRepository fundamentals;
    private final ListingPriceService prices;
    private final TechnicalScreener screener;
    private final TechnicalPanel panel;
    private final TechnicalSynthesisAgent synthesis;
    private final TechnicalLessons lessons;
    private final TechnicalScreeningRepository repository;
    private final IngestionJobRepository jobs;
    private final TechnicalScreeningProperties properties;
    private final ScreeningProperties screening;
    private final JsonMapper json;

    public TechnicalScreeningService(FundamentalEtlService etl, FundamentalRepository fundamentals,
                                     ListingPriceService prices, TechnicalScreener screener, TechnicalPanel panel,
                                     TechnicalSynthesisAgent synthesis, TechnicalLessons lessons,
                                     TechnicalScreeningRepository repository, IngestionJobRepository jobs,
                                     TechnicalScreeningProperties properties, ScreeningProperties screening,
                                     JsonMapper json) {
        this.etl = etl;
        this.fundamentals = fundamentals;
        this.prices = prices;
        this.screener = screener;
        this.panel = panel;
        this.synthesis = synthesis;
        this.lessons = lessons;
        this.repository = repository;
        this.jobs = jobs;
        this.properties = properties;
        this.screening = screening;
        this.json = json;
    }

    /** Stopped by the shutdown of the application. */
    static class InterruptedRunException extends RuntimeException {

        InterruptedRunException() {
            super("Stopped: the application is shutting down");
        }
    }

    /** A candidate of the shortlist while the run works on it. */
    private static final class Work {
        final Evaluation stock;
        final long candidateId;
        final String dossier;
        final Set<String> signalKeys;
        final Facts facts;
        final Map<TechnicalAgent, Outcome> outcomes = new EnumMap<>(TechnicalAgent.class);
        final Map<TechnicalAgent, Double> finals = new EnumMap<>(TechnicalAgent.class);
        final Map<TechnicalAgent, TradePlan> plans = new EnumMap<>(TechnicalAgent.class);
        double overall;
        double adjustment;
        StockView view;

        Work(Evaluation stock, long candidateId, String dossier, Set<String> signalKeys, Facts facts) {
            this.stock = stock;
            this.candidateId = candidateId;
            this.dossier = dossier;
            this.signalKeys = signalKeys;
            this.facts = facts;
        }

        String ticker() {
            return stock.ticker();
        }
    }

    public void run(UUID id) {
        RunParameters p = repository.parameters(id)
                .orElseThrow(() -> new IllegalStateException("Technical screening run " + id + " not found"));
        UsageMeter meter = new UsageMeter(id, p.budgetUsd(), repository::insertUsage);
        Notes notes = new Notes();
        try {
            execute(p, meter, notes);
        } catch (InterruptedRunException e) {
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Interrupted", e.getMessage(), null);
        } catch (RuntimeException e) {
            log.error("technical screening run {} failed", id, e);
            notes.add("The run failed: " + message(e));
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Failed", message(e), null);
        }
    }

    private void execute(RunParameters p, UsageMeter meter, Notes notes) {
        UUID id = p.id();
        Exchange exchange = Exchange.of(p.exchange());
        ZoneId zone = exchange.zone();
        boolean pricesBehind = false;

        // ---- data and Stage 1
        TechnicalScreener.Result s1;
        if (p.selection()) {
            jobs.running(id, "Stage 1: rules of " + p.tickers().size() + " selected stocks (stored daily prices)");
            Map<String, Double> caps = new LinkedHashMap<>();
            for (StockSnapshot s : fundamentals.latestSnapshots(exchange, p.tickers())) {
                if (s.marketCap() != null) {
                    caps.put(s.ticker(), s.marketCap());
                }
            }
            s1 = screener.screenSelection(exchange, p.tickers(), caps, p.agents());
        } else {
            jobs.running(id, "Loading today's " + exchange.code() + " market data (Yahoo Finance)");
            if (!etl.universeIsCurrent(exchange)) {
                try {
                    etl.syncUniverse(exchange);
                } catch (PriceProviderException e) {
                    notes.add("Today's market data could not be loaded (" + e.getMessage()
                            + "); the latest stored data was used.");
                }
            }
            checkInterrupted();
            List<StockSnapshot> universe = fundamentals.latestSnapshots(exchange);
            if (universe.isEmpty()) {
                finish(id, meter, notes, IngestionJobStatus.FAILED, "No market data", "No " + exchange.code()
                        + " market data is stored and Yahoo Finance could not be reached (Ingestion > Screening Data IDX)");
                return;
            }
            if (properties.priceHistory().refreshBeforeRun()) {
                List<ListingPriceService.Target> targets = screener.marketFiltered(universe, p.tier(), zone).stream()
                        .map(s -> new ListingPriceService.Target(s.listingId(), s.ticker())).toList();
                jobs.progress(id, "Price history of " + targets.size() + " " + p.tier().label().toLowerCase(Locale.ROOT)
                        + " listings (Yahoo Finance)", null, null);
                Throttle throttle = new Throttle();
                ListingPriceService.Result r = prices.update(exchange, targets, false, (done, total, ticker) -> {
                    if (ticker != null && throttle.due()) {
                        jobs.progress(id, "Price history of " + total + " listings (" + (done + 1) + "/" + total + ", "
                                + ticker + ")", null, null);
                    }
                });
                if (r.interrupted()) {
                    throw new InterruptedRunException();
                }
                if (r.rateLimited()) {
                    pricesBehind = true;
                    notes.add("Rate limit of the price provider while loading the price history: the stocks not loaded "
                            + "were screened on their stored bars, or left out without them.");
                }
                if (r.notFound() + r.failed() > 0) {
                    notes.add((r.notFound() + r.failed()) + " listings have no price history on the price provider "
                            + "and were left out.");
                }
            }
            checkInterrupted();
            jobs.progress(id, "Stage 1: rules of the " + p.tier().label().toLowerCase(Locale.ROOT) + " listings", null,
                    null);
            s1 = screener.screenTier(exchange, p.tier(), universe, p.agents(), p.topN(), zone);
        }
        repository.saveStage1(id, s1.asOfDate(), s1.universe(), s1.eligible().size(), s1.shortlist().size(), s1.funnel(),
                s1.market());
        if (!s1.excluded().isEmpty()) {
            notes.add("Left out in Stage 1: " + s1.excluded().entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining("; ")) + ".");
        }
        if (s1.shortlist().isEmpty()) {
            notes.add("No stock passed the Stage 1 filters.");
            finish(id, meter, notes, IngestionJobStatus.INCOMPLETE, "No stock passed the Stage 1 filters", p.selection()
                    ? "None of the " + p.tickers().size() + " selected stocks has enough stored daily prices"
                    : "No " + p.tier().label().toLowerCase(Locale.ROOT) + " stock passed the filters (load the price "
                    + "history on Ingestion > Screening Data IDX)");
            return;
        }
        if (s1.eligible().size() < p.topN()) {
            notes.add("Only " + s1.eligible().size() + " stocks passed the filters (top " + p.topN() + " requested).");
        }
        List<Work> works = new ArrayList<>();
        for (int i = 0; i < s1.shortlist().size(); i++) {
            Evaluation e = s1.shortlist().get(i);
            Map<String, Object> dossier = dossier(e);
            long candidateId = repository.insertCandidate(id, e.ticker(), e.companyName(), e.sector(), e.close(),
                    e.asOfDate(), e.marketCap(), metrics(e), e.summary(), e.priorOverall(), i + 1);
            works.add(new Work(e, candidateId, "STOCK CHART DOSSIER\n" + json.writeValueAsString(dossier),
                    signalKeys(dossier), facts(e)));
        }
        checkInterrupted();

        double reserve = properties.synthesisReserveUsd();
        ModelPrice agentPrice = screening.llm().price(screening.llm().agentModel());

        // ---- Stage 2a: the technical agents (independent, one call per agent and stock)
        Map<TechnicalAgent, List<String>> learnedBefore = new EnumMap<>(TechnicalAgent.class);
        for (TechnicalAgent agent : p.agents()) {
            learnedBefore.put(agent, lessons.lessons(agent));
        }
        record Task(Work work, TechnicalAgent agent) {
        }
        List<Task> tasks = new ArrayList<>();
        for (Work w : works) {
            for (TechnicalAgent agent : p.agents()) {
                tasks.add(new Task(w, agent));
            }
        }
        AtomicInteger priorOnly = new AtomicInteger();
        List<Outcome> outcomes = parallel(tasks, t -> {
            if (!meter.allows(agentPrice.cost(4_000, properties.agentMaxTokens()), reserve)) {
                priorOnly.incrementAndGet();
                return new Outcome(t.agent(), null, null, List.of(), "Cost budget reached");
            }
            return panel.assess(meter, t.work().ticker(), t.work().dossier, t.agent(), t.work().stock.priors().get(t.agent()),
                    t.work().facts, learnedBefore.get(t.agent()), t.work().signalKeys, properties.agentMaxTokens());
        }, done -> progress(id, meter, "Technical agents: " + tasks.size() + " assessments (" + done + "/" + tasks.size()
                + ")"));
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).work().outcomes.put(tasks.get(i).agent(), outcomes.get(i));
        }

        // ---- Stage 2b: Reflection (critic on the answers the validator flagged)
        List<Task> flagged = tasks.stream().filter(t -> {
            Outcome o = t.work().outcomes.get(t.agent());
            return o.original() != null && !o.issues().isEmpty();
        }).toList();
        AtomicInteger unreviewed = new AtomicInteger();
        List<Outcome> reviewed = parallel(flagged, t -> {
            Outcome o = t.work().outcomes.get(t.agent());
            if (!meter.allows(agentPrice.cost(5_000, properties.agentMaxTokens()), reserve)) {
                unreviewed.incrementAndGet();
                return o;
            }
            return panel.reflect(meter, t.work().ticker(), t.work().dossier, t.work().stock.priors().get(t.agent()),
                    learnedBefore.get(t.agent()), o, properties.agentMaxTokens());
        }, done -> progress(id, meter, "Reflection: reviewing " + flagged.size() + " flagged assessments (" + done + "/"
                + flagged.size() + ")"));
        for (int i = 0; i < flagged.size(); i++) {
            flagged.get(i).work().outcomes.put(flagged.get(i).agent(), reviewed.get(i));
        }
        if (priorOnly.get() > 0) {
            notes.add(priorOnly.get() + " assessments use the rules summary only (cost budget reached).");
        }
        long failed = outcomes.stream().filter(o -> o.original() == null && o.error() != null
                && !"Cost budget reached".equals(o.error())).count();
        if (failed > 0) {
            notes.add(failed + " assessments use the rules summary only (no usable model answer).");
        }
        if (unreviewed.get() > 0) {
            notes.add(unreviewed.get() + " flagged assessments were not reviewed (cost budget reached).");
        }

        // ---- scores, trade plans and ranking
        double w = properties.llmWeight();
        AtomicInteger rulesLevels = new AtomicInteger();
        for (Work work : works) {
            double sum = 0;
            for (TechnicalAgent agent : p.agents()) {
                Prior prior = work.stock.priors().get(agent);
                TechnicalAssessment a = work.outcomes.get(agent).effective();
                double fin = a == null || a.score() == null ? prior.score() : round1((1 - w) * prior.score() + w * a.score());
                work.finals.put(agent, fin);
                sum += fin;
                TradePlan plan = a == null ? null : TradePlan.ofModel(a.buyPrice(), a.entryType(), a.sellPrice(),
                        a.stopPrice(), work.stock.close(), screener.ticks(), "AI", agent.name(), agent.label() + " (AI)");
                if (plan == null) {
                    if (a != null) {
                        rulesLevels.incrementAndGet();
                    }
                    plan = TradePlan.ofRules(prior.summary(), agent.name());
                }
                work.plans.put(agent, plan);
            }
            work.overall = round1(sum / p.agents().size());
        }
        if (rulesLevels.get() > 0) {
            notes.add(rulesLevels.get() + " agent answers gave no valid trade plan after the reflection; their preset's "
                    + "rules levels are shown instead.");
        }
        works.sort(Comparator.comparingDouble((Work x) -> x.overall).reversed()
                .thenComparing(Comparator.comparingDouble((Work x) -> x.stock.priorOverall()).reversed())
                .thenComparing(Work::ticker));
        checkInterrupted();

        // ---- synthesis of the top N (+ alternates)
        int topN = Math.min(p.topN(), works.size());
        List<Work> considered = List.copyOf(works.subList(0, Math.min(works.size(), topN + ALTERNATES)));
        List<Row> rows = new ArrayList<>();
        Map<String, Fallback> fallbacks = new LinkedHashMap<>();
        for (int i = 0; i < considered.size(); i++) {
            Work work = considered.get(i);
            rows.add(row(i + 1, work));
            fallbacks.put(work.ticker(), new Fallback(bestPlan(work), work.overall));
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("exchange", exchange.code());
        if (p.selection()) {
            context.put("universe", "Stocks selected by the user (" + p.tickers().size() + "), of any market cap");
        } else {
            context.put("marketCapTier", p.tier().label());
        }
        context.put("asOfDate", s1.asOfDate() == null ? null : s1.asOfDate().toString());
        context.put("market", s1.market());
        context.put("topN", topN);
        context.put("agents", p.agents().stream().map(TechnicalAgent::label).toList());
        context.put("shortlisted", works.size());
        context.put("eligible", s1.eligible().size());
        progress(id, meter, "Synthesis of the top " + topN + " (" + screening.llm().synthesisModel() + ")");
        ModelPrice synthesisPrice = screening.llm().price(screening.llm().synthesisModel());
        boolean allowSynthesis = meter.allows(synthesisPrice.cost(5_000 + rows.size() * 700L,
                2_000 + rows.size() * 180L), 0);
        Synthesis syn = synthesis.synthesize(meter, context, rows, fallbacks, screener.ticks(), allowSynthesis);
        if (syn.fallback() != null) {
            notes.add("Summary written without the synthesis model: " + syn.fallback());
        }
        Map<String, StockView> views = new LinkedHashMap<>();
        syn.stocks().forEach(v -> views.put(v.ticker(), v));
        long rejectedPlans = syn.stocks().stream().filter(v -> v.planSource() != null
                && v.planSource().startsWith("The synthesis' plan was not valid")).count();
        if (rejectedPlans > 0) {
            notes.add(rejectedPlans + " trade plans of the synthesis were not valid prices; the best agent's plan is "
                    + "used for them.");
        }
        for (Work work : considered) {
            work.view = views.get(work.ticker());
            work.adjustment = work.view == null ? 0 : work.view.adjustment();
        }
        works.sort(Comparator.comparingDouble((Work x) -> clamp(x.overall + x.adjustment)).reversed()
                .thenComparing(Comparator.comparingDouble((Work x) -> x.overall).reversed())
                .thenComparing(Work::ticker));

        // ---- save the report
        jobs.progress(id, "Saving the report", null, null);
        Map<TechnicalAgent, List<Issue>> issues = new EnumMap<>(TechnicalAgent.class);
        for (int i = 0; i < works.size(); i++) {
            Work work = works.get(i);
            for (TechnicalAgent agent : p.agents()) {
                Outcome o = work.outcomes.get(agent);
                issues.computeIfAbsent(agent, k -> new ArrayList<>()).addAll(o.issues());
                saveAgent(work, agent, o);
            }
            int rank = i + 1;
            boolean selected = rank <= topN;
            double overall = clamp(work.overall + work.adjustment);
            String action = work.view != null ? work.view.action() : TechnicalAssessment.stanceOf(overall);
            String conviction = work.view != null ? work.view.conviction() : null;
            TradePlan plan = work.view != null && work.view.plan() != null ? work.view.plan() : bestPlan(work);
            String thesis = work.view != null && work.view.thesis() != null && !work.view.thesis().isBlank()
                    ? work.view.thesis() : bestThesis(work);
            Map<String, Object> planDoc = plan == null ? null : new LinkedHashMap<>(plan.toMap());
            if (planDoc != null && work.view != null && work.view.planSource() != null
                    && !"SYNTHESIS".equals(work.view.planSource())) {
                planDoc.put("note", work.view.planSource());
            }
            repository.saveFinal(work.candidateId, overall, work.adjustment == 0 ? null : work.adjustment, rank, selected,
                    action, conviction, planDoc, thesis, redFlags(work));
        }
        List<TechnicalLessons.Learned> learned = lessons.learn(id, issues);
        notes.learned(learned);
        notes.applied(learnedBefore);
        Map<String, Object> synthesisDoc = new LinkedHashMap<>();
        synthesisDoc.put("executiveSummary", syn.executiveSummary());
        synthesisDoc.put("marketNotes", syn.marketNotes());
        synthesisDoc.put("model", syn.model());
        synthesisDoc.put("fallback", syn.fallback());
        repository.saveResult(id, topN, synthesisDoc, notes.document(meter, works.size(), flagged.size()));
        repository.saveTotals(id, meter.total());

        String summary = p.selection()
                ? "Top " + topN + " of " + p.tickers().size() + " selected stocks (" + s1.eligible().size()
                        + " eligible), $" + cents(meter.spentUsd())
                : "Top " + topN + " of " + works.size() + " shortlisted " + p.tier().label().toLowerCase(Locale.ROOT)
                        + " stocks (" + s1.eligible().size() + " eligible), $" + cents(meter.spentUsd());
        boolean degraded = syn.fallback() != null || priorOnly.get() > 0 || failed > 0 || pricesBehind
                || s1.eligible().size() < p.topN();
        if (degraded) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, String.join(" ", notes.messages), null);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, summary, null, null);
        }
        log.info("technical screening run {} finished: {}", id, summary);
    }

    // ------------------------------------------------------------------ report pieces

    private void saveAgent(Work work, TechnicalAgent agent, Outcome o) {
        Prior prior = work.stock.priors().get(agent);
        TechnicalAssessment a = o.effective();
        String status = a == null ? "PRIOR_ONLY" : o.revised() != null ? "REVISED" : "ASSESSED";
        Map<String, Object> reflection = null;
        if (!o.issues().isEmpty() || o.error() != null) {
            reflection = new LinkedHashMap<>();
            reflection.put("issues", o.issues());
            if (o.original() != null) {
                reflection.put("original", Map.of("score", o.original().score(), "stance", String.valueOf(o.original().stance())));
            }
            if (o.revised() != null) {
                reflection.put("revised", Map.of("score", o.revised().score(), "stance", String.valueOf(o.revised().stance())));
                reflection.put("note", o.revised().note());
                reflection.put("changed", !o.revised().score().equals(o.original().score())
                        || !String.valueOf(o.revised().stance()).equals(String.valueOf(o.original().stance())));
            }
            if (o.error() != null) {
                reflection.put("error", o.error());
            }
        }
        TradePlan plan = work.plans.get(agent);
        repository.saveAgentScore(work.candidateId, agent, prior.score(), a == null ? null : a.score(),
                work.finals.get(agent), a == null ? prior.stance() : a.stance(), a == null ? null : a.thesis(),
                a == null ? List.of() : a.supports(), a == null ? List.of() : a.risks(),
                plan == null ? null : plan.toMap(), reflection, status);
    }

    /** One candidate for the synthesis: overall, per agent its score, stance, plan and thesis, key technicals, flags. */
    private Row row(int rank, Work w) {
        Map<String, Object> agents = new LinkedHashMap<>();
        w.finals.forEach((agent, score) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", score);
            TechnicalAssessment a = w.outcomes.get(agent).effective();
            m.put("stance", a != null ? a.stance() : w.stock.priors().get(agent).stance());
            TradePlan plan = w.plans.get(agent);
            if (plan != null && plan.complete()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("buy", plan.buy().price());
                pm.put("buyType", plan.buy().type());
                pm.put("sell", plan.sell().price());
                pm.put("stopLoss", plan.stopLoss().price());
                pm.put("rewardRisk", plan.rewardRisk());
                pm.put("source", plan.source());
                m.put("plan", pm);
            }
            if (a != null && a.thesis() != null) {
                m.put("thesis", a.thesis());
            }
            agents.put(agent.name(), m);
        });
        Map<String, Object> key = new LinkedHashMap<>(metrics(w.stock));
        key.remove("close");
        return new Row(rank, w.ticker(), w.stock.companyName(), w.stock.sector(), w.stock.close(), w.overall,
                w.stock.priorOverall(), agents, key, w.stock.flags());
    }

    /**
     * The best agent's trade plan: of the agents whose stance is the action of the overall score, the one with the
     * highest final score; else the highest final score of all. Only complete plans.
     */
    private static TradePlan bestPlan(Work w) {
        String action = TechnicalAssessment.stanceOf(w.overall);
        Comparator<Map.Entry<TechnicalAgent, Double>> byScore = Map.Entry.comparingByValue();
        TradePlan same = w.finals.entrySet().stream()
                .filter(e -> w.plans.get(e.getKey()) != null && w.plans.get(e.getKey()).complete())
                .filter(e -> action.equals(stance(w, e.getKey())))
                .max(byScore).map(e -> w.plans.get(e.getKey())).orElse(null);
        if (same != null) {
            return same;
        }
        return w.finals.entrySet().stream()
                .filter(e -> w.plans.get(e.getKey()) != null && w.plans.get(e.getKey()).complete())
                .max(byScore).map(e -> w.plans.get(e.getKey())).orElse(null);
    }

    private static String stance(Work w, TechnicalAgent agent) {
        TechnicalAssessment a = w.outcomes.get(agent).effective();
        return a != null ? a.stance() : w.stock.priors().get(agent).stance();
    }

    private static String bestThesis(Work w) {
        return w.outcomes.entrySet().stream()
                .filter(e -> e.getValue().effective() != null && e.getValue().effective().thesis() != null
                        && !e.getValue().effective().thesis().isBlank())
                .max(Comparator.comparingDouble(e -> w.finals.getOrDefault(e.getKey(), 0d)))
                .map(e -> e.getKey().label() + ": " + e.getValue().effective().thesis())
                .orElseGet(() -> w.finals.entrySet().stream().max(Map.Entry.comparingByValue())
                        .map(e -> String.valueOf(w.stock.priors().get(e.getKey()).summary().get("thesis")))
                        .orElse(null));
    }

    private static List<String> redFlags(Work w) {
        List<String> flags = new ArrayList<>(w.stock.flags());
        long avoid = w.outcomes.values().stream().map(Outcome::effective)
                .filter(a -> a != null && "AVOID".equals(a.stance())).count();
        if (avoid >= 2) {
            flags.add(avoid + " of the agents say AVOID");
        }
        return flags;
    }

    // ------------------------------------------------------------------ dossier

    /**
     * The chart dossier the agents read: the indicator snapshot without what they do not need (the swing lists, the
     * notes, which presets use a group), the support / resistance zones reduced to the three nearest each side, the
     * tradability flags and the summaries' caveats.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> dossier(Evaluation e) {
        Map<String, Object> d = json.readValue(json.writeValueAsString(e.snapshot()), Map.class);
        removeKey(d, "usedBy");
        d.remove("notes");
        d.remove("priceBasis");
        d.remove("schemaVersion");
        if (d.get("structure") instanceof Map<?, ?> s) {
            Map<String, Object> structure = (Map<String, Object>) s;
            structure.remove("swings");
            structure.remove("zoneTolerancePct");
            if (structure.remove("zones") instanceof List<?> zones) {
                List<Map<String, Object>> below = new ArrayList<>();
                List<Map<String, Object>> above = new ArrayList<>();
                for (Object z : zones) {
                    if (z instanceof Map<?, ?> zone && zone.get("mid") instanceof Number mid) {
                        Map<String, Object> slim = new LinkedHashMap<>();
                        for (String k : List.of("low", "mid", "high", "touches", "lastTouch", "distancePct")) {
                            slim.put(k, zone.get(k));
                        }
                        (mid.doubleValue() < e.close() ? below : above).add(slim);
                    }
                }
                below.sort(Comparator.comparingDouble((Map<String, Object> z) -> ((Number) z.get("mid")).doubleValue())
                        .reversed());
                above.sort(Comparator.comparingDouble(z -> ((Number) z.get("mid")).doubleValue()));
                structure.put("supportZonesBelow", below.subList(0, Math.min(3, below.size())));
                structure.put("resistanceZonesAbove", above.subList(0, Math.min(3, above.size())));
            }
        }
        if (d.get("volume") instanceof Map<?, ?> v && v.get("volumeSpikes60") instanceof Map<?, ?> spikes) {
            ((Map<String, Object>) spikes).remove("days");
        }
        if (d.get("events") instanceof Map<?, ?> ev && (ev.get("upcoming") instanceof List<?> up && up.isEmpty())
                && (ev.get("last12Months") instanceof List<?> last && last.isEmpty())) {
            d.remove("events");
        }
        if (!e.flags().isEmpty()) {
            d.put("tradabilityFlags", e.flags());
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static void removeKey(Object node, String key) {
        if (node instanceof Map<?, ?> m) {
            ((Map<String, Object>) m).remove(key);
            m.values().forEach(v -> removeKey(v, key));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> removeKey(v, key));
        }
    }

    /** Every key of the dossier, as a name and as a dotted path (lower case): what {@code signalsUsed} may cite. */
    static Set<String> signalKeys(Map<String, Object> dossier) {
        Set<String> keys = new HashSet<>();
        collect(dossier, "", keys);
        return keys;
    }

    private static void collect(Object node, String prefix, Set<String> keys) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                String name = String.valueOf(k).toLowerCase(Locale.ROOT);
                String path = prefix.isEmpty() ? name : prefix + "." + name;
                keys.add(name);
                keys.add(path);
                collect(v, path, keys);
            });
        }
    }

    /** The facts the validator's rules read. */
    static Facts facts(Evaluation e) {
        Map<String, Object> s = e.snapshot();
        double close = e.close();
        double high = nan(TechnicalScreener.path(s, "structure.week52.high"));
        double low = nan(TechnicalScreener.path(s, "structure.week52.low"));
        double range = Double.isFinite(high) && Double.isFinite(low) && high > low ? (close - low) / (high - low) : Double.NaN;
        Object index = TechnicalScreener.path(s, "marketRegime.indexAboveSma200");
        return new Facts(close, nan(TechnicalScreener.path(s, "trend.sma50")), nan(TechnicalScreener.path(s, "trend.sma200")),
                index instanceof Boolean b ? b : null, nan(TechnicalScreener.path(s, "relativeStrength.percentile6m")),
                range, Boolean.TRUE.equals(TechnicalScreener.path(s, "structure.base.detected")));
    }

    /** Key technicals of a candidate (the report's columns and the synthesis' view). */
    static Map<String, Object> metrics(Evaluation e) {
        Map<String, Object> s = e.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("close", e.close());
        put(m, "changePct", s, "price.changePct");
        put(m, "sma50", s, "trend.sma50");
        put(m, "sma200", s, "trend.sma200");
        put(m, "vsSma50Pct", s, "trend.priceVsMaPct.sma50");
        put(m, "vsSma200Pct", s, "trend.priceVsMaPct.sma200");
        put(m, "cross", s, "trend.sma50Vs200Cross.state");
        put(m, "adx14", s, "trend.adx14");
        put(m, "rsi14", s, "momentum.rsi14");
        put(m, "macdHistogram", s, "momentum.macd.histogram");
        put(m, "momentum12_1Pct", s, "momentum.momentum12_1Pct");
        put(m, "atrPct", s, "volatility.atrPct");
        put(m, "atrPctPercentile252", s, "volatility.atrPctPercentile252");
        put(m, "avgValue20", s, "volume.avgValue20");
        put(m, "upDownVolumeRatio50", s, "volume.upDownVolumeRatio50");
        put(m, "week52High", s, "structure.week52.high");
        put(m, "week52Low", s, "structure.week52.low");
        Facts f = facts(e);
        if (Double.isFinite(f.range52())) {
            m.put("range52Pct", Math.round(f.range52() * 1000) / 10.0);
        }
        put(m, "athDrawdownPct", s, "structure.allTimeHigh.drawdownPct");
        put(m, "baseDetected", s, "structure.base.detected");
        put(m, "return6mPct", s, "relativeStrength.return6mPct");
        put(m, "return12mPct", s, "relativeStrength.return12mPct");
        put(m, "percentile6m", s, "relativeStrength.percentile6m");
        put(m, "percentile12m", s, "relativeStrength.percentile12m");
        put(m, "rsChange50Pct", s, "relativeStrength.ratioChange50Pct");
        put(m, "tradingDays", s, "history.tradingDays");
        put(m, "firstDate", s, "history.firstDate");
        return m;
    }

    private static void put(Map<String, Object> m, String key, Map<String, Object> s, String path) {
        Object v = TechnicalScreener.path(s, path);
        if (v != null) {
            m.put(key, v);
        }
    }

    private static double nan(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    // ------------------------------------------------------------------ helpers

    /** Runs {@code fn} on every item, at most {@code llm.concurrency} at once; results in item order. */
    private <T, R> List<R> parallel(List<T> items, Function<T, R> fn, IntConsumer progress) {
        if (items.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(screening.llm().concurrency());
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
            log.warn("technical screening run {}: totals not saved: {}", id, e.getMessage());
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
        List<TechnicalLessons.Learned> learned = List.of();
        Map<String, List<String>> applied = Map.of();

        void add(String message) {
            messages.add(message);
        }

        void learned(List<TechnicalLessons.Learned> learned) {
            this.learned = learned;
        }

        void applied(Map<TechnicalAgent, List<String>> lessons) {
            Map<String, List<String>> m = new LinkedHashMap<>();
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
