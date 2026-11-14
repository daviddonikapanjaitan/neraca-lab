package com.neracalab.backend.portfolio.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The two rules steps of Portfolio &gt; Analysis, in this order and never combined into one score:
 * <ol>
 *   <li><b>Fundamental</b>: every stock of the universe with a Fundamental (Score) of at least
 *       {@code min-fundamental-score}, best first; the best {@code max stocks x fundamental-multiplier} (at most
 *       {@code max-fundamental-shortlist}) pass.</li>
 *   <li><b>Technical</b>: of the stocks step 1 passed, those with a Technical (Score), best first; the best
 *       {@code max stocks x technical-multiplier} (at most {@code max-technical-shortlist}) pass.</li>
 * </ol>
 * The stocks the user holds always go on to the AI agents (they need a recommendation), with their scores and ranks;
 * a holding that would have been left out is flagged instead. Plain rules: no model, nothing stored.
 */
public final class AnalysisSteps {

    private AnalysisSteps() {
    }

    /**
     * A stock of the universe with the two scores of Companies &gt; Fundamentals (the company list's Fundamental (Score)
     * and Technical (Score) columns).
     *
     * @param fundamental 0 - 100; null when the scorecards have too little data
     * @param technical   0 - 100; null without enough prices
     */
    public record Stock(String ticker, String companyName, String sector, Double fundamental, String fundamentalVerdict,
                        Double technical, String technicalStance, String technicalError) {
    }

    /** Step limits of a run. */
    public record Limits(int maxStocks, double minFundamentalScore, int fundamentalKeep, int technicalKeep) {

        static Limits of(int maxStocks, PortfolioAnalysisProperties p) {
            return new Limits(maxStocks, p.minFundamentalScore(),
                    Math.max(1, Math.min(maxStocks * p.fundamentalMultiplier(), p.maxFundamentalShortlist())),
                    Math.max(1, Math.min(maxStocks * p.technicalMultiplier(), p.maxTechnicalShortlist())));
        }
    }

    /**
     * Where a stock ended.
     *
     * @param fundamentalRank its place by Fundamental (Score) among the universe's scored stocks (null: no score)
     * @param technicalRank   its place by Technical (Score) among the stocks step 1 passed (null: not ranked)
     * @param passed          goes on to the AI agents (passed both steps, or held)
     * @param reason          why it stopped (null: it went on)
     * @param flags           a holding the steps would have left out: why
     */
    public record Outcome(Stock stock, boolean held, Integer fundamentalRank, Integer technicalRank, String stage,
                          boolean passed, String reason, List<String> flags) {
    }

    /**
     * @param outcomes every stock of the universe and every holding: the AI candidates first (step 2's order, then the
     *                 holdings), then those left out
     */
    public record Result(List<Outcome> outcomes, Map<String, Object> funnel) {

        public List<Outcome> candidates() {
            return outcomes.stream().filter(Outcome::passed).toList();
        }
    }

    public static final String FUNDAMENTAL_OUT = "FUNDAMENTAL_OUT";
    public static final String TECHNICAL_OUT = "TECHNICAL_OUT";
    public static final String CANDIDATE = "CANDIDATE";

    /**
     * The two steps.
     *
     * @param universe the stocks to choose from (BULK: every company; SELECTED: the chosen ones)
     * @param held     the tickers of the user's positions (with their scores in {@code holdings})
     * @param holdings the scores of the held stocks (they may be outside the universe)
     */
    public static Result run(List<Stock> universe, Set<String> held, Map<String, Stock> holdings, Limits limits) {
        return run(universe, held, holdings, limits, true);
    }

    /**
     * The steps of a run.
     *
     * @param technical true: step 1 then step 2 (FUNDAMENTAL_TECHNICAL); false: step 1 only (FUNDAMENTAL) - step 1's
     *                  stocks go to the AI agents, no Technical (Score) is read
     */
    public static Result run(List<Stock> universe, Set<String> held, Map<String, Stock> holdings, Limits limits,
                             boolean technical) {
        Map<String, Stock> all = new LinkedHashMap<>();
        universe.forEach(s -> all.putIfAbsent(s.ticker(), s));
        holdings.forEach(all::putIfAbsent);

        // step 1: the Fundamental (Score) of the universe's stocks not held, best first
        Comparator<Stock> byFundamental = Comparator.comparing((Stock s) -> -s.fundamental())
                .thenComparing(Stock::ticker);
        List<Stock> universeScored = universe.stream().filter(s -> !held.contains(s.ticker()))
                .filter(s -> s.fundamental() != null).distinct().sorted(byFundamental).toList();
        Map<String, Integer> fundamentalRank = new LinkedHashMap<>();
        for (int i = 0; i < universeScored.size(); i++) {
            fundamentalRank.put(universeScored.get(i).ticker(), i + 1);
        }
        List<Stock> aboveFloor = universeScored.stream().filter(s -> s.fundamental() >= limits.minFundamentalScore())
                .toList();
        List<Stock> step1 = aboveFloor.stream().limit(limits.fundamentalKeep()).toList();
        Set<String> step1Tickers = new java.util.HashSet<>();
        step1.forEach(s -> step1Tickers.add(s.ticker()));

        // step 2: the Technical (Score) of step 1's stocks, best first (fundamental only: none, step 1's stocks go on)
        Comparator<Stock> byTechnical = Comparator.comparing((Stock s) -> -s.technical()).thenComparing(Stock::ticker);
        List<Stock> technicalScored = !technical ? List.of()
                : step1.stream().filter(s -> s.technical() != null).sorted(byTechnical).toList();
        Map<String, Integer> technicalRank = new LinkedHashMap<>();
        for (int i = 0; i < technicalScored.size(); i++) {
            technicalRank.put(technicalScored.get(i).ticker(), i + 1);
        }
        List<Stock> step2 = technical ? technicalScored.stream().limit(limits.technicalKeep()).toList() : step1;
        Set<String> step2Tickers = new java.util.HashSet<>();
        step2.forEach(s -> step2Tickers.add(s.ticker()));

        List<Outcome> candidates = new ArrayList<>();
        for (Stock s : step2) {
            candidates.add(new Outcome(s, false, fundamentalRank.get(s.ticker()), technicalRank.get(s.ticker()),
                    CANDIDATE, true, null, List.of()));
        }
        List<Outcome> heldOutcomes = new ArrayList<>();
        List<Outcome> left = new ArrayList<>();
        for (Stock s : all.values()) {
            String ticker = s.ticker();
            if (held.contains(ticker)) {
                List<String> flags = new ArrayList<>();
                Integer wouldBe = rankAmong(s, universeScored, Stock::fundamental, byFundamental);
                String f = fundamentalReason(s, wouldBe, limits, step1Tickers, true);
                if (f == null && wouldBe != null && wouldBe > limits.fundamentalKeep()) {
                    f = String.format(Locale.ROOT, "Fundamental (Score) %.1f, rank %d: outside the best %d",
                            s.fundamental(), wouldBe, limits.fundamentalKeep());
                }
                if (f != null) {
                    flags.add("Held, but step 1 would leave it out: " + f);
                } else if (technical && s.technical() == null) {
                    flags.add("Held, but it has no Technical (Score)" + (s.technicalError() == null ? ""
                            : " (" + s.technicalError() + ")"));
                }
                heldOutcomes.add(new Outcome(s, true, wouldBe, technical
                        ? rankAmong(s, technicalScored, Stock::technical, byTechnical) : null, CANDIDATE, true, null,
                        List.copyOf(flags)));
                continue;
            }
            if (step2Tickers.contains(ticker)) {
                continue;
            }
            if (!step1Tickers.contains(ticker)) {
                left.add(new Outcome(s, false, fundamentalRank.get(ticker), null, FUNDAMENTAL_OUT, false,
                        fundamentalReason(s, fundamentalRank.get(ticker), limits, step1Tickers, false), List.of()));
            } else {
                String reason = s.technical() == null
                        ? "No Technical (Score)" + (s.technicalError() == null ? "" : ": " + s.technicalError())
                        : String.format(Locale.ROOT, "Technical (Score) %.1f, rank %d of step 1's %d: outside the best %d",
                                s.technical(), technicalRank.get(ticker), technicalScored.size(), limits.technicalKeep());
                left.add(new Outcome(s, false, fundamentalRank.get(ticker), technicalRank.get(ticker), TECHNICAL_OUT,
                        false, reason, List.of()));
            }
        }
        List<Outcome> outcomes = new ArrayList<>(candidates);
        outcomes.addAll(heldOutcomes);
        outcomes.addAll(left);

        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("universe", universe.size());
        funnel.put("held", held.size());
        funnel.put("maxStocks", limits.maxStocks());
        Map<String, Object> f1 = new LinkedHashMap<>();
        f1.put("scored", universeScored.size());
        f1.put("minScore", limits.minFundamentalScore());
        f1.put("aboveMin", aboveFloor.size());
        f1.put("keep", limits.fundamentalKeep());
        f1.put("passed", step1.size());
        f1.put("rule", "Fundamental (Score) at least " + fmt(limits.minFundamentalScore()) + ", the best "
                + limits.fundamentalKeep() + " by Fundamental (Score)");
        funnel.put("fundamental", f1);
        Map<String, Object> f2 = new LinkedHashMap<>();
        f2.put("used", technical);
        f2.put("inputs", step1.size());
        f2.put("scored", technicalScored.size());
        f2.put("keep", technical ? limits.technicalKeep() : 0);
        f2.put("passed", technical ? step2.size() : 0);
        f2.put("rule", technical ? "Of step 1's stocks, the best " + limits.technicalKeep() + " by Technical (Score)"
                : "Not used: fundamental analysis only (step 1's stocks go to the fundamental agent)");
        funnel.put("technical", f2);
        funnel.put("steps", technical ? "FUNDAMENTAL_TECHNICAL" : "FUNDAMENTAL");
        funnel.put("candidates", step2.size() + heldOutcomes.size());
        return new Result(List.copyOf(outcomes), funnel);
    }

    /** A holding's rank as a step would give it among that step's ranked stocks (null without the score). */
    private static Integer rankAmong(Stock s, List<Stock> ranked, java.util.function.Function<Stock, Double> score,
                                     Comparator<Stock> order) {
        if (score.apply(s) == null) {
            return null;
        }
        List<Stock> with = new ArrayList<>(ranked);
        with.removeIf(x -> x.ticker().equals(s.ticker()));
        with.add(s);
        with.sort(order);
        return with.indexOf(s) + 1;
    }

    private static String fundamentalReason(Stock s, Integer rank, Limits limits, Set<String> step1, boolean held) {
        if (s.fundamental() == null) {
            return "No Fundamental (Score) (too little of its scorecards known)";
        }
        if (s.fundamental() < limits.minFundamentalScore()) {
            return String.format(Locale.ROOT, "Fundamental (Score) %.1f below %s", s.fundamental(),
                    fmt(limits.minFundamentalScore()));
        }
        if (!held && !step1.contains(s.ticker())) {
            return String.format(Locale.ROOT, "Fundamental (Score) %.1f, rank %d: outside the best %d", s.fundamental(),
                    rank, limits.fundamentalKeep());
        }
        return null;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
