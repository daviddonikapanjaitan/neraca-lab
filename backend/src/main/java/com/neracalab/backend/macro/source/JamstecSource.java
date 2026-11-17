package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

/**
 * JAMSTEC (Japan Agency for Marine-Earth Science and Technology) SINTEX-F climate index files ({@code source =
 * jamstec}; {@code source_ref} {@code <file>#<column>}, e.g. {@code SINTEX_DMI.csv#Obs}, the observed monthly Indian
 * Ocean Dipole Mode Index). A CSV with a header ({@code time,Obs,Mean,...}); a month is labelled on its 15th
 * ({@code 2026-10-15}) and dated on its 1st; rows without a value in the column (the forecast months) are left out.
 * The Bureau of Meteorology's weekly IOD is not used: its site refuses a crawler that names itself (HTTP 403).
 */
@Component
public class JamstecSource implements MacroSource {

    private final PoliteHttpClient http;
    private final MacroProperties properties;

    public JamstecSource(PoliteHttpClient http, MacroProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "jamstec";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String ref = series.sourceRef() == null ? "" : series.sourceRef().trim();
        int hash = ref.indexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            throw new SourceException("JAMSTEC: source_ref '" + ref + "' of " + series.code() + " is not <file>#<column>");
        }
        URI uri = URI.create(properties.jamstecBaseUrl() + "/" + ref.substring(0, hash));
        return parse(http.getText(uri), ref.substring(0, hash), ref.substring(hash + 1));
    }

    static List<Observation> parse(String csv, String file, String column) {
        String[] lines = csv.replace("\r", "").split("\n");
        if (lines.length < 2) {
            throw new SourceException("JAMSTEC " + file + ": not a CSV");
        }
        String[] header = lines[0].split(",", -1);
        if (!header[0].trim().equalsIgnoreCase("time")) {
            throw new SourceException("JAMSTEC " + file + ": unexpected header '" + abbreviate(lines[0]) + "'");
        }
        int col = -1;
        for (int i = 1; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(column)) {
                col = i;
            }
        }
        if (col < 0) {
            throw new SourceException("JAMSTEC " + file + ": no column '" + column + "'");
        }
        List<Observation> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length <= col) {
                throw new SourceException("JAMSTEC " + file + ": short row '" + abbreviate(line) + "'");
            }
            String value = cells[col].trim();
            if (value.isEmpty() || value.toLowerCase(Locale.ROOT).equals("nan")) {
                continue;
            }
            try {
                LocalDate month = LocalDate.parse(cells[0].trim()).withDayOfMonth(1);
                out.add(new Observation(month, new BigDecimal(value).setScale(3, RoundingMode.HALF_UP)));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new SourceException("JAMSTEC " + file + ": unexpected row '" + abbreviate(line) + "'");
            }
        }
        if (out.isEmpty()) {
            throw new SourceException("JAMSTEC " + file + ": no value in column " + column);
        }
        return out;
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }
}
