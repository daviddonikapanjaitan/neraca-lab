package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

/**
 * NOAA Climate Prediction Center ({@code source = noaa_cpc}): El Nino / La Nina. {@code source_ref} names what is read:
 * <ul>
 *   <li>{@code RONI.ascii.txt}, {@code oni.ascii.txt}: 3-month seasons ({@code SEAS YR [TOTAL] ANOM}, the anomaly is the
 *       last column). A season is dated on the 1st of its <b>last</b> month, so it is public only after it ended: CPC
 *       labels DJF with the year of January (DJF 1950 = Dec 1949 - Feb 1950 -> 1950-02-01) and NDJ with the year of
 *       November (NDJ 2024 -> 2025-01-01).</li>
 *   <li>{@code wksst9120.for#NINO34}: the weekly OISST table, fixed width with the numbers run together
 *       ({@code 20.6-0.1}); dated on the week's centre day as labelled ({@code 11NOV2026}). The Nino 3.4 anomaly is the
 *       6th number (Nino 1+2, 3, 3.4, 4 each give SST and anomaly).</li>
 *   <li>{@code probabilities#ELNINO#3} (or {@code LANINA}, {@code 3} or {@code 6}): the official ENSO probability
 *       table; row {@code k} is the 3-month season centred {@code k} months after the issue month (row 0 is centred on
 *       it, which is checked). Dated on the second Thursday of the issue month ("Issued November 2026"), the day CPC
 *       publishes its ENSO discussion. A full fetch also reads the monthly archive pages (from April 2026, when CPC moved
 *       to RONI); a month without an archived table is skipped.</li>
 * </ul>
 */
@Component
public class NoaaCpcSource implements MacroSource {

    static final String PROBABILITIES_PATH = "/products/analysis_monitoring/enso/roni/probabilities/";
    static final String ARCHIVE_PATH = "/products/analysis_monitoring/enso/roni/archives/";
    /** The seasons of the ONI / RONI files, DJF first; season {@code i} is centred on month {@code i + 1}. */
    static final List<String> SEASONS = List.of("DJF", "JFM", "FMA", "MAM", "AMJ", "MJJ", "JJA", "JAS", "ASO", "SON",
            "OND", "NDJ");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+\\.\\d+");
    private static final Pattern ISSUED = Pattern.compile("Issued\\s+([A-Za-z]+)\\s+(\\d{4})");
    private static final DateTimeFormatter WEEK = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("ddMMMyyyy").toFormatter(Locale.ENGLISH);

    private final PoliteHttpClient http;
    private final MacroProperties properties;

    public NoaaCpcSource(PoliteHttpClient http, MacroProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "noaa_cpc";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String ref = series.sourceRef() == null ? "" : series.sourceRef().trim();
        if (ref.startsWith("probabilities#")) {
            return probabilities(ref, from, today);
        }
        URI uri = URI.create(properties.cpcBaseUrl() + "/data/indices/" + ref.replaceFirst("#.*", ""));
        String text = http.getText(uri);
        if (ref.equalsIgnoreCase("RONI.ascii.txt") || ref.equalsIgnoreCase("oni.ascii.txt")) {
            return seasons(text, ref);
        }
        if (ref.equalsIgnoreCase("wksst9120.for#NINO34")) {
            return weeklyNino34(text);
        }
        throw new SourceException("NOAA CPC: unknown source_ref '" + ref + "' of " + series.code());
    }

    /** The anomaly of every season of an ONI / RONI file, dated on the 1st of the season's last month. */
    static List<Observation> seasons(String text, String ref) {
        String[] lines = text.replace("\r", "").split("\n");
        if (lines.length < 2 || !lines[0].trim().toUpperCase(Locale.ROOT).startsWith("SEAS")) {
            throw new SourceException("NOAA CPC " + ref + ": unexpected header '" + abbreviate(lines.length == 0 ? "" : lines[0]) + "'");
        }
        List<Observation> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split("\\s+");
            int season = cells.length >= 3 ? SEASONS.indexOf(cells[0].toUpperCase(Locale.ROOT)) : -1;
            if (season < 0) {
                throw new SourceException("NOAA CPC " + ref + ": unexpected row '" + abbreviate(line) + "'");
            }
            try {
                int year = Integer.parseInt(cells[1]);
                BigDecimal anomaly = new BigDecimal(cells[cells.length - 1]);
                out.add(new Observation(seasonEnd(season, year), anomaly));
            } catch (NumberFormatException e) {
                throw new SourceException("NOAA CPC " + ref + ": unexpected row '" + abbreviate(line) + "'");
            }
        }
        if (out.isEmpty()) {
            throw new SourceException("NOAA CPC " + ref + ": no season in the file");
        }
        return out;
    }

    /** The 1st of the last month of season {@code season} (0 = DJF) labelled {@code year} by CPC. */
    static LocalDate seasonEnd(int season, int year) {
        // centred on month season + 1 of the label year (DJF: January); the last month is the next one (NDJ: January
        // of the following year)
        return LocalDate.of(year, season + 1, 1).plusMonths(1);
    }

    /** The weekly Nino 3.4 anomaly, dated on the centre day of each week. */
    static List<Observation> weeklyNino34(String text) {
        List<Observation> out = new ArrayList<>();
        boolean header = false;
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            if (line.toUpperCase(Locale.ROOT).contains("NINO34")) {
                header = true;
                continue;
            }
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                continue;
            }
            String[] head = line.split("\\s+", 2);
            LocalDate date;
            try {
                date = LocalDate.parse(head[0], WEEK);
            } catch (DateTimeParseException e) {
                throw new SourceException("NOAA CPC wksst9120.for: unexpected week '" + abbreviate(line) + "'");
            }
            List<String> numbers = new ArrayList<>();
            Matcher m = NUMBER.matcher(head.length > 1 ? head[1] : "");
            while (m.find()) {
                numbers.add(m.group());
            }
            if (numbers.size() != 8) {
                throw new SourceException("NOAA CPC wksst9120.for: expected 8 numbers in '" + abbreviate(line) + "'");
            }
            out.add(new Observation(date, new BigDecimal(numbers.get(5))));
        }
        if (!header || out.isEmpty()) {
            throw new SourceException("NOAA CPC wksst9120.for: no Nino34 column or no week");
        }
        return out;
    }

    // ------------------------------------------------------------------ probabilities

    /** One issue of the official ENSO probabilities: the issue month and, per row, the chances of La Nina / El Nino. */
    record Issue(YearMonth month, List<int[]> rows) {
    }

    private List<Observation> probabilities(String ref, LocalDate from, LocalDate today) {
        String[] parts = ref.split("#");
        if (parts.length != 3 || !(parts[1].equals("ELNINO") || parts[1].equals("LANINA"))) {
            throw new SourceException("NOAA CPC: unknown source_ref '" + ref + "' (expected probabilities#ELNINO|LANINA#<months>)");
        }
        boolean elNino = parts[1].equals("ELNINO");
        int ahead = Integer.parseInt(parts[2]);
        Issue current = parseProbabilities(http.getText(URI.create(properties.cpcBaseUrl() + PROBABILITIES_PATH)));
        List<Issue> issues = new ArrayList<>();
        // the months before the current issue that the window reaches: their archived tables
        for (YearMonth m = YearMonth.from(from); m.isBefore(current.month()); m = m.plusMonths(1)) {
            URI uri = URI.create(properties.cpcBaseUrl() + ARCHIVE_PATH + "?type=probabilities&month="
                    + String.format(Locale.ROOT, "%02d", m.getMonthValue()) + "&year=" + m.getYear());
            PoliteHttpClient.Response response = http.get(uri);
            if (response.status() == 404) {
                continue;
            }
            PoliteHttpClient.requireOk(uri, response);
            Issue archived = parseOptional(response.text());
            if (archived != null && archived.month().equals(m)) {
                issues.add(archived);
            }
        }
        issues.add(current);
        List<Observation> out = new ArrayList<>();
        for (Issue issue : issues) {
            if (ahead >= issue.rows().size()) {
                throw new SourceException("NOAA CPC probabilities of " + issue.month() + ": no season " + ahead
                        + " months ahead (" + issue.rows().size() + " rows)");
            }
            LocalDate date = issueDate(issue.month());
            if (date.isAfter(today)) {
                continue;   // a page labelled with a month whose second Thursday is still to come
            }
            int[] row = issue.rows().get(ahead);
            out.add(new Observation(date, BigDecimal.valueOf(elNino ? row[1] : row[0])));
        }
        return out;
    }

    private static Issue parseOptional(String html) {
        return table(Jsoup.parse(html)) == null ? null : parseProbabilities(html);
    }

    /** The probability table: {@code #probabilities-table} on the current page, {@code #enso-archives-table} archived. */
    private static Element table(Document doc) {
        Element t = doc.getElementById("probabilities-table");
        return t != null ? t : doc.getElementById("enso-archives-table");
    }

    /**
     * The issue month ("Issued November 2026") and the rows of {@code table#probabilities-table}: {La Nina, El Nino} in
     * percent per season. The columns are found by their headings; row 0 must be the season centred on the issue month.
     */
    static Issue parseProbabilities(String html) {
        Document doc = Jsoup.parse(html);
        Matcher issued = ISSUED.matcher(doc.text());
        if (!issued.find()) {
            throw new SourceException("NOAA CPC probabilities: no 'Issued <month> <year>' on the page");
        }
        Month month;
        try {
            month = Month.valueOf(issued.group(1).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new SourceException("NOAA CPC probabilities: unknown issue month '" + issued.group(1) + "'");
        }
        YearMonth issue = YearMonth.of(Integer.parseInt(issued.group(2)), month);
        Element table = table(doc);
        if (table == null) {
            throw new SourceException("NOAA CPC probabilities: no table#probabilities-table (or #enso-archives-table) "
                    + "on the page");
        }
        int laNina = -1;
        int elNino = -1;
        List<Element> headings = table.select("tr.cell-headings th");
        for (int i = 0; i < headings.size(); i++) {
            String h = headings.get(i).text().toLowerCase(Locale.ROOT);
            if (h.startsWith("la ni")) {
                laNina = i;
            } else if (h.startsWith("el ni")) {
                elNino = i;
            }
        }
        if (laNina < 1 || elNino < 1) {
            throw new SourceException("NOAA CPC probabilities: the table has no La Nina / El Nino columns");
        }
        List<int[]> rows = new ArrayList<>();
        for (Element tr : table.select("tbody tr")) {
            if (tr.hasClass("cell-headings")) {
                continue;
            }
            Element th = tr.selectFirst("th");
            List<Element> tds = tr.select("td");
            if (th == null || tds.size() < Math.max(laNina, elNino)) {
                continue;
            }
            String season = th.text().trim();
            season = season.length() >= 3 ? season.substring(0, 3).toUpperCase(Locale.ROOT) : season;
            int index = SEASONS.indexOf(season);
            int expected = (issue.getMonthValue() - 1 + rows.size()) % 12;   // centred on issue + row
            if (index != expected) {
                throw new SourceException("NOAA CPC probabilities of " + issue + ": row " + rows.size() + " is " + season
                        + ", expected " + SEASONS.get(expected));
            }
            try {
                rows.add(new int[] {Integer.parseInt(tds.get(laNina - 1).text().trim()),
                        Integer.parseInt(tds.get(elNino - 1).text().trim())});
            } catch (NumberFormatException e) {
                throw new SourceException("NOAA CPC probabilities of " + issue + ": not a percentage in row " + season);
            }
        }
        if (rows.isEmpty()) {
            throw new SourceException("NOAA CPC probabilities of " + issue + ": the table has no season");
        }
        return new Issue(issue, rows);
    }

    /** The second Thursday of a month: CPC issues its ENSO diagnostic discussion and probabilities that day. */
    static LocalDate issueDate(YearMonth month) {
        return month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.THURSDAY));
    }

    private static String abbreviate(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() > 80 ? t.substring(0, 80) + "..." : t;
    }
}
