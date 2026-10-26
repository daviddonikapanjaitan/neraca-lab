package com.neracalab.backend.registration;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.registration.RegistrationParser.Holder;
import com.neracalab.backend.registration.RegistrationParser.Insider;
import com.neracalab.backend.registration.RegistrationParser.Ownership;
import com.neracalab.backend.registration.RegistrationParser.Report;

/**
 * The monthly registration reports ({@code registration_report}, one per company and month, with the shares by type of
 * holder), the shares of the directors and commissioners in them ({@code registration_insider}) and of the
 * shareholders of 5% or more ({@code registration_holder}).
 */
@Repository
public class RegistrationRepository {

    /** The filing times printed in the reports are Jakarta time. */
    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    /** What storing a report did. */
    public enum Outcome {
        /** the first report of the company for the month */
        STORED,
        /** it replaced the report stored for the month (filed at the same time or earlier: the same file again, or the report a correction corrects) */
        REPLACED,
        /** the report stored for the month was filed later (e.g. its correction): kept, this one not stored */
        KEPT_LATER_FILING
    }

    /**
     * @param reportId the report stored for the month afterwards
     * @param keptFiledAt the filing time of the report kept ({@link Outcome#KEPT_LATER_FILING})
     */
    public record Stored(Outcome outcome, long reportId, Instant keptFiledAt) {
    }

    /** The transactions to list: every holding, or only the months with a purchase, a sale, either. */
    public enum Transaction {
        ALL, BUY, SELL, CHANGED;

        public static Optional<Transaction> of(String code) {
            if (code == null || code.isBlank()) {
                return Optional.of(ALL);
            }
            String c = code.trim().toUpperCase(Locale.ROOT);
            for (Transaction t : values()) {
                if (t.name().equals(c)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The filters of {@link #insiders}: {@code null} / empty = any.
     *
     * @param tickers the companies (of {@code exchange})
     * @param from    first month (inclusive)
     * @param to      last month (inclusive)
     */
    public record Filter(String exchange, List<String> tickers, Transaction transaction, InsiderRole role, YearMonth from,
                         YearMonth to) {
    }

    /**
     * The shares of a director or commissioner at the end of a month.
     *
     * @param period "2026-06": the month of the report
     * @param change shares bought (+) or sold (-) during the month; null when a share count is missing
     */
    public record InsiderRow(long insiderId, long reportId, String exchange, String ticker, String companyName,
                             String period, String holderName, String position, InsiderRole role, Long sharesPrevious,
                             BigDecimal percentPrevious, Long sharesCurrent, BigDecimal percentCurrent, Long change,
                             boolean controller, boolean controllerAffiliate, boolean correction, Instant filedAt,
                             String letterNumber, Long documentId, String fileName) {
    }

    /**
     * One page of {@link InsiderRow}s.
     *
     * @param total rows matching the filters (every page together)
     */
    public record InsiderPage(long total, int limit, int offset, List<InsiderRow> rows) {
    }

    /** A company with registration reports: how many, and the first and last month. */
    public record CompanyReports(String exchange, String ticker, String companyName, int reports, String firstPeriod,
                                 String lastPeriod) {
    }

    /**
     * A stored report.
     *
     * @param holders   shareholders of 5% or more read; null: stored before they were read (upload it again)
     * @param ownership the shares by type of holder at the end of the month (null fields: not printed, or stored before)
     */
    public record ReportRow(long reportId, String exchange, String ticker, String period, String letterNumber,
                            String subject, boolean correction, Instant filedAt, String registrar,
                            Long listedSharesPrevious, Long listedSharesCurrent, int insiders, Integer holders,
                            Ownership ownership, List<String> warnings, Long documentId, String fileName,
                            Instant updatedAt) {
    }

    /**
     * A shareholder of 5% or more at the end of a month.
     *
     * @param period "2026-06": the month of the report
     * @param change shares bought (+) or sold (-) during the month; null when a share count is missing
     */
    public record HolderRow(long holderId, long reportId, String exchange, String ticker, String period, int row,
                            String holderName, Long sharesPrevious, BigDecimal percentPrevious, Long sharesCurrent,
                            BigDecimal percentCurrent, Long change, boolean controller, boolean controllerAffiliate) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public RegistrationRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * Stores the report of the company for its month with its directors and commissioners, its shareholders of 5% or
     * more and its shares by type of holder, in one transaction. A
     * report of the month already stored is replaced, unless it was filed later than this one (a correction filed
     * after the report it corrects stays, whichever is uploaded last).
     */
    @Transactional
    public Stored store(long companyId, Report report, Long documentId, Long fileId, String fileName, UUID jobId) {
        LocalDate period = report.period().atDay(1);
        Instant filedAt = report.filedAt() == null ? null : report.filedAt().atZone(JAKARTA).toInstant();
        record Existing(long reportId, Instant filedAt) {
        }
        Optional<Existing> existing = jdbc.sql("""
                        SELECT report_id, filed_at FROM registration_report
                        WHERE company_id = :c AND period = :p FOR UPDATE""")
                .param("c", companyId).param("p", period)
                .query((rs, i) -> new Existing(rs.getLong(1), instant(rs.getTimestamp(2))))
                .optional();
        if (existing.isPresent() && existing.get().filedAt() != null && filedAt != null
                && existing.get().filedAt().isAfter(filedAt)) {
            return new Stored(Outcome.KEPT_LATER_FILING, existing.get().reportId(), existing.get().filedAt());
        }
        existing.ifPresent(e -> jdbc.sql("DELETE FROM registration_report WHERE report_id = :r")
                .param("r", e.reportId()).update());
        long reportId = jdbc.sql("""
                        INSERT INTO registration_report (company_id, period, document_id, file_id, file_name,
                                                         letter_number, subject, correction, filed_at, registrar,
                                                         listed_shares_previous, listed_shares_current, insiders,
                                                         holders, controller_shares, controller_affiliate_shares,
                                                         insider_shares, treasury_shares, warnings, job_id)
                        VALUES (:company, :period, :document, :file, :fileName, :letter, :subject, :correction, :filed,
                                :registrar, :listedPrevious, :listedCurrent, :insiders, :holders, :controllerShares,
                                :affiliateShares, :insiderShares, :treasuryShares, :warnings, :job)
                        RETURNING report_id""")
                .param("company", companyId)
                .param("period", period)
                .param("document", documentId, Types.BIGINT)
                .param("file", fileId, Types.BIGINT)
                .param("fileName", truncate(fileName, 255), Types.VARCHAR)
                .param("letter", truncate(report.letterNumber(), 200), Types.VARCHAR)
                .param("subject", truncate(report.subject(), 500), Types.VARCHAR)
                .param("correction", report.correction())
                .param("filed", filedAt == null ? null : filedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("registrar", truncate(report.registrar(), 200), Types.VARCHAR)
                .param("listedPrevious", report.listedSharesPrevious(), Types.BIGINT)
                .param("listedCurrent", report.listedSharesCurrent(), Types.BIGINT)
                .param("insiders", report.insiders().size())
                .param("holders", report.holders().size())
                .param("controllerShares", report.ownership().controller(), Types.BIGINT)
                .param("affiliateShares", report.ownership().controllerAffiliates(), Types.BIGINT)
                .param("insiderShares", report.ownership().directorsAndCommissioners(), Types.BIGINT)
                .param("treasuryShares", report.ownership().treasury(), Types.BIGINT)
                .param("warnings", report.warnings().isEmpty() ? null : String.join("\n", report.warnings()), Types.VARCHAR)
                .param("job", jobId)
                .query(Long.class).single();
        List<Object[]> rows = new ArrayList<>(report.insiders().size());
        for (Insider i : report.insiders()) {
            rows.add(new Object[] {reportId, companyId, period, i.row(), truncate(i.name(), 300),
                    truncate(i.position().isEmpty() ? "-" : i.position(), 200), i.role().name(), i.sharesPrevious(),
                    i.percentPrevious(), i.sharesCurrent(), i.percentCurrent(), i.controller(), i.controllerAffiliate()});
        }
        template.batchUpdate("""
                INSERT INTO registration_insider (report_id, company_id, period, row_index, holder_name, position, role,
                                                  shares_previous, percent_previous, shares_current, percent_current,
                                                  controller, controller_affiliate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows,
                new int[] {Types.BIGINT, Types.BIGINT, Types.DATE, Types.INTEGER, Types.VARCHAR, Types.VARCHAR,
                        Types.VARCHAR, Types.BIGINT, Types.NUMERIC, Types.BIGINT, Types.NUMERIC, Types.BOOLEAN,
                        Types.BOOLEAN});
        List<Object[]> holders = new ArrayList<>(report.holders().size());
        for (Holder h : report.holders()) {
            holders.add(new Object[] {reportId, companyId, period, h.row(), truncate(h.name(), 300), h.sharesPrevious(),
                    h.percentPrevious(), h.sharesCurrent(), h.percentCurrent(), h.controller(), h.controllerAffiliate()});
        }
        template.batchUpdate("""
                INSERT INTO registration_holder (report_id, company_id, period, row_index, holder_name, shares_previous,
                                                 percent_previous, shares_current, percent_current, controller,
                                                 controller_affiliate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", holders,
                new int[] {Types.BIGINT, Types.BIGINT, Types.DATE, Types.INTEGER, Types.VARCHAR, Types.BIGINT,
                        Types.NUMERIC, Types.BIGINT, Types.NUMERIC, Types.BOOLEAN, Types.BOOLEAN});
        return new Stored(existing.isPresent() ? Outcome.REPLACED : Outcome.STORED, reportId, null);
    }

    /** Removes the report read from the document (the same PDF stored again as another type); the number removed. */
    public int deleteOfDocument(long documentId) {
        return jdbc.sql("DELETE FROM registration_report WHERE document_id = :d").param("d", documentId).update();
    }

    // ------------------------------------------------------------------ reading

    /**
     * One page of the directors' and commissioners' holdings matching the filter, latest month first, then by ticker
     * and the order of the report.
     */
    @Transactional(readOnly = true)
    public InsiderPage insiders(Filter filter, int limit, int offset) {
        List<String> where = new ArrayList<>();
        if (filter.exchange() != null) {
            where.add("c.exchange = :exchange");
        }
        if (filter.tickers() != null && !filter.tickers().isEmpty()) {
            where.add("c.ticker IN (:tickers)");
        }
        switch (filter.transaction() == null ? Transaction.ALL : filter.transaction()) {
            case BUY -> where.add("i.change_shares > 0");
            case SELL -> where.add("i.change_shares < 0");
            case CHANGED -> where.add("i.change_shares <> 0");
            case ALL -> {
            }
        }
        if (filter.role() != null) {
            where.add("i.role = :role");
        }
        if (filter.from() != null) {
            where.add("i.period >= :from");
        }
        if (filter.to() != null) {
            where.add("i.period <= :to");
        }
        String from = """
                FROM registration_insider i
                JOIN registration_report r ON r.report_id = i.report_id
                JOIN company c ON c.company_id = i.company_id
                """ + (where.isEmpty() ? "" : "WHERE " + String.join(" AND ", where) + "\n");
        long total = params(jdbc.sql("SELECT count(*) " + from), filter).query(Long.class).single();
        List<InsiderRow> rows = params(jdbc.sql("""
                        SELECT i.insider_id, i.report_id, c.exchange, c.ticker, c.company_name, i.period, i.holder_name,
                               i.position, i.role, i.shares_previous, i.percent_previous, i.shares_current,
                               i.percent_current, i.change_shares, i.controller, i.controller_affiliate, r.correction,
                               r.filed_at, r.letter_number, r.document_id, r.file_name
                        """ + from + """
                        ORDER BY i.period DESC, c.ticker, i.row_index
                        LIMIT :limit OFFSET :offset"""), filter)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> insiderRow(rs))
                .list();
        return new InsiderPage(total, limit, offset, rows);
    }

    private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, Filter filter) {
        if (filter.exchange() != null) {
            spec = spec.param("exchange", filter.exchange());
        }
        if (filter.tickers() != null && !filter.tickers().isEmpty()) {
            spec = spec.param("tickers", filter.tickers());
        }
        if (filter.role() != null) {
            spec = spec.param("role", filter.role().name());
        }
        if (filter.from() != null) {
            spec = spec.param("from", filter.from().atDay(1));
        }
        if (filter.to() != null) {
            spec = spec.param("to", filter.to().atDay(1));
        }
        return spec;
    }

    private static InsiderRow insiderRow(ResultSet rs) throws SQLException {
        return new InsiderRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                period(rs.getDate(6).toLocalDate()), rs.getString(7), rs.getString(8), InsiderRole.valueOf(rs.getString(9)),
                rs.getObject(10, Long.class), rs.getBigDecimal(11), rs.getObject(12, Long.class),
                rs.getBigDecimal(13), rs.getObject(14, Long.class), rs.getBoolean(15), rs.getBoolean(16),
                rs.getBoolean(17), instant(rs.getTimestamp(18)), rs.getString(19), rs.getObject(20, Long.class),
                rs.getString(21));
    }

    /** The companies with registration reports, by exchange and ticker. */
    public List<CompanyReports> companies() {
        return jdbc.sql("""
                        SELECT c.exchange, c.ticker, c.company_name, count(*), min(r.period), max(r.period)
                        FROM registration_report r JOIN company c ON c.company_id = r.company_id
                        GROUP BY c.exchange, c.ticker, c.company_name
                        ORDER BY c.exchange, c.ticker""")
                .query((rs, i) -> new CompanyReports(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        period(rs.getDate(5).toLocalDate()), period(rs.getDate(6).toLocalDate())))
                .list();
    }

    /** The reports stored for the company, latest month first. */
    public List<ReportRow> reports(String exchange, String ticker) {
        return jdbc.sql("""
                        SELECT r.report_id, c.exchange, c.ticker, r.period, r.letter_number, r.subject, r.correction,
                               r.filed_at, r.registrar, r.listed_shares_previous, r.listed_shares_current, r.insiders,
                               r.warnings, r.document_id, r.file_name, r.updated_at, r.holders, r.controller_shares,
                               r.controller_affiliate_shares, r.insider_shares, r.treasury_shares
                        FROM registration_report r JOIN company c ON c.company_id = r.company_id
                        WHERE c.exchange = :e AND c.ticker = :t
                        ORDER BY r.period DESC""")
                .param("e", exchange).param("t", ticker)
                .query((rs, i) -> new ReportRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        period(rs.getDate(4).toLocalDate()), rs.getString(5), rs.getString(6), rs.getBoolean(7),
                        instant(rs.getTimestamp(8)), rs.getString(9), rs.getObject(10, Long.class),
                        rs.getObject(11, Long.class), rs.getInt(12), rs.getObject(17, Integer.class),
                        new Ownership(rs.getObject(18, Long.class), rs.getObject(19, Long.class),
                                rs.getObject(20, Long.class), rs.getObject(21, Long.class)),
                        rs.getString(13) == null ? List.of() : List.of(rs.getString(13).split("\n")),
                        rs.getObject(14, Long.class), rs.getString(15), instant(rs.getTimestamp(16))))
                .list();
    }

    /** The shareholders of 5% or more of the company, latest month first, then in the order of the report. */
    public List<HolderRow> holders(String exchange, String ticker) {
        return jdbc.sql("""
                        SELECT h.holder_id, h.report_id, c.exchange, c.ticker, h.period, h.row_index, h.holder_name,
                               h.shares_previous, h.percent_previous, h.shares_current, h.percent_current,
                               h.change_shares, h.controller, h.controller_affiliate
                        FROM registration_holder h JOIN company c ON c.company_id = h.company_id
                        WHERE c.exchange = :e AND c.ticker = :t
                        ORDER BY h.period DESC, h.row_index""")
                .param("e", exchange).param("t", ticker)
                .query((rs, i) -> new HolderRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        period(rs.getDate(5).toLocalDate()), rs.getInt(6), rs.getString(7), rs.getObject(8, Long.class),
                        rs.getBigDecimal(9), rs.getObject(10, Long.class), rs.getBigDecimal(11),
                        rs.getObject(12, Long.class), rs.getBoolean(13), rs.getBoolean(14)))
                .list();
    }

    /** "2026-06" */
    static String period(LocalDate firstDay) {
        return YearMonth.from(firstDay).toString();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
