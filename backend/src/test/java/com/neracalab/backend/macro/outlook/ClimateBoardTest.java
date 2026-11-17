package com.neracalab.backend.macro.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.macro.MacroRepository.Row;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * The weather of the Macro page ({@link ClimateBoard}) and the presets' climate readings: El Nino / La Nina and the IOD
 * read from the global series, Indonesia's risk from its regions, the global and Indonesian parts kept apart, point in
 * time, and no vote in the investability score. Synthetic data; pure.
 */
class ClimateBoardTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 16);

    /** RONI seasons dated on their last month, public 7 days after it ends. */
    private static List<Observation> seasons(LocalDate lastMonth, double... values) {
        List<Observation> out = new ArrayList<>();
        LocalDate m = lastMonth.minusMonths(values.length - 1);
        for (double v : values) {
            out.add(new Observation(m, v, m.plusMonths(1).plusDays(6)));
            m = m.plusMonths(1);
        }
        return out;
    }

    /** Weekly readings ending on {@code last}, public the next day. */
    private static List<Observation> weekly(LocalDate last, double... values) {
        List<Observation> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            LocalDate d = last.minusWeeks(values.length - 1 - i);
            out.add(new Observation(d, values[i], d.plusDays(1)));
        }
        return out;
    }

    /**
     * Monthly rainfall 1985-01 .. 2026-10 (public mid next month): the normal of each calendar month, except the last
     * {@code dry.length} months, which are {@code dry[i]} times their normal.
     */
    private static List<Observation> rain(double normal, double... dry) {
        List<Observation> out = new ArrayList<>();
        LocalDate last = LocalDate.of(2026, 10, 1);
        for (LocalDate m = LocalDate.of(1985, 1, 1); !m.isAfter(last); m = m.plusMonths(1)) {
            double base = normal + 50 * Math.cos(2 * Math.PI * m.getMonthValue() / 12);
            int fromEnd = (int) java.time.temporal.ChronoUnit.MONTHS.between(m, last);
            double factor = fromEnd < dry.length ? dry[dry.length - 1 - fromEnd] : 1;
            out.add(new Observation(m, base * factor, m.plusMonths(1).withDayOfMonth(15)));
        }
        return out;
    }

    private static List<Observation> spi(double value) {
        LocalDate m = LocalDate.of(2026, 10, 1);
        return List.of(new Observation(m.minusMonths(1), value / 2, m.withDayOfMonth(15)),
                new Observation(m, value, m.plusMonths(1).withDayOfMonth(15)));
    }

    /** The monthly IOD (JAMSTEC) ending in {@code last}, public ten days after each month. */
    private static List<Observation> monthlyIod(LocalDate last, double... values) {
        List<Observation> out = new ArrayList<>();
        LocalDate m = last.minusMonths(values.length - 1);
        for (double v : values) {
            out.add(new Observation(m, v, m.plusMonths(1).plusDays(9)));
            m = m.plusMonths(1);
        }
        return out;
    }

    private static Observation event(LocalDate d, double v) {
        return new Observation(d, v, d);
    }

    /** A strong El Nino, a positive IOD, NOAA at 100%, a drought in Kalimantan and Sumatra. */
    static Map<String, List<Observation>> elNinoDrought() {
        Map<String, List<Observation>> m = new LinkedHashMap<>();
        m.put("GL_ENSO_RONI", seasons(LocalDate.of(2026, 10, 1), 0.49, 0.97, 1.36, 1.69, 1.97));
        m.put("GL_ENSO_ONI", seasons(LocalDate.of(2026, 10, 1), 0.95, 1.39, 1.80, 2.16, 2.46));
        m.put("GL_NINO34_WK", weekly(LocalDate.of(2026, 11, 11), 3.0, 3.1, 3.3, 3.2));
        m.put("GL_SOI_30D", weekly(LocalDate.of(2026, 11, 14), -14.5, -12.7));
        m.put("GL_IOD_DMI", monthlyIod(LocalDate.of(2026, 10, 1), 0.31, 0.57, 0.94));
        m.put("GL_PROB_ELNINO_3M", List.of(event(LocalDate.of(2026, 10, 8), 90), event(LocalDate.of(2026, 11, 12), 100)));
        m.put("GL_PROB_LANINA_3M", List.of(event(LocalDate.of(2026, 11, 12), 0)));
        m.put("GL_PROB_ELNINO_6M", List.of(event(LocalDate.of(2026, 11, 12), 38)));
        m.put("GL_PROB_LANINA_6M", List.of(event(LocalDate.of(2026, 11, 12), 1)));
        m.put("ID_RAIN_SUM_PALM", rain(200, 0.57, 0.75, 0.77));
        m.put("ID_SPI3_SUM_PALM", spi(-1.08));
        m.put("ID_RAIN_KAL_PALM", rain(220, 0.28, 0.46, 0.54));
        m.put("ID_SPI3_KAL_PALM", spi(-2.07));
        m.put("ID_RAIN_KAL_COAL", rain(210, 0.25, 0.37, 0.52));
        m.put("ID_SPI3_KAL_COAL", spi(-2.21));
        m.put("ID_RAIN_JAVA_RICE", rain(150, 0.74, 0.72, 0.65));
        m.put("ID_SPI3_JAVA_RICE", spi(-0.71));
        m.put("GL_RAIN_AU_COAL", rain(70));
        m.put("GL_SPI3_AU_COAL", spi(0.2));
        return m;
    }

    @Test
    void anElNinoDroughtIsReadGloballyAndForIndonesia() {
        ClimateBoard b = ClimateBoard.read(elNinoDrought(), AS_OF, AS_OF.minusYears(1));
        assertThat(b.ensoState).isEqualTo("EL_NINO");
        assertThat(b.ensoStrength).isEqualTo("STRONG");          // RONI 1.97: strong (very strong from 2.0)
        assertThat(b.roniSeason).isEqualTo("ASO 2026");
        assertThat(b.ensoTrend).isEqualTo("STRENGTHENING");      // +1.0 since MJJ
        assertThat(b.iodState).isEqualTo("POSITIVE");            // October +0.94
        assertThat(b.iodMonth).isEqualTo("2026-10-01");
        assertThat(b.drySeason).isTrue();
        assertThat(b.risk).isEqualTo("DROUGHT");                 // Kalimantan palm, coal and Sumatra at SPI-3 <= -1
        assertThat(b.tone).isEqualTo(ClimateBoard.NEGATIVE);
        assertThat(b.probElNino3mChange).isEqualTo(10.0);

        Map<String, Object> s = b.section();
        assertThat(s).containsKeys("global", "indonesia", "implications", "charts", "summary");
        @SuppressWarnings("unchecked")
        Map<String, Object> global = (Map<String, Object>) s.get("global");
        @SuppressWarnings("unchecked")
        Map<String, Object> indonesia = (Map<String, Object>) s.get("indonesia");
        // the two parts never mix: Indonesia's regions under indonesia, the other producers under global
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regions = (List<Map<String, Object>>) indonesia.get("regions");
        assertThat(regions).extracting(r -> r.get("code")).containsExactly("ID_RAIN_SUM_PALM", "ID_RAIN_KAL_PALM",
                "ID_RAIN_KAL_COAL", "ID_RAIN_JAVA_RICE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> producers = (List<Map<String, Object>>) global.get("producers");
        assertThat(producers).extracting(r -> r.get("code")).containsExactly("GL_RAIN_AU_COAL");
        // Kalimantan: August 28%, September 46%, October 54% of the 1991 - 2020 normal (every other month at it);
        // the three months together 44% (weighted by the normals 195, 220 and 245 mm)
        Map<String, Object> kal = regions.get(1);
        assertThat(kal.get("month")).isEqualTo("2026-10-01");
        assertThat((Double) kal.get("pctOfNormal")).isEqualTo(54.0);
        assertThat((Double) kal.get("pct3mOfNormal")).isEqualTo(44.0);
        assertThat(kal.get("spiState")).isEqualTo("EXTREME_DROUGHT");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> implications = (List<Map<String, Object>>) s.get("implications");
        assertThat(implications).extracting(i -> i.get("sector") + ":" + i.get("tone")).containsExactly(
                "Plantations (palm oil):POSITIVE", "Coal:NEUTRAL", "Food inflation and Bank Indonesia:NEGATIVE",
                "Rupiah, growth and haze:NEGATIVE");
        assertThat((String) s.get("summary")).contains("Strong El Nino").contains("no vote");
    }

    @Test
    void theWeatherIsReadAsPublished() {
        // on 2026-11-05 the ASO season (public on 2026-11-07) is not known yet: the latest is JAS
        ClimateBoard b = ClimateBoard.read(elNinoDrought(), LocalDate.of(2026, 11, 5), null);
        assertThat(b.roniSeason).isEqualTo("JAS 2026");
        assertThat(b.roni).isEqualTo(1.69);
        // NOAA's November issue (2026-11-12) is not out yet: the October one is read
        assertThat(b.probElNino3m).isEqualTo(90.0);
        // on 2026-11-05 October's IOD (public 2026-11-10) is not out yet: September is read
        assertThat(b.iodMonth).isEqualTo("2026-09-01");
        // a stale IOD (nothing newer than August on 2026-12-20) is unknown, not guessed
        Map<String, List<Observation>> stale = elNinoDrought();
        stale.put("GL_IOD_DMI", monthlyIod(LocalDate.of(2026, 8, 1), 0.9, 0.9));
        assertThat(ClimateBoard.read(stale, LocalDate.of(2026, 12, 20), null).iodState).isNull();
    }

    @Test
    void theForecastOnlyRaisesADryRiskForTheDrySeason() {
        Map<String, List<Observation>> m = new LinkedHashMap<>();
        m.put("GL_ENSO_RONI", seasons(LocalDate.of(2026, 2, 1), 0.1, 0.2, 0.3));
        m.put("GL_PROB_ELNINO_3M", List.of(event(LocalDate.of(2026, 3, 12), 70)));
        // issued in March: three months ahead is June, the dry season
        assertThat(ClimateBoard.read(m, LocalDate.of(2026, 3, 20), null).risk).isEqualTo("DRY_RISK");
        // the same chance issued in December points at March, the wet season: no dry-season risk
        Map<String, List<Observation>> dec = new LinkedHashMap<>();
        dec.put("GL_ENSO_RONI", seasons(LocalDate.of(2026, 11, 1), 0.1, 0.2, 0.3));
        dec.put("GL_PROB_ELNINO_3M", List.of(event(LocalDate.of(2026, 12, 10), 70)));
        assertThat(ClimateBoard.read(dec, LocalDate.of(2026, 12, 20), null).risk).isEqualTo("NORMAL");
    }

    @Test
    void noWeatherDataIsSaidPlainly() {
        ClimateBoard b = ClimateBoard.read(Map.of(), AS_OF, null);
        assertThat(b.any()).isFalse();
        assertThat(b.risk).isNull();
        assertThat((String) b.section().get("summary")).contains("No weather data stored yet");
    }

    // ------------------------------------------------------------------ the presets

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

    /** A few macro series: BI on hold, CPI rising, the rupiah weakening. */
    private static Map<String, List<Observation>> macro() {
        Map<String, List<Observation>> m = new LinkedHashMap<>();
        LocalDate first = AS_OF.minusYears(1);
        m.put("BI_RATE", List.of(new Observation(first, 5.75, first)));
        List<Observation> cpi = new ArrayList<>();
        double[] values = {2.0, 2.2, 2.5, 2.9, 3.3, 3.8};
        LocalDate month = LocalDate.of(2026, 10, 1).minusMonths(values.length - 1);
        for (double v : values) {
            cpi.add(new Observation(month, v, month.plusMonths(1).withDayOfMonth(2)));
            month = month.plusMonths(1);
        }
        m.put("ID_CPI_YOY", cpi);
        List<Observation> usdIdr = new ArrayList<>();
        for (int i = 0; i <= 400; i++) {
            LocalDate d = AS_OF.minusDays(400 - i);
            usdIdr.add(new Observation(d, 15500 + 2500.0 * i / 400, d));
        }
        m.put("USD_IDR", usdIdr);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyPresetReadsTheWeatherWithoutAVote() {
        Map<String, List<Observation>> with = macro();
        with.putAll(elNinoDrought());
        Map<String, Object> out = MacroOutlook.compute("IHSG", "IHSG", index(7000, 6000), with, AS_OF, AS_OF.minusYears(1));
        Map<String, Object> without = MacroOutlook.compute("IHSG", "IHSG", index(7000, 6000), macro(), AS_OF,
                AS_OF.minusYears(1));
        List<Map<String, Object>> presets = (List<Map<String, Object>>) out.get("presets");
        assertThat(presets).extracting(p -> p.get("preset")).containsExactly("ptj", "druckenmiller", "soros", "dalio");
        for (Map<String, Object> p : presets) {
            Map<String, Object> climate = (Map<String, Object>) p.get("climate");
            assertThat((String) climate.get("text")).as((String) p.get("preset")).contains("Strong El Nino");
            List<Map<String, Object>> points = (List<Map<String, Object>>) p.get("points");
            assertThat((String) points.getLast().get("text")).startsWith("Climate (no vote): ");
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        presets.forEach(p -> byId.put((String) p.get("preset"), (Map<String, Object>) p.get("climate")));
        // CPI rising: the drought shows in the regime (Tudor Jones); NOAA gives El Nino 38% in 6 months - fading, not
        // yet gone: the drought keeps BI tight until it fades (Druckenmiller); the rupiah weakens (Soros); the food shock
        // pushes the inflation axis up (Dalio)
        assertThat(byId.get("ptj").get("tone")).isEqualTo("NEGATIVE");
        assertThat(byId.get("druckenmiller").get("tone")).isEqualTo("NEGATIVE");
        assertThat(byId.get("soros").get("tone")).isEqualTo("NEGATIVE");
        assertThat(byId.get("dalio").get("tone")).isEqualTo("NEGATIVE");
        Map<String, Object> weather = (Map<String, Object>) out.get("weather");
        assertThat((List<Object>) weather.get("assessments")).hasSize(4);
        // no vote: the presets' stances and the investability score are the same with and without the weather
        List<Map<String, Object>> presetsWithout = (List<Map<String, Object>>) without.get("presets");
        for (int i = 0; i < 4; i++) {
            assertThat(presets.get(i).get("stance")).isEqualTo(presetsWithout.get(i).get("stance"));
            assertThat(presets.get(i).get("score")).isEqualTo(presetsWithout.get(i).get("score"));
        }
        assertThat(((Map<String, Object>) out.get("conclusion")).get("score"))
                .isEqualTo(((Map<String, Object>) without.get("conclusion")).get("score"));
        assertThat(out.get("rulesVersion")).isEqualTo(MacroOutlook.RULES_VERSION);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anElNinoExpectedToEndIsTheTurnDruckenmillerWaitsFor() {
        Map<String, List<Observation>> m = macro();
        m.putAll(elNinoDrought());
        m.put("GL_PROB_ELNINO_6M", List.of(event(LocalDate.of(2026, 11, 12), 20)));
        Map<String, Object> out = MacroOutlook.compute("IHSG", "IHSG", index(7000, 6000), m, AS_OF, null);
        Map<String, Object> d = ((List<Map<String, Object>>) out.get("presets")).stream()
                .filter(p -> "druckenmiller".equals(p.get("preset"))).findFirst().orElseThrow();
        Map<String, Object> climate = (Map<String, Object>) d.get("climate");
        assertThat(climate.get("tone")).isEqualTo("POSITIVE");
        assertThat((String) climate.get("text")).contains("expected to fade");
    }

    // ------------------------------------------------------------------ Macro > Data: rainfall normals

    @Test
    void aRainfallSeriesIsReadAgainstItsNormals() {
        List<Row> rows = new ArrayList<>();
        for (LocalDate m = LocalDate.of(1990, 1, 1); !m.isAfter(LocalDate.of(2026, 10, 1)); m = m.plusMonths(1)) {
            double v = m.getYear() == 2026 && m.getMonthValue() >= 8 ? 50 : 100 + m.getMonthValue();
            rows.add(new Row(m, BigDecimal.valueOf(v), m.plusMonths(1).minusDays(1), m.plusMonths(1).plusDays(14)));
        }
        MacroSeriesChart.Normals n = MacroSeriesChart.normals(rows);
        assertThat(n.monthly()).hasSize(12);
        assertThat(n.monthly().get(9)).isEqualByComparingTo("110.0");          // October: 100 + 10
        assertThat(n.latestPct()).isEqualByComparingTo("45");                   // 50 of 110
        assertThat(n.threeMonthPct()).isEqualByComparingTo("46");              // 150 of 108 + 109 + 110
        assertThat(MacroSeriesChart.normals(List.of())).isNull();
        assertThat(MacroSeriesChart.kind("GL_ENSO_RONI", "degC")).isEqualTo(MacroSeriesChart.Kind.RATE);
        assertThat(MacroSeriesChart.kind("ID_SPI3_KAL_PALM", "SPI")).isEqualTo(MacroSeriesChart.Kind.RATE);
        assertThat(MacroSeriesChart.kind("ID_RAIN_KAL_PALM", "mm")).isEqualTo(MacroSeriesChart.Kind.LEVEL);
    }
}
