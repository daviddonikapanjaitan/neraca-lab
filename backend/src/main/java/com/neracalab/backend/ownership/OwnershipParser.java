package com.neracalab.backend.ownership;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads KSEI's daily list of the holders of more than 5% of the listed companies, by investor (SID), as published on
 * idx.co.id ({@code peng-2026-10-27-00104-lima-persen.xlsx}: "KEPEMILIKAN EFEK DIATAS 5% BERDASARKAN SID (PUBLIK) per
 * tanggal 27 Oct 2026"). One sheet; a header row ("No", "Kode Efek", "Nama Emiten", "Nama Pemegang Rekening Efek",
 * "Nama Pemegang Saham", "Nama Rekening Efek", ..., "Kepemilikan Per 26-OCT-2026", "Kepemilikan Per 27-OCT-2026",
 * "Perubahan") and a sub-header (per date: "Jumlah Saham", "Saham Gabungan Per Investor", "Persentase Kepemilikan
 * Per Investor (%)"). Every investor starts at a row with its number ("No"), its name and its combined shares and
 * percentage of both dates; each of its securities accounts is a row (the first one on the investor's row).
 * <p>
 * The columns are found by their header, not by position. The share counts are text ("1,197,023,942"), except a
 * few cells that were turned into numbers on the way: "441,400" became 441.4 and "200,000" became 200. A number with
 * a fraction is therefore thousands (441.4 = 441,400); a whole number below 1,000 may be either (200 or 200,000):
 * the investor's combined shares decide, as the accounts must add up to them, and otherwise the percentage against
 * the shares listed implied by the other investors of the company. What cannot be decided is read as written, with a
 * note. "-" is no holding on that date (an investor that crossed 5% or fell below it, an account opened or closed).
 * The column "Perubahan" (change) is not read: it is mangled the same way; changes are the difference of the two
 * dates. Addresses ("Data tidak ditampilkan") are not read.
 * <p>
 * KSEI lists an investor once per name its accounts are registered under (SIAP: "EVIO SECURITIES, PT", "PT. EVIO
 * SECURITIES" and "PT.EVIO SECURITIES", each with the same ten accounts): an investor of the same company with the same
 * accounts, holdings and totals as one listed before is marked as an alias of that one ({@link Holder#aliasOf()}), so
 * totals count it once.
 */
public final class OwnershipParser {

    /** Not a readable list of the holders of more than 5%: nothing is stored. */
    public static class OwnershipFormatException extends RuntimeException {

        public OwnershipFormatException(String message) {
            super(message);
        }
    }

    /**
     * A securities account of an investor.
     *
     * @param index         1 for the investor's row, then the rows below it
     * @param participant   the account's KSEI participant: broker or custodian bank (Nama Pemegang Rekening Efek)
     * @param accountName   the account's name (Nama Rekening Efek)
     * @param status        L (local) or A (foreign, asing); null when not printed
     * @param sharesPrevious null: no holding in the account on the previous date
     */
    public record Account(int index, String participant, String accountName, String nationality, String domicile,
                          String status, Long sharesPrevious, Long sharesCurrent) {
    }

    /**
     * An investor holding more than 5% of a company on either date.
     *
     * @param row            its number in the list (No)
     * @param holderKey      the name normalized (upper case, letters and digits, without PT / Tbk): the same investor
     *                       across dates and companies
     * @param sharesPrevious combined shares on the previous date; null: not in the list then (below 5%)
     * @param percentPrevious percentage of the shares listed, as printed
     * @param aliasOf        the row of the investor listed before with the same accounts (the same SID under another
     *                       name); null for the first
     */
    public record Holder(int row, String ticker, String issuerName, String holderName, String holderKey,
                         String nationality, String domicile, String status, Long sharesPrevious,
                         BigDecimal percentPrevious, Long sharesCurrent, BigDecimal percentCurrent, Integer aliasOf,
                         List<Account> accounts) {

        public Movement movement() {
            return Movement.of(sharesPrevious, sharesCurrent);
        }

        /** Accounts whose shares changed (also when the investor's total did not: moved between accounts). */
        public int accountMoves() {
            return (int) accounts.stream().filter(a -> a.sharesPrevious() == null ? a.sharesCurrent() != null
                    : !a.sharesPrevious().equals(a.sharesCurrent())).count();
        }
    }

    /** What happened to an investor's holding between the two dates. */
    public enum Movement {
        /** more shares */
        BUY,
        /** fewer shares */
        SELL,
        /** the same shares (the percentage may still change with the shares listed) */
        UNCHANGED,
        /** in the list on the report's date only: crossed 5% */
        ENTERED,
        /**
         * in the list on the previous date only: fell below 5% (or sold out). KSEI normally leaves such an investor
         * out of the day's list altogether; {@link OwnershipRepository} finds those in the stored list of the day
         * before. A "-" on the report's date reads as this too.
         */
        EXITED;

        public static Movement of(Long previous, Long current) {
            if (previous == null) {
                return ENTERED;
            }
            if (current == null) {
                return EXITED;
            }
            int c = Long.compare(current, previous);
            return c > 0 ? BUY : c < 0 ? SELL : UNCHANGED;
        }
    }

    /**
     * The list of one day.
     *
     * @param reportDate   the list's date (the second date of the sheet)
     * @param previousDate the date it is compared with (the first: the previous trading day)
     * @param warnings     what did not add up (accounts that do not sum to the investor's total, an unreadable row)
     * @param notes        what was decided without certainty (a number read as written)
     */
    public record Report(LocalDate reportDate, LocalDate previousDate, String title, List<Holder> holders,
                         List<String> warnings, List<String> notes) {

        /** The companies listed, in the list's order. */
        public List<String> tickers() {
            return holders.stream().map(Holder::ticker).distinct().toList();
        }

        public int accounts() {
            return holders.stream().mapToInt(h -> h.accounts().size()).sum();
        }

        public int aliases() {
            return (int) holders.stream().filter(h -> h.aliasOf() != null).count();
        }

        /** Investors (aliases not counted) with this movement. */
        public int count(Movement movement) {
            return (int) holders.stream().filter(h -> h.aliasOf() == null && h.movement() == movement).count();
        }
    }

    // ------------------------------------------------------------------ cell values

    /** A share count as written: missing ("-", empty), exact, or a whole number below 1,000 (it or its thousands). */
    private sealed interface Num permits Missing, Exact, Ambiguous {
    }

    private record Missing() implements Num {
    }

    private record Exact(long value) implements Num {
    }

    private record Ambiguous(long literal) implements Num {
    }

    private static final Num MISSING = new Missing();

    /** Most ambiguous accounts of one investor tried in every combination (2^n). */
    private static final int MAX_AMBIGUOUS = 16;

    private static final Pattern COMMA_GROUPED = Pattern.compile("\\d{1,3}(,\\d{3})+");
    private static final Pattern DOT_GROUPED = Pattern.compile("\\d{1,3}(\\.\\d{3})+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})[\\s-]+([A-Za-z]{3,})[\\s-]+(\\d{4})");
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");

    /** English and Indonesian month names by their first three letters. */
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("PEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("MEI", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("AGU", 8), Map.entry("AGT", 8), Map.entry("AGS", 8), Map.entry("SEP", 9), Map.entry("OCT", 10),
            Map.entry("OKT", 10), Map.entry("NOV", 11), Map.entry("NOP", 11), Map.entry("DEC", 12),
            Map.entry("DES", 12));

    private OwnershipParser() {
    }

    // ------------------------------------------------------------------ parse

    /**
     * Reads the list.
     *
     * @throws OwnershipFormatException not a workbook, not the list of the holders of more than 5%, or a value that
     *                                  is not a number where one must be
     */
    public static Report parse(byte[] content) {
        // the sheets are text-heavy and compress well (as the IDX XBRL workbooks)
        ZipSecureFile.setMinInflateRatio(0.001);
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : book) {
                Integer header = headerRow(sheet);
                if (header != null) {
                    return read(sheet, header);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof OwnershipFormatException f) {
                throw f;
            }
            throw new OwnershipFormatException("Not a readable Excel workbook (.xlsx): " + message(e));
        }
        throw new OwnershipFormatException("Not KSEI's list of the shareholders of more than 5%: no sheet has the "
                + "header 'Kode Efek' / 'Nama Pemegang Saham' / 'Kepemilikan Per ...'");
    }

    /** The row (0-based) with "Kode Efek", within the first 30 rows. */
    private static Integer headerRow(Sheet sheet) {
        for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 30); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if ("KODE EFEK".equals(norm(text(cell)))) {
                    return r;
                }
            }
        }
        return null;
    }

    /** The columns of one date: its shares per account, its combined shares and its percentage per investor. */
    private record Block(LocalDate date, int shares, int combined, int percent) {
    }

    private record Columns(int no, int ticker, int issuer, int participant, int holder, int account, int nationality,
                           int domicile, int status, Block previous, Block current) {
    }

    /** The cells of one row of an investor (its account). */
    private record RawRow(int sheetRow, String participant, String accountName, String nationality, String domicile,
                          String status, Num previous, Num current) {
    }

    /** An investor as read, before its numbers are resolved. */
    private static final class RawHolder {
        final int sheetRow;
        final int no;
        final String ticker;
        final String issuer;
        final String name;
        final Num totalPrevious;
        final Num totalCurrent;
        final BigDecimal percentPrevious;
        final BigDecimal percentCurrent;
        final List<RawRow> rows = new ArrayList<>();
        /** the resolved shares of the dates (null: not held), per date the total and each account's */
        Long[] previous;
        Long[] current;

        RawHolder(int sheetRow, int no, String ticker, String issuer, String name, Num totalPrevious, Num totalCurrent,
                  BigDecimal percentPrevious, BigDecimal percentCurrent) {
            this.sheetRow = sheetRow;
            this.no = no;
            this.ticker = ticker;
            this.issuer = issuer;
            this.name = name;
            this.totalPrevious = totalPrevious;
            this.totalCurrent = totalCurrent;
            this.percentPrevious = percentPrevious;
            this.percentCurrent = percentCurrent;
        }
    }

    private static Report read(Sheet sheet, int headerRow) {
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String title = title(sheet, headerRow);
        if (title != null) {
            String upper = title.toUpperCase(Locale.ROOT);
            if (!upper.contains("5%") && !upper.contains("5 %") && (upper.contains("1%") || upper.contains("1 %"))) {
                throw new OwnershipFormatException("This is the list of the holders of more than 1% (" + title
                        + "): choose the document type Ownership (>1%), a later feature");
            }
            if (!upper.contains("5%") && !upper.contains("5 %") && !upper.contains("LIMA PERSEN")) {
                throw new OwnershipFormatException("Not KSEI's list of the shareholders of more than 5%: its title is '"
                        + title + "'");
            }
        } else {
            warnings.add("No title above the table (KEPEMILIKAN EFEK DIATAS 5% ...): the dates are read from the header");
        }
        Columns columns = columns(sheet, headerRow);
        if (!columns.previous().date().isBefore(columns.current().date())) {
            throw new OwnershipFormatException("The first date " + columns.previous().date()
                    + " is not before the second " + columns.current().date());
        }
        LocalDate titleDate = title == null ? null : titleDate(title);
        if (titleDate != null && !titleDate.equals(columns.current().date())) {
            warnings.add("The title's date " + titleDate + " is not the second date of the table "
                    + columns.current().date());
        }

        List<RawHolder> raw = rows(sheet, headerRow, columns, warnings);
        if (raw.isEmpty()) {
            throw new OwnershipFormatException("The list has no investor below its header");
        }
        resolve(raw, warnings, notes);

        List<Holder> holders = new ArrayList<>();
        Map<String, Integer> firstBySignature = new HashMap<>();
        Set<Integer> rows = new LinkedHashSet<>();
        for (RawHolder h : raw) {
            if (h.previous[0] == null && h.current[0] == null) {
                warnings.add("Row " + (h.sheetRow + 1) + " (" + h.ticker + ", " + h.name + "): no holding on either date;"
                        + " not stored");
                continue;
            }
            if (!rows.add(h.no)) {
                warnings.add("Row " + (h.sheetRow + 1) + " (" + h.ticker + ", " + h.name + "): its number " + h.no
                        + " was used before; not stored");
                continue;
            }
            List<Account> accounts = new ArrayList<>();
            for (int i = 0; i < h.rows.size(); i++) {
                RawRow r = h.rows.get(i);
                accounts.add(new Account(i + 1, r.participant(), r.accountName(), r.nationality(), r.domicile(),
                        r.status(), h.previous[i + 1], h.current[i + 1]));
            }
            RawRow first = h.rows.getFirst();
            String signature = signature(h.ticker, h.previous[0], h.current[0], accounts);
            Integer aliasOf = firstBySignature.putIfAbsent(signature, h.no);
            holders.add(new Holder(h.no, h.ticker, h.issuer, h.name, holderKey(h.name), first.nationality(),
                    first.domicile(), first.status(), h.previous[0], h.percentPrevious, h.current[0], h.percentCurrent,
                    aliasOf, List.copyOf(accounts)));
        }
        return new Report(columns.current().date(), columns.previous().date(), title, List.copyOf(holders),
                List.copyOf(warnings), List.copyOf(notes));
    }

    /** The text above the header that names the list ("KEPEMILIKAN EFEK DIATAS 5% ... per tanggal 27 Oct 2026"). */
    private static String title(Sheet sheet, int headerRow) {
        for (int r = sheet.getFirstRowNum(); r < headerRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                String t = clean(text(cell));
                if (t != null && t.toUpperCase(Locale.ROOT).contains("KEPEMILIKAN")) {
                    return t;
                }
            }
        }
        return null;
    }

    /** "... per tanggal 19 Oct 2026" -> 2026-10-19 */
    static LocalDate titleDate(String title) {
        Matcher m = Pattern.compile("(?i)tanggal\\s+(\\d{1,2})\\s+([A-Za-z]{3,})\\s+(\\d{4})").matcher(title);
        return m.find() ? date(m.group(1), m.group(2), m.group(3)) : null;
    }

    /** "Kepemilikan Per 16-OCT-2026" -> 2026-10-16 (English or Indonesian month); null when there is none. */
    static LocalDate headerDate(String text) {
        Matcher m = DATE.matcher(text);
        return m.find() ? date(m.group(1), m.group(2), m.group(3)) : null;
    }

    private static LocalDate date(String day, String month, String year) {
        Integer mm = MONTHS.get(month.substring(0, 3).toUpperCase(Locale.ROOT));
        if (mm == null) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(year), mm, Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Columns columns(Sheet sheet, int headerRow) {
        Row header = sheet.getRow(headerRow);
        Map<String, Integer> named = new HashMap<>();
        List<Integer> headerCols = new ArrayList<>();
        List<int[]> blocks = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        for (Cell cell : header) {
            String name = norm(text(cell));
            if (name.isEmpty()) {
                continue;
            }
            int col = cell.getColumnIndex();
            headerCols.add(col);
            if (name.startsWith("KEPEMILIKAN PER")) {
                LocalDate date = headerDate(name);
                if (date == null) {
                    throw new OwnershipFormatException("The header '" + clean(text(cell)) + "' has no date");
                }
                blocks.add(new int[] {col});
                dates.add(date);
            } else if (name.startsWith("STATUS")) {
                named.putIfAbsent("STATUS", col);
            } else {
                named.putIfAbsent(name, col);
            }
        }
        if (blocks.size() != 2) {
            throw new OwnershipFormatException("Expected two columns 'Kepemilikan Per <date>', found " + blocks.size());
        }
        Row sub = sheet.getRow(headerRow + 1);
        if (sub == null) {
            throw new OwnershipFormatException("No sub-header (Jumlah Saham, Saham Gabungan Per Investor, Persentase "
                    + "Kepemilikan Per Investor) below the header");
        }
        Block[] parsed = new Block[2];
        for (int i = 0; i < 2; i++) {
            int start = blocks.get(i)[0];
            int end = headerCols.stream().filter(c -> c > start).min(Integer::compare).orElse(start + 3);
            Integer shares = null;
            Integer combined = null;
            Integer percent = null;
            for (int c = start; c < end; c++) {
                String name = norm(text(sub.getCell(c)));
                if (name.equals("JUMLAH SAHAM")) {
                    shares = c;
                } else if (name.startsWith("SAHAM GABUNGAN")) {
                    combined = c;
                } else if (name.startsWith("PERSENTASE")) {
                    percent = c;
                }
            }
            if (shares == null || combined == null || percent == null) {
                throw new OwnershipFormatException("The columns of " + dates.get(i) + " are not 'Jumlah Saham', "
                        + "'Saham Gabungan Per Investor' and 'Persentase Kepemilikan Per Investor (%)'");
            }
            parsed[i] = new Block(dates.get(i), shares, combined, percent);
        }
        return new Columns(required(named, "NO"), required(named, "KODE EFEK"), required(named, "NAMA EMITEN"),
                required(named, "NAMA PEMEGANG REKENING EFEK"), required(named, "NAMA PEMEGANG SAHAM"),
                required(named, "NAMA REKENING EFEK"), named.getOrDefault("KEBANGSAAN", -1),
                named.getOrDefault("DOMISILI", -1), named.getOrDefault("STATUS", -1), parsed[0], parsed[1]);
    }

    private static int required(Map<String, Integer> named, String name) {
        Integer col = named.get(name);
        if (col == null) {
            throw new OwnershipFormatException("Not KSEI's list of the shareholders of more than 5%: no column '"
                    + name + "'");
        }
        return col;
    }

    private static List<RawHolder> rows(Sheet sheet, int headerRow, Columns c, List<String> warnings) {
        List<RawHolder> out = new ArrayList<>();
        RawHolder current = null;
        for (int r = headerRow + 2; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String ticker = clean(text(row.getCell(c.ticker())));
            if (ticker == null) {
                continue;     // empty rows, the legend below the table (Keterangan, * Font: ...)
            }
            ticker = ticker.toUpperCase(Locale.ROOT);
            String where = "Row " + (r + 1) + " (" + ticker + ")";
            if (!TICKER.matcher(ticker).matches()) {
                warnings.add(where + ": '" + ticker + "' is not a ticker; not read");
                continue;
            }
            Integer no = number(row.getCell(c.no()));
            if (no != null) {
                String name = clean(text(row.getCell(c.holder())));
                if (name == null) {
                    name = clean(text(row.getCell(c.account())));
                    warnings.add(where + ": no investor name (Nama Pemegang Saham); the account's name is used");
                }
                if (name == null) {
                    throw new OwnershipFormatException(where + ": the investor has no name");
                }
                String issuer = clean(text(row.getCell(c.issuer())));
                current = new RawHolder(r, no, ticker, issuer == null ? ticker : issuer, name,
                        shares(row.getCell(c.previous().combined()), where),
                        shares(row.getCell(c.current().combined()), where),
                        percent(row.getCell(c.previous().percent()), where, warnings),
                        percent(row.getCell(c.current().percent()), where, warnings));
                out.add(current);
            } else if (current == null) {
                warnings.add(where + ": an account before the first investor; not read");
                continue;
            } else if (!current.ticker.equals(ticker)) {
                warnings.add(where + ": an account of " + ticker + " under an investor of " + current.ticker
                        + " (row " + (current.sheetRow + 1) + "); not read");
                continue;
            }
            current.rows.add(new RawRow(r, nullToEmpty(clean(text(row.getCell(c.participant())))),
                    nullToEmpty(clean(text(row.getCell(c.account())))), optional(row, c.nationality()),
                    optional(row, c.domicile()), status(optional(row, c.status())),
                    shares(row.getCell(c.previous().shares()), where), shares(row.getCell(c.current().shares()), where)));
        }
        return out;
    }

    // ------------------------------------------------------------------ resolving the numbers

    /** A way to read an investor's numbers of one date that adds up: its total and each account's shares. */
    private record Solution(Long total, Long[] accounts) {
    }

    private static void resolve(List<RawHolder> holders, List<String> warnings, List<String> notes) {
        // first pass: what the totals decide
        Map<RawHolder, List<Solution>> openPrevious = new LinkedHashMap<>();
        Map<RawHolder, List<Solution>> openCurrent = new LinkedHashMap<>();
        for (RawHolder h : holders) {
            String where = "Row " + (h.sheetRow + 1) + " (" + h.ticker + ", " + h.name + ")";
            List<Solution> p = solutions(h.totalPrevious, h.rows.stream().map(RawRow::previous).toList(), where,
                    warnings);
            List<Solution> c = solutions(h.totalCurrent, h.rows.stream().map(RawRow::current).toList(), where,
                    warnings);
            if (p.size() == 1) {
                h.previous = flat(p.getFirst());
            } else {
                openPrevious.put(h, p);
            }
            if (c.size() == 1) {
                h.current = flat(c.getFirst());
            } else {
                openCurrent.put(h, c);
            }
        }
        // second pass: the percentage against the shares listed implied by the company's other investors
        settle(openPrevious, holders, true, notes);
        settle(openCurrent, holders, false, notes);
    }

    private static void settle(Map<RawHolder, List<Solution>> open, List<RawHolder> holders, boolean previous,
                               List<String> notes) {
        Map<String, Double> listed = new HashMap<>();
        Map<String, BigDecimal> best = new HashMap<>();
        for (RawHolder h : holders) {
            Long[] values = previous ? h.previous : h.current;
            BigDecimal pct = previous ? h.percentPrevious : h.percentCurrent;
            if (values == null || values[0] == null || pct == null || pct.signum() <= 0) {
                continue;
            }
            // the largest holding gives the most precise estimate (percentages are printed with two decimals)
            if (!best.containsKey(h.ticker) || pct.compareTo(best.get(h.ticker)) > 0) {
                best.put(h.ticker, pct);
                listed.put(h.ticker, values[0] * 100d / pct.doubleValue());
            }
        }
        for (Map.Entry<RawHolder, List<Solution>> e : open.entrySet()) {
            RawHolder h = e.getKey();
            List<Solution> options = e.getValue();
            BigDecimal pct = previous ? h.percentPrevious : h.percentCurrent;
            Double shares = listed.get(h.ticker);
            Solution chosen = null;
            if (shares != null && pct != null) {
                double target = pct.doubleValue() * shares / 100d;
                chosen = options.stream().min((a, b) -> Double.compare(Math.abs(a.total() - target),
                        Math.abs(b.total() - target))).orElseThrow();
            } else {
                chosen = options.stream().min((a, b) -> Long.compare(a.total(), b.total())).orElseThrow();
                notes.add(h.ticker + " " + h.name + " (" + (previous ? "previous date" : "report date") + "): "
                        + String.format(Locale.ROOT, "%,d", chosen.total()) + " shares as written; the sheet does "
                        + "not show whether the number is in thousands");
            }
            Long[] values = flat(chosen);
            if (previous) {
                h.previous = values;
            } else {
                h.current = values;
            }
        }
    }

    /** [total, account 1, account 2, ...] */
    private static Long[] flat(Solution s) {
        Long[] out = new Long[s.accounts().length + 1];
        out[0] = s.total();
        System.arraycopy(s.accounts(), 0, out, 1, s.accounts().length);
        return out;
    }

    /**
     * The readings of one date in which the accounts add up to the investor's total: one, or several when an
     * ambiguous number leaves the choice open (decided by the percentage). With no reading that adds up, the one
     * closest to the total, with a warning.
     */
    private static List<Solution> solutions(Num total, List<Num> accounts, String where, List<String> warnings) {
        int n = accounts.size();
        boolean noAccount = accounts.stream().allMatch(a -> a instanceof Missing);
        List<Integer> ambiguous = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (accounts.get(i) instanceof Ambiguous) {
                ambiguous.add(i);
            }
        }
        if (total instanceof Missing) {
            if (noAccount) {
                return List.of(new Solution(null, new Long[n]));
            }
            Long[] literal = literal(accounts);
            long sum = sum(literal);
            warnings.add(where + ": no combined shares although its accounts hold " + String.format(Locale.ROOT, "%,d",
                    sum) + "; their sum is used");
            return List.of(new Solution(sum, literal));
        }
        List<Long> totals = switch (total) {
            case Exact x -> List.of(x.value());
            case Ambiguous a -> List.of(a.literal(), a.literal() * 1000);
            case Missing m -> List.of();
        };
        if (noAccount) {
            return totals.stream().map(t -> new Solution(t, new Long[n])).toList();
        }
        if (ambiguous.size() > MAX_AMBIGUOUS) {
            Long[] literal = literal(accounts);
            warnings.add(where + ": " + ambiguous.size() + " accounts written as small numbers; read as written");
            return List.of(new Solution(totals.getFirst(), literal));
        }
        List<Solution> found = new ArrayList<>();
        Solution closest = null;
        long closestGap = Long.MAX_VALUE;
        for (long t : totals) {
            for (int mask = 0; mask < (1 << ambiguous.size()); mask++) {
                Long[] values = new Long[n];
                for (int i = 0; i < n; i++) {
                    values[i] = switch (accounts.get(i)) {
                        case Exact x -> x.value();
                        case Ambiguous a -> a.literal();
                        case Missing m -> null;
                    };
                }
                for (int b = 0; b < ambiguous.size(); b++) {
                    if ((mask & (1 << b)) != 0) {
                        values[ambiguous.get(b)] *= 1000;
                    }
                }
                long gap = Math.abs(sum(values) - t);
                if (gap == 0) {
                    found.add(new Solution(t, values));
                } else if (gap < closestGap) {
                    closestGap = gap;
                    closest = new Solution(t, values);
                }
            }
        }
        if (!found.isEmpty()) {
            // the same total read two ways: the fewest numbers multiplied (the first found per total)
            Map<Long, Solution> byTotal = new LinkedHashMap<>();
            found.forEach(s -> byTotal.putIfAbsent(s.total(), s));
            return List.copyOf(byTotal.values());
        }
        warnings.add(where + ": the accounts add up to " + String.format(Locale.ROOT, "%,d", sum(closest.accounts()))
                + ", not to the combined " + String.format(Locale.ROOT, "%,d", closest.total()) + " shares");
        return List.of(closest);
    }

    private static Long[] literal(List<Num> accounts) {
        Long[] out = new Long[accounts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (accounts.get(i)) {
                case Exact x -> x.value();
                case Ambiguous a -> a.literal();
                case Missing m -> null;
            };
        }
        return out;
    }

    private static long sum(Long[] values) {
        long s = 0;
        for (Long v : values) {
            s += v == null ? 0 : v;
        }
        return s;
    }

    /** The same investor under another name: the same company, totals and accounts. */
    private static String signature(String ticker, Long previous, Long current, List<Account> accounts) {
        List<String> parts = new ArrayList<>();
        for (Account a : accounts) {
            parts.add(norm(a.participant()) + "|" + norm(a.accountName()) + "|" + a.sharesPrevious() + "|"
                    + a.sharesCurrent());
        }
        parts.sort(String::compareTo);
        return ticker + "#" + previous + "#" + current + "#" + String.join("#", parts);
    }

    // ------------------------------------------------------------------ cells

    /** A share count: text "1,197,023,942" (or "1.197.023.942"), "-" / empty = none, a number (see the class). */
    private static Num shares(Cell cell, String where) {
        Object v = value(cell);
        if (v == null) {
            return MISSING;
        }
        if (v instanceof Double d) {
            if (d < 0 || d.isNaN() || d.isInfinite()) {
                throw new OwnershipFormatException(where + ": " + d + " is not a share count");
            }
            if (d == Math.rint(d)) {
                // "441,400" arrives as 441.4 and "200,000" as 200: only a whole number below 1,000 can be thousands
                return d < 1000 ? new Ambiguous(d.longValue()) : new Exact(d.longValue());
            }
            double thousands = d * 1000;
            if (d >= 1000 || Math.abs(thousands - Math.rint(thousands)) > 1e-6) {
                throw new OwnershipFormatException(where + ": " + d + " is not a share count");
            }
            return new Exact(Math.round(thousands));
        }
        String s = ((String) v).replace(" ", "");
        if (s.isEmpty() || s.chars().allMatch(ch -> ch == '-')) {
            return MISSING;
        }
        if (COMMA_GROUPED.matcher(s).matches()) {
            return new Exact(Long.parseLong(s.replace(",", "")));
        }
        if (DOT_GROUPED.matcher(s).matches()) {
            return new Exact(Long.parseLong(s.replace(".", "")));
        }
        if (DIGITS.matcher(s).matches() && s.length() <= 18) {
            return new Exact(Long.parseLong(s));
        }
        throw new OwnershipFormatException(where + ": '" + s + "' is not a share count");
    }

    /** "41.10" (or "41,10", or a number) -> 41.10; "-" / empty -> null. */
    private static BigDecimal percent(Cell cell, String where, List<String> warnings) {
        Object v = value(cell);
        if (v == null) {
            return null;
        }
        BigDecimal p;
        if (v instanceof Double d) {
            p = BigDecimal.valueOf(d);
        } else {
            String s = ((String) v).replace(" ", "").replace("%", "");
            if (s.isEmpty() || s.chars().allMatch(ch -> ch == '-')) {
                return null;
            }
            if (s.indexOf('.') < 0) {
                s = s.replace(',', '.');
            }
            try {
                p = new BigDecimal(s);
            } catch (NumberFormatException e) {
                warnings.add(where + ": '" + s + "' is not a percentage; not read");
                return null;
            }
        }
        if (p.signum() < 0 || p.compareTo(BigDecimal.valueOf(100)) > 0) {
            warnings.add(where + ": " + p.toPlainString() + "% is not a percentage; not read");
            return null;
        }
        return p;
    }

    /** The investor number "No": a whole number (as a number or as text); null when empty. */
    private static Integer number(Cell cell) {
        Object v = value(cell);
        if (v instanceof Double d) {
            return d == Math.rint(d) && d >= 1 && d < Integer.MAX_VALUE ? (int) d.doubleValue() : null;
        }
        if (v instanceof String s && DIGITS.matcher(s.trim()).matches() && s.trim().length() <= 9) {
            int n = Integer.parseInt(s.trim());
            return n >= 1 ? n : null;
        }
        return null;
    }

    private static String optional(Row row, int col) {
        return col < 0 ? null : clean(text(row.getCell(col)));
    }

    /** L (lokal) or A (asing); anything else unknown. */
    private static String status(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        return v.equals("L") || v.equals("A") ? v : null;
    }

    /** A String, a Double, or null (empty). Formulas give their cached result. */
    private static Object value(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> {
                String s = cell.getStringCellValue().replace(' ', ' ').trim();
                yield s.isEmpty() ? null : s;
            }
            case NUMERIC -> cell.getNumericCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private static String text(Cell cell) {
        Object v = value(cell);
        if (v instanceof Double d) {
            return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
        }
        return (String) v;
    }

    /** Trimmed, inner whitespace (and non-breaking spaces) collapsed; null when empty. */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace(' ', ' ').trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    private static String norm(String s) {
        String t = clean(s);
        return t == null ? "" : t.toUpperCase(Locale.ROOT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * The name of an investor normalized, to recognise it across dates and companies: "PT. EVIO SECURITIES" and
     * "EVIO SECURITIES, PT" -> "EVIO SECURITIES"; "DRS LO KHENG HONG" stays (titles are part of KSEI's name).
     */
    public static String holderKey(String name) {
        String upper = clean(name) == null ? "" : clean(name).toUpperCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String token : upper.split("[^A-Z0-9]+")) {
            if (!token.isEmpty() && !token.equals("PT") && !token.equals("TBK")) {
                tokens.add(token);
            }
        }
        String key = tokens.isEmpty() ? upper : String.join(" ", tokens);
        return key.length() <= 300 ? key : key.substring(0, 300);
    }

    private static String message(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
