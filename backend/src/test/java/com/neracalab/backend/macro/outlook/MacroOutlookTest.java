package com.neracalab.backend.macro.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * The Macro page's rules: the summaries of the panels, the Paul Tudor Jones and Druckenmiller stances, the investability
 * score with its verdict and action, the derived series, point in time. Synthetic data; pure.
 */
class MacroOutlookTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 14);

    /** A daily series of 400 days up to the as-of date, linear from {@code from} to {@code to}, public the next day. */
    private static List<Observation> daily(double from, double to) {
        List<Observation> obs = new ArrayList<>();
        LocalDate start = AS_OF.minusDays(400);
        for (int i = 0; i <= 400; i++) {
            LocalDate d = start.plusDays(i);
            obs.add(new Observation(d, from + (to - from) * i / 400, d.plusDays(1)));
        }
        return obs;
    }

    /** Monthly values ending in September 2026, public mid next month. */
    private static List<Observation> monthly(double... values) {
        List<Observation> obs = new ArrayList<>();
        LocalDate month = LocalDate.of(2026, 9, 1).minusMonths(values.length - 1);
        for (double v : values) {
            obs.add(new Observation(month, v, month.plusMonths(1).withDayOfMonth(15)));
            month = month.plusMonths(1);
        }
        return obs;
    }

    /** BI rate decisions: {@code from} until 7 months before the as-of date, then {@code to}. */
    private static List<Observation> biRate(double from, double to) {
        LocalDate first = AS_OF.minusYears(1);
        LocalDate change = AS_OF.minusMonths(5);
        return List.of(new Observation(first, from, first), new Observation(change, to, change));
    }

    /** 300 weekday bars up to the as-of date, linear from {@code from} to {@code to}. */
    private static List<Candle> index(double from, double to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = AS_OF; days.size() < 300; d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.addFirst(d);
            }
        }
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            double c = from + (to - from) * i / (days.size() - 1);
            bars.add(Candle.daily(days.get(i), c, c, c, c, c, 1_000_000));
        }
        return bars;
    }

    /** Tight money everywhere, risk-off, a falling index. */
    private static Map<String, List<Observation>> bearish() {
        Map<String, List<Observation>> m = new LinkedHashMap<>();
        m.put("BI_RATE", biRate(5.0, 6.25));                  // hiking
        m.put("ID_CPI_YOY", monthly(2.5, 2.8, 3.2, 3.6, 4.0, 4.4));   // above the band, rising
        m.put("SBN_10Y", daily(6.5, 7.6));                    // rising
        m.put("USD_IDR", daily(15500, 18000));                // rupiah weakening
        m.put("US_FED_FUNDS", daily(3.5, 4.2));               // hiking
        m.put("US_FED_ASSETS", daily(7_000_000, 6_500_000));
        m.put("US_TGA", daily(700_000, 900_000));
        m.put("US_RRP", daily(100, 300));                     // net liquidity shrinking
        m.put("US_HY_SPREAD", daily(3.0, 5.5));               // stressed
        m.put("DXY", daily(95, 108));                         // strengthening
        m.put("VIX", daily(14, 32));                          // fear
        m.put("US_10Y", daily(4.0, 4.6));
        m.put("US_2Y", daily(4.2, 4.9));                      // inverted
        return m;
    }

    /** Easy money, risk-on, a rising index. */
    private static Map<String, List<Observation>> bullish() {
        Map<String, List<Observation>> m = new LinkedHashMap<>();
        m.put("BI_RATE", biRate(6.25, 5.25));                 // cutting
        m.put("ID_CPI_YOY", monthly(3.0, 2.9, 2.8, 2.7, 2.6, 2.5));   // inside the band, stable
        m.put("SBN_10Y", daily(8.0, 6.6));                    // falling (-0.32 in 3 months)
        m.put("USD_IDR", daily(16800, 15600));                // rupiah strengthening
        m.put("ID_FX_RESERVES", monthly(140_000, 141_000, 143_000, 145_000, 147_000, 149_000));
        m.put("US_FED_FUNDS", daily(4.5, 3.6));               // cutting
        m.put("US_FED_ASSETS", daily(6_500_000, 7_000_000));
        m.put("US_TGA", daily(900_000, 700_000));
        m.put("US_RRP", daily(300, 100));                     // net liquidity growing
        m.put("US_HY_SPREAD", daily(5.0, 3.0));               // calm
        m.put("DXY", daily(108, 96));                         // weakening
        m.put("VIX", daily(25, 13));                          // calm
        m.put("US_10Y", daily(4.6, 4.0));
        m.put("US_2Y", daily(4.4, 3.4));                      // normal
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> preset(Map<String, Object> out, String id) {
        return ((List<Map<String, Object>>) out.get("presets")).stream().filter(p -> p.get("preset").equals(id))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> panel(Map<String, Object> out, String code) {
        return ((List<Map<String, Object>>) out.get("panels")).stream().filter(p -> p.get("code").equals(code))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> conclusion(Map<String, Object> out) {
        return (Map<String, Object>) out.get("conclusion");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @Test
    void tightMoneyRiskOffAndADowntrendIsNotInvestable() {
        Map<String, Object> out = MacroOutlook.compute("IHSG", "IDX Composite Index (IHSG)", index(8000, 6000),
                bearish(), AS_OF, AS_OF.minusYears(1));

        Map<String, Object> c = conclusion(out);
        assertThat((Integer) c.get("score")).isLessThan(MacroOutlook.NOT_INVESTABLE);
        assertThat(c.get("verdict")).isEqualTo("NOT_INVESTABLE");
        assertThat(c.get("action")).isEqualTo("SELL");
        assertThat(list(c, "sellWhen")).allSatisfy(x -> assertThat(x.get("met")).isEqualTo(true));
        assertThat(list(c, "buyWhen")).allSatisfy(x -> assertThat(x.get("met")).isEqualTo(false));
        assertThat(list(c, "components")).hasSize(6).allSatisfy(p -> assertThat((Double) p.get("value")).isLessThanOrEqualTo(0));
        assertThat((String) c.get("summary")).startsWith("Indonesia scores ").contains("not investable now");

        assertThat(preset(out, "ptj").get("stance")).isEqualTo("AVOID");
        assertThat(preset(out, "druckenmiller").get("stance")).isEqualTo("AVOID");
        assertThat((String) preset(out, "druckenmiller").get("thesis")).contains("Liquidity is tightening");

        assertThat(panel(out, "BI_RATE").get("tone")).isEqualTo("NEGATIVE");
        assertThat((String) panel(out, "BI_RATE").get("summary")).contains("Bank Indonesia is hiking");
        assertThat(panel(out, "ID_CPI_YOY").get("tone")).isEqualTo("NEGATIVE");
        assertThat((String) panel(out, "ID_CPI_YOY").get("summary")).contains("above BI's 1.5 - 3.5% target band");
        assertThat(panel(out, "USD_IDR").get("tone")).isEqualTo("NEGATIVE");
        assertThat(panel(out, "US_HY_SPREAD").get("tone")).isEqualTo("NEGATIVE");
        assertThat(panel(out, "US_CURVE").get("tone")).isEqualTo("NEGATIVE");
        assertThat(panel(out, "VIX").get("tone")).isEqualTo("NEGATIVE");
    }

    @Test
    void easyMoneyRiskOnAndAnUptrendIsInvestable() {
        Map<String, Object> out = MacroOutlook.compute("IHSG", "IDX Composite Index (IHSG)", index(6000, 8000),
                bullish(), AS_OF, AS_OF.minusYears(1));

        Map<String, Object> c = conclusion(out);
        assertThat((Integer) c.get("score")).isGreaterThanOrEqualTo(MacroOutlook.INVESTABLE);
        assertThat(c.get("verdict")).isEqualTo("INVESTABLE");
        assertThat(c.get("action")).isEqualTo("BUY");
        assertThat(list(c, "buyWhen")).allSatisfy(x -> assertThat(x.get("met")).isEqualTo(true));
        assertThat(list(c, "sellWhen")).allSatisfy(x -> assertThat(x.get("met")).isEqualTo(false));

        assertThat(preset(out, "ptj").get("stance")).isEqualTo("BUY");
        assertThat(preset(out, "druckenmiller").get("stance")).isEqualTo("BUY");
        assertThat(panel(out, "BI_RATE").get("tone")).isEqualTo("POSITIVE");
        assertThat(panel(out, "SBN_10Y").get("tone")).isEqualTo("POSITIVE");
        assertThat(panel(out, "ID_FX_RESERVES").get("tone")).isEqualTo("POSITIVE");
        assertThat(panel(out, "US_NET_LIQUIDITY").get("tone")).isEqualTo("POSITIVE");
        assertThat(panel(out, "DXY").get("tone")).isEqualTo("POSITIVE");
    }

    /** Good macro, but the index still below its 200-day average: Druckenmiller accumulates, Tudor Jones waits. */
    @Test
    void goodMacroBelowTheAverageIsAccumulatedNotBought() {
        Map<String, Object> out = MacroOutlook.compute("IHSG", "IDX Composite Index (IHSG)", index(8000, 6000),
                bullish(), AS_OF, null);

        assertThat(preset(out, "druckenmiller").get("stance")).isEqualTo("ACCUMULATE");
        assertThat(preset(out, "ptj").get("stance")).isEqualTo("WAIT");
        String action = (String) conclusion(out).get("action");
        assertThat(action).isIn("ACCUMULATE", "WAIT");
        assertThat(action).isNotEqualTo("BUY");
    }

    @Test
    void derivedSeriesAndPointInTime() {
        Map<String, List<Observation>> macro = bullish();
        macro.put("US_CPI_INDEX", monthly(300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313));
        // a September print released after the as-of date is not known yet
        macro.put("ID_CPI_YOY", List.of(new Observation(LocalDate.of(2026, 8, 1), 2.6, LocalDate.of(2026, 9, 2)),
                new Observation(LocalDate.of(2026, 9, 1), 9.9, AS_OF.plusDays(1))));
        Map<String, Object> out = MacroOutlook.compute("LQ45", "IDX LQ45 Index (LQ45)", index(600, 800), macro, AS_OF,
                null);

        assertThat((Double) panel(out, "US_CURVE").get("lastValue")).isCloseTo(4.0 - 3.4, org.assertj.core.data.Offset.offset(0.02));
        assertThat((Double) panel(out, "US_NET_LIQUIDITY").get("lastValue")).isCloseTo(7000 - 700 - 100.0,
                org.assertj.core.data.Offset.offset(3.0));
        // September (313) is public on 15 October: August 312 against August 2025 300
        assertThat((Double) panel(out, "US_CPI_YOY").get("lastValue")).isCloseTo((312.0 / 300 - 1) * 100,
                org.assertj.core.data.Offset.offset(0.01));
        assertThat((Double) panel(out, "ID_CPI_YOY").get("lastValue")).isEqualTo(2.6);
        assertThat((String) panel(out, "ID_CPI_YOY").get("summary")).contains("August 2026");
        assertThat((Double) panel(out, "ID_FX_RESERVES").get("lastValue")).as("September is public on 15 October").isEqualTo(147.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> index = (Map<String, Object>) out.get("index");
        assertThat(index.get("code")).isEqualTo("LQ45");
        assertThat(index.get("aboveSma200")).isEqualTo(true);
    }

    @Test
    void missingDataIsNotedNotGuessed() {
        Map<String, Object> out = MacroOutlook.compute("IHSG", "IDX Composite Index (IHSG)", List.of(),
                Map.of("BI_RATE", biRate(6.0, 6.0)), AS_OF, null);

        assertThat(list(out, "panels")).extracting(p -> p.get("code")).containsExactly("BI_RATE");
        @SuppressWarnings("unchecked")
        List<String> notes = (List<String>) out.get("notes");
        assertThat(notes).anyMatch(n -> n.startsWith("IHSG is not stored yet"))
                .anyMatch(n -> n.startsWith("No data stored yet for: ") && n.contains("SBN 10Y yield"));
        Map<String, Object> c = conclusion(out);
        assertThat(c.get("action")).isEqualTo("WAIT");
        assertThat(preset(out, "ptj").get("stance")).isEqualTo("WAIT");
        assertThat(preset(out, "druckenmiller").get("stance")).isEqualTo("WAIT");
    }

    @Test
    void formatting() {
        assertThat(MacroOutlook.f(7033.06, 0)).isEqualTo("7,033");
        assertThat(MacroOutlook.sp(-1.35, 1)).isEqualTo("-1.4");
        assertThat(MacroOutlook.sp(0.24, 2)).isEqualTo("+0.24");
        assertThat(MacroOutlook.ordinal(97.6)).isEqualTo("98th");
        assertThat(MacroOutlook.ordinal(11.0)).isEqualTo("11th");
        assertThat(MacroOutlook.ordinal(22.0)).isEqualTo("22nd");
        assertThat(MacroOutlook.month("2026-08-01")).isEqualTo("August 2026");
    }
}
