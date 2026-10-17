package com.neracalab.backend.technical.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.idx.AutoRejection;
import com.neracalab.backend.technical.idx.AutoRejection.Hit;
import com.neracalab.backend.technical.idx.IdxTickSize;

/**
 * The {@code IndicatorSnapshot} of a listing: every indicator the future technical agents need, at the latest bar, as
 * a versioned JSON document ({@link #SCHEMA_VERSION}; docs/indicator-snapshot.md). Pure and deterministic: the same
 * bars, events and market context give the same document. Values that cannot be computed (too little history, no
 * source) are {@code null}, never guessed; {@code notes} say why.
 * <p>
 * The snapshot is data, not a signal: no score, no buy / sell verdict.
 */
public final class SnapshotCalculator {

    /**
     * Version of the snapshot JSON; raise it on any incompatible change and document it. 2: the macro section gained
     * {@code series}, {@code signals} and {@code regimes}. 3: no macro section - the snapshot is technical only (the
     * macro view is the Macro page).
     */
    public static final int SCHEMA_VERSION = 3;

    /**
     * Revision of the computation (part of the input hash): change it when a value is computed differently without a
     * schema change, so that every stored snapshot is recomputed at its next request or refresh.
     */
    public static final String ENGINE_REVISION = "2026-10-16.1";

    /** A corporate event as the snapshot and the chart markers see it. */
    public record EventInfo(LocalDate date, String type, BigDecimal value, String description, String source) {
    }

    private final TechnicalProperties properties;
    private final IdxTickSize ticks;
    private final Structure structure;
    private final AutoRejection autoRejection;

    public SnapshotCalculator(TechnicalProperties properties, IdxTickSize ticks) {
        this.properties = properties;
        this.ticks = ticks;
        this.structure = new Structure(properties, ticks);
        this.autoRejection = new AutoRejection(properties, ticks);
    }

    public Structure structure() {
        return structure;
    }

    public AutoRejection autoRejection() {
        return autoRejection;
    }

    /**
     * The snapshot of a listing.
     *
     * @param bars   daily, split-adjusted bars in the exchange currency, oldest first (at least one)
     * @param events the listing's corporate events, oldest first
     */
    public Map<String, Object> snapshot(String exchange, String ticker, String companyName, String sector, String currency,
                                        List<Candle> bars, List<EventInfo> events, MarketContext market) {
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("no bars for " + ticker);
        }
        List<String> notes = new ArrayList<>();
        IndicatorSet ind = IndicatorEngine.compute(bars, Interval.D);
        int n = bars.size();
        Candle last = bars.getLast();
        Candle prev = n > 1 ? bars.get(n - 2) : null;

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", SCHEMA_VERSION);
        doc.put("exchange", exchange);
        doc.put("ticker", ticker);
        doc.put("companyName", companyName);
        doc.put("sector", sector);
        doc.put("currency", currency);
        doc.put("asOfDate", last.date().toString());
        doc.put("priceBasis", "split-adjusted daily bars as traded, in the exchange currency; levels rounded to valid IDX prices");

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("tradingDays", n);
        history.put("firstDate", bars.getFirst().date().toString());
        history.put("lastDate", last.date().toString());
        boolean limited = n < properties.minHistoryDays();
        history.put("limited", limited);
        history.put("minHistoryDays", properties.minHistoryDays());
        if (limited) {
            notes.add("Limited history: " + n + " trading days (fewer than " + properties.minHistoryDays()
                    + "); long-period indicators are null");
        }
        doc.put("history", history);

        Map<String, Object> price = new LinkedHashMap<>();
        price.put("open", num(last.open()));
        price.put("high", num(last.high()));
        price.put("low", num(last.low()));
        price.put("close", num(last.close()));
        price.put("changePct", prev == null ? null : num(Stats.pctChange(last.close(), prev.close())));
        price.put("volume", num(last.volume()));
        price.put("valueTraded", num(last.value()));
        price.put("tickSize", num(ticks.tick(last.close())));
        price.put("lotSize", properties.lotSize());
        doc.put("price", price);

        doc.put("trend", trend(ind, bars));
        doc.put("momentum", momentum(ind));
        doc.put("volatility", volatility(ind));
        doc.put("volume", volume(ind, bars));
        doc.put("structure", structure(ind, bars));
        doc.put("relativeStrength", relativeStrength(bars, market, notes));
        doc.put("marketRegime", marketRegime(market, notes));
        doc.put("events", events(bars, events, notes));
        doc.put("risk", risk(ind, bars, market, notes));
        doc.put("notes", notes);
        return doc;
    }

    // ------------------------------------------------------------------ groups

    private Map<String, Object> trend(IndicatorSet ind, List<Candle> bars) {
        double close = bars.getLast().close();
        Map<String, Object> t = new LinkedHashMap<>();
        for (String k : List.of("sma20", "sma50", "sma100", "sma200", "ema20", "ema50")) {
            t.put(k, num(ind.last(k)));
        }
        t.put("sma200Slope20dPct", num(Stats.pctChange(ind.last("sma200"), ind.ago("sma200", 20))));
        Map<String, Object> vs = new LinkedHashMap<>();
        for (String k : List.of("sma20", "sma50", "sma100", "sma200", "ema20", "ema50")) {
            vs.put(k, num(Stats.pctChange(close, ind.last(k))));
        }
        t.put("priceVsMaPct", vs);
        double[] s50 = ind.get("sma50");
        double[] s200 = ind.get("sma200");
        int n = s50.length;
        String state = null;
        Integer days = null;
        String date = null;
        if (!Double.isNaN(s50[n - 1]) && !Double.isNaN(s200[n - 1])) {
            boolean golden = s50[n - 1] > s200[n - 1];
            state = golden ? "GOLDEN" : "DEATH";
            for (int i = n - 1; i > 0; i--) {
                if (Double.isNaN(s50[i - 1]) || Double.isNaN(s200[i - 1])) {
                    break;
                }
                if ((s50[i - 1] > s200[i - 1]) != golden) {
                    days = n - 1 - i;
                    date = bars.get(i).date().toString();
                    break;
                }
            }
        }
        Map<String, Object> cross = new LinkedHashMap<>();
        cross.put("state", state);
        cross.put("lastCrossDate", date);
        cross.put("barsSinceCross", days);
        t.put("sma50Vs200Cross", cross);
        t.put("adx14", num(ind.last("adx.adx")));
        t.put("plusDi14", num(ind.last("adx.plusDi")));
        t.put("minusDi14", num(ind.last("adx.minusDi")));
        t.put("usedBy", List.of("murphy", "ptj", "druckenmiller"));
        return t;
    }

    private Map<String, Object> momentum(IndicatorSet ind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rsi14", num(ind.last("rsi14")));
        Map<String, Object> macd = new LinkedHashMap<>();
        macd.put("line", num(ind.last("macd.line")));
        macd.put("signal", num(ind.last("macd.signal")));
        macd.put("histogram", num(ind.last("macd.histogram")));
        m.put("macd", macd);
        Map<String, Object> stoch = new LinkedHashMap<>();
        stoch.put("k", num(ind.last("stoch.k")));
        stoch.put("d", num(ind.last("stoch.d")));
        m.put("stochastic", stoch);
        Map<String, Object> roc = new LinkedHashMap<>();
        for (int p : new int[] {21, 63, 126, 252}) {
            roc.put("d" + p, num(ind.last("roc" + p)));
        }
        m.put("rocPct", roc);
        m.put("momentum12_1Pct", num(ind.last("mom12_1")));
        m.put("usedBy", List.of("murphy", "druckenmiller"));
        return m;
    }

    private Map<String, Object> volatility(IndicatorSet ind) {
        Map<String, Object> v = new LinkedHashMap<>();
        double[] atrPct = ind.get("atrPct");
        int n = atrPct.length;
        v.put("atr14", num(ind.last("atr14")));
        v.put("atrPct", num(ind.last("atrPct")));
        double percentile = Double.NaN;
        if (n >= 252 && !Double.isNaN(atrPct[n - 1])) {
            percentile = Stats.percentileRank(atrPct[n - 1], java.util.Arrays.copyOfRange(atrPct, n - 252, n));
        }
        v.put("atrPctPercentile252", num(percentile));
        Map<String, Object> bb = new LinkedHashMap<>();
        bb.put("upper", num(ind.last("bb.upper")));
        bb.put("middle", num(ind.last("bb.middle")));
        bb.put("lower", num(ind.last("bb.lower")));
        bb.put("bandwidthPct", num(ind.last("bb.width")));
        v.put("bollinger20_2", bb);
        v.put("usedBy", List.of("ptj", "gill", "risk"));
        return v;
    }

    private Map<String, Object> volume(IndicatorSet ind, List<Candle> bars) {
        int n = bars.size();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("avgVolume20", num(ind.last("volSma20")));
        v.put("avgVolume50", num(ind.last("volSma50")));
        v.put("avgValue20", num(ind.last("valueSma20")));
        v.put("avgValue50", num(ind.last("valueSma50")));
        double[] obv = ind.get("obv");
        v.put("obv", num(ind.last("obv")));
        double slope = n >= 20 ? Stats.slope(obv, n - 20, n - 1) : Double.NaN;
        v.put("obvSlope20", num(slope));
        double avg20 = ind.last("volSma20");
        v.put("obvSlope20PerAvgVolume", num(avg20 > 0 ? slope / avg20 : Double.NaN));
        double up = 0;
        double down = 0;
        if (n > 50) {
            for (int i = n - 50; i < n; i++) {
                if (bars.get(i).up(bars.get(i - 1))) {
                    up += bars.get(i).volume();
                } else if (bars.get(i).down(bars.get(i - 1))) {
                    down += bars.get(i).volume();
                }
            }
        }
        v.put("upDownVolumeRatio50", n > 50 && down > 0 ? num(up / down) : null);
        int upSpikes = 0;
        int downSpikes = 0;
        List<Map<String, Object>> spikes = new ArrayList<>();
        double multiple = properties.volume().spikeMultiple();
        for (int i = Math.max(51, n - 60); i < n; i++) {
            double avg = 0;
            for (int j = i - 50; j < i; j++) {
                avg += bars.get(j).volume();
            }
            avg /= 50;
            if (avg > 0 && bars.get(i).volume() >= multiple * avg) {
                boolean isUp = bars.get(i).up(bars.get(i - 1));
                boolean isDown = bars.get(i).down(bars.get(i - 1));
                upSpikes += isUp ? 1 : 0;
                downSpikes += isDown ? 1 : 0;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("date", bars.get(i).date().toString());
                s.put("direction", isUp ? "UP" : isDown ? "DOWN" : "FLAT");
                s.put("volumeMultiple", num(bars.get(i).volume() / avg));
                spikes.add(s);
            }
        }
        Map<String, Object> spike = new LinkedHashMap<>();
        spike.put("multiple", multiple);
        spike.put("windowDays", 60);
        spike.put("upDays", n > 51 ? upSpikes : null);
        spike.put("downDays", n > 51 ? downSpikes : null);
        spike.put("days", spikes);
        v.put("volumeSpikes60", spike);
        v.put("usedBy", List.of("murphy", "gill", "risk"));
        return v;
    }

    private Map<String, Object> structure(IndicatorSet ind, List<Candle> bars) {
        Map<String, Object> s = new LinkedHashMap<>();
        List<Structure.Pivot> pivots = structure.pivots(bars);
        int lookback = Math.max(0, bars.size() - properties.zones().lookbackDays());
        List<Map<String, Object>> highs = new ArrayList<>();
        List<Map<String, Object>> lows = new ArrayList<>();
        for (Structure.Pivot p : pivots) {
            if (p.index() < lookback) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", p.date().toString());
            m.put("price", num(ticks.round(p.price())));
            (p.high() ? highs : lows).add(m);
        }
        Map<String, Object> swings = new LinkedHashMap<>();
        swings.put("barsEachSide", properties.pivot().barsEachSide());
        swings.put("lookbackDays", properties.zones().lookbackDays());
        swings.put("highs", highs);
        swings.put("lows", lows);
        s.put("swings", swings);

        List<Map<String, Object>> zones = new ArrayList<>();
        for (Structure.Zone z : structure.zones(bars, pivots)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", z.type());
            m.put("low", num(z.low()));
            m.put("high", num(z.high()));
            m.put("mid", num(z.mid()));
            m.put("touches", z.touches());
            m.put("firstTouch", z.firstTouch().toString());
            m.put("lastTouch", z.lastTouch().toString());
            m.put("distancePct", num(z.distancePct()));
            zones.add(m);
        }
        s.put("zones", zones);
        s.put("zoneTolerancePct", properties.zones().tolerancePct() * 100);

        Structure.VolumeProfile profile = structure.volumeProfile(bars);
        Map<String, Object> vp = new LinkedHashMap<>();
        vp.put("days", profile.days());
        vp.put("from", profile.from() == null ? null : profile.from().toString());
        vp.put("to", profile.to() == null ? null : profile.to().toString());
        vp.put("bucketCount", profile.buckets().size());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Structure.ProfileBucket b : profile.topNodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", num(b.price()));
            m.put("low", num(b.low()));
            m.put("high", num(b.high()));
            m.put("volume", num(b.volume()));
            m.put("sharePct", num(b.sharePct()));
            m.put("distancePct", num(Stats.pctChange(b.price(), bars.getLast().close())));
            nodes.add(m);
        }
        vp.put("topNodes", nodes);
        s.put("volumeProfile", vp);

        int window = Math.min(bars.size(), 500);
        Structure.Retracement r = structure.retracement(bars.subList(bars.size() - window, bars.size()), ind.last("atr14"));
        if (r == null) {
            s.put("retracement", null);
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("direction", r.direction());
            m.put("swingStart", point(r.start().date(), r.start().price()));
            m.put("swingEnd", point(r.end().date(), r.end().price()));
            m.put("amplitude", num(r.amplitude()));
            m.put("majorSwingAtrMultiple", properties.retracement().majorSwingAtr());
            List<Map<String, Object>> levels = new ArrayList<>();
            for (Structure.Level l : r.levels()) {
                Map<String, Object> lv = new LinkedHashMap<>();
                lv.put("ratio", l.ratio());
                lv.put("price", num(l.price()));
                lv.put("distancePct", num(l.distancePct()));
                levels.add(lv);
            }
            m.put("levels", levels);
            s.put("retracement", m);
        }

        Structure.Extremes e = structure.extremes(bars);
        Map<String, Object> w52 = new LinkedHashMap<>();
        w52.put("high", num(e.week52High()));
        w52.put("highDate", e.week52HighDate().toString());
        w52.put("low", num(e.week52Low()));
        w52.put("lowDate", e.week52LowDate().toString());
        w52.put("distanceFromHighPct", num(e.distanceFromHighPct()));
        w52.put("distanceFromLowPct", num(e.distanceFromLowPct()));
        w52.put("complete", bars.size() >= 252);
        s.put("week52", w52);
        Map<String, Object> ath = new LinkedHashMap<>();
        ath.put("price", num(e.allTimeHigh()));
        ath.put("date", e.allTimeHighDate().toString());
        ath.put("drawdownPct", num(e.drawdownPct()));
        ath.put("since", bars.getFirst().date().toString());
        s.put("allTimeHigh", ath);

        Structure.Base base = structure.base(bars, ind.get("atrPct"));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("detected", base != null);
        if (base != null) {
            b.put("startDate", base.start().toString());
            b.put("endDate", base.end().toString());
            b.put("days", base.days());
            b.put("high", num(base.high()));
            b.put("low", num(base.low()));
            b.put("depthPct", num(base.depthPct()));
            b.put("atrPctStart", num(base.atrPctStart()));
            b.put("atrPctEnd", num(base.atrPctEnd()));
        }
        s.put("base", b);
        s.put("usedBy", List.of("murphy", "gill", "ptj"));
        return s;
    }

    private Map<String, Object> relativeStrength(List<Candle> bars, MarketContext market, List<String> notes) {
        Map<String, Object> rs = new LinkedHashMap<>();
        rs.put("benchmark", market.indexCode());
        RelativeStrength.Ratio ratio = RelativeStrength.ratio(bars, market.indexBars());
        if (ratio.dates().isEmpty()) {
            notes.add("No " + market.indexCode() + " bars stored: relative strength vs the index is null");
            rs.put("ratio", null);
            rs.put("ratioChange50Pct", null);
        } else {
            double[] values = ratio.values();
            int m = values.length;
            rs.put("ratio", num(values[m - 1]));
            double slope = m >= 50 ? Stats.slope(values, m - 50, m - 1) : Double.NaN;
            double mean = m >= 50 ? Stats.mean(values, m - 50, m - 1) : Double.NaN;
            // the regression slope over 50 bars as % of the ratio's mean: how much the ratio rose / fell over the window
            rs.put("ratioChange50Pct", num(mean > 0 ? slope * 49 / mean * 100 : Double.NaN));
        }
        int n = bars.size();
        double r6 = n > 126 ? Stats.pctChange(bars.getLast().close(), bars.get(n - 1 - 126).close()) : Double.NaN;
        double r12 = n > 252 ? Stats.pctChange(bars.getLast().close(), bars.get(n - 1 - 252).close()) : Double.NaN;
        rs.put("return6mPct", num(r6));
        rs.put("return12mPct", num(r12));
        double[] u6 = market.universe().values().stream().mapToDouble(MarketContext.Member::return6m).toArray();
        double[] u12 = market.universe().values().stream().mapToDouble(MarketContext.Member::return12m).toArray();
        rs.put("percentile6m", num(Stats.percentileRank(r6, u6)));
        rs.put("percentile12m", num(Stats.percentileRank(r12, u12)));
        rs.put("universe6m", java.util.Arrays.stream(u6).filter(d -> !Double.isNaN(d)).count());
        rs.put("universe12m", java.util.Arrays.stream(u12).filter(d -> !Double.isNaN(d)).count());
        rs.put("usedBy", List.of("murphy", "druckenmiller"));
        return rs;
    }

    /** Sector relative strength: added by {@link #withSector} (it needs the listing's own universe entry). */
    public static void withSector(Map<String, Object> doc, long companyId, MarketContext market) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rs = (Map<String, Object>) doc.get("relativeStrength");
        MarketContext.Member self = market.universe().get(companyId);
        Map<String, Object> sector = new LinkedHashMap<>();
        String name = self == null ? (String) doc.get("sector") : self.sector();
        sector.put("name", name);
        if (name == null) {
            sector.put("members", 0);
            rs.put("sector", sector);
            return;
        }
        List<MarketContext.Member> members = market.universe().values().stream().filter(m -> name.equals(m.sector())).toList();
        double m6 = Stats.median(members.stream().mapToDouble(MarketContext.Member::return6m).toArray());
        double m12 = Stats.median(members.stream().mapToDouble(MarketContext.Member::return12m).toArray());
        sector.put("members", members.size());
        boolean enough = members.size() >= 3;
        sector.put("medianReturn6mPct", enough ? num(m6) : null);
        sector.put("medianReturn12mPct", enough ? num(m12) : null);
        Double r6 = (Double) rs.get("return6mPct");
        Double r12 = (Double) rs.get("return12mPct");
        sector.put("vsSector6mPct", enough && r6 != null && !Double.isNaN(m6) ? num(r6 - m6) : null);
        sector.put("vsSector12mPct", enough && r12 != null && !Double.isNaN(m12) ? num(r12 - m12) : null);
        if (!enough) {
            sector.put("note", "fewer than 3 listings of the sector stored: no sector comparison");
        }
        rs.put("sector", sector);
    }

    private Map<String, Object> marketRegime(MarketContext market, List<String> notes) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", market.indexCode());
        if (market.indexBars().isEmpty()) {
            r.put("indexClose", null);
            r.put("indexSma200", null);
            r.put("indexAboveSma200", null);
            r.put("indexVsSma200Pct", null);
        } else {
            IndicatorSet ix = IndicatorEngine.compute(market.indexBars(), Interval.D);
            double close = market.indexBars().getLast().close();
            double sma = ix.last("sma200");
            r.put("indexDate", market.indexBars().getLast().date().toString());
            r.put("indexClose", num(close));
            r.put("indexSma200", num(sma));
            r.put("indexAboveSma200", Double.isNaN(sma) ? null : close > sma);
            r.put("indexVsSma200Pct", num(Stats.pctChange(close, sma)));
            r.put("indexSma200Slope20dPct", num(Stats.pctChange(sma, ix.ago("sma200", 20))));
        }
        double breadth = market.breadthAbove200Pct();
        r.put("breadthAbove200Pct", num(breadth));
        r.put("breadthCounted", market.breadthCounted());
        r.put("breadthUniverse", market.universe().size());
        r.put("breadthNote", "share of the stored active listings of the exchange (not the whole exchange) above their SMA 200");
        r.put("usedBy", List.of("ptj", "druckenmiller"));
        return r;
    }

    private Map<String, Object> events(List<Candle> bars, List<EventInfo> events, List<String> notes) {
        Map<String, Object> e = new LinkedHashMap<>();
        LocalDate asOf = bars.getLast().date();
        int days = properties.events().upcomingDays();
        List<Map<String, Object>> upcoming = new ArrayList<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        for (EventInfo ev : events) {
            if (ev.date().isAfter(asOf) && !ev.date().isAfter(asOf.plusDays(days))) {
                upcoming.add(event(ev));
            } else if (!ev.date().isAfter(asOf) && ev.date().isAfter(asOf.minusDays(365))) {
                recent.add(event(ev));
            }
        }
        e.put("upcomingDays", days);
        e.put("upcoming", upcoming);
        e.put("last12Months", recent);
        EventInfo earnings = null;
        for (EventInfo ev : events) {
            if ("EARNINGS".equals(ev.type()) && !ev.date().isAfter(asOf)) {
                earnings = ev;
            }
        }
        if (earnings == null) {
            e.put("lastEarnings", null);
            notes.add("No earnings dates stored (the filings do not carry their publication date yet): events.lastEarnings is null");
        } else {
            e.put("lastEarnings", earningsReaction(bars, earnings.date()));
        }
        e.put("usedBy", List.of("gill", "druckenmiller"));
        return e;
    }

    /** Gap of the first session after an earnings date and the 5-day follow-through from that session's close. */
    static Map<String, Object> earningsReaction(List<Candle> bars, LocalDate date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        int first = -1;
        for (int i = 0; i < bars.size(); i++) {
            if (!bars.get(i).date().isBefore(date)) {
                first = i;
                break;
            }
        }
        if (first <= 0) {
            m.put("gapPct", null);
            m.put("followThrough5dPct", null);
            return m;
        }
        m.put("gapPct", num(Stats.pctChange(bars.get(first).open(), bars.get(first - 1).close())));
        m.put("followThrough5dPct", first + 5 < bars.size()
                ? num(Stats.pctChange(bars.get(first + 5).close(), bars.get(first).close())) : null);
        return m;
    }

    private static Map<String, Object> event(EventInfo ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", ev.date().toString());
        m.put("type", ev.type());
        m.put("value", ev.value() == null ? null : ev.value().stripTrailingZeros().toPlainString());
        m.put("description", ev.description());
        m.put("source", ev.source());
        return m;
    }

    private Map<String, Object> risk(IndicatorSet ind, List<Candle> bars, MarketContext market, List<String> notes) {
        Map<String, Object> r = new LinkedHashMap<>();
        int n = bars.size();
        r.put("avgValueTraded20", num(ind.last("valueSma20")));
        int from = Math.max(1, n - 60);
        List<Hit> hits = autoRejection.hits(bars.subList(from - 1, n));
        long ara = hits.stream().filter(h -> h.type() == AutoRejection.Type.ARA).count();
        long arb = hits.stream().filter(h -> h.type() == AutoRejection.Type.ARB).count();
        r.put("araCount60", ara);
        r.put("arbCount60", arb);
        List<Hit> all = autoRejection.hits(bars);
        r.put("lastAra", all.stream().filter(h -> h.type() == AutoRejection.Type.ARA).reduce((a, b) -> b)
                .map(h -> h.date().toString()).orElse(null));
        r.put("lastArb", all.stream().filter(h -> h.type() == AutoRejection.Type.ARB).reduce((a, b) -> b)
                .map(h -> h.date().toString()).orElse(null));
        int gaps = 0;
        int counted = 0;
        for (int i = from; i < n; i++) {
            Candle prev = bars.get(i - 1);
            Candle bar = bars.get(i);
            counted++;
            if (bar.open() > prev.high() || bar.open() < prev.low()) {
                gaps++;
            }
        }
        r.put("gapDays60", gaps);
        r.put("gapFrequency60Pct", counted == 0 ? null : num(gaps * 100.0 / counted));
        int zeroVolume = 0;
        for (int i = Math.max(0, n - 20); i < n; i++) {
            zeroVolume += bars.get(i).volume() <= 0 ? 1 : 0;
        }
        r.put("zeroVolumeDays20", zeroVolume);
        // sessions of the index (IDX trading days) without a bar of the listing. After its last bar they only say the
        // stored prices are older than the index (prices are fetched per company); within its last 60 bars a run of
        // missing sessions, or no volume on its last 3 bars, is what a suspension looks like in daily bars
        Integer stale = null;
        Integer missing = null;
        int longestRun = 0;
        if (!market.indexBars().isEmpty()) {
            LocalDate lastBar = bars.getLast().date();
            LocalDate windowStart = bars.get(Math.max(0, n - 60)).date();
            java.util.Set<LocalDate> traded = new java.util.HashSet<>();
            for (int i = Math.max(0, n - 60); i < n; i++) {
                traded.add(bars.get(i).date());
            }
            stale = 0;
            missing = 0;
            int run = 0;
            for (Candle ix : market.indexBars()) {
                if (ix.date().isAfter(lastBar)) {
                    stale++;
                } else if (!ix.date().isBefore(windowStart)) {
                    if (traded.contains(ix.date())) {
                        run = 0;
                    } else {
                        missing++;
                        run++;
                        longestRun = Math.max(longestRun, run);
                    }
                }
            }
        }
        boolean silent = n >= 3 && bars.get(n - 1).volume() <= 0 && bars.get(n - 2).volume() <= 0 && bars.get(n - 3).volume() <= 0;
        r.put("sessionsAfterLastBar", stale);
        r.put("missingSessions60", missing);
        r.put("possiblySuspended", silent || longestRun >= 3);
        r.put("specialMonitoringBoard", null);
        r.put("suspended", null);
        notes.add("No source of the special monitoring board (Papan Pemantauan Khusus) or of suspensions: "
                + "risk.specialMonitoringBoard and risk.suspended are null; risk.possiblySuspended only says the listing has "
                + "3 or more consecutive index sessions without a bar within its last 60 bars, or no volume on its last 3 bars");
        r.put("usedBy", List.of("risk"));
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> point(LocalDate date, double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("price", num(price));
        return m;
    }

    /** A JSON number: null for NaN / infinity, else rounded to 4 decimals (6 significant digits for tiny values). */
    public static Double num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return null;
        }
        if (v != 0 && Math.abs(v) < 0.01) {
            return new BigDecimal(v).round(new java.math.MathContext(6)).doubleValue();
        }
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /** Hash input of a snapshot: the bars, events and market context it is computed from. */
    public static String inputHash(List<Candle> bars, List<EventInfo> events, MarketContext market, Object settings) {
        Map<String, Object> input = new HashMap<>();
        input.put("v", SCHEMA_VERSION);
        input.put("engine", ENGINE_REVISION);
        input.put("bars", bars.hashCode());
        input.put("events", events.hashCode());
        input.put("asOf", market.asOfDate());
        input.put("index", market.indexBars().isEmpty() ? null : market.indexBars().getLast());
        input.put("universe", market.universe().hashCode());
        input.put("settings", settings.hashCode());
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(new java.util.TreeMap<>(input).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
