package com.neracalab.backend.registration;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.neracalab.backend.registration.PdfWords.Page;
import com.neracalab.backend.registration.PdfWords.Word;

/**
 * Reads a monthly registration report of securities holders (Laporan Bulanan Registrasi Pemegang Efek, LBRE), the
 * PDF a listed company files on idx.co.id every month: who it is (Kode Emiten), the month, the letter, whether it
 * corrects an earlier one, when it was filed, and the table of the shares held by its directors and commissioners
 * (Pemegang Saham Direksi dan Komisaris) at the end of the previous month and of this one.
 * <p>
 * The table is read by the position of its text, not from the plain text: a cell's text wraps over several lines
 * (names, addresses, even a long number: "3.272.048.00" / "0"), so the lines of the plain text mix the cells of a
 * row. The columns are found from the header (each header is centred over its column); a row starts at the line that
 * holds its percentage (every cell of a row is top-aligned); a row ends where the next one starts. Every share count
 * is checked against its percentage and the shares listed at the end of that month: a share count read from the
 * wrong cell does not pass. Only the first table is read (the Indonesian half; the English half repeats it).
 */
public final class RegistrationParser {

    /**
     * A director or commissioner and the shares held. {@code null} = the cell is empty ("-" is read as 0).
     *
     * @param row             1-based row of the table
     * @param position        the position as filed ("Direksi", "Komisaris", "Direktur Utama", ...)
     * @param controller      marked as the controlling shareholder (Pengendali)
     * @param controllerAffiliate marked as an affiliate of the controlling shareholder (Afiliasi Pengendali)
     */
    public record Insider(int row, String name, String position, InsiderRole role, Long sharesPrevious,
                          BigDecimal percentPrevious, Long sharesCurrent, BigDecimal percentCurrent, boolean controller,
                          boolean controllerAffiliate) {

        /** Shares bought (+) or sold (-) during the month; null when a share count is missing. */
        public Long change() {
            return sharesPrevious == null || sharesCurrent == null ? null : sharesCurrent - sharesPrevious;
        }
    }

    /**
     * A registration report.
     *
     * @param period              the month the report closes (holdings at its end)
     * @param correction          it corrects a report filed before (KOREKSI)
     * @param filedAt             filing time (Tanggal dan Waktu, Jakarta time); null when not printed
     * @param listedSharesPrevious shares listed at the end of the previous month; null when not printed
     * @param warnings            what did not add up (a percentage that does not match its share count)
     */
    public record Report(String ticker, String companyName, String letterNumber, String subject, boolean correction,
                         YearMonth period, LocalDateTime filedAt, String registrar, Long listedSharesPrevious,
                         Long listedSharesCurrent, List<Insider> insiders, List<String> warnings) {
    }

    /** The PDF is not a registration report, or its table cannot be read without guessing. */
    public static class RegistrationFormatException extends RuntimeException {

        public RegistrationFormatException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ the cover page

    private static final Pattern SUBJECT_REGISTRATION = Pattern.compile(
            "Laporan Bulanan Registrasi Pemegang Efek|Monthly Report of Securities Holders? Registration",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TICKER = Pattern.compile("(?m)^[ \\t]*(?:Kode Emiten|Issuer Code)[ \\t]+([A-Za-z0-9.-]{1,20})[ \\t]*$");
    private static final Pattern COMPANY = Pattern.compile("(?m)^[ \\t]*(?:Nama Perusahaan|Issuer Name)[ \\t]+(\\S.*?)[ \\t]*$");
    private static final Pattern LETTER = Pattern.compile("(?m)^[ \\t]*(?:Nomor Surat|Letter / Announcement No\\.?)[ \\t]+(\\S.*?)[ \\t]*$");
    private static final Pattern SUBJECT = Pattern.compile("(?m)^[ \\t]*(?:Perihal|Subject)[ \\t]+(\\S.*?)[ \\t]*$");
    private static final Pattern CORRECTION = Pattern.compile("KOREKSI|CORRECTION|Mengoreksi surat", Pattern.CASE_INSENSITIVE);
    /** "... yang berakhir pada akhir bulan Juni - 2026", "... which ends in Agustus - 2026" */
    private static final Pattern PERIOD = Pattern.compile(
            "(?:akhir\\s+bulan|ends\\s+in)\\s+(\\p{L}+)\\s*-?\\s*((?:19|20)\\d{2})\\b", Pattern.CASE_INSENSITIVE);
    /** "Tanggal dan Waktu 10-08-2026 15:14" (day-month-year, Jakarta time) */
    private static final Pattern FILED = Pattern.compile(
            "(?:Tanggal dan Waktu|Date and Time)\\s+(\\d{1,2})-(\\d{1,2})-(\\d{4})\\s+(\\d{1,2}):(\\d{2})");
    private static final Pattern REGISTRAR = Pattern.compile(
            "(?m)(?:Biro Administrasi Efek|Security Administration Bureau)[ \\t]*:[ \\t]*(\\S.*?)[ \\t]*$");
    /** The free float table: shares listed at the end of the previous month and of this one. */
    private static final Pattern LISTED = Pattern.compile(
            "(?m)^[ \\t]*(?:Jumlah saham tercatat di Bursa per akhir bulan|The number of shares listed on the Exchange at the end of the)"
                    + "[ \\t]+([\\d.,]+)[ \\t]+([\\d.,]+)[ \\t]*$");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("januari", 1), Map.entry("january", 1), Map.entry("februari", 2), Map.entry("february", 2),
            Map.entry("maret", 3), Map.entry("march", 3), Map.entry("april", 4), Map.entry("mei", 5),
            Map.entry("may", 5), Map.entry("juni", 6), Map.entry("june", 6), Map.entry("juli", 7),
            Map.entry("july", 7), Map.entry("agustus", 8), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("oktober", 10), Map.entry("october", 10), Map.entry("november", 11), Map.entry("nopember", 11),
            Map.entry("desember", 12), Map.entry("december", 12));

    // ------------------------------------------------------------------ the table

    /** Header texts of the first three columns (Indonesian, English). */
    private static final Set<String> NAME = Set.of("nama", "name");
    private static final Set<String> ADDRESS = Set.of("alamat", "address");
    private static final Set<String> POSITION = Set.of("jabatan", "position");

    /** The columns of the directors' and commissioners' table, left to right. */
    private static final int NAME_COL = 0;
    private static final int POSITION_COL = 2;
    private static final int SHARES_PREVIOUS = 3;
    private static final int PERCENT_PREVIOUS = 4;
    private static final int SHARES_CURRENT = 5;
    private static final int PERCENT_CURRENT = 6;
    private static final int CONTROLLER = 7;
    private static final int AFFILIATE = 8;
    private static final int COLUMNS = 9;

    /** Two pieces of text within this distance (points) of each other's baseline are on one line. */
    private static final double SAME_LINE = 2.0;
    /** The header's lines lie within this distance above and below the line of "Nama". */
    private static final double HEADER_ABOVE = 25;
    private static final double HEADER_BELOW = 20;
    /** Header lines of one column are centred within this distance of each other. */
    private static final double SAME_COLUMN = 6;
    /** A larger empty space between two lines of the table ends it (the lines of a cell are about 11 points apart). */
    private static final double MAX_GAP = 25;
    /** How far the text of a cell may reach beyond the outer columns' estimated edges. */
    private static final double EDGE_SLACK = 4;

    private static final Pattern PERCENT = Pattern.compile("(\\d+)(?:[.,](\\d+))?%");
    private static final Pattern SHARES = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+|\\d+");
    private static final Set<String> MARKS = Set.of("X", "V", "√", "✓", "YA", "YES");

    private RegistrationParser() {
    }

    /**
     * @throws RegistrationFormatException not a registration report, or its table cannot be read
     * @throws com.neracalab.backend.rag.PdfText.PdfException not a readable PDF
     */
    public static Report parse(byte[] pdf) {
        return parse(PdfWords.read(pdf));
    }

    static Report parse(List<Page> pages) {
        StringBuilder all = new StringBuilder();
        for (Page p : pages) {
            all.append(p.text()).append('\n');
        }
        String text = all.toString();
        String subject = first(SUBJECT, text);
        if (!SUBJECT_REGISTRATION.matcher(subject != null ? subject : text).find()) {
            throw new RegistrationFormatException("Not a monthly registration report of securities holders "
                    + "(Laporan Bulanan Registrasi Pemegang Efek) from idx.co.id");
        }
        String ticker = first(TICKER, text);
        if (ticker == null) {
            throw new RegistrationFormatException("The report does not name its company code (Kode Emiten)");
        }
        YearMonth period = period(text);
        boolean correction = (subject != null && CORRECTION.matcher(subject).find())
                || text.toLowerCase(Locale.ROOT).contains("mengoreksi surat");
        Matcher listed = LISTED.matcher(text);
        Long listedPrevious = null;
        Long listedCurrent = null;
        if (listed.find()) {
            listedPrevious = shares(listed.group(1), "the shares listed at the end of the previous month");
            listedCurrent = shares(listed.group(2), "the shares listed at the end of the month");
        }
        List<String> warnings = new ArrayList<>();
        if (listedPrevious == null || listedCurrent == null) {
            warnings.add("The shares listed on the exchange are not printed: the percentages were not checked");
        }
        List<Insider> insiders = insiders(pages, listedPrevious, listedCurrent, warnings);
        return new Report(ticker.toUpperCase(Locale.ROOT), first(COMPANY, text), first(LETTER, text), subject,
                correction, period, filedAt(text), first(REGISTRAR, text), listedPrevious, listedCurrent,
                List.copyOf(insiders), List.copyOf(warnings));
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim().replaceAll("\\s+", " ") : null;
    }

    private static YearMonth period(String text) {
        Matcher m = PERIOD.matcher(text);
        if (!m.find()) {
            throw new RegistrationFormatException("The report does not say which month it closes "
                    + "(\"... pada akhir bulan <month> - <year>\")");
        }
        Integer month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
        if (month == null) {
            throw new RegistrationFormatException("Unknown month '" + m.group(1) + "' in the report's period");
        }
        return YearMonth.of(Integer.parseInt(m.group(2)), month);
    }

    private static LocalDateTime filedAt(String text) {
        Matcher m = FILED.matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the directors' and commissioners' table

    /** Where the columns are: a word belongs to the column its centre falls in. */
    record Columns(double left, double right, double[] bounds) {

        /** The column of a word (0 .. 8), or -1 outside the table. */
        int of(Word w) {
            double c = w.center();
            if (c < left || c > right) {
                return -1;
            }
            for (int k = 0; k < bounds.length; k++) {
                if (c < bounds[k]) {
                    return k;
                }
            }
            return bounds.length;
        }
    }

    /** The header of the table: its columns, and the lowest line of its text. */
    record Header(Columns columns, double nameY, double bottom) {
    }

    /** A line of the table: its page and baseline, and its words with their column. */
    private record Line(int page, double y, List<Word> words, List<Integer> columns) {
    }

    private static List<Insider> insiders(List<Page> pages, Long listedPrevious, Long listedCurrent,
                                          List<String> warnings) {
        int start = -1;
        Header header = null;
        for (int i = 0; i < pages.size() && header == null; i++) {
            header = directorsHeader(pages.get(i), Double.NEGATIVE_INFINITY);
            start = i;
        }
        if (header == null) {
            throw new RegistrationFormatException("The table of the directors' and commissioners' shares "
                    + "(Pemegang Saham Direksi dan Komisaris: Nama, Alamat, Jabatan, ...) is not in the report");
        }
        List<Line> lines = new ArrayList<>();
        Columns columns = header.columns();
        double from = header.bottom();
        Double last = header.bottom();
        for (int i = start; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (i > start) {
                // the table goes on over the page: the header may be printed again at the top
                Header again = directorsHeader(page, Double.NEGATIVE_INFINITY);
                Double next = nextTableY(page, Double.NEGATIVE_INFINITY);
                if (again != null && (next == null || again.nameY() <= next)) {
                    columns = again.columns();
                    from = again.bottom();
                } else {
                    from = Double.NEGATIVE_INFINITY;
                }
                last = null;
            }
            Double nextTable = nextTableY(page, from + SAME_LINE);
            double end = nextTable == null ? Double.POSITIVE_INFINITY : nextTable - HEADER_ABOVE;
            boolean ended = nextTable != null;
            for (List<Word> lineWords : lines(page.words(), from + SAME_LINE, end)) {
                double y = lineWords.getFirst().y();
                if (crossesTheEdge(page.runs(), y, columns)) {
                    ended = true;     // text that is not in a cell of the table: the table is over
                    break;
                }
                List<Word> in = new ArrayList<>();
                List<Integer> cols = new ArrayList<>();
                for (Word w : lineWords) {
                    int c = columns.of(w);
                    if (c >= 0) {
                        in.add(w);
                        cols.add(c);
                    }
                }
                if (in.isEmpty()) {
                    continue;     // the row label beside the table (Pemegang Saham Direksi dan Komisaris)
                }
                if (last != null && y - last > MAX_GAP) {
                    ended = true;
                    break;
                }
                last = y;
                lines.add(new Line(page.number(), y, in, cols));
            }
            if (ended) {
                break;
            }
        }
        return rows(lines, listedPrevious, listedCurrent, warnings);
    }

    /**
     * The header of the directors' table on the page below {@code afterY}: a line with "Nama", "Alamat" and
     * "Jabatan" (or "Name", "Address", "Position"); null when the page has none.
     */
    static Header directorsHeader(Page page, double afterY) {
        for (List<Word> line : lines(page.runs(), afterY, Double.POSITIVE_INFINITY)) {
            Word name = find(line, NAME);
            Word address = find(line, ADDRESS);
            Word position = find(line, POSITION);
            if (name == null || address == null || position == null
                    || !(name.center() < address.center() && address.center() < position.center())) {
                continue;
            }
            double nameY = name.y();
            double minCenter = name.center() - 0.6 * (address.center() - name.center());
            List<Word> band = page.runs().stream()
                    .filter(w -> w.y() >= nameY - HEADER_ABOVE && w.y() <= nameY + HEADER_BELOW && w.center() >= minCenter)
                    .sorted(Comparator.comparingDouble(Word::center))
                    .toList();
            List<List<Word>> clusters = new ArrayList<>();
            for (Word w : band) {
                List<Word> current = clusters.isEmpty() ? null : clusters.getLast();
                if (current == null || w.center() - current.getFirst().center() > SAME_COLUMN) {
                    current = new ArrayList<>();
                    clusters.add(current);
                }
                current.add(w);
            }
            if (clusters.size() != COLUMNS) {
                throw new RegistrationFormatException("The table of the directors' and commissioners' shares on page "
                        + page.number() + " has " + clusters.size() + " columns, expected " + COLUMNS
                        + " (name, address, position, shares and percentage of the previous month and of this one, "
                        + "controller, affiliate of the controller): " + clusters.stream()
                        .map(c -> String.join(" ", c.stream().map(Word::text).toList())).toList());
            }
            checkHeader(clusters, page.number());
            double[] bounds = new double[COLUMNS - 1];
            for (int k = 0; k < COLUMNS - 1; k++) {
                double right = clusters.get(k).stream().mapToDouble(Word::x1).max().orElseThrow();
                double left = clusters.get(k + 1).stream().mapToDouble(Word::x0).min().orElseThrow();
                bounds[k] = right <= left ? (right + left) / 2
                        : (center(clusters.get(k)) + center(clusters.get(k + 1))) / 2;
            }
            double firstCenter = center(clusters.getFirst());
            double lastCenter = center(clusters.getLast());
            double tableLeft = firstCenter - (bounds[0] - firstCenter) - EDGE_SLACK;
            double tableRight = lastCenter + (lastCenter - bounds[COLUMNS - 2]) + EDGE_SLACK;
            double bottom = band.stream().mapToDouble(Word::y).max().orElseThrow();
            return new Header(new Columns(tableLeft, tableRight, bounds), nameY, bottom);
        }
        return null;
    }

    private static double center(List<Word> cluster) {
        return cluster.getFirst().center();
    }

    /** The header columns read left to right must be those of the directors' table. */
    private static void checkHeader(List<List<Word>> clusters, int page) {
        String[][] expected = {
                {"nama", "name"}, {"alamat", "address"}, {"jabatan", "position"},
                {"sebelum", "previous"}, {"sebelum", "previous"}, {"ini", "current"}, {"ini", "current"},
                {"pengendali", "controller"}, {"afiliasi", "affiliate"}};
        for (int k = 0; k < COLUMNS; k++) {
            String label = String.join(" ", clusters.get(k).stream().map(Word::text).toList());
            String lower = label.toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String e : expected[k]) {
                ok |= lower.matches("(?s).*\\b" + e + ".*");
            }
            if (k == PERCENT_PREVIOUS || k == PERCENT_CURRENT) {
                ok &= lower.contains("persen") || lower.contains("percent");
            }
            if (k == CONTROLLER) {
                ok &= !lower.contains("afiliasi") && !lower.contains("affiliate");
            }
            if (!ok) {
                throw new RegistrationFormatException("Column " + (k + 1) + " of the directors' and commissioners' table "
                        + "on page " + page + " is '" + label + "', not the expected one");
            }
        }
    }

    /** The baseline of the next table's "Nama" header (any table, e.g. the shareholders of 5% or more) below {@code afterY}. */
    private static Double nextTableY(Page page, double afterY) {
        for (List<Word> line : lines(page.runs(), afterY, Double.POSITIVE_INFINITY)) {
            if (find(line, NAME) != null && find(line, ADDRESS) != null) {
                return line.getFirst().y();
            }
        }
        return null;
    }

    /** Text on the line that sits across the table's left or right edge, or right of it: not a cell of the table. */
    private static boolean crossesTheEdge(List<Word> runs, double y, Columns columns) {
        for (Word r : runs) {
            if (Math.abs(r.y() - y) > SAME_LINE) {
                continue;
            }
            boolean acrossLeft = r.x0() < columns.left() && r.x1() > columns.left() + EDGE_SLACK;
            boolean acrossRight = r.x0() < columns.right() - EDGE_SLACK && r.x1() > columns.right();
            if (acrossLeft || acrossRight || r.x0() >= columns.right()) {
                return true;
            }
        }
        return false;
    }

    private static Word find(List<Word> line, Set<String> labels) {
        for (Word w : line) {
            if (labels.contains(w.text().trim().toLowerCase(Locale.ROOT))) {
                return w;
            }
        }
        return null;
    }

    /** The pieces of text with a baseline in ({@code afterY}, {@code beforeY}), grouped into lines, top to bottom. */
    private static List<List<Word>> lines(List<Word> words, double afterY, double beforeY) {
        List<Word> sorted = words.stream()
                .filter(w -> w.y() > afterY && w.y() < beforeY)
                .sorted(Comparator.comparingDouble(Word::y).thenComparingDouble(Word::x0))
                .toList();
        List<List<Word>> lines = new ArrayList<>();
        for (Word w : sorted) {
            List<Word> line = lines.isEmpty() ? null : lines.getLast();
            if (line == null || w.y() - line.getFirst().y() > SAME_LINE) {
                line = new ArrayList<>();
                lines.add(line);
            }
            line.add(w);
        }
        for (List<Word> line : lines) {
            line.sort(Comparator.comparingDouble(Word::x0));
        }
        return lines;
    }

    // ------------------------------------------------------------------ rows

    private static List<Insider> rows(List<Line> lines, Long listedPrevious, Long listedCurrent, List<String> warnings) {
        List<List<Line>> rows = new ArrayList<>();
        for (Line line : lines) {
            if (startsARow(line)) {
                rows.add(new ArrayList<>());
            } else if (rows.isEmpty()) {
                throw new RegistrationFormatException("Unexpected text above the first row of the directors' and "
                        + "commissioners' table (page " + line.page() + "): '" + String.join(" ",
                        line.words().stream().map(Word::text).toList()) + "'");
            }
            rows.getLast().add(line);
        }
        List<Insider> insiders = new ArrayList<>();
        for (List<Line> row : rows) {
            insiders.add(insider(insiders.size() + 1, row, listedPrevious, listedCurrent, warnings));
        }
        return insiders;
    }

    /** A row starts at the line with its percentage (of the previous month or of this one). */
    private static boolean startsARow(Line line) {
        for (int i = 0; i < line.words().size(); i++) {
            int c = line.columns().get(i);
            if ((c == PERCENT_PREVIOUS || c == PERCENT_CURRENT) && PERCENT.matcher(line.words().get(i).text()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Insider insider(int number, List<Line> row, Long listedPrevious, Long listedCurrent,
                                   List<String> warnings) {
        StringBuilder[] cells = new StringBuilder[COLUMNS];
        for (int k = 0; k < COLUMNS; k++) {
            cells[k] = new StringBuilder();
        }
        for (Line line : row) {
            for (int i = 0; i < line.words().size(); i++) {
                int c = line.columns().get(i);
                boolean numeric = c >= SHARES_PREVIOUS && c <= PERCENT_CURRENT;
                if (!cells[c].isEmpty() && !numeric) {
                    cells[c].append(' ');
                }
                // a number wrapped in its cell ("3.272.048.00" / "0") is joined again without a blank
                cells[c].append(line.words().get(i).text());
            }
        }
        String name = cells[NAME_COL].toString().trim();
        String position = cells[POSITION_COL].toString().trim();
        String where = "row " + number + (name.isEmpty() ? "" : " (" + name + ")") + " of the directors' and "
                + "commissioners' table, page " + row.getFirst().page();
        if (name.isEmpty() || !name.matches("(?s).*\\p{L}.*")) {
            throw new RegistrationFormatException("No name in " + where);
        }
        Long sharesPrevious = shares(cells[SHARES_PREVIOUS].toString(), where);
        BigDecimal percentPrevious = percent(cells[PERCENT_PREVIOUS].toString(), where);
        Long sharesCurrent = shares(cells[SHARES_CURRENT].toString(), where);
        BigDecimal percentCurrent = percent(cells[PERCENT_CURRENT].toString(), where);
        if (sharesPrevious == null && sharesCurrent == null) {
            throw new RegistrationFormatException("No share count in " + where);
        }
        check(sharesPrevious, percentPrevious, listedPrevious, "the previous month", where, warnings);
        check(sharesCurrent, percentCurrent, listedCurrent, "this month", where, warnings);
        return new Insider(number, name, position, InsiderRole.ofPosition(position), sharesPrevious, percentPrevious,
                sharesCurrent, percentCurrent, mark(cells[CONTROLLER].toString(), where),
                mark(cells[AFFILIATE].toString(), where));
    }

    /** A share count: "2.157.500", "2,157,500", "2157500"; "-" is 0; empty is null. */
    static Long shares(String cell, String where) {
        String s = cell.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.equals("-")) {
            return 0L;
        }
        if (!SHARES.matcher(s).matches()) {
            throw new RegistrationFormatException("'" + s + "' is not a share count (" + where + ")");
        }
        try {
            return Long.parseLong(s.replace(".", "").replace(",", ""));
        } catch (NumberFormatException e) {
            throw new RegistrationFormatException("'" + s + "' is not a share count (" + where + ")");
        }
    }

    /** A percentage: "0,05%", "0.05%", "71%"; "-" is 0; empty is null. */
    static BigDecimal percent(String cell, String where) {
        String s = cell.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.equals("-")) {
            return BigDecimal.ZERO;
        }
        Matcher m = PERCENT.matcher(s);
        if (!m.matches()) {
            throw new RegistrationFormatException("'" + s + "' is not a percentage (" + where + ")");
        }
        return new BigDecimal(m.group(1) + (m.group(2) == null ? "" : "." + m.group(2)));
    }

    /**
     * The percentage must be the share count over the shares listed, give or take one unit of its last decimal (the
     * reports round, and some cut off: 1,310,552,200 of 4,605,262,400 = 28.4577 % is printed 28,45 %).
     */
    private static void check(Long shares, BigDecimal percent, Long listed, String month, String where,
                              List<String> warnings) {
        if (shares == null || percent == null || listed == null || listed <= 0) {
            return;
        }
        BigDecimal actual = BigDecimal.valueOf(shares).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(listed), MathContext.DECIMAL64);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(Math.max(percent.scale(), 0));
        if (actual.subtract(percent).abs().compareTo(unit) > 0) {
            warnings.add(String.format(Locale.ROOT, "%s: %,d shares are %.4f %% of the %,d shares listed at the end of %s, but "
                    + "the report prints %s %%", where, shares, actual, listed, month, percent.toPlainString()));
        }
    }

    /** A mark of the controller columns: "X" (or a tick) is yes, empty is no. */
    private static boolean mark(String cell, String where) {
        String s = cell.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("-")) {
            return false;
        }
        if (MARKS.contains(s)) {
            return true;
        }
        throw new RegistrationFormatException("'" + cell.trim() + "' in a controller column is not a mark (" + where + ")");
    }
}
