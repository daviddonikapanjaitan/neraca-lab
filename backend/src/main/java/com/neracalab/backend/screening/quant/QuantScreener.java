package com.neracalab.backend.screening.quant;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.quant.QuantScorer.AgentScore;

/**
 * Stage 1: quantitative pre-screen in Java, no model. Filters the universe in a fixed order (every
 * step is recorded in the funnel), scores the remaining stocks per selected agent and keeps the
 * best {@code top N x shortlist-multiplier} (at most {@code max-shortlist}) for Stage 2.
 * <ol>
 *   <li>market-cap tier</li>
 *   <li>share price at least {@code min-price} (IDX: below Rp 50 = special-monitoring board)</li>
 *   <li>traded within {@code max-trade-age} (older: suspended)</li>
 *   <li>average traded value per day at least the tier's minimum (liquidity)</li>
 *   <li>listing board not WATCHLIST and ticker not in {@code excluded-tickers}</li>
 *   <li>fundamentals loaded</li>
 *   <li>positive trailing earnings ({@code require-positive-earnings})</li>
 *   <li>positive book equity</li>
 *   <li>enough known metrics ({@code min-fundamentals-coverage})</li>
 * </ol>
 * A screening of selected stocks ({@link #screenSelection}) runs the same steps on the selected stocks: step 1
 * becomes "market cap known", step 4 uses the minimum of each stock's own tier, and every eligible stock is
 * shortlisted. The tradability steps 2-5 (price, trading, liquidity, board) do not drop a selected stock (the user
 * chose it): it is kept and flagged with the reason, which the agents and the report see. The data steps (market
 * cap, fundamentals, earnings, equity, coverage) still exclude, as the scorecards need them.
 */
@Component
public class QuantScreener {

    /** Steps 1-5 need only market data; the fundamentals of the stocks passing them are refreshed first. */
    static final int MARKET_STEPS = 5;

    public record FunnelStep(String key, String label, int remaining) {
    }

    /** A stock that passed every filter, with its scores. */
    public record Candidate(StockSnapshot snapshot, StockProfile profile, Map<InvestorAgent, AgentScore> scores,
                            double overall, double coverage) {

        public String ticker() {
            return snapshot.ticker();
        }
    }

    /**
     * @param eligible  every stock that passed the filters, best first
     * @param shortlist the best of them, analysed by the agents
     * @param excluded  screening of selected stocks: each selected stock that did not pass, with the filter it
     *                  failed (in selection order); empty for a tier screening
     * @param flags     screening of selected stocks: each selected stock kept although it failed a tradability
     *                  step, with the reasons (e.g. "Low liquidity: ..."); empty for a tier screening
     */
    public record Result(int universe, List<FunnelStep> funnel, List<Candidate> eligible, List<Candidate> shortlist,
                         Map<String, String> excluded, Map<String, List<String>> flags) {

        /** The flags of a stock (empty when none). */
        public List<String> flags(String ticker) {
            return flags.getOrDefault(ticker, List.of());
        }
    }

    /**
     * @param flag null: a stock failing the step is dropped; otherwise (tradability steps of a screening of selected
     *             stocks) it is kept and flagged with this reason
     */
    private record Filter(String key, String label, Predicate<StockSnapshot> keep, Function<StockSnapshot, String> flag) {

        Filter(String key, String label, Predicate<StockSnapshot> keep) {
            this(key, label, keep, null);
        }
    }

    private final ScreeningProperties properties;

    public QuantScreener(ScreeningProperties properties) {
        this.properties = properties;
    }

    /**
     * The stocks passing the market-data filters (steps 1-5): their fundamentals must be current. For selected
     * stocks only the steps that drop a stock apply (a flagged stock is kept, so its fundamentals are refreshed too).
     *
     * @param tier the tier screened; null for a screening of selected stocks ({@code universe} = the selected ones)
     */
    public List<StockSnapshot> marketFiltered(List<StockSnapshot> universe, MarketCapTier tier, ZoneId zone) {
        List<StockSnapshot> remaining = universe;
        for (Filter f : filters(tier, List.of(InvestorAgent.values()), zone).subList(0, MARKET_STEPS)) {
            if (f.flag() == null) {
                remaining = remaining.stream().filter(f.keep()).toList();
            }
        }
        return remaining;
    }

    public Result screen(List<StockSnapshot> universe, MarketCapTier tier, List<InvestorAgent> agents, int topN,
                         ZoneId zone) {
        List<FunnelStep> funnel = new ArrayList<>();
        funnel.add(new FunnelStep("universe", "Active listings with market data", universe.size()));
        List<StockSnapshot> remaining = universe;
        for (Filter f : filters(tier, agents, zone)) {
            remaining = remaining.stream().filter(f.keep()).toList();
            funnel.add(new FunnelStep(f.key(), f.label(), remaining.size()));
        }
        List<Candidate> eligible = eligible(remaining, agents);
        int size = Math.min(properties.maxShortlist(), Math.max(topN, topN * properties.shortlistMultiplier()));
        List<Candidate> shortlist = eligible.subList(0, Math.min(size, eligible.size()));
        funnel.add(new FunnelStep("shortlist", "Shortlist for the AI agents (best quantitative scores)", shortlist.size()));
        return new Result(universe.size(), funnel, List.copyOf(eligible), List.copyOf(shortlist), Map.of(), Map.of());
    }

    /**
     * Stage 1 of a screening of selected stocks: the same filters as a tier screening, except that the tier
     * filter becomes "market cap known", the liquidity minimum is that of each stock's own tier, and a stock failing
     * a tradability step (price, trading, liquidity, board) is kept and flagged instead of dropped. Every eligible
     * stock is shortlisted (the user chose them; at most {@code max-shortlist} can be selected).
     *
     * @param selected the selected tickers, also those without stored market data
     * @param universe the latest snapshots of the selected tickers that Yahoo Finance lists
     */
    public Result screenSelection(List<String> selected, List<StockSnapshot> universe, List<InvestorAgent> agents,
                                  ZoneId zone) {
        List<FunnelStep> funnel = new ArrayList<>();
        Map<String, String> excluded = new LinkedHashMap<>();
        Map<String, List<String>> flags = new LinkedHashMap<>();
        Set<String> listed = new HashSet<>();
        universe.forEach(s -> listed.add(s.ticker()));
        funnel.add(new FunnelStep("universe", "Selected stocks", selected.size()));
        for (String ticker : selected) {
            if (!listed.contains(ticker)) {
                excluded.put(ticker, "No market data on Yahoo Finance");
            }
        }
        funnel.add(new FunnelStep("listed", "Listed with market data (Yahoo Finance)", universe.size()));
        List<StockSnapshot> remaining = universe;
        for (Filter f : filters(null, agents, zone)) {
            List<StockSnapshot> kept = new ArrayList<>();
            int flaggedHere = 0;
            for (StockSnapshot s : remaining) {
                if (f.keep().test(s)) {
                    kept.add(s);
                } else if (f.flag() != null) {
                    kept.add(s);
                    flags.computeIfAbsent(s.ticker(), t -> new ArrayList<>()).add(f.flag().apply(s));
                    flaggedHere++;
                } else {
                    excluded.put(s.ticker(), f.label());
                }
            }
            remaining = kept;
            String label = f.flag() == null ? f.label() : f.label() + " (selected stocks below it are kept and flagged"
                    + (flaggedHere > 0 ? ": " + flaggedHere : "") + ")";
            funnel.add(new FunnelStep(f.key(), label, remaining.size()));
        }
        // flags of the stocks a later step dropped are of no use
        Set<String> kept = new HashSet<>();
        remaining.forEach(s -> kept.add(s.ticker()));
        Map<String, List<String>> flagged = new LinkedHashMap<>();
        flags.forEach((ticker, list) -> {
            if (kept.contains(ticker)) {
                flagged.put(ticker, List.copyOf(list));
            }
        });
        List<Candidate> eligible = eligible(remaining, agents);
        List<Candidate> shortlist = eligible.subList(0, Math.min(properties.maxShortlist(), eligible.size()));
        funnel.add(new FunnelStep("shortlist", "Shortlist for the AI agents (every eligible selected stock)",
                shortlist.size()));
        return new Result(selected.size(), funnel, List.copyOf(eligible), List.copyOf(shortlist), excluded, flagged);
    }

    private List<Candidate> eligible(List<StockSnapshot> remaining, List<InvestorAgent> agents) {
        List<Candidate> eligible = new ArrayList<>();
        for (StockSnapshot s : remaining) {
            eligible.add(candidate(s, agents));
        }
        eligible.sort(Comparator.comparingDouble(Candidate::overall).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::coverage).reversed())
                .thenComparing(Candidate::ticker));
        return eligible;
    }

    Candidate candidate(StockSnapshot s, List<InvestorAgent> agents) {
        StockProfile profile = StockProfile.of(s);
        Map<InvestorAgent, AgentScore> scores = new EnumMap<>(InvestorAgent.class);
        Map<InvestorAgent, Double> values = new LinkedHashMap<>();
        double coverage = 0;
        for (InvestorAgent agent : agents) {
            AgentScore score = QuantScorer.score(agent, profile);
            scores.put(agent, score);
            values.put(agent, score.score());
            coverage += score.coverage();
        }
        return new Candidate(s, profile, scores, QuantScorer.overall(values, properties.riskWeight()),
                agents.isEmpty() ? 0 : QuantScorer.round3(coverage / agents.size()));
    }

    /** The filters in order; {@code tier} null: those of a screening of selected stocks. */
    private List<Filter> filters(MarketCapTier tier, List<InvestorAgent> agents, ZoneId zone) {
        Set<String> excluded = new HashSet<>();
        properties.excludedTickers().forEach(t -> excluded.add(t.trim().toUpperCase(Locale.ROOT)));
        List<Filter> filters = new ArrayList<>();
        if (tier != null) {
            double low = tier.lowerBound(properties);
            double high = tier.upperBound(properties);
            filters.add(new Filter("tier", tier.label() + " (" + range(low, high) + ")",
                    s -> s.marketCap() != null && s.marketCap() >= low && s.marketCap() < high));
        } else {
            filters.add(new Filter("tier", "Market cap known (its tier sets the liquidity minimum)",
                    s -> s.marketCap() != null && s.marketCap() > 0));
        }
        boolean selection = tier == null;
        filters.add(new Filter("price", "Share price at least " + plain(properties.minPrice()),
                s -> s.price() != null && s.price() >= properties.minPrice(),
                !selection ? null : s -> "Share price " + (s.price() == null ? "unknown" : plain(s.price()))
                        + ", below " + plain(properties.minPrice()) + " (special-monitoring board range)"));
        long tradeDays = properties.maxTradeAge().toDays();
        filters.add(new Filter("traded", "Traded in the last " + tradeDays + " days (not suspended)",
                s -> s.lastTradeAt() != null && s.lastTradeAt().isAfter(
                        s.snapshotDate().atStartOfDay(zone).toInstant().minus(properties.maxTradeAge())),
                !selection ? null : s -> "Not traded in the last " + tradeDays + " days (possibly suspended"
                        + (s.lastTradeAt() == null ? ")" : "; last trade " + s.lastTradeAt().atZone(zone).toLocalDate() + ")")));
        if (tier != null) {
            double minValue = properties.minAvgDailyValue(tier);
            filters.add(new Filter("liquidity", "Average traded value per day at least " + money(minValue),
                    s -> s.avgDailyValue3m() != null && s.avgDailyValue3m() >= minValue));
        } else {
            filters.add(new Filter("liquidity", "Average traded value per day at least the minimum of its tier ("
                    + Arrays.stream(MarketCapTier.values()).map(t -> t.label().toLowerCase(Locale.ROOT) + " "
                            + money(properties.minAvgDailyValue(t))).collect(Collectors.joining(", ")) + ")",
                    s -> s.avgDailyValue3m() != null && s.marketCap() != null
                            && s.avgDailyValue3m() >= properties.minAvgDailyValue(MarketCapTier.of(s.marketCap(), properties)),
                    s -> {
                        MarketCapTier own = MarketCapTier.of(s.marketCap(), properties);
                        return "Low liquidity: traded value per day " + (s.avgDailyValue3m() == null ? "unknown"
                                : money(s.avgDailyValue3m())) + ", below the " + money(properties.minAvgDailyValue(own))
                                + " minimum of " + own.label().toLowerCase(Locale.ROOT) + "s";
                    }));
        }
        filters.add(new Filter("board", "Not on the watchlist board, not excluded",
                s -> !"WATCHLIST".equals(s.board()) && !excluded.contains(s.ticker()),
                !selection ? null : s -> "WATCHLIST".equals(s.board()) ? "Listed on the watchlist board"
                        : "In the excluded tickers of the screening settings"));
        filters.add(new Filter("fundamentals", "Fundamentals available", StockSnapshot::hasFundamentals));
        if (properties.requirePositiveEarnings()) {
            filters.add(new Filter("earnings", "Positive trailing earnings", QuantScreener::positiveEarnings));
        }
        filters.add(new Filter("equity", "Positive book equity", s -> (s.bookValuePerShare() != null && s.bookValuePerShare() > 0)
                || (s.bookValuePerShare() == null && s.priceToBook() != null && s.priceToBook() > 0)));
        filters.add(new Filter("coverage", "At least " + Math.round(properties.minFundamentalsCoverage() * 100)
                + "% of the scoring metrics known", s -> candidate(s, agents).coverage() >= properties.minFundamentalsCoverage()));
        return filters;
    }

    static boolean positiveEarnings(StockSnapshot s) {
        if (s.epsTtm() != null) {
            return s.epsTtm() > 0;
        }
        return s.fundamentals() != null && s.fundamentals().netIncomeTtm() != null && s.fundamentals().netIncomeTtm() > 0;
    }

    private static String range(double low, double high) {
        if (Double.isInfinite(high)) {
            return "market cap at least " + money(low);
        }
        return low <= 0 ? "market cap below " + money(high) : "market cap " + money(low) + " to " + money(high);
    }

    /** 10000000000000 -> "10T", 200000000 -> "200M" (listing currency). */
    static String money(double value) {
        if (value >= 1e12) {
            return plain(value / 1e12) + "T";
        }
        if (value >= 1e9) {
            return plain(value / 1e9) + "B";
        }
        if (value >= 1e6) {
            return plain(value / 1e6) + "M";
        }
        return plain(value);
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(Math.round(value * 100) / 100.0);
    }
}
