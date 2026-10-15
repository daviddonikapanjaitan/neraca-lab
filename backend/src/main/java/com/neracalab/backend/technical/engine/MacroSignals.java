package com.neracalab.backend.technical.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The macro view of the Druckenmiller and Paul Tudor Jones presets (macro.md): the stored macro series turned into
 * signals (direction and change, not levels) and two regimes - liquidity (Druckenmiller) and risk (Tudor Jones).
 * <p>
 * Point in time: only observations released on or before the as-of date are used ({@link MarketContext.Observation#released}),
 * so a snapshot never knows a CPI print before it was published. A series whose latest observation is older than its
 * frequency allows (daily 10 days, weekly 21, monthly 80; the BI rate holds until the next decision) is stale: its
 * signal is null with a note. Pure and deterministic.
 */
public final class MacroSignals {

    /** How long the latest observation of a series stays current, in days (none: until the next one). */
    static final Map<String, Integer> MAX_AGE_DAYS = Map.ofEntries(
            Map.entry("SBN_10Y", 10), Map.entry("US_FED_FUNDS", 10), Map.entry("US_10Y", 10), Map.entry("US_2Y", 10),
            Map.entry("US_HY_SPREAD", 10), Map.entry("DXY", 10), Map.entry("US_RRP", 10),
            Map.entry("US_BREAKEVEN_10Y", 10), Map.entry("VIX", 10), Map.entry("USD_IDR", 10),
            Map.entry("ID_FOREIGN_NET_BUY", 10), Map.entry("ID_SBN_FOREIGN", 10), Map.entry("ID_CDS_5Y", 10),
            Map.entry("US_FED_ASSETS", 21), Map.entry("US_TGA", 21), Map.entry("COT_GOLD_NET", 21),
            Map.entry("COT_CRUDE_NET", 21), Map.entry("COT_USD_NET", 21),
            Map.entry("ID_CPI_YOY", 80), Map.entry("US_CPI_INDEX", 80), Map.entry("US_CORE_CPI_INDEX", 80),
            Map.entry("US_M2", 80), Map.entry("ID_FX_RESERVES", 80), Map.entry("ID_M2", 80), Map.entry("CN_PMI_MFG", 80));

    /** What a neutral vote says: "Fed: holding". */
    private static final Map<String, String> LABELS = Map.of("fedPolicy", "Fed", "usNetLiquidity", "US net liquidity",
            "creditStress", "Credit", "dollar", "Dollar", "rupiah", "Rupiah", "biPolicy", "BI",
            "foreignFlows", "Foreign flows on IDX", "riskAppetite", "Volatility", "inflation", "US inflation",
            "sbn10y", "SBN 10Y yield");

    /** A vote of one signal in a regime. */
    public record Component(String signal, double vote, String text) {
    }

    private final Map<String, List<MarketContext.Observation>> macro;
    private final LocalDate asOf;
    private final List<String> notes;
    /** series already reported as stale (one note each) */
    private final java.util.Set<String> staleNoted = new java.util.HashSet<>();

    private MacroSignals(Map<String, List<MarketContext.Observation>> macro, LocalDate asOf, List<String> notes) {
        this.macro = macro == null ? Map.of() : macro;
        this.asOf = asOf;
        this.notes = notes;
    }

    /**
     * The {@code series}, {@code signals} and {@code regimes} groups of the snapshot's macro section.
     *
     * @param macro series code -> observations, oldest first
     * @param notes receives a note for every missing or stale input
     */
    public static Map<String, Object> compute(Map<String, List<MarketContext.Observation>> macro, LocalDate asOf,
                                              List<String> notes) {
        return new MacroSignals(macro, asOf, notes).compute();
    }

    private Map<String, Object> compute() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> series = new LinkedHashMap<>();
        for (String code : macro.keySet()) {
            MarketContext.Observation o = latest(code);
            if (o == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", num(o.value()));
            m.put("date", o.date().toString());
            m.put("released", o.released().toString());
            m.put("stale", stale(code, o));
            series.put(code, m);
        }
        out.put("series", series);

        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("biPolicy", policy("BI_RATE", "BI rate"));
        signals.put("idRealRate", idRealRate());
        signals.put("sbn10y", sbn10y());
        signals.put("fedPolicy", policy("US_FED_FUNDS", "Fed funds"));
        signals.put("usCurve", usCurve());
        signals.put("usNetLiquidity", usNetLiquidity());
        signals.put("creditStress", creditStress());
        signals.put("dollar", dollar());
        signals.put("inflation", inflation());
        signals.put("riskAppetite", riskAppetite());
        signals.put("foreignFlows", foreignFlows());
        signals.put("idLiquidity", idLiquidity());
        signals.put("china", china());
        signals.put("positioning", positioning());
        out.put("signals", signals);

        Map<String, Object> regimes = new LinkedHashMap<>();
        regimes.put("liquidity", liquidityRegime(signals));
        regimes.put("risk", riskRegime(signals));
        out.put("regimes", regimes);
        return out;
    }

    // ------------------------------------------------------------------ signals

    /** A policy rate: its level and its 6-month change (CUTTING / HOLDING / HIKING). */
    private Map<String, Object> policy(String code, String name) {
        MarketContext.Observation now = fresh(code);
        if (now == null) {
            return null;
        }
        MarketContext.Observation then = at(code, asOf.minusMonths(6));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", num(now.value()));
        m.put("asOf", now.date().toString());
        Double change = then == null ? null : now.value() - then.value();
        m.put("change6mPoints", num(change));
        m.put("state", change == null ? null : change <= -0.2 ? "CUTTING" : change >= 0.2 ? "HIKING" : "HOLDING");
        m.put("label", name);
        return m;
    }

    /** BI rate minus Indonesian CPI inflation. */
    private Map<String, Object> idRealRate() {
        MarketContext.Observation bi = fresh("BI_RATE");
        MarketContext.Observation cpi = fresh("ID_CPI_YOY");
        if (bi == null || cpi == null) {
            return null;
        }
        double real = bi.value() - cpi.value();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", num(real));
        m.put("biRate", num(bi.value()));
        m.put("cpiYoy", num(cpi.value()));
        m.put("cpiMonth", cpi.date().toString());
        m.put("state", real >= 2 ? "RESTRICTIVE" : real < 0 ? "ACCOMMODATIVE" : "NEUTRAL");
        return m;
    }

    /** SBN 10Y yield, its 3-month change and its spread over the US 10Y (the country premium). */
    private Map<String, Object> sbn10y() {
        MarketContext.Observation now = fresh("SBN_10Y");
        if (now == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", num(now.value()));
        m.put("asOf", now.date().toString());
        Double change = change("SBN_10Y", 3, false);
        m.put("change3mPoints", num(change));
        m.put("trend", trend(change, 0.25, "RISING", "FALLING"));
        MarketContext.Observation us = fresh("US_10Y");
        if (us != null) {
            double spread = now.value() - us.value();
            m.put("spreadVsUs10y", num(spread));
            MarketContext.Observation sbnThen = at("SBN_10Y", asOf.minusMonths(3));
            MarketContext.Observation usThen = at("US_10Y", asOf.minusMonths(3));
            m.put("spreadChange3mPoints", sbnThen == null || usThen == null ? null
                    : num(spread - (sbnThen.value() - usThen.value())));
        } else {
            m.put("spreadVsUs10y", null);
            m.put("spreadChange3mPoints", null);
        }
        return m;
    }

    /** US 10Y - 2Y: level (INVERTED / FLAT / NORMAL) and its 3-month trend. */
    private Map<String, Object> usCurve() {
        MarketContext.Observation y10 = fresh("US_10Y");
        MarketContext.Observation y2 = fresh("US_2Y");
        if (y10 == null || y2 == null) {
            return null;
        }
        double spread = y10.value() - y2.value();
        MarketContext.Observation y10Then = at("US_10Y", asOf.minusMonths(3));
        MarketContext.Observation y2Then = at("US_2Y", asOf.minusMonths(3));
        Double change = y10Then == null || y2Then == null ? null : spread - (y10Then.value() - y2Then.value());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", num(spread));
        m.put("us10y", num(y10.value()));
        m.put("us2y", num(y2.value()));
        m.put("us10yChange3mPoints", num(change("US_10Y", 3, false)));
        m.put("change3mPoints", num(change));
        m.put("state", spread < 0 ? "INVERTED" : spread < 0.5 ? "FLAT" : "NORMAL");
        m.put("trend", trend(change, 0.15, "STEEPENING", "FLATTENING"));
        m.put("asOf", y10.date().toString());
        return m;
    }

    /**
     * US net liquidity = Fed assets - Treasury General Account - reverse repo (USD bn) and its 3-month change; with only
     * the Fed assets stored, their change stands in (basis says which).
     */
    private Map<String, Object> usNetLiquidity() {
        MarketContext.Observation fed = fresh("US_FED_ASSETS");
        if (fed == null) {
            return null;
        }
        MarketContext.Observation tga = fresh("US_TGA");
        MarketContext.Observation rrp = fresh("US_RRP");
        LocalDate then = asOf.minusMonths(3);
        Map<String, Object> m = new LinkedHashMap<>();
        Double now;
        Double before;
        if (tga != null && rrp != null) {
            now = fed.value() / 1000 - tga.value() / 1000 - rrp.value();
            MarketContext.Observation fedThen = at("US_FED_ASSETS", then);
            MarketContext.Observation tgaThen = at("US_TGA", then);
            MarketContext.Observation rrpThen = at("US_RRP", then);
            before = fedThen == null || tgaThen == null || rrpThen == null ? null
                    : fedThen.value() / 1000 - tgaThen.value() / 1000 - rrpThen.value();
            m.put("basis", "Fed assets - TGA - reverse repo, USD bn");
        } else {
            now = fed.value() / 1000;
            MarketContext.Observation fedThen = at("US_FED_ASSETS", then);
            before = fedThen == null ? null : fedThen.value() / 1000;
            m.put("basis", "Fed assets only (TGA / reverse repo not stored), USD bn");
        }
        Double changePct = before == null || before <= 0 ? null : (now / before - 1) * 100;
        m.put("value", num(now));
        m.put("change3mPct", num(changePct));
        m.put("direction", trend(changePct, 1.0, "GROWING", "SHRINKING"));
        m.put("asOf", fed.date().toString());
        return m;
    }

    /** US high-yield spread: level, its percentile over the last year and its 1-month change. */
    private Map<String, Object> creditStress() {
        MarketContext.Observation now = fresh("US_HY_SPREAD");
        if (now == null) {
            return null;
        }
        Double percentile = percentile("US_HY_SPREAD", 252);
        Double change = change("US_HY_SPREAD", 1, false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", num(now.value()));
        m.put("percentile1y", num(percentile));
        m.put("change1mPoints", num(change));
        String state = "NORMAL";
        if ((percentile != null && percentile >= 80) || (change != null && change >= 1.0)) {
            state = "STRESSED";
        } else if (percentile != null && percentile <= 30 && (change == null || change < 0.5)) {
            state = "CALM";
        }
        m.put("state", state);
        m.put("asOf", now.date().toString());
        return m;
    }

    /** DXY and USD/IDR over 3 months. */
    private Map<String, Object> dollar() {
        MarketContext.Observation dxy = fresh("DXY");
        MarketContext.Observation idr = fresh("USD_IDR");
        if (dxy == null && idr == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Double dxyChange = dxy == null ? null : change("DXY", 3, true);
        Double idrChange = idr == null ? null : change("USD_IDR", 3, true);
        m.put("dxy", dxy == null ? null : num(dxy.value()));
        m.put("dxyChange3mPct", num(dxyChange));
        m.put("dxyTrend", trend(dxyChange, 2.0, "STRENGTHENING", "WEAKENING"));
        m.put("usdIdr", idr == null ? null : num(idr.value()));
        m.put("usdIdrChange3mPct", num(idrChange));
        // USD/IDR up = the rupiah weakens
        m.put("rupiahTrend", idrChange == null ? null : idrChange >= 2 ? "WEAKENING" : idrChange <= -1 ? "STRENGTHENING"
                : "STABLE");
        return m;
    }

    /** Indonesian and US inflation, their 3-month trend, and US breakeven inflation. */
    private Map<String, Object> inflation() {
        Map<String, Object> m = new LinkedHashMap<>();
        MarketContext.Observation id = fresh("ID_CPI_YOY");
        Double idChange = id == null ? null : change("ID_CPI_YOY", 3, false);
        m.put("idCpiYoy", id == null ? null : num(id.value()));
        m.put("idCpiMonth", id == null ? null : id.date().toString());
        m.put("idTrend", trend(idChange, 0.3, "RISING", "FALLING"));
        Double usNow = yoy("US_CPI_INDEX", 0);
        Double usThen = yoy("US_CPI_INDEX", 3);
        m.put("usCpiYoy", num(usNow));
        m.put("usCpiMonth", usNow == null ? null : latest("US_CPI_INDEX").date().toString());
        m.put("usTrend", usNow == null || usThen == null ? null : trend(usNow - usThen, 0.3, "RISING", "FALLING"));
        Double coreNow = yoy("US_CORE_CPI_INDEX", 0);
        Double coreThen = yoy("US_CORE_CPI_INDEX", 3);
        m.put("usCoreCpiYoy", num(coreNow));
        m.put("usCoreTrend", coreNow == null || coreThen == null ? null : trend(coreNow - coreThen, 0.3, "RISING", "FALLING"));
        MarketContext.Observation be = fresh("US_BREAKEVEN_10Y");
        Double beChange = be == null ? null : change("US_BREAKEVEN_10Y", 3, false);
        m.put("usBreakeven10y", be == null ? null : num(be.value()));
        m.put("usBreakevenChange3mPoints", num(beChange));
        MarketContext.Observation y10 = fresh("US_10Y");
        if (be != null && y10 != null) {
            double real = y10.value() - be.value();
            MarketContext.Observation y10Then = at("US_10Y", asOf.minusMonths(3));
            MarketContext.Observation beThen = at("US_BREAKEVEN_10Y", asOf.minusMonths(3));
            m.put("usRealYield10y", num(real));
            m.put("usRealYieldChange3mPoints", y10Then == null || beThen == null ? null
                    : num(real - (y10Then.value() - beThen.value())));
        } else {
            m.put("usRealYield10y", null);
            m.put("usRealYieldChange3mPoints", null);
        }
        boolean any = m.values().stream().anyMatch(v -> v != null);
        return any ? m : null;
    }

    /** VIX level and its percentile over the last year. */
    private Map<String, Object> riskAppetite() {
        MarketContext.Observation vix = fresh("VIX");
        if (vix == null) {
            return null;
        }
        Double percentile = percentile("VIX", 252);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vix", num(vix.value()));
        m.put("percentile1y", num(percentile));
        m.put("state", vix.value() >= 25 ? "FEAR" : vix.value() <= 16 ? "CALM" : "NORMAL");
        m.put("asOf", vix.date().toString());
        return m;
    }

    /** Net foreign buying on IDX over the last 20 sessions, and the 1-month change of foreign SBN holdings. */
    private Map<String, Object> foreignFlows() {
        MarketContext.Observation flow = fresh("ID_FOREIGN_NET_BUY");
        MarketContext.Observation sbn = fresh("ID_SBN_FOREIGN");
        if (flow == null && sbn == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        Double sum20 = null;
        if (flow != null) {
            List<MarketContext.Observation> obs = available("ID_FOREIGN_NET_BUY");
            double sum = 0;
            for (int i = Math.max(0, obs.size() - 20); i < obs.size(); i++) {
                sum += obs.get(i).value();
            }
            sum20 = sum;
        }
        m.put("idxNetBuy20dBn", num(sum20));
        m.put("idxState", sum20 == null ? null : sum20 > 0 ? "INFLOW" : sum20 < 0 ? "OUTFLOW" : "FLAT");
        Double sbnChange = sbn == null ? null : change("ID_SBN_FOREIGN", 1, false);
        m.put("sbnForeignTn", sbn == null ? null : num(sbn.value()));
        m.put("sbnForeignChange1mTn", num(sbnChange));
        m.put("sbnState", sbnChange == null ? null : sbnChange > 0 ? "INFLOW" : sbnChange < 0 ? "OUTFLOW" : "FLAT");
        return m;
    }

    /** BI reserves over 3 months and Indonesian M2 over a year. */
    private Map<String, Object> idLiquidity() {
        MarketContext.Observation reserves = fresh("ID_FX_RESERVES");
        MarketContext.Observation m2 = fresh("ID_M2");
        if (reserves == null && m2 == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fxReservesUsdBn", reserves == null ? null : num(reserves.value() / 1000));
        m.put("fxReservesChange3mPct", num(reserves == null ? null : change("ID_FX_RESERVES", 3, true)));
        m.put("m2YoyPct", num(m2 == null ? null : change("ID_M2", 12, true)));
        return m;
    }

    private Map<String, Object> china() {
        MarketContext.Observation pmi = fresh("CN_PMI_MFG");
        if (pmi == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pmi", num(pmi.value()));
        m.put("month", pmi.date().toString());
        m.put("state", pmi.value() >= 50 ? "EXPANSION" : "CONTRACTION");
        return m;
    }

    /** Speculative net positions (CFTC) and their percentile over 3 years: crowded at the 90th / 10th. */
    private Map<String, Object> positioning() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String[] c : new String[][] {{"gold", "COT_GOLD_NET"}, {"crude", "COT_CRUDE_NET"}, {"usd", "COT_USD_NET"}}) {
            MarketContext.Observation now = fresh(c[1]);
            if (now == null) {
                continue;
            }
            Double percentile = percentile(c[1], 156);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("net", num(now.value()));
            p.put("percentile3y", num(percentile));
            p.put("state", percentile == null ? null : percentile >= 90 ? "CROWDED_LONG" : percentile <= 10 ? "CROWDED_SHORT"
                    : "NEUTRAL");
            p.put("asOf", now.date().toString());
            m.put(c[0], p);
        }
        return m.isEmpty() ? null : m;
    }

    // ------------------------------------------------------------------ regimes

    /**
     * Druckenmiller's liquidity regime: the Fed and BI direction, US net liquidity, credit, the dollar, the rupiah and
     * foreign flows each vote +1 (easier) / -1 (tighter); EASING at +2, TIGHTENING at -2. Fewer than 3 votes: unknown.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> liquidityRegime(Map<String, Object> s) {
        List<Component> c = new ArrayList<>();
        Map<String, Object> fed = (Map<String, Object>) s.get("fedPolicy");
        vote(c, "fedPolicy", str(fed, "state"), "CUTTING", "HIKING", "Fed cutting rates (" + pts(fed, "change6mPoints") + " in 6 months)",
                "Fed hiking rates (" + pts(fed, "change6mPoints") + " in 6 months)");
        Map<String, Object> net = (Map<String, Object>) s.get("usNetLiquidity");
        vote(c, "usNetLiquidity", str(net, "direction"), "GROWING", "SHRINKING",
                "US net liquidity growing (" + pct(net, "change3mPct") + " in 3 months)",
                "US net liquidity shrinking (" + pct(net, "change3mPct") + " in 3 months)");
        Map<String, Object> credit = (Map<String, Object>) s.get("creditStress");
        vote(c, "creditStress", str(credit, "state"), "CALM", "STRESSED",
                "Credit calm: US high-yield spread " + val(credit, "value") + "% (" + rank(credit, "percentile1y")
                        + " percentile of the year)",
                "Credit stress: US high-yield spread " + val(credit, "value") + "% (" + rank(credit, "percentile1y")
                        + " percentile of the year, " + pts(credit, "change1mPoints") + " in a month)");
        Map<String, Object> dollar = (Map<String, Object>) s.get("dollar");
        vote(c, "dollar", str(dollar, "dxyTrend"), "WEAKENING", "STRENGTHENING",
                "Dollar weakening (DXY " + pct(dollar, "dxyChange3mPct") + " in 3 months)",
                "Dollar strengthening (DXY " + pct(dollar, "dxyChange3mPct") + " in 3 months): drains emerging markets");
        vote(c, "rupiah", str(dollar, "rupiahTrend"), "STRENGTHENING", "WEAKENING",
                "Rupiah strengthening (USD/IDR " + pct(dollar, "usdIdrChange3mPct") + " in 3 months)",
                "Rupiah weakening (USD/IDR " + pct(dollar, "usdIdrChange3mPct") + " in 3 months)");
        Map<String, Object> bi = (Map<String, Object>) s.get("biPolicy");
        vote(c, "biPolicy", str(bi, "state"), "CUTTING", "HIKING", "BI cutting (" + pts(bi, "change6mPoints") + " in 6 months)",
                "BI hiking (" + pts(bi, "change6mPoints") + " in 6 months)");
        Map<String, Object> flows = (Map<String, Object>) s.get("foreignFlows");
        vote(c, "foreignFlows", str(flows, "idxState"), "INFLOW", "OUTFLOW",
                "Foreigners net buyers on IDX (Rp " + val(flows, "idxNetBuy20dBn") + " bn in 20 sessions)",
                "Foreigners net sellers on IDX (Rp " + val(flows, "idxNetBuy20dBn") + " bn in 20 sessions)");
        return regime(c, "EASING", "TIGHTENING");
    }

    /**
     * Tudor Jones's risk regime: volatility, credit, the US curve, the dollar, US inflation and Indonesian yields each
     * vote +1 (risk on) / -1 (risk off); RISK_ON at +2, RISK_OFF at -2. Fewer than 3 votes: unknown.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> riskRegime(Map<String, Object> s) {
        List<Component> c = new ArrayList<>();
        Map<String, Object> vix = (Map<String, Object>) s.get("riskAppetite");
        vote(c, "riskAppetite", str(vix, "state"), "CALM", "FEAR", "Volatility low: VIX " + val(vix, "vix"),
                "Fear: VIX " + val(vix, "vix"));
        Map<String, Object> credit = (Map<String, Object>) s.get("creditStress");
        vote(c, "creditStress", str(credit, "state"), "CALM", "STRESSED",
                "Credit spreads tight (" + val(credit, "value") + "%)", "Credit spreads widening (" + val(credit, "value") + "%)");
        Map<String, Object> curve = (Map<String, Object>) s.get("usCurve");
        if (curve != null && "INVERTED".equals(curve.get("state"))) {
            c.add(new Component("usCurve", -1, "US yield curve inverted (10Y - 2Y " + val(curve, "value") + " points)"));
        } else if (curve != null) {
            c.add(new Component("usCurve", 0, "US yield curve " + str(curve, "state").toLowerCase(Locale.ROOT) + " ("
                    + val(curve, "value") + " points, " + String.valueOf(curve.get("trend")).toLowerCase(Locale.ROOT) + ")"));
        }
        Map<String, Object> dollar = (Map<String, Object>) s.get("dollar");
        vote(c, "dollar", str(dollar, "dxyTrend"), "WEAKENING", "STRENGTHENING",
                "Dollar weakening (DXY " + pct(dollar, "dxyChange3mPct") + ")", "Dollar strengthening (DXY "
                        + pct(dollar, "dxyChange3mPct") + ")");
        Map<String, Object> inflation = (Map<String, Object>) s.get("inflation");
        vote(c, "inflation", str(inflation, "usTrend"), "FALLING", "RISING",
                "US inflation falling (CPI " + val(inflation, "usCpiYoy") + "% YoY)",
                "US inflation rising (CPI " + val(inflation, "usCpiYoy") + "% YoY)");
        Map<String, Object> sbn = (Map<String, Object>) s.get("sbn10y");
        vote(c, "sbn10y", str(sbn, "trend"), "FALLING", "RISING",
                "SBN 10Y yield falling (" + pts(sbn, "change3mPoints") + " in 3 months)",
                "SBN 10Y yield rising (" + pts(sbn, "change3mPoints") + " in 3 months)");
        return regime(c, "RISK_ON", "RISK_OFF");
    }

    private static void vote(List<Component> c, String signal, String state, String positive, String negative,
                             String positiveText, String negativeText) {
        if (state == null) {
            return;
        }
        if (state.equals(positive)) {
            c.add(new Component(signal, 1, positiveText));
        } else if (state.equals(negative)) {
            c.add(new Component(signal, -1, negativeText));
        } else {
            c.add(new Component(signal, 0, LABELS.getOrDefault(signal, signal) + ": "
                    + state.toLowerCase(Locale.ROOT).replace('_', ' ')));
        }
    }

    private static Map<String, Object> regime(List<Component> components, String positive, String negative) {
        Map<String, Object> m = new LinkedHashMap<>();
        double score = components.stream().mapToDouble(Component::vote).sum();
        m.put("state", components.size() < 3 ? null : score >= 2 ? positive : score <= -2 ? negative : "NEUTRAL");
        m.put("score", num(score));
        m.put("signals", components.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Component c : components) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("signal", c.signal());
            cm.put("vote", num(c.vote()));
            cm.put("text", c.text());
            list.add(cm);
        }
        m.put("components", list);
        if (components.size() < 3) {
            m.put("note", "fewer than 3 macro signals available: run the macro ingestion (Ingestion > Macro)");
        }
        return m;
    }

    // ------------------------------------------------------------------ data access

    /** Observations released on or before the as-of date, oldest first. */
    private List<MarketContext.Observation> available(String code) {
        List<MarketContext.Observation> obs = macro.get(code);
        if (obs == null || obs.isEmpty()) {
            return List.of();
        }
        List<MarketContext.Observation> out = new ArrayList<>(obs.size());
        for (MarketContext.Observation o : obs) {
            if (!o.released().isAfter(asOf) && !o.date().isAfter(asOf)) {
                out.add(o);
            }
        }
        return out;
    }

    private MarketContext.Observation latest(String code) {
        List<MarketContext.Observation> obs = available(code);
        return obs.isEmpty() ? null : obs.getLast();
    }

    /** The latest observation when it is not stale (else null, with a note). */
    private MarketContext.Observation fresh(String code) {
        MarketContext.Observation o = latest(code);
        if (o == null) {
            return null;
        }
        if (stale(code, o)) {
            if (staleNoted.add(code)) {
                notes.add("Macro " + code + " is stale (latest " + o.date() + "): its signals are null");
            }
            return null;
        }
        return o;
    }

    private boolean stale(String code, MarketContext.Observation o) {
        Integer max = MAX_AGE_DAYS.get(code);
        return max != null && ChronoUnit.DAYS.between(o.date(), asOf) > max;
    }

    /** The latest available observation dated on or before {@code date}. */
    private MarketContext.Observation at(String code, LocalDate date) {
        MarketContext.Observation found = null;
        for (MarketContext.Observation o : available(code)) {
            if (o.date().isAfter(date)) {
                break;
            }
            found = o;
        }
        return found;
    }

    /** Change of the latest observation over {@code months}: points, or % when {@code pct}. */
    private Double change(String code, int months, boolean pct) {
        MarketContext.Observation now = latest(code);
        if (now == null) {
            return null;
        }
        MarketContext.Observation then = at(code, now.date().minusMonths(months));
        if (then == null || then.date().isBefore(now.date().minusMonths(months).minusDays(45))) {
            return null;
        }
        if (pct) {
            return then.value() == 0 ? null : (now.value() / then.value() - 1) * 100;
        }
        return now.value() - then.value();
    }

    /** Percentile rank (0-100) of the latest observation among the last {@code n}; null with fewer than 20. */
    private Double percentile(String code, int n) {
        List<MarketContext.Observation> obs = available(code);
        if (obs.size() < 20) {
            return null;
        }
        List<MarketContext.Observation> window = obs.subList(Math.max(0, obs.size() - n), obs.size());
        double last = window.getLast().value();
        long below = window.stream().filter(o -> o.value() <= last).count();
        return below * 100.0 / window.size();
    }

    /** Year-on-year change (%) of a monthly index {@code monthsAgo} months before its latest month; null when stale. */
    private Double yoy(String code, int monthsAgo) {
        MarketContext.Observation latest = fresh(code);
        if (latest == null) {
            return null;
        }
        LocalDate month = latest.date().minusMonths(monthsAgo);
        MarketContext.Observation now = exact(code, month);
        MarketContext.Observation then = exact(code, month.minusYears(1));
        return now == null || then == null || then.value() == 0 ? null : (now.value() / then.value() - 1) * 100;
    }

    private MarketContext.Observation exact(String code, LocalDate date) {
        for (MarketContext.Observation o : available(code)) {
            if (o.date().equals(date)) {
                return o;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static String trend(Double change, double threshold, String up, String down) {
        if (change == null) {
            return null;
        }
        return change >= threshold ? up : change <= -threshold ? down : "STABLE";
    }

    private static String str(Map<String, Object> m, String key) {
        return m == null || m.get(key) == null ? null : String.valueOf(m.get(key));
    }

    private static String val(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? String.format(Locale.US, "%,.2f", n.doubleValue()) : "n/a";
    }

    /** "92nd" */
    private static String rank(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (!(v instanceof Number n)) {
            return "n/a";
        }
        long r = Math.round(n.doubleValue());
        String suffix = r % 100 >= 11 && r % 100 <= 13 ? "th" : switch ((int) (r % 10)) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return r + suffix;
    }

    private static String pct(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? (n.doubleValue() > 0 ? "+" : "") + String.format(Locale.US, "%.1f", n.doubleValue()) + "%"
                : "n/a";
    }

    private static String pts(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? (n.doubleValue() > 0 ? "+" : "") + String.format(Locale.US, "%.2f", n.doubleValue())
                + " points" : "n/a";
    }

    private static Double num(Double v) {
        return v == null ? null : SnapshotCalculator.num(v);
    }
}
