package com.neracalab.backend.macro.outlook;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.neracalab.backend.technical.engine.Candle;

/**
 * The commodities and currency pairs stored by the price ingestion ({@code instrument_daily}), read for Indonesia: for
 * each its latest close, its 1-month, 3-month and 1-year change, where it stands in its 52-week range, and what a rise
 * means for the country - Indonesia exports coal, nickel, palm oil, tin, gold, copper and LNG and imports crude oil,
 * wheat and soybeans. Fixed rules, no model:
 * <ul>
 *   <li>an <b>export</b> commodity is supportive (POSITIVE) when it rose 5 % or more in 3 months (terms of trade, export
 *       earnings, the rupiah), a headwind (NEGATIVE) when it fell 5 % or more; an <b>import</b> the other way round;
 *       a <b>mixed</b> one (gold: a haven as well as an export) and the yen are NEUTRAL;</li>
 *   <li>USD/IDR rising 2 % or more in 3 months (the rupiah weakening) is a headwind, falling 1 % or more supportive;</li>
 *   <li>USD/CNY rising 2 % or more (a weaker yuan: China is Indonesia's largest trading partner, and the yuan pulls
 *       Asian currencies with it) is a headwind, falling 2 % or more supportive; EUR/USD rising 2 % or more (a weaker
 *       dollar, as {@code MacroSignals} reads the DXY) supportive, falling 2 % or more a headwind; AUD/JPY (risk
 *       appetite and carry trades) rising 5 % or more supportive, falling 5 % or more a headwind; the other pairs
 *       are NEUTRAL on their own;</li>
 *   <li>a daily series without a bar in the last 10 days, a monthly one without a bar in the last 75 days, is stale
 *       (NEUTRAL, with a note).</li>
 * </ul>
 * The terms of trade sum the export (+1 / -1) and import (-1 / +1) tones: from +2 favourable, from -2 unfavourable.
 * <p>
 * The rupiah against its Asian peers ({@link #regional}): USD/IDR's 3-month change beside the median of USD/CNY,
 * USD/MYR, USD/PHP, USD/INR and USD/KRW (at least 3 of them current). Within {@value #PEER_GAP_PTS} points of the
 * median the rupiah moves with the region (IN_LINE: the dollar and global money drive it); weaker by that much or
 * more it UNDERPERFORMS (a pressure of Indonesia's own, a headwind); firmer it OUTPERFORMS (supportive).
 */
public final class MarketBoard {

    /** What a rise of the instrument means for Indonesia. */
    enum Role {
        EXPORT, IMPORT, MIXED, CURRENCY, REFERENCE
    }

    /** One instrument as the board reads it. */
    record Relevance(Role role, String text) {
    }

    /**
     * What each catalogued instrument means for Indonesia (sectors, not tickers). An instrument not listed here is
     * shown as a reference only.
     */
    static final Map<String, Relevance> RELEVANCE = Map.ofEntries(
            Map.entry("COAL", new Relevance(Role.EXPORT, "Indonesia is the largest exporter of thermal coal: coal miners, "
                    + "export earnings and the rupiah")),
            Map.entry("NICKEL", new Relevance(Role.EXPORT, "Indonesia is the largest nickel producer: nickel miners and "
                    + "smelters, downstream (EV battery) investment")),
            Map.entry("COBALT", new Relevance(Role.EXPORT, "a by-product of Indonesia's nickel (HPAL) smelters")),
            Map.entry("PALM_OIL", new Relevance(Role.EXPORT, "Indonesia is the largest palm oil producer: plantations, "
                    + "export earnings, rural incomes")),
            Map.entry("PALM_OIL_MYR", new Relevance(Role.EXPORT, "the Bursa Malaysia benchmark of the palm oil Indonesia "
                    + "exports")),
            Map.entry("TIN", new Relevance(Role.EXPORT, "Indonesia is among the largest tin exporters: tin miners")),
            Map.entry("COPPER", new Relevance(Role.EXPORT, "copper and gold miners; a gauge of global growth")),
            Map.entry("LNG", new Relevance(Role.EXPORT, "Indonesia exports LNG: gas producers")),
            Map.entry("RUBBER", new Relevance(Role.EXPORT, "rubber plantations and smallholders")),
            Map.entry("PULP", new Relevance(Role.EXPORT, "pulp and paper producers")),
            Map.entry("GOLD", new Relevance(Role.MIXED, "gold miners gain; also a haven: it rises when investors fear "
                    + "risk")),
            Map.entry("SILVER", new Relevance(Role.MIXED, "precious metals; follows gold")),
            Map.entry("BRENT", new Relevance(Role.IMPORT, "Indonesia imports crude oil and fuel: a higher price widens "
                    + "the fuel subsidy bill and the trade deficit; oil and gas producers gain")),
            Map.entry("WTI", new Relevance(Role.IMPORT, "the US crude benchmark; follows Brent")),
            Map.entry("NATURAL_GAS", new Relevance(Role.REFERENCE, "the US gas benchmark (Henry Hub)")),
            Map.entry("WHEAT", new Relevance(Role.IMPORT, "Indonesia imports all its wheat: flour and noodle makers' "
                    + "costs, food inflation")),
            Map.entry("SOYBEAN_MEAL", new Relevance(Role.IMPORT, "imported feed: poultry and feed producers' costs")),
            Map.entry("SOYBEAN_OIL", new Relevance(Role.REFERENCE, "a substitute of palm oil in edible oils")),
            Map.entry("CORN", new Relevance(Role.IMPORT, "feed: poultry and feed producers' costs")),
            Map.entry("SUGAR", new Relevance(Role.IMPORT, "Indonesia imports raw sugar: food and beverage makers' costs")),
            Map.entry("UREA", new Relevance(Role.REFERENCE, "fertilizer: farm costs and fertilizer producers")),
            Map.entry("DAP", new Relevance(Role.REFERENCE, "fertilizer: farm costs")),
            Map.entry("POTASH", new Relevance(Role.REFERENCE, "fertilizer: plantation costs")),
            Map.entry("IRON_ORE", new Relevance(Role.REFERENCE, "steel input; a gauge of China's construction")),
            Map.entry("STEEL_HRC", new Relevance(Role.REFERENCE, "steel producers and construction costs")),
            Map.entry("ALUMINIUM", new Relevance(Role.REFERENCE, "aluminium smelting and its users")),
            Map.entry("USDIDR", new Relevance(Role.CURRENCY, "the rupiah: a rise is a weaker rupiah - foreign investors' "
                    + "returns, imported inflation, BI's room to cut")),
            Map.entry("USDCNY", new Relevance(Role.CURRENCY, "the yuan: China is Indonesia's largest trading partner "
                    + "(coal, nickel, palm oil); a rise (a weaker yuan) pulls Asian currencies, the rupiah among them, "
                    + "down and makes Chinese goods cheaper")),
            Map.entry("USDMYR", new Relevance(Role.CURRENCY, "the ringgit: Malaysia's currency, the closest peer of the "
                    + "rupiah and its rival in palm oil; read with the rupiah")),
            Map.entry("USDPHP", new Relevance(Role.CURRENCY, "the peso: an ASEAN peer of the rupiah; read with the "
                    + "rupiah")),
            Map.entry("USDINR", new Relevance(Role.CURRENCY, "the rupee: India buys Indonesian coal and palm oil; an "
                    + "emerging-market peer of the rupiah")),
            Map.entry("USDKRW", new Relevance(Role.CURRENCY, "the won: a gauge of Asia's exports and the tech cycle; a "
                    + "peer of the rupiah")),
            Map.entry("EURUSD", new Relevance(Role.CURRENCY, "the dollar against the euro: a rise is a weaker dollar, "
                    + "easier global money for emerging markets")),
            Map.entry("AUDJPY", new Relevance(Role.CURRENCY, "risk appetite: a fall comes with risk aversion and the "
                    + "unwinding of carry trades, when foreign money leaves emerging markets")),
            Map.entry("USDJPY", new Relevance(Role.CURRENCY, "the yen: a fall (a stronger yen) often comes with global "
                    + "risk aversion and the unwinding of carry trades")));

    /** 3-month move of a commodity that turns its tone. */
    static final double COMMODITY_MOVE_PCT = 5;
    /** 3-month move of USD/IDR: weakening from +2 %, strengthening from -1 % (as {@code MacroSignals} reads it). */
    static final double RUPIAH_WEAK_PCT = 2;
    static final double RUPIAH_STRONG_PCT = -1;
    /** 3-month move of USD/CNY (the yuan) and of EUR/USD (the dollar, as {@code MacroSignals} reads the DXY). */
    static final double YUAN_MOVE_PCT = 2;
    static final double EURO_MOVE_PCT = 2;
    /** 3-month move of AUD/JPY (risk appetite). */
    static final double RISK_MOVE_PCT = 5;
    /** The rupiah's Asian peers, read against it ({@link #regional}). */
    static final List<String> PEERS = List.of("USDCNY", "USDMYR", "USDPHP", "USDINR", "USDKRW");
    /** Peers needed for the regional reading. */
    static final int MIN_PEERS = 3;
    /** Points of 3-month change between the rupiah and its peers' median that tell the rupiah's own move. */
    static final double PEER_GAP_PTS = 1.5;

    /** A stored instrument: its catalog fields and bars (oldest first). */
    public record Input(String code, String name, String market, String unit, String frequency, List<Candle> bars) {
    }

    private MarketBoard() {
    }

    /**
     * The board as of a day: the commodities, the currency pairs and the terms of trade.
     *
     * @param instruments the stored instruments in display order (commodities and Forex pairs)
     */
    public static Map<String, Object> compute(List<Input> instruments, LocalDate asOf) {
        List<Map<String, Object>> commodities = new ArrayList<>();
        List<Map<String, Object>> forex = new ArrayList<>();
        int score = 0;
        int voting = 0;
        List<String> favourable = new ArrayList<>();
        List<String> unfavourable = new ArrayList<>();
        for (Input in : instruments) {
            Map<String, Object> row = row(in, asOf);
            if (row == null) {
                continue;
            }
            if ("FOREX".equals(in.market())) {
                forex.add(row);
            } else {
                commodities.add(row);
            }
            Relevance r = RELEVANCE.get(in.code());
            String tone = (String) row.get("tone");
            if (r != null && (r.role() == Role.EXPORT || r.role() == Role.IMPORT) && !Boolean.TRUE.equals(row.get("stale"))
                    && row.get("change3mPct") != null) {
                voting++;
                if ("POSITIVE".equals(tone)) {
                    score++;
                    favourable.add(in.name() + " " + pct((Double) row.get("change3mPct")));
                } else if ("NEGATIVE".equals(tone)) {
                    score--;
                    unfavourable.add(in.name() + " " + pct((Double) row.get("change3mPct")));
                }
            }
        }
        Map<String, Object> terms = new LinkedHashMap<>();
        String state = voting == 0 ? null : score >= 2 ? "FAVOURABLE" : score <= -2 ? "UNFAVOURABLE" : "NEUTRAL";
        terms.put("state", state);
        terms.put("score", voting == 0 ? null : score);
        terms.put("signals", voting);
        terms.put("favourable", favourable);
        terms.put("unfavourable", unfavourable);
        terms.put("summary", termsSummary(state, score, voting, favourable, unfavourable));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOfDate", asOf.toString());
        out.put("commodities", commodities);
        out.put("forex", forex);
        out.put("termsOfTrade", terms);
        out.put("regionalCurrencies", regional(forex));
        return out;
    }

    /**
     * The rupiah against its Asian peers over 3 months: USD/IDR's change beside the median of the peers' (a rise is a
     * weaker currency in every pair). Only current pairs (not stale, with a 3-month change) are read; without USD/IDR
     * or with fewer than {@link #MIN_PEERS} peers the state is null.
     */
    static Map<String, Object> regional(List<Map<String, Object>> forex) {
        Map<String, Map<String, Object>> current = new LinkedHashMap<>();
        for (Map<String, Object> row : forex) {
            if (!Boolean.TRUE.equals(row.get("stale")) && row.get("change3mPct") != null) {
                current.put((String) row.get("code"), row);
            }
        }
        List<Map<String, Object>> peers = new ArrayList<>();
        List<Double> changes = new ArrayList<>();
        for (String code : PEERS) {
            Map<String, Object> row = current.get(code);
            if (row != null) {
                Map<String, Object> peer = new LinkedHashMap<>();
                peer.put("code", code);
                peer.put("name", row.get("name"));
                peer.put("change3mPct", row.get("change3mPct"));
                peers.add(peer);
                changes.add((Double) row.get("change3mPct"));
            }
        }
        Map<String, Object> rupiah = current.get("USDIDR");
        Double idr = rupiah == null ? null : (Double) rupiah.get("change3mPct");
        Double median = changes.size() < MIN_PEERS ? null : median(changes);
        Double gap = idr == null || median == null ? null : idr - median;
        String state = gap == null ? null : gap >= PEER_GAP_PTS ? "UNDERPERFORMING"
                : gap <= -PEER_GAP_PTS ? "OUTPERFORMING" : "IN_LINE";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state);
        out.put("tone", state == null ? "NEUTRAL" : switch (state) {
            case "UNDERPERFORMING" -> "NEGATIVE";
            case "OUTPERFORMING" -> "POSITIVE";
            default -> "NEUTRAL";
        });
        out.put("rupiah3mPct", idr);
        out.put("peersMedian3mPct", round(median, 2));
        out.put("gapPts", round(gap, 2));
        out.put("peers", peers);
        out.put("summary", regionalSummary(state, idr, median, gap, peers));
        return out;
    }

    static String regionalSummary(String state, Double idr, Double median, Double gap, List<Map<String, Object>> peers) {
        if (idr == null) {
            return "No current USD/IDR price is stored (Ingestion › Price Ingestion, market Forex): the rupiah cannot be "
                    + "read against its Asian peers.";
        }
        if (state == null) {
            return "Only " + peers.size() + " of the rupiah's Asian peers (USD/CNY, USD/MYR, USD/PHP, USD/INR, USD/KRW) "
                    + "have current prices; " + MIN_PEERS + " are needed to read the rupiah against the region "
                    + "(Ingestion › Price Ingestion, market Forex).";
        }
        StringBuilder t = new StringBuilder("Over 3 months USD/IDR moved ").append(pct(idr))
                .append(" (a rise is a weaker rupiah), its Asian peers a median ").append(pct(median)).append(" (");
        for (int i = 0; i < peers.size(); i++) {
            t.append(i == 0 ? "" : ", ").append(peers.get(i).get("name")).append(' ')
                    .append(pct((Double) peers.get(i).get("change3mPct")));
        }
        t.append("). ");
        String gapText = MacroOutlook.f(Math.abs(gap), 1) + " points";
        t.append(switch (state) {
            case "UNDERPERFORMING" -> "The rupiah is " + gapText + " weaker than the region: a pressure of Indonesia's "
                    + "own (foreign outflows, the fiscal and policy outlook), a headwind for foreign money in its stocks.";
            case "OUTPERFORMING" -> "The rupiah is " + gapText + " firmer than the region: money favours Indonesia "
                    + "over its peers, supportive.";
            default -> "The rupiah moves with the region (within " + MacroOutlook.f(PEER_GAP_PTS, 1)
                    + " points of its peers): " + (median >= RUPIAH_WEAK_PCT ? "a strong dollar weighs on all of Asia, "
                    + "not on Indonesia alone." : median <= RUPIAH_STRONG_PCT ? "a weaker dollar lifts all of Asia." : "no "
                    + "pressure of Indonesia's own.");
        });
        return t.toString();
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    static String termsSummary(String state, int score, int voting, List<String> favourable, List<String> unfavourable) {
        if (state == null) {
            return "No commodity prices are stored yet (Ingestion › Price Ingestion, market Commodities): the terms of "
                    + "trade are unknown.";
        }
        StringBuilder t = new StringBuilder("Indonesia's commodity terms of trade over 3 months: ")
                .append(state.toLowerCase(java.util.Locale.ROOT)).append(" (").append(score > 0 ? "+" : "").append(score)
                .append(" of ").append(voting).append(" export and import prices). ");
        if (!favourable.isEmpty()) {
            t.append("For: ").append(String.join(", ", favourable)).append(". ");
        }
        if (!unfavourable.isEmpty()) {
            t.append("Against: ").append(String.join(", ", unfavourable)).append(". ");
        }
        t.append(switch (state) {
            case "FAVOURABLE" -> "Stronger export earnings support the trade balance, the rupiah and the resource sectors.";
            case "UNFAVOURABLE" -> "Weaker export prices (or dearer imports) weigh on the trade balance, the rupiah and "
                    + "the resource sectors.";
            default -> "No strong push from commodities either way.";
        });
        return t.toString();
    }

    /** One instrument's row; null without a bar on or before the as-of date. */
    static Map<String, Object> row(Input in, LocalDate asOf) {
        List<Candle> bars = in.bars().stream().filter(b -> !b.date().isAfter(asOf)).toList();
        if (bars.isEmpty()) {
            return null;
        }
        Candle last = bars.getLast();
        boolean monthly = "MONTHLY".equals(in.frequency());
        double close = last.close();
        Double c1m = change(bars, close, last.date().minusMonths(1));
        Double c3m = change(bars, close, last.date().minusMonths(3));
        Double c1y = change(bars, close, last.date().minusYears(1));
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (Candle b : bars) {
            if (b.date().isAfter(last.date().minusYears(1))) {
                high = Math.max(high, b.close());
                low = Math.min(low, b.close());
            }
        }
        Double rangePct = high > low ? (close - low) / (high - low) * 100 : null;
        boolean stale = last.date().isBefore(asOf.minusDays(monthly ? 75 : 10));
        Relevance r = RELEVANCE.getOrDefault(in.code(), new Relevance(Role.REFERENCE, null));
        String tone = stale ? "NEUTRAL" : tone(in.code(), r.role(), c3m);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", in.code());
        m.put("name", in.name());
        m.put("unit", in.unit());
        m.put("frequency", in.frequency());
        m.put("lastDate", last.date().toString());
        m.put("last", round(close, close >= 100 ? 2 : 4));
        m.put("change1mPct", round(c1m, 2));
        m.put("change3mPct", round(c3m, 2));
        m.put("change1yPct", round(c1y, 2));
        m.put("high52w", round(high, high >= 100 ? 2 : 4));
        m.put("low52w", round(low, low >= 100 ? 2 : 4));
        m.put("rangePct", round(rangePct, 0));
        m.put("role", r.role().name());
        m.put("relevance", r.text());
        m.put("tone", tone);
        m.put("stale", stale);
        m.put("summary", summary(in, r, tone, c3m, c1y, stale, last.date(), monthly));
        m.put("points", weekly(bars, last.date().minusYears(1)));
        return m;
    }

    /** POSITIVE / NEGATIVE / NEUTRAL of an instrument's 3-month move for Indonesia. */
    static String tone(String code, Role role, Double change3m) {
        if (change3m == null) {
            return "NEUTRAL";
        }
        switch (code) {
            case "USDIDR" -> {
                return change3m >= RUPIAH_WEAK_PCT ? "NEGATIVE" : change3m <= RUPIAH_STRONG_PCT ? "POSITIVE" : "NEUTRAL";
            }
            case "USDCNY" -> {
                return change3m >= YUAN_MOVE_PCT ? "NEGATIVE" : change3m <= -YUAN_MOVE_PCT ? "POSITIVE" : "NEUTRAL";
            }
            case "EURUSD" -> {
                return change3m >= EURO_MOVE_PCT ? "POSITIVE" : change3m <= -EURO_MOVE_PCT ? "NEGATIVE" : "NEUTRAL";
            }
            case "AUDJPY" -> {
                return change3m >= RISK_MOVE_PCT ? "POSITIVE" : change3m <= -RISK_MOVE_PCT ? "NEGATIVE" : "NEUTRAL";
            }
            default -> {
            }
        }
        return switch (role) {
            case EXPORT -> change3m >= COMMODITY_MOVE_PCT ? "POSITIVE" : change3m <= -COMMODITY_MOVE_PCT ? "NEGATIVE" : "NEUTRAL";
            case IMPORT -> change3m >= COMMODITY_MOVE_PCT ? "NEGATIVE" : change3m <= -COMMODITY_MOVE_PCT ? "POSITIVE" : "NEUTRAL";
            default -> "NEUTRAL";
        };
    }

    private static String summary(Input in, Relevance r, String tone, Double c3m, Double c1y, boolean stale,
                                  LocalDate lastDate, boolean monthly) {
        if (stale) {
            return in.name() + ": the latest stored " + (monthly ? "month" : "bar") + " is of " + lastDate
                    + ", too old to read the trend; update it on Ingestion › Price Ingestion.";
        }
        String moves = "3M " + pct(c3m) + ", 1Y " + pct(c1y)
                + (monthly ? " (monthly averages, World Bank)" : "");
        return moves + " - " + what(in.code(), r.role(), tone) + (r.text() == null ? "." : "; " + r.text() + ".");
    }

    /** What a tone means, in words: "a weaker rupiah: a headwind". */
    static String what(String code, Role role, String tone) {
        boolean positive = "POSITIVE".equals(tone);
        if (!positive && !"NEGATIVE".equals(tone)) {
            return "no strong move";
        }
        String reading = switch (code) {
            case "USDIDR" -> positive ? "a firmer rupiah" : "a weaker rupiah";
            case "USDCNY" -> positive ? "a firmer yuan" : "a weaker yuan";
            case "EURUSD" -> positive ? "a weaker dollar" : "a stronger dollar";
            case "AUDJPY" -> positive ? "risk appetite rising" : "risk aversion, carry trades unwinding";
            default -> role == Role.IMPORT ? positive ? "a cheaper import" : "a dearer import"
                    : positive ? "a stronger export price" : "a weaker export price";
        };
        return reading + (positive ? ": supportive" : ": a headwind");
    }

    /** Percent change of the close against the last bar on or before a day (null before the first bar). */
    private static Double change(List<Candle> bars, double close, LocalDate then) {
        Candle found = null;
        for (Candle b : bars) {
            if (b.date().isAfter(then)) {
                break;
            }
            found = b;
        }
        return found == null || found.close() <= 0 ? null : (close / found.close() - 1) * 100;
    }

    /** [epoch seconds, close]: the last close of each ISO week since {@code from} (a monthly series: every month). */
    static List<double[]> weekly(List<Candle> bars, LocalDate from) {
        Map<String, Candle> byWeek = new LinkedHashMap<>();
        for (Candle b : bars) {
            if (b.date().isBefore(from)) {
                continue;
            }
            String week = b.date().get(IsoFields.WEEK_BASED_YEAR) + "-" + b.date().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            byWeek.put(week, b);
        }
        List<double[]> out = new ArrayList<>();
        for (Candle b : byWeek.values()) {
            out.add(new double[] {b.date().atStartOfDay().toEpochSecond(ZoneOffset.UTC), b.close()});
        }
        return out;
    }

    /** "+5.2%", "n/a" */
    static String pct(Double v) {
        return v == null ? "n/a" : MacroOutlook.sp(v, 1) + "%";
    }

    private static Double round(Double v, int digits) {
        if (v == null || !Double.isFinite(v)) {
            return null;
        }
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
