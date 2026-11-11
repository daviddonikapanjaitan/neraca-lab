package com.neracalab.backend.technical.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Technical Score of a listing: the four preset summaries of Companies &gt; Technicals ({@link PresetSummaries}:
 * Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller) as 0-100 scores and their overall. No model is called.
 * <p>
 * An agent's score is its preset's rules score on 0-100: {@code 50 + 50 x score / maxScore}, clamped (the prior of
 * the technical screening's agents). The overall is the plain average of the four, and its stance follows the share of
 * the maximum ({@code (overall - 50) / 50}) the way Druckenmiller's rule does: Buy from 1/2, Accumulate from 1/6, Wait
 * from -1/6, Avoid below.
 */
public final class TechnicalScore {

    /** The stances in order, best first. */
    public static final List<String> STANCES = List.of("BUY", "ACCUMULATE", "WAIT", "AVOID");

    /**
     * @param preset murphy, gill, ptj, druckenmiller
     * @param score  0-100
     * @param raw    the preset's rules score (-maxScore .. +maxScore)
     * @param stance the preset's own stance (BUY, ACCUMULATE, WAIT, AVOID)
     */
    public record Agent(String preset, String name, double score, double raw, double maxScore, String stance) {
    }

    /**
     * @param overall the average of the agents' scores (0-100); null when there is no summary
     * @param stance  the stance of the overall (BUY, ACCUMULATE, WAIT, AVOID); null with it
     * @param stances how many agents take each stance
     * @param summary a sentence on the overall built from the numbers
     */
    public record Score(Double overall, String stance, List<Agent> agents, Map<String, Integer> stances, String summary) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("overall", overall);
            m.put("stance", stance);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Agent a : agents) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("preset", a.preset());
                am.put("name", a.name());
                am.put("score", a.score());
                am.put("raw", a.raw());
                am.put("maxScore", a.maxScore());
                am.put("stance", a.stance());
                list.add(am);
            }
            m.put("agents", list);
            m.put("stances", stances);
            m.put("summary", summary);
            m.put("method", "Each agent: 50 + 50 x its rules score / its maximum (0-100). Overall: the average of the "
                    + "agents; Buy from 75, Accumulate from 58.3, Wait from 41.7, Avoid below. Rules only, no AI.");
            return m;
        }
    }

    private TechnicalScore() {
    }

    /** A preset's score as 0-100: 50 + 50 x score / its maximum, clamped; 50 when it has no maximum. */
    public static double agentScore(Object score, Object maxScore) {
        Double s = number(score);
        Double max = number(maxScore);
        if (s == null || max == null || max <= 0) {
            return 50;
        }
        return round1(Math.max(0, Math.min(100, 50 + 50 * s / max)));
    }

    /** The stance of an overall score (0-100). */
    public static String stance(double overall) {
        double share = (overall - 50) / 50;
        return share >= 0.5 ? "BUY" : share >= 1.0 / 6 ? "ACCUMULATE" : share >= -1.0 / 6 ? "WAIT" : "AVOID";
    }

    /** The Technical Score of a {@link PresetSummaries#summarize} document (as a map, or read back from JSON). */
    public static Score of(Map<String, Object> summary) {
        List<Agent> agents = new ArrayList<>();
        if (summary.get("summaries") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("preset") != null) {
                    Double max = number(m.get("maxScore"));
                    Double raw = number(m.get("score"));
                    agents.add(new Agent(m.get("preset").toString(), String.valueOf(m.get("name")),
                            agentScore(m.get("score"), m.get("maxScore")), raw == null ? 0 : raw, max == null ? 0 : max,
                            m.get("stance") == null ? null : m.get("stance").toString()));
                }
            }
        }
        Map<String, Integer> stances = new LinkedHashMap<>();
        STANCES.forEach(s -> stances.put(s, 0));
        agents.forEach(a -> {
            if (a.stance() != null) {
                stances.merge(a.stance(), 1, Integer::sum);
            }
        });
        if (agents.isEmpty()) {
            return new Score(null, null, List.of(), stances, "No preset summary: too little price history.");
        }
        double overall = round1(agents.stream().mapToDouble(Agent::score).average().orElse(50));
        String stance = stance(overall);
        return new Score(overall, stance, List.copyOf(agents), stances, sentence(overall, stance, agents, stances));
    }

    /** "Overall 62.5 (Accumulate): 2 of 4 agents Buy or Accumulate. Strongest Murphy 85.7 (Buy), weakest ..." */
    private static String sentence(double overall, String stance, List<Agent> agents, Map<String, Integer> stances) {
        int positive = stances.get("BUY") + stances.get("ACCUMULATE");
        Agent best = agents.stream().max(Comparator.comparingDouble(Agent::score)).orElseThrow();
        Agent worst = agents.stream().min(Comparator.comparingDouble(Agent::score)).orElseThrow();
        StringBuilder b = new StringBuilder();
        b.append("Overall ").append(fmt(overall)).append(" (").append(label(stance)).append("): ")
                .append(positive).append(" of ").append(agents.size()).append(positive == 1 ? " agents says" : " agents say")
                .append(" Buy or Accumulate");
        List<String> counts = new ArrayList<>();
        for (String s : STANCES) {
            int n = stances.get(s);
            if (n > 0) {
                counts.add(n + " " + label(s));
            }
        }
        b.append(" (").append(String.join(", ", counts)).append(").");
        if (best != worst && best.score() != worst.score()) {
            b.append(" Strongest ").append(best.name()).append(" ").append(fmt(best.score())).append(" (")
                    .append(label(best.stance())).append("), weakest ").append(worst.name()).append(" ")
                    .append(fmt(worst.score())).append(" (").append(label(worst.stance())).append(").");
        } else {
            b.append(" Every agent scores ").append(fmt(best.score())).append(".");
        }
        long agree = agents.stream().filter(a -> stance.equals(a.stance())).count();
        if (agree == agents.size()) {
            b.append(" All agents agree.");
        } else if (positive > 0 && positive < agents.size()) {
            b.append(" The agents disagree: read their evidence before acting.");
        }
        return b.toString();
    }

    /** BUY -> Buy */
    public static String label(String stance) {
        if (stance == null) {
            return "—";
        }
        return stance.charAt(0) + stance.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static Double number(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
