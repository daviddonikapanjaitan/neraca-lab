package com.neracalab.backend.technical.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The macro signals and regimes of the Druckenmiller and Tudor Jones presets: direction from changes, point in time
 * (a value released after the as-of date is not used), stale series left out, the regimes from the votes. Pure.
 */
class MacroSignalsTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 14);

    /** A daily series from {@code start} to the as-of date, moving linearly from {@code from} to {@code to}. */
    private static List<MarketContext.Observation> daily(double from, double to) {
        List<MarketContext.Observation> obs = new ArrayList<>();
        LocalDate start = AS_OF.minusDays(400);
        int n = 401;
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            obs.add(new MarketContext.Observation(d, from + (to - from) * i / (n - 1), d.plusDays(1)));
        }
        // the last value is released the next day: as of AS_OF the day before is the latest known
        return obs;
    }

    private static List<MarketContext.Observation> monthly(double... values) {
        List<MarketContext.Observation> obs = new ArrayList<>();
        LocalDate month = LocalDate.of(2026, 9, 1).minusMonths(values.length - 1);
        for (double v : values) {
            obs.add(new MarketContext.Observation(month, v, month.plusMonths(1).withDayOfMonth(15)));
            month = month.plusMonths(1);
        }
        return obs;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> get(Map<String, Object> m, String path) {
        Object cur = m;
        for (String k : path.split("\\.")) {
            cur = ((Map<String, Object>) cur).get(k);
        }
        return (Map<String, Object>) cur;
    }

    @Test
    void tighteningLiquidityAndRiskOff() {
        Map<String, List<MarketContext.Observation>> macro = new LinkedHashMap<>();
        macro.put("US_FED_FUNDS", daily(3.6, 4.1));          // hiking
        macro.put("US_FED_ASSETS", daily(7_000_000, 6_600_000));
        macro.put("US_TGA", daily(700_000, 900_000));
        macro.put("US_RRP", daily(100, 300));               // net liquidity shrinking
        macro.put("US_HY_SPREAD", daily(3.0, 5.0));         // widening to its high
        macro.put("DXY", daily(95, 105));                   // strengthening
        macro.put("USD_IDR", daily(15000, 17000));          // rupiah weakening
        macro.put("VIX", daily(14, 30));                    // fear
        macro.put("US_10Y", daily(4.0, 4.2));
        macro.put("US_2Y", daily(4.5, 4.6));                // inverted
        List<String> notes = new ArrayList<>();
        Map<String, Object> out = MacroSignals.compute(macro, AS_OF, notes);

        assertThat(get(out, "signals.fedPolicy").get("state")).isEqualTo("HIKING");
        assertThat(get(out, "signals.usNetLiquidity").get("direction")).isEqualTo("SHRINKING");
        assertThat(get(out, "signals.creditStress").get("state")).isEqualTo("STRESSED");
        assertThat(get(out, "signals.dollar").get("dxyTrend")).isEqualTo("STRENGTHENING");
        assertThat(get(out, "signals.dollar").get("rupiahTrend")).isEqualTo("WEAKENING");
        assertThat(get(out, "signals.usCurve").get("state")).isEqualTo("INVERTED");
        assertThat(get(out, "regimes.liquidity").get("state")).isEqualTo("TIGHTENING");
        assertThat(get(out, "regimes.risk").get("state")).isEqualTo("RISK_OFF");
        assertThat((List<?>) get(out, "regimes.liquidity").get("components")).hasSize(5);
    }

    @Test
    void aValueIsNotKnownBeforeItsRelease() {
        Map<String, List<MarketContext.Observation>> macro = new LinkedHashMap<>();
        // September CPI released on 15 Oct: as of 14 Oct the latest known month is August
        macro.put("US_CPI_INDEX", monthly(320, 321, 322, 323, 324, 325, 326, 327, 328, 329, 330, 331, 332, 333, 340));
        Map<String, Object> out = MacroSignals.compute(macro, AS_OF, new ArrayList<>());
        Map<String, Object> series = get(out, "series.US_CPI_INDEX");
        assertThat(series.get("date")).isEqualTo("2026-08-01");
        assertThat(series.get("value")).isEqualTo(333.0);
        // YoY of August: 333 / 321 - 1
        assertThat((Double) get(out, "signals.inflation").get("usCpiYoy")).isCloseTo(3.738, org.assertj.core.data.Offset.offset(0.01));
        Map<String, Object> later = MacroSignals.compute(macro, LocalDate.of(2026, 10, 15), new ArrayList<>());
        assertThat(get(later, "series.US_CPI_INDEX").get("date")).isEqualTo("2026-09-01");
    }

    @Test
    void aStaleSeriesGivesNoSignal() {
        Map<String, List<MarketContext.Observation>> macro = new LinkedHashMap<>();
        macro.put("VIX", List.of(new MarketContext.Observation(LocalDate.of(2026, 8, 1), 18)));
        List<String> notes = new ArrayList<>();
        Map<String, Object> out = MacroSignals.compute(macro, AS_OF, notes);
        assertThat(get(out, "signals").get("riskAppetite")).isNull();
        assertThat(get(out, "series.VIX").get("stale")).isEqualTo(true);
        assertThat(notes).anyMatch(n -> n.contains("VIX is stale"));
        assertThat(get(out, "regimes.risk").get("state")).isNull();
        assertThat(get(out, "regimes.risk").get("note")).asString().contains("fewer than 3");
    }

    @Test
    void theBiRateHoldsUntilTheNextDecision() {
        Map<String, List<MarketContext.Observation>> macro = new LinkedHashMap<>();
        macro.put("BI_RATE", List.of(new MarketContext.Observation(LocalDate.of(2026, 3, 18), 4.75),
                new MarketContext.Observation(LocalDate.of(2026, 9, 23), 5.75)));
        macro.put("ID_CPI_YOY", monthly(3.0, 3.1, 3.19));
        Map<String, Object> out = MacroSignals.compute(macro, AS_OF, new ArrayList<>());
        assertThat(get(out, "signals.biPolicy").get("state")).isEqualTo("HIKING");
        assertThat(get(out, "signals.biPolicy").get("change6mPoints")).isEqualTo(1.0);
        // real rate: 5.75 - 3.1 (August, the latest released by 14 Oct)
        assertThat(get(out, "signals.idRealRate").get("value")).isEqualTo(2.65);
        assertThat(get(out, "signals.idRealRate").get("state")).isEqualTo("RESTRICTIVE");
    }
}
