package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.neracalab.backend.crawl.SourceException;

/** Dates, months and numbers as Bank Indonesia writes them, in Indonesian or English ("19 Agustus 2026", "Mei 2026"). */
final class IndonesianDates {

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("januari", 1), Map.entry("january", 1), Map.entry("jan", 1),
            Map.entry("februari", 2), Map.entry("february", 2), Map.entry("feb", 2), Map.entry("pebruari", 2),
            Map.entry("maret", 3), Map.entry("march", 3), Map.entry("mar", 3),
            Map.entry("april", 4), Map.entry("apr", 4),
            Map.entry("mei", 5), Map.entry("may", 5),
            Map.entry("juni", 6), Map.entry("june", 6), Map.entry("jun", 6),
            Map.entry("juli", 7), Map.entry("july", 7), Map.entry("jul", 7),
            Map.entry("agustus", 8), Map.entry("august", 8), Map.entry("agu", 8), Map.entry("agt", 8), Map.entry("aug", 8),
            Map.entry("ags", 8),
            Map.entry("september", 9), Map.entry("sep", 9), Map.entry("sept", 9),
            Map.entry("oktober", 10), Map.entry("october", 10), Map.entry("okt", 10), Map.entry("oct", 10),
            Map.entry("november", 11), Map.entry("nov", 11), Map.entry("nop", 11), Map.entry("nopember", 11),
            Map.entry("desember", 12), Map.entry("december", 12), Map.entry("des", 12), Map.entry("dec", 12));

    private static final Pattern DAY = Pattern.compile("(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4})");
    private static final Pattern MONTH = Pattern.compile("([A-Za-z]+)\\s+(\\d{4})");

    private IndonesianDates() {
    }

    /** The month number of a name or abbreviation ("Agustus", "Aug", "Des*"); null when it is none. */
    static Integer month(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return t.isEmpty() ? null : MONTHS.get(t);
    }

    /** "19 Agustus 2026" -> 2026-08-19 */
    static LocalDate day(String text) {
        Matcher m = DAY.matcher(text == null ? "" : text.trim());
        Integer month = m.matches() ? month(m.group(2)) : null;
        if (month == null) {
            throw new SourceException("Bank Indonesia: '" + text + "' is not a date");
        }
        return LocalDate.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)));
    }

    /** "Agustus 2026" -> 2026-08-01 */
    static LocalDate month1st(String text) {
        Matcher m = MONTH.matcher(text == null ? "" : text.trim());
        Integer month = m.matches() ? month(m.group(1)) : null;
        if (month == null) {
            throw new SourceException("Bank Indonesia: '" + text + "' is not a month");
        }
        return LocalDate.of(Integer.parseInt(m.group(2)), month, 1);
    }

    /** "5.75 %", "3,19 %" -> 5.75, 3.19 */
    static BigDecimal percent(String text) {
        String t = text == null ? "" : text.replace("%", "").replace(' ', ' ').trim().replace(',', '.');
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new SourceException("Bank Indonesia: '" + text + "' is not a percentage");
        }
    }
}
