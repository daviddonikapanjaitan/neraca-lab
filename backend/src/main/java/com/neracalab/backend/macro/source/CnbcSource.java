package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CNBC daily bars of a bond yield ({@code ID10Y-ID}, the Indonesian 10-year government bond, since 2011): the chart
 * service of cnbc.com ({@code /bars/{symbol}/1D/{from}/{to}/adjusted/EST5EDT.json}). The close of each day is the
 * yield in %.
 * <ul>
 *   <li>One request per calendar year: a request over many years leaves days out (2026-09-10 and 2026-09-21 were
 *       missing from the 2011 - 2026 answer, present in the 2026 one).</li>
 *   <li>A bar dated on a Saturday or Sunday is left out: the Indonesian bond market does not trade then, and such bars
 *       are either sessions dated a day early by the US time zone of the service (most of 2015, beside the bar of the
 *       right day) or a repeat of Friday's close.</li>
 *   <li>Today's bar (Jakarta) may be unfinished and is left out. CNBC has no bar in 2014.</li>
 * </ul>
 */
@Component
public class CnbcSource implements MacroSource {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final MacroProperties properties;

    public CnbcSource(PoliteHttpClient http, JsonMapper json, MacroProperties properties) {
        this.http = http;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "cnbc";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String symbol = series.sourceRef();
        Map<LocalDate, BigDecimal> byDate = new TreeMap<>();
        for (LocalDate start = from; !start.isAfter(today); start = start.withDayOfYear(1).plusYears(1)) {
            LocalDate end = start.withDayOfYear(start.lengthOfYear());
            if (end.isAfter(today)) {
                end = today;
            }
            URI uri = URI.create(properties.cnbcBaseUrl() + "/bars/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                    + "/1D/" + start.format(STAMP) + "000000/" + end.plusDays(1).format(STAMP)
                    + "000000/adjusted/EST5EDT.json");
            for (Observation o : parse(json, http.getText(uri), symbol, today)) {
                byDate.put(o.date(), o.value());
            }
        }
        List<Observation> out = new ArrayList<>();
        byDate.forEach((d, v) -> out.add(new Observation(d, v)));
        return out;
    }

    static List<Observation> parse(JsonMapper json, String body, String symbol, LocalDate today) {
        JsonNode bars;
        try {
            bars = json.readTree(body).path("barData").path("priceBars");
        } catch (JacksonException e) {
            throw new SourceException("CNBC " + symbol + ": the answer is not JSON", e);
        }
        if (!bars.isArray()) {
            throw new SourceException("CNBC " + symbol + ": no barData.priceBars in the answer");
        }
        Map<LocalDate, BigDecimal> byDate = new TreeMap<>();
        for (JsonNode bar : bars) {
            String time = bar.path("tradeTime").asString("");
            String close = bar.path("close").asString("");
            if (time.length() < 8 || close.isBlank()) {
                throw new SourceException("CNBC " + symbol + ": a bar without tradeTime / close: " + bar);
            }
            LocalDate date;
            BigDecimal value;
            try {
                date = LocalDate.parse(time.substring(0, 8), STAMP);
                value = new BigDecimal(close.trim());
            } catch (RuntimeException e) {
                throw new SourceException("CNBC " + symbol + ": unexpected bar " + bar);
            }
            boolean weekend = date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            if (date.isBefore(today) && !weekend) {
                byDate.put(date, value);   // a day sent twice: the later bar
            }
        }
        List<Observation> out = new ArrayList<>();
        byDate.forEach((d, v) -> out.add(new Observation(d, v)));
        return out;
    }
}
