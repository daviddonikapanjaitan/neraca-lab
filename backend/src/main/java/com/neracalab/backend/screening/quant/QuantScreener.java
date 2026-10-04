package com.neracalab.backend.screening.quant;

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
import java.util.function.Predicate;

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
     * @param eligible every stock that passed the filters, best first
     * @param shortlist the best of them, analysed by the agents
     */
    public record Result(int universe, List<FunnelStep> funnel, List<Candidate> eligible, List<Candidate> shortlist) {
    }

    private record Filter(String key, String label, Predicate<StockSnapshot> keep) {
    }

    private final ScreeningProperties properties;

    public QuantScreener(ScreeningProperties properties) {
        this.properties = properties;
    }

    /** The stocks passing the market-data filters (steps 1-5): their fundamentals must be current. */
    public List<StockSnapshot> marketFiltered(List<StockSnapshot> universe, MarketCapTier tier, ZoneId zone) {
        List<StockSnapshot> remaining = universe;
        for (Filter f : filters(tier, List.of(InvestorAgent.values()), zone).subList(0, MARKET_STEPS)) {
            remaining = remaining.stream().filter(f.keep()).toList();
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
        List<Candidate> eligible = new ArrayList<>();
        for (StockSnapshot s : remaining) {
            eligible.add(candidate(s, agents));
        }
        eligible.sort(Comparator.comparingDouble(Candidate::overall).reversed()
                .thenComparing(Comparator.comparingDouble(Candidate::coverage).reversed())
                .thenComparing(Candidate::ticker));
        int size = Math.min(properties.maxShortlist(), Math.max(topN, topN * properties.shortlistMultiplier()));
        List<Candidate> shortlist = eligible.subList(0, Math.min(size, eligible.size()));
        funnel.add(new FunnelStep("shortlist", "Shortlist for the AI agents (best quantitative scores)", shortlist.size()));
        return new Result(universe.size(), funnel, List.copyOf(eligible), List.copyOf(shortlist));
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

    private List<Filter> filters(MarketCapTier tier, List<InvestorAgent> agents, ZoneId zone) {
        double low = tier.lowerBound(properties);
        double high = tier.upperBound(properties);
        double minValue = properties.minAvgDailyValue(tier);
        Set<String> excluded = new HashSet<>();
        properties.excludedTickers().forEach(t -> excluded.add(t.trim().toUpperCase(Locale.ROOT)));
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter("tier", tier.label() + " (" + range(low, high) + ")",
                s -> s.marketCap() != null && s.marketCap() >= low && s.marketCap() < high));
        filters.add(new Filter("price", "Share price at least " + plain(properties.minPrice()),
                s -> s.price() != null && s.price() >= properties.minPrice()));
        filters.add(new Filter("traded", "Traded in the last " + properties.maxTradeAge().toDays() + " days (not suspended)",
                s -> s.lastTradeAt() != null && s.lastTradeAt().isAfter(
                        s.snapshotDate().atStartOfDay(zone).toInstant().minus(properties.maxTradeAge()))));
        filters.add(new Filter("liquidity", "Average traded value per day at least " + money(minValue),
                s -> s.avgDailyValue3m() != null && s.avgDailyValue3m() >= minValue));
        filters.add(new Filter("board", "Not on the watchlist board, not excluded",
                s -> !"WATCHLIST".equals(s.board()) && !excluded.contains(s.ticker())));
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
