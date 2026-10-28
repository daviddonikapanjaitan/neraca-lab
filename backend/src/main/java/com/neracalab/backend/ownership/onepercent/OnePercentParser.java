package com.neracalab.backend.ownership.onepercent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads KSEI's monthly list of the holders of more than 1% of the listed companies, as published on idx.co.id
 * ({@code peng-2026-09-00024-satu-persen.xlsx}). One sheet: a disclaimer (Indonesian and English), then a header row
 * ({@code DATE, SHARE_CODE, ISSUER_NAME, INVESTOR_NAME, INVESTOR_TYPE} or {@code INVESTOR_CLASSIFICATION,
 * LOCAL_FOREIGN, NATIONALITY, DOMICILE, HOLDINGS_SCRIPLESS, HOLDINGS_SCRIP, TOTAL_HOLDING_SHARES, PERCENTAGE}) and one
 * row per holding on the month's last trading day (its DATE). The columns are found by their header, not by position,
 * and the header may be anywhere in the first 30 rows.
 * <p>
 * What the lists hold, and how it is read:
 * <ul>
 *   <li>The investor type is a code in the older lists (CP, ID, MF, IB, IS, SC, PF, FD, OT) and a classification from
 *       April 2026 ("Corporate", "Mutual Funds", "Sole proprietorship", "PermanentEstablishment", ...): both become one
 *       label ({@link #investorType}) and a group ({@link #typeGroup}).</li>
 *   <li>LOCAL_FOREIGN is "L" or "A" / "F" (padded with blanks in some lists); empty for a special scrip account.</li>
 *   <li>KSEI counts an investor by SID, and an investor without an SID by account, so the same investor may have two
 *       rows for a company (an SID and a scrip account, or "PT X" and "X, PT"): rows of a company whose names are the
 *       same once normalized ({@link #holderKey}) are one investor, their shares added, with a note.</li>
 *   <li>A total that is not the sum of its scripless and scrip shares (MAYA in February 2026, SMRA in March 2026) is
 *       decided by the percentage: the one whose share of the shares listed (implied by the company's other rows)
 *       is closest to the printed percentage is kept, with a note.</li>
 *   <li>Rows without a ticker and an investor (empty, or "#DIV/0!" in a column without a header) are skipped.</li>
 * </ul>
 */
public final class OnePercentParser {

    /** Not a readable list of the holders of more than 1%: nothing is stored. */
    public static class OnePercentFormatException extends RuntimeException {

        public OnePercentFormatException(String message) {
            super(message);
        }
    }

    /**
     * An investor holding more than 1% of a company.
     *
     * @param row          its order in the list (the first of its rows)
     * @param holderKey    the name normalized: the same investor across months and companies
     * @param otherNames   the other names of its rows (the same investor listed twice)
     * @param sourceRows   rows of the sheet counted together
     * @param investorType a label (Individual, Corporate, Mutual Funds, ...); null when not printed
     * @param typeGroup    INDIVIDUAL, CORPORATE, FUND, FINANCIAL, GOVERNMENT or OTHER; null when not printed
     * @param status       L (local) or A (foreign); null when not printed
     * @param percent      percentage of the shares listed, as printed (four decimals at most); null when not printed
     */
    public record Holder(int row, String ticker, String issuerName, String holderName, String holderKey,
                         List<String> otherNames, int sourceRows, String investorType, String typeGroup,
                         String status, String nationality, String domicile, long scripless, long scrip,
                         long shares, BigDecimal percent) {
    }

    /**
     * The list of one month.
     *
     * @param reportDate the list's date (its DATE column)
     * @param sourceRows rows of the sheet read (before rows of the same investor were counted together)
     * @param warnings   rows that could not be read
     * @param notes      what was decided while reading
     */
    public record Report(LocalDate reportDate, List<Holder> holders, int sourceRows, List<String> warnings,
                         List<String> notes) {

        /** The companies listed, in the list's order. */
        public List<String> tickers() {
            return holders.stream().map(Holder::ticker).distinct().toList();
        }
    }

    /** Most notes kept about totals and investors counted together (then one line says there are more). */
    static final int MAX_NOTES = 100;

    private static final Pattern COMMA_GROUPED = Pattern.compile("\\d{1,3}(,\\d{3})+");
    private static final Pattern DOT_GROUPED = Pattern.compile("\\d{1,3}(\\.\\d{3})+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH),
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM uuuu").toFormatter(Locale.ENGLISH));

    /** The words left out of {@link #holderKey}: legal forms and titles that KSEI writes one month and not the next. */
    private static final Set<String> DROPPED = Set.of("PT", "TBK", "PERSERO", "PTE", "LTD", "LIMITED", "PRIVATE", "INC",
            "DRS", "DR", "IR", "H", "HJ");

    /** Type codes of the lists before April 2026. */
    private static final Map<String, String> TYPE_CODES = Map.of(
            "CP", "Corporate", "ID", "Individual", "MF", "Mutual Funds", "IB", "Financial Institution",
            "IS", "Insurance", "SC", "Securities Company", "PF", "Pension Funds", "FD", "Foundation", "OT", "Others");

    /** Classifications from April 2026 (letters only, lower case) -> label and group. */
    private static final Map<String, String[]> TYPES = new HashMap<>();

    static {
        type("INDIVIDUAL", "Individual");
        type("CORPORATE", "Corporate", "Firm", "Partnership", "Limited Partnership (CV)", "Sole Proprietorship",
                "Permanent Establishment", "Cooperatives");
        type("FUND", "Mutual Funds", "Exchange Traded Funds", "Hedge Fund", "Pension Funds", "Venture Capital",
                "Private Equity");
        type("FINANCIAL", "Financial Institution", "Financial Institutional", "Bank", "Private Bank", "Trustee Bank",
                "Insurance", "Securities Company", "Brokerage Firms", "Investment Manager", "Investment Advisors",
                "Investment Fund Selling Agent", "Peer To Peer Lending",
                "Capital Market Supporting Institutions And Professions");
        type("GOVERNMENT", "Government", "State Owned Enterprises", "State Owned Company", "Sovereign Wealth Fund");
        type("OTHER", "Foundation", "International Organization", "Educational Institution", "Diocese",
                "Association/Social Organizations", "Others");
        TYPES.put(letters("Commanditaire Vennootschap (CV) Or Limited Partnership"),
                new String[] {"Limited Partnership (CV)", "CORPORATE"});
    }

    private static void type(String group, String... labels) {
        for (String label : labels) {
            TYPES.put(letters(label), new String[] {label, group});
        }
    }

    private OnePercentParser() {
    }

    // ------------------------------------------------------------------ parse

    /**
     * Reads the list.
     *
     * @throws OnePercentFormatException not a workbook, not the list of the holders of more than 1%, or no row
     */
    public static Report parse(byte[] content) {
        ZipSecureFile.setMinInflateRatio(0.001);
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : book) {
                Columns columns = columns(sheet);
                if (columns != null) {
                    return read(sheet, columns);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof OnePercentFormatException f) {
                throw f;
            }
            throw new OnePercentFormatException("Not a readable Excel workbook (.xlsx): " + message(e));
        }
        throw new OnePercentFormatException("Not KSEI's list of the shareholders of more than 1%: no sheet has the "
                + "header 'SHARE_CODE' / 'INVESTOR_NAME' / 'TOTAL_HOLDING_SHARES' / 'PERCENTAGE'");
    }

    private record Columns(int headerRow, int date, int ticker, int issuer, int investor, int type, int status,
                           int nationality, int domicile, int scripless, int scrip, int total, int percent) {
    }

    /** The header row (within the first 30 rows) and its columns; null when the sheet has none. */
    private static Columns columns(Sheet sheet) {
        for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 30); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, Integer> named = new LinkedHashMap<>();
            for (Cell cell : row) {
                String name = norm(text(cell)).replace(' ', '_');
                if (!name.isEmpty()) {
                    named.putIfAbsent(name, cell.getColumnIndex());
                }
            }
            if (named.containsKey("SHARE_CODE") && named.containsKey("INVESTOR_NAME")
                    && named.containsKey("TOTAL_HOLDING_SHARES") && named.containsKey("PERCENTAGE")) {
                return new Columns(r, named.getOrDefault("DATE", -1), named.get("SHARE_CODE"),
                        named.getOrDefault("ISSUER_NAME", -1), named.get("INVESTOR_NAME"),
                        named.getOrDefault("INVESTOR_CLASSIFICATION", named.getOrDefault("INVESTOR_TYPE", -1)),
                        named.getOrDefault("LOCAL_FOREIGN", -1), named.getOrDefault("NATIONALITY", -1),
                        named.getOrDefault("DOMICILE", -1), named.getOrDefault("HOLDINGS_SCRIPLESS", -1),
                        named.getOrDefault("HOLDINGS_SCRIP", -1), named.get("TOTAL_HOLDING_SHARES"),
                        named.get("PERCENTAGE"));
            }
        }
        return null;
    }

    /** A row as read; {@code total} not yet decided when the sheet's total and the sum of its parts differ. */
    private static final class RawRow {
        final int sheetRow;
        final String ticker;
        final String issuer;
        final String name;
        final String type;
        final String group;
        final String status;
        final String nationality;
        final String domicile;
        long scripless;
        long scrip;
        long total;
        /** the sum of the parts, when it differs from the sheet's total */
        Long sum;
        final BigDecimal percent;

        RawRow(int sheetRow, String ticker, String issuer, String name, String type, String group, String status,
               String nationality, String domicile, long scripless, long scrip, long total, Long sum,
               BigDecimal percent) {
            this.sheetRow = sheetRow;
            this.ticker = ticker;
            this.issuer = issuer;
            this.name = name;
            this.type = type;
            this.group = group;
            this.status = status;
            this.nationality = nationality;
            this.domicile = domicile;
            this.scripless = scripless;
            this.scrip = scrip;
            this.total = total;
            this.sum = sum;
            this.percent = percent;
        }
    }

    private static Report read(Sheet sheet, Columns c) {
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<LocalDate, Integer> dates = new LinkedHashMap<>();
        List<RawRow> rows = new ArrayList<>();
        int skipped = 0;
        for (int r = c.headerRow() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String where = "Row " + (r + 1);
            String ticker = clean(text(row.getCell(c.ticker())));
            String name = clean(text(row.getCell(c.investor())));
            if (ticker == null && name == null) {
                if (hasContent(row)) {
                    skipped++;
                }
                continue;
            }
            ticker = ticker == null ? null : ticker.toUpperCase(Locale.ROOT);
            if (ticker == null || !TICKER.matcher(ticker).matches()) {
                warnings.add(where + ": '" + (ticker == null ? "" : ticker) + "' is not a ticker; not read");
                continue;
            }
            if (name == null) {
                warnings.add(where + " (" + ticker + "): no investor name; not read");
                continue;
            }
            Long scripless;
            Long scrip;
            Long total;
            try {
                scripless = c.scripless() < 0 ? null : shares(row.getCell(c.scripless()));
                scrip = c.scrip() < 0 ? null : shares(row.getCell(c.scrip()));
                total = shares(row.getCell(c.total()));
            } catch (NumberFormatException e) {
                warnings.add(where + " (" + ticker + ", " + name + "): " + e.getMessage() + "; not read");
                continue;
            }
            Long sum = scripless == null && scrip == null ? null
                    : (scripless == null ? 0 : scripless) + (scrip == null ? 0 : scrip);
            if (total == null && sum == null) {
                warnings.add(where + " (" + ticker + ", " + name + "): no shares; not read");
                continue;
            }
            BigDecimal percent = percent(row.getCell(c.percent()), where, warnings);
            if (c.date() >= 0) {
                LocalDate d = date(row.getCell(c.date()));
                if (d != null) {
                    dates.merge(d, 1, Integer::sum);
                }
            }
            String rawType = c.type() < 0 ? null : clean(text(row.getCell(c.type())));
            String[] type = investorType(rawType);
            long kept = total != null ? total : sum;
            long less = scripless == null ? (scrip == null ? kept : Math.max(0, kept - scrip)) : scripless;
            long more = scrip == null ? Math.max(0, kept - less) : scrip;
            rows.add(new RawRow(r + 1, ticker, clean(text(c.issuer() < 0 ? null : row.getCell(c.issuer()))), name,
                    type == null ? null : type[0], type == null ? null : type[1],
                    status(c.status() < 0 ? null : text(row.getCell(c.status()))),
                    c.nationality() < 0 ? null : clean(text(row.getCell(c.nationality()))),
                    c.domicile() < 0 ? null : clean(text(row.getCell(c.domicile()))), less, more, kept,
                    total != null && sum != null && !total.equals(sum) ? sum : null, percent));
        }
        if (rows.isEmpty()) {
            throw new OnePercentFormatException("Not KSEI's list of the shareholders of more than 1%: the sheet has the "
                    + "header but no holding");
        }
        if (dates.isEmpty()) {
            throw new OnePercentFormatException("The list has no date: its DATE column is empty or missing");
        }
        LocalDate reportDate = dates.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        if (dates.size() > 1) {
            int others = dates.entrySet().stream().filter(e -> !e.getKey().equals(reportDate))
                    .mapToInt(Map.Entry::getValue).sum();
            warnings.add(others + (others == 1 ? " row is" : " rows are") + " dated otherwise than " + reportDate
                    + "; read as of " + reportDate);
        }
        decideTotals(rows, notes);
        if (skipped > 0) {
            notes.add(skipped + (skipped == 1 ? " row" : " rows") + " without a ticker and an investor skipped");
        }
        List<Holder> holders = combine(rows, notes);
        return new Report(reportDate, List.copyOf(holders), rows.size(), List.copyOf(warnings), List.copyOf(notes));
    }

    /**
     * A total that is not the sum of its scripless and scrip shares: the one whose percentage of the shares listed
     * (the company's other rows give them: shares / percentage) is closer to the printed percentage is kept.
     */
    private static void decideTotals(List<RawRow> rows, List<String> notes) {
        Map<String, List<RawRow>> byTicker = new LinkedHashMap<>();
        rows.forEach(r -> byTicker.computeIfAbsent(r.ticker, k -> new ArrayList<>()).add(r));
        for (RawRow r : rows) {
            if (r.sum == null) {
                continue;
            }
            Double listed = impliedListed(byTicker.get(r.ticker), r);
            boolean sumWins = false;
            if (listed != null && r.percent != null) {
                double printed = r.percent.doubleValue();
                double bySum = r.sum * 100.0 / listed;
                double byTotal = r.total * 100.0 / listed;
                sumWins = Math.abs(bySum - printed) < Math.abs(byTotal - printed);
            }
            String head = r.ticker + ", " + r.name + " (row " + r.sheetRow + "): scripless " + format(r.scripless)
                    + " + scrip " + format(r.scrip) + " = " + format(r.sum) + " but the total is " + format(r.total);
            if (sumWins) {
                note(notes, head + "; the sum kept (the percentage " + r.percent.toPlainString() + "% confirms it)");
                r.total = r.sum;
            } else {
                long scripless = r.total - r.scrip;
                note(notes, head + "; the total kept" + (listed == null || r.percent == null ? " (nothing to decide by)"
                        : " (the percentage " + r.percent.toPlainString() + "% confirms it)")
                        + (scripless >= 0 ? ", its scripless shares read as " + format(scripless) : ""));
                if (scripless >= 0) {
                    r.scripless = scripless;
                } else {
                    r.scrip = Math.max(0, r.total - r.scripless);
                }
            }
            r.sum = null;
        }
    }

    /** The shares listed implied by the company's other rows (median of shares / percentage); null when none. */
    private static Double impliedListed(List<RawRow> rows, RawRow except) {
        List<Double> implied = new ArrayList<>();
        for (RawRow r : rows) {
            if (r != except && r.sum == null && r.percent != null && r.percent.doubleValue() >= 0.5 && r.total > 0) {
                implied.add(r.total * 100.0 / r.percent.doubleValue());
            }
        }
        if (implied.isEmpty()) {
            return null;
        }
        implied.sort(Double::compare);
        int n = implied.size();
        return n % 2 == 1 ? implied.get(n / 2) : (implied.get(n / 2 - 1) + implied.get(n / 2)) / 2;
    }

    /** The rows of one company whose names are the same once normalized are one investor. */
    private static List<Holder> combine(List<RawRow> rows, List<String> notes) {
        Map<String, List<RawRow>> groups = new LinkedHashMap<>();
        for (RawRow r : rows) {
            groups.computeIfAbsent(r.ticker + "\u001f" + holderKey(r.name), k -> new ArrayList<>()).add(r);
        }
        List<Holder> out = new ArrayList<>(groups.size());
        int number = 0;
        for (List<RawRow> group : groups.values()) {
            number++;
            RawRow main = group.stream().max((a, b) -> Long.compare(a.total, b.total)).orElseThrow();
            Set<String> others = new LinkedHashSet<>();
            group.forEach(r -> {
                if (!r.name.equals(main.name)) {
                    others.add(r.name);
                }
            });
            long scripless = 0;
            long scrip = 0;
            long total = 0;
            BigDecimal percent = null;
            for (RawRow r : group) {
                scripless += r.scripless;
                scrip += r.scrip;
                total += r.total;
                if (r.percent != null) {
                    percent = percent == null ? r.percent : percent.add(r.percent);
                }
            }
            if (group.size() > 1) {
                note(notes, main.ticker + ": " + main.name + " listed " + group.size() + " times"
                        + (others.isEmpty() ? "" : " (also as " + String.join("; ", others) + ")")
                        + ": counted as one investor with " + format(total) + " shares");
            }
            out.add(new Holder(number, main.ticker, main.issuer == null ? main.ticker : main.issuer, main.name,
                    holderKey(main.name), List.copyOf(others), group.size(), first(group, main, r -> r.type),
                    first(group, main, r -> r.group), first(group, main, r -> r.status),
                    first(group, main, r -> r.nationality), first(group, main, r -> r.domicile), scripless, scrip,
                    total, percent));
        }
        return out;
    }

    /** The value of the investor's largest row, else of its first row that has one. */
    private static String first(List<RawRow> group, RawRow main, java.util.function.Function<RawRow, String> field) {
        String value = field.apply(main);
        if (value != null) {
            return value;
        }
        return group.stream().map(field).filter(v -> v != null).findFirst().orElse(null);
    }

    private static void note(List<String> notes, String note) {
        long kept = notes.stream().filter(n -> !n.startsWith("…")).count();
        if (kept < MAX_NOTES) {
            notes.add(note);
        } else if (notes.isEmpty() || !notes.getLast().startsWith("…")) {
            notes.add("… and more (the first " + MAX_NOTES + " are shown)");
        }
    }

    // ------------------------------------------------------------------ names and types

    /**
     * The name of an investor normalized, to recognise it across months and companies: "PT. BEYOND MEDIA",
     * "BEYOND MEDIA, PT" and "PT Beyond Media" -> "BEYOND MEDIA"; "IR. T. PERMADI RACHMAT" -> "T PERMADI RACHMAT";
     * "UOB KAY HIAN PRIVATE LIMITED" and "UOB KAY HIAN PTE LTD" -> "UOB KAY HIAN".
     */
    public static String holderKey(String name) {
        String upper = clean(name) == null ? "" : clean(name).toUpperCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String token : upper.split("[^A-Z0-9]+")) {
            if (!token.isEmpty() && !DROPPED.contains(token)) {
                tokens.add(token);
            }
        }
        String key = tokens.isEmpty() ? upper.replaceAll("[^A-Z0-9]+", " ").trim() : String.join(" ", tokens);
        if (key.isEmpty()) {
            key = upper;
        }
        return key.length() <= 300 ? key : key.substring(0, 300);
    }

    /** [label, group] of a type code or classification; null when empty. */
    static String[] investorType(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if (TYPE_CODES.containsKey(code)) {
            String label = TYPE_CODES.get(code);
            return TYPES.get(letters(label));
        }
        String[] known = TYPES.get(letters(raw));
        if (known != null) {
            return known;
        }
        String label = clean(raw);
        return label == null ? null : new String[] {label.length() <= 100 ? label : label.substring(0, 100), "OTHER"};
    }

    private static String letters(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /** L (lokal) or A (asing; the newer lists write F, foreign); anything else unknown. */
    private static String status(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "L", "LOCAL", "LOKAL" -> "L";
            case "A", "F", "FOREIGN", "ASING" -> "A";
            default -> null;
        };
    }

    // ------------------------------------------------------------------ cells

    /** A share count: a number, or text "1,197,023,942" / "1.197.023.942"; null when empty or "-". */
    private static Long shares(Cell cell) {
        Object v = value(cell);
        if (v == null) {
            return null;
        }
        if (v instanceof Double d) {
            if (d < 0 || d.isNaN() || d.isInfinite() || d != Math.rint(d)) {
                throw new NumberFormatException(d + " is not a share count");
            }
            return d.longValue();
        }
        String s = ((String) v).replace(" ", "");
        if (s.isEmpty() || s.chars().allMatch(ch -> ch == '-')) {
            return null;
        }
        if (COMMA_GROUPED.matcher(s).matches()) {
            return Long.parseLong(s.replace(",", ""));
        }
        if (DOT_GROUPED.matcher(s).matches()) {
            return Long.parseLong(s.replace(".", ""));
        }
        if (DIGITS.matcher(s).matches() && s.length() <= 18) {
            return Long.parseLong(s);
        }
        throw new NumberFormatException("'" + s + "' is not a share count");
    }

    /** 15.370000000000001 -> 15.3700; "41,10" / "41.10%" too; null when empty or not between 0 and 100. */
    private static BigDecimal percent(Cell cell, String where, List<String> warnings) {
        Object v = value(cell);
        if (v == null) {
            return null;
        }
        BigDecimal p;
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                return null;
            }
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
        return p.setScale(4, RoundingMode.HALF_UP);
    }

    /** A date cell (an Excel date, or text 2026-09-30 / 30/09/2026 / 30-Sep-2026); null when none. */
    private static LocalDate date(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell) || (d > 20000 && d < 80000)) {
                return DateUtil.getLocalDateTime(d).toLocalDate();
            }
            return null;
        }
        String s = clean(text(cell));
        if (s == null) {
            return null;
        }
        String day = s.length() > 10 && s.charAt(10) == 'T' ? s.substring(0, 10) : s;
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(day, f);
            } catch (DateTimeException e) {
                // the next format
            }
        }
        return null;
    }

    /** A String, a Double, or null (empty). Formulas give their cached result; errors (#DIV/0!) are empty. */
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

    private static boolean hasContent(Row row) {
        for (Cell cell : row) {
            if (value(cell) != null) {
                return true;
            }
        }
        return false;
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

    private static String format(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String message(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
