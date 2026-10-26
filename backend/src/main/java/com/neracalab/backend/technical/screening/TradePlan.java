package com.neracalab.backend.technical.screening;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.neracalab.backend.technical.idx.IdxTickSize;

/**
 * A trade plan, as the preset summaries of Companies > Technicals give it: where to buy (a LIMIT at or below the close
 * - a pullback - or a STOP above it - a breakout), where to sell (the target) and where the idea is wrong (the stop
 * loss). Every price is a valid exchange price ({@link IdxTickSize}) and stop &lt; buy &lt; sell.
 * <p>
 * A plan written by a model is only kept when it passes {@link #problems}: complete, ordered, the buy on the right
 * side of the close for its type and every level within a sane distance (buy within -40% / +30% of the close, at most
 * 40% risk below the buy, a target at most 3 times the buy).
 *
 * @param source RULES (a preset's rules summary), AI (an agent's own levels) or SYNTHESIS (the final synthesis)
 * @param by     who set it: an agent's name for AI / RULES, the synthesis model for SYNTHESIS
 */
public record TradePlan(Level buy, Level sell, Level stopLoss, Double rewardRisk, String source, String by) {

    /** LIMIT tolerance: a pullback buy may sit this far above the close (the close itself is a valid limit). */
    static final double LIMIT_ABOVE = 1.02;
    /** STOP tolerance: a breakout buy may sit this far below the close. */
    static final double STOP_BELOW = 0.98;
    static final double MIN_BUY = 0.6;
    static final double MAX_BUY = 1.3;
    static final double MAX_RISK = 0.4;
    static final double MAX_TARGET = 3;

    /**
     * One level.
     *
     * @param type        LIMIT or STOP (the buy; a sell is a LIMIT, the stop loss a STOP)
     * @param basis       why this price (a support zone, the 20-day high, 2 ATR below the entry, ...)
     * @param distancePct the level against the close, percent
     */
    public record Level(double price, String type, String basis, Double distancePct) {
    }

    /** Whether the plan has all three prices. */
    public boolean complete() {
        return buy != null && sell != null && stopLoss != null;
    }

    /** The plan as stored and shown (the shape of the preset summaries' prices). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("buy", level(buy));
        m.put("sell", level(sell));
        m.put("stopLoss", level(stopLoss));
        m.put("rewardRisk", rewardRisk);
        m.put("source", source);
        m.put("by", by);
        return m;
    }

    private static Map<String, Object> level(Level l) {
        if (l == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("price", l.price());
        m.put("type", l.type());
        m.put("basis", l.basis());
        m.put("distancePct", l.distancePct());
        return m;
    }

    // ------------------------------------------------------------------ from the rules

    /**
     * The plan of a preset's rules summary ({@code PresetSummaries}: buy, sell, stopLoss, rewardRisk); incomplete when
     * the summary has no prices (no ATR yet).
     */
    public static TradePlan ofRules(Map<String, Object> summary, String by) {
        return new TradePlan(ruleLevel(summary.get("buy"), "LIMIT"), ruleLevel(summary.get("sell"), "LIMIT"),
                ruleLevel(summary.get("stopLoss"), "STOP"), number(summary.get("rewardRisk")), "RULES", by);
    }

    private static Level ruleLevel(Object value, String defaultType) {
        if (!(value instanceof Map<?, ?> m) || number(m.get("price")) == null) {
            return null;
        }
        Object type = m.get("type");
        return new Level(number(m.get("price")), type == null ? defaultType : type.toString(),
                m.get("basis") == null ? null : m.get("basis").toString(), number(m.get("distancePct")));
    }

    static Double number(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    // ------------------------------------------------------------------ from a model

    /**
     * What is wrong with proposed prices (empty: a valid plan). The buy type is inferred when missing: above the close
     * a STOP (breakout), else a LIMIT (pullback).
     */
    public static List<String> problems(Double buy, String buyType, Double sell, Double stop, double close) {
        List<String> out = new ArrayList<>();
        if (!positive(buy) || !positive(sell) || !positive(stop)) {
            out.add("the plan needs a buy price, a sell (target) and a stop loss, all positive");
            return out;
        }
        if (!(stop < buy && buy < sell)) {
            out.add("the prices must keep stop loss < buy < sell (got stop " + fmt(stop) + ", buy " + fmt(buy)
                    + ", sell " + fmt(sell) + ")");
        }
        String type = type(buyType, buy, close);
        if (type.equals("LIMIT") && buy > close * LIMIT_ABOVE) {
            out.add("a LIMIT buy of " + fmt(buy) + " is above the close " + fmt(close)
                    + " (it would fill at once): use a STOP for a breakout entry or a price at or below the close");
        }
        if (type.equals("STOP") && buy < close * STOP_BELOW) {
            out.add("a STOP buy of " + fmt(buy) + " is below the close " + fmt(close)
                    + ": a buy-stop sits above the price (a breakout); use a LIMIT for a pullback");
        }
        if (buy < close * MIN_BUY || buy > close * MAX_BUY) {
            out.add("the buy " + fmt(buy) + " is too far from the close " + fmt(close) + " (keep it within -40% / +30%)");
        }
        if (stop < buy && stop < buy * (1 - MAX_RISK)) {
            out.add("the stop loss " + fmt(stop) + " risks more than 40% below the buy " + fmt(buy));
        }
        if (sell > buy && sell > buy * MAX_TARGET) {
            out.add("the target " + fmt(sell) + " is more than 3 times the buy " + fmt(buy));
        }
        return out;
    }

    /**
     * A model's prices as a plan: rounded to valid prices (buy LIMIT and sell to the nearest tick, a buy STOP up, the
     * stop loss down), then checked again; null when they do not make a valid plan.
     *
     * @param basis what the levels rest on, shown with them (e.g. "Murphy (AI)")
     */
    public static TradePlan ofModel(Double buy, String buyType, Double sell, Double stop, double close, IdxTickSize ticks,
                                    String source, String by, String basis) {
        if (!problems(buy, buyType, sell, stop, close).isEmpty()) {
            return null;
        }
        String type = type(buyType, buy, close);
        double b = type.equals("STOP") ? ticks.ceil(buy) : ticks.round(buy);
        double s = ticks.round(sell);
        double l = ticks.floor(stop);
        if (!problems(b, type, s, l, close).isEmpty()) {
            return null;
        }
        return new TradePlan(new Level(b, type, basis, pct(b, close)), new Level(s, "LIMIT", basis, pct(s, close)),
                new Level(l, "STOP", basis, pct(l, close)), rewardRisk(b, s, l), source, by);
    }

    /** (sell - buy) / (buy - stop), 2 decimals; null without risk. */
    static Double rewardRisk(double buy, double sell, double stop) {
        double risk = buy - stop;
        return risk > 0 ? Math.round((sell - buy) / risk * 100) / 100.0 : null;
    }

    /** LIMIT or STOP as given; else STOP above the close, LIMIT at or below it. */
    static String type(String given, double buy, double close) {
        String t = given == null ? "" : given.trim().toUpperCase(Locale.ROOT);
        if (t.equals("LIMIT") || t.equals("STOP")) {
            return t;
        }
        return buy > close ? "STOP" : "LIMIT";
    }

    private static Double pct(double price, double close) {
        return close > 0 ? Math.round((price / close - 1) * 10_000) / 100.0 : null;
    }

    private static boolean positive(Double v) {
        return v != null && Double.isFinite(v) && v > 0;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%,d", (long) v) : String.format(Locale.ROOT, "%,.2f", v);
    }
}
