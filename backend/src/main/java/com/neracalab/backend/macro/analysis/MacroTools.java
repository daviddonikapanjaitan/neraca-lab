package com.neracalab.backend.macro.analysis;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * Tools of the macro research agent (Tool Calling Pattern), over the data the analysis read from the database: the
 * macro series (point in time: only observations released by the as-of date), the commodities and currency pairs of
 * the price ingestion and the country's indexes, and - when the country has any - its macro documents in the RAG
 * vector store (only those published by the as-of date). Nothing outside the database; every result gets a reference
 * the brief cites (T1, T2, ... for the series, D1, D2, ... for the document excerpts). History and correlation calls
 * count against {@code max-tool-calls}, document searches against {@code max-document-searches}.
 */
public class MacroTools {

    /** Name of the document search tool (offered only when {@link #hasDocuments()}). */
    static final String DOCUMENT_TOOL = "searchDocuments";
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    /** A tool result as the report lists it. */
    public record Retrieved(String ref, String tool, String arguments, String summary) {
    }

    /**
     * The country's macro documents the research agent may search ({@link MacroDocumentSearch}): those published by the
     * as-of date.
     */
    public interface DocumentSearch {

        /** The documents that can be found, latest publication first. */
        List<RagRepository.MacroDocumentRow> documents();

        /** The chunks closest to the query, closest first. */
        List<RagRepository.MacroHit> search(String query);
    }

    /**
     * What a code is: its name and unit, whether its changes are read in points (rates) or percent (prices), and whether
     * it is a flow of a session (a month is the sum of its observations). A series with a value of zero or less in the
     * window (a net position, a flow) has its changes in points as well: a percent change of it means nothing.
     */
    public record Meta(String name, String unit, boolean rate, boolean flow) {
    }

    private final Map<String, List<Observation>> macro;
    private final Map<String, List<Candle>> prices;
    private final Map<String, Meta> meta;
    private final LocalDate asOf;
    private final MacroAnalysisProperties properties;
    private final DocumentSearch documents;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger refCount = new AtomicInteger();
    private final AtomicInteger documentRefCount = new AtomicInteger();
    private final Set<String> asked = new HashSet<>();
    private final List<Retrieved> retrieved = new ArrayList<>();
    private final Map<Long, String> chunkRefs = new HashMap<>();

    /**
     * @param macro  macro series code -> observations, oldest first
     * @param prices commodity, currency pair and index code -> bars, oldest first
     * @param meta   every code -> its name, unit and kind
     */
    public MacroTools(Map<String, List<Observation>> macro, Map<String, List<Candle>> prices, Map<String, Meta> meta,
                      LocalDate asOf, MacroAnalysisProperties properties) {
        this(macro, prices, meta, asOf, properties, null);
    }

    /** @param documents the country's macro documents; null (or none published by the as-of date): no document search */
    public MacroTools(Map<String, List<Observation>> macro, Map<String, List<Candle>> prices, Map<String, Meta> meta,
                      LocalDate asOf, MacroAnalysisProperties properties, DocumentSearch documents) {
        this.macro = macro;
        this.prices = prices;
        this.meta = meta;
        this.asOf = asOf;
        this.properties = properties;
        this.documents = documents;
    }

    @Tool(description = """
            Monthly history of one stored series: a macro series (e.g. BI_RATE, ID_CPI_YOY, SBN_10Y, US_10Y, DXY, VIX, \
            ID_FX_RESERVES), a commodity (e.g. COAL, NICKEL, PALM_OIL, BRENT, GOLD), a currency pair (e.g. USDIDR, \
            USDCNY, USDMYR, EURUSD, AUDJPY) or an index (IHSG, LQ45). Returns the last value of each month (a flow such as ID_FOREIGN_NET_BUY: the sum of the month), \
            the latest value, the range and the change over the window, and the highest and lowest value of everything \
            stored with their dates (a record only when it is the stored high or low), with a ref (T1, T2, ...).""")
    public Map<String, Object> history(@ToolParam(description = "the code exactly as listed, e.g. COAL") String code,
                                       @ToolParam(description = "months of history, 6 to the limit given") Integer months) {
        String c = code(code);
        int m = months(months);
        guard("history|" + c + "|" + m);
        TreeMap<YearMonth, Double> monthly = monthly(c);
        if (monthly.isEmpty()) {
            throw new IllegalArgumentException("No stored data of " + c + " by " + asOf);
        }
        YearMonth last = monthly.lastKey();
        List<Object[]> points = new ArrayList<>();
        for (Map.Entry<YearMonth, Double> e : monthly.tailMap(last.minusMonths(m - 1L), true).entrySet()) {
            points.add(new Object[] {e.getKey().toString(), round(e.getValue())});
        }
        double first = (double) points.getFirst()[1];
        double latest = (double) points.getLast()[1];
        double min = points.stream().mapToDouble(p -> (double) p[1]).min().orElse(latest);
        double max = points.stream().mapToDouble(p -> (double) p[1]).max().orElse(latest);
        Meta info = meta.get(c);
        boolean inPoints = info.rate() || min <= 0;
        Map<String, Object> out = new LinkedHashMap<>();
        String ref = ref();
        out.put("ref", ref);
        out.put("code", c);
        out.put("name", info.name());
        out.put("unit", info.unit());
        out.put("months", points.size());
        out.put("monthValue", info.flow() ? "sum of the month" : "last value of the month");
        out.put("latest", round(latest));
        out.put("latestMonth", last.toString());
        out.put("min", round(min));
        out.put("max", round(max));
        String change;
        if (inPoints) {
            out.put("changePoints", round(latest - first));
            change = signed(latest - first) + " points";
        } else {
            Double pct = first == 0 ? null : (latest / first - 1) * 100;
            out.put("changePct", pct == null ? null : round(pct));
            change = pct == null ? "n/a" : signed(pct) + "%";
        }
        // the extremes of everything stored (each observation, not month ends), so a record is called one only when it is
        String extremes = "";
        if (!info.flow()) {
            Extreme[] stored = extremes(c);
            if (stored != null) {
                out.put("storedSince", stored[2].date().toString());
                out.put("storedHigh", Map.of("value", round(stored[0].value()), "date", stored[0].date().toString()));
                out.put("storedLow", Map.of("value", round(stored[1].value()), "date", stored[1].date().toString()));
                extremes = "; stored high " + round(stored[0].value()) + " (" + stored[0].date() + "), low "
                        + round(stored[1].value()) + " (" + stored[1].date() + ") since " + stored[2].date();
            }
        }
        out.put("points", points);
        record(ref, "history", c + ", " + m + " months", info.name() + ": " + round(latest) + " in " + last + ", "
                + change + " over " + points.size() + " months (range " + round(min) + " - " + round(max) + ")"
                + (info.flow() ? "; monthly sums" : extremes));
        return out;
    }

    @Tool(description = """
            Correlation of the monthly changes of two stored series over a window (Pearson, -1 .. +1), e.g. IHSG with \
            USDIDR, COAL, AUDJPY or US_10Y, or USDIDR with USDCNY: how the index moved when the other moved. Percent changes for prices and indexes, \
            point changes for rates. Needs 12 months or more; returns r, the months used and a ref.""")
    public Map<String, Object> correlation(@ToolParam(description = "first code, e.g. IHSG") String a,
                                           @ToolParam(description = "second code, e.g. COAL") String b,
                                           @ToolParam(description = "months, 12 to the limit given") Integer months) {
        String x = code(a);
        String y = code(b);
        if (x.equals(y)) {
            throw new IllegalArgumentException("Choose two different codes");
        }
        int m = Math.max(12, months(months));
        guard("correlation|" + x + "|" + y + "|" + m);
        Map<YearMonth, Double> dx = changes(x);
        Map<YearMonth, Double> dy = changes(y);
        YearMonth end = YearMonth.from(asOf);
        List<double[]> pairs = new ArrayList<>();
        for (Map.Entry<YearMonth, Double> e : dx.entrySet()) {
            if (!e.getKey().isAfter(end) && !e.getKey().isBefore(end.minusMonths(m)) && dy.containsKey(e.getKey())) {
                pairs.add(new double[] {e.getValue(), dy.get(e.getKey())});
            }
        }
        if (pairs.size() < 12) {
            throw new IllegalArgumentException("Only " + pairs.size() + " common months of " + x + " and " + y
                    + "; a correlation needs 12");
        }
        Double r = pearson(pairs);
        String ref = ref();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("a", x);
        out.put("b", y);
        out.put("months", pairs.size());
        out.put("r", r == null ? null : Math.round(r * 100) / 100.0);
        String reading = r == null ? "no variation to correlate" : Math.abs(r) < 0.2 ? "little relation"
                : (r > 0 ? "moved together" : "moved opposite") + (Math.abs(r) >= 0.5 ? ", strongly" : ", moderately");
        out.put("reading", reading);
        record(ref, "correlation", x + " / " + y + ", " + m + " months", meta.get(x).name() + " vs " + meta.get(y).name()
                + ": r = " + (r == null ? "n/a" : String.format(Locale.ROOT, "%.2f", r)) + " over " + pairs.size()
                + " months (" + reading + ")");
        return out;
    }

    @Tool(name = DOCUMENT_TOOL, description = """
            Semantic search in the country's stored macro documents published by the as-of date: central bank \
            statements and reports, budget and fiscal documents, IMF / World Bank / ADB reports and the like. Use it for \
            what the series cannot say: the reasons behind a decision, forward guidance, forecasts, stated risks and \
            policy plans. Returns the closest excerpts with a ref (D1, D2, ...), the issuer, title, publication date \
            and pages. Short, specific queries, e.g. 'Bank Indonesia forward guidance rupiah' or 'fiscal deficit \
            financing 2027'.""")
    public List<Map<String, Object>> searchDocuments(@ToolParam(description = "what to look for, in a few words")
                                                     String query) {
        if (!hasDocuments()) {
            throw new IllegalStateException("No macro documents of the country are stored for the as-of date " + asOf);
        }
        String q = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        if (q.isEmpty() || q.length() > 300) {
            throw new IllegalArgumentException("The query must have 1 to 300 characters");
        }
        synchronized (asked) {
            if (!asked.add("documents|" + q.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("This was searched already; use its results");
            }
        }
        if (searches.incrementAndGet() > properties.maxDocumentSearches()) {
            throw new IllegalStateException("Document search limit reached (" + properties.maxDocumentSearches() + ")");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RagRepository.MacroHit hit : documents.search(q)) {
            String excerpt = excerpt(hit.content(), properties.excerptChars());
            String date = hit.publishedAt() == null ? null : hit.publishedAt().atZone(JAKARTA).toLocalDate().toString();
            String pages = pages(hit.pageFrom(), hit.pageTo());
            String ref;
            synchronized (this) {
                ref = chunkRefs.get(hit.chunkId());
                if (ref == null) {
                    ref = "D" + documentRefCount.incrementAndGet();
                    chunkRefs.put(hit.chunkId(), ref);
                    record(ref, DOCUMENT_TOOL, q, source(hit.issuer(), hit.title(), date, pages) + ": "
                            + excerpt(hit.content(), 240));
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ref);
            m.put("issuer", hit.issuer());
            m.put("title", hit.title());
            m.put("type", hit.documentType().label());
            m.put("published", date);
            m.put("pages", pages);
            m.put("excerpt", excerpt);
            out.add(m);
        }
        return out;
    }

    /** Documents can be searched: the country has some published by the as-of date. */
    public boolean hasDocuments() {
        return documents != null && !documents.documents().isEmpty();
    }

    /** The documents the search can find (none without a document search). */
    public List<RagRepository.MacroDocumentRow> documents() {
        return documents == null ? List.of() : documents.documents();
    }

    public int documentSearches() {
        return searches.get();
    }

    /** The codes the tools know, with names and units (told to the agent). */
    public Map<String, Meta> catalog() {
        return meta;
    }

    public int calls() {
        return calls.get();
    }

    public synchronized List<Retrieved> retrieved() {
        return List.copyOf(retrieved);
    }

    /** Refs of every result returned so far (the grounding check of the brief). */
    public synchronized Set<String> refs() {
        Set<String> refs = new HashSet<>();
        retrieved.forEach(r -> refs.add(r.ref()));
        return refs;
    }

    // ------------------------------------------------------------------ data

    /**
     * The last value of each month on or before the as-of date (macro: released by then); of a flow, the sum of the
     * month's observations.
     */
    TreeMap<YearMonth, Double> monthly(String code) {
        TreeMap<YearMonth, Double> out = new TreeMap<>();
        List<Observation> obs = macro.get(code);
        boolean flow = meta.containsKey(code) && meta.get(code).flow();
        if (obs != null) {
            for (Observation o : obs) {
                LocalDate released = o.released() == null ? o.date() : o.released();
                if (!o.date().isAfter(asOf) && !released.isAfter(asOf)) {
                    if (flow) {
                        out.merge(YearMonth.from(o.date()), o.value(), Double::sum);
                    } else {
                        out.put(YearMonth.from(o.date()), o.value());
                    }
                }
            }
            return out;
        }
        for (Candle c : prices.getOrDefault(code, List.of())) {
            if (!c.date().isAfter(asOf)) {
                out.put(YearMonth.from(c.date()), c.close());
            }
        }
        return out;
    }

    /** A value of a series on a day. */
    record Extreme(LocalDate date, double value) {
    }

    /** The highest, the lowest and the first value of everything stored by the as-of date; null when nothing is. */
    Extreme[] extremes(String code) {
        Extreme high = null;
        Extreme low = null;
        Extreme first = null;
        List<Observation> obs = macro.get(code);
        List<Extreme> values = new ArrayList<>();
        if (obs != null) {
            for (Observation o : obs) {
                LocalDate released = o.released() == null ? o.date() : o.released();
                if (!o.date().isAfter(asOf) && !released.isAfter(asOf)) {
                    values.add(new Extreme(o.date(), o.value()));
                }
            }
        } else {
            for (Candle c : prices.getOrDefault(code, List.of())) {
                if (!c.date().isAfter(asOf)) {
                    values.add(new Extreme(c.date(), c.close()));
                }
            }
        }
        for (Extreme e : values) {
            first = first == null ? e : first;
            high = high == null || e.value() > high.value() ? e : high;
            low = low == null || e.value() < low.value() ? e : low;
        }
        return first == null ? null : new Extreme[] {high, low, first};
    }

    /**
     * What a month moved: percent for prices, points for rates and series with values of zero or less; a flow is its
     * monthly sum itself (the month's net buying, not its change).
     */
    private Map<YearMonth, Double> changes(String code) {
        TreeMap<YearMonth, Double> monthly = monthly(code);
        if (meta.get(code).flow()) {
            return monthly;
        }
        boolean rate = meta.get(code).rate() || monthly.values().stream().anyMatch(v -> v <= 0);
        Map<YearMonth, Double> out = new TreeMap<>();
        Map.Entry<YearMonth, Double> previous = null;
        for (Map.Entry<YearMonth, Double> e : monthly.entrySet()) {
            if (previous != null && previous.getKey().plusMonths(1).equals(e.getKey())) {
                double change = rate ? e.getValue() - previous.getValue()
                        : previous.getValue() == 0 ? Double.NaN : (e.getValue() / previous.getValue() - 1) * 100;
                if (Double.isFinite(change)) {
                    out.put(e.getKey(), change);
                }
            }
            previous = e;
        }
        return out;
    }

    static Double pearson(List<double[]> pairs) {
        int n = pairs.size();
        double mx = 0;
        double my = 0;
        for (double[] p : pairs) {
            mx += p[0];
            my += p[1];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (double[] p : pairs) {
            sxy += (p[0] - mx) * (p[1] - my);
            sxx += (p[0] - mx) * (p[0] - mx);
            syy += (p[1] - my) * (p[1] - my);
        }
        return sxx == 0 || syy == 0 ? null : sxy / Math.sqrt(sxx * syy);
    }

    // ------------------------------------------------------------------ guards

    private String code(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replace('/', ' ').replace(" ", "");
        if ("USD_IDR".equals(c) || "USDIDR=X".equals(c)) {
            c = prices.containsKey("USDIDR") ? "USDIDR" : "USD_IDR";
        }
        if (!meta.containsKey(c)) {
            throw new IllegalArgumentException("Unknown code '" + code + "'; use one of: " + String.join(", ", meta.keySet()));
        }
        return c;
    }

    private int months(Integer months) {
        int m = months == null ? 24 : months;
        return Math.max(6, Math.min(properties.historyMonths(), m));
    }

    private void guard(String key) {
        synchronized (asked) {
            if (!asked.add(key)) {
                throw new IllegalStateException("This was asked already; use its result");
            }
        }
        if (calls.incrementAndGet() > properties.maxToolCalls()) {
            throw new IllegalStateException("Tool call limit reached (" + properties.maxToolCalls() + ")");
        }
    }

    private String ref() {
        return "T" + refCount.incrementAndGet();
    }

    /** "Bank Indonesia - Monetary Policy Review August 2026 (2026-08-20, pp. 3-4)" */
    static String source(String issuer, String title, String date, String pages) {
        StringBuilder b = new StringBuilder();
        if (issuer != null && !issuer.isBlank()) {
            b.append(issuer).append(" - ");
        }
        b.append(title);
        List<String> where = new ArrayList<>();
        if (date != null) {
            where.add(date);
        }
        if (pages != null) {
            where.add(pages);
        }
        return where.isEmpty() ? b.toString() : b.append(" (").append(String.join(", ", where)).append(')').toString();
    }

    static String pages(Integer from, Integer to) {
        if (from == null) {
            return null;
        }
        return to == null || to.equals(from) ? "p. " + from : "pp. " + from + "-" + to;
    }

    /** The text with its white space collapsed, at most {@code max} characters. */
    static String excerpt(String text, int max) {
        String t = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private synchronized void record(String ref, String tool, String arguments, String summary) {
        retrieved.add(new Retrieved(ref, tool, arguments, summary));
    }

    private static double round(double v) {
        double abs = Math.abs(v);
        double f = abs >= 1000 ? 1 : abs >= 10 ? 100 : 10000;
        return Math.round(v * f) / f;
    }

    private static String signed(double v) {
        return (v > 0 ? "+" : "") + round(v);
    }
}
