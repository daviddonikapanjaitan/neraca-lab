package com.neracalab.backend.technical;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.technical.TechnicalRepository.Event;
import com.neracalab.backend.technical.TechnicalRepository.Listing;
import com.neracalab.backend.technical.engine.Aggregation;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.IndicatorEngine;
import com.neracalab.backend.technical.engine.IndicatorSet;
import com.neracalab.backend.technical.engine.Interval;
import com.neracalab.backend.technical.engine.MarketContext;
import com.neracalab.backend.technical.engine.RelativeStrength;
import com.neracalab.backend.technical.engine.SnapshotCalculator;
import com.neracalab.backend.technical.engine.SnapshotCalculator.EventInfo;
import com.neracalab.backend.technical.engine.Stats;
import com.neracalab.backend.technical.engine.Structure;
import com.neracalab.backend.technical.idx.AutoRejection;
import com.neracalab.backend.technical.idx.IdxTickSize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The technical analysis: chart data (bars, indicator series, levels, events, market panel), the agent-ready snapshot
 * and the refresh of the data it needs beyond the daily prices (the index, corporate events). No model is called
 * anywhere: every value is computed in Java from stored data.
 * <p>
 * Daily bars, indicator series and the market context are cached in memory (Caffeine). A cache key carries a
 * fingerprint of the stored prices (count, last date and the sums of close, adjusted close and volume), so new prices
 * or a provider re-adjustment are picked up at the next request; the refresh clears the cache as well.
 */
@Service
public class TechnicalService {

    private static final Logger log = LoggerFactory.getLogger(TechnicalService.class);

    /** Price basis of the chart bars: as traded (split-adjusted) or with dividends reinvested. */
    public enum Adjustment { SPLIT, TOTAL;

        public static Adjustment of(String value) {
            if (value == null || value.isBlank() || value.equalsIgnoreCase("split") || value.equalsIgnoreCase("true")) {
                return SPLIT;
            }
            if (value.equalsIgnoreCase("total") || value.equalsIgnoreCase("dividends")) {
                return TOTAL;
            }
            throw new IllegalArgumentException("Unknown adjustment '" + value + "': use split or total");
        }
    }

    /** The listing does not exist on the exchange. */
    public static class ListingNotFoundException extends RuntimeException {
        public ListingNotFoundException(String message) {
            super(message);
        }
    }

    /** The listing exists but has no stored prices. */
    public static class NoPricesException extends RuntimeException {
        public NoPricesException(String message) {
            super(message);
        }
    }

    /** What a refresh did, for the API. */
    public record RefreshStatus(boolean running, String exchange, String ticker, Instant startedAt, Instant finishedAt,
                                Integer indexRows, Integer eventCompanies, Integer eventsStored, Integer snapshots,
                                List<String> errors) {
    }

    private final TechnicalRepository repository;
    private final TechnicalProperties properties;
    private final YahooChartClient yahoo;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final com.neracalab.backend.price.PriceProperties prices;
    private final IdxTickSize ticks;
    private final SnapshotCalculator calculator;
    private final Cache<String, Object> cache;
    private final AtomicReference<RefreshStatus> refresh = new AtomicReference<>(
            new RefreshStatus(false, null, null, null, null, null, null, null, null, List.of()));

    public TechnicalService(TechnicalRepository repository, TechnicalProperties properties, YahooChartClient yahoo,
                            JsonMapper json, JdbcClient jdbc, com.neracalab.backend.price.PriceProperties prices) {
        this.repository = repository;
        this.prices = prices;
        this.properties = properties;
        this.yahoo = yahoo;
        this.json = json;
        this.jdbc = jdbc;
        this.ticks = new IdxTickSize(properties.tickSizes());
        this.calculator = new SnapshotCalculator(properties, ticks);
        this.cache = Caffeine.newBuilder().maximumSize(properties.cacheSize()).expireAfterWrite(properties.cacheTtl()).build();
    }

    public IdxTickSize ticks() {
        return ticks;
    }

    // ------------------------------------------------------------------ listings

    public List<Map<String, Object>> tickers(String exchangeCode, String query, int limit) {
        Exchange exchange = Exchange.of(exchangeCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Listing l : repository.search(exchange, query, Math.max(1, Math.min(limit, 200)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exchange", l.exchange());
            m.put("ticker", l.ticker());
            m.put("companyName", l.companyName());
            m.put("sector", l.sector());
            m.put("industry", l.industry());
            m.put("active", l.active());
            m.put("tradingDays", l.tradingDays() == null ? 0 : l.tradingDays());
            m.put("firstDate", l.firstDate() == null ? null : l.firstDate().toString());
            m.put("lastDate", l.lastDate() == null ? null : l.lastDate().toString());
            m.put("limitedHistory", l.tradingDays() == null || l.tradingDays() < properties.minHistoryDays());
            out.add(m);
        }
        return out;
    }

    Listing listing(String exchangeCode, String ticker) {
        Exchange exchange = Exchange.of(exchangeCode);
        String t = Tickers.normalize(ticker);
        return repository.listing(exchange, t).orElseThrow(() -> new ListingNotFoundException(
                "No company " + t + " on " + exchange.code()));
    }

    /** Daily split-adjusted bars of a listing in the exchange currency (cached by price fingerprint). */
    List<Candle> dailyBars(Listing listing) {
        String key = "bars:" + listing.companyId() + ":" + fingerprint(listing.companyId());
        @SuppressWarnings("unchecked")
        List<Candle> bars = (List<Candle>) cache.get(key, k -> repository.bars(listing.companyId(),
                Exchange.of(listing.exchange()).currency()));
        if (bars.isEmpty()) {
            throw new NoPricesException("No prices stored for " + listing.ticker() + " (run the price ingestion first)");
        }
        return bars;
    }

    private String fingerprint(long companyId) {
        return jdbc.sql("""
                        SELECT count(*) || ':' || coalesce(max(trading_date)::text, '') || ':' || coalesce(sum(close_price), 0)
                               || ':' || coalesce(sum(adjusted_close), 0) || ':' || coalesce(sum(volume), 0)
                        FROM price_daily WHERE company_id = :id""")
                .param("id", companyId).query(String.class).single();
    }

    private String indexFingerprint() {
        return jdbc.sql("""
                        SELECT count(*) || ':' || coalesce(max(trading_date)::text, '') || ':' || coalesce(sum(close_price), 0)
                        FROM index_daily WHERE index_code = :code""")
                .param("code", properties.index().code()).query(String.class).single();
    }

    private String exchangeFingerprint(Exchange exchange) {
        return jdbc.sql("""
                        SELECT count(*) || ':' || coalesce(max(p.trading_date)::text, '') || ':' || coalesce(sum(p.close_price), 0)
                        FROM price_daily p JOIN company c ON c.company_id = p.company_id
                        WHERE c.exchange = :exchange AND c.active""")
                .param("exchange", exchange.code()).query(String.class).single()
                + ":" + jdbc.sql("SELECT count(*) || ':' || coalesce(max(rate_date)::text, '') FROM fx_rate_daily")
                .query(String.class).single()
                + ":" + jdbc.sql("SELECT count(*) || ':' || coalesce(max(obs_date)::text, '') FROM macro_series")
                .query(String.class).single();
    }

    List<Candle> indexBars() {
        @SuppressWarnings("unchecked")
        List<Candle> bars = (List<Candle>) cache.get("index:" + indexFingerprint(),
                k -> repository.indexBars(properties.index().code()));
        return bars;
    }

    private List<Candle> basis(List<Candle> daily, Adjustment adjustment) {
        return adjustment == Adjustment.TOTAL ? daily.stream().map(Candle::onTotalReturnBasis).toList() : daily;
    }

    private IndicatorSet indicatorSet(Listing listing, Interval interval, Adjustment adjustment) {
        List<Candle> daily = dailyBars(listing);
        String key = "ind:" + listing.companyId() + ":" + interval + ":" + adjustment + ":" + daily.size() + ":"
                + daily.getLast().hashCode() + ":" + daily.hashCode();
        return (IndicatorSet) cache.get(key, k -> IndicatorEngine.compute(Aggregation.aggregate(basis(daily, adjustment), interval), interval));
    }

    // ------------------------------------------------------------------ chart data

    /** Bars of a listing between {@code from} and {@code to} (inclusive; null = open), as compact rows. */
    public Map<String, Object> ohlcv(String exchange, String ticker, String intervalCode, LocalDate from, LocalDate to,
                                     String adjustmentCode) {
        Listing listing = listing(exchange, ticker);
        Interval interval = Interval.of(intervalCode);
        Adjustment adjustment = Adjustment.of(adjustmentCode);
        List<Candle> daily = dailyBars(listing);
        List<Candle> bars = indicatorSet(listing, interval, adjustment).bars();
        List<List<Object>> rows = new ArrayList<>();
        for (Candle c : bars) {
            if (in(c.date(), from, to, interval)) {
                rows.add(List.of(time(c.date()), r4(c.open()), r4(c.high()), r4(c.low()), r4(c.close()), (long) c.volume(),
                        Math.round(c.value())));
            }
        }
        // auto-rejection hits are detected on the daily bars as traded; on a weekly / monthly chart they mark the bar
        // of the week / month they fall in
        List<List<Object>> hits = new ArrayList<>();
        for (AutoRejection.Hit hit : calculator.autoRejection().hits(daily)) {
            LocalDate barDate = barDate(bars, hit.date(), interval);
            if (barDate != null && in(barDate, from, to, interval)) {
                hits.add(List.of(time(barDate), hit.type().name(), r4(hit.changePct()), hit.date().toString()));
            }
        }
        Map<String, Object> out = header(listing, interval, daily);
        out.put("adjustment", adjustment.name().toLowerCase(Locale.ROOT));
        out.put("priceBasis", adjustment == Adjustment.SPLIT
                ? "split-adjusted, as traded since the last split (raw prices before a split are not stored)"
                : "adjusted for splits and cash dividends (total return); not tradable prices");
        out.put("columns", List.of("time", "open", "high", "low", "close", "volume", "value"));
        out.put("bars", rows);
        out.put("autoRejection", hits);
        return out;
    }

    /** Series of the requested indicators, cut to the range; {@code set} names from {@link IndicatorSet}, plus rs and index. */
    public Map<String, Object> indicators(String exchange, String ticker, String intervalCode, LocalDate from, LocalDate to,
                                          String set, String adjustmentCode) {
        Listing listing = listing(exchange, ticker);
        Interval interval = Interval.of(intervalCode);
        Adjustment adjustment = Adjustment.of(adjustmentCode);
        IndicatorSet ind = indicatorSet(listing, interval, adjustment);
        List<Candle> bars = ind.bars();
        Set<String> names = names(set, ind);
        Map<String, Object> series = new LinkedHashMap<>();
        for (String name : names) {
            double[] values = switch (name) {
                case "rs" -> relativeStrengthSeries(bars, interval);
                case "index" -> indexOnBars(bars, interval);
                default -> ind.get(name);
            };
            List<List<Object>> points = new ArrayList<>();
            for (int i = 0; i < bars.size(); i++) {
                if (!Double.isNaN(values[i]) && in(bars.get(i).date(), from, to, interval)) {
                    points.add(List.of(time(bars.get(i).date()), r6(values[i])));
                }
            }
            series.put(name, points);
        }
        Map<String, Object> out = header(listing, interval, dailyBars(listing));
        out.put("adjustment", adjustment.name().toLowerCase(Locale.ROOT));
        out.put("series", series);
        return out;
    }

    /** Every indicator name the API serves. */
    public List<String> indicatorNames() {
        List<String> names = new ArrayList<>(IndicatorEngine.compute(List.of(Candle.daily(LocalDate.EPOCH, 1, 1, 1, 1, 1, 0)),
                Interval.D).names());
        names.add("rs");
        names.add("index");
        return names;
    }

    private Set<String> names(String set, IndicatorSet ind) {
        Set<String> names = new LinkedHashSet<>();
        if (set == null || set.isBlank()) {
            throw new IllegalArgumentException("set is required, e.g. set=sma50,sma200,rsi14 (names: " + indicatorNames() + ")");
        }
        for (String raw : set.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            // a group name expands to its lines: bb -> bb.upper, bb.middle, bb.lower
            List<String> group = ind.names().stream().filter(k -> k.startsWith(name + ".")).sorted().toList();
            if (!group.isEmpty()) {
                names.addAll(group);
            } else if (ind.names().contains(name) || name.equals("rs") || name.equals("index")) {
                names.add(name);
            } else {
                throw new IllegalArgumentException("Unknown indicator '" + name + "' (names: " + indicatorNames() + ")");
            }
        }
        return names;
    }

    /** Stock / index ratio on the bars' dates, rebased to 100 at the first bar with both. */
    private double[] relativeStrengthSeries(List<Candle> bars, Interval interval) {
        double[] index = indexOnBars(bars, interval);
        double[] out = new double[bars.size()];
        double base = Double.NaN;
        for (int i = 0; i < bars.size(); i++) {
            double ratio = index[i] > 0 ? bars.get(i).close() / index[i] : Double.NaN;
            if (Double.isNaN(base) && !Double.isNaN(ratio)) {
                base = ratio;
            }
            out[i] = Double.isNaN(ratio) ? Double.NaN : ratio / base * 100;
        }
        return out;
    }

    /** The index close at each bar (for a week / month: at its last trading day). */
    private double[] indexOnBars(List<Candle> bars, Interval interval) {
        List<Candle> index = Aggregation.aggregate(indexBars(), interval);
        if (interval == Interval.D) {
            return RelativeStrength.indexOn(bars, index);
        }
        // aggregated bars are dated on the first day of the period: match by period
        double[] out = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            Double close = null;
            for (Candle c : index) {
                if (samePeriod(c.date(), bars.get(i).date(), interval)) {
                    close = c.close();
                    break;
                }
            }
            out[i] = close == null ? Double.NaN : close;
        }
        return out;
    }

    private static boolean samePeriod(LocalDate a, LocalDate b, Interval interval) {
        return switch (interval) {
            case D -> a.equals(b);
            case W -> a.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) == b.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR)
                    && a.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) == b.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case M -> a.getYear() == b.getYear() && a.getMonth() == b.getMonth();
        };
    }

    /** Support / resistance, retracements, swings, volume profile, 52-week and all-time levels (valid IDX prices). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> levels(String exchange, String ticker) {
        Listing listing = listing(exchange, ticker);
        Map<String, Object> snapshot = snapshotDocument(listing);
        Map<String, Object> structure = (Map<String, Object>) snapshot.get("structure");
        List<Candle> daily = dailyBars(listing);
        Structure.VolumeProfile profile = calculator.structure().volumeProfile(daily);
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (Structure.ProfileBucket b : profile.buckets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("low", r4(b.low()));
            m.put("high", r4(b.high()));
            m.put("price", r4(b.price()));
            m.put("volume", Math.round(b.volume()));
            m.put("sharePct", SnapshotCalculator.num(b.sharePct()));
            buckets.add(m);
        }
        Map<String, Object> out = header(listing, Interval.D, daily);
        out.put("close", ((Map<String, Object>) snapshot.get("price")).get("close"));
        out.put("tickSize", ((Map<String, Object>) snapshot.get("price")).get("tickSize"));
        out.put("zones", structure.get("zones"));
        out.put("retracement", structure.get("retracement"));
        out.put("swings", structure.get("swings"));
        out.put("week52", structure.get("week52"));
        out.put("allTimeHigh", structure.get("allTimeHigh"));
        out.put("base", structure.get("base"));
        Map<String, Object> vp = new LinkedHashMap<>((Map<String, Object>) structure.get("volumeProfile"));
        vp.put("buckets", buckets);
        out.put("volumeProfile", vp);
        out.put("keyLevels", keyLevels(structure, ((Number) ((Map<String, Object>) snapshot.get("price")).get("close")).doubleValue()));
        return out;
    }

    /** One table of every level with its distance from the close, from the top price down. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> keyLevels(Map<String, Object> structure, double close) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> z : (List<Map<String, Object>>) structure.get("zones")) {
            rows.add(level(z.get("type").equals("SUPPORT") ? "Support zone" : z.get("type").equals("RESISTANCE")
                    ? "Resistance zone" : "Pivot zone", (Double) z.get("mid"), (Double) z.get("low"), (Double) z.get("high"),
                    z.get("touches") + " touches, last " + z.get("lastTouch"), close));
        }
        Map<String, Object> r = (Map<String, Object>) structure.get("retracement");
        if (r != null) {
            for (Map<String, Object> l : (List<Map<String, Object>>) r.get("levels")) {
                double ratio = ((Number) l.get("ratio")).doubleValue();
                rows.add(level("Retracement " + trim(ratio * 100) + "%", (Double) l.get("price"), null, null,
                        "of the " + r.get("direction").toString().toLowerCase(Locale.ROOT) + " swing "
                                + ((Map<String, Object>) r.get("swingStart")).get("date") + " .. "
                                + ((Map<String, Object>) r.get("swingEnd")).get("date"), close));
            }
        }
        Map<String, Object> w = (Map<String, Object>) structure.get("week52");
        rows.add(level("52-week high", (Double) w.get("high"), null, null, String.valueOf(w.get("highDate")), close));
        rows.add(level("52-week low", (Double) w.get("low"), null, null, String.valueOf(w.get("lowDate")), close));
        Map<String, Object> ath = (Map<String, Object>) structure.get("allTimeHigh");
        rows.add(level("All-time high", (Double) ath.get("price"), null, null, ath.get("date") + " (since " + ath.get("since") + ")", close));
        Map<String, Object> vp = (Map<String, Object>) structure.get("volumeProfile");
        int i = 1;
        for (Map<String, Object> node : (List<Map<String, Object>>) vp.get("topNodes")) {
            // the bucket shown as the valid prices it spans; its share of the volume to one decimal
            double share = ((Number) node.get("sharePct")).doubleValue();
            rows.add(level("Volume node " + i++, (Double) node.get("price"), ticks.floor((Double) node.get("low")),
                    ticks.ceil((Double) node.get("high")), String.format(java.util.Locale.ROOT, "%.1f", share)
                            + "% of the volume of the last " + vp.get("days") + " days", close));
        }
        Map<String, Object> base = (Map<String, Object>) structure.get("base");
        if (Boolean.TRUE.equals(base.get("detected"))) {
            rows.add(level("Base high", (Double) base.get("high"), null, null, base.get("days") + "-day base from " + base.get("startDate"), close));
            rows.add(level("Base low", (Double) base.get("low"), null, null, base.get("days") + "-day base from " + base.get("startDate"), close));
        }
        rows.sort((a, b) -> Double.compare((Double) b.get("price"), (Double) a.get("price")));
        return rows;
    }

    private static Map<String, Object> level(String label, Double price, Double low, Double high, String detail, double close) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("price", price);
        m.put("low", low);
        m.put("high", high);
        m.put("distancePct", price == null ? null : SnapshotCalculator.num(Stats.pctChange(price, close)));
        m.put("detail", detail);
        return m;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    /** Corporate events of a listing (oldest first), dated as the chart dates its bars. */
    public Map<String, Object> events(String exchange, String ticker) {
        Listing listing = listing(exchange, ticker);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Event e : repository.events(listing.companyId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.date().toString());
            m.put("time", time(e.date()));
            m.put("type", e.type());
            m.put("value", e.value() == null ? null : e.value().stripTrailingZeros().toPlainString());
            m.put("description", e.description());
            m.put("source", e.source());
            m.put("sourceUrl", e.sourceUrl());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchange", listing.exchange());
        out.put("ticker", listing.ticker());
        out.put("events", rows);
        out.put("eventsFetchedAt", repository.eventsFetchedAt(listing.companyId(), YahooChartClient.SOURCE)
                .map(OffsetDateTime::toString).orElse(null));
        out.put("note", "Dividends and splits from Yahoo Finance and the splits recorded from the filings. Earnings dates, "
                + "RUPS / RUPSLB, rights issues and private placements have no source yet.");
        return out;
    }

    /** IHSG regime, breadth and macro series for the market panel. */
    public Map<String, Object> market(String exchangeCode, LocalDate from) {
        Exchange exchange = Exchange.of(exchangeCode);
        MarketContext context = marketContext(exchange);
        List<Candle> index = context.indexBars();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchange", exchange.code());
        out.put("asOfDate", context.asOfDate() == null ? null : context.asOfDate().toString());
        out.put("index", properties.index().code());
        Map<String, Object> regime = new LinkedHashMap<>();
        if (!index.isEmpty()) {
            IndicatorSet ix = IndicatorEngine.compute(index, Interval.D);
            double close = index.getLast().close();
            regime.put("date", index.getLast().date().toString());
            regime.put("close", SnapshotCalculator.num(close));
            regime.put("sma200", SnapshotCalculator.num(ix.last("sma200")));
            regime.put("aboveSma200", Double.isNaN(ix.last("sma200")) ? null : close > ix.last("sma200"));
            regime.put("vsSma200Pct", SnapshotCalculator.num(Stats.pctChange(close, ix.last("sma200"))));
            List<List<Object>> closes = new ArrayList<>();
            List<List<Object>> sma = new ArrayList<>();
            double[] s200 = ix.get("sma200");
            for (int i = 0; i < index.size(); i++) {
                if (from == null || !index.get(i).date().isBefore(from)) {
                    closes.add(List.of(time(index.get(i).date()), r4(index.get(i).close())));
                    if (!Double.isNaN(s200[i])) {
                        sma.add(List.of(time(index.get(i).date()), r4(s200[i])));
                    }
                }
            }
            regime.put("closes", closes);
            regime.put("sma200Series", sma);
        }
        out.put("regime", regime);
        Map<String, Object> breadth = new LinkedHashMap<>();
        breadth.put("above200Pct", SnapshotCalculator.num(context.breadthAbove200Pct()));
        breadth.put("counted", context.breadthCounted());
        breadth.put("universe", context.universe().size());
        breadth.put("note", "share of the stored active listings of " + exchange.code() + " above their SMA 200 (not the whole exchange)");
        out.put("breadth", breadth);
        Map<String, Object> macro = new LinkedHashMap<>();
        for (String code : List.of("BI_RATE", "SBN_10Y", "USD_IDR")) {
            List<List<Object>> points = new ArrayList<>();
            for (MarketContext.Observation o : context.macro().getOrDefault(code, List.of())) {
                if (from == null || !o.date().isBefore(from)) {
                    points.add(List.of(time(o.date()), r6(o.value())));
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("points", points);
            m.put("source", code.equals("USD_IDR") ? "ECB reference rates (fx_rate_daily)" : "macro_series (no source connected)");
            macro.put(code, m);
        }
        out.put("macro", macro);
        return out;
    }

    /** The market context of an exchange (cached by the fingerprint of its prices, the index and the macro data). */
    MarketContext marketContext(Exchange exchange) {
        String key = "market:" + exchange.code() + ":" + exchangeFingerprint(exchange) + ":" + indexFingerprint();
        return (MarketContext) cache.get(key, k -> buildMarketContext(exchange));
    }

    private MarketContext buildMarketContext(Exchange exchange) {
        List<Candle> index = indexBars();
        List<Listing> universe = repository.universe(exchange);
        // the latest day of the index or of any listing (the index is fetched by the refresh, the prices by the price
        // ingestion: either may be a day ahead of the other)
        LocalDate asOf = index.isEmpty() ? null : index.getLast().date();
        Map<Long, List<Candle>> bars = new LinkedHashMap<>();
        for (Listing l : universe) {
            List<Candle> b = repository.bars(l.companyId(), exchange.currency());
            if (!b.isEmpty()) {
                bars.put(l.companyId(), b);
                asOf = asOf == null ? b.getLast().date() : max(asOf, b.getLast().date());
            }
        }
        Map<Long, MarketContext.Member> members = new LinkedHashMap<>();
        for (Listing l : universe) {
            List<Candle> b = bars.get(l.companyId());
            if (b == null) {
                continue;
            }
            LocalDate cutoff = asOf;
            List<Candle> upTo = b.stream().filter(c -> !c.date().isAfter(cutoff)).toList();
            if (upTo.isEmpty()) {
                continue;
            }
            int n = upTo.size();
            double close = upTo.getLast().close();
            double r6 = n > 126 ? Stats.pctChange(close, upTo.get(n - 1 - 126).close()) : Double.NaN;
            double r12 = n > 252 ? Stats.pctChange(close, upTo.get(n - 1 - 252).close()) : Double.NaN;
            Boolean above = null;
            if (n >= 200 && !upTo.getLast().date().isBefore(asOf.minusDays(10))) {
                double sum = 0;
                for (int i = n - 200; i < n; i++) {
                    sum += upTo.get(i).close();
                }
                above = close > sum / 200;
            }
            members.put(l.companyId(), new MarketContext.Member(l.companyId(), l.ticker(), l.sector(), upTo.getLast().date(),
                    r6, r12, above));
        }
        Map<String, List<MarketContext.Observation>> macro = new LinkedHashMap<>();
        for (String code : List.of("BI_RATE", "SBN_10Y", "USD_IDR")) {
            macro.put(code, repository.macro(code).stream().map(o -> new MarketContext.Observation(o.date(), o.value())).toList());
        }
        return new MarketContext(exchange.code(), asOf, properties.index().code(), index, members, macro);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    // ------------------------------------------------------------------ snapshot

    /** The latest snapshot of a listing as JSON text: the stored one when its inputs are unchanged, else recomputed and stored. */
    public String snapshot(String exchange, String ticker) {
        Listing listing = listing(exchange, ticker);
        return snapshotJson(listing);
    }

    private String snapshotJson(Listing listing) {
        List<Candle> daily = dailyBars(listing);
        MarketContext market = marketContext(Exchange.of(listing.exchange()));
        List<EventInfo> events = eventInfos(listing);
        String hash = SnapshotCalculator.inputHash(daily, events, market, properties);
        var stored = repository.snapshot(listing.companyId());
        if (stored.isPresent() && stored.get().inputHash().equals(hash)
                && stored.get().schemaVersion() == SnapshotCalculator.SCHEMA_VERSION) {
            return stored.get().json();
        }
        Map<String, Object> doc = calculator.snapshot(listing.exchange(), listing.ticker(), listing.companyName(),
                listing.sector(), Exchange.of(listing.exchange()).currency(), daily, events, market);
        SnapshotCalculator.withSector(doc, listing.companyId(), market);
        String text = json.writeValueAsString(doc);
        repository.saveSnapshot(listing.companyId(), daily.getLast().date(), SnapshotCalculator.SCHEMA_VERSION, text, hash);
        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotDocument(Listing listing) {
        return json.readValue(snapshotJson(listing), Map.class);
    }

    private List<EventInfo> eventInfos(Listing listing) {
        return repository.events(listing.companyId()).stream()
                .map(e -> new EventInfo(e.date(), e.type(), e.value(), e.description(), e.source())).toList();
    }

    // ------------------------------------------------------------------ refresh

    public RefreshStatus refreshStatus() {
        return refresh.get();
    }

    /**
     * Starts a refresh in the background: the index bars, the events of the listings that are due (or of {@code ticker}),
     * then the snapshots. Returns the running status; an already running refresh is returned as it is.
     */
    public RefreshStatus startRefresh(String exchangeCode, String ticker, boolean forceEvents) {
        Exchange exchange = Exchange.of(exchangeCode);
        Listing only = ticker == null || ticker.isBlank() ? null : listing(exchange.code(), ticker);
        RefreshStatus running = new RefreshStatus(true, exchange.code(), only == null ? null : only.ticker(), Instant.now(),
                null, null, null, null, null, List.of());
        RefreshStatus previous = refresh.get();
        if (previous.running() || !refresh.compareAndSet(previous, running)) {
            return refresh.get();
        }
        Thread.ofVirtual().name("technical-refresh").start(() -> refresh.set(runRefresh(exchange, only, forceEvents, running)));
        return running;
    }

    /** The refresh itself (also run by the nightly schedule). */
    RefreshStatus runRefresh(Exchange exchange, Listing only, boolean forceEvents, RefreshStatus started) {
        List<String> errors = new ArrayList<>();
        Integer indexRows = null;
        int eventCompanies = 0;
        int eventsStored = 0;
        int snapshots = 0;
        try {
            indexRows = refreshIndex();
        } catch (RuntimeException e) {
            errors.add("Index " + properties.index().yahooSymbol() + ": " + e.getMessage());
            log.warn("technical refresh: index not refreshed: {}", e.getMessage());
        }
        List<Listing> listings = only != null ? List.of(only) : repository.universe(exchange);
        boolean rateLimited = false;
        for (Listing l : listings) {
            if (rateLimited) {
                break;
            }
            var fetched = repository.eventsFetchedAt(l.companyId(), YahooChartClient.SOURCE);
            boolean due = forceEvents || fetched.isEmpty()
                    || fetched.get().toInstant().isBefore(Instant.now().minus(properties.events().refreshAfter()));
            if (!due) {
                continue;
            }
            try {
                List<Event> events = yahoo.events(l.ticker() + ".JK", exchange.zone());
                repository.replaceEvents(l.companyId(), YahooChartClient.SOURCE, events);
                eventCompanies++;
                eventsStored += events.size();
            } catch (RateLimitedException e) {
                errors.add("Events: Yahoo Finance rate limit after " + eventCompanies + " listings; the rest wait for the next run");
                rateLimited = true;
            } catch (PriceProviderException e) {
                errors.add("Events of " + l.ticker() + ": " + e.getMessage());
            }
        }
        cache.invalidateAll();
        for (Listing l : listings) {
            try {
                snapshotJson(l);
                snapshots++;
            } catch (NoPricesException e) {
                // nothing to compute
            } catch (RuntimeException e) {
                errors.add("Snapshot of " + l.ticker() + ": " + e.getMessage());
                log.warn("technical refresh: snapshot of {} failed", l.ticker(), e);
            }
        }
        log.info("technical refresh {}: index rows {}, events of {} listings ({} events), {} snapshots, {} errors",
                exchange.code(), indexRows, eventCompanies, eventsStored, snapshots, errors.size());
        return new RefreshStatus(false, exchange.code(), started.ticker(), started.startedAt(), Instant.now(), indexRows,
                eventCompanies, eventsStored, snapshots, List.copyOf(errors));
    }

    /**
     * Fetches the index from 7 days before its last stored day (all of it the first time); returns rows inserted /
     * changed. Today's bar is stored only after the session close cutoff of the price ingestion
     * ({@code neracalab.prices.session-close-cutoff}): before it, it is an unfinished intraday bar.
     */
    int refreshIndex() {
        var index = properties.index();
        Exchange exchange = Exchange.of(index.exchange());
        LocalDate from = repository.latestIndexDate(index.code()).map(d -> d.minusDays(7)).orElse(index.historyFrom());
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(exchange.zone());
        LocalDate to = now.toLocalTime().isBefore(prices.sessionCloseCutoffTime()) ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        List<Candle> bars = yahoo.dailyBars(index.yahooSymbol(), exchange.zone(), from, to).stream()
                .filter(b -> !b.date().isAfter(to)).toList();
        return repository.upsertIndexBars(index.code(), exchange.code(), YahooChartClient.SOURCE, bars);
    }

    // ------------------------------------------------------------------ chart settings

    public JsonNode chartSettings(long userId) {
        return repository.chartSettings(userId).map(json::readTree).orElse(null);
    }

    public JsonNode saveChartSettings(long userId, JsonNode settings) {
        if (settings == null || !settings.isObject()) {
            throw new IllegalArgumentException("Chart settings must be a JSON object");
        }
        String text = json.writeValueAsString(settings);
        if (text.length() > 20_000) {
            throw new IllegalArgumentException("Chart settings are limited to 20,000 characters");
        }
        repository.saveChartSettings(userId, text);
        return settings;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> header(Listing listing, Interval interval, List<Candle> daily) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchange", listing.exchange());
        out.put("ticker", listing.ticker());
        out.put("companyName", listing.companyName());
        out.put("currency", Exchange.of(listing.exchange()).currency());
        out.put("interval", interval.name());
        out.put("tradingDays", daily.size());
        out.put("firstDate", daily.getFirst().date().toString());
        out.put("lastDate", daily.getLast().date().toString());
        out.put("limitedHistory", daily.size() < properties.minHistoryDays());
        out.put("minHistoryDays", properties.minHistoryDays());
        out.put("lotSize", properties.lotSize());
        return out;
    }

    /** Whether a bar dated {@code date} belongs to the range (a week / month that overlaps it counts). */
    private static boolean in(LocalDate date, LocalDate from, LocalDate to, Interval interval) {
        LocalDate periodEnd = switch (interval) {
            case D -> date;
            case W -> date.with(java.time.DayOfWeek.SUNDAY);
            case M -> date.withDayOfMonth(date.lengthOfMonth());
        };
        return (from == null || !periodEnd.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    /** The date of the (aggregated) bar a day falls in. */
    private static LocalDate barDate(List<Candle> bars, LocalDate day, Interval interval) {
        if (interval == Interval.D) {
            return day;
        }
        for (Candle c : bars) {
            if (samePeriod(c.date(), day, interval)) {
                return c.date();
            }
        }
        return null;
    }

    /** Chart time: seconds since the epoch at 00:00 UTC of the day (a business day for the chart library). */
    static long time(LocalDate date) {
        return date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }

    private static double r4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private static Object r6(double v) {
        return SnapshotCalculator.num(v);
    }
}
