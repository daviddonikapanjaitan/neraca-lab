package com.neracalab.backend.ownership.onepercent;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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

import com.neracalab.backend.ownership.onepercent.OnePercentMatcher.Candidate;
import com.neracalab.backend.ownership.onepercent.OnePercentMatcher.Match;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.Holder;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.Report;
import com.neracalab.backend.rag.ExcelDocumentType;
import com.neracalab.backend.rag.RagRepository.Company;

/**
 * KSEI's monthly lists of the shareholders of more than 1% ({@code ownership_one_report}, one per month) and their
 * investors ({@code ownership_one_holder}). Each list is compared with the stored list before it
 * ({@link OnePercentMatcher}) when it is stored, and so is the stored list after it, so a month uploaded late fits in
 * between. The movements, with the investors that left a list, come from {@code v_ownership_one_movement}.
 */
@Repository
public class OnePercentRepository {

    static final ExcelDocumentType TYPE = ExcelDocumentType.OWNERSHIP_1_PERCENT;

    /** What storing a list did. */
    public enum Outcome {
        /** the first list of the date */
        STORED,
        /** it replaced the list of the date stored before */
        REPLACED
    }

    /**
     * @param previousDate the stored list it is compared with; null when none is stored before it
     * @param exited       investors of that list that no investor of this one is (no longer above 1%); null when none
     *                     is stored before it
     * @param nextDate     the stored list after it, compared with it again; null when none
     */
    public record Stored(Outcome outcome, long reportId, LocalDate previousDate, Integer exited, LocalDate nextDate) {
    }

    /** The movements to list. */
    public enum Transaction {
        ALL, BUY, SELL,
        /** a buy or a sell */
        CHANGED,
        /** new above 1% */
        ENTERED,
        /** no longer above 1% (left the list) */
        EXITED,
        /** entered or exited */
        CROSSED,
        /** the same investor under another name, or the same block of shares under another name */
        RENAMED,
        /** anything but no change */
        ACTIVITY;

        public static Optional<Transaction> of(String code) {
            if (code == null || code.isBlank()) {
                return Optional.of(ALL);
            }
            String c = code.trim().toUpperCase(Locale.ROOT);
            return Arrays.stream(values()).filter(t -> t.name().equals(c)).findFirst();
        }

        /** The movements of {@code v_ownership_one_movement.movement} it lists; null = every one. */
        List<String> movements() {
            return switch (this) {
                case ALL, RENAMED -> null;
                case BUY -> List.of("BUY");
                case SELL -> List.of("SELL");
                case CHANGED -> List.of("BUY", "SELL");
                case ENTERED -> List.of("ENTERED");
                case EXITED -> List.of("EXITED");
                case CROSSED -> List.of("ENTERED", "EXITED");
                case ACTIVITY -> List.of("BUY", "SELL", "ENTERED", "EXITED");
            };
        }

        /** The matches ({@code match_kind}) it lists; null = any. */
        List<String> kinds() {
            return this == RENAMED ? List.of("RENAMED", "MOVED") : null;
        }
    }

    /**
     * The order of {@link #movements}: by month (latest first, then ticker and the list's order), or by the change in
     * shares or in percentage points, largest first (DESC) or smallest first (ASC). Rows without a change (new above
     * 1%, no longer above it, or no list before) come last either way; equal changes by month as DATE.
     */
    public enum Sort {
        DATE, CHANGE_DESC, CHANGE_ASC, POINTS_DESC, POINTS_ASC;

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
     * @param holder part of an investor's name (normalized as {@link OnePercentParser#holderKey})
     * @param status L (local) or A (foreign)
     * @param group  INDIVIDUAL, CORPORATE, FUND, FINANCIAL, GOVERNMENT or OTHER
     * @param from   first date (inclusive)
     * @param to     last date (inclusive)
     */
    public record Filter(String exchange, List<String> tickers, String holder, Transaction transaction, String status,
                         String group, LocalDate from, LocalDate to) {
    }

    /**
     * An investor of a company in the list of a month.
     *
     * @param holderId      its row (of the month before when it left the list)
     * @param previousDate  the list it is compared with; null when none is stored before it
     * @param otherNames    the other names of its rows that month
     * @param sourceRows    rows of the sheet counted together
     * @param investorType  Individual, Corporate, Mutual Funds, ...; null when not printed
     * @param typeGroup     INDIVIDUAL, CORPORATE, FUND, FINANCIAL, GOVERNMENT or OTHER
     * @param previousName  its name in the list before (another one when RENAMED or MOVED)
     * @param matchKind     SAME, RENAMED (its name spelled otherwise) or MOVED (the same block of shares under another
     *                      name); null when not in the list before
     * @param previousHolderId its row in the list before; null when none
     * @param change       shares bought (+) or sold (-); null when either month has none
     * @param percentChange percentage points; null when either is missing
     * @param movement      BUY, SELL, UNCHANGED, ENTERED (new above 1%), EXITED (no longer above 1%); null when no list
     *                      before it is stored
     * @param leftList      not in the month's list: it was in the list before (EXITED)
     */
    public record MovementRow(long holderId, long reportId, String exchange, LocalDate reportDate,
                              LocalDate previousDate, String ticker, String issuerName, int listNumber,
                              String holderName, String holderKey, List<String> otherNames, int sourceRows,
                              String investorType, String typeGroup, String status, String nationality,
                              String domicile, long sharesScripless, long sharesScrip, String previousName,
                              String matchKind, Long previousHolderId, Long sharesPrevious, BigDecimal percentPrevious, Long sharesCurrent,
                              BigDecimal percentCurrent, Long change, BigDecimal percentChange, String movement,
                              boolean leftList) {
    }

    public record MovementPage(long total, int limit, int offset, List<MovementRow> rows) {
    }

    /**
     * A stored list.
     *
     * @param previousDate the stored list it is compared with; null when none (no movements known)
     * @param exited       investors of the list before no longer above 1%; null when none is stored before it
     * @param renamed      investors matched with one of the list before under a name spelled otherwise
     * @param moved        blocks of shares listed under another name than the month before
     */
    public record ReportRow(long reportId, String exchange, ExcelDocumentType documentType, LocalDate reportDate,
                            LocalDate previousDate, String announcementNumber, String fileName, Long fileId,
                            int issuers, int holders, int sourceRows, int buys, int sells, int unchanged,
                            int entered, Integer exited, int renamed, int moved, List<String> warnings,
                            List<String> notes, Instant updatedAt) {
    }

    public record ReportPage(long total, int limit, int offset, List<ReportRow> reports) {
    }

    /**
     * A company of the stored lists: its investors on the latest month it is listed.
     *
     * @param stored the company is stored in {@code company} (it has a company page and an ownership document)
     */
    public record TickerRow(String ticker, String issuerName, int holders, LocalDate lastDate, LocalDate firstDate,
                            int months, boolean stored) {
    }

    /** An investor (by its normalized name) and what it holds. */
    public record Entity(String holderKey, String name, String investorType, String typeGroup, String status,
                         String nationality, String domicile, List<EntityHolding> holdings) {
    }

    /**
     * @param lastDate    the latest list naming the investor for the company
     * @param listed      it is in the latest stored list
     * @param firstDate   the first date of its stored holding (the list before its first stored one, or the month it
     *                    was new above 1%)
     * @param firstShares its shares then
     * @param change      shares since firstDate; null when it was new above 1% again later (it left in between)
     */
    public record EntityHolding(String ticker, String issuerName, LocalDate lastDate, Long shares, BigDecimal percent,
                                String lastMovement, boolean listed, LocalDate firstDate, Long firstShares,
                                Long change) {
    }

    /** An investor listed for the most companies in one list. */
    public record TopInvestor(String holderKey, String name, String investorType, String status, int companies,
                              BigDecimal largestPercent, String largestTicker) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public OnePercentRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    // ------------------------------------------------------------------ storing

    private static final Pattern ANNOUNCEMENT = Pattern.compile("(?i)peng-(?:\\d{4}-)?\\d{2}-(\\d+)-satu");

    /** "peng-2026-09-00024-satu-persen.xlsx" and "peng-02-00011-satu-persen.xlsx" -> "00024", "00011"; else null. */
    static String announcementNumber(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = ANNOUNCEMENT.matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Stores the list of a month; a list of the same date stored before is replaced. The list is compared with the
     * stored list before it, and the stored list after it with this one, in one transaction.
     */
    @Transactional
    public Stored store(String exchange, Report report, Long fileId, String fileName, UUID jobId) {
        Optional<Long> existing = jdbc.sql("""
                        SELECT report_id FROM ownership_one_report
                        WHERE exchange = :e AND document_type = :t AND report_date = :d""")
                .param("e", exchange).param("t", TYPE.name()).param("d", report.reportDate())
                .query(Long.class).optional();
        existing.ifPresent(id -> jdbc.sql("DELETE FROM ownership_one_report WHERE report_id = :id").param("id", id)
                .update());
        long reportId = jdbc.sql("""
                        INSERT INTO ownership_one_report (exchange, document_type, report_date, announcement_number,
                                                          file_id, file_name, issuers, holders, source_rows, warnings,
                                                          notes, job_id)
                        VALUES (:exchange, :type, :date, :announcement, :fileId, :fileName, :issuers, :holders, :rows,
                                :warnings, :notes, :job)
                        RETURNING report_id""")
                .param("exchange", exchange)
                .param("type", TYPE.name())
                .param("date", report.reportDate())
                .param("announcement", announcementNumber(fileName), Types.VARCHAR)
                .param("fileId", fileId, Types.BIGINT)
                .param("fileName", truncate(fileName, 255), Types.VARCHAR)
                .param("issuers", report.tickers().size())
                .param("holders", report.holders().size())
                .param("rows", report.sourceRows())
                .param("warnings", lines(report.warnings()), Types.VARCHAR)
                .param("notes", lines(report.notes()), Types.VARCHAR)
                .param("job", jobId)
                .query(Long.class).single();

        List<Object[]> holders = new ArrayList<>(report.holders().size());
        for (Holder h : report.holders()) {
            holders.add(new Object[] {reportId, exchange, report.reportDate(), h.ticker(), truncate(h.issuerName(), 300),
                    h.row(), truncate(h.holderName(), 300), h.holderKey(), lines(h.otherNames()), h.sourceRows(),
                    truncate(h.investorType(), 100), h.typeGroup(), h.status(), truncate(h.nationality(), 100),
                    truncate(h.domicile(), 100), h.scripless(), h.scrip(), h.shares(), h.percent()});
        }
        template.batchUpdate("""
                INSERT INTO ownership_one_holder (report_id, exchange, report_date, ticker, issuer_name, list_number,
                                                  holder_name, holder_key, other_names, source_rows, investor_type,
                                                  type_group, investor_status, nationality, domicile,
                                                  shares_scripless, shares_scrip, shares, percent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", holders,
                new int[] {Types.BIGINT, Types.VARCHAR, Types.DATE, Types.VARCHAR, Types.VARCHAR, Types.INTEGER,
                        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR,
                        Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BIGINT, Types.BIGINT,
                        Types.NUMERIC});

        LocalDate previous = relink(exchange, reportId, report.reportDate());
        Optional<Long> next = jdbc.sql("""
                        SELECT report_id FROM ownership_one_report
                        WHERE exchange = :e AND document_type = :t AND report_date > :d
                        ORDER BY report_date LIMIT 1""")
                .param("e", exchange).param("t", TYPE.name()).param("d", report.reportDate())
                .query(Long.class).optional();
        LocalDate nextDate = null;
        if (next.isPresent()) {
            nextDate = date(next.get());
            relink(exchange, next.get(), nextDate);
        }
        return new Stored(existing.isPresent() ? Outcome.REPLACED : Outcome.STORED, reportId, previous,
                exited(reportId), nextDate);
    }

    /**
     * Removes the list of a date, and compares the stored list after it with the one before it again. Returns whether
     * a list was stored.
     */
    @Transactional
    public boolean delete(String exchange, LocalDate date) {
        int deleted = jdbc.sql("""
                        DELETE FROM ownership_one_report
                        WHERE exchange = :e AND document_type = :t AND report_date = :d""")
                .param("e", exchange).param("t", TYPE.name()).param("d", date).update();
        if (deleted == 0) {
            return false;
        }
        jdbc.sql("""
                        SELECT report_id, report_date FROM ownership_one_report
                        WHERE exchange = :e AND document_type = :t AND report_date > :d
                        ORDER BY report_date LIMIT 1""")
                .param("e", exchange).param("t", TYPE.name()).param("d", date)
                .query((rs, i) -> new Object[] {rs.getLong(1), rs.getObject(2, LocalDate.class)}).optional()
                .ifPresent(next -> relink(exchange, (Long) next[0], (LocalDate) next[1]));
        return true;
    }

    private LocalDate date(long reportId) {
        return jdbc.sql("SELECT report_date FROM ownership_one_report WHERE report_id = :r").param("r", reportId)
                .query(LocalDate.class).single();
    }

    /** A holder as the matcher reads it. */
    private record Row(long holderId, Candidate candidate, BigDecimal percent) {
    }

    private List<Row> rows(long reportId) {
        return jdbc.sql("""
                        SELECT holder_id, ticker, holder_key, holder_name, shares, percent FROM ownership_one_holder
                        WHERE report_id = :r ORDER BY list_number""")
                .param("r", reportId)
                .query((rs, i) -> new Row(rs.getLong(1), new Candidate(rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5)), rs.getBigDecimal(6)))
                .list();
    }

    /**
     * Compares the list {@code reportId} with the latest stored list before it: each investor's match there, its
     * shares then. Returns the date of that list (null when none: no comparison).
     */
    private LocalDate relink(String exchange, long reportId, LocalDate date) {
        Optional<Object[]> before = jdbc.sql("""
                        SELECT report_id, report_date FROM ownership_one_report
                        WHERE exchange = :e AND document_type = :t AND report_date < :d
                        ORDER BY report_date DESC LIMIT 1""")
                .param("e", exchange).param("t", TYPE.name()).param("d", date)
                .query((rs, i) -> new Object[] {rs.getLong(1), rs.getObject(2, LocalDate.class)}).optional();
        LocalDate previousDate = before.map(b -> (LocalDate) b[1]).orElse(null);
        jdbc.sql("UPDATE ownership_one_report SET previous_date = :p WHERE report_id = :r")
                .param("p", previousDate, Types.DATE).param("r", reportId).update();
        // cleared first: the check (match kind and shares before set together) holds row by row
        jdbc.sql("""
                        UPDATE ownership_one_holder SET previous_date = :p, previous_holder_id = NULL,
                               previous_name = NULL, match_kind = NULL, shares_previous = NULL, percent_previous = NULL
                        WHERE report_id = :r""")
                .param("p", previousDate, Types.DATE).param("r", reportId).update();
        if (before.isEmpty()) {
            return null;
        }
        List<Row> previous = rows((Long) before.get()[0]);
        List<Row> current = rows(reportId);
        List<Match> matches = OnePercentMatcher.match(previous.stream().map(Row::candidate).toList(),
                current.stream().map(Row::candidate).toList());
        List<Object[]> updates = new ArrayList<>(matches.size());
        for (Match m : matches) {
            Row p = previous.get(m.previous());
            Row c = current.get(m.current());
            updates.add(new Object[] {p.holderId(), truncate(p.candidate().name(), 300), m.kind().name(),
                    p.candidate().shares(), p.percent(), c.holderId()});
        }
        template.batchUpdate("""
                UPDATE ownership_one_holder SET previous_holder_id = ?, previous_name = ?, match_kind = ?,
                       shares_previous = ?, percent_previous = ?
                WHERE holder_id = ?""", updates,
                new int[] {Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.NUMERIC, Types.BIGINT});
        // the investors of the list before that one of this list is (the others are no longer above 1%)
        jdbc.sql("UPDATE ownership_one_holder SET continued = FALSE WHERE report_id = :p")
                .param("p", (Long) before.get()[0]).update();
        template.batchUpdate("UPDATE ownership_one_holder SET continued = TRUE WHERE holder_id = ?",
                matches.stream().map(m -> new Object[] {previous.get(m.previous()).holderId()}).toList(),
                new int[] {Types.BIGINT});
        return previousDate;
    }

    /** The investors of the list before no longer above 1%; null when no list before it is stored. */
    public Integer exited(long reportId) {
        Boolean before = jdbc.sql("SELECT previous_date IS NOT NULL FROM ownership_one_report WHERE report_id = :r")
                .param("r", reportId).query(Boolean.class).single();
        if (!before) {
            return null;
        }
        return jdbc.sql("SELECT count(*) FROM v_ownership_one_movement WHERE report_id = :r AND left_list")
                .param("r", reportId).query(Long.class).single().intValue();
    }

    // ------------------------------------------------------------------ reading

    private static final String MOVEMENT_COLUMNS = """
            m.holder_id, m.report_id, m.exchange, m.report_date, m.previous_date, m.ticker, m.issuer_name,
            m.list_number, m.holder_name, m.holder_key, m.other_names, m.source_rows, m.investor_type, m.type_group,
            m.investor_status, m.nationality, m.domicile, m.shares_scripless, m.shares_scrip, m.previous_name,
            m.match_kind, m.previous_holder_id, m.shares_previous, m.percent_previous, m.shares_current,
            m.percent_current, m.change_shares,
            m.percent_current - m.percent_previous AS percent_change, m.movement, m.left_list
            """;

    private static final String MOVEMENT_FILTER = """
            FROM v_ownership_one_movement m
            WHERE m.exchange = :exchange AND m.document_type = :type
              AND (:allTickers OR m.ticker IN (:tickers))
              AND (CAST(:holder AS varchar) IS NULL OR m.holder_key LIKE '%' || :holder || '%')
              AND (:allMovements OR m.movement IN (:movements))
              AND (:allKinds OR m.match_kind IN (:kinds))
              AND (CAST(:status AS varchar) IS NULL OR m.investor_status = :status)
              AND (CAST(:group AS varchar) IS NULL OR m.type_group = :group)
              AND (CAST(:from AS date) IS NULL OR m.report_date >= :from)
              AND (CAST(:to AS date) IS NULL OR m.report_date <= :to)
            """;

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
        Transaction t = f.transaction() == null ? Transaction.ALL : f.transaction();
        List<String> movements = t.movements();
        List<String> kinds = t.kinds();
        String holder = f.holder() == null || f.holder().isBlank() ? null : OnePercentParser.holderKey(f.holder());
        return jdbc.sql(sql)
                .param("exchange", f.exchange())
                .param("type", TYPE.name())
                .param("allTickers", f.tickers() == null || f.tickers().isEmpty())
                .param("tickers", f.tickers() == null || f.tickers().isEmpty() ? List.of("") : f.tickers())
                .param("holder", holder == null || holder.isEmpty() ? null : holder, Types.VARCHAR)
                .param("allMovements", movements == null)
                .param("movements", movements == null ? List.of("") : movements)
                .param("allKinds", kinds == null)
                .param("kinds", kinds == null ? List.of("") : kinds)
                .param("status", f.status(), Types.VARCHAR)
                .param("group", f.group(), Types.VARCHAR)
                .param("from", f.from(), Types.DATE)
                .param("to", f.to(), Types.DATE);
    }

    private static MovementRow movement(ResultSet rs) throws SQLException {
        return new MovementRow(rs.getLong("holder_id"), rs.getLong("report_id"), rs.getString("exchange"),
                rs.getObject("report_date", LocalDate.class), rs.getObject("previous_date", LocalDate.class),
                rs.getString("ticker"), rs.getString("issuer_name"), rs.getInt("list_number"),
                rs.getString("holder_name"), rs.getString("holder_key"), split(rs.getString("other_names")),
                rs.getInt("source_rows"), rs.getString("investor_type"), rs.getString("type_group"),
                trim(rs.getString("investor_status")), rs.getString("nationality"), rs.getString("domicile"),
                rs.getLong("shares_scripless"), rs.getLong("shares_scrip"), rs.getString("previous_name"),
                rs.getString("match_kind"), (Long) rs.getObject("previous_holder_id"),
                (Long) rs.getObject("shares_previous"),
                scaled(rs.getBigDecimal("percent_previous")), (Long) rs.getObject("shares_current"),
                scaled(rs.getBigDecimal("percent_current")), (Long) rs.getObject("change_shares"),
                scaled(rs.getBigDecimal("percent_change")), rs.getString("movement"), rs.getBoolean("left_list"));
    }

    /**
     * Every investor of one company in the stored lists from {@code from} to {@code to} ({@code null} = any), latest
     * month first, then the list's order; the investors that left a list included.
     */
    public List<MovementRow> holders(String exchange, String ticker, LocalDate from, LocalDate to) {
        return filtered("SELECT " + MOVEMENT_COLUMNS + MOVEMENT_FILTER + """
                        ORDER BY m.report_date DESC, m.left_list, m.list_number""",
                new Filter(exchange, List.of(ticker), null, Transaction.ALL, null, null, from, to))
                .query((rs, i) -> movement(rs)).list();
    }

    /** The stored list of one date, with its movements; empty when that date is not stored. */
    public Optional<ReportRow> report(String exchange, LocalDate date) {
        return reports(exchange, date, 1, 0).reports().stream().findFirst();
    }

    /** The stored lists, latest first, with the movements of each. */
    public ReportPage reports(String exchange, int limit, int offset) {
        return reports(exchange, null, limit, offset);
    }

    /** The stored lists (of {@code date} only when given), latest first, with the movements of each. */
    @Transactional(readOnly = true)
    public ReportPage reports(String exchange, LocalDate date, int limit, int offset) {
        long total = jdbc.sql("""
                        SELECT count(*) FROM ownership_one_report WHERE exchange = :e AND document_type = :t
                          AND (CAST(:date AS date) IS NULL OR report_date = :date)""")
                .param("e", exchange).param("t", TYPE.name()).param("date", date, Types.DATE)
                .query(Long.class).single();
        List<ReportRow> rows = jdbc.sql("""
                        SELECT r.report_id, r.exchange, r.document_type, r.report_date, r.previous_date,
                               r.announcement_number, r.file_name, r.file_id, r.issuers, r.holders, r.source_rows,
                               r.warnings, r.notes, r.updated_at,
                               count(*) FILTER (WHERE m.movement = 'BUY' AND NOT m.left_list) AS buys,
                               count(*) FILTER (WHERE m.movement = 'SELL' AND NOT m.left_list) AS sells,
                               count(*) FILTER (WHERE m.movement = 'UNCHANGED' AND NOT m.left_list) AS unchanged,
                               count(*) FILTER (WHERE m.movement = 'ENTERED' AND NOT m.left_list) AS entered,
                               count(*) FILTER (WHERE m.movement = 'EXITED') AS exited,
                               count(*) FILTER (WHERE m.match_kind = 'RENAMED') AS renamed,
                               count(*) FILTER (WHERE m.match_kind = 'MOVED') AS moved
                        FROM ownership_one_report r
                        LEFT JOIN v_ownership_one_movement m ON m.report_id = r.report_id
                        WHERE r.exchange = :e AND r.document_type = :t
                          AND (CAST(:date AS date) IS NULL OR r.report_date = :date)
                        GROUP BY r.report_id
                        ORDER BY r.report_date DESC
                        LIMIT :limit OFFSET :offset""")
                .param("e", exchange).param("t", TYPE.name()).param("date", date, Types.DATE)
                .param("limit", limit).param("offset", offset)
                .query((rs, i) -> {
                    LocalDate previous = rs.getObject("previous_date", LocalDate.class);
                    return new ReportRow(rs.getLong("report_id"), rs.getString("exchange"),
                            ExcelDocumentType.valueOf(rs.getString("document_type")),
                            rs.getObject("report_date", LocalDate.class), previous,
                            rs.getString("announcement_number"), rs.getString("file_name"),
                            (Long) rs.getObject("file_id"), rs.getInt("issuers"), rs.getInt("holders"),
                            rs.getInt("source_rows"), rs.getInt("buys"), rs.getInt("sells"), rs.getInt("unchanged"),
                            rs.getInt("entered"), previous == null ? null : rs.getInt("exited"), rs.getInt("renamed"),
                            rs.getInt("moved"), split(rs.getString("warnings")), split(rs.getString("notes")),
                            instant(rs.getTimestamp("updated_at")));
                })
                .list();
        return new ReportPage(total, limit, offset, rows);
    }

    /** Every company of the stored lists, by ticker: its investors on the latest month listed. */
    public List<TickerRow> tickers(String exchange) {
        return jdbc.sql("""
                        WITH t AS (
                            SELECT h.ticker, max(h.report_date) AS last_date, min(h.report_date) AS first_date,
                                   count(DISTINCT h.report_date) AS months
                            FROM ownership_one_holder h
                            WHERE h.exchange = :e
                            GROUP BY h.ticker)
                        SELECT t.ticker,
                               (SELECT h.issuer_name FROM ownership_one_holder h
                                WHERE h.exchange = :e AND h.ticker = t.ticker AND h.report_date = t.last_date
                                ORDER BY h.list_number LIMIT 1) AS issuer_name,
                               (SELECT count(*) FROM ownership_one_holder h
                                WHERE h.exchange = :e AND h.ticker = t.ticker AND h.report_date = t.last_date) AS holders,
                               t.last_date, t.first_date, t.months,
                               EXISTS (SELECT 1 FROM company c WHERE c.exchange = :e AND c.ticker = t.ticker) AS stored
                        FROM t ORDER BY t.ticker""")
                .param("e", exchange)
                .query((rs, i) -> new TickerRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getInt(6),
                        rs.getBoolean(7)))
                .list();
    }

    /** The dates of the stored lists, latest first. */
    public List<LocalDate> dates(String exchange) {
        return jdbc.sql("""
                        SELECT report_date FROM ownership_one_report WHERE exchange = :e AND document_type = :t
                        ORDER BY report_date DESC""")
                .param("e", exchange).param("t", TYPE.name()).query(LocalDate.class).list();
    }

    /**
     * The largest movements of one kind in one list, at most {@code limit}: by percentage points (a buy or sell), by
     * the percentage itself (new above 1%, no longer above it, renamed or moved), then by shares.
     */
    public List<MovementRow> largest(String exchange, LocalDate date, Transaction transaction, int limit) {
        return filtered("SELECT " + MOVEMENT_COLUMNS + MOVEMENT_FILTER + """
                        ORDER BY abs(COALESCE(m.percent_current - m.percent_previous, 0)) DESC,
                                 abs(COALESCE(m.change_shares, 0)) DESC,
                                 COALESCE(m.percent_current, m.percent_previous, 0) DESC, m.ticker
                        LIMIT :limit""",
                new Filter(exchange, List.of(), null, transaction, null, null, date, date))
                .param("limit", limit)
                .query((rs, i) -> movement(rs)).list();
    }

    /** The investors listed for the most companies in the list of {@code date}, at most {@code limit}. */
    public List<TopInvestor> topInvestors(String exchange, LocalDate date, int limit) {
        return jdbc.sql("""
                        SELECT h.holder_key,
                               (array_agg(h.holder_name ORDER BY h.percent DESC NULLS LAST))[1] AS name,
                               (array_agg(h.investor_type ORDER BY h.percent DESC NULLS LAST))[1] AS investor_type,
                               (array_agg(h.investor_status ORDER BY h.percent DESC NULLS LAST))[1] AS status,
                               count(*) AS companies, max(h.percent) AS largest,
                               (array_agg(h.ticker ORDER BY h.percent DESC NULLS LAST))[1] AS largest_ticker
                        FROM ownership_one_holder h JOIN ownership_one_report r ON r.report_id = h.report_id
                        WHERE h.exchange = :e AND r.document_type = :t AND h.report_date = :d
                        GROUP BY h.holder_key
                        HAVING count(*) > 1
                        ORDER BY count(*) DESC, max(h.percent) DESC NULLS LAST, h.holder_key
                        LIMIT :limit""")
                .param("e", exchange).param("t", TYPE.name()).param("d", date).param("limit", limit)
                .query((rs, i) -> new TopInvestor(rs.getString("holder_key"), rs.getString("name"),
                        rs.getString("investor_type"), trim(rs.getString("status")), rs.getInt("companies"),
                        scaled(rs.getBigDecimal("largest")), rs.getString("largest_ticker")))
                .list();
    }

    /**
     * The investors whose normalized name contains {@code query} (at most {@code limit}, most holdings first), each
     * with every company it is listed for (its latest month there).
     */
    @Transactional(readOnly = true)
    public List<Entity> entities(String exchange, String query, int limit) {
        String key = OnePercentParser.holderKey(query);
        if (key.isEmpty()) {
            return List.of();
        }
        LocalDate latest = dates(exchange).stream().findFirst().orElse(null);
        Map<String, Entity> out = new LinkedHashMap<>();
        Map<String, List<EntityHolding>> holdings = new LinkedHashMap<>();
        jdbc.sql("""
                        WITH matched AS (
                            SELECT h.holder_key FROM ownership_one_holder h
                            WHERE h.exchange = :e AND h.holder_key LIKE '%' || :key || '%'
                            GROUP BY h.holder_key
                            ORDER BY count(DISTINCT h.ticker) DESC, h.holder_key
                            LIMIT :limit),
                        latest AS (
                            SELECT DISTINCT ON (h.holder_key, h.ticker) h.*
                            FROM ownership_one_holder h
                            WHERE h.exchange = :e AND h.holder_key IN (SELECT holder_key FROM matched)
                            ORDER BY h.holder_key, h.ticker, h.report_date DESC),
                        first AS (
                            SELECT DISTINCT ON (h.holder_key, h.ticker) h.holder_key, h.ticker, h.report_date,
                                   COALESCE(h.shares_previous, h.shares) AS shares,
                                   CASE WHEN h.shares_previous IS NULL THEN h.report_date ELSE h.previous_date END AS since
                            FROM ownership_one_holder h
                            WHERE h.exchange = :e AND h.holder_key IN (SELECT holder_key FROM matched)
                            ORDER BY h.holder_key, h.ticker, h.report_date)
                        SELECT l.holder_key, l.holder_name, l.investor_type, l.type_group, l.investor_status,
                               l.nationality, l.domicile, l.ticker, l.issuer_name, l.report_date, l.shares, l.percent,
                               l.movement, f.since, f.shares AS first_shares,
                               (SELECT count(*) FROM ownership_one_holder x
                                WHERE x.exchange = l.exchange AND x.ticker = l.ticker AND x.holder_key = l.holder_key
                                  AND x.report_date > f.report_date AND x.movement = 'ENTERED') AS reentries
                        FROM latest l JOIN first f ON f.holder_key = l.holder_key AND f.ticker = l.ticker
                        ORDER BY l.holder_key, l.percent DESC NULLS LAST, l.ticker""")
                .param("e", exchange).param("key", key).param("limit", limit)
                .query(rs -> {
                    String k = rs.getString("holder_key");
                    out.putIfAbsent(k, new Entity(k, rs.getString("holder_name"), rs.getString("investor_type"),
                            rs.getString("type_group"), trim(rs.getString("investor_status")),
                            rs.getString("nationality"), rs.getString("domicile"), List.of()));
                    LocalDate last = rs.getObject("report_date", LocalDate.class);
                    long shares = rs.getLong("shares");
                    Long first = (Long) rs.getObject("first_shares");
                    boolean listed = latest != null && latest.equals(last);
                    Long change = first != null && rs.getLong("reentries") == 0 ? shares - first : null;
                    holdings.computeIfAbsent(k, x -> new ArrayList<>()).add(new EntityHolding(rs.getString("ticker"),
                            rs.getString("issuer_name"), last, shares, scaled(rs.getBigDecimal("percent")),
                            rs.getString("movement"), listed, rs.getObject("since", LocalDate.class), first, change));
                });
        return out.values().stream().map(e -> new Entity(e.holderKey(), e.name(), e.investorType(), e.typeGroup(),
                e.status(), e.nationality(), e.domicile(), List.copyOf(holdings.getOrDefault(e.holderKey(), List.of()))))
                .toList();
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

    /** Stored lists naming the company, and the latest of them (for the AI analysis). */
    public record Coverage(int months, LocalDate latestDate) {
    }

    public Coverage coverage(String exchange, String ticker) {
        return jdbc.sql("""
                        SELECT count(DISTINCT report_date), max(report_date) FROM ownership_one_holder
                        WHERE exchange = :e AND ticker = :t""")
                .param("e", exchange).param("t", ticker)
                .query((rs, i) -> new Coverage(rs.getInt(1), rs.getObject(2, LocalDate.class))).single();
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
