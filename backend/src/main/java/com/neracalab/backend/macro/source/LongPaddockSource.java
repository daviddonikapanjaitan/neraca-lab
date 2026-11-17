package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

/**
 * Queensland Government's Long Paddock SOI files ({@code source = longpaddock}): the Southern Oscillation Index as the
 * Bureau of Meteorology scales it (1887 - 1989 base). {@code source_ref}:
 * <ul>
 *   <li>{@code MonthlySOI1887-1989Base.txt}: a header {@code Year Month SOI}, then {@code 2026 10 -14.76}; a month is
 *       dated on its 1st.</li>
 *   <li>{@code DailySOI1887-1989Base.txt#30D}: the daily SOI ({@code Year Day Tahiti Darwin SOI}, Day the day of the
 *       year) averaged over the last 30 days, dated on the window's last day - Long Paddock's "average SOI for last 30
 *       days" (checked: -11.00 on 2026-11-16 both ways). A window with a day missing is left out.</li>
 * </ul>
 * The Bureau of Meteorology's own SOI files are not used: its site refuses a crawler that names itself (HTTP 403).
 */
@Component
public class LongPaddockSource implements MacroSource {

    private final PoliteHttpClient http;
    private final MacroProperties properties;

    public LongPaddockSource(PoliteHttpClient http, MacroProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "longpaddock";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String ref = series.sourceRef() == null ? "" : series.sourceRef().trim();
        if (ref.endsWith("#30D")) {
            String file = ref.substring(0, ref.length() - 4);
            return thirtyDay(parseDaily(http.getText(URI.create(properties.longPaddockBaseUrl() + "/" + file)), file));
        }
        URI uri = URI.create(properties.longPaddockBaseUrl() + "/" + ref);
        return parseMonthly(http.getText(uri), ref);
    }

    /** The daily SOI: {@code Year Day Tahiti Darwin SOI}, Day the day of the year. */
    static List<Observation> parseDaily(String text, String ref) {
        String[] lines = text.replace("\r", "").split("\n");
        if (lines.length < 2 || !lines[0].trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                .startsWith("year day tahiti darwin soi")) {
            throw new SourceException("Long Paddock " + ref + ": unexpected header '"
                    + (lines.length == 0 ? "" : lines[0].strip()) + "' (expected Year Day Tahiti Darwin SOI)");
        }
        List<Observation> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split("\\s+");
            if (cells.length != 5) {
                throw new SourceException("Long Paddock " + ref + ": unexpected row '" + line + "'");
            }
            try {
                out.add(new Observation(LocalDate.ofYearDay(Integer.parseInt(cells[0]), Integer.parseInt(cells[1])),
                        new BigDecimal(cells[4])));
            } catch (NumberFormatException | DateTimeException e) {
                throw new SourceException("Long Paddock " + ref + ": unexpected row '" + line + "'");
            }
        }
        if (out.isEmpty()) {
            throw new SourceException("Long Paddock " + ref + ": the file has no day");
        }
        return out;
    }

    /** The mean of every 30 consecutive days, dated on the last; a window with a missing day is left out. */
    static List<Observation> thirtyDay(List<Observation> daily) {
        List<Observation> out = new ArrayList<>();
        for (int i = 29; i < daily.size(); i++) {
            if (!daily.get(i - 29).date().plusDays(29).equals(daily.get(i).date())) {
                continue;   // a gap (or a duplicate) inside the window
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int k = i - 29; k <= i; k++) {
                sum = sum.add(daily.get(k).value());
            }
            out.add(new Observation(daily.get(i).date(), sum.divide(BigDecimal.valueOf(30), 2, java.math.RoundingMode.HALF_UP)));
        }
        return out;
    }

    static List<Observation> parseMonthly(String text, String ref) {
        String[] lines = text.replace("\r", "").split("\n");
        if (lines.length < 2 || !lines[0].trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").startsWith("year month soi")) {
            throw new SourceException("Long Paddock " + ref + ": unexpected header '"
                    + (lines.length == 0 ? "" : lines[0].strip()) + "' (expected Year Month SOI)");
        }
        List<Observation> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split("\\s+");
            if (cells.length != 3) {
                throw new SourceException("Long Paddock " + ref + ": unexpected row '" + line + "'");
            }
            try {
                out.add(new Observation(LocalDate.of(Integer.parseInt(cells[0]), Integer.parseInt(cells[1]), 1),
                        new BigDecimal(cells[2])));
            } catch (NumberFormatException | DateTimeException e) {
                throw new SourceException("Long Paddock " + ref + ": unexpected row '" + line + "'");
            }
        }
        if (out.isEmpty()) {
            throw new SourceException("Long Paddock " + ref + ": the file has no month");
        }
        return out;
    }
}
