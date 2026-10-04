package com.neracalab.backend.screening.agent;

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

import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;

import tools.jackson.databind.json.JsonMapper;

/**
 * Final synthesis of the top N (one call of the synthesis model, Opus): executive summary,
 * portfolio notes, a conviction and thesis per candidate and an optional score adjustment of at most
 * +-5 points. Without the model (budget, failure) a deterministic summary is written instead.
 */
@Component
public class SynthesisAgent {

    private static final Logger log = LoggerFactory.getLogger(SynthesisAgent.class);
    static final String STAGE = "SYNTHESIS";
    static final double MAX_ADJUSTMENT = 5;
    private static final Set<String> CONVICTIONS = Set.of("HIGH", "MEDIUM", "LOW");

    /** One candidate as the synthesis sees it. */
    public record Row(int rank, String ticker, String company, String sector, double overall,
                      Map<String, Double> agentScores, Map<String, String> verdicts, Map<String, Object> keyMetrics,
                      String newsSentiment, String newsSummary, List<String> concerns) {
    }

    public record StockView(String ticker, String conviction, String thesis, Double adjustment, String adjustmentReason) {
    }

    /**
     * @param model    the model that wrote it; null for the deterministic fallback
     * @param fallback why the fallback was used (null: the model wrote it)
     */
    public record Synthesis(String executiveSummary, List<String> portfolioNotes, List<StockView> stocks, String model,
                            String fallback) {
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm properties;
    private final JsonReplies json;

    public SynthesisAgent(LlmGateway llm, ScreeningProperties properties, JsonMapper mapper) {
        this.llm = llm;
        this.properties = properties.llm();
        this.json = new JsonReplies(mapper);
    }

    /**
     * @param context run parameters (exchange, tier, agents, top N)
     * @param rows    top N and alternates, best first
     */
    public Synthesis synthesize(UsageMeter meter, Map<String, Object> context, List<Row> rows, boolean allowModel) {
        if (!allowModel) {
            return fallback(context, rows, "The cost budget did not allow the synthesis model");
        }
        Map<String, Object> input = new LinkedHashMap<>(context);
        input.put("candidates", rows);
        try {
            LlmGateway.Reply reply = llm.synthesis(meter, new Purpose(STAGE, "SYNTHESIS", null), List.of(
                    new SystemMessage(ScreeningPrompts.SYNTHESIS),
                    new UserMessage("SCREENING RESULT\n" + json.write(input))));
            Synthesis parsed = json.parse(reply.text(), Synthesis.class);
            return normalize(parsed, rows, properties.synthesisModel());
        } catch (LlmException | InvalidReplyException e) {
            log.warn("synthesis failed, deterministic summary used: {}", e.getMessage());
            return fallback(context, rows, "Synthesis model failed: " + e.getMessage());
        }
    }

    /** Known tickers only, one entry each, conviction known, adjustment within +-5. */
    static Synthesis normalize(Synthesis s, List<Row> rows, String model) {
        Map<String, Row> byTicker = rows.stream().collect(Collectors.toMap(Row::ticker, r -> r, (a, b) -> a, LinkedHashMap::new));
        Map<String, StockView> views = new LinkedHashMap<>();
        if (s.stocks() != null) {
            for (StockView v : s.stocks()) {
                if (v == null || v.ticker() == null) {
                    continue;
                }
                String ticker = v.ticker().trim().toUpperCase(Locale.ROOT);
                Row row = byTicker.get(ticker);
                if (row == null || views.containsKey(ticker)) {
                    continue;
                }
                String conviction = v.conviction() == null ? null : v.conviction().trim().toUpperCase(Locale.ROOT);
                double adjustment = v.adjustment() == null || !Double.isFinite(v.adjustment()) ? 0
                        : Math.max(-MAX_ADJUSTMENT, Math.min(MAX_ADJUSTMENT, Math.round(v.adjustment())));
                views.put(ticker, new StockView(ticker,
                        CONVICTIONS.contains(conviction) ? conviction : conviction(row.overall()),
                        NewsBrief.clip(v.thesis(), 400), adjustment,
                        adjustment == 0 ? null : NewsBrief.clip(v.adjustmentReason(), 200)));
            }
        }
        for (Row row : rows) {
            views.putIfAbsent(row.ticker(), new StockView(row.ticker(), conviction(row.overall()), null, 0d, null));
        }
        return new Synthesis(NewsBrief.clip(s.executiveSummary(), 2000), NewsBrief.clip(s.portfolioNotes(), 5, 300),
                List.copyOf(views.values()), model, null);
    }

    static String conviction(double overall) {
        if (overall >= 70) {
            return "HIGH";
        }
        return overall >= 55 ? "MEDIUM" : "LOW";
    }

    /** Deterministic summary: ranking, sector mix, average score, news sentiment. */
    static Synthesis fallback(Map<String, Object> context, List<Row> rows, String reason) {
        int topN = context.get("topN") instanceof Number n ? n.intValue() : rows.size();
        List<Row> top = rows.subList(0, Math.min(topN, rows.size()));
        Map<String, Long> sectors = top.stream().collect(Collectors.groupingBy(
                r -> r.sector() == null ? "Unknown" : r.sector(), LinkedHashMap::new, Collectors.counting()));
        double avg = top.stream().mapToDouble(Row::overall).average().orElse(0);
        String leaders = top.stream().limit(5).map(r -> r.ticker() + " (" + Math.round(r.overall()) + ")")
                .collect(Collectors.joining(", "));
        String summary = top.isEmpty() ? "No stock qualified for the ranking."
                : "Top " + top.size() + " of the screening by overall score (average " + Math.round(avg) + "/100). Leaders: "
                + leaders + ". Sectors: " + sectors.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ")) + ". This summary was generated without the synthesis model.";
        List<String> notes = new ArrayList<>();
        sectors.entrySet().stream().filter(e -> top.size() >= 5 && e.getValue() * 2 > top.size()).findFirst()
                .ifPresent(e -> notes.add("Sector concentration: " + e.getValue() + " of " + top.size() + " in " + e.getKey() + "."));
        long negative = top.stream().filter(r -> "NEGATIVE".equals(r.newsSentiment())).count();
        if (negative > 0) {
            notes.add(negative + " of the selected stocks have negative news; review them first.");
        }
        List<StockView> stocks = rows.stream().map(r -> new StockView(r.ticker(), conviction(r.overall()), null, 0d, null))
                .toList();
        return new Synthesis(summary, notes, stocks, null, reason);
    }
}
