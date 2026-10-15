package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CFTC Commitments of Traders, legacy futures-only report (publicreporting.cftc.gov, dataset 6dca-aqww): the weekly
 * non-commercial (speculative) long minus short positions of one contract market code ({@code 088691} = COMEX gold),
 * dated on the Tuesday the positions are of.
 */
@Component
public class CftcSource implements MacroSource {

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final MacroProperties properties;

    public CftcSource(PoliteHttpClient http, JsonMapper json, MacroProperties properties) {
        this.http = http;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "cftc";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String code = series.sourceRef();
        if (!code.matches("[0-9A-Z]{6}")) {
            throw new SourceException("CFTC: '" + code + "' is not a contract market code");
        }
        String query = "$select=" + enc("report_date_as_yyyy_mm_dd,noncomm_positions_long_all,noncomm_positions_short_all")
                + "&$where=" + enc("cftc_contract_market_code='" + code + "' AND report_date_as_yyyy_mm_dd >= '"
                + from + "T00:00:00'")
                + "&$order=" + enc("report_date_as_yyyy_mm_dd") + "&$limit=10000";
        URI uri = URI.create(properties.cftcBaseUrl() + "?" + query);
        return parse(json, http.getText(uri), code);
    }

    static List<Observation> parse(JsonMapper json, String body, String code) {
        JsonNode rows;
        try {
            rows = json.readTree(body);
        } catch (JacksonException e) {
            throw new SourceException("CFTC " + code + ": the answer is not JSON", e);
        }
        if (!rows.isArray()) {
            throw new SourceException("CFTC " + code + ": the answer is not a list of reports");
        }
        List<Observation> out = new ArrayList<>();
        LocalDate previous = null;
        for (JsonNode row : rows) {
            String date = row.path("report_date_as_yyyy_mm_dd").asString("");
            String longs = row.path("noncomm_positions_long_all").asString("");
            String shorts = row.path("noncomm_positions_short_all").asString("");
            if (date.length() < 10 || longs.isBlank() || shorts.isBlank()) {
                throw new SourceException("CFTC " + code + ": a report without date / positions: " + row);
            }
            LocalDate d = LocalDate.parse(date.substring(0, 10));
            if (previous != null && !d.isAfter(previous)) {
                continue;   // the same week twice: keep the first
            }
            out.add(new Observation(d, new BigDecimal(longs.trim()).subtract(new BigDecimal(shorts.trim()))));
            previous = d;
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
