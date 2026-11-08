package com.neracalab.backend.company.profile;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the IDX company profiles a browser session collected on idx.co.id (the backend cannot reach it: Cloudflare).
 * Accepted, as one JSON file:
 * <ul>
 *   <li>the list response {@code GET /primary/ListedCompany/GetCompanyProfiles?emitenType=s&start=0&length=9999}:
 *       {@code {"data": [{"KodeEmiten": "AALI", "TanggalPencatatan": "1997-12-09T00:00:00", ...}]}} (or the bare array)</li>
 *   <li>one detail response {@code GET /primary/ListedCompany/GetCompanyProfilesDetail?KodeEmiten=AALI}:
 *       {@code {"Profiles": [{...}], "Dividen": [{...}], ...}}</li>
 *   <li>the file of {@code scripts/idx_company_profiles.js}: {@code {"profiles": <list response>, "details":
 *       {"AALI": <detail response>, ...}}} ({@code details} may also be an array of detail responses)</li>
 * </ul>
 * Field names are matched without regard to case, blanks and underscores, and a few alternatives are accepted, so a
 * renamed field of IDX's (TanggalPencatatan / ListingDate) still reads. A profile without a ticker is skipped with a
 * warning; values that cannot be read are left out (null) with a warning, never guessed.
 */
public final class IdxProfileParser {

    /** One company of the file: its profile (from the list or the detail) and, from a detail, its dividends. */
    public record Company(String ticker, String name, LocalDate listingDate, String listingBoard, String boardLabel,
                          String sector, String subSector, String industry, String subIndustry,
                          String businessActivity, String shareRegistrar, boolean detail, List<Dividend> dividends) {
    }

    /** A cash dividend of the detail's section Dividen. */
    public record Dividend(Integer fiscalYear, String type, BigDecimal cashPerShare, String currency, LocalDate cumDate,
                           LocalDate exDate, LocalDate recordingDate, LocalDate paymentDate, BigDecimal totalAmount,
                           String totalCurrency) {
    }

    /**
     * @param profiles companies with a profile row (list or detail)
     * @param details  companies with a detail response (their dividend history is complete)
     */
    public record Parsed(List<Company> companies, int profiles, int details, List<String> warnings) {
    }

    /** The file is not JSON or holds no company profile. */
    public static class InvalidProfileFileException extends RuntimeException {
        public InvalidProfileFileException(String message) {
            super(message);
        }
    }

    private static final Map<String, String> BOARDS = Map.of(
            "utama", "MAIN", "main", "MAIN",
            "pengembangan", "DEVELOPMENT", "development", "DEVELOPMENT",
            "akselerasi", "ACCELERATION", "acceleration", "ACCELERATION",
            "ekonomibaru", "NEW_ECONOMY", "neweconomy", "NEW_ECONOMY",
            "pemantauankhusus", "WATCHLIST", "watchlist", "WATCHLIST");

    private static final Pattern MS_DATE = Pattern.compile("/Date\\((-?\\d+)\\)/");
    private static final Pattern TICKER = Pattern.compile("^[A-Z0-9][A-Z0-9.-]*$");
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final List<DateTimeFormatter> DAY_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.forLanguageTag("id")),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("id")));

    private final JsonMapper json;

    public IdxProfileParser(JsonMapper json) {
        this.json = json;
    }

    public Parsed parse(byte[] bytes) {
        JsonNode root;
        try {
            root = json.readTree(bytes);
        } catch (RuntimeException e) {
            throw new InvalidProfileFileException("The file is not JSON: " + e.getMessage());
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new InvalidProfileFileException("The file is empty");
        }
        List<String> warnings = new ArrayList<>();
        Map<String, Builder> byTicker = new LinkedHashMap<>();

        // the collector's keys exactly (lower case): IDX's detail response has "Profiles" (capitalized) itself
        JsonNode profiles = root.isObject() ? exact(root, "profiles") : null;
        JsonNode details = root.isObject() ? exact(root, "details") : null;
        if ((profiles != null || details != null) && field(root, "Dividen", "Dividend", "Dividends") == null) {
            if (profiles != null) {
                readList(profiles, byTicker, warnings);
            }
            if (details != null) {
                readDetails(details, byTicker, warnings);
            }
        } else if (isDetail(root)) {
            readDetail(null, root, byTicker, warnings);
        } else if (root.isArray() && !root.isEmpty() && isDetail(root.get(0))) {
            readDetails(root, byTicker, warnings);
        } else {
            readList(root, byTicker, warnings);
        }

        List<Company> companies = byTicker.values().stream().map(Builder::build).toList();
        if (companies.isEmpty()) {
            throw new InvalidProfileFileException("No company profile in the file: expected the response of IDX's "
                    + "GetCompanyProfiles ({\"data\": [...]}), of GetCompanyProfilesDetail ({\"Profiles\": [...], "
                    + "\"Dividen\": [...]}) or the file of scripts/idx_company_profiles.js ({\"profiles\": ..., "
                    + "\"details\": ...})");
        }
        int withProfile = (int) byTicker.values().stream().filter(b -> b.hasProfile).count();
        int withDetail = (int) byTicker.values().stream().filter(b -> b.detail).count();
        return new Parsed(companies, withProfile, withDetail, List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ list

    private void readList(JsonNode node, Map<String, Builder> byTicker, List<String> warnings) {
        JsonNode rows = node;
        if (node.isObject()) {
            rows = field(node, "data", "profiles", "rows", "result");
            if (rows == null) {
                warnings.add("The profile list holds no 'data' array; it was skipped");
                return;
            }
        }
        if (!rows.isArray()) {
            warnings.add("The profile list is not an array; it was skipped");
            return;
        }
        int index = 0;
        for (JsonNode row : rows) {
            index++;
            readProfile(row, null, byTicker, warnings, "profile row " + index);
        }
    }

    private void readProfile(JsonNode row, String fallbackTicker, Map<String, Builder> byTicker, List<String> warnings,
                             String where) {
        if (row == null || !row.isObject()) {
            warnings.add(where + " is not an object; skipped");
            return;
        }
        String ticker = ticker(text(row, "KodeEmiten", "Kode", "Ticker", "StockCode", "KodeSaham"));
        if (ticker == null) {
            ticker = fallbackTicker;
        }
        if (ticker == null) {
            warnings.add(where + " has no ticker (KodeEmiten); skipped");
            return;
        }
        Builder b = byTicker.computeIfAbsent(ticker, Builder::new);
        b.hasProfile = true;
        b.name = first(b.name, text(row, "NamaEmiten", "Nama", "CompanyName", "Name"));
        String rawDate = text(row, "TanggalPencatatan", "ListingDate", "TglPencatatan", "TanggalListing");
        LocalDate listing = date(rawDate);
        if (rawDate != null && listing == null) {
            warnings.add(ticker + ": listing date '" + rawDate + "' cannot be read");
        }
        b.listingDate = first(b.listingDate, listing);
        String board = text(row, "PapanPencatatan", "ListingBoard", "Papan", "Board");
        if (board != null && b.boardLabel == null) {
            b.boardLabel = board;
            b.listingBoard = board(board);
        }
        b.sector = first(b.sector, text(row, "Sektor", "Sector"));
        b.subSector = first(b.subSector, text(row, "SubSektor", "SubSector"));
        b.industry = first(b.industry, text(row, "Industri", "Industry"));
        b.subIndustry = first(b.subIndustry, text(row, "SubIndustri", "SubIndustry"));
        b.businessActivity = first(b.businessActivity, text(row, "KegiatanUsahaUtama", "KegiatanUsaha",
                "BusinessActivity", "MainBusiness"));
        b.shareRegistrar = first(b.shareRegistrar, text(row, "BAE", "ShareRegistrar", "BiroAdministrasiEfek"));
    }

    // ------------------------------------------------------------------ detail

    private static boolean isDetail(JsonNode node) {
        return node != null && node.isObject()
                && (field(node, "Profiles") != null || field(node, "Dividen", "Dividend", "Dividends") != null);
    }

    private void readDetails(JsonNode details, Map<String, Builder> byTicker, List<String> warnings) {
        if (details.isObject() && !isDetail(details)) {
            for (Map.Entry<String, JsonNode> e : details.properties()) {
                readDetail(ticker(e.getKey()), e.getValue(), byTicker, warnings);
            }
        } else if (details.isArray()) {
            for (JsonNode d : details) {
                readDetail(null, d, byTicker, warnings);
            }
        } else {
            readDetail(null, details, byTicker, warnings);
        }
    }

    private void readDetail(String keyTicker, JsonNode detail, Map<String, Builder> byTicker, List<String> warnings) {
        if (detail == null || !detail.isObject()) {
            warnings.add("The detail of " + (keyTicker == null ? "a company" : keyTicker) + " is not an object; skipped");
            return;
        }
        JsonNode profiles = field(detail, "Profiles", "Profile");
        JsonNode profile = profiles == null ? null : profiles.isArray() ? (profiles.isEmpty() ? null : profiles.get(0))
                : profiles;
        String ticker = keyTicker;
        if (profile != null) {
            String own = ticker(text(profile, "KodeEmiten", "Kode", "Ticker"));
            if (own != null && keyTicker != null && !own.equals(keyTicker)) {
                warnings.add("The detail stored under " + keyTicker + " is the profile of " + own + "; read as " + own);
            }
            ticker = own != null ? own : keyTicker;
        }
        if (ticker == null) {
            warnings.add("A detail response has no ticker (Profiles[0].KodeEmiten); skipped");
            return;
        }
        if (profile != null) {
            readProfile(profile, ticker, byTicker, warnings, "the profile of " + ticker);
        }
        Builder b = byTicker.computeIfAbsent(ticker, Builder::new);
        b.detail = true;
        JsonNode dividends = field(detail, "Dividen", "Dividend", "Dividends");
        if (dividends == null || dividends.isNull()) {
            return;
        }
        if (!dividends.isArray()) {
            warnings.add(ticker + ": section Dividen is not an array; no dividends read");
            return;
        }
        // one dividend per key of uq_company_dividend (fiscal year, cum date, ex date, amount per share)
        Set<List<Object>> keys = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode d : dividends) {
            index++;
            Dividend dividend = dividend(ticker, index, d, warnings);
            if (dividend == null) {
                continue;
            }
            List<Object> key = java.util.Arrays.asList(dividend.fiscalYear(), dividend.cumDate(), dividend.exDate(),
                    dividend.cashPerShare());
            if (keys.add(key)) {
                b.dividends.add(dividend);
            } else {
                warnings.add(ticker + ": dividend " + index + " repeats an earlier one; read once");
            }
        }
    }

    private Dividend dividend(String ticker, int index, JsonNode d, List<String> warnings) {
        if (!d.isObject()) {
            return null;
        }
        String where = ticker + ": dividend " + index;
        BigDecimal perShare = number(field(d, "CashDividenPerSaham", "CashDividendPerShare", "DividenPerSaham",
                "DividendPerShare"));
        if (perShare == null || perShare.signum() <= 0) {
            // a bonus-share row or an empty one: no cash per share
            return null;
        }
        String currency = currency(text(d, "CashDividenPerSahamMU", "CashDividendPerShareCurrency", "MataUang",
                "Currency"));
        LocalDate cum = dated(d, where, "cum date", warnings, "TanggalCum", "CumDate", "TanggalCumRegulerDanNegosiasi");
        LocalDate ex = dated(d, where, "ex date", warnings, "TanggalExRegulerDanNegosiasi", "TanggalEx", "ExDate",
                "TanggalExReguler");
        LocalDate recording = dated(d, where, "recording date", warnings, "TanggalDPS", "RecordingDate",
                "TanggalRecording");
        LocalDate payment = dated(d, where, "payment date", warnings, "TanggalPembayaran", "PaymentDate");
        if (cum == null && ex == null && payment == null) {
            warnings.add(where + " has no cum, ex or payment date; skipped");
            return null;
        }
        Integer year = year(text(d, "TahunBuku", "FiscalYear", "Tahun"));
        String type = text(d, "Jenis", "Type", "JenisDividen");
        BigDecimal total = number(field(d, "CashDividenTotal", "CashDividendTotal", "TotalDividen"));
        // the stored form: per share at 8 decimals, the total at 4 (NUMERIC(24,8) / NUMERIC(28,4))
        return new Dividend(year, type == null || type.length() <= 40 ? type : type.substring(0, 40),
                perShare.setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros(), currency, cum, ex, recording,
                payment,
                total == null || total.signum() <= 0 ? null
                        : total.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros(),
                currency(text(d, "CashDividenTotalMU", "CashDividendTotalCurrency")));
    }

    private static LocalDate dated(JsonNode d, String where, String what, List<String> warnings, String... names) {
        String raw = text(d, names);
        LocalDate date = date(raw);
        if (raw != null && date == null) {
            warnings.add(where + ": " + what + " '" + raw + "' cannot be read");
        }
        return date;
    }

    // ------------------------------------------------------------------ values

    /** A property by its exact name; null when absent or JSON null. */
    private static JsonNode exact(JsonNode node, String name) {
        JsonNode v = node.get(name);
        return v == null || v.isNull() || v.isMissingNode() ? null : v;
    }

    /** A property by any of the names, ignoring case, blanks and underscores; null when absent or JSON null. */
    static JsonNode field(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode exact = node.get(name);
            if (exact != null && !exact.isNull() && !exact.isMissingNode()) {
                return exact;
            }
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String key = squash(e.getKey());
            for (String name : names) {
                if (key.equals(squash(name)) && !e.getValue().isNull()) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String squash(String s) {
        return s.replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /** Trimmed text of a string or number property; null when absent, blank or "-". */
    static String text(JsonNode node, String... names) {
        JsonNode v = field(node, names);
        if (v == null || !(v.isString() || v.isNumber() || v.isBoolean())) {
            return null;
        }
        String s = v.asString().replace(' ', ' ').trim().replaceAll("\\s+", " ");
        return s.isEmpty() || s.equals("-") ? null : s;
    }

    static String ticker(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        return TICKER.matcher(t).matches() ? t : null;
    }

    /** IDX's board name as a code (MAIN, DEVELOPMENT, ...); another value upper case with underscores. */
    static String board(String label) {
        String key = label.toLowerCase(Locale.ROOT).replaceAll("^papan\\s+", "").replaceAll("[^a-z]", "");
        String code = BOARDS.get(key);
        if (code != null) {
            return code;
        }
        String other = label.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        return other.isEmpty() ? null : other.length() > 40 ? other.substring(0, 40) : other;
    }

    /** ISO date or date-time ("1997-12-09T00:00:00"), /Date(ms)/, d/M/yyyy, "9 Dec 1997" / "9 Desember 1997". */
    static LocalDate date(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        Matcher ms = MS_DATE.matcher(s);
        if (ms.find()) {
            return Instant.ofEpochMilli(Long.parseLong(ms.group(1))).atZone(JAKARTA).toLocalDate();
        }
        if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            try {
                LocalDate d = LocalDate.parse(s.substring(0, 10));
                return d.getYear() < 1900 ? null : d;
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        for (DateTimeFormatter f : DAY_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException e) {
                // next format
            }
        }
        return null;
    }

    /** A JSON number, or a number in text with thousands separators ("1,234.5", "Rp 25"). */
    static BigDecimal number(JsonNode v) {
        if (v == null) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (!v.isString()) {
            return null;
        }
        String s = v.asString().replaceAll("(?i)rp|idr|usd|[\\s ]", "");
        if (s.isEmpty() || s.equals("-")) {
            return null;
        }
        int comma = s.lastIndexOf(',');
        int dot = s.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            // both: the last one is the decimal separator (1.234,5 / 1,234.5)
            s = comma > dot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (comma >= 0) {
            // 1,234,567 groups thousands; 538,08 / 12,5 is a decimal comma
            s = s.matches("-?\\d{1,3}(,\\d{3})+") ? s.replace(",", "") : s.replace(',', '.');
        } else if (dot >= 0 && s.matches("-?\\d{1,3}(\\.\\d{3}){2,}")) {
            // 1.234.567: thousands (a single dot is a decimal point)
            s = s.replace(".", "");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String currency(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.trim().toUpperCase(Locale.ROOT);
        if (c.equals("RP")) {
            return "IDR";
        }
        return c.matches("[A-Z]{3}") ? c : null;
    }

    static Integer year(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(raw);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    private static <T> T first(T current, T next) {
        return current != null ? current : next;
    }

    private static final class Builder {
        final String ticker;
        boolean hasProfile;
        boolean detail;
        String name;
        LocalDate listingDate;
        String listingBoard;
        String boardLabel;
        String sector;
        String subSector;
        String industry;
        String subIndustry;
        String businessActivity;
        String shareRegistrar;
        final List<Dividend> dividends = new ArrayList<>();

        Builder(String ticker) {
            this.ticker = ticker;
        }

        Company build() {
            return new Company(ticker, name, listingDate, listingBoard, boardLabel, sector, subSector, industry,
                    subIndustry, businessActivity, shareRegistrar, detail, List.copyOf(dividends));
        }
    }
}
