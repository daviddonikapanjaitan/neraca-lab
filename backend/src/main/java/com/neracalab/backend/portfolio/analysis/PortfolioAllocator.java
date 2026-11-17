package com.neracalab.backend.portfolio.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sizing and the limits of Portfolio &gt; Analysis, in Java (whatever the synthesis proposes):
 * <ul>
 *   <li><b>Kelly</b> ({@link #kelly}): f* = p - (1 - p) / b, where the win probability p comes from the fundamental
 *       step (0.35 at a final fundamental score of 0 to 0.65 at 100) and the payoff b from the technical step (the
 *       trade plan's reward / risk: (target - entry) / (entry - stop)); the weight is {@code kelly-fraction} of it
 *       (half-Kelly), at most {@code max-position-weight}. The two scores feed the two different inputs of the
 *       formula; they are never added into one score.</li>
 *   <li><b>Allocation</b> ({@link #allocate}): a new stock is bought only with a fundamental PASS and a technical BUY
 *       or ACCUMULATE, a holding increased only with both; no stock above {@code max-position-weight} (33%), no sector
 *       above {@code max-sector-weight} (33%), at most {@code max stocks} stocks, no new position below
 *       {@code min-position-weight}, the stocks and the cash within the capital (the cash never negative); shares in
 *       whole lots ({@code lot-size}) rounded down, a held position kept at its exact shares when it is held.</li>
 * </ul>
 */
public final class PortfolioAllocator {

    private PortfolioAllocator() {
    }

    public static final String BUY = "BUY";
    public static final String INCREASE = "INCREASE";
    public static final String HOLD = "HOLD";
    public static final String DECREASE = "DECREASE";
    public static final String SELL = "SELL";

    /** The limits of a run. */
    public record Limits(int maxStocks, double maxPositionWeight, double maxSectorWeight, double minPositionWeight,
                         double kellyFraction, int lotSize) {
    }

    /**
     * Kelly sizing of an idea.
     *
     * @param p      win probability (from the final fundamental score)
     * @param b      payoff: reward / risk of the technical plan (null without a complete plan)
     * @param f      the full Kelly fraction (may be negative: no edge)
     * @param weight the weight used: kelly-fraction x f, between 0 and max-position-weight
     */
    public record Kelly(double p, Double b, Double f, double weight, String note) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("winProbability", round(p, 4));
            m.put("payoff", b == null ? null : round(b, 2));
            m.put("kellyFraction", f == null ? null : round(f, 4));
            m.put("weight", round(weight, 4));
            m.put("note", note);
            return m;
        }
    }

    /**
     * The Kelly weight of an idea.
     *
     * @param fundamental the final fundamental score (0 - 100)
     */
    public static Kelly kelly(double fundamental, Double entry, Double stop, Double target, Limits limits) {
        double p = Math.max(0.35, Math.min(0.65, 0.35 + 0.30 * fundamental / 100));
        if (entry == null || stop == null || target == null || !(stop < entry && entry < target)) {
            return new Kelly(p, null, null, 0, "No complete trade plan (stop < entry < target): no Kelly size");
        }
        double b = (target - entry) / (entry - stop);
        double f = p - (1 - p) / b;
        double w = Math.max(0, Math.min(limits.maxPositionWeight(), f * limits.kellyFraction()));
        String note = f <= 0
                ? String.format(Locale.ROOT, "No edge: p %.2f with reward / risk %.2f gives Kelly %.3f", p, b, f)
                : String.format(Locale.ROOT, "p %.2f, reward / risk %.2f: Kelly %.3f, x %.2f = %.3f%s", p, b, f,
                        limits.kellyFraction(), f * limits.kellyFraction(),
                        f * limits.kellyFraction() > limits.maxPositionWeight() ? " (capped at "
                                + pct(limits.maxPositionWeight()) + ")" : "");
        return new Kelly(p, b, f, w, note);
    }

    /**
     * The Kelly weight of an idea without a technical plan (fundamental analysis only): an even payoff (b = 1) is
     * assumed, so f* = 2p - 1 with p from the final fundamental score (at most 0.65: f* 0.30, half-Kelly 0.15).
     */
    public static Kelly kellyEven(double fundamental, Limits limits) {
        double p = Math.max(0.35, Math.min(0.65, 0.35 + 0.30 * fundamental / 100));
        double f = 2 * p - 1;
        double w = Math.max(0, Math.min(limits.maxPositionWeight(), f * limits.kellyFraction()));
        String note = f <= 0
                ? String.format(Locale.ROOT, "No edge: p %.2f with an even payoff (no technical plan) gives Kelly %.3f",
                        p, f)
                : String.format(Locale.ROOT, "p %.2f, even payoff assumed (fundamental only, no technical plan): Kelly "
                        + "%.3f, x %.2f = %.3f", p, f, limits.kellyFraction(), f * limits.kellyFraction());
        return new Kelly(p, 1.0, f, w, note);
    }

    /**
     * A stock the allocation can hold.
     *
     * @param price           the last close (null: no price, a holding is then kept as it is)
     * @param currentShares   shares held (0: not held)
     * @param currentValue    market value held
     * @param fundamentalPass the fundamental agent's verdict (or the rules' when there is no answer)
     * @param technicalStance the technical agent's stance (or the rules'); null without one
     * @param orderScore      orders new ideas in the fallback: the final technical score (fundamental only: the final
     *                        fundamental score)
     * @param timingRequired  a buy or an increase needs a technical BUY or ACCUMULATE too (false: fundamental analysis
     *                        only, the fundamental PASS is enough)
     */
    public record Idea(String ticker, String sector, boolean held, Double price, double currentShares,
                       double currentValue, boolean fundamentalPass, String technicalStance, double orderScore,
                       Kelly kelly, boolean timingRequired) {

        /** An idea of a run with both steps (the technical stance is required). */
        public Idea(String ticker, String sector, boolean held, Double price, double currentShares, double currentValue,
                    boolean fundamentalPass, String technicalStance, double orderScore, Kelly kelly) {
            this(ticker, sector, held, price, currentShares, currentValue, fundamentalPass, technicalStance, orderScore,
                    kelly, true);
        }

        boolean timely() {
            return BUY.equals(technicalStance) || "ACCUMULATE".equals(technicalStance);
        }

        boolean buyable() {
            return fundamentalPass && (!timingRequired || timely());
        }

        /** What a buy or an increase needs (for the notes). */
        String needs() {
            return timingRequired ? "a fundamental PASS and a technical BUY or ACCUMULATE" : "a fundamental PASS";
        }
    }

    /** What the synthesis (or the fallback) proposes for a stock. */
    public record Proposal(String ticker, String action, double targetWeight, String conviction, String reason) {
    }

    /**
     * A stock of the result.
     *
     * @param tradeShares shares to buy (positive) or sell (negative)
     * @param adjustments what the limits changed in the proposal
     */
    public record Position(String ticker, String sector, boolean held, String action, Double price,
                           double currentShares, double currentValue, double currentWeight, double proposedWeight,
                           double targetWeight, double targetShares, double targetValue, double tradeShares,
                           double tradeLots, double tradeValue, String conviction, String reason,
                           List<String> adjustments) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", action);
            m.put("price", price);
            m.put("currentShares", currentShares);
            m.put("currentValue", round(currentValue, 2));
            m.put("currentWeight", round(currentWeight, 6));
            m.put("proposedWeight", round(proposedWeight, 6));
            m.put("targetWeight", round(targetWeight, 6));
            m.put("targetShares", targetShares);
            m.put("targetValue", round(targetValue, 2));
            m.put("tradeShares", tradeShares);
            m.put("tradeLots", tradeLots);
            m.put("tradeValue", round(tradeValue, 2));
            m.put("reason", reason);
            m.put("adjustments", adjustments);
            return m;
        }
    }

    /**
     * @param positions every stock held before or after, the largest target first, then those sold
     * @param checks    the limits checked on the result (all should pass)
     * @param skipped   stocks not held that end without a position: why (what the limits changed)
     */
    public record Allocation(double capital, double cashBefore, double cashAfter, double cashTargetWeight,
                             List<Position> positions, Map<String, Double> sectorsBefore,
                             Map<String, Double> sectorsAfter, List<Map<String, Object>> checks, List<String> notes,
                             Map<String, List<String>> skipped) {
    }

    /**
     * The portfolio after the proposals, within the limits.
     *
     * @param capital     market value + cash + added capital
     * @param cash        cash before (with the added capital)
     * @param cashTarget  the cash weight the proposal wants (null: what is left)
     */
    public static Allocation allocate(List<Idea> ideas, Map<String, Proposal> proposals, Double cashTarget,
                                      double capital, double cash, Limits limits) {
        List<String> notes = new ArrayList<>();
        Map<String, double[]> w = new LinkedHashMap<>();   // [proposed, target]
        Map<String, List<String>> adjust = new LinkedHashMap<>();
        Map<String, Idea> byTicker = new LinkedHashMap<>();
        ideas.forEach(i -> byTicker.put(i.ticker(), i));
        java.util.function.BiConsumer<String, String> note = (t, n) -> adjust.computeIfAbsent(t,
                k -> new ArrayList<>()).add(n);

        for (Idea i : ideas) {
            double current = capital > 0 ? i.currentValue() / capital : 0;
            Proposal pr = proposals.get(i.ticker());
            // a holding the proposal holds keeps its exact shares (the weight it gives is rounded)
            double proposed = pr == null || (i.held() && HOLD.equals(pr.action())) ? (i.held() ? current : 0)
                    : Math.max(0, pr.targetWeight());
            double target = proposed;
            if (i.held() && i.price() == null) {
                if (Math.abs(target - current) > 1e-9) {
                    note.accept(i.ticker(), "No price stored: the position is kept as it is");
                }
                target = current;
            } else if (!i.held() && target > 0 && !i.buyable()) {
                note.accept(i.ticker(), "Not bought: a new stock needs " + i.needs());
                target = 0;
            } else if (i.held() && target > current + 1e-9 && !i.buyable()) {
                note.accept(i.ticker(), "Not increased: an increase needs " + i.needs());
                target = current;
            }
            if (target > limits.maxPositionWeight()) {
                note.accept(i.ticker(), "Capped at " + pct(limits.maxPositionWeight()) + " of the capital (from "
                        + pct(target) + ")");
                target = limits.maxPositionWeight();
            }
            if (!i.held() && target > 0 && target < limits.minPositionWeight()) {
                note.accept(i.ticker(), "Too small to open (" + pct(target) + ", below " + pct(limits.minPositionWeight())
                        + ")");
                target = 0;
            }
            w.put(i.ticker(), new double[] {proposed, target});
        }

        // at most max stocks: drop the smallest new buys first, then the smallest holdings
        List<String> kept = new ArrayList<>(w.entrySet().stream().filter(e -> e.getValue()[1] > 0).map(Map.Entry::getKey)
                .toList());
        if (kept.size() > limits.maxStocks()) {
            kept.sort(Comparator.comparing((String t) -> byTicker.get(t).held())
                    .thenComparingDouble(t -> w.get(t)[1]).thenComparing(t -> t));
            int drop = kept.size() - limits.maxStocks();
            for (int k = 0; k < drop; k++) {
                String t = kept.get(k);
                note.accept(t, "Left out: at most " + limits.maxStocks() + " stocks");
                w.get(t)[1] = 0;
            }
            notes.add(drop + " stock(s) left out to keep at most " + limits.maxStocks() + " stocks.");
        }

        // no sector above the limit: its stocks scaled down together (positions without a price stay as they are)
        Map<String, Double> sectors = sectorWeights(ideas, t -> w.get(t)[1]);
        for (Map.Entry<String, Double> s : sectors.entrySet()) {
            if (s.getValue() <= limits.maxSectorWeight() + 1e-9) {
                continue;
            }
            double fixed = ideas.stream().filter(i -> sectorOf(i).equals(s.getKey()) && i.held() && i.price() == null)
                    .mapToDouble(i -> w.get(i.ticker())[1]).sum();
            double movable = s.getValue() - fixed;
            double room = Math.max(0, limits.maxSectorWeight() - fixed);
            double factor = movable > 0 ? Math.min(1, room / movable) : 1;
            for (Idea i : ideas) {
                if (sectorOf(i).equals(s.getKey()) && !(i.held() && i.price() == null) && w.get(i.ticker())[1] > 0) {
                    w.get(i.ticker())[1] *= factor;
                    note.accept(i.ticker(), "Sector " + s.getKey() + " was " + pct(s.getValue()) + ": scaled to keep it "
                            + "at most " + pct(limits.maxSectorWeight()));
                }
            }
            notes.add("Sector " + s.getKey() + " brought from " + pct(s.getValue()) + " to at most "
                    + pct(limits.maxSectorWeight()) + ".");
        }

        // the stocks and the cash within the capital: buys and increases scaled down first
        double cashFloor = cashTarget == null ? 0 : Math.max(0, Math.min(1, cashTarget));
        double invested = w.values().stream().mapToDouble(x -> x[1]).sum();
        if (invested > 1 - cashFloor + 1e-9) {
            double fixed = 0;
            double growth = 0;
            for (Idea i : ideas) {
                double current = capital > 0 ? i.currentValue() / capital : 0;
                double t = w.get(i.ticker())[1];
                double base = Math.min(t, i.held() ? current : 0);
                fixed += base;
                growth += t - base;
            }
            double room = Math.max(0, 1 - cashFloor - fixed);
            double factor = growth > 0 ? Math.min(1, room / growth) : 1;
            for (Idea i : ideas) {
                double current = capital > 0 ? i.currentValue() / capital : 0;
                double[] x = w.get(i.ticker());
                double base = Math.min(x[1], i.held() ? current : 0);
                if (x[1] - base > 1e-12) {
                    x[1] = base + (x[1] - base) * factor;
                    note.accept(i.ticker(), "Buy scaled down to keep " + pct(cashFloor) + " in cash");
                }
            }
            if (fixed > 1 - cashFloor + 1e-9) {
                double f2 = (1 - cashFloor) / fixed;
                w.values().forEach(x -> x[1] *= f2);
                notes.add("The holdings alone exceed the capital less the cash target: every position scaled down.");
            }
            notes.add("Buys scaled down so that the stocks and the cash fit the capital.");
        }

        // shares in whole lots
        List<Position> positions = new ArrayList<>();
        Map<String, List<String>> skipped = new LinkedHashMap<>();
        double spent = 0;
        for (Idea i : ideas) {
            double[] x = w.get(i.ticker());
            double current = capital > 0 ? i.currentValue() / capital : 0;
            Proposal pr = proposals.get(i.ticker());
            double targetShares;
            int lot = limits.lotSize();
            if (i.price() == null || i.price() <= 0) {
                targetShares = i.currentShares();
            } else if (i.held() && Math.abs(x[1] - current) < 1e-9) {
                targetShares = i.currentShares();
            } else {
                double desired = x[1] * capital / i.price();
                if (!i.held()) {
                    targetShares = Math.floor(desired / lot + 1e-9) * lot;
                } else if (x[1] > current) {
                    // an increase buys whole lots on top of the shares held (an odd lot stays as it is)
                    targetShares = i.currentShares() + Math.floor((desired - i.currentShares()) / lot + 1e-9) * lot;
                } else if (x[1] <= 1e-12) {
                    targetShares = 0;
                } else {
                    // a decrease sells whole lots, enough to reach the target weight or below; all when less is left
                    double sell = Math.ceil((i.currentShares() - desired) / lot - 1e-9) * lot;
                    targetShares = Math.max(0, i.currentShares() - sell);
                }
            }
            double price = i.price() == null ? 0 : i.price();
            double targetValue = i.price() == null ? i.currentValue() : targetShares * price;
            double trade = targetShares - i.currentShares();
            String action;
            if (!i.held()) {
                action = targetShares > 0 ? BUY : null;
            } else if (targetShares <= 0) {
                action = SELL;
            } else if (trade > 0) {
                action = INCREASE;
            } else if (trade < 0) {
                action = DECREASE;
            } else {
                action = HOLD;
            }
            if (action == null) {
                if (x[0] > 0 && x[1] > 0 && !adjust.containsKey(i.ticker())) {
                    note.accept(i.ticker(), "Less than one lot at the target weight");
                }
                if (adjust.containsKey(i.ticker())) {
                    skipped.put(i.ticker(), List.copyOf(adjust.get(i.ticker())));
                }
                continue;
            }
            spent += i.price() == null ? 0 : trade * price;
            positions.add(new Position(i.ticker(), sectorOf(i), i.held(), action, i.price(), i.currentShares(),
                    i.currentValue(), current, x[0], capital > 0 ? targetValue / capital : 0, targetShares, targetValue,
                    trade, limits.lotSize() > 0 ? trade / limits.lotSize() : trade, trade * price,
                    pr == null ? null : pr.conviction(), pr == null ? null : pr.reason(),
                    List.copyOf(adjust.getOrDefault(i.ticker(), List.of()))));
        }
        positions.sort(Comparator.comparing((Position p) -> SELL.equals(p.action()))
                .thenComparing(Comparator.comparingDouble(Position::targetValue).reversed())
                .thenComparing(Position::ticker));
        double cashAfter = cash - spent;

        Map<String, Double> before = sectorWeights(ideas, t -> capital > 0 ? byTicker.get(t).currentValue() / capital : 0);
        Map<String, Double> after = new TreeMap<>();
        for (Position p : positions) {
            if (p.targetValue() > 0 && capital > 0) {
                after.merge(p.sector(), p.targetValue() / capital, Double::sum);
            }
        }
        List<Map<String, Object>> checks = checks(positions, after, cashAfter, capital, limits);
        return new Allocation(capital, cash, cashAfter, capital > 0 ? cashAfter / capital : 0, List.copyOf(positions),
                before, after, checks, notes, skipped);
    }

    private static List<Map<String, Object>> checks(List<Position> positions, Map<String, Double> sectors,
                                                    double cashAfter, double capital, Limits limits) {
        List<Map<String, Object>> out = new ArrayList<>();
        long held = positions.stream().filter(p -> p.targetShares() > 0).count();
        out.add(check("Stocks held", held <= limits.maxStocks(), held + " of at most " + limits.maxStocks()));
        Position largest = positions.stream().max(Comparator.comparingDouble(Position::targetWeight)).orElse(null);
        boolean posOk = positions.stream().allMatch(p -> p.targetWeight() <= limits.maxPositionWeight() + 1e-9
                || p.price() == null);
        out.add(check("Largest stock", posOk, largest == null ? "none" : largest.ticker() + " "
                + pct(largest.targetWeight()) + " (at most " + pct(limits.maxPositionWeight()) + ")"));
        Map.Entry<String, Double> top = sectors.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        boolean secOk = sectors.values().stream().allMatch(v -> v <= limits.maxSectorWeight() + 1e-9);
        out.add(check("Largest sector", secOk, top == null ? "none" : top.getKey() + " " + pct(top.getValue())
                + " (at most " + pct(limits.maxSectorWeight()) + ")"));
        out.add(check("Cash", cashAfter >= -0.005, String.format(Locale.ROOT, "Rp %,.0f (%s)", cashAfter,
                capital > 0 ? pct(cashAfter / capital) : "-")));
        boolean lots = positions.stream().allMatch(p -> p.price() == null || Math.abs(p.tradeShares()
                % limits.lotSize()) < 1e-9 || HOLD.equals(p.action()) || SELL.equals(p.action()));
        out.add(check("Whole lots", lots, "every buy and partial sale in lots of " + limits.lotSize() + " shares"));
        return out;
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("ok", ok);
        m.put("detail", detail);
        return m;
    }

    static String sectorOf(Idea i) {
        return i.sector() == null || i.sector().isBlank() ? "Unclassified" : i.sector();
    }

    private static Map<String, Double> sectorWeights(List<Idea> ideas, java.util.function.ToDoubleFunction<String> w) {
        Map<String, Double> out = new TreeMap<>();
        for (Idea i : ideas) {
            double x = w.applyAsDouble(i.ticker());
            if (x > 0) {
                out.merge(sectorOf(i), x, Double::sum);
            }
        }
        return out;
    }

    /**
     * The proposal without the synthesis model: a holding with a fundamental FAIL and a technical AVOID is sold, one with
     * either halved, one with both good raised to its Kelly weight, the others held; new stocks that passed both agents
     * are bought at their Kelly weight in the order of step 2 (the technical score), up to the most stocks allowed. With
     * the fundamental analysis only, a holding with a FAIL is sold, one with a PASS kept or raised to its Kelly weight,
     * and new stocks with a PASS bought in the order of their final fundamental score.
     */
    public static Map<String, Proposal> fallback(List<Idea> ideas, double capital, Limits limits) {
        Map<String, Proposal> out = new LinkedHashMap<>();
        int count = 0;
        for (Idea i : ideas) {
            if (!i.held()) {
                continue;
            }
            double current = capital > 0 ? i.currentValue() / capital : 0;
            boolean avoid = "AVOID".equals(i.technicalStance());
            if (!i.timingRequired()) {
                // fundamental analysis only: the fundamental verdict decides alone
                if (!i.fundamentalPass()) {
                    out.put(i.ticker(), new Proposal(i.ticker(), SELL, 0, "LOW", "Fundamental FAIL"));
                    continue;
                }
                if (i.kelly() != null && i.kelly().weight() > current) {
                    out.put(i.ticker(), new Proposal(i.ticker(), INCREASE, i.kelly().weight(), "MEDIUM",
                            "Fundamental PASS: raised to its half-Kelly weight"));
                } else {
                    out.put(i.ticker(), new Proposal(i.ticker(), HOLD, current, "MEDIUM", "Kept"));
                }
                count++;
                continue;
            }
            if (!i.fundamentalPass() && avoid) {
                out.put(i.ticker(), new Proposal(i.ticker(), SELL, 0, "LOW", "Fundamental FAIL and technical AVOID"));
                continue;
            }
            if (!i.fundamentalPass() || avoid) {
                out.put(i.ticker(), new Proposal(i.ticker(), DECREASE, current / 2, "LOW",
                        !i.fundamentalPass() ? "Fundamental FAIL" : "Technical AVOID"));
            } else if (i.buyable() && i.kelly() != null && i.kelly().weight() > current) {
                out.put(i.ticker(), new Proposal(i.ticker(), INCREASE, i.kelly().weight(), "MEDIUM",
                        "Fundamental PASS and technical " + i.technicalStance() + ": raised to its half-Kelly weight"));
            } else {
                out.put(i.ticker(), new Proposal(i.ticker(), HOLD, current, "MEDIUM", "Kept"));
            }
            count++;
        }
        List<Idea> fresh = ideas.stream().filter(i -> !i.held() && i.buyable() && i.kelly() != null
                && i.kelly().weight() >= limits.minPositionWeight())
                .sorted(Comparator.comparingDouble((Idea i) -> -i.orderScore()).thenComparing(Idea::ticker)).toList();
        for (Idea i : fresh) {
            if (count >= limits.maxStocks()) {
                break;
            }
            out.put(i.ticker(), new Proposal(i.ticker(), BUY, i.kelly().weight(), "MEDIUM", i.timingRequired()
                    ? "Fundamental PASS, technical " + i.technicalStance() + ": bought at its half-Kelly weight"
                    : "Fundamental PASS: bought at its half-Kelly weight"));
            count++;
        }
        return out;
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
