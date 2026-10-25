package com.neracalab.backend.technical.screening;

import java.time.LocalDate;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.screening.MarketCapTier;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.data.FundamentalRepository.StockSnapshot;
import com.neracalab.backend.screening.data.ListingPriceRepository;
import com.neracalab.backend.screening.data.ListingPriceRepository.ListingBars;
import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalRepository;
import com.neracalab.backend.technical.TechnicalService;
import com.neracalab.backend.technical.engine.Aggregation;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.IndicatorEngine;
import com.neracalab.backend.technical.engine.Interval;
import com.neracalab.backend.technical.engine.MarketContext;
import com.neracalab.backend.technical.engine.PresetSummaries;
import com.neracalab.backend.technical.engine.SnapshotCalculator;
import com.neracalab.backend.technical.idx.IdxTickSize;
import com.neracalab.backend.technical.screening.TechnicalPanel.Prior;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stage 1 of a technical screening: the rules, no model. Every stock gets the indicator snapshot and the four preset
 * summaries of Companies > Technicals ({@link SnapshotCalculator}, {@link PresetSummaries}); an agent's prior is its
 * preset's score as 0-100 ({@code 50 + 50 x score / max}), the stock's prior overall the average of the selected
 * agents'.
 * <ul>
 *   <li><b>A market-cap tier</b> (Technicals (All)) reads the screening universe: the tradability filters of the stock
 *       screening (tier, price, trading, liquidity, board; {@code neracalab.screening}), then enough stored daily bars
 *       ({@code listing_price_daily}, at least {@code min-bars}, the last one within {@code max-bar-age}). Breadth and the
 *       relative-strength percentiles compare it with every listing of the exchange with bars. The best
 *       {@code top N x shortlist-multiplier} (at most {@code max-shortlist}) go to the agents.</li>
 *   <li><b>Selected stocks</b> (Technicals (Selected)) read the companies table and its daily prices ({@code price_daily}),
 *       exactly as Companies > Technicals does. A stock without prices or with fewer than {@code min-bars} is left out;
 *       one failing a tradability check (price, liquidity, stale prices, auto-rejection days) is kept and flagged. Every
 *       eligible stock goes to the agents.</li>
 * </ul>
 */
@Component
public class TechnicalScreener {

    private static final Logger log = LoggerFactory.getLogger(TechnicalScreener.class);
    /** Bars of the context universe: enough for the 12-month return and the SMA 200 at the as-of day. */
    private static final int CONTEXT_DAYS = 420;

    public record FunnelStep(String key, String label, int remaining) {
    }

    /**
     * A stock read by the rules.
     *
     * @param snapshot     its indicator snapshot (docs/indicator-snapshot.md)
     * @param summary      the preset summaries ({@code PresetSummaries}: summaries, caveats)
     * @param priors       per selected agent: its preset's summary and score
     * @param priorOverall the average of the selected agents' priors
     * @param flags        tradability checks it failed but was kept for (selected stocks), and the summaries' caveats
     * @param inCompanies  whether it is in the companies table (its chart is on Companies > Technicals)
     */
    public record Evaluation(String ticker, String companyName, String sector, Double marketCap, double close,
                            LocalDate asOfDate, Map<String, Object> snapshot, Map<String, Object> summary,
                            Map<TechnicalAgent, Prior> priors, double priorOverall, List<String> flags,
                            boolean inCompanies) {
    }

    /**
     * @param eligible  every stock that passed, best prior first
     * @param shortlist the best of them, analysed by the agents
     * @param excluded  selected stocks that did not pass, with the reason (in selection order); empty for a tier
     * @param market    IHSG against its 200-day average and the breadth of the universe, as the snapshots read them
     */
    public record Result(int universe, List<FunnelStep> funnel, List<Evaluation> eligible, List<Evaluation> shortlist,
                         Map<String, String> excluded, Map<String, Object> market, LocalDate asOfDate) {
    }

    private record Filter(String key, String label, Predicate<StockSnapshot> keep) {
    }

    private final ScreeningProperties screening;
    private final TechnicalScreeningProperties properties;
    private final TechnicalProperties technical;
    private final TechnicalRepository technicalRepository;
    private final TechnicalService technicalService;
    private final ListingPriceRepository listingPrices;
    private final JsonMapper json;
    private final SnapshotCalculator calculator;

    public TechnicalScreener(ScreeningProperties screening, TechnicalScreeningProperties properties,
                             TechnicalProperties technical, TechnicalRepository technicalRepository,
                             TechnicalService technicalService, ListingPriceRepository listingPrices, JsonMapper json) {
        this.screening = screening;
        this.properties = properties;
        this.technical = technical;
        this.technicalRepository = technicalRepository;
        this.technicalService = technicalService;
        this.listingPrices = listingPrices;
        this.json = json;
        this.calculator = new SnapshotCalculator(technical, technicalService.ticks());
    }

    public IdxTickSize ticks() {
        return technicalService.ticks();
    }

    // ------------------------------------------------------------------ a market-cap tier

    /** The listings of the tier passing the tradability filters: their bars must be current. */
    public List<StockSnapshot> marketFiltered(List<StockSnapshot> universe, MarketCapTier tier, ZoneId zone) {
        List<StockSnapshot> remaining = universe;
        for (Filter f : filters(tier, zone)) {
            remaining = remaining.stream().filter(f.keep()).toList();
        }
        return remaining;
    }

    /**
     * Stage 1 of a tier.
     *
     * @param universe the latest market data of every active listing of the exchange
     */
    public Result screenTier(Exchange exchange, MarketCapTier tier, List<StockSnapshot> universe,
                             List<TechnicalAgent> agents, int topN, ZoneId zone) {
        List<FunnelStep> funnel = new ArrayList<>();
        funnel.add(new FunnelStep("universe", "Active listings with market data", universe.size()));
        List<StockSnapshot> remaining = universe;
        for (Filter f : filters(tier, zone)) {
            remaining = remaining.stream().filter(f.keep()).toList();
            funnel.add(new FunnelStep(f.key(), f.label(), remaining.size()));
        }

        // the context: IHSG and every listing of the exchange with bars (breadth, relative-strength percentiles)
        List<Candle> index = technicalRepository.indexBars(technical.index().code());
        List<ListingBars> listings = listingPrices.listings(exchange);
        Map<String, ListingBars> byTicker = new LinkedHashMap<>();
        listings.forEach(l -> byTicker.put(l.ticker(), l));
        LocalDate asOf = index.isEmpty() ? null : index.getLast().date();
        for (ListingBars l : listings) {
            if (l.lastDate() != null) {
                asOf = asOf == null || l.lastDate().isAfter(asOf) ? l.lastDate() : asOf;
            }
        }
        if (asOf == null) {
            funnel.add(new FunnelStep("history", "Daily bars stored (Ingestion > Screening Data IDX)", 0));
            funnel.add(new FunnelStep("shortlist", "Shortlist for the AI agents (best rules scores)", 0));
            return new Result(universe.size(), funnel, List.of(), List.of(), Map.of(), Map.of(), null);
        }
        MarketContext market = context(exchange, index, listings, asOf);

        LocalDate stale = asOf.minusDays(properties.maxBarAge().toDays());
        List<StockSnapshot> withHistory = remaining.stream().filter(s -> {
            ListingBars l = byTicker.get(s.ticker());
            return l != null && l.bars() >= properties.minBars() && l.lastDate() != null && !l.lastDate().isBefore(stale);
        }).toList();
        funnel.add(new FunnelStep("history", "At least " + properties.minBars() + " daily bars stored, the last one within "
                + properties.maxBarAge().toDays() + " days of " + asOf, withHistory.size()));

        Map<Long, List<Candle>> bars = listingPrices.bars(
                withHistory.stream().map(s -> byTicker.get(s.ticker()).listingId()).toList(), null);
        Set<String> companies = new HashSet<>(technicalRepository.universe(exchange).stream()
                .map(TechnicalRepository.Listing::ticker).toList());
        List<Evaluation> eligible = new ArrayList<>();
        for (StockSnapshot s : withHistory) {
            ListingBars l = byTicker.get(s.ticker());
            List<Candle> b = bars.getOrDefault(l.listingId(), List.of());
            if (b.isEmpty()) {
                continue;
            }
            try {
                eligible.add(evaluate(exchange, l, s, b, market, agents, companies.contains(s.ticker())));
            } catch (RuntimeException e) {
                log.warn("technical screening: rules of {} failed: {}", s.ticker(), e.getMessage(), e);
            }
        }
        sort(eligible);
        int size = Math.min(properties.maxShortlist(), Math.max(topN, topN * properties.shortlistMultiplier()));
        List<Evaluation> shortlist = eligible.subList(0, Math.min(size, eligible.size()));
        funnel.add(new FunnelStep("shortlist", "Shortlist for the AI agents (best rules scores)", shortlist.size()));
        return new Result(universe.size(), funnel, List.copyOf(eligible), List.copyOf(shortlist), Map.of(),
                marketDocument(eligible), asOf);
    }

    /** The market context of the whole exchange: IHSG and the listings' latest returns and SMA 200 verdicts. */
    private MarketContext context(Exchange exchange, List<Candle> index, List<ListingBars> listings, LocalDate asOf) {
        List<ListingBars> stored = listings.stream().filter(l -> l.bars() > 0).toList();
        Map<Long, List<Candle>> recent = listingPrices.bars(stored.stream().map(ListingBars::listingId).toList(),
                asOf.minusDays(CONTEXT_DAYS));
        Map<Long, MarketContext.Member> members = new LinkedHashMap<>();
        for (ListingBars l : stored) {
            List<Candle> b = recent.get(l.listingId());
            MarketContext.Member member = b == null ? null : MarketContext.Member.of(l.listingId(), l.ticker(), l.sector(), b, asOf);
            if (member != null) {
                members.put(l.listingId(), member);
            }
        }
        return new MarketContext(exchange.code(), asOf, technical.index().code(), index, members);
    }

    /** The snapshot and the preset summaries of a listing from its stored bars, as Companies > Technicals computes them. */
    @SuppressWarnings("unchecked")
    private Evaluation evaluate(Exchange exchange, ListingBars l, StockSnapshot s, List<Candle> bars, MarketContext market,
                                List<TechnicalAgent> agents, boolean inCompanies) {
        Map<String, Object> doc = calculator.snapshot(exchange.code(), s.ticker(), s.companyName(), s.sector(),
                exchange.currency(), bars, List.of(), market);
        SnapshotCalculator.withSector(doc, l.listingId(), market);
        doc.put("priceBasis", "split-adjusted daily bars as traded (Yahoo Finance, listing_price_daily), in the "
                + "exchange currency; levels rounded to valid IDX prices");
        // as the stored snapshot is read back: JSON numbers, not the calculator's objects
        Map<String, Object> snapshot = json.readValue(json.writeValueAsString(doc), Map.class);
        Map<String, Object> summary = PresetSummaries.summarize(new PresetSummaries.Input(snapshot,
                IndicatorEngine.compute(Aggregation.aggregate(bars, Interval.D), Interval.D), ticks(), "STOCK"));
        Map<String, Object> roundTripped = json.readValue(json.writeValueAsString(summary), Map.class);
        List<String> flags = new ArrayList<>(caveats(roundTripped));
        return evaluation(s.ticker(), s.companyName(), s.sector(), s.marketCap(), snapshot, roundTripped, agents, flags,
                inCompanies);
    }

    // ------------------------------------------------------------------ selected stocks

    /**
     * Stage 1 of selected stocks of the companies table, read exactly as Companies > Technicals reads them.
     *
     * @param marketCaps the latest market cap of the selected tickers that the screening universe lists
     */
    @SuppressWarnings("unchecked")
    public Result screenSelection(Exchange exchange, List<String> tickers, Map<String, Double> marketCaps,
                                  List<TechnicalAgent> agents) {
        List<FunnelStep> funnel = new ArrayList<>();
        Map<String, String> excluded = new LinkedHashMap<>();
        funnel.add(new FunnelStep("universe", "Selected stocks", tickers.size()));
        Map<String, TechnicalRepository.Listing> listings = new LinkedHashMap<>();
        for (TechnicalRepository.Listing l : technicalRepository.search(exchange, "", 100_000)) {
            listings.put(l.ticker(), l);
        }
        List<String> priced = new ArrayList<>();
        for (String ticker : tickers) {
            TechnicalRepository.Listing l = listings.get(ticker);
            if (l == null || l.tradingDays() == null || l.tradingDays() == 0) {
                excluded.put(ticker, "No daily prices stored (Ingestion > Price Ingestion)");
            } else if (l.tradingDays() < properties.minBars()) {
                excluded.put(ticker, "Only " + l.tradingDays() + " daily bars stored (at least " + properties.minBars()
                        + " needed)");
            } else {
                priced.add(ticker);
            }
        }
        funnel.add(new FunnelStep("history", "At least " + properties.minBars() + " daily bars stored (companies table, "
                + "Ingestion > Price Ingestion)", priced.size()));

        List<Evaluation> eligible = new ArrayList<>();
        LocalDate asOf = null;
        for (String ticker : priced) {
            TechnicalRepository.Listing l = listings.get(ticker);
            try {
                Map<String, Object> snapshot = json.readValue(technicalService.snapshot(exchange.code(), ticker), Map.class);
                Map<String, Object> summary = json.readValue(json.writeValueAsString(
                        technicalService.summary(exchange.code(), ticker)), Map.class);
                Double marketCap = marketCaps.get(ticker);
                List<String> flags = new ArrayList<>(tradability(snapshot, marketCap));
                for (String caveat : caveats(summary)) {
                    if (!flags.contains(caveat)) {
                        flags.add(caveat);
                    }
                }
                Evaluation e = evaluation(ticker, l.companyName(), l.sector(), marketCap, snapshot, summary, agents, flags,
                        true);
                eligible.add(e);
                asOf = asOf == null || e.asOfDate().isAfter(asOf) ? e.asOfDate() : asOf;
            } catch (RuntimeException e) {
                excluded.put(ticker, "The rules could not read its chart: " + e.getMessage());
                log.warn("technical screening: rules of {} failed: {}", ticker, e.getMessage(), e);
            }
        }
        funnel.add(new FunnelStep("rules", "Read by the rules (indicator snapshot and preset summaries)", eligible.size()));
        sort(eligible);
        List<Evaluation> shortlist = eligible.subList(0, Math.min(properties.maxShortlist(), eligible.size()));
        funnel.add(new FunnelStep("shortlist", "Shortlist for the AI agents (every eligible selected stock)",
                shortlist.size()));
        return new Result(tickers.size(), funnel, List.copyOf(eligible), List.copyOf(shortlist), excluded,
                marketDocument(eligible), asOf);
    }

    /**
     * The tradability checks a selected stock failed (kept and flagged, as Fundamental (Selected) does): price below the
     * minimum, traded value below its tier's minimum. Stale prices, thin liquidity and auto-rejection days are flagged by
     * the summaries' caveats.
     */
    private List<String> tradability(Map<String, Object> snapshot, Double marketCap) {
        List<String> flags = new ArrayList<>();
        Double close = number(path(snapshot, "price.close"));
        if (close != null && close < screening.minPrice()) {
            flags.add("Share price " + plain(close) + ", below " + plain(screening.minPrice())
                    + " (special-monitoring board range)");
        }
        Double value = number(path(snapshot, "volume.avgValue20"));
        if (value != null && marketCap != null && marketCap > 0) {
            MarketCapTier own = MarketCapTier.of(marketCap, screening);
            double min = screening.minAvgDailyValue(own);
            if (value < min) {
                flags.add("Low liquidity: traded value per day " + money(value) + " (20 days), below the " + money(min)
                        + " minimum of " + own.label().toLowerCase(Locale.ROOT) + "s");
            }
        }
        return flags;
    }

    // ------------------------------------------------------------------ shared

    private Evaluation evaluation(String ticker, String companyName, String sector, Double marketCap,
                                  Map<String, Object> snapshot, Map<String, Object> summary, List<TechnicalAgent> agents,
                                  List<String> flags, boolean inCompanies) {
        Map<String, Map<String, Object>> presets = new LinkedHashMap<>();
        if (summary.get("summaries") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("preset") != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> preset = (Map<String, Object>) m;
                    presets.put(m.get("preset").toString(), preset);
                }
            }
        }
        Map<TechnicalAgent, Prior> priors = new EnumMap<>(TechnicalAgent.class);
        double sum = 0;
        for (TechnicalAgent agent : agents) {
            Map<String, Object> preset = presets.get(agent.preset());
            if (preset == null) {
                throw new IllegalStateException("no " + agent.preset() + " summary for " + ticker);
            }
            Prior prior = new Prior(priorScore(preset), preset);
            priors.put(agent, prior);
            sum += prior.score();
        }
        double close = number(path(snapshot, "price.close"));
        return new Evaluation(ticker, companyName == null ? ticker : companyName, sector, marketCap, close,
                LocalDate.parse(snapshot.get("asOfDate").toString()), snapshot, summary, priors,
                agents.isEmpty() ? 0 : round1(sum / agents.size()), List.copyOf(flags), inCompanies);
    }

    /** A preset's score as 0-100: 50 + 50 x score / its maximum, clamped. */
    static double priorScore(Map<String, Object> preset) {
        Double score = number(preset.get("score"));
        Double max = number(preset.get("maxScore"));
        if (score == null || max == null || max <= 0) {
            return 50;
        }
        return round1(Math.max(0, Math.min(100, 50 + 50 * score / max)));
    }

    private static void sort(List<Evaluation> eligible) {
        eligible.sort(Comparator.comparingDouble(Evaluation::priorOverall).reversed().thenComparing(Evaluation::ticker));
    }

    @SuppressWarnings("unchecked")
    private static List<String> caveats(Map<String, Object> summary) {
        return summary.get("caveats") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** IHSG and breadth as the snapshots read them (every snapshot of a run shares its market context). */
    private static Map<String, Object> marketDocument(List<Evaluation> eligible) {
        if (eligible.isEmpty() || !(eligible.getFirst().snapshot().get("marketRegime") instanceof Map<?, ?> regime)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("index", "indexDate", "indexClose", "indexSma200", "indexAboveSma200", "indexVsSma200Pct",
                "indexSma200Slope20dPct", "breadthAbove200Pct", "breadthCounted", "breadthUniverse", "breadthNote")) {
            out.put(key, regime.get(key));
        }
        return out;
    }

    /** The tradability filters of a tier, in order (those of the stock screening's Stage 1). */
    private List<Filter> filters(MarketCapTier tier, ZoneId zone) {
        Set<String> excluded = new HashSet<>();
        screening.excludedTickers().forEach(t -> excluded.add(t.trim().toUpperCase(Locale.ROOT)));
        double low = tier.lowerBound(screening);
        double high = tier.upperBound(screening);
        double minValue = screening.minAvgDailyValue(tier);
        long tradeDays = screening.maxTradeAge().toDays();
        return List.of(
                new Filter("tier", tier.label() + " (" + range(low, high) + ")",
                        s -> s.marketCap() != null && s.marketCap() >= low && s.marketCap() < high),
                new Filter("price", "Share price at least " + plain(screening.minPrice()),
                        s -> s.price() != null && s.price() >= screening.minPrice()),
                new Filter("traded", "Traded in the last " + tradeDays + " days (not suspended)",
                        s -> s.lastTradeAt() != null && s.lastTradeAt().isAfter(
                                s.snapshotDate().atStartOfDay(zone).toInstant().minus(screening.maxTradeAge()))),
                new Filter("liquidity", "Average traded value per day at least " + money(minValue),
                        s -> s.avgDailyValue3m() != null && s.avgDailyValue3m() >= minValue),
                new Filter("board", "Not on the watchlist board, not excluded",
                        s -> !"WATCHLIST".equals(s.board()) && !excluded.contains(s.ticker())));
    }

    /** The description of a tier's market-cap range: "Market cap at least Rp 10T". */
    public String tierDescription(MarketCapTier tier) {
        double low = tier.lowerBound(screening) / 1e12;
        double high = tier.upperBound(screening) / 1e12;
        return switch (tier) {
            case LARGE -> "Market cap at least Rp " + plain(low) + "T";
            case MID -> "Market cap Rp " + plain(low) + "T to " + plain(high) + "T";
            case SMALL -> "Market cap below Rp " + plain(high) + "T";
        };
    }

    /** The tiers in order with their descriptions. */
    public Map<MarketCapTier, String> tiers() {
        return Arrays.stream(MarketCapTier.values()).collect(Collectors.toMap(t -> t, this::tierDescription,
                (a, b) -> a, LinkedHashMap::new));
    }

    @SuppressWarnings("unchecked")
    static Object path(Map<String, Object> doc, String path) {
        Object node = doc;
        for (String key : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> m)) {
                return null;
            }
            node = ((Map<String, Object>) m).get(key);
        }
        return node;
    }

    static Double number(Object value) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
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
