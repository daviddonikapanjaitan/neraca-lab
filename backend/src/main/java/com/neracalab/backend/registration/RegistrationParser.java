package com.neracalab.backend.registration;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 * corrects an earlier one, when it was filed, the table of the shares held by its directors and commissioners
 * (Pemegang Saham Direksi dan Komisaris) at the end of the previous month and of this one, the table of the
 * shareholders of 5% or more (Pemegang saham 5% atau lebih: the controller and the other large holders) and the
 * summary of the shares by type of holder (the controller, its affiliates, the directors and commissioners, the
 * treasury shares).
 * <p>
 * The tables are read by the position of their text, not from the plain text: a cell's text wraps over several lines
 * (names, addresses, even a long number: "3.272.048.00" / "0"), so the lines of the plain text mix the cells of a
 * row. The columns are found from the header (each header is centred over its column); a row starts at the line that
 * holds its percentage (every cell of a row is top-aligned); a row ends where the next one starts. Every share count
 * is checked against its percentage and the shares listed at the end of that month: a share count read from the
 * wrong cell does not pass. Only the first table of each kind is read (the Indonesian half; the English half repeats
 * it). The shareholders' table and the summary are read when they can be: one that cannot be read is a warning, never
 * a reason to refuse the report. A report without the directors' table is read when it has one of the other two (a
 * warning says so), else refused.
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
     * A shareholder of 5% or more (the controller is one when it holds that much). {@code null} = the cell is empty.
     *
     * @param row                 1-based row of the table
     * @param controller          marked as the controlling shareholder (Pengendali)
     * @param controllerAffiliate marked as an affiliate of the controlling shareholder
     */
    public record Holder(int row, String name, Long sharesPrevious, BigDecimal percentPrevious, Long sharesCurrent,
                         BigDecimal percentCurrent, boolean controller, boolean controllerAffiliate) {

        /** Shares bought (+) or sold (-) during the month; null when a share count is missing. */
        public Long change() {
            return sharesPrevious == null || sharesCurrent == null ? null : sharesCurrent - sharesPrevious;
        }
    }

    /**
     * The shares by type of holder at the end of the month (Jumlah Saham Scrip dan Scripless berdasarkan Tipe dan
     * Klasifikasi Investor); null = not printed.
     *
     * @param controller                the controller (Pengendali)
     * @param controllerAffiliates      the affiliates of the controller (Afiliasi dari pengendali)
     * @param directorsAndCommissioners the directors and commissioners together (Direksi dan Dewan Komisaris)
     * @param treasury                  the treasury shares (Saham Tresuri)
     */
    public record Ownership(Long controller, Long controllerAffiliates, Long directorsAndCommissioners, Long treasury) {

        static final Ownership NONE = new Ownership(null, null, null, null);

        /** Nothing printed (not a bean property: not in the JSON). */
        public boolean blank() {
            return controller == null && controllerAffiliates == null && directorsAndCommissioners == null && treasury == null;
        }
    }

    /**
     * A registration report.
     *
     * @param period              the month the report closes (holdings at its end)
     * @param correction          it corrects a report filed before (KOREKSI)
     * @param filedAt             filing time (Tanggal dan Waktu, Jakarta time); null when not printed
     * @param listedSharesPrevious shares listed at the end of the previous month; null when not printed
     * @param holders             the shareholders of 5% or more (empty: none, or the table could not be read)
     * @param ownership           the shares by type of holder
     * @param warnings            what did not add up (a percentage that does not match its share count)
     */
    public record Report(String ticker, String companyName, String letterNumber, String subject, boolean correction,
                         YearMonth period, LocalDateTime filedAt, String registrar, Long listedSharesPrevious,
                         Long listedSharesCurrent, List<Insider> insiders, List<Holder> holders, Ownership ownership,
                         List<String> warnings) {

        /** A report without the shareholders' table and the summary. */
        public Report(String ticker, String companyName, String letterNumber, String subject, boolean correction,
                      YearMonth period, LocalDateTime filedAt, String registrar, Long listedSharesPrevious,
                      Long listedSharesCurrent, List<Insider> insiders, List<String> warnings) {
            this(ticker, companyName, letterNumber, subject, correction, period, filedAt, registrar, listedSharesPrevious,
                    listedSharesCurrent, insiders, List.of(), Ownership.NONE, warnings);
        }
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

    // ------------------------------------------------------------------ the tables

    /** Header texts of the first columns (Indonesian, English). */
    private static final Set<String> NAME = Set.of("nama", "name");
    private static final Set<String> ADDRESS = Set.of("alamat", "address");
    private static final Set<String> POSITION = Set.of("jabatan", "position");

    /**
     * The columns of a table, left to right ({@code position} -1: none) and what each header must say.
     *
     * @param title   what the table is, for messages
     */
    record Layout(String title, int columns, int name, int position, int sharesPrevious, int percentPrevious,
                  int sharesCurrent, int percentCurrent, int controller, int affiliate, String[][] expected) {
    }

    /** The directors' and commissioners' table: 9 columns. */
    static final Layout DIRECTORS = new Layout("directors' and commissioners' table", 9, 0, 2, 3, 4, 5, 6, 7, 8,
            new String[][] {{"nama", "name"}, {"alamat", "address"}, {"jabatan", "position"},
                    {"sebelum", "previous"}, {"sebelum", "previous"}, {"ini", "current"}, {"ini", "current"},
                    {"pengendali", "controller"}, {"afiliasi", "affiliate"}});

    /** The table of the shareholders of 5% or more: the directors' columns without the position, 8 columns. */
    static final Layout HOLDERS = new Layout("table of the shareholders of 5% or more", 8, 0, -1, 2, 3, 4, 5, 6, 7,
            new String[][] {{"nama", "name"}, {"alamat", "address"},
                    {"sebelum", "previous"}, {"sebelum", "previous"}, {"ini", "current"}, {"ini", "current"},
                    {"pengendali", "controller"}, {"afiliasi", "affiliate"}});

    /**
     * The rows after the shareholders of 5% or more: the holders below 5%, the public (Masyarakat, HRTA; the cell may
     * break inside the word: "MASYARAKA" / "T"), the treasury shares, the total. The table of the named holders ends at
     * the first of them.
     */
    private static final Pattern AGGREGATE = Pattern.compile(
            "^(?:pemegang\\s+saham\\s*<|shareholders?\\s*<|masyaraka|public\\b|saham\\s+tre|treasury|total\\b|jumlah\\b)",
            Pattern.CASE_INSENSITIVE);
    /**
     * The percentages of the shareholders' table may be of the shares issued, which can be more than the shares listed
     * (BBCA: 123,275,050,000 issued, 122,042,299,500 listed): a percentage up to this factor below the share of the
     * listed shares passes.
     */
    private static final BigDecimal ISSUED_OVER_LISTED = new BigDecimal("1.2");

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

    // ------------------------------------------------------------------ the summary by type of holder

    /**
     * The heading of the summary (not the one of the KSEI classification below it); until April 2026 the Indonesian
     * half called it "Jumlah Saham Non Warkat berdasarkan Tipe Investor".
     */
    private static final Pattern SUMMARY = Pattern.compile(
            "Jumlah Saham Scrip dan Scripless berdasarkan Tipe dan Klasifikasi Investor(?!\\s+dari KSEI)"
                    + "|Jumlah Saham Non Warkat berdasarkan Tipe Investor"
                    + "|Number of Scrip and Scripless Shares (?:by|based on) (?:Type and )?Investor (?:Type and )?Classification(?!\\s+(?:from|of) KSEI)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KSEI = Pattern.compile("dari KSEI|from KSEI|of KSEI", Pattern.CASE_INSENSITIVE);

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
        List<Holder> holders = holders(pages, listedPrevious, listedCurrent, warnings);
        Ownership ownership = ownership(pages, warnings);
        if (insiders == null) {
            // DMAS August 2026: the label "Pemegang Saham Direksi dan Komisaris" with no table beside it
            if (holders.isEmpty() && ownership.blank()) {
                throw new RegistrationFormatException("The table of the directors' and commissioners' shares "
                        + "(Pemegang Saham Direksi dan Komisaris: Nama, Alamat, Jabatan, ...) is not in the report");
            }
            warnings.add("The report has no table of the directors' and commissioners' shares: no holdings stored");
            insiders = List.of();
        }
        return new Report(ticker.toUpperCase(Locale.ROOT), first(COMPANY, text), first(LETTER, text), subject,
                correction, period, filedAt(text), first(REGISTRAR, text), listedPrevious, listedCurrent,
                List.copyOf(insiders), List.copyOf(holders), ownership, List.copyOf(warnings));
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

    // ------------------------------------------------------------------ reading a table

    /**
     * Where the columns are: a word belongs to the column its centre falls in, except that a word starting left of
     * the first number column's header belongs to the column before it (the numbers are right-aligned and never reach
     * further left than their header; an address may reach past the middle between the two: "Lantai 5").
     *
     * @param firstNumber     the first number column (shares of the previous month)
     * @param firstNumberLeft the left edge of its header
     */
    record Columns(double left, double right, double[] bounds, int firstNumber, double firstNumberLeft) {

        Columns(double left, double right, double[] bounds) {
            this(left, right, bounds, -1, Double.NEGATIVE_INFINITY);
        }

        /** The column of a word (0 .. columns - 1), or -1 outside the table. */
        int of(Word w) {
            double c = w.center();
            if (c < left || c > right) {
                return -1;
            }
            int k = 0;
            while (k < bounds.length && c >= bounds[k]) {
                k++;
            }
            return k == firstNumber && w.x0() < firstNumberLeft - EDGE_SLACK ? k - 1 : k;
        }
    }

    /** The header of the table: its columns, and the lowest line of its text. */
    record Header(Columns columns, double nameY, double bottom) {
    }

    /** A line of the table: its page and baseline, and its words with their column. */
    private record Line(int page, double y, List<Word> words, List<Integer> columns) {
    }

    /** The directors and commissioners; null when the report has no such table. */
    private static List<Insider> insiders(List<Page> pages, Long listedPrevious, Long listedCurrent,
                                          List<String> warnings) {
        List<Line> lines = lines(pages, DIRECTORS);
        if (lines == null) {
            return null;
        }
        List<List<Line>> rows = rows(lines, DIRECTORS);
        List<Insider> insiders = new ArrayList<>();
        for (List<Line> row : rows) {
            String[] cells = cells(row, DIRECTORS);
            int number = insiders.size() + 1;
            String name = cells[DIRECTORS.name()];
            String position = cells[DIRECTORS.position()];
            String where = "row " + number + (name.isEmpty() ? "" : " (" + name + ")") + " of the "
                    + DIRECTORS.title() + ", page " + row.getFirst().page();
            Values v = values(cells, DIRECTORS, where, listedPrevious, listedCurrent, warnings);
            insiders.add(new Insider(number, name, position, InsiderRole.ofPosition(position), v.sharesPrevious(),
                    v.percentPrevious(), v.sharesCurrent(), v.percentCurrent(), v.controller(), v.affiliate()));
        }
        return insiders;
    }

    /**
     * The shareholders of 5% or more; empty when the report has no such table (a report without one is still read)
     * or it cannot be read without guessing (a warning says why).
     */
    private static List<Holder> holders(List<Page> pages, Long listedPrevious, Long listedCurrent, List<String> warnings) {
        try {
            List<Line> lines = lines(pages, HOLDERS);
            if (lines == null) {
                return List.of();
            }
            List<Holder> holders = new ArrayList<>();
            for (List<Line> row : rows(lines, HOLDERS)) {
                String[] cells = cells(row, HOLDERS);
                int number = holders.size() + 1;
                String name = cells[HOLDERS.name()];
                String where = "row " + number + (name.isEmpty() ? "" : " (" + name + ")") + " of the "
                        + HOLDERS.title() + ", page " + row.getFirst().page();
                Values v = values(cells, HOLDERS, where, listedPrevious, listedCurrent, warnings);
                holders.add(new Holder(number, name, v.sharesPrevious(), v.percentPrevious(), v.sharesCurrent(),
                        v.percentCurrent(), v.controller(), v.affiliate()));
            }
            return holders;
        } catch (RegistrationFormatException e) {
            warnings.add("The shareholders of 5% or more were not read: " + e.getMessage());
            return List.of();
        }
    }

    /** The lines of the first table of the layout, across pages; null when the report has none. */
    private static List<Line> lines(List<Page> pages, Layout layout) {
        int start = -1;
        Header header = null;
        for (int i = 0; i < pages.size() && header == null; i++) {
            header = header(pages.get(i), Double.NEGATIVE_INFINITY, layout);
            start = i;
        }
        if (header == null) {
            return null;
        }
        List<Line> lines = new ArrayList<>();
        Columns columns = header.columns();
        double from = header.bottom();
        Double last = header.bottom();
        for (int i = start; i < pages.size(); i++) {
            Page page = pages.get(i);
            if (i > start) {
                // the table goes on over the page: the header may be printed again at the top
                Header again = header(page, Double.NEGATIVE_INFINITY, layout);
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
                if (layout == HOLDERS && AGGREGATE.matcher(text(in)).find()) {
                    ended = true;     // the holders below 5%, the treasury shares, the total: no named holder
                    break;
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
        return lines;
    }

    /**
     * The header of the directors' table on the page below {@code afterY}: a line with "Nama", "Alamat" and
     * "Jabatan" (or "Name", "Address", "Position"); null when the page has none.
     */
    static Header directorsHeader(Page page, double afterY) {
        return header(page, afterY, DIRECTORS);
    }

    /**
     * The header of a table of the layout on the page below {@code afterY}: a line with "Nama" and "Alamat" (or
     * "Name", "Address") and, for the directors' table, "Jabatan" ("Position") - the shareholders' table has none;
     * null when the page has none.
     */
    static Header header(Page page, double afterY, Layout layout) {
        for (List<Word> line : lines(page.runs(), afterY, Double.POSITIVE_INFINITY)) {
            Word name = find(line, NAME);
            Word address = find(line, ADDRESS);
            Word position = find(line, POSITION);
            if (name == null || address == null || !(name.center() < address.center())) {
                continue;
            }
            if (layout.position() >= 0 ? position == null || !(address.center() < position.center()) : position != null) {
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
            if (clusters.size() != layout.columns()) {
                throw new RegistrationFormatException("The " + layout.title() + " on page " + page.number() + " has "
                        + clusters.size() + " columns, expected " + layout.columns() + " (name, address, "
                        + (layout.position() >= 0 ? "position, " : "") + "shares and percentage of the previous month "
                        + "and of this one, controller, affiliate of the controller): " + clusters.stream()
                        .map(c -> String.join(" ", c.stream().map(Word::text).toList())).toList());
            }
            checkHeader(clusters, page.number(), layout);
            int n = layout.columns();
            double[] bounds = new double[n - 1];
            for (int k = 0; k < n - 1; k++) {
                double right = clusters.get(k).stream().mapToDouble(Word::x1).max().orElseThrow();
                double left = clusters.get(k + 1).stream().mapToDouble(Word::x0).min().orElseThrow();
                bounds[k] = right <= left ? (right + left) / 2
                        : (center(clusters.get(k)) + center(clusters.get(k + 1))) / 2;
            }
            double firstCenter = center(clusters.getFirst());
            double lastCenter = center(clusters.getLast());
            double tableLeft = firstCenter - (bounds[0] - firstCenter) - EDGE_SLACK;
            double tableRight = lastCenter + (lastCenter - bounds[n - 2]) + EDGE_SLACK;
            double bottom = band.stream().mapToDouble(Word::y).max().orElseThrow();
            double numberLeft = clusters.get(layout.sharesPrevious()).stream().mapToDouble(Word::x0).min().orElseThrow();
            return new Header(new Columns(tableLeft, tableRight, bounds, layout.sharesPrevious(), numberLeft), nameY,
                    bottom);
        }
        return null;
    }

    private static double center(List<Word> cluster) {
        return cluster.getFirst().center();
    }

    /** The header columns read left to right must be those of the layout. */
    private static void checkHeader(List<List<Word>> clusters, int page, Layout layout) {
        for (int k = 0; k < layout.columns(); k++) {
            String label = String.join(" ", clusters.get(k).stream().map(Word::text).toList());
            String lower = label.toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String e : layout.expected()[k]) {
                ok |= lower.matches("(?s).*\\b" + e + ".*");
            }
            if (k == layout.percentPrevious() || k == layout.percentCurrent()) {
                ok &= lower.contains("persen") || lower.contains("percent");
            }
            if (k == layout.controller()) {
                ok &= !lower.contains("afiliasi") && !lower.contains("affiliate");
            }
            if (!ok) {
                throw new RegistrationFormatException("Column " + (k + 1) + " of the " + layout.title() + " on page "
                        + page + " is '" + label + "', not the expected one");
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

    /** The lines grouped into rows: a row starts at the line with its percentage. */
    private static List<List<Line>> rows(List<Line> lines, Layout layout) {
        List<List<Line>> rows = new ArrayList<>();
        for (Line line : lines) {
            if (startsARow(line, layout)) {
                rows.add(new ArrayList<>());
            } else if (rows.isEmpty()) {
                throw new RegistrationFormatException("Unexpected text above the first row of the " + layout.title()
                        + " (page " + line.page() + "): '" + String.join(" ",
                        line.words().stream().map(Word::text).toList()) + "'");
            }
            rows.getLast().add(line);
        }
        return rows;
    }

    /** A row starts at the line with its percentage (of the previous month or of this one). */
    private static boolean startsARow(Line line, Layout layout) {
        for (int i = 0; i < line.words().size(); i++) {
            int c = line.columns().get(i);
            if ((c == layout.percentPrevious() || c == layout.percentCurrent())
                    && PERCENT.matcher(line.words().get(i).text()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The text of each cell of a row, trimmed; a number wrapped in its cell is joined again without a blank. */
    private static String[] cells(List<Line> row, Layout layout) {
        StringBuilder[] cells = new StringBuilder[layout.columns()];
        for (int k = 0; k < cells.length; k++) {
            cells[k] = new StringBuilder();
        }
        for (Line line : row) {
            for (int i = 0; i < line.words().size(); i++) {
                int c = line.columns().get(i);
                boolean numeric = c >= layout.sharesPrevious() && c <= layout.percentCurrent();
                if (!cells[c].isEmpty() && !numeric) {
                    cells[c].append(' ');
                }
                // a number wrapped in its cell ("3.272.048.00" / "0") is joined again without a blank
                cells[c].append(line.words().get(i).text());
            }
        }
        String[] out = new String[cells.length];
        for (int k = 0; k < cells.length; k++) {
            out[k] = cells[k].toString().trim();
        }
        return out;
    }

    /** The share counts, percentages and marks of a row. */
    private record Values(Long sharesPrevious, BigDecimal percentPrevious, Long sharesCurrent, BigDecimal percentCurrent,
                          boolean controller, boolean affiliate) {
    }

    private static Values values(String[] cells, Layout layout, String where, Long listedPrevious, Long listedCurrent,
                                 List<String> warnings) {
        String name = cells[layout.name()];
        if (name.isEmpty() || !name.matches("(?s).*\\p{L}.*")) {
            throw new RegistrationFormatException("No name in " + where);
        }
        Long sharesPrevious = shares(cells[layout.sharesPrevious()], where);
        BigDecimal percentPrevious = percent(cells[layout.percentPrevious()], where);
        Long sharesCurrent = shares(cells[layout.sharesCurrent()], where);
        BigDecimal percentCurrent = percent(cells[layout.percentCurrent()], where);
        if (sharesPrevious == null && sharesCurrent == null) {
            throw new RegistrationFormatException("No share count in " + where);
        }
        boolean ofIssued = layout == HOLDERS;
        check(sharesPrevious, percentPrevious, listedPrevious, ofIssued, "the previous month", where, warnings);
        check(sharesCurrent, percentCurrent, listedCurrent, ofIssued, "this month", where, warnings);
        return new Values(sharesPrevious, percentPrevious, sharesCurrent, percentCurrent,
                mark(cells[layout.controller()], where), mark(cells[layout.affiliate()], where));
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
    private static void check(Long shares, BigDecimal percent, Long listed, boolean ofIssued, String month, String where,
                              List<String> warnings) {
        if (shares == null || percent == null || listed == null || listed <= 0) {
            return;
        }
        BigDecimal actual = BigDecimal.valueOf(shares).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(listed), MathContext.DECIMAL64);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(Math.max(percent.scale(), 0));
        boolean ofTheIssued = ofIssued && percent.compareTo(actual.add(unit)) <= 0
                && percent.compareTo(actual.divide(ISSUED_OVER_LISTED, MathContext.DECIMAL64).subtract(unit)) >= 0;
        if (actual.subtract(percent).abs().compareTo(unit) > 0 && !ofTheIssued) {
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

    // ------------------------------------------------------------------ the summary by type of holder

    /** The labels of the summary (lower case, blanks collapsed), Indonesian and English. */
    private static final Map<String, String> SUMMARY_LABELS = Map.ofEntries(
            Map.entry("pengendali", "controller"), Map.entry("controller", "controller"),
            Map.entry("afiliasi dari pengendali", "affiliates"), Map.entry("affiliates of controller", "affiliates"),
            Map.entry("affiliate of controller", "affiliates"),
            Map.entry("afiliasi dari pengendali dan pemilik manfaat", "affiliates"),
            Map.entry("affiliation from controller & beneficial owner", "affiliates"),
            Map.entry("direksi dan dewan komisaris", "insiders"), Map.entry("board of directors and commissioners", "insiders"),
            Map.entry("bod / boc", "insiders"),
            Map.entry("saham tresuri", "treasury"), Map.entry("saham treasuri", "treasury"), Map.entry("treasury", "treasury"),
            Map.entry("treasury stock", "treasury"), Map.entry("treasury shares", "treasury"));

    /**
     * The shares by type of holder: the lines of the summary under its heading, each a label and its number on the same
     * baseline. Only the first summary (the Indonesian half; the KSEI classification below it is not read).
     */
    private static Ownership ownership(List<Page> pages, List<String> warnings) {
        for (Page page : pages) {
            Double top = null;
            for (List<Word> line : lines(page.runs(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                if (SUMMARY.matcher(text(line)).find()) {
                    top = line.getFirst().y();
                    break;
                }
            }
            if (top == null) {
                continue;
            }
            Map<String, Long> values = new LinkedHashMap<>();
            for (List<Word> line : lines(page.words(), top + SAME_LINE, Double.POSITIVE_INFINITY)) {
                String all = text(line);
                if (KSEI.matcher(all).find()) {
                    break;     // the KSEI classification: another table
                }
                List<Word> label = new ArrayList<>();
                String number = null;
                for (Word w : line) {
                    if (number == null && (SHARES.matcher(w.text()).matches() || w.text().equals("-")) && !label.isEmpty()) {
                        number = w.text();
                    } else if (number == null) {
                        label.add(w);
                    }
                }
                String key = SUMMARY_LABELS.get(text(label).toLowerCase(Locale.ROOT));
                if (key == null || number == null || values.containsKey(key)) {
                    continue;
                }
                try {
                    values.put(key, shares(number, "the summary by type of holder, page " + page.number()));
                } catch (RegistrationFormatException e) {
                    warnings.add(e.getMessage());
                }
            }
            return new Ownership(values.get("controller"), values.get("affiliates"), values.get("insiders"),
                    values.get("treasury"));
        }
        return Ownership.NONE;
    }

    private static String text(List<Word> words) {
        return String.join(" ", words.stream().map(Word::text).toList()).trim().replaceAll("\\s+", " ");
    }
}
