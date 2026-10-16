package com.neracalab.backend.technical.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalTestData;
import com.neracalab.backend.technical.idx.IdxTickSize;

/**
 * The preset summaries (Murphy, Keith Gill, Paul Tudor Jones, Druckenmiller) on the real HRTA, SMSM and MYOR fixtures and
 * on built trends: every summary has a stance, valid IDX prices with stop &lt; buy &lt; sell, a consistent reward to risk
 * and a thesis; the rules follow the trend; the result is deterministic. No database, no model.
 */
class PresetSummariesTest {

    private static final Set<String> STANCES = Set.of("BUY", "ACCUMULATE", "WAIT", "AVOID");

    private final TechnicalProperties properties = TechnicalTestData.properties();
    private final IdxTickSize ticks = new IdxTickSize(properties.tickSizes());
    private final IdxTickSize indexTicks = new IdxTickSize(List.of(new TechnicalProperties.TickBand(null, new BigDecimal("0.01"))));
    private final SnapshotCalculator calculator = new SnapshotCalculator(properties, ticks);
    private final List<Candle> ihsg = TechnicalTestData.fixture("IHSG");

    private MarketContext market() {
        return new MarketContext("IDX", ihsg.getLast().date(), "IHSG", ihsg, new LinkedHashMap<>());
    }

    private Map<String, Object> summarize(List<Candle> bars, IdxTickSize t, boolean index) {
        SnapshotCalculator calc = t == ticks ? calculator : new SnapshotCalculator(properties, t);
        Map<String, Object> doc = calc.snapshot("IDX", "TEST", "Test", null, "IDR", bars, List.of(), market());
        return PresetSummaries.summarize(new PresetSummaries.Input(doc, IndicatorEngine.compute(bars, Interval.D), t, index));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> summaries(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("summaries");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> of(Map<String, Object> out, String preset) {
        return summaries(out).stream().filter(s -> s.get("preset").equals(preset)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static double price(Map<String, Object> summary, String key) {
        return ((Number) ((Map<String, Object>) summary.get(key)).get("price")).doubleValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HRTA", "SMSM", "MYOR"})
    void fourConsistentSummariesOnRealData(String ticker) {
        Map<String, Object> out = summarize(TechnicalTestData.fixture(ticker), ticks, false);

        assertThat(summaries(out)).extracting(s -> s.get("preset"))
                .containsExactly("murphy", "gill", "ptj", "druckenmiller");
        for (Map<String, Object> s : summaries(out)) {
            assertConsistent(s, ticks);
        }
        assertThat(out.get("disclaimer").toString()).contains("Not investment advice");
        assertThat(out.get("type")).isEqualTo("STOCK");
    }

    @SuppressWarnings("unchecked")
    private static void assertConsistent(Map<String, Object> s, IdxTickSize ticks) {
        String preset = (String) s.get("preset");
        assertThat(STANCES).as(preset).contains((String) s.get("stance"));
        assertThat((String) s.get("stanceLabel")).as(preset).isNotBlank();
        assertThat((List<Object>) s.get("points")).as(preset).isNotEmpty();
        double buy = price(s, "buy");
        double sell = price(s, "sell");
        double stop = price(s, "stopLoss");
        assertThat(stop).as(preset + " stop < buy").isLessThan(buy);
        assertThat(buy).as(preset + " buy < sell").isLessThan(sell);
        assertThat(stop).as(preset + " stop > 0").isPositive();
        for (double p : new double[] {buy, sell, stop}) {
            assertThat(ticks.isValid(BigDecimal.valueOf(p))).as(preset + ": " + p + " is a valid price").isTrue();
        }
        double rr = Math.round((sell - buy) / (buy - stop) * 100) / 100.0;
        assertThat((Double) s.get("rewardRisk")).as(preset).isEqualTo(rr);
        assertThat(((Map<String, Object>) s.get("buy")).get("type")).isIn("LIMIT", "STOP");
        assertThat((String) s.get("thesis")).as(preset).contains("Buy at or").contains("sell at").contains("stop");
    }

    @Test
    void deterministic() {
        List<Candle> bars = TechnicalTestData.fixture("SMSM");
        assertThat(summarize(bars, ticks, false)).isEqualTo(summarize(bars, ticks, false));
    }

    @Test
    void anUptrendIsBoughtAndADowntrendAvoided() {
        Map<String, Object> up = summarize(trend(1000, 1.004), ticks, false);
        Map<String, Object> down = summarize(trend(3000, 0.996), ticks, false);

        assertThat(of(up, "ptj").get("stance")).isIn("BUY", "ACCUMULATE");
        assertThat(of(up, "murphy").get("stance")).isIn("BUY", "ACCUMULATE");
        assertThat(of(down, "ptj").get("stance")).isEqualTo("AVOID");
        assertThat(of(down, "murphy").get("stance")).isIn("WAIT", "AVOID");
        // below the 200-day PTJ only buys a reclaim: a buy-stop above the close
        @SuppressWarnings("unchecked")
        Map<String, Object> ptjBuy = (Map<String, Object>) of(down, "ptj").get("buy");
        assertThat(ptjBuy.get("type")).isEqualTo("STOP");
        assertThat(((Number) ptjBuy.get("price")).doubleValue()).isGreaterThan(((Number) down.get("close")).doubleValue());
        // PTJ's target is 5 times the risk
        Map<String, Object> ptj = of(up, "ptj");
        assertThat((Double) ptj.get("rewardRisk")).isBetween(4.8, 5.2);
        for (Map<String, Object> s : summaries(up)) {
            assertConsistent(s, ticks);
        }
        for (Map<String, Object> s : summaries(down)) {
            assertConsistent(s, ticks);
        }
    }

    @Test
    void anIndexIsPricedInPointsWithoutRelativeStrengthAgainstItself() {
        Map<String, Object> out = summarize(ihsg, indexTicks, true);

        assertThat(out.get("type")).isEqualTo("INDEX");
        for (Map<String, Object> s : summaries(out)) {
            assertConsistent(s, indexTicks);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> murphyPoints = (List<Map<String, Object>>) of(out, "murphy").get("points");
        assertThat(murphyPoints).noneMatch(p -> p.get("text").toString().contains("IHSG: relative strength"));
        assertThat((List<?>) out.get("caveats")).isEmpty();
    }

    @Test
    void fewerThanFifteenBarsGiveNoPrices() {
        Map<String, Object> out = summarize(trend(1000, 1.01).subList(0, 10), ticks, false);

        for (Map<String, Object> s : summaries(out)) {
            assertThat(s.get("buy")).isNull();
            assertThat(s.get("sell")).isNull();
            assertThat(s.get("stopLoss")).isNull();
            assertThat(STANCES).contains((String) s.get("stance"));
        }
        assertThat((List<String>) caveats(out)).anyMatch(c -> c.contains("no prices are given"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> caveats(Map<String, Object> out) {
        return (List<String>) out.get("caveats");
    }

    /** A currency pair: 300 weekday bars without volume, rounded to {@code decimals}, ending on the IHSG fixture's day. */
    private List<Candle> pair(double start, double factor, int decimals) {
        List<Candle> bars = new ArrayList<>();
        double scale = Math.pow(10, decimals);
        LocalDate date = ihsg.getLast().date();
        double close = start;
        List<Candle> reversed = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            while (date.getDayOfWeek().getValue() > 5) {
                date = date.minusDays(1);
            }
            double wiggle = (i % 6 < 3 ? 1.002 : 0.998);
            double c = Math.round(close * wiggle * scale) / scale;
            reversed.add(Candle.daily(date, c, c * 1.003, c * 0.997, c, c, 0));
            close /= factor;
            date = date.minusDays(1);
        }
        for (int i = reversed.size() - 1; i >= 0; i--) {
            bars.add(reversed.get(i));
        }
        return bars;
    }

    /** Daily observations over 400 days to the IHSG fixture's last day, moving linearly from {@code from} to {@code to}. */
    private List<MarketContext.Observation> line(double from, double to) {
        List<MarketContext.Observation> obs = new ArrayList<>();
        LocalDate end = ihsg.getLast().date();
        for (int i = 0; i <= 400; i++) {
            obs.add(new MarketContext.Observation(end.minusDays(400 - i), from + (to - from) * i / 400.0));
        }
        return obs;
    }

    /** A currency pair has no IHSG and no leadership among stocks: Druckenmiller reads its own trend and momentum. */
    @Test
    @SuppressWarnings("unchecked")
    void aCurrencyPairIsReadThroughItsOwnTrend() {
        IdxTickSize thousandths = new IdxTickSize(List.of(new TechnicalProperties.TickBand(null, new BigDecimal("0.001"))));
        List<Candle> bars = pair(150, 1.001, 3);
        Map<String, Object> doc = new SnapshotCalculator(properties, thousandths)
                .snapshot("FOREX", "USDJPY", "USD/JPY", null, "JPY", bars, List.of(), market());
        Map<String, Object> out = PresetSummaries.summarize(new PresetSummaries.Input(doc,
                IndicatorEngine.compute(bars, Interval.D), thousandths, "FOREX"));

        assertThat(out.get("type")).isEqualTo("FOREX");
        for (Map<String, Object> s : summaries(out)) {
            assertConsistent(s, thousandths);
        }
        Map<String, Object> druck = of(out, "druckenmiller");
        List<String> points = ((List<Map<String, Object>>) druck.get("points")).stream()
                .map(p -> (String) p.get("text")).toList();
        assertThat(points).anyMatch(p -> p.startsWith("Primary trend up"));
        assertThat(points).anyMatch(p -> p.startsWith("12-1 month momentum"));
        assertThat(points).noneMatch(p -> p.contains("IHSG") || p.contains("percentile of the stored listings"));
        assertThat((Double) druck.get("maxScore")).isEqualTo(3.0);
        assertThat(druck.get("stance")).isEqualTo("BUY");
        assertThat((String) druck.get("thesis")).contains("Druckenmiller trades USD/JPY with its trend and momentum");
        // prices with the decimals of the tick, no volume caveat hidden
        assertThat((String) druck.get("thesis")).containsPattern("\\d+\\.\\d{3}");
        assertThat(caveats(out)).anyMatch(c -> c.startsWith("No traded volume for USD/JPY"));

        List<Candle> falling = pair(150, 0.999, 3);
        Map<String, Object> down = PresetSummaries.summarize(new PresetSummaries.Input(new SnapshotCalculator(properties,
                thousandths).snapshot("FOREX", "USDJPY", "USD/JPY", null, "JPY", falling, List.of(), market()),
                IndicatorEngine.compute(falling, Interval.D), thousandths, "FOREX"));
        assertThat(of(down, "druckenmiller").get("stance")).isEqualTo("AVOID");
    }

    /**
     * Technicals is technical only: the snapshot has no macro section and no preset reads macro data - for a stock, an
     * index, a currency pair or a commodity (the macro view is the Macro page).
     */
    @Test
    @SuppressWarnings("unchecked")
    void noPresetReadsMacroData() {
        IdxTickSize ones = new IdxTickSize(List.of(new TechnicalProperties.TickBand(null, BigDecimal.ONE)));
        IdxTickSize tenths = new IdxTickSize(List.of(new TechnicalProperties.TickBand(null, new BigDecimal("0.1"))));
        List<Candle> idr = pair(16000, 1.0003, 0);
        List<Candle> gold = pair(4000, 1.0003, 1);
        List<Candle> stock = trend(1000, 1.002);
        List<Map<String, Object>> docs = List.of(
                calculator.snapshot("IDX", "TEST", "Test", null, "IDR", stock, List.of(), market()),
                new SnapshotCalculator(properties, ones).snapshot("FOREX", "USDIDR", "USD/IDR", null, "IDR", idr, List.of(), market()),
                new SnapshotCalculator(properties, tenths).snapshot("COMMODITY", "GOLD", "Gold", null, "USD", gold, List.of(), market()));
        List<Map<String, Object>> outs = List.of(
                PresetSummaries.summarize(new PresetSummaries.Input(docs.get(0), IndicatorEngine.compute(stock, Interval.D), ticks, "STOCK")),
                PresetSummaries.summarize(new PresetSummaries.Input(docs.get(1), IndicatorEngine.compute(idr, Interval.D), ones, "FOREX")),
                PresetSummaries.summarize(new PresetSummaries.Input(docs.get(2), IndicatorEngine.compute(gold, Interval.D), tenths, "COMMODITY")),
                summarize(ihsg, indexTicks, true));
        for (Map<String, Object> doc : docs) {
            assertThat(doc).doesNotContainKey("macro");
        }
        for (Map<String, Object> out : outs) {
            for (Map<String, Object> s : summaries(out)) {
                List<String> texts = new ArrayList<>(((List<Map<String, Object>>) s.get("points")).stream()
                        .map(p -> (String) p.get("text")).toList());
                texts.add((String) s.get("thesis"));
                texts.add((String) s.get("stanceLabel"));
                texts.add((String) s.get("style"));
                assertThat(texts).as(out.get("ticker") + " " + s.get("preset")).noneMatch(t -> t.toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(macro|liquidity|risk-on|risk-off|bi rate|fed |rupiah|dollar|yield|vix|credit).*"));
            }
        }
        assertThat(PresetSummaries.RULES_VERSION).isEqualTo("2026-10-16.2");
    }

    @Test
    void anAcronymKeepsItsCase() {
        assertThat(PresetSummaries.lowerFirst("IHSG above its 200-day")).isEqualTo("IHSG above its 200-day");
        assertThat(PresetSummaries.lowerFirst("Buy pullbacks")).isEqualTo("buy pullbacks");
    }

    /** 300 weekday bars from {@code start}, each close {@code factor} times the one before with a small zigzag. */
    private static List<Candle> trend(double start, double factor) {
        List<Candle> bars = new ArrayList<>();
        LocalDate date = LocalDate.of(2025, 6, 2);
        double close = start;
        for (int i = 0; i < 300; i++) {
            while (date.getDayOfWeek().getValue() > 5) {
                date = date.plusDays(1);
            }
            double wiggle = (i % 6 < 3 ? 1.006 : 0.995);
            double c = Math.round(close * wiggle);
            double open = Math.round(close);
            bars.add(Candle.daily(date, open, Math.max(open, c) * 1.01, Math.min(open, c) * 0.99, c, c,
                    1_000_000 + (i % 5) * 100_000));
            close *= factor;
            date = date.plusDays(1);
        }
        return bars;
    }
}
