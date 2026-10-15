package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

/**
 * FRED (Federal Reserve Bank of St. Louis): the CSV download of a series' graph
 * ({@code /graph/fredgraph.csv?id=DGS10&cosd=2026-01-01}), which needs no API key. The header is
 * {@code observation_date,<ID>}; a day without a value (a holiday) is {@code .} or empty and is left out. A monthly
 * series is dated on the 1st of its month.
 */
@Component
public class FredSource implements MacroSource {

    private final PoliteHttpClient http;
    private final MacroProperties properties;

    public FredSource(PoliteHttpClient http, MacroProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "fred";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String id = series.sourceRef();
        URI uri = URI.create(properties.fredBaseUrl() + "/graph/fredgraph.csv?id="
                + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&cosd=" + from);
        return parse(http.getText(uri), id);
    }

    static List<Observation> parse(String csv, String id) {
        String[] lines = csv.replace("\r", "").split("\n");
        if (lines.length == 0 || !lines[0].contains(",")) {
            throw new SourceException("FRED " + id + ": not a CSV (" + abbreviate(csv) + ")");
        }
        String[] header = lines[0].split(",");
        if (header.length != 2 || !header[1].trim().equalsIgnoreCase(id)) {
            throw new SourceException("FRED " + id + ": unexpected header '" + lines[0] + "'");
        }
        List<Observation> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length != 2) {
                throw new SourceException("FRED " + id + ": unexpected row '" + line + "'");
            }
            String value = cells[1].trim();
            if (value.isEmpty() || value.equals(".")) {
                continue;
            }
            try {
                out.add(new Observation(LocalDate.parse(cells[0].trim()), new BigDecimal(value)));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new SourceException("FRED " + id + ": unexpected row '" + line + "'");
            }
        }
        return out;
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }
}
