package com.neracalab.backend.technical.screening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.neracalab.backend.analysis.Texts;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.UsageMeter;
import com.neracalab.backend.technical.idx.IdxTickSize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Final synthesis of a technical screening (one call of the synthesis model, Opus): executive summary, market notes,
 * and per candidate an action, a conviction, a thesis, one trade plan and an optional score adjustment of at most +-5
 * points. A trade plan that is not valid ({@link TradePlan#problems}) is replaced by the best agent's; without the model
 * (budget, failure) a deterministic summary is written and every candidate keeps its best agent's plan.
 */
@Component
public class TechnicalSynthesisAgent {

    private static final Logger log = LoggerFactory.getLogger(TechnicalSynthesisAgent.class);
    static final String STAGE = "SYNTHESIS";
    static final double MAX_ADJUSTMENT = 5;
    private static final Set<String> CONVICTIONS = Set.of("HIGH", "MEDIUM", "LOW");

    /**
     * One candidate as the synthesis sees it.
     *
     * @param agents per agent: final score, stance, trade plan, thesis
     */
    public record Row(int rank, String ticker, String company, String sector, double close, double overall,
                      double rulesOverall, Map<String, Object> agents, Map<String, Object> keyTechnicals,
                      List<String> flags) {
    }

    /**
     * The synthesis' view of a candidate.
     *
     * @param plan       its trade plan (the synthesis', else the best agent's)
     * @param planSource SYNTHESIS, or why the best agent's plan is used
     */
    public record StockView(String ticker, String action, String conviction, String thesis, TradePlan plan,
                            String planSource, double adjustment, String adjustmentReason) {
    }

    /**
     * @param model    the model that wrote it; null for the deterministic fallback
     * @param fallback why the fallback was used (null: the model wrote it)
     */
    public record Synthesis(String executiveSummary, List<String> marketNotes, List<StockView> stocks, String model,
                            String fallback) {
    }

    /** A candidate's fallback: its best agent's plan and its overall score. */
    public record Fallback(TradePlan plan, double overall) {
    }

    /** The model's reply (lenient: prices as numbers or {"price": ...}). */
    public record Reply(String executiveSummary, List<String> marketNotes, List<StockReply> stocks) {
    }

    public record StockReply(String ticker, String action, String conviction, String thesis, JsonNode buy, String buyType,
                      JsonNode sell, JsonNode stopLoss, Double adjustment, String adjustmentReason) {
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final TechnicalScreeningProperties properties;
    private final JsonReplies json;

    public TechnicalSynthesisAgent(LlmGateway llm, ScreeningProperties screening, TechnicalScreeningProperties properties,
                                   JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.properties = properties;
        this.json = new JsonReplies(mapper);
    }

    /**
     * @param context   run parameters and the market (exchange, scope, agents, top N, IHSG regime, breadth)
     * @param rows      top N and alternates, best first
     * @param fallbacks per ticker: its best agent's plan and overall score
     */
    public Synthesis synthesize(UsageMeter meter, Map<String, Object> context, List<Row> rows,
                                Map<String, Fallback> fallbacks, IdxTickSize ticks, boolean allowModel) {
        if (!allowModel) {
            return fallback(context, rows, fallbacks, "The cost budget did not allow the synthesis model");
        }
        Map<String, Object> input = new LinkedHashMap<>(context);
        input.put("candidates", rows);
        try {
            LlmGateway.Reply reply = llm.synthesis(meter, new Purpose(STAGE, "SYNTHESIS", null), List.of(
                    new SystemMessage(TechnicalPrompts.SYNTHESIS),
                    new UserMessage("TECHNICAL SCREENING RESULT\n" + json.write(input))), properties.synthesisMaxTokens());
            Reply parsed = json.parse(reply.text(), Reply.class);
            return normalize(parsed, rows, fallbacks, ticks, models.synthesisModel());
        } catch (LlmException | InvalidReplyException e) {
            log.warn("technical synthesis failed, deterministic summary used: {}", e.getMessage());
            return fallback(context, rows, fallbacks, "Synthesis model failed: " + e.getMessage());
        }
    }

    /** Known tickers only, one entry each, a known action and conviction, a valid plan, adjustment within +-5. */
    static Synthesis normalize(Reply r, List<Row> rows, Map<String, Fallback> fallbacks, IdxTickSize ticks, String model) {
        Map<String, Row> byTicker = rows.stream()
                .collect(Collectors.toMap(Row::ticker, x -> x, (a, b) -> a, LinkedHashMap::new));
        Map<String, StockView> views = new LinkedHashMap<>();
        if (r.stocks() != null) {
            for (StockReply v : r.stocks()) {
                if (v == null || v.ticker() == null) {
                    continue;
                }
                String ticker = v.ticker().trim().toUpperCase(Locale.ROOT);
                Row row = byTicker.get(ticker);
                if (row == null || views.containsKey(ticker)) {
                    continue;
                }
                String action = upper(v.action());
                String conviction = upper(v.conviction());
                double adjustment = v.adjustment() == null || !Double.isFinite(v.adjustment()) ? 0
                        : Math.max(-MAX_ADJUSTMENT, Math.min(MAX_ADJUSTMENT, Math.round(v.adjustment())));
                String type = v.buy() != null && v.buy().isObject() && v.buy().path("type").isString()
                        ? v.buy().path("type").asString() : v.buyType();
                Double buy = TechnicalAssessment.price(v.buy());
                Double sell = TechnicalAssessment.price(v.sell());
                Double stop = TechnicalAssessment.price(v.stopLoss());
                TradePlan plan = TradePlan.ofModel(buy, type, sell, stop, row.close(), ticks, "SYNTHESIS", model,
                        "the synthesis (" + model + ")");
                String planSource = "SYNTHESIS";
                Fallback fb = fallbacks.get(ticker);
                if (plan == null) {
                    List<String> problems = TradePlan.problems(buy, type, sell, stop, row.close());
                    plan = fb == null ? null : fb.plan();
                    planSource = "The synthesis' plan was not valid (" + String.join("; ", problems)
                            + "); the best agent's plan is used";
                }
                views.put(ticker, new StockView(ticker,
                        action != null && TechnicalAssessment.STANCES.contains(action) ? action
                                : TechnicalAssessment.stanceOf(row.overall()),
                        conviction != null && CONVICTIONS.contains(conviction) ? conviction : conviction(row.overall()),
                        Texts.clipOrNull(v.thesis(), 400), plan, planSource, adjustment,
                        adjustment == 0 ? null : Texts.clipOrNull(v.adjustmentReason(), 200)));
            }
        }
        for (Row row : rows) {
            Fallback fb = fallbacks.get(row.ticker());
            views.putIfAbsent(row.ticker(), new StockView(row.ticker(), TechnicalAssessment.stanceOf(row.overall()),
                    conviction(row.overall()), null, fb == null ? null : fb.plan(),
                    "Not covered by the synthesis; the best agent's plan is used", 0, null));
        }
        return new Synthesis(Texts.clipOrNull(r.executiveSummary(), 2000), Texts.clip(r.marketNotes(), 5, 300),
                List.copyOf(views.values()), model, null);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    static String conviction(double overall) {
        if (overall >= 70) {
            return "HIGH";
        }
        return overall >= 55 ? "MEDIUM" : "LOW";
    }

    /** Deterministic summary: ranking, actions, sector mix, the market's trend; every candidate keeps its best agent's plan. */
    static Synthesis fallback(Map<String, Object> context, List<Row> rows, Map<String, Fallback> fallbacks, String reason) {
        int topN = context.get("topN") instanceof Number n ? n.intValue() : rows.size();
        List<Row> top = rows.subList(0, Math.min(topN, rows.size()));
        Map<String, Long> actions = top.stream().collect(Collectors.groupingBy(
                r -> TechnicalAssessment.stanceOf(r.overall()), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> sectors = top.stream().collect(Collectors.groupingBy(
                r -> r.sector() == null ? "Unknown" : r.sector(), LinkedHashMap::new, Collectors.counting()));
        double avg = top.stream().mapToDouble(Row::overall).average().orElse(0);
        String leaders = top.stream().limit(5).map(r -> r.ticker() + " (" + Math.round(r.overall()) + ")")
                .collect(Collectors.joining(", "));
        String market = context.get("market") instanceof Map<?, ?> m && m.get("indexAboveSma200") instanceof Boolean above
                ? " IHSG is " + (above ? "above" : "below") + " its 200-day average." : "";
        String summary = top.isEmpty() ? "No stock qualified for the ranking."
                : "Top " + top.size() + " of the technical screening by overall score (average " + Math.round(avg)
                + "/100). Leaders: " + leaders + ". Actions: " + actions.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")) + "." + market
                + " This summary was generated without the synthesis model.";
        List<String> notes = new ArrayList<>();
        sectors.entrySet().stream().filter(e -> top.size() >= 5 && e.getValue() * 2 > top.size()).findFirst()
                .ifPresent(e -> notes.add("Sector concentration: " + e.getValue() + " of " + top.size() + " in "
                        + e.getKey() + "."));
        long flagged = top.stream().filter(r -> !r.flags().isEmpty()).count();
        if (flagged > 0) {
            notes.add(flagged + " of the selected stocks carry red flags (liquidity, stale prices, auto-rejection days); "
                    + "review them first.");
        }
        List<StockView> stocks = rows.stream().map(r -> {
            Fallback fb = fallbacks.get(r.ticker());
            return new StockView(r.ticker(), TechnicalAssessment.stanceOf(r.overall()), conviction(r.overall()), null,
                    fb == null ? null : fb.plan(), "Written without the synthesis model; the best agent's plan is used",
                    0, null);
        }).toList();
        return new Synthesis(summary, notes, stocks, null, reason);
    }
}
