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
        Map<String, List<MarketContext.Observation>> macro = new LinkedHashMap<>();
        macro.put("BI_RATE", List.of());
        macro.put("SBN_10Y", List.of());
        macro.put("USD_IDR", List.of(new MarketContext.Observation(LocalDate.of(2026, 6, 30), 16000),
                new MarketContext.Observation(LocalDate.of(2026, 10, 1), 16800)));
        return new MarketContext("IDX", ihsg.getLast().date(), "IHSG", ihsg, new LinkedHashMap<>(), macro);
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
