package com.neracalab.backend.macro.outlook;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.MacroSignals;
import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * The Macro page (Macro, {@code GET /api/v1/macro/outlook}): a country's stock index read through the macro data of the
 * Druckenmiller, Paul Tudor Jones, George Soros and Ray Dalio presets. Fixed rules over {@link MacroSignals} (no AI
 * model), deterministic:
 * <ul>
 *   <li><b>index</b>: the index as a plain line, its changes and its 200-day average (used by the rules, not drawn);</li>
 *   <li><b>panels</b>: one chart per macro series with a summary of what it says for the country's equities, and its
 *       tone (POSITIVE / NEGATIVE / NEUTRAL) - the summaries read the same signals as the regimes, so they agree;</li>
 *   <li><b>presets</b>: Druckenmiller (liquidity), Paul Tudor Jones (risk regime and the 200-day rule), George Soros
 *       (reflexivity: the loop of foreign money, the rupiah and Bank Indonesia) and Ray Dalio (the growth / inflation
 *       quadrant, real rates and credit), each with a stance, the thesis, the evidence and when to buy and when to
 *       sell - the macro side, not the chart;</li>
 *   <li><b>conclusion</b>: an investability score (0 - 100) from six weighted parts, the verdict, the action and the
 *       conditions of buying and selling with whether each is met.</li>
 * </ul>
 * Point in time: only observations released on or before the as-of date are read (as the snapshot does).
 */
public final class MacroOutlook {

    public static final String RULES_VERSION = "2026-10-17.1";

    /** BI's inflation target for 2025 - 2027: 2.5 % +/- 1. */
    static final double BI_TARGET_LOW = 1.5;
    static final double BI_TARGET_HIGH = 3.5;
    /** Investability: from this score up investable, below {@link #NOT_INVESTABLE} not. */
    static final int INVESTABLE = 60;
    static final int NOT_INVESTABLE = 40;

    public static final String DISCLAIMER = "Rules over the stored macro data and the index, computed in Java (no AI "
            + "model); a summary of the conditions, not investment advice. Macro data lag the market.";

    /** A chart of the macro panel. */
    record Panel(String code, String group, String title, String unit, int digits, String readBy) {
    }

    /** The panels of Indonesia, in this order; a panel without stored data is left out (with a note). */
    static final List<Panel> PANELS = List.of(
            new Panel("BI_RATE", "Indonesia", "BI rate", "%", 2, "Druckenmiller, Tudor Jones"),
            new Panel("ID_CPI_YOY", "Indonesia", "Indonesia CPI inflation (YoY)", "%", 2, "Druckenmiller, Tudor Jones"),
            new Panel("SBN_10Y", "Indonesia", "SBN 10Y yield", "%", 2, "Druckenmiller, Tudor Jones"),
            new Panel("USD_IDR", "Indonesia", "USD/IDR", "IDR per USD", 0, "Druckenmiller"),
            new Panel("ID_FX_RESERVES", "Indonesia", "BI foreign exchange reserves", "USD bn", 1, "Druckenmiller"),
            new Panel("ID_M2_YOY", "Indonesia", "Indonesia M2 money supply (YoY)", "%", 1, "Druckenmiller"),
            new Panel("ID_FOREIGN_NET_BUY", "Indonesia", "Net foreign buy / sell on IDX", "IDR bn", 0, "Druckenmiller, Tudor Jones"),
            new Panel("ID_SBN_FOREIGN", "Indonesia", "Foreign ownership of SBN", "IDR tn", 1, "Druckenmiller"),
            new Panel("ID_CDS_5Y", "Indonesia", "Indonesia 5Y CDS", "bps", 0, "Tudor Jones"),
            new Panel("US_FED_FUNDS", "Global", "Fed funds rate", "%", 2, "Druckenmiller, Tudor Jones"),
            new Panel("US_10Y", "Global", "US 10Y yield", "%", 2, "Druckenmiller, Tudor Jones"),
            new Panel("US_CURVE", "Global", "US yield curve (10Y - 2Y)", "points", 2, "Tudor Jones"),
            new Panel("US_NET_LIQUIDITY", "Global", "US net liquidity (Fed assets - TGA - reverse repo)", "USD bn", 0, "Druckenmiller"),
            new Panel("US_HY_SPREAD", "Global", "US high-yield credit spread", "%", 2, "Druckenmiller, Tudor Jones"),
            new Panel("DXY", "Global", "US dollar index (DXY)", "index", 2, "Druckenmiller, Tudor Jones"),
            new Panel("VIX", "Global", "VIX (volatility)", "index", 2, "Tudor Jones"),
            new Panel("US_CPI_YOY", "Global", "US CPI inflation (YoY)", "%", 2, "Tudor Jones"),
            new Panel("CN_PMI_MFG", "Global", "China manufacturing PMI", "index", 1, "Druckenmiller"));

    /** One input of the investability score: a value in -1 .. +1 and its weight. */
    record Part(String key, String name, int weight, Double value, String text) {
    }

    private final String indexCode;
    private final String indexName;
    private final List<Candle> index;
    private final Map<String, List<Observation>> macro;
    private final LocalDate asOf;
    private final LocalDate from;
    private final Map<String, String> sources;
    private final List<String> notes = new ArrayList<>();
    private Map<String, Object> signals;
    /** The signals three months before the as-of date (the Soros loop's turn). */
    private Map<String, Object> signalsThen;
    private Map<String, Object> regimes;
    private Map<String, Object> regimesThen;
    private IndexRead read;

    private MacroOutlook(String indexCode, String indexName, List<Candle> index, Map<String, List<Observation>> macro,
                         LocalDate asOf, LocalDate from, Map<String, String> sources) {
        this.indexCode = indexCode;
        this.indexName = indexName;
        this.index = index == null ? List.of() : index;
        this.macro = macro == null ? Map.of() : macro;
        this.asOf = asOf;
        this.from = from;
        this.sources = sources == null ? Map.of() : sources;
    }

    /**
     * The page's data.
     *
     * @param index daily bars of the index, oldest first (only those on or before {@code asOf} are read)
     * @param macro series code -> observations, oldest first (as {@code MarketContext} holds them)
     * @param asOf  the as-of date: everything released by then is read (the page: today)
     * @param from  first day of the charts ({@code null}: everything stored)
     */
    public static Map<String, Object> compute(String indexCode, String indexName, List<Candle> index,
                                              Map<String, List<Observation>> macro, LocalDate asOf, LocalDate from) {
        return compute(indexCode, indexName, index, macro, asOf, from, Map.of());
    }

    /** @param sources series code -> where its data come from (shown under each chart); derived charts by their inputs */
    public static Map<String, Object> compute(String indexCode, String indexName, List<Candle> index,
                                              Map<String, List<Observation>> macro, LocalDate asOf, LocalDate from,
                                              Map<String, String> sources) {
        return new MacroOutlook(indexCode, indexName, index, macro, asOf, from, sources).compute();
    }

    /** The series a chart is made of: a derived chart's source is that of its inputs. */
    static String sourceCode(String panel) {
        return switch (panel) {
            case "US_CURVE" -> "US_10Y";
            case "US_NET_LIQUIDITY" -> "US_FED_ASSETS";
            case "US_CPI_YOY" -> "US_CPI_INDEX";
            case "ID_M2_YOY" -> "ID_M2";
            default -> panel;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compute() {
        List<String> signalNotes = new ArrayList<>();
        Map<String, Object> now = MacroSignals.compute(macro, asOf, signalNotes);
        signals = (Map<String, Object>) now.get("signals");
        regimes = (Map<String, Object>) now.get("regimes");
        Map<String, Object> then = MacroSignals.compute(macro, asOf.minusMonths(3), new ArrayList<>());
        regimesThen = (Map<String, Object>) then.get("regimes");
        signalsThen = (Map<String, Object>) then.get("signals");
        read = IndexRead.of(index, asOf);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOfDate", asOf.toString());
        out.put("index", indexSection());
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("liquidity", regimeWithThen("liquidity"));
        reg.put("risk", regimeWithThen("risk"));
        out.put("regimes", reg);
        out.put("panels", panels());
        out.put("presets", List.of(tudorJones(), druckenmiller(), soros(), dalio()));
        out.put("conclusion", conclusion());
        notes.addAll(signalNotes);
        out.put("notes", notes);
        out.put("rulesVersion", RULES_VERSION);
        out.put("disclaimer", DISCLAIMER);
        return out;
    }

    // ------------------------------------------------------------------ index

    /** The index as of the as-of date: close, changes, the 200-day average and its slope, the 52-week range. */
    record IndexRead(LocalDate date, Double close, Double change1mPct, Double change3mPct, Double ytdPct,
                     Double change1yPct, Double high52w, Double low52w, Double sma200, Double sma200Then) {

        static IndexRead of(List<Candle> bars, LocalDate asOf) {
            List<Candle> upTo = bars.stream().filter(b -> !b.date().isAfter(asOf)).toList();
            if (upTo.isEmpty()) {
                return new IndexRead(null, null, null, null, null, null, null, null, null, null);
            }
            Candle last = upTo.getLast();
            double close = last.close();
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (Candle b : upTo) {
                if (b.date().isAfter(last.date().minusYears(1))) {
                    high = Math.max(high, b.close());
                    low = Math.min(low, b.close());
                }
            }
            Candle yearEnd = closeOn(upTo, LocalDate.of(last.date().getYear() - 1, 12, 31));
            return new IndexRead(last.date(), close, change(upTo, close, last.date().minusMonths(1)),
                    change(upTo, close, last.date().minusMonths(3)),
                    yearEnd == null ? null : (close / yearEnd.close() - 1) * 100,
                    change(upTo, close, last.date().minusYears(1)), high, low, sma(upTo, upTo.size(), 200),
                    sma(upTo, upTo.size() - 21, 200));
        }

        private static Double change(List<Candle> bars, double close, LocalDate then) {
            Candle c = closeOn(bars, then);
            return c == null || c.close() <= 0 ? null : (close / c.close() - 1) * 100;
        }

        /** The last bar on or before a day (null before the first bar). */
        private static Candle closeOn(List<Candle> bars, LocalDate day) {
            Candle found = null;
            for (Candle b : bars) {
                if (b.date().isAfter(day)) {
                    break;
                }
                found = b;
            }
            return found;
        }

        /** The average close of the {@code n} bars ending before index {@code end} (exclusive); null with fewer. */
        private static Double sma(List<Candle> bars, int end, int n) {
            if (end < n || end > bars.size()) {
                return null;
            }
            double sum = 0;
            for (int i = end - n; i < end; i++) {
                sum += bars.get(i).close();
            }
            return sum / n;
        }

        boolean known() {
            return close != null;
        }

        Boolean aboveSma200() {
            return sma200 == null || close == null ? null : close > sma200;
        }

        Double vsSma200Pct() {
            return sma200 == null || close == null ? null : (close / sma200 - 1) * 100;
        }

        /** RISING / FALLING / FLAT: the 200-day average against a month (21 sessions) before, at +/- 0.5 %. */
        String sma200Slope() {
            if (sma200 == null || sma200Then == null) {
                return null;
            }
            double pct = (sma200 / sma200Then - 1) * 100;
            return pct >= 0.5 ? "RISING" : pct <= -0.5 ? "FALLING" : "FLAT";
        }

        Double fromHighPct() {
            return high52w == null || close == null ? null : (close / high52w - 1) * 100;
        }
    }

    private Map<String, Object> indexSection() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", indexCode);
        m.put("name", indexName);
        m.put("date", read.date() == null ? null : read.date().toString());
        m.put("close", r(read.close(), 2));
        m.put("change1mPct", r(read.change1mPct(), 2));
        m.put("change3mPct", r(read.change3mPct(), 2));
        m.put("ytdPct", r(read.ytdPct(), 2));
        m.put("change1yPct", r(read.change1yPct(), 2));
        m.put("high52w", r(read.high52w(), 2));
        m.put("low52w", r(read.low52w(), 2));
        m.put("fromHighPct", r(read.fromHighPct(), 2));
        m.put("sma200", r(read.sma200(), 2));
        m.put("vsSma200Pct", r(read.vsSma200Pct(), 2));
        m.put("aboveSma200", read.aboveSma200());
        m.put("sma200Slope", read.sma200Slope());
        List<List<Object>> points = new ArrayList<>();
        for (Candle b : index) {
            if ((from == null || !b.date().isBefore(from)) && !b.date().isAfter(asOf)) {
                points.add(List.of(epoch(b.date()), r(b.close(), 2)));
            }
        }
        m.put("points", points);
        if (!read.known()) {
            notes.add(indexCode + " is not stored yet: run the price update (Ingestion > Price Ingestion), which also "
                    + "fetches the indexes");
        }
        return m;
    }

    // ------------------------------------------------------------------ regimes

    @SuppressWarnings("unchecked")
    private Map<String, Object> regimeWithThen(String key) {
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) regimes.get(key));
        Map<String, Object> then = (Map<String, Object>) regimesThen.get(key);
        m.put("stateThen", then == null ? null : then.get("state"));
        m.put("scoreThen", then == null ? null : then.get("score"));
        return m;
    }

    private String regimeState(String key) {
        return str(map(regimes, key), "state");
    }

    private Double regimeScore(String key) {
        return dbl(map(regimes, key), "score");
    }

    private int regimeSignals(String key) {
        Double n = dbl(map(regimes, key), "signals");
        return n == null ? 0 : n.intValue();
    }

    private Double regimeScoreThen(String key) {
        Map<String, Object> then = map(regimesThen, key);
        return then == null || then.get("state") == null ? null : dbl(then, "score");
    }

    // ------------------------------------------------------------------ panels

    private List<Map<String, Object>> panels() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Panel p : PANELS) {
            List<double[]> series = series(p.code());
            if (series.isEmpty()) {
                missing.add(p.title());
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.code());
            m.put("group", p.group());
            m.put("title", p.title());
            m.put("unit", p.unit());
            m.put("digits", p.digits());
            m.put("readBy", p.readBy());
            List<List<Object>> points = new ArrayList<>();
            double[] last = null;
            for (double[] o : series) {
                LocalDate d = LocalDate.ofEpochDay((long) o[0]);
                if (from == null || !d.isBefore(from)) {
                    points.add(List.of(epoch(d), r(o[1], 6)));
                }
                last = o;
            }
            m.put("points", points);
            m.put("lastDate", last == null ? null : LocalDate.ofEpochDay((long) last[0]).toString());
            m.put("lastValue", last == null ? null : r(last[1], 6));
            m.put("source", sources.get(sourceCode(p.code())));
            String[] summary = summary(p.code());
            m.put("tone", summary[0]);
            m.put("summary", summary[1]);
            out.add(m);
        }
        if (!missing.isEmpty()) {
            notes.add("No data stored yet for: " + String.join(", ", missing)
                    + " (Ingestion > Macro; the IDX flows, SBN ownership, CDS and China PMI are CSV uploads)");
        }
        return out;
    }

    /**
     * A chart's points {epoch day, value} released by the as-of date, oldest first; derived series (the US curve, net
     * liquidity, CPI and M2 year on year, reserves in USD bn) are computed from the stored ones.
     */
    List<double[]> series(String code) {
        return switch (code) {
            case "US_CURVE" -> difference("US_10Y", "US_2Y");
            case "US_NET_LIQUIDITY" -> netLiquidity();
            case "US_CPI_YOY" -> yearOnYear("US_CPI_INDEX");
            case "ID_M2_YOY" -> yearOnYear("ID_M2");
            case "ID_FX_RESERVES" -> available(code).stream().map(o -> new double[] {o.date().toEpochDay(), o.value() / 1000})
                    .toList();
            default -> available(code).stream().map(o -> new double[] {o.date().toEpochDay(), o.value()}).toList();
        };
    }

    private List<Observation> available(String code) {
        List<Observation> obs = macro.get(code);
        if (obs == null) {
            return List.of();
        }
        return obs.stream().filter(o -> !o.released().isAfter(asOf) && !o.date().isAfter(asOf)).toList();
    }

    private List<double[]> difference(String a, String b) {
        Map<LocalDate, Double> right = new TreeMap<>();
        available(b).forEach(o -> right.put(o.date(), o.value()));
        List<double[]> out = new ArrayList<>();
        for (Observation o : available(a)) {
            Double v = right.get(o.date());
            if (v != null) {
                out.add(new double[] {o.date().toEpochDay(), o.value() - v});
            }
        }
        return out;
    }

    /** Weekly: Fed assets - TGA (both USD mn, Wednesdays) - reverse repo (USD bn, the last day on or before), USD bn. */
    private List<double[]> netLiquidity() {
        TreeMap<LocalDate, Double> tga = new TreeMap<>();
        available("US_TGA").forEach(o -> tga.put(o.date(), o.value()));
        TreeMap<LocalDate, Double> rrp = new TreeMap<>();
        available("US_RRP").forEach(o -> rrp.put(o.date(), o.value()));
        List<double[]> out = new ArrayList<>();
        for (Observation fed : available("US_FED_ASSETS")) {
            Map.Entry<LocalDate, Double> t = tga.floorEntry(fed.date());
            Map.Entry<LocalDate, Double> r = rrp.floorEntry(fed.date());
            if (t == null || r == null || t.getKey().isBefore(fed.date().minusDays(7))
                    || r.getKey().isBefore(fed.date().minusDays(7))) {
                continue;
            }
            out.add(new double[] {fed.date().toEpochDay(), fed.value() / 1000 - t.getValue() / 1000 - r.getValue()});
        }
        return out;
    }

    /** A monthly level against the same month a year before, %. */
    private List<double[]> yearOnYear(String code) {
        Map<LocalDate, Double> byMonth = new TreeMap<>();
        available(code).forEach(o -> byMonth.put(o.date().withDayOfMonth(1), o.value()));
        List<double[]> out = new ArrayList<>();
        for (Observation o : available(code)) {
            Double before = byMonth.get(o.date().withDayOfMonth(1).minusYears(1));
            if (before != null && before > 0) {
                out.add(new double[] {o.date().toEpochDay(), (o.value() / before - 1) * 100});
            }
        }
        return out;
    }

    /** {tone, text} of a panel, from the same signals the regimes read. */
    String[] summary(String code) {
        return switch (code) {
            case "BI_RATE" -> biRate();
            case "ID_CPI_YOY" -> idInflation();
            case "SBN_10Y" -> sbn();
            case "USD_IDR" -> rupiah();
            case "ID_FX_RESERVES" -> reserves();
            case "ID_M2_YOY" -> idM2();
            case "ID_FOREIGN_NET_BUY" -> foreignFlows();
            case "ID_SBN_FOREIGN" -> sbnForeign();
            case "ID_CDS_5Y" -> cds();
            case "US_FED_FUNDS" -> fed();
            case "US_10Y" -> us10y();
            case "US_CURVE" -> curve();
            case "US_NET_LIQUIDITY" -> netLiquiditySummary();
            case "US_HY_SPREAD" -> credit();
            case "DXY" -> dxy();
            case "VIX" -> vix();
            case "US_CPI_YOY" -> usInflation();
            case "CN_PMI_MFG" -> china();
            default -> new String[] {"NEUTRAL", ""};
        };
    }

    private static final String POSITIVE = "POSITIVE";
    private static final String NEGATIVE = "NEGATIVE";
    private static final String NEUTRAL = "NEUTRAL";

    private String[] stale(String what) {
        return new String[] {NEUTRAL, "The latest " + what + " is too old to read (stale): no signal until a newer "
                + "observation is stored."};
    }

    private String[] biRate() {
        Map<String, Object> bi = signal("biPolicy");
        if (bi == null) {
            return stale("BI rate");
        }
        String state = str(bi, "state");
        Map<String, Object> real = signal("idRealRate");
        StringBuilder t = new StringBuilder("BI rate ").append(f(dbl(bi, "value"), 2)).append('%');
        if (dbl(bi, "change6mPoints") != null) {
            t.append(" (").append(sp(dbl(bi, "change6mPoints"), 2)).append(" points in 6 months)");
        }
        t.append(": Bank Indonesia is ").append("CUTTING".equals(state) ? "cutting" : "HIKING".equals(state) ? "hiking"
                : "on hold").append('.');
        String realState = str(real, "state");
        if (real != null) {
            t.append(" The real rate (BI rate - CPI ").append(f(dbl(real, "cpiYoy"), 2)).append("%) is ")
                    .append(f(dbl(real, "value"), 2)).append("%: ").append(lower(realState)).append('.');
        }
        String tone;
        if ("CUTTING".equals(state)) {
            tone = POSITIVE;
            t.append(" Cheaper money supports credit, earnings and valuations.");
        } else if ("HIKING".equals(state)) {
            tone = NEGATIVE;
            t.append(" Tighter money weighs on credit growth and equity valuations.");
        } else {
            tone = "ACCOMMODATIVE".equals(realState) ? POSITIVE : NEUTRAL;
            t.append("RESTRICTIVE".equals(realState) ? " With a high real rate BI has room to cut once the rupiah allows."
                    : " Policy is steady; the next move decides the direction.");
        }
        return new String[] {tone, t.toString()};
    }

    private String[] idInflation() {
        Map<String, Object> inf = signal("inflation");
        Double cpi = dbl(inf, "idCpiYoy");
        if (cpi == null) {
            return stale("Indonesian CPI");
        }
        String trend = str(inf, "idTrend");
        String band = cpi > BI_TARGET_HIGH ? "above" : cpi < BI_TARGET_LOW ? "below" : "inside";
        StringBuilder t = new StringBuilder("Inflation ").append(f(cpi, 2)).append("% YoY in ")
                .append(month(str(inf, "idCpiMonth"))).append(", ").append(band).append(" BI's ")
                .append(f(BI_TARGET_LOW, 1)).append(" - ").append(f(BI_TARGET_HIGH, 1)).append("% target band")
                .append(trend == null ? "" : ", " + lower(trend) + " over 3 months").append('.');
        String tone;
        if ("above".equals(band) || "RISING".equals(trend)) {
            tone = NEGATIVE;
            t.append(" Pressure on BI to keep rates high.");
        } else if ("below".equals(band)) {
            tone = NEUTRAL;
            t.append(" Weak demand; BI has room to cut.");
        } else {
            tone = POSITIVE;
            t.append(" Inflation under control leaves BI room to cut when the rupiah allows.");
        }
        return new String[] {tone, t.toString()};
    }

    private String[] sbn() {
        Map<String, Object> s = signal("sbn10y");
        if (s == null) {
            return stale("SBN 10Y yield");
        }
        String trend = str(s, "trend");
        StringBuilder t = new StringBuilder("SBN 10Y yield ").append(f(dbl(s, "value"), 2)).append('%');
        if (dbl(s, "change3mPoints") != null) {
            t.append(" (").append(sp(dbl(s, "change3mPoints"), 2)).append(" points in 3 months, ").append(lower(trend))
                    .append(')');
        }
        Double spread = dbl(s, "spreadVsUs10y");
        Double spreadChange = dbl(s, "spreadChange3mPoints");
        if (spread != null) {
            t.append("; ").append(f(spread, 2)).append(" points over the US 10Y");
            if (spreadChange != null) {
                t.append(" (").append(sp(spreadChange, 2)).append(" in 3 months)");
            }
        }
        t.append('.');
        String tone;
        if ("FALLING".equals(trend)) {
            tone = POSITIVE;
            t.append(" Falling yields lift equity valuations and show demand for rupiah bonds.");
        } else if ("RISING".equals(trend)) {
            tone = NEGATIVE;
            t.append(" Rising yields compete with equities and signal money leaving Indonesian bonds.");
        } else {
            tone = NEUTRAL;
            t.append(spreadChange != null && spreadChange <= -0.25
                    ? " Yields are steady and the country premium narrowed: foreign demand holding up."
                    : spreadChange != null && spreadChange >= 0.25
                    ? " Yields are steady but the country premium widened."
                    : " Yields are steady.");
        }
        return new String[] {tone, t.toString()};
    }

    private String[] rupiah() {
        Map<String, Object> d = signal("dollar");
        String trend = str(d, "rupiahTrend");
        if (d == null || dbl(d, "usdIdr") == null) {
            return stale("USD/IDR rate");
        }
        StringBuilder t = new StringBuilder("USD/IDR ").append(f(dbl(d, "usdIdr"), 0));
        if (dbl(d, "usdIdrChange3mPct") != null) {
            t.append(" (").append(sp(dbl(d, "usdIdrChange3mPct"), 1)).append("% in 3 months)");
        }
        t.append(": the rupiah is ").append(trend == null ? "unknown" : lower(trend)).append('.');
        String tone;
        if ("WEAKENING".equals(trend)) {
            tone = NEGATIVE;
            t.append(" A falling rupiah drives foreign money out and forces BI to defend it with rates.");
        } else if ("STRENGTHENING".equals(trend)) {
            tone = POSITIVE;
            t.append(" A firm rupiah draws foreign money in and gives BI room to cut.");
        } else {
            tone = NEUTRAL;
            t.append(" A stable rupiah takes pressure off BI.");
        }
        return new String[] {tone, t.toString()};
    }

    private String[] reserves() {
        Map<String, Object> l = signal("idLiquidity");
        Double bn = dbl(l, "fxReservesUsdBn");
        if (bn == null) {
            return stale("reserves figure");
        }
        Double change = dbl(l, "fxReservesChange3mPct");
        StringBuilder t = new StringBuilder("BI reserves USD ").append(f(bn, 1)).append(" bn");
        if (change != null) {
            t.append(" (").append(sp(change, 1)).append("% in 3 months)");
        }
        t.append('.');
        if (change != null && change >= 1) {
            return new String[] {POSITIVE, t + " Growing reserves give BI more room to steady the rupiah."};
        }
        if (change != null && change <= -2) {
            return new String[] {NEGATIVE, t + " BI is spending reserves to defend the rupiah."};
        }
        return new String[] {NEUTRAL, t + " A steady buffer against capital outflows."};
    }

    private String[] idM2() {
        Map<String, Object> l = signal("idLiquidity");
        Double yoy = dbl(l, "m2YoyPct");
        if (yoy == null) {
            return stale("M2 figure");
        }
        String t = "Broad money M2 " + sp(yoy, 1) + "% YoY.";
        if (yoy >= 8) {
            return new String[] {POSITIVE, t + " Money growing faster than the economy: supportive domestic liquidity."};
        }
        if (yoy < 5) {
            return new String[] {NEGATIVE, t + " Slow money growth: tight domestic liquidity."};
        }
        return new String[] {NEUTRAL, t + " Money growing about in line with the economy."};
    }

    private String[] foreignFlows() {
        Map<String, Object> f = signal("foreignFlows");
        Double sum = dbl(f, "idxNetBuy20dBn");
        if (sum == null) {
            return stale("foreign flow figure");
        }
        String t = "Foreigners were net " + (sum >= 0 ? "buyers" : "sellers") + " of Rp " + f(Math.abs(sum), 0)
                + " bn on IDX over the last 20 sessions.";
        return sum > 0 ? new String[] {POSITIVE, t + " Foreign money supports the index."}
                : sum < 0 ? new String[] {NEGATIVE, t + " Foreign selling weighs on the large caps."}
                : new String[] {NEUTRAL, t};
    }

    private String[] sbnForeign() {
        Map<String, Object> f = signal("foreignFlows");
        Double tn = dbl(f, "sbnForeignTn");
        if (tn == null) {
            return stale("SBN ownership figure");
        }
        Double change = dbl(f, "sbnForeignChange1mTn");
        String t = "Foreigners hold Rp " + f(tn, 1) + " tn of tradable SBN"
                + (change == null ? "." : " (" + sp(change, 1) + " tn in a month).");
        return change != null && change > 0 ? new String[] {POSITIVE, t + " Foreign money coming into rupiah bonds."}
                : change != null && change < 0 ? new String[] {NEGATIVE, t + " Foreign money leaving rupiah bonds."}
                : new String[] {NEUTRAL, t};
    }

    private String[] cds() {
        List<double[]> s = series("ID_CDS_5Y");
        if (s.isEmpty()) {
            return stale("CDS quote");
        }
        double last = s.getLast()[1];
        return new String[] {last >= 150 ? NEGATIVE : last <= 80 ? POSITIVE : NEUTRAL,
                "Indonesia 5Y CDS " + f(last, 0) + " bps: the price of insuring against a default; "
                        + (last >= 150 ? "high country risk." : last <= 80 ? "low country risk." : "moderate country risk.")};
    }

    private String[] fed() {
        Map<String, Object> fed = signal("fedPolicy");
        if (fed == null) {
            return stale("Fed funds rate");
        }
        String state = str(fed, "state");
        StringBuilder t = new StringBuilder("Fed funds ").append(f(dbl(fed, "value"), 2)).append('%');
        if (dbl(fed, "change6mPoints") != null) {
            t.append(" (").append(sp(dbl(fed, "change6mPoints"), 2)).append(" points in 6 months)");
        }
        t.append(": the Fed is ").append("CUTTING".equals(state) ? "cutting" : "HIKING".equals(state) ? "hiking" : "on hold")
                .append('.');
        if ("HIKING".equals(state)) {
            return new String[] {NEGATIVE, t + " A tighter Fed drains global liquidity and lifts the dollar."};
        }
        if ("CUTTING".equals(state)) {
            return new String[] {POSITIVE, t + " A Fed that cuts sends money toward emerging markets."};
        }
        return new String[] {NEUTRAL, t + " Global money is neither added nor taken away."};
    }

    private String[] us10y() {
        Map<String, Object> c = signal("usCurve");
        Map<String, Object> inf = signal("inflation");
        if (c == null) {
            return stale("US 10Y yield");
        }
        Double change = dbl(c, "us10yChange3mPoints");
        StringBuilder t = new StringBuilder("US 10Y yield ").append(f(dbl(c, "us10y"), 2)).append('%');
        if (change != null) {
            t.append(" (").append(sp(change, 2)).append(" points in 3 months)");
        }
        if (dbl(inf, "usRealYield10y") != null) {
            t.append("; real yield ").append(f(dbl(inf, "usRealYield10y"), 2)).append('%');
        }
        t.append('.');
        if (change != null && change >= 0.25) {
            return new String[] {NEGATIVE, t + " Rising US yields pull money out of emerging markets."};
        }
        if (change != null && change <= -0.25) {
            return new String[] {POSITIVE, t + " Falling US yields send money looking for yield in emerging markets."};
        }
        return new String[] {NEUTRAL, t + " US yields are steady."};
    }

    private String[] curve() {
        Map<String, Object> c = signal("usCurve");
        if (c == null) {
            return stale("US yield curve");
        }
        String state = str(c, "state");
        String t = "US 10Y - 2Y " + sp(dbl(c, "value"), 2) + " points: " + lower(state)
                + (str(c, "trend") == null ? "" : ", " + lower(str(c, "trend"))) + '.';
        if ("INVERTED".equals(state)) {
            return new String[] {NEGATIVE, t + " An inverted curve has preceded US recessions."};
        }
        if ("NORMAL".equals(state)) {
            return new String[] {POSITIVE, t + " A normal curve: no recession signal."};
        }
        return new String[] {NEUTRAL, t + " A flat curve: late in the cycle, no recession signal yet."};
    }

    private String[] netLiquiditySummary() {
        Map<String, Object> n = signal("usNetLiquidity");
        if (n == null) {
            return stale("Fed balance sheet");
        }
        String direction = str(n, "direction");
        // no level here: the chart pairs each week's figures by date, the signal takes the latest of each
        String t = "US net liquidity (Fed assets - TGA - reverse repo) "
                + (dbl(n, "change3mPct") == null ? "has no 3-month change" : sp(dbl(n, "change3mPct"), 1) + "% in 3 months")
                + ": " + lower(direction) + '.';
        if ("GROWING".equals(direction)) {
            return new String[] {POSITIVE, t + " More dollars in the system tend to lift risk assets everywhere."};
        }
        if ("SHRINKING".equals(direction)) {
            return new String[] {NEGATIVE, t + " Fewer dollars in the system: a headwind for risk assets."};
        }
        return new String[] {NEUTRAL, t};
    }

    private String[] credit() {
        Map<String, Object> c = signal("creditStress");
        if (c == null) {
            return stale("credit spread");
        }
        String state = str(c, "state");
        String t = "US high-yield spread " + f(dbl(c, "value"), 2) + "% (" + ordinal(dbl(c, "percentile1y"))
                + " percentile of the year, " + sp(dbl(c, "change1mPoints"), 2) + " points in a month): " + lower(state)
                + '.';
        if ("STRESSED".equals(state)) {
            return new String[] {NEGATIVE, t + " Credit markets price more defaults; stocks usually follow."};
        }
        if ("CALM".equals(state)) {
            return new String[] {POSITIVE, t + " Calm credit markets support risk-taking."};
        }
        return new String[] {NEUTRAL, t};
    }

    private String[] dxy() {
        Map<String, Object> d = signal("dollar");
        if (d == null || dbl(d, "dxy") == null) {
            return stale("dollar index");
        }
        String trend = str(d, "dxyTrend");
        String t = "DXY " + f(dbl(d, "dxy"), 2) + " (" + sp(dbl(d, "dxyChange3mPct"), 1) + "% in 3 months): the dollar is "
                + lower(trend) + '.';
        if ("STRENGTHENING".equals(trend)) {
            return new String[] {NEGATIVE, t + " A strong dollar drains emerging markets."};
        }
        if ("WEAKENING".equals(trend)) {
            return new String[] {POSITIVE, t + " A weaker dollar sends money to emerging markets."};
        }
        return new String[] {NEUTRAL, t};
    }

    private String[] vix() {
        Map<String, Object> v = signal("riskAppetite");
        if (v == null) {
            return stale("VIX");
        }
        String state = str(v, "state");
        String t = "VIX " + f(dbl(v, "vix"), 2) + " (" + ordinal(dbl(v, "percentile1y")) + " percentile of the year): "
                + ("FEAR".equals(state) ? "fear" : "CALM".equals(state) ? "calm" : "normal") + '.';
        if ("FEAR".equals(state)) {
            return new String[] {NEGATIVE, t + " Investors pay up for protection; risk is being cut."};
        }
        if ("CALM".equals(state)) {
            return new String[] {POSITIVE, t + " Low volatility: investors take risk."};
        }
        return new String[] {NEUTRAL, t};
    }

    private String[] usInflation() {
        Map<String, Object> inf = signal("inflation");
        Double cpi = dbl(inf, "usCpiYoy");
        if (cpi == null) {
            return stale("US CPI");
        }
        String trend = str(inf, "usTrend");
        String t = "US CPI " + f(cpi, 2) + "% YoY in " + month(str(inf, "usCpiMonth"))
                + (dbl(inf, "usCoreCpiYoy") == null ? "" : ", core " + f(dbl(inf, "usCoreCpiYoy"), 2) + "%")
                + (trend == null ? "" : "; " + lower(trend) + " over 3 months") + '.';
        if ("RISING".equals(trend)) {
            return new String[] {NEGATIVE, t + " Rising inflation keeps the Fed tight."};
        }
        if ("FALLING".equals(trend)) {
            return new String[] {POSITIVE, t + " Falling inflation lets the Fed ease."};
        }
        return new String[] {NEUTRAL, t};
    }

    private String[] china() {
        Map<String, Object> c = signal("china");
        if (c == null) {
            return stale("China PMI");
        }
        boolean expansion = "EXPANSION".equals(str(c, "state"));
        String t = "China PMI " + f(dbl(c, "pmi"), 1) + " in " + month(str(c, "month")) + ": "
                + (expansion ? "expansion" : "contraction") + '.';
        return expansion ? new String[] {POSITIVE, t + " Chinese demand supports Indonesia's coal, nickel and palm oil."}
                : new String[] {NEGATIVE, t + " Weak Chinese demand weighs on Indonesia's commodity exports."};
    }

    // ------------------------------------------------------------------ presets

    /** "IHSG 5,185 is 26.3% below its 200-day average (7,033)" */
    private String trendText() {
        if (read.sma200() == null) {
            return indexCode + (read.known() ? " has less than 200 days of history" : " is not stored");
        }
        double vs = read.vsSma200Pct();
        return indexCode + " " + f(read.close(), 0) + " is " + f(Math.abs(vs), 1) + "% " + (vs >= 0 ? "above" : "below")
                + " its 200-day average (" + f(read.sma200(), 0) + ")";
    }

    private Map<String, Object> druckenmiller() {
        String state = regimeState("liquidity");
        Double score = regimeScore("liquidity");
        Double then = regimeScoreThen("liquidity");
        int n = regimeSignals("liquidity");
        boolean turning = score != null && then != null && score - then >= 2;
        Boolean above = read.aboveSma200();
        String stance;
        String label;
        if (state == null) {
            stance = "WAIT";
            label = "Wait: too few liquidity signals to read";
        } else if (state.equals("EASING")) {
            stance = Boolean.TRUE.equals(above) ? "BUY" : "ACCUMULATE";
            label = Boolean.TRUE.equals(above) ? "Buy: liquidity is easing and the index confirms"
                    : "Accumulate: liquidity is easing ahead of the index";
        } else if (state.equals("NEUTRAL")) {
            stance = turning ? "ACCUMULATE" : "WAIT";
            label = turning ? "Accumulate: liquidity is turning" : "Wait: liquidity gives no direction";
        } else {
            stance = turning ? "WAIT" : "AVOID";
            label = turning ? "Wait: still tight, but turning - watch for the first cut"
                    : "Avoid: liquidity is draining";
        }
        List<Map<String, Object>> points = new ArrayList<>();
        components("liquidity").forEach(points::add);
        Map<String, Object> real = signal("idRealRate");
        if (real != null) {
            String rs = str(real, "state");
            points.add(point("ACCOMMODATIVE".equals(rs) ? POSITIVE : NEUTRAL,
                    "Indonesian real rate " + f(dbl(real, "value"), 2) + "% (" + lower(rs) + ")"
                            + ("RESTRICTIVE".equals(rs) ? ": room for BI to cut" : "")));
        }
        String[] reserves = reserves();
        if (dbl(signal("idLiquidity"), "fxReservesUsdBn") != null) {
            points.add(point(reserves[0], reserves[1]));
        }
        if (read.sma200() != null) {
            points.add(point(Boolean.TRUE.equals(above) ? POSITIVE : NEGATIVE, trendText() + " (confirmation, not the signal)"));
        }
        String thesis = "Druckenmiller reads the market through liquidity, 12 - 24 months ahead: the direction of the Fed "
                + "and Bank Indonesia, dollar liquidity, credit and the rupiah. Liquidity is "
                + (state == null ? "unknown" : lower(state)) + (score == null ? "" : " (" + sp(score, 0) + " from " + n
                + " signals" + (then == null ? "" : "; " + sp(then, 0) + " three months ago") + ")") + ". "
                + drivers("liquidity") + label + '.';
        List<String> buy = new ArrayList<>();
        List<String> sell = new ArrayList<>();
        buy.add("The liquidity regime turns easing (+2 or more; now " + regimeText("liquidity")
                + "): the Fed or BI cuts, US net liquidity grows, credit spreads narrow, the dollar weakens");
        buy.add("Liquidity improves by 2 points or more within 3 months while not tightening: accumulate before the index "
                + "turns");
        buy.add("The rupiah holds steady or strengthens while BI cuts");
        sell.add("The liquidity regime turns tightening (-2 or lower; now " + regimeText("liquidity") + ")");
        sell.add("BI hikes to defend a falling rupiah, or the dollar strengthens more than 2% in 3 months");
        sell.add("US credit spreads jump (high-yield spread above its 80th percentile of the year)");
        return preset("druckenmiller", "Druckenmiller", "Liquidity first: buy when money is getting easier, sell when it "
                + "is drained, 12 - 24 months ahead of earnings", stance, label, score, n, thesis, points, buy, sell);
    }

    private Map<String, Object> tudorJones() {
        String state = regimeState("risk");
        Double score = regimeScore("risk");
        int n = regimeSignals("risk");
        Boolean above = read.aboveSma200();
        String stance;
        String label;
        if (above == null) {
            stance = "WAIT";
            label = "Wait: no 200-day average of " + indexCode + " yet";
        } else if (!above) {
            stance = "RISK_ON".equals(state) ? "WAIT" : "AVOID";
            label = "RISK_ON".equals(state) ? "Wait: the regime is risk-on, but " + indexCode + " is below its 200-day average"
                    : "Avoid: nothing below the 200-day average";
        } else if ("RISK_ON".equals(state)) {
            stance = "BUY";
            label = "Buy: above the 200-day average in a risk-on regime";
        } else if ("RISK_OFF".equals(state)) {
            stance = "WAIT";
            label = "Wait: the trend holds but the regime turned risk-off - tighten stops";
        } else {
            stance = score != null && score >= 0 ? "ACCUMULATE" : "WAIT";
            label = score != null && score >= 0 ? "Accumulate: above the 200-day average, the regime is neutral"
                    : "Wait: above the 200-day average, but the regime leans risk-off";
        }
        List<Map<String, Object>> points = new ArrayList<>();
        if (read.sma200() != null) {
            points.add(point(Boolean.TRUE.equals(above) ? POSITIVE : NEGATIVE, "Rule one: " + trendText()
                    + (read.sma200Slope() == null ? "" : "; the average is " + lower(read.sma200Slope()))));
        }
        components("risk").forEach(points::add);
        String[] inf = idInflation();
        if (dbl(signal("inflation"), "idCpiYoy") != null) {
            points.add(point(inf[0], inf[1]));
        }
        String thesis = "Tudor Jones plays defence first: " + trendText() + ", and he holds nothing below it. The risk "
                + "regime (volatility, credit, the US curve, the dollar, US inflation, SBN yields) is "
                + (state == null ? "unknown" : lower(state).replace('_', ' ')) + (score == null ? "" : " (" + sp(score, 0)
                + " from " + n + " signals)") + ". " + drivers("risk") + label + '.';
        List<String> buy = new ArrayList<>();
        List<String> sell = new ArrayList<>();
        if (read.sma200() != null) {
            buy.add(indexCode + " closes above its 200-day average (" + f(read.sma200(), 0) + "; now "
                    + sp(read.vsSma200Pct(), 1) + "%)");
        }
        buy.add("The risk regime is risk-on or neutral (now " + regimeText("risk") + "): VIX below 20, credit spreads calm, "
                + "US inflation not rising, SBN yields steady or falling");
        sell.add(indexCode + " closes below its 200-day average" + (read.sma200() == null ? "" : " (" + f(read.sma200(), 0) + ")"));
        sell.add("The risk regime turns risk-off (-2 or lower): VIX above 25, credit spreads widening, the US curve "
                + "inverting");
        sell.add("Inflation re-accelerates (US or Indonesian CPI rising over 3 months) while SBN yields rise");
        return preset("ptj", "Paul Tudor Jones", "Defence first: the 200-day rule, then the macro regime - inflation, "
                + "bonds, the dollar, volatility and credit", stance, label, score, n, thesis, points, buy, sell);
    }

    /** A vote of a preset's reading: +1 for the country's equities, -1 against, 0 neither. */
    record Vote(String signal, int vote, String text) {
    }

    /** How far above its 200-day average the index is stretched: the late stage of a boom (Soros). */
    static final double STRETCHED_PCT = 15;

    /**
     * The reflexive loop of the Soros preset, from the given signals: foreign money in IDX stocks and in SBN, the
     * rupiah, Bank Indonesia's reaction, its reserves, the dollar and the country premium each vote +1 (the loop feeds
     * a boom) / -1 (it feeds a bust).
     */
    List<Vote> sorosLoop(Map<String, Object> from) {
        List<Vote> votes = new ArrayList<>();
        Map<String, Object> flows = map(from, "foreignFlows");
        String idx = str(flows, "idxState");
        Double idxSum = dbl(flows, "idxNetBuy20dBn");
        if (idx != null && idxSum != null) {
            votes.add(new Vote("idxFlows", idx.equals("INFLOW") ? 1 : idx.equals("OUTFLOW") ? -1 : 0, "Foreigners net "
                    + (idxSum >= 0 ? "buyers" : "sellers") + " of Rp " + f(Math.abs(idxSum), 0) + " bn on IDX over 20 sessions"));
        }
        String sbnState = str(flows, "sbnState");
        if (sbnState != null) {
            votes.add(new Vote("sbnFlows", sbnState.equals("INFLOW") ? 1 : sbnState.equals("OUTFLOW") ? -1 : 0,
                    "Foreign holdings of SBN " + sp(dbl(flows, "sbnForeignChange1mTn"), 1) + " tn in a month"));
        }
        Map<String, Object> dollar = map(from, "dollar");
        String rupiah = str(dollar, "rupiahTrend");
        if (rupiah != null) {
            votes.add(new Vote("rupiah", rupiah.equals("STRENGTHENING") ? 1 : rupiah.equals("WEAKENING") ? -1 : 0,
                    "Rupiah " + lower(rupiah) + " (USD/IDR " + sp(dbl(dollar, "usdIdrChange3mPct"), 1) + "% in 3 months)"));
        }
        Map<String, Object> bi = map(from, "biPolicy");
        String biState = str(bi, "state");
        if (biState != null) {
            votes.add(new Vote("biPolicy", biState.equals("CUTTING") ? 1 : biState.equals("HIKING") ? -1 : 0,
                    "BI " + (biState.equals("CUTTING") ? "cutting" : biState.equals("HIKING") ? "hiking" : "on hold") + " ("
                            + f(dbl(bi, "value"), 2) + "%, " + sp(dbl(bi, "change6mPoints"), 2) + " points in 6 months)"));
        }
        Double reserves = dbl(map(from, "idLiquidity"), "fxReservesChange3mPct");
        if (reserves != null) {
            votes.add(new Vote("reserves", reserves >= 1 ? 1 : reserves <= -2 ? -1 : 0, "BI reserves " + sp(reserves, 1)
                    + "% in 3 months" + (reserves <= -2 ? ": spent to hold the rupiah" : "")));
        }
        String dxy = str(dollar, "dxyTrend");
        if (dxy != null) {
            votes.add(new Vote("dollar", dxy.equals("WEAKENING") ? 1 : dxy.equals("STRENGTHENING") ? -1 : 0,
                    "Dollar " + lower(dxy) + " (DXY " + sp(dbl(dollar, "dxyChange3mPct"), 1) + "% in 3 months)"));
        }
        Map<String, Object> sbn = map(from, "sbn10y");
        Double premiumChange = dbl(sbn, "spreadChange3mPoints");
        if (premiumChange != null) {
            votes.add(new Vote("countryPremium", premiumChange <= -0.25 ? 1 : premiumChange >= 0.25 ? -1 : 0,
                    "Country premium (SBN 10Y over the US 10Y) " + f(dbl(sbn, "spreadVsUs10y"), 2) + " points, "
                            + sp(premiumChange, 2) + " in 3 months: " + (premiumChange <= -0.25 ? "narrowing"
                            : premiumChange >= 0.25 ? "widening" : "steady")));
        }
        return votes;
    }

    private static int total(List<Vote> votes) {
        return votes.stream().mapToInt(Vote::vote).sum();
    }

    /** VIRTUOUS at +2, VICIOUS at -2, else NEUTRAL; null with fewer than 3 votes. */
    private static String loopState(List<Vote> votes) {
        if (votes.size() < 3) {
            return null;
        }
        int score = total(votes);
        return score >= 2 ? "VIRTUOUS" : score <= -2 ? "VICIOUS" : "NEUTRAL";
    }

    /** "Against: Rupiah weakening (...); BI hiking (...). For: Dollar weakening (...). " */
    private static String votesText(List<Vote> votes) {
        List<String> against = votes.stream().filter(v -> v.vote() < 0).map(Vote::text).toList();
        List<String> forList = votes.stream().filter(v -> v.vote() > 0).map(Vote::text).toList();
        StringBuilder t = new StringBuilder();
        if (!against.isEmpty()) {
            t.append("Against: ").append(String.join("; ", against)).append(". ");
        }
        if (!forList.isEmpty()) {
            t.append("For: ").append(String.join("; ", forList)).append(". ");
        }
        return t.toString();
    }

    private static Map<String, Object> point(Vote v) {
        return point(v.vote() > 0 ? POSITIVE : v.vote() < 0 ? NEGATIVE : NEUTRAL, v.text());
    }

    /**
     * George Soros: reflexivity. Prices change the fundamentals they are meant to reflect; in Indonesia the loop runs
     * through foreign money, the rupiah and Bank Indonesia. A virtuous loop with the index in an uptrend is a boom to
     * ride; a loop that weakens while the index is stretched is a boom running out of fuel; a vicious loop is a bust.
     */
    private Map<String, Object> soros() {
        List<Vote> loop = sorosLoop(signals);
        String state = loopState(loop);
        int n = loop.size();
        Integer score = state == null ? null : total(loop);
        List<Vote> loopThen = sorosLoop(signalsThen);
        Integer then = loopState(loopThen) == null ? null : total(loopThen);
        boolean improving = score != null && then != null && score - then >= 2;
        boolean fading = score != null && then != null && then - score >= 2;
        Boolean above = read.aboveSma200();
        Double vs = read.vsSma200Pct();
        boolean stretched = vs != null && vs >= STRETCHED_PCT;
        String stance;
        String label;
        if (state == null) {
            stance = "WAIT";
            label = "Wait: too few signals to read the loop of foreign money, the rupiah and BI";
        } else if (state.equals("VIRTUOUS")) {
            if (fading) {
                stance = "WAIT";
                label = "Wait: the loop is still virtuous but weakening - the boom is losing its fuel";
            } else if (Boolean.TRUE.equals(above)) {
                stance = "BUY";
                label = "Buy: a self-reinforcing boom - foreign money, the rupiah and BI feed the trend";
            } else if (Boolean.FALSE.equals(above)) {
                stance = "ACCUMULATE";
                label = "Accumulate: the loop turned virtuous before the index - the bust's premise is failing";
            } else {
                stance = "ACCUMULATE";
                label = "Accumulate: the loop is virtuous (no 200-day average of " + indexCode + " to confirm)";
            }
        } else if (state.equals("NEUTRAL")) {
            if (improving) {
                stance = "ACCUMULATE";
                label = "Accumulate: the loop is turning virtuous";
            } else if (fading && stretched) {
                stance = "AVOID";
                label = "Avoid: " + indexCode + " is stretched above its 200-day average while the flows turn - a boom "
                        + "running out of fuel";
            } else {
                stance = "WAIT";
                label = "Wait: no reflexive loop - foreign money, the rupiah and BI give no direction";
            }
        } else if (improving) {
            stance = "WAIT";
            label = "Wait: still a bust, but turning - watch for foreign money to come back";
        } else if (Boolean.TRUE.equals(above)) {
            stance = "AVOID";
            label = "Avoid: money is leaving while " + indexCode + " still holds its 200-day average - the trend's "
                    + "premise is failing";
        } else {
            stance = "AVOID";
            label = "Avoid: a self-reinforcing bust - outflows, a weaker rupiah and a defensive BI feed each other";
        }
        List<Map<String, Object>> points = new ArrayList<>();
        loop.forEach(v -> points.add(point(v)));
        if (read.sma200() != null) {
            points.add(point(Boolean.TRUE.equals(above) ? (stretched ? NEUTRAL : POSITIVE) : NEGATIVE, trendText()
                    + (stretched ? ": stretched, the late stage of a boom" : "") + " (the trend the loop feeds or undoes)"));
        }
        String loopText = state == null ? "unknown" : lower(state) + " (" + sp(score.doubleValue(), 0) + " from " + n
                + " signals" + (then == null ? "" : "; " + sp(then.doubleValue(), 0) + " three months ago") + ")";
        String thesis = "Soros reads markets through reflexivity: prices change the fundamentals they are meant to reflect. "
                + "In Indonesia the loop runs through foreign money, the rupiah and Bank Indonesia - inflows firm the rupiah, a "
                + "firm rupiah lets BI cut, cuts lift stocks and draw more money in, and the same loop runs in reverse, "
                + "faster. The loop is " + loopText + ". " + votesText(loop) + trendText() + ". " + label + '.';
        String now = state == null ? "unknown" : lower(state) + " (" + sp(score.doubleValue(), 0) + " of " + n + ")";
        List<String> buy = new ArrayList<>();
        List<String> sell = new ArrayList<>();
        buy.add("The loop turns virtuous (+2 or more; now " + now + "): foreigners buy IDX stocks and SBN, the rupiah "
                + "firms, BI cuts, reserves grow");
        buy.add("The loop improves by 2 points or more within 3 months" + (score == null || then == null ? ""
                : " (now " + sp((double) (score - then), 0) + ")") + " while " + indexCode + " is below its 200-day "
                + "average: the bust's premise is failing - buy early");
        buy.add("The country premium narrows: SBN 10Y over the US 10Y down 0.25 points or more in 3 months");
        sell.add("The loop turns vicious (-2 or lower; now " + now + "): foreign selling, a weaker rupiah, BI hiking to "
                + "defend it");
        sell.add("The loop weakens by 2 points or more within 3 months while " + indexCode + " is stretched "
                + f(STRETCHED_PCT, 0) + "% or more above its 200-day average" + (vs == null ? "" : " (now " + sp(vs, 1) + "%)")
                + ": the boom is running out of fuel");
        sell.add("BI spends reserves (down 2% or more in 3 months) to hold the rupiah: the defence that comes before a "
                + "break");
        return preset("soros", "George Soros", "Reflexivity: ride a self-reinforcing boom, get out when the loop of "
                + "foreign money, the rupiah and BI turns", stance, label, score == null ? null : score.doubleValue(), n,
                thesis, points, buy, sell);
    }

    /** Ray Dalio's growth / inflation quadrant and its text. */
    record Quadrant(String growth, String inflation, String quadrant, List<Vote> growthParts, List<Vote> inflationParts) {
    }

    /**
     * Growth from the proxies stored (no GDP series): China's PMI, the US curve and Indonesian M2, each +1 / -1 / 0;
     * EXPANDING at a mean of +1/3 or more, SLOWING at -1/3 or less. Inflation from the 3-month trend of Indonesian and US
     * CPI and Indonesian CPI against BI's band: RISING at a mean of +1/2 or more, FALLING at -1/2 or less.
     */
    Quadrant quadrant() {
        List<Vote> growth = new ArrayList<>();
        Map<String, Object> china = signal("china");
        if (str(china, "state") != null) {
            boolean expansion = "EXPANSION".equals(str(china, "state"));
            growth.add(new Vote("china", expansion ? 1 : -1, "China PMI " + f(dbl(china, "pmi"), 1) + " ("
                    + (expansion ? "expansion" : "contraction") + ")"));
        }
        Map<String, Object> curve = signal("usCurve");
        if (str(curve, "state") != null) {
            String s = str(curve, "state");
            growth.add(new Vote("usCurve", s.equals("NORMAL") ? 1 : s.equals("INVERTED") ? -1 : 0, "US curve "
                    + sp(dbl(curve, "value"), 2) + " points (" + lower(s) + ")"));
        }
        Double m2 = dbl(signal("idLiquidity"), "m2YoyPct");
        if (m2 != null) {
            growth.add(new Vote("idM2", m2 >= 8 ? 1 : m2 < 5 ? -1 : 0, "Indonesian M2 " + sp(m2, 1) + "% YoY"));
        }
        List<Vote> inflation = new ArrayList<>();
        Map<String, Object> inf = signal("inflation");
        Double cpi = dbl(inf, "idCpiYoy");
        if (cpi != null) {
            inflation.add(new Vote("idCpiLevel", cpi > BI_TARGET_HIGH ? 1 : cpi < BI_TARGET_LOW ? -1 : 0, "Indonesian CPI "
                    + f(cpi, 2) + "% " + (cpi > BI_TARGET_HIGH ? "above" : cpi < BI_TARGET_LOW ? "below" : "inside")
                    + " BI's " + f(BI_TARGET_LOW, 1) + " - " + f(BI_TARGET_HIGH, 1) + "% band"));
        }
        for (String[] t : List.of(new String[] {"idTrend", "Indonesian CPI"}, new String[] {"usTrend", "US CPI"})) {
            String trend = str(inf, t[0]);
            if (trend != null) {
                inflation.add(new Vote(t[0], trend.equals("RISING") ? 1 : trend.equals("FALLING") ? -1 : 0,
                        t[1] + " " + lower(trend) + " over 3 months"));
            }
        }
        // the inflation votes say up (+1) or down (-1), not good or bad for equities
        Double g = growth.isEmpty() ? null : growth.stream().mapToInt(Vote::vote).average().orElse(0);
        Double i = inflation.isEmpty() ? null : inflation.stream().mapToInt(Vote::vote).average().orElse(0);
        String gs = g == null ? null : g >= 1.0 / 3 - 1e-9 ? "EXPANDING" : g <= -1.0 / 3 + 1e-9 ? "SLOWING" : "STEADY";
        String is = i == null ? null : i >= 0.5 ? "RISING" : i <= -0.5 ? "FALLING" : "STABLE";
        String q = gs == null || is == null ? null : switch (gs) {
            case "EXPANDING" -> is.equals("RISING") ? "REFLATION" : "GOLDILOCKS";
            case "SLOWING" -> is.equals("RISING") ? "STAGFLATION" : "DISINFLATIONARY_SLOWDOWN";
            default -> "MIXED";
        };
        return new Quadrant(gs, is, q, growth, inflation);
    }

    static String quadrantText(String quadrant) {
        if (quadrant == null) {
            return "no quadrant yet (too few growth or inflation signals)";
        }
        return switch (quadrant) {
            case "GOLDILOCKS" -> "Goldilocks (growth up, inflation contained) - the best quadrant for equities";
            case "REFLATION" -> "reflation (growth and inflation up) - equities and commodities over bonds";
            case "STAGFLATION" -> "stagflation (growth down, inflation up) - the worst quadrant for equities";
            case "DISINFLATIONARY_SLOWDOWN" -> "a disinflationary slowdown (growth and inflation down) - bonds over equities";
            default -> "no clear quadrant (growth steady)";
        };
    }

    /**
     * Ray Dalio: the economic machine. Growth and inflation against BI's band place the economy in a quadrant; the
     * cost of money (Indonesian and US real rates) and credit say whether the debt cycle helps or squeezes. Votes:
     * growth, inflation (falling is good for equities), the Indonesian real rate, the US real yield's 3-month change,
     * credit spreads. Without a clear case for equities: balanced (All Weather), no tilt.
     */
    private Map<String, Object> dalio() {
        Quadrant q = quadrant();
        List<Vote> votes = new ArrayList<>();
        if (q.growth() != null) {
            votes.add(new Vote("growth", q.growth().equals("EXPANDING") ? 1 : q.growth().equals("SLOWING") ? -1 : 0,
                    "Growth " + lower(q.growth()) + ": " + String.join(", ", q.growthParts().stream().map(Vote::text).toList())));
        }
        if (q.inflation() != null) {
            votes.add(new Vote("inflation", q.inflation().equals("FALLING") ? 1 : q.inflation().equals("RISING") ? -1 : 0,
                    "Inflation " + lower(q.inflation()) + ": " + String.join(", ",
                            q.inflationParts().stream().map(Vote::text).toList())));
        }
        Map<String, Object> real = signal("idRealRate");
        Double realRate = dbl(real, "value");
        if (str(real, "state") != null) {
            String s = str(real, "state");
            votes.add(new Vote("idRealRate", s.equals("ACCOMMODATIVE") ? 1 : s.equals("RESTRICTIVE") ? -1 : 0,
                    "Indonesian real rate " + f(realRate, 2) + "% (" + lower(s) + ")"));
        }
        Map<String, Object> inf = signal("inflation");
        Double usReal = dbl(inf, "usRealYield10y");
        Double usRealChange = dbl(inf, "usRealYieldChange3mPoints");
        if (usRealChange != null) {
            votes.add(new Vote("usRealYield", usRealChange <= -0.25 ? 1 : usRealChange >= 0.25 ? -1 : 0,
                    "US 10Y real yield " + f(usReal, 2) + "% (" + sp(usRealChange, 2) + " points in 3 months): "
                            + (usRealChange <= -0.25 ? "money getting cheaper" : usRealChange >= 0.25 ? "money getting dearer"
                            : "steady")));
        }
        Map<String, Object> credit = signal("creditStress");
        if (str(credit, "state") != null) {
            String s = str(credit, "state");
            votes.add(new Vote("credit", s.equals("CALM") ? 1 : s.equals("STRESSED") ? -1 : 0, "US high-yield spread "
                    + f(dbl(credit, "value"), 2) + "% (" + lower(s) + ")"));
        }
        int n = votes.size();
        Integer score = n < 3 ? null : total(votes);
        Boolean above = read.aboveSma200();
        String quad = q.quadrant();
        String stance;
        String label;
        if (score == null || quad == null) {
            stance = "WAIT";
            label = "Wait: too few growth and inflation signals to place Indonesia in a quadrant";
        } else if (quad.equals("STAGFLATION")) {
            stance = score <= -2 ? "AVOID" : "WAIT";
            label = score <= -2 ? "Avoid: stagflation with tight money - the worst quadrant for equities; hold the "
                    + "balanced mix" : "Wait: growth slowing while inflation rises - no tilt toward equities";
        } else if (score >= 2 && quad.equals("DISINFLATIONARY_SLOWDOWN")) {
            stance = "ACCUMULATE";
            label = "Accumulate: growth is slowing, but falling inflation and cheaper money let policy ease - equities "
                    + "follow once growth turns";
        } else if (score >= 2) {
            stance = Boolean.TRUE.equals(above) ? "BUY" : "ACCUMULATE";
            label = Boolean.TRUE.equals(above) ? "Buy: " + quadrantName(quad) + " with cheap money and calm credit - the "
                    + "environment equities do best in"
                    : "Accumulate: the environment favours equities ahead of " + indexCode + "'s trend"
                    + (above == null ? " (no 200-day average to confirm)" : " (still below its 200-day average)");
        } else if (score <= -2) {
            stance = "AVOID";
            label = "Avoid: " + quadrantName(quad) + " with dear money or stressed credit - underweight equities against "
                    + "the balanced mix";
        } else {
            stance = "WAIT";
            label = "Wait: a mixed environment - stay balanced (All Weather), no tilt toward equities";
        }
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(point(quad == null ? NEUTRAL : switch (quad) {
            case "GOLDILOCKS", "REFLATION" -> POSITIVE;
            case "STAGFLATION", "DISINFLATIONARY_SLOWDOWN" -> NEGATIVE;
            default -> NEUTRAL;
        }, "Quadrant: " + quadrantText(quad)));
        votes.forEach(v -> points.add(point(v)));
        if (read.sma200() != null) {
            points.add(point(Boolean.TRUE.equals(above) ? POSITIVE : NEGATIVE, trendText() + " (confirmation, not the signal)"));
        }
        String thesis = "Dalio reads the economic machine: growth and inflation decide which assets do well, and the cost of "
                + "money - real interest rates - and credit decide whether the debt cycle helps or squeezes. Indonesia is "
                + "in " + quadrantText(quad) + ". The machine scores " + (score == null ? "unknown" : sp(score.doubleValue(), 0)
                + " from " + n + " signals") + " (growth from China's PMI, the US curve and Indonesian M2: no GDP series "
                + "is stored). " + votesText(votes) + label + '.';
        List<String> buy = new ArrayList<>();
        List<String> sell = new ArrayList<>();
        buy.add("Growth turns up while inflation stays contained (Goldilocks; now " + quadrantName(quad) + ")");
        buy.add("Money gets cheaper: the US 10Y real yield down 0.25 points or more in 3 months, the Indonesian real rate "
                + "not restrictive" + (realRate == null ? "" : " (now " + f(realRate, 2) + "%)"));
        buy.add("The machine scores +2 or more (now " + (score == null ? "unknown" : sp(score.doubleValue(), 0) + " of " + n)
                + ") with " + indexCode + " above its 200-day average");
        sell.add("Stagflation: growth slowing while inflation rises (Indonesian or US CPI rising over 3 months)");
        sell.add("Money gets dearer into slowing growth: real rates up as central banks tighten - the squeeze of the "
                + "short-term debt cycle");
        sell.add("Credit stress: the US high-yield spread above its 80th percentile of the year - the debt cycle turning");
        return preset("dalio", "Ray Dalio", "The economic machine: growth and inflation quadrants, real rates and credit - "
                + "balanced (All Weather) unless the environment clearly favours equities", stance, label,
                score == null ? null : score.doubleValue(), n, thesis, points, buy, sell);
    }

    /** "Goldilocks", "stagflation", ... ("unknown" without a quadrant) */
    private static String quadrantName(String quadrant) {
        return quadrant == null ? "unknown" : quadrant.equals("GOLDILOCKS") ? "Goldilocks" : lower(quadrant);
    }

    private Map<String, Object> preset(String id, String name, String style, String stance, String label, Double score,
                                       int max, String thesis, List<Map<String, Object>> points, List<String> buyWhen,
                                       List<String> sellWhen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preset", id);
        m.put("name", name);
        m.put("style", style);
        m.put("stance", stance);
        m.put("stanceLabel", label);
        m.put("score", score);
        m.put("maxScore", max);
        m.put("thesis", thesis);
        m.put("points", points);
        m.put("buyWhen", buyWhen);
        m.put("sellWhen", sellWhen);
        return m;
    }

    /** The votes of a regime as evidence points. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> components(String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> regime = map(regimes, key);
        if (regime == null) {
            return out;
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) regime.getOrDefault("components", List.of())) {
            Double vote = dbl(c, "vote");
            out.add(point(vote == null || vote == 0 ? NEUTRAL : vote > 0 ? POSITIVE : NEGATIVE, str(c, "text")));
        }
        return out;
    }

    /** "Against: Fed hiking rates (...), BI hiking (...). For: Rupiah strengthening (...). " */
    @SuppressWarnings("unchecked")
    private String drivers(String key) {
        Map<String, Object> regime = map(regimes, key);
        if (regime == null) {
            return "";
        }
        List<String> forList = new ArrayList<>();
        List<String> against = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) regime.getOrDefault("components", List.of())) {
            Double vote = dbl(c, "vote");
            if (vote != null && vote > 0) {
                forList.add(str(c, "text"));
            } else if (vote != null && vote < 0) {
                against.add(str(c, "text"));
            }
        }
        StringBuilder t = new StringBuilder();
        if (!against.isEmpty()) {
            t.append("Against: ").append(String.join("; ", against)).append(". ");
        }
        if (!forList.isEmpty()) {
            t.append("For: ").append(String.join("; ", forList)).append(". ");
        }
        return t.toString();
    }

    /** "tightening (-3 of 6)" */
    private String regimeText(String key) {
        String state = regimeState(key);
        Double score = regimeScore(key);
        return state == null ? "unknown" : lower(state).replace('_', ' ') + " (" + sp(score, 0) + " of "
                + regimeSignals(key) + ")";
    }

    // ------------------------------------------------------------------ conclusion

    private Map<String, Object> conclusion() {
        List<Part> parts = parts();
        double weighted = 0;
        int weights = 0;
        for (Part p : parts) {
            if (p.value() != null) {
                weighted += p.weight() * p.value();
                weights += p.weight();
            }
        }
        Integer score = weights == 0 ? null : (int) Math.round(50 + 50 * weighted / weights);
        Boolean above = read.aboveSma200();
        String verdict = score == null ? null : score >= INVESTABLE ? "INVESTABLE" : score >= NOT_INVESTABLE ? "SELECTIVE"
                : "NOT_INVESTABLE";
        String action;
        String reason;
        if (score == null) {
            action = "WAIT";
            reason = "too little macro data to score";
        } else if (score >= INVESTABLE) {
            action = Boolean.TRUE.equals(above) ? "BUY" : "ACCUMULATE";
            reason = Boolean.TRUE.equals(above) ? "the macro conditions are supportive and " + indexCode + " is in an uptrend"
                    : "the macro conditions are supportive, but " + indexCode + " is still below its 200-day average: build "
                    + "the position in steps";
        } else if (score >= NOT_INVESTABLE) {
            boolean tightening = "TIGHTENING".equals(regimeState("liquidity"));
            action = Boolean.TRUE.equals(above) && !tightening ? "ACCUMULATE" : "WAIT";
            reason = action.equals("ACCUMULATE") ? "mixed macro conditions with " + indexCode + " in an uptrend: add only "
                    + "on dips" : "mixed macro conditions" + (Boolean.TRUE.equals(above) ? " and tightening liquidity"
                    : " and " + indexCode + " below its 200-day average") + ": hold, no new money";
        } else {
            action = Boolean.FALSE.equals(above) ? "SELL" : "WAIT";
            reason = action.equals("SELL") ? "the macro conditions are against equities and " + indexCode
                    + " is in a downtrend: reduce exposure"
                    : "the macro conditions are against equities, but " + indexCode + " still holds its 200-day average: "
                    + "no new money, tighten stops";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", score);
        m.put("verdict", verdict);
        m.put("verdictLabel", verdict == null ? "Unknown" : switch (verdict) {
            case "INVESTABLE" -> "Investable";
            case "SELECTIVE" -> "Selective";
            default -> "Not investable now";
        });
        m.put("action", action);
        m.put("actionLabel", switch (action) {
            case "BUY" -> "Buy";
            case "ACCUMULATE" -> "Accumulate";
            case "SELL" -> "Sell / reduce";
            default -> "Wait";
        });
        m.put("reason", reason);
        List<Map<String, Object>> partList = new ArrayList<>();
        for (Part p : parts) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("key", p.key());
            pm.put("name", p.name());
            pm.put("weight", p.weight());
            pm.put("value", r(p.value(), 2));
            pm.put("points", p.value() == null || weights == 0 ? null : r(50.0 * p.weight() * p.value() / weights, 1));
            pm.put("text", p.text());
            partList.add(pm);
        }
        m.put("components", partList);
        m.put("summary", summaryText(score, verdict, action, reason, parts));
        String sma = read.sma200() == null ? "its 200-day average" : "its 200-day average (" + f(read.sma200(), 0) + ")";
        m.put("buyWhen", List.of(
                condition("Investability score " + INVESTABLE + " or more (now " + (score == null ? "unknown" : score) + ")",
                        score != null && score >= INVESTABLE),
                condition(indexCode + " closes above " + sma + (read.close() == null ? "" : " (now " + f(read.close(), 0)
                        + ")"), Boolean.TRUE.equals(above))));
        m.put("accumulateWhen", List.of(
                condition("Score " + INVESTABLE + " or more while " + indexCode + " is still below " + sma
                        + ": build the position in steps", score != null && score >= INVESTABLE && Boolean.FALSE.equals(above)),
                condition("Score " + NOT_INVESTABLE + " - " + (INVESTABLE - 1) + " with " + indexCode + " above " + sma
                        + " and liquidity not tightening: add on dips", score != null && score >= NOT_INVESTABLE
                        && score < INVESTABLE && Boolean.TRUE.equals(above) && !"TIGHTENING".equals(regimeState("liquidity")))));
        m.put("sellWhen", List.of(
                condition("Investability score below " + NOT_INVESTABLE + " (now " + (score == null ? "unknown" : score) + ")",
                        score != null && score < NOT_INVESTABLE),
                condition(indexCode + " closes below " + sma, Boolean.FALSE.equals(above))));
        m.put("watch", watch(parts));
        return m;
    }

    private static Map<String, Object> condition(String text, boolean met) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("met", met);
        return m;
    }

    /** The six weighted parts of the investability score. */
    @SuppressWarnings("unchecked")
    List<Part> parts() {
        List<Part> parts = new ArrayList<>();
        // 1. global liquidity (Druckenmiller): the regime's score per signal
        String ls = regimeState("liquidity");
        Double lScore = regimeScore("liquidity");
        int ln = regimeSignals("liquidity");
        parts.add(new Part("liquidity", "Liquidity regime (Druckenmiller)", 25,
                ls == null || ln == 0 ? null : clamp(lScore / ln),
                ls == null ? "too few signals" : "liquidity " + regimeText("liquidity")));
        // 2. risk regime (Tudor Jones)
        String rs = regimeState("risk");
        Double rScore = regimeScore("risk");
        int rn = regimeSignals("risk");
        parts.add(new Part("risk", "Risk regime (Tudor Jones)", 20, rs == null || rn == 0 ? null : clamp(rScore / rn),
                rs == null ? "too few signals" : "risk " + regimeText("risk")));
        // 3. Indonesian monetary policy: BI's direction and the real rate
        Map<String, Object> bi = signal("biPolicy");
        Map<String, Object> real = signal("idRealRate");
        List<Double> mon = new ArrayList<>();
        List<String> monText = new ArrayList<>();
        if (bi != null && str(bi, "state") != null) {
            String s = str(bi, "state");
            mon.add(s.equals("CUTTING") ? 1.0 : s.equals("HIKING") ? -1.0 : 0.0);
            monText.add("BI " + (s.equals("CUTTING") ? "cutting" : s.equals("HIKING") ? "hiking" : "on hold") + " ("
                    + f(dbl(bi, "value"), 2) + "%)");
        }
        if (real != null) {
            String s = str(real, "state");
            mon.add(s.equals("ACCOMMODATIVE") ? 1.0 : s.equals("RESTRICTIVE") ? -0.5 : 0.0);
            monText.add("real rate " + f(dbl(real, "value"), 2) + "% (" + lower(s) + ")");
        }
        parts.add(new Part("monetary", "Indonesian monetary policy", 15, mean(mon),
                monText.isEmpty() ? "no BI data" : String.join(", ", monText)));
        // 4. inflation against BI's target band
        Map<String, Object> inf = signal("inflation");
        Double cpi = dbl(inf, "idCpiYoy");
        Double infValue = null;
        String infText = "no Indonesian CPI";
        if (cpi != null) {
            String trend = str(inf, "idTrend");
            infValue = cpi > BI_TARGET_HIGH || "RISING".equals(trend) ? -1.0 : cpi < BI_TARGET_LOW ? 0.0 : 1.0;
            infText = "CPI " + f(cpi, 2) + "% " + (cpi > BI_TARGET_HIGH ? "above" : cpi < BI_TARGET_LOW ? "below" : "inside")
                    + " the " + f(BI_TARGET_LOW, 1) + " - " + f(BI_TARGET_HIGH, 1) + "% band" + (trend == null ? ""
                    : ", " + lower(trend));
        }
        parts.add(new Part("inflation", "Inflation vs BI's target", 10, infValue, infText));
        // 5. the rupiah and the external buffers: the rupiah, BI reserves, SBN yields
        Map<String, Object> dollar = signal("dollar");
        Map<String, Object> liq = signal("idLiquidity");
        Map<String, Object> sbn = signal("sbn10y");
        List<Double> ext = new ArrayList<>();
        List<String> extText = new ArrayList<>();
        String rupiah = str(dollar, "rupiahTrend");
        if (rupiah != null) {
            ext.add(rupiah.equals("STRENGTHENING") ? 1.0 : rupiah.equals("WEAKENING") ? -1.0 : 0.0);
            extText.add("rupiah " + lower(rupiah) + " (USD/IDR " + sp(dbl(dollar, "usdIdrChange3mPct"), 1) + "% in 3 months)");
        }
        Double reserves = dbl(liq, "fxReservesChange3mPct");
        if (reserves != null) {
            ext.add(reserves >= 1 ? 1.0 : reserves <= -2 ? -1.0 : 0.0);
            extText.add("reserves " + sp(reserves, 1) + "% in 3 months");
        }
        String sbnTrend = str(sbn, "trend");
        if (sbnTrend != null) {
            ext.add(sbnTrend.equals("FALLING") ? 1.0 : sbnTrend.equals("RISING") ? -1.0 : 0.0);
            extText.add("SBN 10Y " + lower(sbnTrend));
        }
        parts.add(new Part("external", "Rupiah and external buffers", 15, mean(ext),
                extText.isEmpty() ? "no data" : String.join(", ", extText)));
        // 6. the index's trend: above / below its 200-day average and the average's slope
        List<Double> tr = new ArrayList<>();
        if (read.aboveSma200() != null) {
            tr.add(read.aboveSma200() ? 1.0 : -1.0);
        }
        if (read.sma200Slope() != null) {
            tr.add(read.sma200Slope().equals("RISING") ? 1.0 : read.sma200Slope().equals("FALLING") ? -1.0 : 0.0);
        }
        parts.add(new Part("trend", "Index trend (" + indexCode + ")", 15, mean(tr),
                read.sma200() == null ? "no 200-day average yet" : trendText()
                        + (read.sma200Slope() == null ? "" : ", the average " + lower(read.sma200Slope()))));
        return parts;
    }

    private String summaryText(Integer score, String verdict, String action, String reason, List<Part> parts) {
        if (score == null) {
            return "Too little macro data to score Indonesia: run the macro ingestion (Ingestion > Macro).";
        }
        List<Part> known = parts.stream().filter(p -> p.value() != null).toList();
        List<String> good = known.stream().filter(p -> p.value() > 0).sorted((a, b) -> Double.compare(
                b.weight() * b.value(), a.weight() * a.value())).limit(2).map(p -> p.name() + ": " + p.text()).toList();
        List<String> bad = known.stream().filter(p -> p.value() < 0).sorted((a, b) -> Double.compare(
                a.weight() * a.value(), b.weight() * b.value())).limit(2).map(p -> p.name() + ": " + p.text()).toList();
        StringBuilder t = new StringBuilder("Indonesia scores ").append(score).append(" of 100: ")
                .append(switch (verdict) {
                    case "INVESTABLE" -> "investable";
                    case "SELECTIVE" -> "selective - mixed conditions";
                    default -> "not investable now";
                }).append(". ");
        if (!bad.isEmpty()) {
            t.append("Against: ").append(String.join("; ", bad)).append(". ");
        }
        if (!good.isEmpty()) {
            t.append("For: ").append(String.join("; ", good)).append(". ");
        }
        t.append(switch (action) {
            case "BUY" -> "Buy";
            case "ACCUMULATE" -> "Accumulate";
            case "SELL" -> "Sell / reduce";
            default -> "Wait";
        }).append(": ").append(reason).append('.');
        return t.toString();
    }

    /** What would move the score most: the parts against, the heaviest first. */
    private List<String> watch(List<Part> parts) {
        List<String> out = new ArrayList<>();
        parts.stream().filter(p -> p.value() != null && p.value() < 0)
                .sorted((a, b) -> Double.compare(a.weight() * a.value(), b.weight() * b.value()))
                .forEach(p -> out.add(p.name() + ": " + p.text() + switch (p.key()) {
                    case "liquidity" -> " - watch for the Fed or BI to stop hiking and credit spreads to narrow";
                    case "risk" -> " - watch for volatility and credit spreads to calm";
                    case "monetary" -> " - watch for BI's first cut";
                    case "inflation" -> " - watch for CPI back inside BI's band and falling";
                    case "external" -> " - watch for a steady rupiah and falling SBN yields";
                    case "trend" -> " - watch for " + indexCode + " to close back above its 200-day average";
                    default -> "";
                }));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> signal(String key) {
        return signals == null ? null : (Map<String, Object>) signals.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return m == null ? null : (Map<String, Object>) m.get(key);
    }

    private static Map<String, Object> point(String tone, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tone", tone);
        m.put("text", text);
        return m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    private static Double dbl(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    private static Double r(Double v, int digits) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return null;
        }
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    /** 7033.12 -> "7,033" (digits 0); 2.5 -> "2.50" (digits 2) */
    static String f(Double v, int digits) {
        if (v == null) {
            return "n/a";
        }
        return String.format(Locale.US, "%,." + digits + "f", v);
    }

    /** Signed: "+0.24", "-1.4" */
    static String sp(Double v, int digits) {
        if (v == null) {
            return "n/a";
        }
        return (v > 0 ? "+" : "") + f(v, digits);
    }

    /** 97.6 -> "98th" */
    static String ordinal(Double v) {
        if (v == null) {
            return "n/a";
        }
        long n = Math.round(v);
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch ((int) (n % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    /** "2026-08-01" -> "August 2026" */
    static String month(String date) {
        if (date == null) {
            return "n/a";
        }
        LocalDate d = LocalDate.parse(date);
        return d.getMonth().getDisplayName(TextStyle.FULL, Locale.US) + " " + d.getYear();
    }

    private static String lower(String v) {
        return v == null ? "unknown" : v.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Epoch seconds of a day (UTC midnight), as the chart APIs send times. */
    private static long epoch(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }
}
