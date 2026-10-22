package com.neracalab.backend.macro.analysis;

import java.time.Instant;
import java.time.LocalDate;
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

import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.macro.analysis.MacroAnalysisRepository.Parameters;
import com.neracalab.backend.macro.analysis.MacroPanel.Outcome;
import com.neracalab.backend.macro.analysis.MacroPanel.Prior;
import com.neracalab.backend.macro.analysis.MacroSynthesisAgent.AgentRow;
import com.neracalab.backend.macro.analysis.MacroSynthesisAgent.MacroSynthesis;
import com.neracalab.backend.macro.analysis.MacroValidator.Facts;
import com.neracalab.backend.macro.analysis.MacroValidator.Issue;
import com.neracalab.backend.macro.outlook.MacroOutlookService;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningProperties.ModelPrice;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One AI macro analysis of a country's stock market, end to end (called by the {@link MacroAnalysisQueue} worker):
 * <ol>
 *   <li><b>Macro data</b> (no model): the Macro page's rules over the stored macro series - regimes, the reading of
 *       every series, the four investor presets, the rules' investability score - the country's indexes and the
 *       commodities and currency pairs ({@link MacroOutlookService}).</li>
 *   <li><b>Research agent</b> (ReAct + tool calling over the stored histories, {@link MacroResearchAgent}): a brief
 *       with references.</li>
 *   <li><b>Macro agents</b> (Paul Tudor Jones, Stanley Druckenmiller, George Soros, Ray Dalio): one independent call
 *       each, sharing the system prompt and the dossier (cacheable prefix); <b>Reflection</b>: the validator checks
 *       every answer, a critic reviews the flagged ones; <b>Reflexion</b>: lessons of earlier analyses are in the
 *       prompts, the issues of this one become lessons.</li>
 *   <li><b>Score</b>: per agent {@code prior-weight x its rules preset + (1 - prior-weight) x its judgement};
 *       investability = {@code (1 - rules-weight) x the agents' mean + rules-weight x the rules score}.</li>
 *   <li><b>Synthesis</b> (Opus): summary, conviction, bull / bear case, risks, sector tilts, at most +-5 points.</li>
 * </ol>
 * Every model call is metered; the analysis stays within {@code neracalab.macro-analysis.budget-usd} by skipping
 * optional calls (an agent without a call keeps its rules prior).
 */
@Service
public class MacroAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(MacroAnalysisService.class);
    /** Investability: from this score up investable, below {@link #NOT_INVESTABLE} not (as the rules). */
    static final double INVESTABLE = 60;
    static final double NOT_INVESTABLE = 40;

    private final MacroOutlookService outlook;
    private final MacroResearchAgent research;
    private final MacroPanel panel;
    private final MacroSynthesisAgent synthesis;
    private final MacroLessons lessons;
    private final MacroAnalysisRepository repository;
    private final IngestionJobRepository jobs;
    private final ScreeningProperties screening;
    private final MacroAnalysisProperties properties;
    private final JsonReplies json;

    public MacroAnalysisService(MacroOutlookService outlook, MacroResearchAgent research, MacroPanel panel,
                                MacroSynthesisAgent synthesis, MacroLessons lessons, MacroAnalysisRepository repository,
                                IngestionJobRepository jobs, ScreeningProperties screening,
                                MacroAnalysisProperties properties, JsonMapper mapper) {
        this.outlook = outlook;
        this.research = research;
        this.panel = panel;
        this.synthesis = synthesis;
        this.lessons = lessons;
        this.repository = repository;
        this.jobs = jobs;
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

    public void run(UUID id) {
        Parameters p = repository.parameters(id)
                .orElseThrow(() -> new IllegalStateException("Macro analysis " + id + " not found"));
        UsageMeter meter = new UsageMeter(id, p.budgetUsd(), repository::insertUsage);
        Notes notes = new Notes();
        try {
            execute(p, meter, notes);
        } catch (InterruptedRunException e) {
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Interrupted", e.getMessage(), null);
        } catch (RuntimeException e) {
            log.error("macro analysis {} failed", id, e);
            notes.add("The analysis failed: " + message(e));
            saveQuietly(id, meter, notes);
            jobs.finish(id, IngestionJobStatus.FAILED, "Failed", message(e), null);
        }
    }

    @SuppressWarnings("unchecked")
    private void execute(Parameters p, UsageMeter meter, Notes notes) {
        UUID id = p.id();

        // ---- macro data from the database (the Macro page's rules), no model
        jobs.running(id, "Reading the macro data, indexes, commodities and currency pairs from the database");
        MacroOutlookService.Choice choice = outlook.choice(p.country(), p.indexCode());
        String subject = choice.countryName() + " (" + choice.index() + ")";
        Map<String, Object> view = outlook.outlook(choice.country(), choice.index(), "1Y");
        Map<String, Object> context = context(choice, view);
        Map<String, Object> conclusion = (Map<String, Object>) view.get("conclusion");
        Double rulesScore = number(conclusion == null ? null : conclusion.get("score"));
        LocalDate asOf = LocalDate.parse(String.valueOf(view.get("asOfDate")));
        repository.saveContext(id, context, asOf, rulesScore);
        for (Object note : (List<Object>) view.getOrDefault("notes", List.of())) {
            notes.add("Data: " + note);
        }
        Map<String, Object> markets = (Map<String, Object>) view.get("markets");
        if (markets == null || ((List<?>) markets.getOrDefault("commodities", List.of())).isEmpty()) {
            notes.add("No commodity prices are stored (Ingestion > Price Ingestion, market Commodities): the agents had "
                    + "no terms of trade.");
        }
        checkInterrupted();

        double reserve = properties.synthesisReserveUsd();
        ModelPrice researchPrice = screening.llm().price(screening.llm().researchModel());
        ModelPrice agentPrice = screening.llm().price(screening.llm().agentModel());

        // ---- research agent (ReAct + tools over the stored histories)
        progress(id, meter, "Research agent: reading the history of the macro series, commodities and indexes");
        MacroOutlookService.History history = outlook.history(choice.country());
        Map<String, MacroTools.Meta> meta = new LinkedHashMap<>();
        history.info().forEach((code, info) -> meta.put(code, new MacroTools.Meta(info.name(), info.unit(), info.rate(), info.flow())));
        MacroTools tools = new MacroTools(history.macro(), history.prices(), meta, asOf, properties);
        boolean allowResearch = meter.allows(researchPrice.cost(30_000, 3_000), reserve);
        MacroResearchAgent.Result r = research.research(meter, subject, researchSummary(view, asOf), tools, allowResearch);
        boolean researchDegraded = r.note() != null;
        if (r.note() != null) {
            notes.add("Research: " + r.note() + ".");
        }
        if (r.droppedRefs() > 0) {
            notes.add(r.droppedRefs() + " research evidence items cited tool results that were never returned and were dropped.");
        }
        repository.saveResearch(id, researchDocument(r));
        checkInterrupted();

        // ---- the dossier every macro agent shares (cacheable prefix)
        Map<String, Object> dossier = new LinkedHashMap<>(context);
        dossier.put("research", r.brief());
        String dossierText = "MACRO DOSSIER\n" + json.write(dossier);
        Set<String> signalKeys = signalKeys(json.tree(json.write(dossier)), meta.keySet());
        Facts facts = facts(view);

        // ---- macro agents (independent, one call each)
        Map<MacroAgent, List<String>> learnedBefore = new EnumMap<>(MacroAgent.class);
        Map<MacroAgent, Prior> priors = new EnumMap<>(MacroAgent.class);
        for (MacroAgent agent : p.agents()) {
            learnedBefore.put(agent, lessons.lessons(agent));
            priors.put(agent, prior(view, agent));
        }
        AtomicInteger budgetSkipped = new AtomicInteger();
        List<Outcome> outcomes = parallel(p.agents(), agent -> {
            if (!meter.allows(agentPrice.cost(12_000, properties.agentMaxTokens()), reserve)) {
                budgetSkipped.incrementAndGet();
                return new Outcome(agent, null, null, List.of(), "Cost budget reached");
            }
            return panel.assess(meter, subject, dossierText, agent, priors.get(agent), facts, learnedBefore.get(agent),
                    signalKeys, properties.agentMaxTokens());
        }, done -> progress(id, meter, "Macro agents: " + done + " of " + p.agents().size() + " assessments"));
        Map<MacroAgent, Outcome> byAgent = new EnumMap<>(MacroAgent.class);
        for (int i = 0; i < p.agents().size(); i++) {
            byAgent.put(p.agents().get(i), outcomes.get(i));
        }

        // ---- Reflection: the critic reviews the flagged answers
        List<MacroAgent> flagged = p.agents().stream()
                .filter(a -> byAgent.get(a).original() != null && !byAgent.get(a).issues().isEmpty()).toList();
        AtomicInteger unreviewed = new AtomicInteger();
        List<Outcome> reviewed = parallel(flagged, agent -> {
            Outcome o = byAgent.get(agent);
            if (!meter.allows(agentPrice.cost(13_000, properties.agentMaxTokens()), reserve)) {
                unreviewed.incrementAndGet();
                return o;
            }
            return panel.reflect(meter, subject, dossierText, priors.get(agent), learnedBefore.get(agent), o,
                    properties.agentMaxTokens());
        }, done -> progress(id, meter, "Reflection: reviewing " + flagged.size() + " flagged assessments (" + done + "/"
                + flagged.size() + ")"));
        for (int i = 0; i < flagged.size(); i++) {
            byAgent.put(flagged.get(i), reviewed.get(i));
        }
        if (budgetSkipped.get() > 0) {
            notes.add(budgetSkipped.get() + " agents were not asked (cost budget reached); they keep their rules prior.");
        }
        long failed = byAgent.values().stream().filter(o -> o.original() == null && o.error() != null
                && !"Cost budget reached".equals(o.error())).count();
        if (failed > 0) {
            notes.add(failed + " agents gave no usable answer; they keep their rules prior.");
        }
        if (unreviewed.get() > 0) {
            notes.add(unreviewed.get() + " flagged assessments were not reviewed (cost budget reached).");
        }
        checkInterrupted();

        // ---- scores
        Map<MacroAgent, Double> finals = new EnumMap<>(MacroAgent.class);
        for (MacroAgent agent : p.agents()) {
            Double fin = blend(priors.get(agent).score(), score(byAgent.get(agent).effective()), properties.priorWeight());
            if (fin != null) {
                finals.put(agent, fin);
            }
        }
        Double agentsMean = finals.isEmpty() ? null
                : round1(finals.values().stream().mapToDouble(Double::doubleValue).average().orElse(0));
        Double overall = agentsMean == null ? null : rulesScore == null ? agentsMean
                : round1((1 - properties.rulesWeight()) * agentsMean + properties.rulesWeight() * rulesScore);

        // ---- synthesis (Opus)
        progress(id, meter, "Synthesis (" + screening.llm().synthesisModel() + ")");
        List<AgentRow> rows = new ArrayList<>();
        for (MacroAgent agent : p.agents()) {
            Outcome o = byAgent.get(agent);
            MacroAssessment a = o.effective();
            rows.add(new AgentRow(agent.name(), agent.label(), priors.get(agent).score(), score(a), finals.get(agent),
                    a == null ? null : a.stance(), a == null ? null : a.thesis(),
                    a == null ? List.of() : a.supports(), a == null ? List.of() : a.risks(),
                    o.issues().stream().map(Issue::message).toList()));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("subject", context.get("subject"));
        input.put("asOfDate", asOf.toString());
        input.put("investabilityScore", overall);
        input.put("investabilityVerdict", overall == null ? null : verdict(overall));
        input.put("agentsMean", agentsMean);
        input.put("rulesConclusion", conclusion == null ? null : compactConclusion(conclusion));
        input.put("regimes", view.get("regimes"));
        input.put("index", context.get("index"));
        input.put("indexes", context.get("indexes"));
        input.put("termsOfTrade", markets == null ? null : markets.get("termsOfTrade"));
        input.put("agents", rows);
        input.put("research", r.brief());
        input.put("dataNotes", List.copyOf(notes.messages));
        ModelPrice synthesisPrice = screening.llm().price(screening.llm().synthesisModel());
        boolean allowSynthesis = meter.allows(synthesisPrice.cost(8_000, 3_000), 0);
        MacroSynthesis syn = synthesis.synthesize(meter, subject, input, rows, r.brief(), overall, allowSynthesis);
        if (syn.fallback() != null) {
            notes.add("Summary written without the synthesis model: " + syn.fallback());
        }
        double adjustment = syn.adjustment() == null ? 0 : syn.adjustment();
        Double finalScore = overall == null ? null : clamp(overall + adjustment);
        String verdict = finalScore == null ? null : verdict(finalScore);
        String action = finalScore == null ? null : action(finalScore, facts.aboveSma200(), facts.liquidity());

        // ---- save the report
        jobs.progress(id, "Saving the report", null, null);
        Map<MacroAgent, List<Issue>> issues = new EnumMap<>(MacroAgent.class);
        for (MacroAgent agent : p.agents()) {
            Outcome o = byAgent.get(agent);
            issues.put(agent, o.issues());
            saveAgent(id, agent, o, priors.get(agent), finals.get(agent));
        }
        notes.learned(lessons.learn(id, issues));
        notes.applied(learnedBefore);
        Map<String, Object> synthesisDoc = new LinkedHashMap<>();
        synthesisDoc.put("executiveSummary", syn.executiveSummary());
        synthesisDoc.put("conviction", syn.conviction());
        synthesisDoc.put("thesis", syn.thesis());
        synthesisDoc.put("bullCase", syn.bullCase());
        synthesisDoc.put("bearCase", syn.bearCase());
        synthesisDoc.put("keyRisks", syn.keyRisks());
        synthesisDoc.put("monitor", syn.monitor());
        synthesisDoc.put("sectorTilts", syn.sectorTilts());
        synthesisDoc.put("dataGaps", syn.dataGaps());
        synthesisDoc.put("adjustmentReason", syn.adjustmentReason());
        synthesisDoc.put("agentsMean", agentsMean);
        synthesisDoc.put("model", syn.model());
        synthesisDoc.put("fallback", syn.fallback());
        repository.saveResult(id, new MacroAnalysisRepository.Result(rulesScore, finalScore,
                adjustment == 0 ? null : adjustment, verdict, action, syn.conviction(), synthesisDoc,
                notes.document(meter, flagged.size())));
        repository.saveTotals(id, meter.total());

        String summary = subject + ": " + (finalScore == null ? "no score"
                : "investability " + String.format(Locale.ROOT, "%.1f", finalScore) + " (" + verdict + "), " + action)
                + (syn.conviction() == null ? "" : ", conviction " + syn.conviction()) + ", $" + cents(meter.spentUsd());
        boolean degraded = syn.fallback() != null || budgetSkipped.get() > 0 || failed > 0 || researchDegraded
                || finalScore == null;
        if (degraded) {
            jobs.finish(id, IngestionJobStatus.INCOMPLETE, summary, String.join(" ", notes.messages.stream()
                    .filter(m -> !m.startsWith("Data: ")).toList()), null);
        } else {
            jobs.finish(id, IngestionJobStatus.SUCCEEDED, summary, null, null);
        }
        log.info("macro analysis {} finished: {}", id, summary);
    }

    // ------------------------------------------------------------------ data

    /**
     * What the agents see (and the report keeps): the Macro page's view without its chart points and its dropdown
     * lists - the as-of date, the index and the country's other indexes, the regimes, the reading of every series, the
     * presets, the rules' conclusion, the commodities and currency pairs.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> context(MacroOutlookService.Choice choice, Map<String, Object> view) {
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, Object> subject = new LinkedHashMap<>();
        subject.put("country", choice.country());
        subject.put("countryName", choice.countryName());
        subject.put("index", choice.index());
        subject.put("indexName", choice.indexName());
        c.put("subject", subject);
        c.put("asOfDate", view.get("asOfDate"));
        c.put("index", withoutPoints(view.get("index")));
        c.put("indexes", view.get("indexes"));
        c.put("regimes", view.get("regimes"));
        List<Map<String, Object>> panels = new ArrayList<>();
        for (Object o : (List<Object>) view.getOrDefault("panels", List.of())) {
            Map<String, Object> panel = (Map<String, Object>) withoutPoints(o);
            panel.remove("digits");
            panels.add(panel);
        }
        c.put("series", panels);
        List<Map<String, Object>> presets = new ArrayList<>();
        for (Object o : (List<Object>) view.getOrDefault("presets", List.of())) {
            presets.add((Map<String, Object>) o);
        }
        c.put("presets", presets);
        c.put("rulesConclusion", view.get("conclusion"));
        Map<String, Object> markets = (Map<String, Object>) view.get("markets");
        if (markets != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("termsOfTrade", markets.get("termsOfTrade"));
            m.put("commodities", ((List<Object>) markets.getOrDefault("commodities", List.of())).stream()
                    .map(MacroAnalysisService::withoutPoints).toList());
            m.put("currencyPairs", ((List<Object>) markets.getOrDefault("forex", List.of())).stream()
                    .map(MacroAnalysisService::withoutPoints).toList());
            c.put("markets", m);
        }
        c.put("rulesVersion", view.get("rulesVersion"));
        return c;
    }

    /** A copy of a map without its chart points. */
    @SuppressWarnings("unchecked")
    static Object withoutPoints(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return o;
        }
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) m);
        copy.remove("points");
        return copy;
    }

    /** What the research agent is told about the dossier: the as-of date, the regimes and the rules' score. */
    @SuppressWarnings("unchecked")
    static String researchSummary(Map<String, Object> view, LocalDate asOf) {
        Map<String, Object> regimes = (Map<String, Object>) view.getOrDefault("regimes", Map.of());
        Map<String, Object> conclusion = (Map<String, Object>) view.getOrDefault("conclusion", Map.of());
        Map<String, Object> index = (Map<String, Object>) view.getOrDefault("index", Map.of());
        StringBuilder b = new StringBuilder("As of ").append(asOf).append(" (only data published by then).");
        for (String key : List.of("liquidity", "risk")) {
            Map<String, Object> regime = (Map<String, Object>) regimes.get(key);
            if (regime != null) {
                b.append(' ').append(key.substring(0, 1).toUpperCase(Locale.ROOT)).append(key.substring(1))
                        .append(" regime (rules): ").append(regime.get("state") == null ? "unknown" : regime.get("state"))
                        .append(" (score ").append(regime.get("score")).append(" of ").append(regime.get("signals"))
                        .append(").");
            }
        }
        b.append(" Rules investability score: ").append(conclusion.get("score")).append("/100 (")
                .append(conclusion.get("verdict")).append(", ").append(conclusion.get("action")).append(").");
        b.append(" Index ").append(index.get("code")).append(": ").append(index.get("close")).append(", ")
                .append(index.get("vsSma200Pct")).append("% against its 200-day average, 1Y ")
                .append(index.get("change1yPct")).append("%.");
        return b.toString();
    }

    /** The rules' conclusion without its conditions lists (the synthesis' input). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> compactConclusion(Map<String, Object> conclusion) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : List.of("score", "verdict", "action", "reason", "summary", "watch")) {
            m.put(key, conclusion.get(key));
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (Object o : (List<Object>) conclusion.getOrDefault("components", List.of())) {
            Map<String, Object> part = (Map<String, Object>) o;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", part.get("name"));
            pm.put("weight", part.get("weight"));
            pm.put("value", part.get("value"));
            parts.add(pm);
        }
        m.put("components", parts);
        return m;
    }

    /** The agent's prior: its preset's score on 0-100 (50 + 50 x score / its signals), stance and label. */
    @SuppressWarnings("unchecked")
    static Prior prior(Map<String, Object> view, MacroAgent agent) {
        for (Object o : (List<Object>) view.getOrDefault("presets", List.of())) {
            Map<String, Object> preset = (Map<String, Object>) o;
            if (!agent.preset().equals(preset.get("preset"))) {
                continue;
            }
            Double score = number(preset.get("score"));
            Double max = number(preset.get("maxScore"));
            Double prior = score == null || max == null || max <= 0 ? null
                    : clamp(50 + 50 * Math.max(-1, Math.min(1, score / max)));
            String text = score == null ? "no score" : (score > 0 ? "+" : "") + Math.round(score) + " of "
                    + Math.round(max == null ? 0 : max);
            return new Prior(prior, (String) preset.get("stance"), (String) preset.get("stanceLabel"), text);
        }
        return new Prior(null, null, null, "no preset");
    }

    /** The dossier facts the validator's rules check. */
    @SuppressWarnings("unchecked")
    static Facts facts(Map<String, Object> view) {
        Map<String, Object> index = (Map<String, Object>) view.getOrDefault("index", Map.of());
        Map<String, Object> regimes = (Map<String, Object>) view.getOrDefault("regimes", Map.of());
        Map<String, Object> liquidity = (Map<String, Object>) regimes.getOrDefault("liquidity", Map.of());
        String loop = null;
        String quadrant = null;
        for (Object o : (List<Object>) view.getOrDefault("presets", List.of())) {
            Map<String, Object> preset = (Map<String, Object>) o;
            if ("soros".equals(preset.get("preset"))) {
                loop = (String) preset.get("loop");
            } else if ("dalio".equals(preset.get("preset"))) {
                quadrant = (String) preset.get("quadrant");
            }
        }
        Object above = index.get("aboveSma200");
        return new Facts(above instanceof Boolean b ? b : null, number(index.get("vsSma200Pct")),
                (String) liquidity.get("state"), loop, quadrant);
    }

    /** Every field name and string value of the dossier (upper case), with the codes of the tools. */
    static Set<String> signalKeys(JsonNode node, Set<String> codes) {
        Set<String> keys = new LinkedHashSet<>();
        codes.forEach(c -> keys.add(c.toUpperCase(Locale.ROOT)));
        collect(node, keys);
        return keys;
    }

    private static void collect(JsonNode node, Set<String> keys) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                keys.add(e.getKey().toUpperCase(Locale.ROOT));
                JsonNode value = e.getValue();
                if (value.isString() && value.asString().length() <= 40) {
                    keys.add(value.asString().toUpperCase(Locale.ROOT));
                }
                collect(value, keys);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, keys));
        }
    }

    private static Map<String, Object> researchDocument(MacroResearchAgent.Result r) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("brief", r.brief());
        doc.put("trace", r.trace());
        doc.put("retrieved", r.retrieved());
        doc.put("modelUsed", r.modelUsed());
        doc.put("droppedRefs", r.droppedRefs());
        doc.put("note", r.note());
        return doc;
    }

    private void saveAgent(UUID id, MacroAgent agent, Outcome o, Prior prior, Double finalScore) {
        MacroAssessment a = o.effective();
        String status = a != null ? (o.revised() != null ? "REVISED" : "ASSESSED")
                : prior.score() != null ? "PRIOR_ONLY" : "NO_SCORE";
        Map<String, Object> priorDetail = new LinkedHashMap<>();
        priorDetail.put("stance", prior.stance());
        priorDetail.put("stanceLabel", prior.stanceLabel());
        priorDetail.put("preset", prior.presetText());
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
        repository.saveAgentScore(id, new MacroAnalysisRepository.AgentRow(agent, prior.score(), priorDetail, score(a),
                finalScore, a == null ? null : a.stance(), a == null ? null : a.thesis(),
                a == null ? List.of() : a.supports(), a == null ? List.of() : a.risks(),
                a == null ? List.of() : a.buyWhen(), a == null ? List.of() : a.sellWhen(), reflection, status));
    }

    // ------------------------------------------------------------------ scores

    /** prior-weight x prior + (1 - prior-weight) x judgement; either alone when the other is missing. */
    static Double blend(Double prior, Double judgement, double priorWeight) {
        if (prior != null && judgement != null) {
            return round1(priorWeight * prior + (1 - priorWeight) * judgement);
        }
        return judgement != null ? judgement : prior;
    }

    /** INVESTABLE from 60, SELECTIVE from 40, else NOT_INVESTABLE (as the rules). */
    static String verdict(double score) {
        return score >= INVESTABLE ? "INVESTABLE" : score >= NOT_INVESTABLE ? "SELECTIVE" : "NOT_INVESTABLE";
    }

    /**
     * The action of the rules' table: 60+ BUY above the 200-day average, ACCUMULATE below it; 40 - 59 ACCUMULATE above
     * it while liquidity is not tightening, else WAIT; below 40 SELL below it, WAIT above it.
     */
    static String action(double score, Boolean aboveSma200, String liquidity) {
        if (score >= INVESTABLE) {
            return Boolean.TRUE.equals(aboveSma200) ? "BUY" : "ACCUMULATE";
        }
        if (score >= NOT_INVESTABLE) {
            return Boolean.TRUE.equals(aboveSma200) && !"TIGHTENING".equals(liquidity) ? "ACCUMULATE" : "WAIT";
        }
        return Boolean.FALSE.equals(aboveSma200) ? "SELL" : "WAIT";
    }

    private static Double score(MacroAssessment a) {
        return a == null ? null : a.score();
    }

    private static Double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
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
            log.warn("macro analysis {}: totals not saved: {}", id, e.getMessage());
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

    /** Notes of an analysis: messages, lessons learned and applied, budget use. */
    private static final class Notes {
        final List<String> messages = new CopyOnWriteArrayList<>();
        List<MacroLessons.Learned> learned = List.of();
        Map<String, List<String>> applied = Map.of();

        void add(String message) {
            messages.add(message);
        }

        void learned(List<MacroLessons.Learned> learned) {
            this.learned = learned;
        }

        void applied(Map<MacroAgent, List<String>> lessons) {
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
