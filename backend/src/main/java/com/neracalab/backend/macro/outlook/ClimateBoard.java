package com.neracalab.backend.macro.outlook;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * The weather of the Macro page ({@code weather} of {@code GET /api/v1/macro/outlook}): fixed rules over the weather
 * series of Ingestion › Macro (no AI model), as published by the as-of date. Two parts, never mixed:
 * <ul>
 *   <li><b>global</b>: El Nino / La Nina (RONI, ONI, the weekly Nino 3.4, the SOI), the Indian Ocean Dipole, NOAA's
 *       ENSO forecast and the rainfall and drought of the other producers (Malaysia's palm oil, Australia's coal basins,
 *       India's monsoon);</li>
 *   <li><b>indonesia</b>: the rainfall (% of the 1991 - 2020 normal) and drought (SPI-3) of the palm oil, coal and rice
 *       regions, and the country's weather risk now.</li>
 * </ul>
 * Then what it means for the sectors (plantations, coal, food inflation and Bank Indonesia, the rupiah and haze), one
 * tone for the country's equities and, in {@link MacroOutlook}, how each of the four investors reads it. The weather is
 * context: it casts <b>no vote</b> in the regimes, the presets' stances or the investability score - the link from
 * the weather to the index is not backtested (instruction/quant/1_quant_testing/la_nina_el_nino_rainfall_drought_index.md).
 * <p>
 * Thresholds: El Nino / La Nina at RONI +/- 0.5 (weak; moderate 1.0, strong 1.5, very strong 2.0), its trend at +/- 0.3
 * in three months; IOD positive / negative at a monthly DMI of +/- 0.4 (the Bureau of Meteorology's); SPI-3 -1 moderate
 * drought, -1.5 severe, -2 extreme, +1 wet, +1.5 very wet. Indonesia's dry season, when ENSO and the IOD decide its
 * rainfall (a correlation of -0.7 to -0.86 with RONI from June to November, 1982 - 2025), is June - November.
 */
public final class ClimateBoard {

    public static final String POSITIVE = "POSITIVE";
    public static final String NEGATIVE = "NEGATIVE";
    public static final String NEUTRAL = "NEUTRAL";

    /** A rainfall region and its drought index. */
    record Region(String rainCode, String spiCode, String name, String role) {
    }

    /** Indonesia's regions, in this order. */
    static final List<Region> INDONESIA = List.of(
            new Region("ID_RAIN_SUM_PALM", "ID_SPI3_SUM_PALM", "Sumatra palm oil regions", "palm oil"),
            new Region("ID_RAIN_KAL_PALM", "ID_SPI3_KAL_PALM", "Kalimantan palm oil regions", "palm oil"),
            new Region("ID_RAIN_KAL_COAL", "ID_SPI3_KAL_COAL", "Kalimantan coal regions", "coal"),
            new Region("ID_RAIN_JAVA_RICE", "ID_SPI3_JAVA_RICE", "Java rice regions", "rice"));

    /** The other producers. */
    static final List<Region> PRODUCERS = List.of(
            new Region("GL_RAIN_MY_PALM", "GL_SPI3_MY_PALM", "Malaysia palm oil regions", "palm oil"),
            new Region("GL_RAIN_AU_COAL", "GL_SPI3_AU_COAL", "Australia coal basins", "coal"),
            new Region("GL_RAIN_IN_MONSOON", "GL_SPI3_IN_MONSOON", "India monsoon", "rice"));

    /** A chart of the weather section. */
    record Chart(String code, String group, String title, String unit, int digits) {
    }

    static final List<Chart> GLOBAL_CHARTS = List.of(
            new Chart("GL_ENSO_RONI", "Global", "El Nino / La Nina: RONI (3-month)", "degC", 2),
            new Chart("GL_NINO34_WK", "Global", "Nino 3.4 weekly anomaly", "degC", 1),
            new Chart("GL_SOI_30D", "Global", "SOI 30-day", "SOI", 1),
            new Chart("GL_IOD_DMI", "Global", "Indian Ocean Dipole (DMI, monthly)", "degC", 2),
            new Chart("GL_PROB_ELNINO_3M", "Global", "NOAA: chance of El Nino in 3 months", "%", 0),
            new Chart("GL_PROB_LANINA_3M", "Global", "NOAA: chance of La Nina in 3 months", "%", 0),
            new Chart("GL_SPI3_MY_PALM", "Global", "Drought (SPI-3): Malaysia palm oil", "SPI", 2),
            new Chart("GL_SPI3_AU_COAL", "Global", "Drought (SPI-3): Australia coal basins", "SPI", 2),
            new Chart("GL_SPI3_IN_MONSOON", "Global", "Drought (SPI-3): India monsoon", "SPI", 2));

    private final Map<String, List<Observation>> macro;
    private final LocalDate asOf;
    private final LocalDate from;
    private final List<String> notes = new ArrayList<>();

    // the reading, for the summaries and the four investors' assessments (MacroOutlook)
    String ensoState;
    String ensoStrength;
    Double roni;
    String roniSeason;
    String ensoTrend;
    Double roniChange3m;
    String iodState;
    Double iodValue;
    String iodMonth;
    Double probElNino3m;
    Double probLaNina3m;
    Double probElNino6m;
    Double probLaNina6m;
    Double probElNino3mChange;
    String probIssued;
    String risk;
    String tone;
    boolean drySeason;
    final Map<String, Double> spi = new LinkedHashMap<>();
    final Map<String, String> spiMonth = new LinkedHashMap<>();

    private ClimateBoard(Map<String, List<Observation>> macro, LocalDate asOf, LocalDate from) {
        this.macro = macro == null ? Map.of() : macro;
        this.asOf = asOf;
        this.from = from;
    }

    /** The section and the reading (for the investors' assessments). */
    static ClimateBoard read(Map<String, List<Observation>> macro, LocalDate asOf, LocalDate from) {
        ClimateBoard b = new ClimateBoard(macro, asOf, from);
        b.readAll();
        return b;
    }

    /** Whether any weather series is stored (the section is shown at all). */
    boolean any() {
        return GLOBAL_CHARTS.stream().anyMatch(c -> !available(c.code()).isEmpty())
                || INDONESIA.stream().anyMatch(r -> !available(r.rainCode()).isEmpty());
    }

    // ------------------------------------------------------------------ reading

    private void readAll() {
        drySeason = asOf.getMonthValue() >= 6 && asOf.getMonthValue() <= 11;
        Observation r = latest("GL_ENSO_RONI", asOf.minusMonths(4));
        if (r != null) {
            roni = r.value();
            roniSeason = seasonOf(r.date());
            ensoState = roni >= 0.5 ? "EL_NINO" : roni <= -0.5 ? "LA_NINA" : "NEUTRAL";
            double a = Math.abs(roni);
            ensoStrength = a >= 2 ? "VERY_STRONG" : a >= 1.5 ? "STRONG" : a >= 1 ? "MODERATE" : a >= 0.5 ? "WEAK" : null;
            Observation then = onOrBefore("GL_ENSO_RONI", r.date().minusMonths(3));
            if (then != null && !then.date().isBefore(r.date().minusMonths(4))) {
                roniChange3m = roni - then.value();
                ensoTrend = Math.abs(roni) - Math.abs(then.value()) >= 0.3 ? "STRENGTHENING"
                        : Math.abs(roni) - Math.abs(then.value()) <= -0.3 ? "WEAKENING" : "STEADY";
            }
        } else if (!available("GL_ENSO_RONI").isEmpty()) {
            notes.add("The latest RONI is older than three months (stale): El Nino / La Nina unknown now");
        }
        // monthly, posted about ten days after the month: the latest is at most ~70 days old
        Observation dmi = latest("GL_IOD_DMI", asOf.minusDays(75));
        if (dmi != null) {
            iodValue = dmi.value();
            iodMonth = dmi.date().toString();
            iodState = iodValue >= 0.4 ? "POSITIVE" : iodValue <= -0.4 ? "NEGATIVE" : "NEUTRAL";
        } else if (!available("GL_IOD_DMI").isEmpty()) {
            notes.add("The latest Indian Ocean Dipole month is too old to read (stale)");
        }
        Observation e3 = latest("GL_PROB_ELNINO_3M", asOf.minusDays(45));
        Observation l3 = latest("GL_PROB_LANINA_3M", asOf.minusDays(45));
        Observation e6 = latest("GL_PROB_ELNINO_6M", asOf.minusDays(45));
        Observation l6 = latest("GL_PROB_LANINA_6M", asOf.minusDays(45));
        if (e3 != null) {
            probElNino3m = e3.value();
            probIssued = e3.date().toString();
            List<Observation> all = available("GL_PROB_ELNINO_3M");
            if (all.size() >= 2) {
                probElNino3mChange = e3.value() - all.get(all.size() - 2).value();
            }
        }
        probLaNina3m = l3 == null ? null : l3.value();
        probElNino6m = e6 == null ? null : e6.value();
        probLaNina6m = l6 == null ? null : l6.value();
        for (Region region : concat(INDONESIA, PRODUCERS)) {
            Observation s = latest(region.spiCode(), asOf.minusMonths(3));
            if (s != null) {
                spi.put(region.spiCode(), s.value());
                spiMonth.put(region.spiCode(), s.date().toString());
            }
        }
        risk = risk();
        tone = switch (risk == null ? "" : risk) {
            case "DROUGHT", "DRY_RISK" -> NEGATIVE;
            default -> NEUTRAL;
        };
    }

    /**
     * Indonesia's weather risk now: DROUGHT (two or more of its four regions at SPI-3 -1 or less), WET (two or more at
     * +1.5 or more), DRY_RISK (El Nino or a positive IOD during the dry season, or NOAA at 60% or more for El Nino in
     * three months when those three months land in the dry season), WET_RISK (the same for La Nina / a negative IOD),
     * else NORMAL; null without any reading.
     */
    private String risk() {
        long dry = INDONESIA.stream().map(r -> spi.get(r.spiCode())).filter(v -> v != null && v <= -1).count();
        long wet = INDONESIA.stream().map(r -> spi.get(r.spiCode())).filter(v -> v != null && v >= 1.5).count();
        boolean anySpi = INDONESIA.stream().anyMatch(r -> spi.containsKey(r.spiCode()));
        if (dry >= 2) {
            return "DROUGHT";
        }
        if (wet >= 2) {
            return "WET";
        }
        if (dryNow() || dryForecast()) {
            return "DRY_RISK";
        }
        if (wetNow() || wetForecast()) {
            return "WET_RISK";
        }
        return anySpi || ensoState != null || iodState != null ? "NORMAL" : null;
    }

    private boolean dryNow() {
        return drySeason && ("EL_NINO".equals(ensoState) || "POSITIVE".equals(iodState));
    }

    private boolean wetNow() {
        return drySeason && ("LA_NINA".equals(ensoState) || "NEGATIVE".equals(iodState));
    }

    /** Whether the season NOAA's 3-month forecast is centred on falls in the dry season (June - November). */
    private boolean forecastInDrySeason() {
        int target = asOf.plusMonths(3).getMonthValue();
        return target >= 6 && target <= 11;
    }

    private boolean dryForecast() {
        return forecastInDrySeason() && probElNino3m != null && probElNino3m >= 60;
    }

    private boolean wetForecast() {
        return forecastInDrySeason() && probLaNina3m != null && probLaNina3m >= 60;
    }

    // ------------------------------------------------------------------ the section

    Map<String, Object> section() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOfDate", asOf.toString());
        m.put("season", season());
        m.put("global", global());
        m.put("indonesia", indonesia());
        m.put("implications", implications());
        m.put("tone", tone);
        m.put("summary", summary());
        m.put("charts", charts());
        m.put("notes", notes);
        return m;
    }

    private Map<String, Object> season() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drySeason", drySeason);
        int month = asOf.getMonthValue();
        m.put("text", drySeason
                ? "Indonesia's dry season (June - November): El Nino / La Nina and the Indian Ocean Dipole decide its rainfall now"
                : month >= 4 && month <= 5
                ? "The dry season starts in June: the ENSO and IOD forecasts for June - November matter most now"
                : "Indonesia's wet season (December - May): ENSO matters less for Sumatra, still for Kalimantan's coal and Java");
        return m;
    }

    private Map<String, Object> global() {
        Map<String, Object> g = new LinkedHashMap<>();
        Map<String, Object> enso = new LinkedHashMap<>();
        enso.put("state", ensoState);
        enso.put("strength", ensoStrength);
        enso.put("roni", r(roni, 2));
        enso.put("roniSeason", roniSeason);
        enso.put("roniChange3m", r(roniChange3m, 2));
        enso.put("trend", ensoTrend);
        Observation oni = latest("GL_ENSO_ONI", asOf.minusMonths(4));
        enso.put("oni", oni == null ? null : r(oni.value(), 2));
        Observation wk = latest("GL_NINO34_WK", asOf.minusDays(21));
        enso.put("nino34Weekly", wk == null ? null : r(wk.value(), 2));
        enso.put("nino34WeekOf", wk == null ? null : wk.date().toString());
        Observation soi30 = latest("GL_SOI_30D", asOf.minusDays(10));
        enso.put("soi30d", soi30 == null ? null : r(soi30.value(), 1));
        enso.put("soi30dTo", soi30 == null ? null : soi30.date().toString());
        Observation soi = latest("GL_SOI", asOf.minusMonths(3));
        enso.put("soiMonthly", soi == null ? null : r(soi.value(), 1));
        enso.put("soiMonth", soi == null ? null : soi.date().toString());
        String[] ensoText = ensoText(oni, wk, soi30);
        enso.put("tone", ensoText[0]);
        enso.put("summary", ensoText[1]);
        g.put("enso", enso);

        Map<String, Object> iod = new LinkedHashMap<>();
        iod.put("state", iodState);
        iod.put("value", r(iodValue, 2));
        iod.put("month", iodMonth);
        String[] iodText = iodText();
        iod.put("tone", iodText[0]);
        iod.put("summary", iodText[1]);
        g.put("iod", iod);

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("issued", probIssued);
        f.put("elNino3m", r(probElNino3m, 0));
        f.put("laNina3m", r(probLaNina3m, 0));
        f.put("elNino6m", r(probElNino6m, 0));
        f.put("laNina6m", r(probLaNina6m, 0));
        f.put("elNino3mChange", r(probElNino3mChange, 0));
        String[] forecastText = forecastText();
        f.put("tone", forecastText[0]);
        f.put("summary", forecastText[1]);
        g.put("forecast", f);

        List<Map<String, Object>> producers = new ArrayList<>();
        for (Region region : PRODUCERS) {
            Map<String, Object> p = regionRow(region, false);
            if (p != null) {
                producers.add(p);
            }
        }
        g.put("producers", producers);
        return g;
    }

    private Map<String, Object> indonesia() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> regions = new ArrayList<>();
        for (Region region : INDONESIA) {
            Map<String, Object> row = regionRow(region, true);
            if (row != null) {
                regions.add(row);
            }
        }
        m.put("regions", regions);
        m.put("risk", risk);
        m.put("riskLabel", riskLabel(risk));
        m.put("tone", tone);
        m.put("summary", riskText());
        return m;
    }

    /** A region: the latest month, its rainfall against the normal, the 3-month rainfall and SPI-3. */
    private Map<String, Object> regionRow(Region region, boolean indonesia) {
        List<Observation> rain = available(region.rainCode());
        if (rain.isEmpty()) {
            return null;
        }
        Map<Integer, Double> normals = normals(rain);
        Observation last = rain.getLast();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", region.rainCode());
        m.put("spiCode", region.spiCode());
        m.put("name", region.name());
        m.put("role", region.role());
        m.put("month", last.date().toString());
        m.put("stale", last.date().isBefore(asOf.minusMonths(3)));
        m.put("rainfallMm", r(last.value(), 0));
        Double normal = normals.get(last.date().getMonthValue());
        m.put("normalMm", r(normal, 0));
        m.put("pctOfNormal", normal == null || normal <= 0 ? null : r(last.value() / normal * 100, 0));
        m.put("pct3mOfNormal", r(pct3m(rain, normals), 0));
        Double s = spi.get(region.spiCode());
        m.put("spi3", r(s, 2));
        m.put("spiState", spiState(s));
        String[] text = regionText(region, last, normal, s, indonesia);
        m.put("tone", text[0]);
        m.put("summary", text[1]);
        return m;
    }

    /** The 3-month rainfall to the latest month against the sum of the three monthly normals, %. */
    static Double pct3m(List<Observation> rain, Map<Integer, Double> normals) {
        if (rain.size() < 3) {
            return null;
        }
        Observation last = rain.getLast();
        double sum = 0;
        double normal = 0;
        for (int k = 0; k < 3; k++) {
            YearMonth m = YearMonth.from(last.date()).minusMonths(k);
            Observation o = rain.get(rain.size() - 1 - k);
            if (!YearMonth.from(o.date()).equals(m)) {
                return null;    // a month missing
            }
            Double n = normals.get(m.getMonthValue());
            if (n == null) {
                return null;
            }
            sum += o.value();
            normal += n;
        }
        return normal <= 0 ? null : sum / normal * 100;
    }

    /** The 1991 - 2020 mean of every calendar month with at least 25 years. */
    static Map<Integer, Double> normals(List<Observation> rain) {
        Map<Integer, double[]> acc = new TreeMap<>();
        for (Observation o : rain) {
            int y = o.date().getYear();
            if (y >= 1991 && y <= 2020) {
                double[] a = acc.computeIfAbsent(o.date().getMonthValue(), k -> new double[2]);
                a[0] += o.value();
                a[1]++;
            }
        }
        Map<Integer, Double> out = new TreeMap<>();
        acc.forEach((month, a) -> {
            if (a[1] >= 25) {
                out.put(month, a[0] / a[1]);
            }
        });
        return out;
    }

    static String spiState(Double s) {
        if (s == null) {
            return null;
        }
        return s <= -2 ? "EXTREME_DROUGHT" : s <= -1.5 ? "SEVERE_DROUGHT" : s <= -1 ? "MODERATE_DROUGHT"
                : s >= 1.5 ? "VERY_WET" : s >= 1 ? "WET" : "NEAR_NORMAL";
    }

    static String spiLabel(String state) {
        return state == null ? "unknown" : switch (state) {
            case "EXTREME_DROUGHT" -> "extreme drought";
            case "SEVERE_DROUGHT" -> "severe drought";
            case "MODERATE_DROUGHT" -> "moderate drought";
            case "VERY_WET" -> "very wet";
            case "WET" -> "wet";
            default -> "near normal";
        };
    }

    static String riskLabel(String risk) {
        return risk == null ? "Unknown" : switch (risk) {
            case "DROUGHT" -> "Drought";
            case "DRY_RISK" -> "Dry risk";
            case "WET" -> "Very wet";
            case "WET_RISK" -> "Wet risk";
            default -> "Normal";
        };
    }

    // ------------------------------------------------------------------ texts

    private String[] ensoText(Observation oni, Observation weekly, Observation soi30) {
        if (ensoState == null) {
            return new String[] {NEUTRAL, "No current El Nino / La Nina reading: fetch GL_ENSO_RONI on Ingestion > Macro."};
        }
        StringBuilder t = new StringBuilder();
        t.append(switch (ensoState) {
            case "EL_NINO" -> lower(ensoStrength) + " El Nino";
            case "LA_NINA" -> lower(ensoStrength) + " La Nina";
            default -> "ENSO-neutral";
        });
        t.setCharAt(0, Character.toUpperCase(t.charAt(0)));
        t.append(": RONI ").append(MacroOutlook.sp(roni, 2)).append(" in ").append(roniSeason);
        if (ensoTrend != null) {
            t.append(", ").append(lower(ensoTrend)).append(" (").append(MacroOutlook.sp(roniChange3m, 2))
                    .append(" in three months)");
        }
        if (oni != null) {
            t.append("; classic ONI ").append(MacroOutlook.sp(oni.value(), 2));
        }
        if (weekly != null) {
            t.append("; Nino 3.4 ").append(MacroOutlook.sp(weekly.value(), 1)).append(" in the week of ")
                    .append(weekly.date());
        }
        if (soi30 != null) {
            t.append("; SOI 30-day ").append(MacroOutlook.sp(soi30.value(), 1));
        }
        t.append(". ");
        t.append(switch (ensoState) {
            case "EL_NINO" -> drySeason ? "In the dry season it dries Sumatra, Kalimantan and Java: lower palm oil output "
                    + "6 - 12 months on, higher rice prices and food inflation."
                    : "Outside the dry season its effect on Indonesia's rainfall is weaker, except in Kalimantan and Java.";
            case "LA_NINA" -> "It brings more rain: better harvests and lower food inflation, but flooded coal pits and "
                    + "Australian floods that lift coal prices.";
            default -> "No ENSO push on Indonesia's rainfall now.";
        });
        String tone = "EL_NINO".equals(ensoState) && drySeason ? NEGATIVE : NEUTRAL;
        return new String[] {tone, t.toString()};
    }

    private String[] iodText() {
        if (iodState == null) {
            return new String[] {NEUTRAL, "No current Indian Ocean Dipole reading: fetch GL_IOD_DMI on Ingestion > Macro."};
        }
        String t = "Indian Ocean Dipole " + switch (iodState) {
            case "POSITIVE" -> "positive";
            case "NEGATIVE" -> "negative";
            default -> "neutral";
        } + ": " + MacroOutlook.sp(iodValue, 2) + " in " + MacroOutlook.month(iodMonth) + ". ";
        boolean matters = asOf.getMonthValue() >= 7 && asOf.getMonthValue() <= 11;
        t += switch (iodState) {
            case "POSITIVE" -> matters ? "A cool sea off Sumatra and Java dries them from July to November (2019 without El "
                    + "Nino; 1997, 2006, 2023 with it)." : "It matters for Indonesia from July to November.";
            case "NEGATIVE" -> matters ? "A warm sea off Sumatra and Java wets them." : "It matters from July to November.";
            default -> "No push from the Indian Ocean.";
        };
        return new String[] {"POSITIVE".equals(iodState) && matters ? NEGATIVE : NEUTRAL, t};
    }

    private String[] forecastText() {
        if (probElNino3m == null && probLaNina3m == null) {
            return new String[] {NEUTRAL, "No ENSO forecast stored: fetch GL_PROB_ELNINO_3M on Ingestion > Macro."};
        }
        StringBuilder t = new StringBuilder("NOAA (issued ").append(probIssued).append("): El Nino ")
                .append(MacroOutlook.f(probElNino3m, 0)).append("% / La Nina ").append(MacroOutlook.f(probLaNina3m, 0))
                .append("% in three months");
        if (probElNino6m != null || probLaNina6m != null) {
            t.append(", El Nino ").append(MacroOutlook.f(probElNino6m, 0)).append("% / La Nina ")
                    .append(MacroOutlook.f(probLaNina6m, 0)).append("% in six");
        }
        t.append('.');
        if (probElNino3mChange != null && Math.abs(probElNino3mChange) >= 15) {
            t.append(" The El Nino chance moved ").append(MacroOutlook.sp(probElNino3mChange, 0))
                    .append(" points since the last issue: the surprise markets price.");
        }
        String tone = probElNino3m != null && probElNino3m >= 60 ? NEGATIVE : NEUTRAL;
        return new String[] {tone, t.toString()};
    }

    private String[] regionText(Region region, Observation last, Double normal, Double s, boolean indonesia) {
        StringBuilder t = new StringBuilder(region.name()).append(": ").append(MacroOutlook.f(last.value(), 0))
                .append(" mm in ").append(MacroOutlook.month(last.date().toString()));
        if (normal != null && normal > 0) {
            t.append(" (").append(MacroOutlook.f(last.value() / normal * 100, 0)).append("% of the 1991 - 2020 normal)");
        }
        if (s != null) {
            t.append(", SPI-3 ").append(MacroOutlook.sp(s, 2)).append(": ").append(spiLabel(spiState(s)));
        }
        t.append('.');
        boolean drought = s != null && s <= -1;
        boolean wet = s != null && s >= 1.5;
        String tone = NEUTRAL;
        if (indonesia) {
            switch (region.role()) {
                case "palm oil" -> {
                    if (drought) {
                        t.append(" Lower palm oil output 6 - 12 months on (higher CPO prices).");
                    }
                }
                case "coal" -> {
                    if (wet) {
                        t.append(" Heavy rain floods pits and slows coal output.");
                        tone = NEGATIVE;
                    } else if (s != null && s <= -1.5) {
                        t.append(" Low rivers can slow coal barging.");
                    }
                }
                case "rice" -> {
                    if (drought) {
                        t.append(" A poor rice harvest: food inflation risk.");
                        tone = NEGATIVE;
                    }
                }
                default -> {
                }
            }
        } else {
            switch (region.role()) {
                case "palm oil" -> {
                    if (drought) {
                        t.append(" Less Malaysian palm oil later: supportive for CPO prices.");
                        tone = POSITIVE;
                    }
                }
                case "coal" -> {
                    if (wet) {
                        t.append(" Australian coal supply disrupted: supportive for the coal price Indonesian miners get.");
                        tone = POSITIVE;
                    }
                }
                case "rice" -> {
                    if (drought) {
                        t.append(" A weak monsoon: India may curb rice exports, lifting Indonesia's import bill and food prices.");
                        tone = NEGATIVE;
                    }
                }
                default -> {
                }
            }
        }
        if (last.date().isBefore(asOf.minusMonths(3))) {
            t.append(" (stale: no newer month stored)");
            tone = NEUTRAL;
        }
        return new String[] {tone, t.toString()};
    }

    private String riskText() {
        if (risk == null) {
            return "No weather reading of Indonesia yet: fetch the weather series on Ingestion > Macro (the rainfall in full "
                    + "first, then its SPI).";
        }
        List<String> dry = new ArrayList<>();
        List<String> wet = new ArrayList<>();
        for (Region region : INDONESIA) {
            Double s = spi.get(region.spiCode());
            if (s != null && s <= -1) {
                dry.add(region.name() + " " + MacroOutlook.sp(s, 1));
            } else if (s != null && s >= 1.5) {
                wet.add(region.name() + " " + MacroOutlook.sp(s, 1));
            }
        }
        return switch (risk) {
            case "DROUGHT" -> "Drought in " + String.join(", ", dry) + " (SPI-3). Expect lower palm oil output 6 - 12 months "
                    + "on, higher rice prices and haze risk in the dry season.";
            case "WET" -> "Very wet: " + String.join(", ", wet) + " (SPI-3). Good for harvests, a risk for coal mining and "
                    + "barging.";
            case "DRY_RISK" -> "No drought in the rainfall yet, but " + dryCause() + ": a drier dry season (June - "
                    + "November) than normal is likely.";
            case "WET_RISK" -> "No excess rain yet, but " + wetCause() + ": a wetter dry season (June - November) than "
                    + "normal is likely.";
            default -> "Rainfall near normal and no ENSO / IOD push.";
        };
    }

    private String dryCause() {
        List<String> c = new ArrayList<>();
        if (drySeason && "EL_NINO".equals(ensoState)) {
            c.add("El Nino in the dry season");
        }
        if (drySeason && "POSITIVE".equals(iodState)) {
            c.add("a positive Indian Ocean Dipole");
        }
        if (dryForecast()) {
            c.add("NOAA gives El Nino " + MacroOutlook.f(probElNino3m, 0) + "% for the season three months ahead");
        }
        return String.join(" and ", c);
    }

    private String wetCause() {
        List<String> c = new ArrayList<>();
        if (drySeason && "LA_NINA".equals(ensoState)) {
            c.add("La Nina in the dry season");
        }
        if (drySeason && "NEGATIVE".equals(iodState)) {
            c.add("a negative Indian Ocean Dipole");
        }
        if (wetForecast()) {
            c.add("NOAA gives La Nina " + MacroOutlook.f(probLaNina3m, 0) + "% for the season three months ahead");
        }
        return String.join(" and ", c);
    }

    /** What the weather means for the sectors it moves. */
    private List<Map<String, Object>> implications() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (risk == null && ensoState == null) {
            return out;
        }
        Double sumPalm = spi.get("ID_SPI3_SUM_PALM");
        Double kalPalm = spi.get("ID_SPI3_KAL_PALM");
        Double myPalm = spi.get("GL_SPI3_MY_PALM");
        boolean palmDrought = (sumPalm != null && sumPalm <= -1) || (kalPalm != null && kalPalm <= -1)
                || (myPalm != null && myPalm <= -1) || ("EL_NINO".equals(ensoState) && drySeason
                && ("STRONG".equals(ensoStrength) || "VERY_STRONG".equals(ensoStrength)));
        out.add(implication("Plantations (palm oil)", palmDrought ? POSITIVE : NEUTRAL, palmDrought
                ? "Drought in the palm oil regions cuts output 6 - 12 months later (up to 24): CPO prices tend to rise "
                + "before the volume falls - supportive for CPO prices, a volume risk for the planters next year."
                : "No drought in the palm oil regions: no weather push on CPO supply."));
        Double kalCoal = spi.get("ID_SPI3_KAL_COAL");
        Double auCoal = spi.get("GL_SPI3_AU_COAL");
        String coalTone = NEUTRAL;
        String coalText = "No weather push on coal supply.";
        if (auCoal != null && auCoal >= 1.5) {
            coalTone = POSITIVE;
            coalText = "Australia's coal basins are very wet (SPI-3 " + MacroOutlook.sp(auCoal, 1) + "): flood risk to "
                    + "Australian supply, supportive for the coal price.";
        }
        if (kalCoal != null && kalCoal >= 1.5) {
            coalTone = NEGATIVE;
            coalText = "Kalimantan's coal regions are very wet (SPI-3 " + MacroOutlook.sp(kalCoal, 1) + "): flooded pits "
                    + "and slower output for Indonesian miners" + (auCoal != null && auCoal >= 1.5
                    ? ", while Australia's floods support the price." : ".");
        } else if (kalCoal != null && kalCoal <= -1.5) {
            coalText = "Kalimantan's coal regions are in drought (SPI-3 " + MacroOutlook.sp(kalCoal, 1) + "): low rivers "
                    + "can slow barging." + (coalTone.equals(POSITIVE) ? " " + coalText : "");
        }
        out.add(implication("Coal", coalTone, coalText));
        Double java = spi.get("ID_SPI3_JAVA_RICE");
        Double india = spi.get("GL_SPI3_IN_MONSOON");
        boolean food = (java != null && java <= -1) || ("EL_NINO".equals(ensoState) && drySeason)
                || (india != null && india <= -1 && asOf.getMonthValue() >= 7 && asOf.getMonthValue() <= 12);
        // good rains help the harvest; a flood (SPI-3 +2 or more) does not
        boolean disinflation = !food && ((java != null && java >= 1 && java < 2) || ("LA_NINA".equals(ensoState) && drySeason));
        out.add(implication("Food inflation and Bank Indonesia", food ? NEGATIVE : disinflation ? POSITIVE : NEUTRAL,
                food ? "A weaker rice harvest (Java's rain" + (java == null ? "" : ", SPI-3 " + MacroOutlook.sp(java, 1))
                        + (india != null && india <= -1 ? "; India's weak monsoon can curb its rice exports" : "")
                        + ") lifts food inflation: less room for Bank Indonesia to cut, a squeeze on consumer staples."
                        : disinflation ? "Good rains support the rice harvest: food prices contained, room for Bank "
                        + "Indonesia to ease." : "No weather push on food prices."));
        boolean haze = drySeason && asOf.getMonthValue() >= 8 && ((sumPalm != null && sumPalm <= -1.5)
                || (kalPalm != null && kalPalm <= -1.5) || "DRY_RISK".equals(risk) || "DROUGHT".equals(risk));
        out.add(implication("Rupiah, growth and haze", haze ? NEGATIVE : NEUTRAL, haze
                ? "A dry August - October on peat (Sumatra, Kalimantan) brings fires and haze, as in 2015 and 2019: a drag "
                + "on growth and the rupiah, and a regional political risk."
                : "No fire and haze season signal now."));
        return out;
    }

    private static Map<String, Object> implication(String sector, String tone, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sector", sector);
        m.put("tone", tone);
        m.put("text", text);
        return m;
    }

    private String summary() {
        if (risk == null && ensoState == null && iodState == null) {
            return "No weather data stored yet: fetch the weather series on Ingestion > Macro (Weather - Global and Weather "
                    + "- Indonesia).";
        }
        StringBuilder t = new StringBuilder();
        String[] enso = ensoText(null, null, null);
        t.append(enso[1].split("\\. ")[0]).append(". ");
        if (iodState != null) {
            t.append("IOD ").append(lower(iodState)).append(" (").append(MacroOutlook.sp(iodValue, 2)).append(" in ")
                    .append(MacroOutlook.month(iodMonth)).append("). ");
        }
        if (probElNino3m != null) {
            t.append("NOAA: El Nino ").append(MacroOutlook.f(probElNino3m, 0)).append("% / La Nina ")
                    .append(MacroOutlook.f(probLaNina3m, 0)).append("% in three months. ");
        }
        t.append("Indonesia: ").append(riskLabel(risk).toLowerCase(java.util.Locale.ROOT)).append(" - ").append(riskText());
        t.append(" Context only: the weather casts no vote in the investability score.");
        return t.toString();
    }

    // ------------------------------------------------------------------ charts

    private List<Map<String, Object>> charts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Chart c : GLOBAL_CHARTS) {
            List<Observation> obs = available(c.code());
            if (!obs.isEmpty()) {
                out.add(chart(c.code(), c.group(), c.title(), c.unit(), c.digits(), obs, false));
            }
        }
        for (Region region : INDONESIA) {
            List<Observation> rain = available(region.rainCode());
            if (!rain.isEmpty()) {
                Map<Integer, Double> normals = normals(rain);
                List<Observation> pct = new ArrayList<>();
                for (Observation o : rain) {
                    Double n = normals.get(o.date().getMonthValue());
                    if (n != null && n > 0) {
                        pct.add(new Observation(o.date(), o.value() / n * 100, o.released()));
                    }
                }
                if (!pct.isEmpty()) {
                    out.add(chart(region.rainCode(), "Indonesia", "Rainfall, % of normal: " + region.name(), "%", 0, pct,
                            true));
                }
            }
            List<Observation> s = available(region.spiCode());
            if (!s.isEmpty()) {
                out.add(chart(region.spiCode(), "Indonesia", "Drought (SPI-3): " + region.name(), "SPI", 2, s, false));
            }
        }
        return out;
    }

    private Map<String, Object> chart(String code, String group, String title, String unit, int digits,
                                      List<Observation> obs, boolean pctOfNormal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("group", group);
        m.put("title", title);
        m.put("unit", unit);
        m.put("digits", digits);
        m.put("pctOfNormal", pctOfNormal);
        List<List<Object>> points = new ArrayList<>();
        for (Observation o : obs) {
            if (from == null || !o.date().isBefore(from)) {
                points.add(List.of(o.date().atStartOfDay(ZoneOffset.UTC).toEpochSecond(), r(o.value(), 4)));
            }
        }
        m.put("points", points);
        Observation last = obs.getLast();
        m.put("lastDate", last.date().toString());
        m.put("lastValue", r(last.value(), 4));
        return m;
    }

    // ------------------------------------------------------------------ helpers

    /** Observations released by the as-of date, oldest first. */
    private List<Observation> available(String code) {
        List<Observation> obs = macro.get(code);
        if (obs == null) {
            return List.of();
        }
        return obs.stream().filter(o -> !o.released().isAfter(asOf) && !o.date().isAfter(asOf)).toList();
    }

    /** The latest available observation, or null when there is none or it is older than {@code notBefore}. */
    private Observation latest(String code, LocalDate notBefore) {
        List<Observation> obs = available(code);
        if (obs.isEmpty() || obs.getLast().date().isBefore(notBefore)) {
            return null;
        }
        return obs.getLast();
    }

    private Observation onOrBefore(String code, LocalDate day) {
        Observation found = null;
        for (Observation o : available(code)) {
            if (o.date().isAfter(day)) {
                break;
            }
            found = o;
        }
        return found;
    }

    /** "ASO 2026" for a season dated on its last month (2026-10-01). */
    static String seasonOf(LocalDate lastMonth) {
        String letters = "JFMAMJJASOND";
        LocalDate first = lastMonth.minusMonths(2);
        StringBuilder s = new StringBuilder();
        for (int k = 0; k < 3; k++) {
            s.append(letters.charAt(first.plusMonths(k).getMonthValue() - 1));
        }
        return s + " " + lastMonth.getYear();
    }

    private static List<Region> concat(List<Region> a, List<Region> b) {
        List<Region> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String lower(String v) {
        return v == null ? "" : v.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static Double r(Double v, int digits) {
        return MacroOutlook.round(v, digits);
    }

    List<String> notes() {
        return notes;
    }
}
