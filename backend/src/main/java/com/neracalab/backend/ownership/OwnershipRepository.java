package com.neracalab.backend.ownership;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ownership.OwnershipParser.Account;
import com.neracalab.backend.ownership.OwnershipParser.Holder;
import com.neracalab.backend.ownership.OwnershipParser.Report;
import com.neracalab.backend.rag.ExcelDocumentType;
import com.neracalab.backend.rag.RagRepository.Company;

/**
 * KSEI's daily lists of the shareholders of more than 5% ({@code ownership_report}, one per day), their investors
 * ({@code ownership_holder}) and the investors' securities accounts ({@code ownership_account}). The movements of the
 * investors, with those that left a list, come from {@code v_ownership_movement}.
 */
@Repository
public class OwnershipRepository {

    /** What storing a list did. */
    public enum Outcome {
        /** the first list of the day */
        STORED,
        /** it replaced the list of the day stored before */
        REPLACED
    }

    /**
     * @param exited investors of the stored list of the day before that are not in this one (fell below 5%); null when
     *               the list of the day before is not stored
     */
    public record Stored(Outcome outcome, long reportId, Integer exited) {
    }

    /** The movements to list. */
    public enum Transaction {
        ALL, BUY, SELL,
        /** a buy or a sell */
        CHANGED,
        /** crossed 5% */
        ENTERED,
        /** fell below 5% (left the list) */
        EXITED,
        /** entered or exited */
        CROSSED,
        /** anything but no change */
        ACTIVITY;

        public static Optional<Transaction> of(String code) {
            if (code == null || code.isBlank()) {
                return Optional.of(ALL);
            }
            String c = code.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values()).filter(t -> t.name().equals(c)).findFirst();
        }

        /** The movements of {@code v_ownership_movement.movement} it lists; null = every one. */
        List<String> movements() {
            return switch (this) {
                case ALL -> null;
                case BUY -> List.of("BUY");
                case SELL -> List.of("SELL");
                case CHANGED -> List.of("BUY", "SELL");
                case ENTERED -> List.of("ENTERED");
                case EXITED -> List.of("EXITED");
                case CROSSED -> List.of("ENTERED", "EXITED");
                case ACTIVITY -> List.of("BUY", "SELL", "ENTERED", "EXITED");
            };
        }
    }

    /**
     * The order of {@link #movements}: by day (latest first, then ticker and the list's order), or by the change in
     * shares or in percentage points, largest first (DESC: the largest buys first) or smallest first (ASC: the largest
     * sells first). Rows without a change (an investor that crossed 5% or fell below it) come last either way; equal
     * changes by day as DATE.
     */
    public enum Sort {
        DATE, CHANGE_DESC, CHANGE_ASC, POINTS_DESC, POINTS_ASC;

        /** The sort of a code ("change_desc", "change-asc" too); DATE when not given, empty when unknown. */
        public static Optional<Sort> of(String code) {
            if (code == null || code.isBlank()) {
                return Optional.of(DATE);
            }
            String c = code.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            return Arrays.stream(values()).filter(s -> s.name().equals(c)).findFirst();
        }

        String orderBy() {
            String byDate = "m.report_date DESC, m.ticker, m.left_list, m.list_number";
            return switch (this) {
                case DATE -> byDate;
                case CHANGE_DESC -> "m.change_shares DESC NULLS LAST, " + byDate;
                case CHANGE_ASC -> "m.change_shares ASC NULLS LAST, " + byDate;
                case POINTS_DESC -> "m.percent_current - m.percent_previous DESC NULLS LAST, m.change_shares DESC NULLS LAST, "
                        + byDate;
                case POINTS_ASC -> "m.percent_current - m.percent_previous ASC NULLS LAST, m.change_shares ASC NULLS LAST, "
                        + byDate;
            };
        }
    }

    /**
     * The filters of {@link #movements}: {@code null} / empty = any.
     *
     * @param holder  part of an investor's name (normalized as {@link OwnershipParser#holderKey})
     * @param status  L (local) or A (foreign)
     * @param from    first date (inclusive)
     * @param to      last date (inclusive)
     * @param aliases also the investors listed again under another name
     */
    public record Filter(String exchange, ExcelDocumentType documentType, List<String> tickers, String holder,
                         Transaction transaction, String status, LocalDate from, LocalDate to, boolean aliases) {
    }

    /**
     * An investor of a company in the list of a day.
     *
     * @param holderId       its row (of the day before when it left the list)
     * @param aliases        the other names the same investor (the same accounts) is listed under that day
     * @param change         shares bought (+) or sold (-); null when either date has none
     * @param percentChange  percentage points, current - previous; null when either is missing
     * @param movement       BUY, SELL, UNCHANGED, ENTERED (crossed 5%) or EXITED (fell below 5%)
     * @param accountMoves   accounts whose shares changed (with UNCHANGED: moved between accounts)
     * @param leftList       not in the day's list: it was in the list of the day before (EXITED)
     */
    public record MovementRow(long holderId, long reportId, String exchange, LocalDate reportDate,
                              LocalDate previousDate, String ticker, String issuerName, int listNumber,
                              String holderName, String holderKey, Integer aliasOf, List<String> aliases,
                              String nationality, String domicile, String status, Long sharesPrevious,
                              BigDecimal percentPrevious, Long sharesCurrent, BigDecimal percentCurrent, Long change,
                              BigDecimal percentChange, String movement, int accounts, int accountMoves,
                              boolean leftList) {
    }

    public record MovementPage(long total, int limit, int offset, List<MovementRow> rows) {
    }

    /** A securities account of an investor in the list of a day. */
    public record AccountRow(long holderId, int index, String participant, String accountName, String nationality,
                             String domicile, String status, Long sharesPrevious, Long sharesCurrent, Long change) {
    }

    /** An investor of a company in the list of a day, with its accounts (empty when it left the list). */
    public record HolderDay(MovementRow holder, List<AccountRow> accounts) {
    }

    /** A stored list. */
    public record ReportRow(long reportId, String exchange, ExcelDocumentType documentType, LocalDate reportDate,
                            LocalDate previousDate, String title, String announcementNumber, String fileName,
                            Long fileId, int issuers, int holders, int accounts, int aliases, int buys, int sells,
                            int unchanged, int entered, Integer exited, List<String> warnings, List<String> notes,
                            Instant updatedAt) {
    }

    public record ReportPage(long total, int limit, int offset, List<ReportRow> reports) {
    }

    /**
     * A company of the stored lists: its investors on the latest day it is listed.
     *
     * @param stored the company is stored in {@code company} (it has a company page and an ownership document)
     */
    public record TickerRow(String ticker, String issuerName, int holders, LocalDate lastDate, LocalDate firstDate,
                            int days, boolean stored) {
    }

    /**
     * An investor (by its normalized name) and what it holds.
     *
     * @param holdings each company on the latest day the investor is listed for it, largest percentage first
     */
    public record Entity(String holderKey, String name, String status, String nationality, String domicile,
                         List<EntityHolding> holdings) {
    }

    /**
     * @param lastDate      the latest list naming the investor for the company
     * @param listed        it is in the latest stored list
     * @param firstDate     the first stored list naming it
     * @param firstShares   its shares then (on firstDate)
     * @param change        shares since firstDate (its shares then: on the day before its first stored list, or the
     *                      day it entered); null when it entered the list again later (it left it in between)
     */
    public record EntityHolding(String ticker, String issuerName, LocalDate lastDate, Long shares, BigDecimal percent,
                                String lastMovement, boolean listed, LocalDate firstDate, Long firstShares,
                                Long change, int accounts) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public OwnershipRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    // ------------------------------------------------------------------ storing

    private static final Pattern ANNOUNCEMENT = Pattern.compile("(?i)peng-\\d{4}-\\d{2}-\\d{2}-(\\d+)");

    /** "peng-2026-10-27-00104-lima-persen.xlsx" -> "00104"; null for another name. */
    static String announcementNumber(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = ANNOUNCEMENT.matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Stores the list of a day; a list of the same day stored before is replaced (with its investors and accounts),
     * in one transaction.
     */
    @Transactional
    public Stored store(String exchange, ExcelDocumentType type, Report report, Long fileId, String fileName,
                        UUID jobId) {
        Optional<Long> existing = jdbc.sql("""
                        SELECT report_id FROM ownership_report
                        WHERE exchange = :e AND document_type = :t AND report_date = :d""")
                .param("e", exchange).param("t", type.name()).param("d", report.reportDate())
                .query(Long.class).optional();
        existing.ifPresent(id -> jdbc.sql("DELETE FROM ownership_report WHERE report_id = :id").param("id", id).update());
        long reportId = jdbc.sql("""
                        INSERT INTO ownership_report (exchange, document_type, report_date, previous_date, title,
                                                      announcement_number, file_id, file_name, issuers, holders,
                                                      accounts, aliases, warnings, notes, job_id)
                        VALUES (:exchange, :type, :date, :previous, :title, :announcement, :fileId, :fileName,
                                :issuers, :holders, :accounts, :aliases, :warnings, :notes, :job)
                        RETURNING report_id""")
                .param("exchange", exchange)
                .param("type", type.name())
                .param("date", report.reportDate())
                .param("previous", report.previousDate())
                .param("title", truncate(report.title(), 500), Types.VARCHAR)
                .param("announcement", announcementNumber(fileName), Types.VARCHAR)
                .param("fileId", fileId, Types.BIGINT)
                .param("fileName", truncate(fileName, 255), Types.VARCHAR)
                .param("issuers", report.tickers().size())
                .param("holders", report.holders().size())
                .param("accounts", report.accounts())
                .param("aliases", report.aliases())
                .param("warnings", lines(report.warnings()), Types.VARCHAR)
                .param("notes", lines(report.notes()), Types.VARCHAR)
                .param("job", jobId)
                .query(Long.class).single();

        List<Object[]> holders = new ArrayList<>(report.holders().size());
        for (Holder h : report.holders()) {
            holders.add(new Object[] {reportId, exchange, report.reportDate(), report.previousDate(), h.ticker(),
                    truncate(h.issuerName(), 300), h.row(), truncate(h.holderName(), 300), h.holderKey(), h.aliasOf(),
                    truncate(h.nationality(), 100), truncate(h.domicile(), 100), h.status(), h.sharesPrevious(),
                    h.percentPrevious(), h.sharesCurrent(), h.percentCurrent(), h.accounts().size(),
                    h.accountMoves()});
        }
        template.batchUpdate("""
                INSERT INTO ownership_holder (report_id, exchange, report_date, previous_date, ticker, issuer_name,
                                              list_number, holder_name, holder_key, alias_of, nationality, domicile,
                                              investor_status, shares_previous, percent_previous, shares_current,
                                              percent_current, accounts, account_moves)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", holders,
                new int[] {Types.BIGINT, Types.VARCHAR, Types.DATE, Types.DATE, Types.VARCHAR, Types.VARCHAR,
                        Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR,
                        Types.CHAR, Types.BIGINT, Types.NUMERIC, Types.BIGINT, Types.NUMERIC, Types.INTEGER,
                        Types.INTEGER});

        Map<Integer, Long> ids = new HashMap<>();
        jdbc.sql("SELECT list_number, holder_id FROM ownership_holder WHERE report_id = :r").param("r", reportId)
                .query(rs -> {
                    ids.put(rs.getInt(1), rs.getLong(2));
                });
        List<Object[]> accounts = new ArrayList<>(report.accounts());
        for (Holder h : report.holders()) {
            long holderId = ids.get(h.row());
            for (Account a : h.accounts()) {
                accounts.add(new Object[] {holderId, a.index(), truncate(a.participant(), 200),
                        truncate(a.accountName(), 300), truncate(a.nationality(), 100), truncate(a.domicile(), 100),
                        a.status(), a.sharesPrevious(), a.sharesCurrent()});
            }
        }
        template.batchUpdate("""
                INSERT INTO ownership_account (holder_id, account_index, participant, account_name, nationality,
                                               domicile, investor_status, shares_previous, shares_current)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", accounts,
                new int[] {Types.BIGINT, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.CHAR, Types.BIGINT, Types.BIGINT});
        return new Stored(existing.isPresent() ? Outcome.REPLACED : Outcome.STORED, reportId, exited(reportId));
    }

    /** The investors that left the list of a day (fell below 5%); null when the list of the day before is not stored. */
    public Integer exited(long reportId) {
        Boolean before = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM ownership_report pr JOIN ownership_report r
                                       ON pr.exchange = r.exchange AND pr.document_type = r.document_type
                                      AND pr.report_date = r.previous_date
                                       WHERE r.report_id = :r)""")
                .param("r", reportId).query(Boolean.class).single();
        if (!before) {
            return null;
        }
        return jdbc.sql("""
                        SELECT count(*) FROM v_ownership_movement
                        WHERE report_id = :r AND left_list AND alias_of IS NULL""")
                .param("r", reportId).query(Long.class).single().intValue();
    }

    // ------------------------------------------------------------------ reading

    private static final String MOVEMENT_COLUMNS = """
            m.holder_id, m.report_id, m.exchange, m.report_date, m.previous_date, m.ticker, m.issuer_name,
            m.list_number, m.holder_name, m.holder_key, m.alias_of,
            (SELECT string_agg(a.holder_name, '\u001f' ORDER BY a.list_number) FROM ownership_holder a
             WHERE a.report_id = m.holder_report_id AND a.ticker = m.ticker AND a.alias_of = m.list_number) AS aliases,
            m.nationality, m.domicile, m.investor_status, m.shares_previous, m.percent_previous, m.shares_current,
            m.percent_current, m.change_shares, m.percent_current - m.percent_previous AS percent_change, m.movement,
            m.accounts, m.account_moves, m.left_list
            """;

    private static final String MOVEMENT_FILTER = """
            FROM v_ownership_movement m
            WHERE m.exchange = :exchange AND m.document_type = :type
              AND (:allTickers OR m.ticker IN (:tickers))
              AND (CAST(:holder AS varchar) IS NULL OR m.holder_key LIKE '%' || :holder || '%')
              AND (:allMovements OR m.movement IN (:movements))
              AND (CAST(:status AS varchar) IS NULL OR m.investor_status = :status)
              AND (CAST(:from AS date) IS NULL OR m.report_date >= :from)
              AND (CAST(:to AS date) IS NULL OR m.report_date <= :to)
              AND (:aliases OR m.alias_of IS NULL)
            """;

    /** One page of the investors of the lists, latest day first, then ticker and the list's order. */
    public MovementPage movements(Filter filter, int limit, int offset) {
        return movements(filter, Sort.DATE, limit, offset);
    }

    /** One page of the investors of the lists in the order given. */
    @Transactional(readOnly = true)
    public MovementPage movements(Filter filter, Sort sort, int limit, int offset) {
        long total = filtered("SELECT count(*) " + MOVEMENT_FILTER, filter).query(Long.class).single();
        List<MovementRow> rows = filtered("SELECT " + MOVEMENT_COLUMNS + MOVEMENT_FILTER
                        + "ORDER BY " + sort.orderBy() + "\nLIMIT :limit OFFSET :offset", filter)
                .param("limit", limit).param("offset", offset)
                .query((rs, i) -> movement(rs)).list();
        return new MovementPage(total, limit, offset, rows);
    }

    private JdbcClient.StatementSpec filtered(String sql, Filter f) {
        List<String> movements = f.transaction() == null ? null : f.transaction().movements();
        String holder = f.holder() == null || f.holder().isBlank() ? null : OwnershipParser.holderKey(f.holder());
        return jdbc.sql(sql)
                .param("exchange", f.exchange())
                .param("type", f.documentType().name())
                .param("allTickers", f.tickers() == null || f.tickers().isEmpty())
                .param("tickers", f.tickers() == null || f.tickers().isEmpty() ? List.of("") : f.tickers())
                .param("holder", holder == null || holder.isEmpty() ? null : holder, Types.VARCHAR)
                .param("allMovements", movements == null)
                .param("movements", movements == null ? List.of("") : movements)
                .param("status", f.status(), Types.VARCHAR)
                .param("from", f.from(), Types.DATE)
                .param("to", f.to(), Types.DATE)
                .param("aliases", f.aliases());
    }

    private static MovementRow movement(ResultSet rs) throws SQLException {
        String aliases = rs.getString("aliases");
        return new MovementRow(rs.getLong("holder_id"), rs.getLong("report_id"), rs.getString("exchange"),
                rs.getObject("report_date", LocalDate.class), rs.getObject("previous_date", LocalDate.class),
                rs.getString("ticker"), rs.getString("issuer_name"), rs.getInt("list_number"),
                rs.getString("holder_name"), rs.getString("holder_key"), (Integer) rs.getObject("alias_of"),
                aliases == null ? List.of() : List.of(aliases.split("\u001f")), rs.getString("nationality"),
                rs.getString("domicile"), trim(rs.getString("investor_status")), (Long) rs.getObject("shares_previous"),
                scaled(rs.getBigDecimal("percent_previous")), (Long) rs.getObject("shares_current"),
                scaled(rs.getBigDecimal("percent_current")), (Long) rs.getObject("change_shares"),
                scaled(rs.getBigDecimal("percent_change")), rs.getString("movement"), rs.getInt("accounts"),
                rs.getInt("account_moves"), rs.getBoolean("left_list"));
    }

    /**
     * Every investor of one company in the stored lists from {@code from} to {@code to} ({@code null} = any), latest
     * day first, then the list's order, with their accounts; aliases and the investors that left a list included.
     */
    @Transactional(readOnly = true)
    public List<HolderDay> holders(String exchange, ExcelDocumentType type, String ticker, LocalDate from,
                                   LocalDate to) {
        List<MovementRow> rows = filtered("SELECT " + MOVEMENT_COLUMNS + MOVEMENT_FILTER + """
                        ORDER BY m.report_date DESC, m.left_list, m.list_number""",
                new Filter(exchange, type, List.of(ticker), null, Transaction.ALL, null, from, to, true))
                .query((rs, i) -> movement(rs)).list();
        Map<Long, List<AccountRow>> accounts = new HashMap<>();
        jdbc.sql("""
                        SELECT a.holder_id, a.account_index, a.participant, a.account_name, a.nationality, a.domicile,
                               a.investor_status, a.shares_previous, a.shares_current, a.change_shares
                        FROM ownership_account a
                        JOIN ownership_holder h ON h.holder_id = a.holder_id
                        JOIN ownership_report r ON r.report_id = h.report_id
                        WHERE h.exchange = :e AND r.document_type = :t AND h.ticker = :ticker
                          AND (CAST(:from AS date) IS NULL OR h.report_date >= :from)
                          AND (CAST(:to AS date) IS NULL OR h.report_date <= :to)
                        ORDER BY a.holder_id, a.account_index""")
                .param("e", exchange).param("t", type.name()).param("ticker", ticker)
                .param("from", from, Types.DATE).param("to", to, Types.DATE)
                .query(rs -> {
                    AccountRow a = new AccountRow(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), trim(rs.getString(7)), (Long) rs.getObject(8),
                            (Long) rs.getObject(9), (Long) rs.getObject(10));
                    accounts.computeIfAbsent(a.holderId(), k -> new ArrayList<>()).add(a);
                });
        return rows.stream().map(r -> new HolderDay(r, r.leftList() ? List.of()
                : accounts.getOrDefault(r.holderId(), List.of()))).toList();
    }

    /** The stored list of one day, with its movements; empty when that day is not stored. */
    public Optional<ReportRow> report(String exchange, ExcelDocumentType type, LocalDate date) {
        return reports(exchange, type, date, 1, 0).reports().stream().findFirst();
    }

    /** The stored lists, latest day first, with the movements of each. */
    public ReportPage reports(String exchange, ExcelDocumentType type, int limit, int offset) {
        return reports(exchange, type, null, limit, offset);
    }

    /** The stored lists (of {@code date} only when given), latest day first, with the movements of each. */
    @Transactional(readOnly = true)
    public ReportPage reports(String exchange, ExcelDocumentType type, LocalDate date, int limit, int offset) {
        long total = jdbc.sql("""
                        SELECT count(*) FROM ownership_report WHERE exchange = :e AND document_type = :t
                          AND (CAST(:date AS date) IS NULL OR report_date = :date)""")
                .param("e", exchange).param("t", type.name()).param("date", date, Types.DATE)
                .query(Long.class).single();
        List<ReportRow> rows = jdbc.sql("""
                        SELECT r.report_id, r.exchange, r.document_type, r.report_date, r.previous_date, r.title,
                               r.announcement_number, r.file_name, r.file_id, r.issuers, r.holders, r.accounts,
                               r.aliases, r.warnings, r.notes, r.updated_at,
                               count(*) FILTER (WHERE m.movement = 'BUY' AND NOT m.left_list) AS buys,
                               count(*) FILTER (WHERE m.movement = 'SELL' AND NOT m.left_list) AS sells,
                               count(*) FILTER (WHERE m.movement = 'UNCHANGED' AND NOT m.left_list) AS unchanged,
                               count(*) FILTER (WHERE m.movement = 'ENTERED' AND NOT m.left_list) AS entered,
                               count(*) FILTER (WHERE m.movement = 'EXITED') AS exited,
                               EXISTS (SELECT 1 FROM ownership_report pr
                                       WHERE pr.exchange = r.exchange AND pr.document_type = r.document_type
                                         AND pr.report_date = r.previous_date) AS before_stored
                        FROM ownership_report r
                        LEFT JOIN v_ownership_movement m ON m.report_id = r.report_id AND m.alias_of IS NULL
                        WHERE r.exchange = :e AND r.document_type = :t
                          AND (CAST(:date AS date) IS NULL OR r.report_date = :date)
                        GROUP BY r.report_id
                        ORDER BY r.report_date DESC
                        LIMIT :limit OFFSET :offset""")
                .param("e", exchange).param("t", type.name()).param("date", date, Types.DATE)
                .param("limit", limit).param("offset", offset)
                .query((rs, i) -> new ReportRow(rs.getLong("report_id"), rs.getString("exchange"),
                        ExcelDocumentType.valueOf(rs.getString("document_type")),
                        rs.getObject("report_date", LocalDate.class), rs.getObject("previous_date", LocalDate.class),
                        rs.getString("title"), rs.getString("announcement_number"), rs.getString("file_name"),
                        (Long) rs.getObject("file_id"), rs.getInt("issuers"), rs.getInt("holders"),
                        rs.getInt("accounts"), rs.getInt("aliases"), rs.getInt("buys"), rs.getInt("sells"),
                        rs.getInt("unchanged"), rs.getInt("entered"),
                        rs.getBoolean("before_stored") ? rs.getInt("exited") : null, split(rs.getString("warnings")),
                        split(rs.getString("notes")), instant(rs.getTimestamp("updated_at"))))
                .list();
        return new ReportPage(total, limit, offset, rows);
    }

    /** Every company of the stored lists, by ticker: its investors (aliases not counted) on the latest day listed. */
    public List<TickerRow> tickers(String exchange, ExcelDocumentType type) {
        return jdbc.sql("""
                        WITH t AS (
                            SELECT h.ticker, max(h.report_date) AS last_date, min(h.report_date) AS first_date,
                                   count(DISTINCT h.report_date) AS days
                            FROM ownership_holder h JOIN ownership_report r ON r.report_id = h.report_id
                            WHERE h.exchange = :e AND r.document_type = :type
                            GROUP BY h.ticker)
                        SELECT t.ticker,
                               (SELECT h.issuer_name FROM ownership_holder h
                                WHERE h.exchange = :e AND h.ticker = t.ticker AND h.report_date = t.last_date
                                ORDER BY h.list_number LIMIT 1) AS issuer_name,
                               (SELECT count(*) FROM ownership_holder h JOIN ownership_report r ON r.report_id = h.report_id
                                WHERE h.exchange = :e AND r.document_type = :type AND h.ticker = t.ticker
                                  AND h.report_date = t.last_date AND h.alias_of IS NULL
                                  AND h.shares_current IS NOT NULL) AS holders,
                               t.last_date, t.first_date, t.days,
                               EXISTS (SELECT 1 FROM company c WHERE c.exchange = :e AND c.ticker = t.ticker) AS stored
                        FROM t ORDER BY t.ticker""")
                .param("e", exchange).param("type", type.name())
                .query((rs, i) -> new TickerRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getInt(6),
                        rs.getBoolean(7)))
                .list();
    }

    /** The dates of the stored lists, latest first. */
    public List<LocalDate> dates(String exchange, ExcelDocumentType type) {
        return jdbc.sql("""
                        SELECT report_date FROM ownership_report WHERE exchange = :e AND document_type = :t
                        ORDER BY report_date DESC""")
                .param("e", exchange).param("t", type.name()).query(LocalDate.class).list();
    }

    /**
     * The largest movements of one kind on one day, at most {@code limit}: by percentage points (a buy or sell), by the
     * percentage itself (an investor that crossed 5% or fell below it), then by shares.
     */
    public List<MovementRow> largest(String exchange, ExcelDocumentType type, LocalDate date, String movement,
                                     int limit) {
        return filtered("SELECT " + MOVEMENT_COLUMNS + MOVEMENT_FILTER + """
                        ORDER BY abs(COALESCE(m.percent_current - m.percent_previous, m.percent_current,
                                              m.percent_previous, 0)) DESC,
                                 abs(COALESCE(m.change_shares, 0)) DESC, m.ticker
                        LIMIT :limit""",
                new Filter(exchange, type, List.of(), null, Transaction.of(movement).orElseThrow(), null, date, date,
                        false))
                .param("limit", limit)
                .query((rs, i) -> movement(rs)).list();
    }

    /**
     * The investors whose normalized name contains {@code query} (at most {@code limit}, most holdings first), each
     * with every company it is listed for (its latest day there).
     */
    @Transactional(readOnly = true)
    public List<Entity> entities(String exchange, ExcelDocumentType type, String query, int limit) {
        String key = OwnershipParser.holderKey(query);
        if (key.isEmpty()) {
            return List.of();
        }
        LocalDate latest = dates(exchange, type).stream().findFirst().orElse(null);
        Map<String, Entity> out = new LinkedHashMap<>();
        Map<String, List<EntityHolding>> holdings = new LinkedHashMap<>();
        jdbc.sql("""
                        WITH matched AS (
                            SELECT h.holder_key FROM ownership_holder h JOIN ownership_report r ON r.report_id = h.report_id
                            WHERE h.exchange = :e AND r.document_type = :t AND h.alias_of IS NULL
                              AND h.holder_key LIKE '%' || :key || '%'
                            GROUP BY h.holder_key
                            ORDER BY count(DISTINCT h.ticker) DESC, h.holder_key
                            LIMIT :limit),
                        latest AS (
                            SELECT DISTINCT ON (h.holder_key, h.ticker) h.*
                            FROM ownership_holder h JOIN ownership_report r ON r.report_id = h.report_id
                            WHERE h.exchange = :e AND r.document_type = :t AND h.alias_of IS NULL
                              AND h.holder_key IN (SELECT holder_key FROM matched)
                            ORDER BY h.holder_key, h.ticker, h.report_date DESC),
                        first AS (
                            SELECT DISTINCT ON (h.holder_key, h.ticker) h.holder_key, h.ticker, h.report_date,
                                   COALESCE(h.shares_previous, h.shares_current) AS shares,
                                   CASE WHEN h.shares_previous IS NULL THEN h.report_date ELSE h.previous_date END AS since
                            FROM ownership_holder h JOIN ownership_report r ON r.report_id = h.report_id
                            WHERE h.exchange = :e AND r.document_type = :t AND h.alias_of IS NULL
                              AND h.holder_key IN (SELECT holder_key FROM matched)
                            ORDER BY h.holder_key, h.ticker, h.report_date)
                        SELECT l.holder_key, l.holder_name, l.investor_status, l.nationality, l.domicile, l.ticker,
                               l.issuer_name, l.report_date, l.shares_current, l.percent_current, l.movement,
                               f.since, f.shares AS first_shares, l.accounts,
                               (SELECT count(*) FROM ownership_holder x
                                WHERE x.exchange = l.exchange AND x.ticker = l.ticker AND x.holder_key = l.holder_key
                                  AND x.alias_of IS NULL AND x.report_date > f.report_date
                                  AND x.shares_previous IS NULL) AS crossings
                        FROM latest l JOIN first f ON f.holder_key = l.holder_key AND f.ticker = l.ticker
                        ORDER BY l.holder_key, l.percent_current DESC NULLS LAST, l.ticker""")
                .param("e", exchange).param("t", type.name()).param("key", key).param("limit", limit)
                .query(rs -> {
                    String k = rs.getString("holder_key");
                    out.putIfAbsent(k, new Entity(k, rs.getString("holder_name"), trim(rs.getString("investor_status")),
                            rs.getString("nationality"), rs.getString("domicile"), List.of()));
                    LocalDate last = rs.getObject("report_date", LocalDate.class);
                    Long shares = (Long) rs.getObject("shares_current");
                    Long first = (Long) rs.getObject("first_shares");
                    boolean listed = latest != null && latest.equals(last) && shares != null;
                    // a change only when it was listed on every day since the first (no entry after it)
                    Long change = shares != null && first != null && rs.getLong("crossings") == 0 ? shares - first
                            : null;
                    holdings.computeIfAbsent(k, x -> new ArrayList<>()).add(new EntityHolding(rs.getString("ticker"),
                            rs.getString("issuer_name"), last, shares, scaled(rs.getBigDecimal("percent_current")),
                            rs.getString("movement"), listed, rs.getObject("since", LocalDate.class), first, change,
                            rs.getInt("accounts")));
                });
        return out.values().stream().map(e -> new Entity(e.holderKey(), e.name(), e.status(), e.nationality(),
                e.domicile(), List.copyOf(holdings.getOrDefault(e.holderKey(), List.of())))).toList();
    }

    /** The stored companies among {@code tickers} (the RAG documents are linked to their company). */
    public Map<String, Company> companies(String exchange, List<String> tickers) {
        Map<String, Company> out = new LinkedHashMap<>();
        if (tickers.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT company_id, exchange, ticker, company_name FROM company
                        WHERE exchange = :e AND ticker IN (:tickers) ORDER BY ticker""")
                .param("e", exchange).param("tickers", tickers)
                .query(rs -> {
                    out.put(rs.getString(3), new Company(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4)));
                });
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String lines(List<String> lines) {
        return lines == null || lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static List<String> split(String lines) {
        return lines == null || lines.isBlank() ? List.of() : List.of(lines.split("\n"));
    }

    /** "41.1000" -> 41.10 (the lists print two decimals). */
    private static BigDecimal scaled(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 2 ? value.setScale(2, java.math.RoundingMode.UNNECESSARY) : stripped;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
