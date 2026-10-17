package com.neracalab.backend.technical.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.neracalab.backend.technical.idx.IdxTickSize;

/**
 * A quick summary of the chart for each preset (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller): a stance, a buy
 * price, a sell price (target), a stop and the thesis, each derived by fixed rules from the indicator snapshot and the
 * daily indicators. Deterministic: no model is called, the same data always gives the same summary, and every price is
 * a valid price (an IDX stock: the tick of its band; an index: 0.01; a Forex pair or commodity: its tick size).
 * <p>
 * The rules are the presets' published styles reduced to the indicators the page shows:
 * <ul>
 *   <li><b>Murphy</b> - trend following with confirmation: the primary trend (SMA 200, golden / death cross), the
 *       intermediate trend (SMA 50), MACD, RSI, OBV and relative strength vote; buy a pullback to the nearest support
 *       (zone, retracement, moving average), sell at the next resistance, stop below the support.</li>
 *   <li><b>Keith Gill</b> - patient, contrarian accumulation: a base with contracting volatility, the position in the
 *       52-week range, the drawdown from the all-time high and buying vs selling volume; buy low in the base (or at the
 *       heaviest volume node), sell at the prior peak (52-week / all-time high), stop below the base.</li>
 *   <li><b>Paul Tudor Jones</b> - the 200-day rule and breakouts with strict risk: above the SMA 200, ADX with +DI /
 *       -DI, the 20 / 50-day channels, volatility; buy a break of the 20-day high, stop at the 20-day low or 2 ATR,
 *       target 5 times the risk.</li>
 *   <li><b>Druckenmiller</b> - leadership and the market's trend: IHSG above its SMA 200, breadth, relative strength vs
 *       IHSG and its percentile, 12-1 month momentum; buy the leader on a pullback (EMA 20, SMA 50), sell at the prior
 *       high, stop below the SMA 50. A Forex pair or commodity has no IHSG or leadership: its own primary trend and
 *       momentum.</li>
 * </ul>
 * Technical only: no macro data is read (the macro view is the Macro page).
 * Every price keeps stop &lt; buy &lt; sell. A missing indicator (limited history, no IHSG) does not vote.
 */
public final class PresetSummaries {

    /** Changes when a rule changes (summaries are computed on request, never stored). */
    public static final String RULES_VERSION = "2026-10-16.2";

    public static final String DISCLAIMER = "Rules-based summary of the indicators, computed by Neraca Lab from stored "
            + "daily prices (no AI model). For information only. Not investment advice.";

    /**
     * @param snapshot the listing's IndicatorSnapshot document (docs/indicator-snapshot.md)
     * @param daily    the daily indicators of the same bars (channels, EMA 20)
     * @param ticks    price rounding: the IDX tick table, 0.01 for an index, the instrument's tick size
     * @param type     STOCK, INDEX (IHSG, LQ45: no leadership among stocks, no liquidity caveats), FOREX or COMMODITY
     *                 (no IHSG comparison: their own trend)
     */
    public record Input(Map<String, Object> snapshot, IndicatorSet daily, IdxTickSize ticks, String type) {

        /** A stock, or the index ({@code index}). */
        public Input(Map<String, Object> snapshot, IndicatorSet daily, IdxTickSize ticks, boolean index) {
            this(snapshot, daily, ticks, index ? "INDEX" : "STOCK");
        }

        /** Not a stock: no relative strength vs IHSG, no IDX liquidity caveats. */
        public boolean index() {
            return !"STOCK".equals(type);
        }
    }

    /**
     * A price level with why it was chosen; {@code floor} = the lower edge of the zone / node / base it comes from (NaN:
     * none), {@code floorName} = what that edge is ("the support zone"), for the stop placed below it.
     */
    record Level(double price, String basis, double floor, String floorName) {

        Level(double price, String basis) {
            this(price, basis, Double.NaN, null);
        }

        /** The stop half an ATR below the floor, or {@code multiple} ATR below the price when there is none. */
        Level stop(Data d, double multiple) {
            if (ok(floor)) {
                return new Level(d.floor(floor - 0.5 * d.atrOr(0)), "half an ATR below " + floorName);
            }
            return new Level(d.floor(price - multiple * d.atrOr(0)), trimAtr(multiple) + " ATR below the entry");
        }
    }

    private PresetSummaries() {
    }

    /** The summaries of the four presets with what they were computed from. */
    public static Map<String, Object> summarize(Input input) {
        Data d = new Data(input);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchange", input.snapshot().get("exchange"));
        out.put("ticker", input.snapshot().get("ticker"));
        out.put("asOfDate", input.snapshot().get("asOfDate"));
        out.put("currency", input.snapshot().get("currency"));
        out.put("close", SnapshotCalculator.num(d.close));
        out.put("type", input.type());
        out.put("method", "rules");
        out.put("rulesVersion", RULES_VERSION);
        List<Map<String, Object>> summaries = new ArrayList<>();
        summaries.add(murphy(d).toMap(d));
        summaries.add(gill(d).toMap(d));
        summaries.add(ptj(d).toMap(d));
        summaries.add(druckenmiller(d).toMap(d));
        out.put("summaries", summaries);
        out.put("caveats", d.caveats());
        out.put("disclaimer", DISCLAIMER);
        return out;
    }

    // ------------------------------------------------------------------ presets

    static Summary murphy(Data d) {
        Summary s = new Summary("murphy", "Murphy", "Trend following with confirmation (Technical Analysis of the "
                + "Financial Markets): trade with the primary trend, buy pullbacks to support, sell at resistance");
        // primary trend
        if (ok(d.sma200)) {
            s.vote(d.close > d.sma200 ? 1 : -1, "Primary trend " + (d.close > d.sma200 ? "up" : "down") + ": close "
                    + pct(d.vsSma200) + " vs the SMA 200 (" + d.fmt(d.sma200) + ")");
        } else {
            s.info("No SMA 200 yet (limited history): the primary trend is unknown");
        }
        if (d.cross != null) {
            s.vote(d.cross.equals("GOLDEN") ? 1 : -1, (d.cross.equals("GOLDEN") ? "Golden cross" : "Death cross")
                    + ": SMA 50 " + (d.cross.equals("GOLDEN") ? "above" : "below") + " SMA 200"
                    + (d.crossDate == null ? "" : " since " + d.crossDate));
        }
        if (ok(d.sma50)) {
            s.vote(d.close > d.sma50 ? 1 : -1, "Intermediate trend " + (d.close > d.sma50 ? "up" : "down")
                    + ": close " + pct(d.vsSma50) + " vs the SMA 50 (" + d.fmt(d.sma50) + ")");
        }
        if (ok(d.macdHist)) {
            s.vote(d.macdHist > 0 ? 1 : -1, "MACD " + (d.macdHist > 0 ? "above" : "below") + " its signal line "
                    + "(histogram " + num2(d.macdHist) + ")");
        }
        if (ok(d.rsi)) {
            if (d.rsi >= 70) {
                s.vote(-0.5, "RSI " + num1(d.rsi) + ": overbought, a pullback is likely before a better entry");
            } else if (d.rsi <= 30) {
                s.vote(ok(d.sma200) && d.close > d.sma200 ? 0.5 : 0, "RSI " + num1(d.rsi) + ": oversold"
                        + (ok(d.sma200) && d.close > d.sma200 ? " within an uptrend: a buying opportunity" : ""));
            } else {
                s.info("RSI " + num1(d.rsi) + ": neither overbought nor oversold");
            }
        }
        if (ok(d.obvSlope)) {
            s.vote(d.obvSlope > 0 ? 1 : -1, "On-balance volume " + (d.obvSlope > 0 ? "rising: volume confirms the "
                    + "advance" : "falling: volume does not confirm"));
        }
        if (!d.index && ok(d.rsChange50)) {
            s.vote(d.rsChange50 > 0 ? 1 : -1, (d.rsChange50 > 0 ? "Outperforming" : "Underperforming") + " IHSG: "
                    + "relative strength " + pct(d.rsChange50) + " over 50 days");
        }
        s.maxScore = d.index ? 6 : 7;
        s.stance = s.score >= 3 ? "BUY" : s.score >= 1 ? "ACCUMULATE" : s.score > -2 ? "WAIT" : "AVOID";
        s.label = switch (s.stance) {
            case "BUY" -> "Buy pullbacks: the trend and its confirmations agree";
            case "ACCUMULATE" -> "Buy on weakness: the trend is up, the confirmations are mixed";
            case "WAIT" -> "Wait: the trend and the indicators disagree";
            default -> "Avoid: downtrend; sell rallies into resistance";
        };

        List<Level> supports = new ArrayList<>(d.supportZonesBelow());
        supports.addAll(d.retracementsBelow());
        if (ok(d.sma50) && d.close > d.sma50) {
            supports.add(new Level(d.round(d.sma50), "the SMA 50"));
        }
        if (ok(d.sma200) && d.close > d.sma200) {
            supports.add(new Level(d.round(d.sma200), "the SMA 200"));
        }
        Level buy;
        if (s.stance.equals("BUY") || s.stance.equals("ACCUMULATE")) {
            buy = nearestBelow(supports, d.close, d.close * 0.88)
                    .orElse(d.atrLevel(-1, "1 ATR below the close (no support within 12%)"));
        } else {
            buy = d.strongestSupportBelow()
                    .or(() -> d.week52Low < d.close ? Optional.of(new Level(d.week52Low, "the 52-week low")) : Optional.empty())
                    .orElse(d.atrLevel(-2, "2 ATR below the close"));
            buy = new Level(buy.price(), buy.basis() + ", only after the trend turns up (a close back above the SMA 50)",
                    buy.floor(), buy.floorName());
        }
        List<Level> resistances = new ArrayList<>(d.resistanceZonesAbove());
        resistances.addAll(d.retracementsAbove());
        if (ok(d.sma200) && d.close < d.sma200) {
            resistances.add(new Level(d.round(d.sma200), "the SMA 200 overhead"));
        }
        if (d.week52High > d.close) {
            resistances.add(new Level(d.week52High, "the 52-week high"));
        }
        Level sell = nearestAbove(resistances, Math.max(d.close, buy.price()) + 0.5 * d.atrOr(0))
                .orElse(null);
        Level stop = buy.stop(d, 1.5);
        s.prices(d, buy, "LIMIT", sell, stop, 3);
        s.thesis = thesis(s, d, "Murphy reads a " + trendWord(d) + "; ");
        return s;
    }

    static Summary gill(Data d) {
        Summary s = new Summary("gill", "Keith Gill", "Patient, contrarian accumulation: buy what is out of favour once a "
                + "base forms and buyers return, hold for the re-rating to the prior peak");
        if (d.baseDetected) {
            s.vote(1.5, "A base of " + d.baseDays + " days (" + d.fmt(d.baseLow) + " - " + d.fmt(d.baseHigh) + ", depth "
                    + num1(d.baseDepth) + "%) with contracting volatility: accumulation");
        } else {
            s.info("No base: the price has not settled into a tight range yet");
        }
        if (ok(d.week52High) && ok(d.week52Low) && d.week52High > d.week52Low) {
            double pos = (d.close - d.week52Low) / (d.week52High - d.week52Low);
            if (pos <= 0.3) {
                s.vote(1, "Near the 52-week low (" + Math.round(pos * 100) + "% of the range): priced for pessimism");
            } else if (pos >= 0.85) {
                s.vote(-0.5, "Near the 52-week high (" + Math.round(pos * 100) + "% of the range): little margin of safety");
            } else {
                s.info("At " + Math.round(pos * 100) + "% of the 52-week range " + d.fmt(d.week52Low) + " - "
                        + d.fmt(d.week52High));
            }
        }
        if (ok(d.athDrawdown) && d.athDrawdown <= -50) {
            s.vote(1, num1(-d.athDrawdown) + "% below its all-time high " + d.fmt(d.athPrice) + ": deep-value territory");
        }
        if (ok(d.upDownRatio)) {
            if (d.upDownRatio >= 1.1) {
                s.vote(1, "Up-day volume " + num2(d.upDownRatio) + "x down-day volume (50 days): buyers in control");
            } else if (d.upDownRatio <= 0.9) {
                s.vote(-1, "Up-day volume only " + num2(d.upDownRatio) + "x down-day volume (50 days): sellers in control");
            }
        }
        if (d.spikesUp + d.spikesDown > 0) {
            double w = d.spikesUp > d.spikesDown ? 0.5 : d.spikesDown > d.spikesUp ? -0.5 : 0;
            s.vote(w, "Volume spikes (60 days): " + d.spikesUp + " on up days, " + d.spikesDown + " on down days"
                    + (w > 0 ? ": accumulation" : w < 0 ? ": distribution" : ""));
        }
        if (ok(d.obvSlope)) {
            s.vote(d.obvSlope > 0 ? 0.5 : -0.5, "On-balance volume " + (d.obvSlope > 0 ? "rising" : "falling"));
        }
        if (ok(d.sma200)) {
            if (d.close > d.sma200) {
                s.vote(0.5, "Back above the SMA 200 (" + d.fmt(d.sma200) + "): the turn is under way");
            } else if (ok(d.sma200Slope) && d.sma200Slope < 0) {
                s.vote(-0.5, "Below a falling SMA 200 (" + d.fmt(d.sma200) + "): still in a downtrend");
            }
        }
        if (!d.upcoming.isEmpty()) {
            s.info("Upcoming: " + String.join(", ", d.upcoming));
        }
        s.maxScore = 6;
        s.stance = s.score >= 3 ? "BUY" : s.score >= 1.5 ? "ACCUMULATE" : s.score >= -1 ? "WAIT" : "AVOID";
        s.label = switch (s.stance) {
            case "BUY" -> "Accumulate: a base with buying volume";
            case "ACCUMULATE" -> "Build a starter position near support";
            case "WAIT" -> "Watch: no base or accumulation yet";
            default -> "Avoid: still distributing or making new lows";
        };

        Level buy;
        String base = "the base of " + d.baseDays + " days " + d.fmt(d.baseLow) + " - " + d.fmt(d.baseHigh);
        if (d.baseDetected && d.close > d.baseHigh) {
            buy = new Level(d.baseHigh, "a retest of the base high (the breakout level)", d.baseLow, "the base low");
        } else if (d.baseDetected) {
            double lowerQuarter = d.round(d.baseLow + 0.25 * (d.baseHigh - d.baseLow));
            buy = lowerQuarter < d.close
                    ? new Level(lowerQuarter, "the lower quarter of " + base, d.baseLow, "the base low")
                    : new Level(d.floor(d.close), "the close: already in the lower quarter of " + base, d.baseLow,
                    "the base low");
        } else {
            buy = d.heaviestNodeBelow()
                    .or(() -> nearestBelow(d.supportZonesBelow(), d.close, 0))
                    .or(() -> d.week52Low < d.close ? Optional.of(new Level(d.week52Low, "the 52-week low")) : Optional.empty())
                    .orElse(d.atrLevel(-1, "1 ATR below the close"));
        }
        Level sell;
        if (d.week52High > d.close * 1.05) {
            sell = new Level(d.week52High, "the 52-week high (the prior peak)");
        } else if (d.athPrice > d.close * 1.05) {
            sell = new Level(d.athPrice, "the all-time high");
        } else {
            sell = nearestAbove(d.resistanceZonesAbove(), d.close).orElse(d.atrLevel(3, "3 ATR above the close"));
        }
        Level stop = buy.stop(d, 2);
        s.prices(d, buy, "LIMIT", sell, stop, 3);
        s.thesis = thesis(s, d, d.index ? "Gill looks for a beaten-down market where buyers return; "
                : "Gill looks for a beaten-down stock where buyers return; ");
        return s;
    }

    static Summary ptj(Data d) {
        Summary s = new Summary("ptj", "Paul Tudor Jones", "The 200-day rule and breakouts with strict risk: nothing good "
                + "happens below the 200-day average; play defense, cut losers fast, aim for 5 times the risk");
        boolean trendKnown = ok(d.sma200);
        boolean above = trendKnown ? d.close > d.sma200 : ok(d.sma50) && d.close > d.sma50;
        if (trendKnown) {
            s.vote(above ? 2 : -2, (above ? "Above" : "Below") + " the 200-day average (" + d.fmt(d.sma200) + ", "
                    + pct(d.vsSma200) + ")");
        } else {
            s.info("No SMA 200 yet (limited history): the SMA 50 stands in for the 200-day rule");
        }
        boolean trending = ok(d.adx) && d.adx >= 20;
        boolean upDi = ok(d.plusDi) && ok(d.minusDi) && d.plusDi > d.minusDi;
        if (ok(d.adx)) {
            if (d.adx >= 25) {
                s.vote(upDi ? 1 : -1, "ADX " + num1(d.adx) + " with " + (upDi ? "+DI above -DI: a strong uptrend"
                        : "-DI above +DI: a strong downtrend"));
            } else if (d.adx >= 20) {
                s.vote(upDi ? 0.5 : -0.5, "ADX " + num1(d.adx) + ": a trend is forming (" + (upDi ? "+DI" : "-DI") + " leads)");
            } else {
                s.info("ADX " + num1(d.adx) + ": no trend, the price is range-bound");
            }
        }
        if (ok(d.prior20High) && d.close > d.prior20High) {
            s.vote(1, "Broke the 20-day high " + d.fmt(d.prior20High) + " today");
        } else if (ok(d.prior20Low) && d.close < d.prior20Low) {
            s.vote(-1, "Broke the 20-day low " + d.fmt(d.prior20Low) + " today");
        } else if (ok(d.prior20High)) {
            s.info("Inside the 20-day channel " + d.fmt(d.prior20Low) + " - " + d.fmt(d.prior20High));
        }
        if (ok(d.prior50High) && d.close >= d.prior50High * 0.98) {
            s.vote(0.5, "At the top of the 50-day channel (" + d.fmt(d.prior50High) + ")");
        }
        if (ok(d.atrPercentile) && d.atrPercentile >= 80) {
            s.vote(-0.5, "Volatility high (ATR in the " + Math.round(d.atrPercentile) + "th percentile of the year): "
                    + "trade smaller");
        }
        s.maxScore = 4.5;
        if (!above) {
            s.stance = "AVOID";
        } else if (trending && upDi) {
            s.stance = s.score >= 2.5 ? "BUY" : "ACCUMULATE";
        } else if (!trending) {
            s.stance = "WAIT";
        } else {
            s.stance = "ACCUMULATE";
        }
        s.label = switch (s.stance) {
            case "BUY" -> "Buy the breakout: above the 200-day in a trending market";
            case "ACCUMULATE" -> "Above the 200-day, trend not confirmed: buy only on a breakout";
            case "WAIT" -> "Range-bound: wait for a breakout";
            default -> "Below the 200-day: stay out, defense first";
        };

        double breakout = ok(d.prior20High) ? d.prior20High : d.close;
        Level buy;
        String buyType = "STOP";
        if (s.stance.equals("AVOID")) {
            double reclaim = Math.max(ok(d.sma200) ? d.sma200 : ok(d.sma50) ? d.sma50 : d.close, breakout);
            buy = new Level(d.above(reclaim), "a buy-stop above " + (ok(d.sma200) ? "the SMA 200" : "the SMA 50")
                    + " and the 20-day high: only after the price reclaims the 200-day");
        } else if (d.close > breakout) {
            buy = new Level(d.round(d.close), "today's close: it broke the 20-day high " + d.fmt(breakout));
            buyType = "LIMIT";
        } else {
            buy = new Level(d.above(breakout), "a buy-stop just above the 20-day high " + d.fmt(breakout));
        }
        double risk2Atr = buy.price() - 2 * d.atrOr(0);
        Level stop = ok(d.prior20Low) && d.prior20Low < buy.price() && d.prior20Low > risk2Atr
                ? new Level(d.floor(d.prior20Low) - d.tick(d.prior20Low), "just below the 20-day low " + d.fmt(d.prior20Low))
                : new Level(d.floor(risk2Atr), "2 ATR below the entry");
        double risk = buy.price() - stop.price();
        Level sell = risk > 0 ? new Level(d.floor(buy.price() + 5 * risk), "5 times the risk (PTJ's 5:1 reward to risk)")
                : null;
        s.prices(d, buy, buyType, sell, stop, 5);
        s.thesis = thesis(s, d, "Tudor Jones only plays the long side above the 200-day; ")
                + (ok(d.prior20Low) ? " Trail the stop to the 20-day low as the trend runs." : "");
        return s;
    }

    static Summary druckenmiller(Data d) {
        Summary s = new Summary("druckenmiller", "Druckenmiller", d.instrument
                ? "Trend and momentum: take the side of the strongest trend in " + d.name + ", size up when momentum "
                + "agrees, get out when the trend breaks"
                : "Leadership and the market: own the strongest stocks while the market's trend supports them, "
                + "concentrate in the leaders, get out when the thesis breaks");
        double max;
        if (d.instrument) {
            // no IHSG and no leadership among stocks: its own primary trend
            max = 1.5;
            if (ok(d.sma200)) {
                s.vote(d.close > d.sma200 ? 1.5 : -1.5, "Primary trend " + (d.close > d.sma200 ? "up" : "down")
                        + ": " + pct(d.vsSma200) + " vs the SMA 200 (" + d.fmt(d.sma200) + ")");
            } else {
                s.info("No SMA 200 yet (limited history): the primary trend is unknown");
            }
        } else {
            max = 1.5;
            if (d.indexAbove != null) {
                s.vote(d.indexAbove ? 1 : -1, "IHSG " + (d.indexAbove ? "above" : "below") + " its 200-day average ("
                        + pct(d.indexVs200) + "): the market is in " + (d.indexAbove ? "an uptrend" : "a downtrend"));
            } else {
                s.info("No IHSG regime: run the IHSG ingestion");
            }
            if (ok(d.breadth)) {
                if (d.breadth >= 55) {
                    s.vote(0.5, "Breadth: " + num1(d.breadth) + "% of the stored listings above their SMA 200: broad participation");
                } else if (d.breadth <= 45) {
                    s.vote(-0.5, "Breadth: only " + num1(d.breadth) + "% of the stored listings above their SMA 200: a narrow market");
                } else {
                    s.info("Breadth: " + num1(d.breadth) + "% of the stored listings above their SMA 200");
                }
            }
        }
        if (!d.index) {
            max += 2.5;
            if (ok(d.pct6)) {
                if (d.pct6 >= 70) {
                    s.vote(1, "A leader: 6-month return in the " + Math.round(d.pct6) + "th percentile of the stored listings");
                } else if (d.pct6 <= 30) {
                    s.vote(-1, "A laggard: 6-month return in the " + Math.round(d.pct6) + "th percentile of the stored listings");
                }
            }
            if (ok(d.pct12)) {
                if (d.pct12 >= 70) {
                    s.vote(0.5, "12-month return in the " + Math.round(d.pct12) + "th percentile");
                } else if (d.pct12 <= 30) {
                    s.vote(-0.5, "12-month return in the " + Math.round(d.pct12) + "th percentile");
                }
            }
            if (ok(d.rsChange50)) {
                s.vote(d.rsChange50 > 0 ? 1 : -1, (d.rsChange50 > 0 ? "Outperforming" : "Underperforming")
                        + " IHSG over 50 days (relative strength " + pct(d.rsChange50) + ")");
            }
        }
        double momentum = d.instrument ? 1 : 0.5;
        max += momentum + 0.5;
        if (ok(d.mom12_1)) {
            s.vote(d.mom12_1 > 0 ? momentum : -momentum, "12-1 month momentum " + pct(d.mom12_1));
        }
        if (ok(d.sma50)) {
            s.vote(d.close > d.sma50 ? 0.5 : -0.5, (d.close > d.sma50 ? "Above" : "Below") + " the SMA 50 ("
                    + d.fmt(d.sma50) + ")");
        }
        // a stock 5, an index 2.5, an instrument 3: buy from half of it, avoid below -1/6 of it
        s.maxScore = max;
        double share = max == 0 ? 0 : s.score / max;
        s.stance = share >= 0.5 ? "BUY" : share >= 1.0 / 6 ? "ACCUMULATE" : share >= -1.0 / 6 ? "WAIT" : "AVOID";
        if (d.instrument) {
            s.label = switch (s.stance) {
                case "BUY" -> "Ride the trend: " + d.name + " is in an uptrend with momentum behind it";
                case "ACCUMULATE" -> "Lean long " + d.name + ": the trend is up, momentum is mixed";
                case "WAIT" -> "Neutral: wait for the trend and momentum of " + d.name + " to line up";
                default -> "Avoid: " + d.name + " is in a downtrend";
            };
        } else {
            s.label = switch (s.stance) {
                case "BUY" -> d.index ? "Risk on: the market's trend and momentum are up" : "Own the leader: relative "
                        + "strength with the market behind it";
                case "ACCUMULATE" -> d.index ? "Lean long: the trend is mixed" : "A leader in a mixed market: build gradually";
                case "WAIT" -> d.index ? "Neutral: wait for the trend to turn" : "Wait: leadership or market support is missing";
                default -> d.index ? "Risk off: the market is in a downtrend" : "Avoid: a laggard, or the market is falling";
            };
        }

        Level buy;
        String buyType = "LIMIT";
        if ((s.stance.equals("WAIT") || s.stance.equals("AVOID")) && ok(d.sma50) && d.close < d.sma50) {
            buy = new Level(d.above(d.sma50), d.instrument ? "a buy-stop above the SMA 50: only once the price reclaims it"
                    : d.index ? "a buy-stop above the SMA 50: only once the index reclaims it"
                    : "a buy-stop above the SMA 50: only once it reclaims it and leads IHSG again");
            buyType = "STOP";
        } else {
            List<Level> pullbacks = new ArrayList<>(d.supportZonesBelow());
            if (ok(d.ema20) && d.close > d.ema20) {
                pullbacks.add(new Level(d.round(d.ema20), "the EMA 20"));
            }
            if (ok(d.sma50) && d.close > d.sma50) {
                pullbacks.add(new Level(d.round(d.sma50), "the SMA 50"));
            }
            double limit = s.stance.equals("BUY") || s.stance.equals("ACCUMULATE") ? 0.90 : 0.85;
            buy = nearestBelow(pullbacks, d.close, d.close * limit)
                    .orElse(d.atrLevel(-0.5, "half an ATR below the close (leaders rarely pull back far)"));
        }
        Level sell;
        if (d.week52High > d.close * 1.03) {
            sell = new Level(d.week52High, "the 52-week high");
        } else if (d.athPrice > d.close * 1.03) {
            sell = new Level(d.athPrice, "the all-time high");
        } else {
            sell = d.atrLevel(3, "3 ATR above the close (at new highs: trail the stop rather than sell)");
        }
        Level stop = ok(d.sma50) && d.sma50 < buy.price()
                ? new Level(d.floor(d.sma50 - d.atrOr(0)), "1 ATR below the SMA 50")
                : buy.stop(d, 2);
        s.prices(d, buy, buyType, sell, stop, 3);
        s.thesis = thesis(s, d, d.instrument ? "Druckenmiller trades " + d.name + " with its trend and momentum; "
                : d.index ? "Druckenmiller reads the market through its trend and momentum; "
                : "Druckenmiller owns leaders while the market supports them; ");
        return s;
    }

    private static String trendWord(Data d) {
        if (!ok(d.sma200)) {
            return "short history";
        }
        if (!ok(d.sma50)) {
            return d.close > d.sma200 ? "primary uptrend" : "primary downtrend";
        }
        return d.close > d.sma200 ? (d.close > d.sma50 ? "primary and intermediate uptrend" : "primary uptrend in a pullback")
                : (ok(d.sma50) && d.close > d.sma50 ? "primary downtrend with a rebound" : "primary and intermediate downtrend");
    }

    /** "{intro}{stance label}. Buy at 1,250 (...), sell at 1,420 (...), stop 1,180 (...)." */
    private static String thesis(Summary s, Data d, String intro) {
        StringBuilder t = new StringBuilder(intro).append(lowerFirst(s.label)).append('.');
        if (s.buy != null) {
            t.append(s.buyType.equals("STOP") ? " Buy at or above " : " Buy at or below ").append(d.fmt(s.buy.price()))
                    .append(" (").append(s.buy.basis()).append(")");
            if (s.sell != null) {
                t.append(", sell at ").append(d.fmt(s.sell.price())).append(" (").append(s.sell.basis()).append(")");
            }
            if (s.stop != null) {
                t.append(", stop ").append(d.fmt(s.stop.price())).append(" (").append(s.stop.basis()).append(")");
            }
            t.append('.');
        }
        String strongest = s.points.stream().filter(p -> !p.tone().equals("NEUTRAL"))
                .max(Comparator.comparingDouble(p -> Math.abs(p.weight()))).map(Point::text).orElse(null);
        if (strongest != null) {
            t.append(" Key evidence: ").append(lowerFirst(strongest)).append('.');
        }
        return t.toString();
    }

    // ------------------------------------------------------------------ levels

    /** The highest level below {@code ref} and at or above {@code min}. */
    static Optional<Level> nearestBelow(List<Level> levels, double ref, double min) {
        return levels.stream().filter(l -> ok(l.price()) && l.price() < ref && l.price() >= min)
                .max(Comparator.comparingDouble(Level::price));
    }

    /** The lowest level above {@code ref}. */
    static Optional<Level> nearestAbove(List<Level> levels, double ref) {
        return levels.stream().filter(l -> ok(l.price()) && l.price() > ref).min(Comparator.comparingDouble(Level::price));
    }

    // ------------------------------------------------------------------ summary

    record Point(String tone, double weight, String text) {
    }

    /** One preset's summary as it is built. */
    static final class Summary {
        final String preset;
        final String name;
        final String style;
        final List<Point> points = new ArrayList<>();
        double score;
        double maxScore;
        String stance;
        String label;
        Level buy;
        String buyType = "LIMIT";
        Level sell;
        Level stop;
        Double rewardRisk;
        String thesis;

        Summary(String preset, String name, String style) {
            this.preset = preset;
            this.name = name;
            this.style = style;
        }

        void vote(double weight, String text) {
            score += weight;
            points.add(new Point(weight > 0 ? "POSITIVE" : weight < 0 ? "NEGATIVE" : "NEUTRAL", weight, text));
        }

        void info(String text) {
            points.add(new Point("NEUTRAL", 0, text));
        }

        /**
         * Sets the prices keeping stop &lt; buy &lt; sell: a target at or below the entry becomes {@code fallbackAtr}
         * ATR above it, a stop at or above the entry 1 ATR below it. Without an ATR (fewer than 15 bars) nothing is set.
         */
        void prices(Data d, Level buy, String buyType, Level sell, Level stop, double fallbackAtr) {
            if (!ok(d.atr) || buy == null || !ok(buy.price()) || buy.price() <= 0) {
                return;
            }
            this.buy = buy;
            this.buyType = buyType;
            if (sell == null || !ok(sell.price()) || sell.price() <= buy.price()) {
                sell = new Level(d.ceil(buy.price() + fallbackAtr * d.atr), num1(fallbackAtr).replace(".0", "")
                        + " ATR above the entry (no level above it)");
            }
            if (stop == null || !ok(stop.price()) || stop.price() >= buy.price()) {
                stop = new Level(d.floor(buy.price() - d.atr), "1 ATR below the entry");
            }
            if (stop.price() >= buy.price()) {
                stop = new Level(buy.price() - d.tick(buy.price()), "one tick below the entry");
            }
            this.sell = sell;
            this.stop = stop.price() > 0 ? stop : null;
            if (this.stop != null) {
                rewardRisk = Math.round((sell.price() - buy.price()) / (buy.price() - this.stop.price()) * 100) / 100.0;
            }
        }

        Map<String, Object> toMap(Data d) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("preset", preset);
            m.put("name", name);
            m.put("style", style);
            m.put("stance", stance);
            m.put("stanceLabel", label);
            m.put("score", Math.round(score * 10) / 10.0);
            m.put("maxScore", maxScore);
            m.put("buy", price(d, buy, buyType));
            m.put("sell", price(d, sell, "LIMIT"));
            m.put("stopLoss", price(d, stop, "STOP"));
            m.put("rewardRisk", rewardRisk);
            m.put("thesis", thesis);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Point p : points) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("tone", p.tone());
                pm.put("text", p.text());
                list.add(pm);
            }
            m.put("points", list);
            return m;
        }

        private static Map<String, Object> price(Data d, Level level, String type) {
            if (level == null) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("price", SnapshotCalculator.num(level.price()));
            m.put("type", type);
            m.put("basis", level.basis());
            m.put("distancePct", SnapshotCalculator.num(Stats.pctChange(level.price(), d.close)));
            return m;
        }
    }

    // ------------------------------------------------------------------ data

    /** The values the rules read, from the snapshot (NaN / null when missing). */
    static final class Data {
        /** not a stock (an index or an instrument) */
        final boolean index;
        /** a Forex pair or commodity */
        final boolean instrument;
        /** STOCK, INDEX, FOREX or COMMODITY */
        final String type;
        final String ticker;
        final String name;
        final IdxTickSize ticks;
        final double close;
        final double atr;
        final double sma50;
        final double sma200;
        final double ema20;
        final double vsSma50;
        final double vsSma200;
        final double sma200Slope;
        final String cross;
        final String crossDate;
        final double adx;
        final double plusDi;
        final double minusDi;
        final double rsi;
        final double macdHist;
        final double mom12_1;
        final double atrPercentile;
        final double obvSlope;
        final double upDownRatio;
        final int spikesUp;
        final int spikesDown;
        final double week52High;
        final double week52Low;
        final double athPrice;
        final double athDrawdown;
        final boolean baseDetected;
        final double baseHigh;
        final double baseLow;
        final int baseDays;
        final double baseDepth;
        final double rsChange50;
        final double pct6;
        final double pct12;
        final Boolean indexAbove;
        final double indexVs200;
        final double breadth;
        final double prior20High;
        final double prior20Low;
        final double prior50High;
        final List<Map<String, Object>> zones;
        final Map<String, Object> retracement;
        final List<Map<String, Object>> nodes;
        final List<String> upcoming = new ArrayList<>();
        private final Map<String, Object> doc;

        @SuppressWarnings("unchecked")
        Data(Input in) {
            this.doc = in.snapshot();
            this.index = in.index();
            this.type = in.type();
            this.instrument = "FOREX".equals(in.type()) || "COMMODITY".equals(in.type());
            this.ticker = String.valueOf(doc.get("ticker"));
            this.name = doc.get("companyName") == null ? ticker : String.valueOf(doc.get("companyName"));
            this.ticks = in.ticks();
            close = d("price.close");
            atr = d("volatility.atr14");
            sma50 = d("trend.sma50");
            sma200 = d("trend.sma200");
            ema20 = d("trend.ema20");
            vsSma50 = d("trend.priceVsMaPct.sma50");
            vsSma200 = d("trend.priceVsMaPct.sma200");
            sma200Slope = d("trend.sma200Slope20dPct");
            cross = (String) get("trend.sma50Vs200Cross.state");
            crossDate = (String) get("trend.sma50Vs200Cross.lastCrossDate");
            adx = d("trend.adx14");
            plusDi = d("trend.plusDi14");
            minusDi = d("trend.minusDi14");
            rsi = d("momentum.rsi14");
            macdHist = d("momentum.macd.histogram");
            mom12_1 = d("momentum.momentum12_1Pct");
            atrPercentile = d("volatility.atrPctPercentile252");
            obvSlope = d("volume.obvSlope20PerAvgVolume");
            upDownRatio = d("volume.upDownVolumeRatio50");
            spikesUp = (int) orZero(d("volume.volumeSpikes60.upDays"));
            spikesDown = (int) orZero(d("volume.volumeSpikes60.downDays"));
            week52High = d("structure.week52.high");
            week52Low = d("structure.week52.low");
            athPrice = d("structure.allTimeHigh.price");
            athDrawdown = d("structure.allTimeHigh.drawdownPct");
            baseDetected = Boolean.TRUE.equals(get("structure.base.detected"));
            baseHigh = d("structure.base.high");
            baseLow = d("structure.base.low");
            baseDays = (int) orZero(d("structure.base.days"));
            baseDepth = d("structure.base.depthPct");
            rsChange50 = d("relativeStrength.ratioChange50Pct");
            pct6 = d("relativeStrength.percentile6m");
            pct12 = d("relativeStrength.percentile12m");
            indexAbove = (Boolean) get("marketRegime.indexAboveSma200");
            indexVs200 = d("marketRegime.indexVsSma200Pct");
            breadth = d("marketRegime.breadthAbove200Pct");
            IndicatorSet ind = in.daily();
            prior20High = ind == null ? Double.NaN : ind.ago("channel20.high", 1);
            prior20Low = ind == null ? Double.NaN : ind.ago("channel20.low", 1);
            prior50High = ind == null ? Double.NaN : ind.ago("channel50.high", 1);
            Object z = get("structure.zones");
            zones = z instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            Object r = get("structure.retracement");
            retracement = r instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            Object n = get("structure.volumeProfile.topNodes");
            nodes = n instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            Object up = get("events.upcoming");
            if (up instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> e) {
                        upcoming.add(e.get("type") + " " + e.get("date"));
                    }
                }
            }
        }

        Object get(String path) {
            Object cur = doc;
            for (String key : path.split("\\.")) {
                if (!(cur instanceof Map<?, ?> m)) {
                    return null;
                }
                cur = m.get(key);
            }
            return cur;
        }

        double d(String path) {
            return get(path) instanceof Number n ? n.doubleValue() : Double.NaN;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list(String path) {
            return get(path) instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        }

        List<Level> supportZonesBelow() {
            List<Level> out = new ArrayList<>();
            for (Map<String, Object> z : zones) {
                double low = num(z.get("low"));
                double high = num(z.get("high"));
                double mid = num(z.get("mid"));
                if (!ok(mid) || mid >= close) {
                    continue;
                }
                double top = ok(high) && high < close ? high : mid;
                out.add(new Level(top, "the support zone " + fmt(low) + " - " + fmt(high) + " (" + z.get("touches")
                        + " touches)", low, "the support zone"));
            }
            return out;
        }

        List<Level> resistanceZonesAbove() {
            List<Level> out = new ArrayList<>();
            for (Map<String, Object> z : zones) {
                double low = num(z.get("low"));
                double high = num(z.get("high"));
                double mid = num(z.get("mid"));
                if (!ok(mid) || mid <= close) {
                    continue;
                }
                double bottom = ok(low) && low > close ? low : mid;
                out.add(new Level(bottom, "the resistance zone " + fmt(low) + " - " + fmt(high) + " (" + z.get("touches")
                        + " touches)"));
            }
            return out;
        }

        /** The support zone below the close with the most touches (the nearer on a tie). */
        Optional<Level> strongestSupportBelow() {
            Map<String, Object> best = null;
            for (Map<String, Object> z : zones) {
                double mid = num(z.get("mid"));
                if (!ok(mid) || mid >= close) {
                    continue;
                }
                if (best == null || num(z.get("touches")) > num(best.get("touches"))
                        || (num(z.get("touches")) == num(best.get("touches")) && mid > num(best.get("mid")))) {
                    best = z;
                }
            }
            if (best == null) {
                return Optional.empty();
            }
            double high = num(best.get("high"));
            double mid = num(best.get("mid"));
            return Optional.of(new Level(ok(high) && high < close ? high : mid, "the strongest support zone "
                    + fmt(num(best.get("low"))) + " - " + fmt(high) + " (" + best.get("touches") + " touches)",
                    num(best.get("low")), "the support zone"));
        }

        @SuppressWarnings("unchecked")
        private List<Level> retracements(boolean below) {
            List<Level> out = new ArrayList<>();
            if (retracement == null || !(retracement.get("levels") instanceof List<?> levels)) {
                return out;
            }
            String direction = String.valueOf(retracement.get("direction"));
            Map<String, Object> start = (Map<String, Object>) retracement.get("swingStart");
            Map<String, Object> end = (Map<String, Object>) retracement.get("swingEnd");
            for (Object o : levels) {
                Map<String, Object> l = (Map<String, Object>) o;
                double price = num(l.get("price"));
                double ratio = num(l.get("ratio"));
                if (!ok(price) || (below ? price >= close : price <= close)) {
                    continue;
                }
                // pullbacks of an up swing are supports; rebounds of a down swing are resistances
                if (below && !direction.equals("UP") || !below && !direction.equals("DOWN")) {
                    continue;
                }
                out.add(new Level(price, trim(ratio * 100) + "% retracement of the " + direction.toLowerCase(Locale.ROOT)
                        + " swing " + fmt(num(start.get("price"))) + " → " + fmt(num(end.get("price")))));
            }
            return out;
        }

        List<Level> retracementsBelow() {
            return retracements(true);
        }

        List<Level> retracementsAbove() {
            return retracements(false);
        }

        /** The volume node below the close with the largest share of the volume. */
        Optional<Level> heaviestNodeBelow() {
            Map<String, Object> best = null;
            for (Map<String, Object> node : nodes) {
                double price = num(node.get("price"));
                if (!ok(price) || price >= close) {
                    continue;
                }
                if (best == null || num(node.get("sharePct")) > num(best.get("sharePct"))) {
                    best = node;
                }
            }
            if (best == null) {
                return Optional.empty();
            }
            return Optional.of(new Level(num(best.get("price")), "the heaviest volume node below the close ("
                    + num1(num(best.get("sharePct"))) + "% of the volume of the last 120 days)",
                    floor(num(best.get("low"))), "the volume node"));
        }

        /** {@code multiple} ATR from the close, rounded down (below) or up (above). */
        Level atrLevel(double multiple, String basis) {
            double a = atrOr(close * 0.02);
            double v = close + multiple * a;
            return new Level(multiple < 0 ? floor(v) : ceil(v), basis);
        }

        double atrOr(double fallback) {
            return ok(atr) ? atr : fallback;
        }

        double tick(double price) {
            return ticks.tick(price);
        }

        double round(double price) {
            return ticks.round(price);
        }

        double floor(double price) {
            return ticks.floor(price);
        }

        double ceil(double price) {
            return ticks.ceil(price);
        }

        /** The first valid price above {@code price}. */
        double above(double price) {
            double p = ticks.ceil(price);
            return p > price ? p : ticks.ceil(p + ticks.tick(p) / 2);
        }

        /** A price with the decimals of its tick (an IDX price in whole rupiah, an index with 2, USD/JPY with 3). */
        String fmt(double price) {
            if (!ok(price)) {
                return "n/a";
            }
            int decimals = Math.max(0, java.math.BigDecimal.valueOf(ticks.tick(price)).stripTrailingZeros().scale());
            if (index && !instrument) {
                decimals = Math.max(decimals, 2);
            }
            return String.format(Locale.US, "%,." + decimals + "f", price);
        }

        List<String> caveats() {
            List<String> out = new ArrayList<>();
            if (Boolean.TRUE.equals(get("history.limited"))) {
                out.add("Limited history (" + (int) orZero(d("history.tradingDays")) + " trading days): long-period "
                        + "indicators are missing, so the summaries rest on less evidence.");
            }
            if (!ok(atr)) {
                out.add("Fewer than 15 trading days: no ATR, so no prices are given.");
            }
            if (instrument && orZero(d("volume.avgVolume20")) == 0) {
                out.add("No traded volume for " + name + " (an over-the-counter market): the volume evidence (OBV, "
                        + "volume profile, up / down volume) is skipped.");
            }
            if (!index) {
                double value = d("risk.avgValueTraded20");
                if (ok(value) && value < 1e9) {
                    out.add("Thin liquidity: Rp " + String.format(Locale.US, "%,.0f", value / 1e6)
                            + " million traded per day on average (20 days); prices may be hard to fill.");
                }
                int ar = (int) (orZero(d("risk.araCount60")) + orZero(d("risk.arbCount60")));
                if (ar > 0) {
                    out.add(ar + " auto-rejection day(s) (ARA / ARB) in the last 60 sessions: the daily limit may block "
                            + "orders.");
                }
                if (Boolean.TRUE.equals(get("risk.possiblySuspended"))) {
                    out.add("Possibly suspended (missing sessions or no volume on the last 3 bars): check the exchange.");
                }
                double stale = d("risk.sessionsAfterLastBar");
                if (ok(stale) && stale > 0) {
                    out.add("The stored prices are " + (int) stale + " IHSG session(s) old: update the prices first.");
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean ok(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static double orZero(double v) {
        return ok(v) ? v : 0;
    }

    /** "Buy pullbacks" -> "buy pullbacks"; an acronym ("IHSG", "ADX 32") keeps its case. */
    static String lowerFirst(String text) {
        if (text.length() > 1 && Character.isUpperCase(text.charAt(1))) {
            return text;
        }
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    private static String pct(double v) {
        return (v > 0 ? "+" : "") + String.format(Locale.US, "%.1f", v) + "%";
    }

    /** "+3", "-1.5", "0" */
    private static String signed(double v) {
        if (!ok(v)) {
            return "n/a";
        }
        return (v > 0 ? "+" : "") + trim(v);
    }

    private static String num1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String num2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** "1.5", "2" */
    static String trimAtr(double multiple) {
        return trim(multiple);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }
}
