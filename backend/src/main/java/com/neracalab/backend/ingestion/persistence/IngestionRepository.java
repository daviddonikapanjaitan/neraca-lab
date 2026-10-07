package com.neracalab.backend.ingestion.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.PeriodRef;
import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.price.ValuationRepository;

/**
 * Writes ingested filings with the same upsert semantics as the SQL data scripts.
 * <p>
 * Rules: data of the filing's current period always replaces stored data (latest filing wins);
 * comparative columns only fill gaps and never overwrite an existing row.
 */
@Repository
public class IngestionRepository {

    /** IDX XBRL filings only, so every ingested company is listed on IDX. */
    public static final String EXCHANGE = Exchange.IDX.code();
    private static final String DERIVED_SCRIPT = "db/V1.0.6__data_metrics_valuation.sql";
    /** Share counts from outside the filings; they need the company, which an upload may just have created. */
    private static final List<String> SHARE_SCRIPTS = List.of(
            "db/V1.0.12__data_SMDR_shares.sql", "db/V1.0.13__data_BNGA_shares.sql");

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final ValuationRepository valuations;

    public IngestionRepository(JdbcClient jdbc, DataSource dataSource, ValuationRepository valuations) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.valuations = valuations;
    }

    public record CompanyRow(long companyId, String ticker, String companyName, String legalName, String sector,
                             String industry, String currency, LocalDate fiscalYearEnd) {
    }

    /** Outcome of writing one row. */
    /**
     * INSERTED / UPDATED: written by the period's own filing; FILLED_GAPS: a comparative filled fields the stored
     * row had empty (e.g. revenue a filing did not tag); KEPT_EXISTING: a comparative changed nothing.
     */
    public enum WriteOutcome { INSERTED, UPDATED, FILLED_GAPS, KEPT_EXISTING }

    public record WriteResult(String table, String period, WriteOutcome outcome, List<String> differences) {
    }

    // ------------------------------------------------------------------ company

    private static final String COMPANY_COLUMNS =
            "company_id, ticker, company_name, legal_name, sector, industry, currency, fiscal_year_end";

    private static final RowMapper<CompanyRow> COMPANY_ROW = (rs, i) -> new CompanyRow(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
            rs.getObject(8, LocalDate.class));

    /** The company is identified by (ticker, exchange), the key of uq_company_ticker_exchange. */
    public Optional<CompanyRow> findCompany(String ticker) {
        return jdbc.sql("SELECT " + COMPANY_COLUMNS + " FROM company WHERE ticker = :ticker AND exchange = :exchange")
                .param("ticker", Tickers.normalize(ticker)).param("exchange", EXCHANGE)
                .query(COMPANY_ROW)
                .optional();
    }

    /**
     * Inserts the company, or updates it when (ticker, exchange) already exists: a single atomic
     * statement on uq_company_ticker_exchange, so concurrent uploads of the same company can never
     * create a second row. Returns the stored row.
     */
    @Transactional
    public CompanyRow upsertCompany(FilingInfo info, String companyName) {
        LocalDate fiscalYearEnd = info.current().isFullYear() ? info.current().end() : info.priorYearEnd().end();
        return jdbc.sql("""
                        INSERT INTO company (ticker, exchange, company_name, legal_name, industry, sector,
                                             country, currency, fiscal_year_end, active)
                        VALUES (:ticker, :exchange, :name, :legal, :industry, :sector, :country, :currency, :fye, TRUE)
                        ON CONFLICT ON CONSTRAINT uq_company_ticker_exchange DO UPDATE SET
                            company_name    = EXCLUDED.company_name,
                            legal_name      = EXCLUDED.legal_name,
                            industry        = COALESCE(EXCLUDED.industry, company.industry),
                            sector          = COALESCE(EXCLUDED.sector, company.sector),
                            currency        = EXCLUDED.currency,
                            fiscal_year_end = GREATEST(company.fiscal_year_end, EXCLUDED.fiscal_year_end),
                            updated_at      = now()
                        RETURNING\s""" + COMPANY_COLUMNS)
                .param("ticker", Tickers.normalize(info.ticker())).param("exchange", EXCHANGE)
                .param("name", companyName).param("legal", info.legalName()).param("industry", info.industry())
                .param("sector", info.sector()).param("country", Exchange.IDX.country())
                .param("currency", info.currency()).param("fye", fiscalYearEnd)
                .query(COMPANY_ROW)
                .single();
    }

    /** Keeps company.fiscal_year_end at the most recent fiscal year end seen in any filing. */
    @Transactional
    public void advanceFiscalYearEnd(long companyId, LocalDate fiscalYearEnd) {
        jdbc.sql("""
                        UPDATE company SET fiscal_year_end = :fye, updated_at = now()
                        WHERE company_id = :c AND (fiscal_year_end IS NULL OR fiscal_year_end < :fye)""")
                .param("c", companyId).param("fye", fiscalYearEnd).update();
    }

    // ------------------------------------------------------------------ periods and statements

    /**
     * Returns the period id, creating the period when missing. For the filing's current period the
     * source filing, audit flag and dates are refreshed. A comparative keeps an existing period,
     * except that a period of unknown audit status (created from an interim filing's prior year end)
     * takes the provenance of a filing that states it, e.g. the audited annual report.
     */
    @Transactional
    public long upsertPeriod(long companyId, PeriodRef period, String sourceFiling, Boolean audited, boolean currentPeriod) {
        String conflict = currentPeriod ? """
                ON CONFLICT ON CONSTRAINT uq_reporting_period DO UPDATE SET
                    period_start  = EXCLUDED.period_start,
                    period_end    = EXCLUDED.period_end,
                    source_filing = EXCLUDED.source_filing,
                    audited       = EXCLUDED.audited""" : """
                ON CONFLICT ON CONSTRAINT uq_reporting_period DO UPDATE SET
                    source_filing = EXCLUDED.source_filing,
                    audited       = EXCLUDED.audited
                WHERE reporting_period.audited IS NULL AND EXCLUDED.audited IS NOT NULL""";
        jdbc.sql("""
                        INSERT INTO reporting_period (company_id, fiscal_year, fiscal_quarter, period_type,
                                                      period_start, period_end, source_filing, audited)
                        VALUES (:company, :fy, :fq, :type, :start, :end, :source, :audited)
                        """ + conflict)
                .param("company", companyId).param("fy", period.fiscalYear()).param("fq", period.fiscalQuarter())
                .param("type", period.periodType()).param("start", period.start()).param("end", period.end())
                .param("source", sourceFiling).param("audited", audited)
                .update();
        return periodId(companyId, period).orElseThrow();
    }

    /** The filing a period's data came from (its own filing, else the first filing that stated it). */
    public Optional<String> sourceFiling(long periodId) {
        return jdbc.sql("SELECT source_filing FROM reporting_period WHERE period_id = :p").param("p", periodId)
                .query((rs, i) -> rs.getString(1)).optional();
    }

    public Optional<Long> periodId(long companyId, PeriodRef period) {
        return jdbc.sql("""
                        SELECT period_id FROM reporting_period
                        WHERE company_id = :company AND fiscal_year = :fy AND period_type = :type
                          AND fiscal_quarter IS NOT DISTINCT FROM :fq""")
                .param("company", companyId).param("fy", period.fiscalYear()).param("type", period.periodType())
                .param("fq", period.fiscalQuarter())
                .query(Long.class).optional();
    }

    /**
     * Writes one mapped statement. {@code overwrite} = the statement belongs to the filing's current
     * period: its values replace the stored ones, except that a field the filing does not report (NULL) keeps
     * the value another filing stored (e.g. SIMP's FY2023 filing tags no revenue; its FY2024 filing's
     * comparative does). Otherwise (a comparative) an existing row keeps every stored value and only its
     * empty fields are filled.
     */
    @Transactional
    public WriteResult writeStatement(long companyId, long periodId, MappedStatement statement, boolean overwrite) {
        String table = statement.table();
        Map<String, BigDecimal> values = statement.values();
        Optional<Map<String, Object>> existing = readStatement(table, periodId);
        if (existing.isPresent() && !overwrite) {
            List<String> gaps = values.keySet().stream()
                    .filter(c -> values.get(c) != null && existing.get().get(c) == null).toList();
            if (gaps.isEmpty()) {
                return new WriteResult(table, statement.period().key(), WriteOutcome.KEPT_EXISTING,
                        differences(existing.get(), values));
            }
            var fill = jdbc.sql("UPDATE " + table + " SET "
                            + String.join(", ", gaps.stream().map(c -> c + " = COALESCE(" + c + ", :" + c + ")").toList())
                            + " WHERE period_id = :period_id")
                    .param("period_id", periodId);
            for (String c : gaps) {
                fill = fill.param(c, values.get(c), java.sql.Types.NUMERIC);
            }
            fill.update();
            List<String> notes = new ArrayList<>(gaps.stream().map(c -> c + ": filled " + values.get(c).toPlainString()).toList());
            differences(existing.get(), values).stream()
                    .filter(d -> gaps.stream().noneMatch(g -> d.startsWith(g + ":")))
                    .forEach(notes::add);
            return new WriteResult(table, statement.period().key(), WriteOutcome.FILLED_GAPS, notes);
        }
        List<String> columns = new ArrayList<>(values.keySet());
        String insertCols = String.join(", ", columns);
        String params = String.join(", ", columns.stream().map(c -> ":" + c).toList());
        String updates = String.join(",\n    ", columns.stream()
                .map(c -> statement.rejected().contains(c) ? c + " = EXCLUDED." + c
                        : c + " = COALESCE(EXCLUDED." + c + ", " + table + "." + c + ")").toList());
        String constraint = "uq_" + table;
        var spec = jdbc.sql("INSERT INTO " + table + " (company_id, period_id, " + insertCols + ")\n"
                        + "VALUES (:company_id, :period_id, " + params + ")\n"
                        + "ON CONFLICT ON CONSTRAINT " + constraint + " DO UPDATE SET\n    " + updates)
                .param("company_id", companyId).param("period_id", periodId);
        for (String c : columns) {
            spec = spec.param(c, values.get(c), java.sql.Types.NUMERIC);
        }
        spec.update();
        List<String> diffs = existing.map(e -> differences(e, values)).orElse(List.of());
        return new WriteResult(table, statement.period().key(),
                existing.isPresent() ? WriteOutcome.UPDATED : WriteOutcome.INSERTED, diffs);
    }

    public Optional<Map<String, Object>> readStatement(String table, long periodId) {
        if (!List.of("income_statement", "balance_sheet", "cash_flow_statement").contains(table)) {
            throw new IllegalArgumentException(table);
        }
        return jdbc.sql("SELECT * FROM " + table + " WHERE period_id = :period")
                .param("period", periodId).query().listOfRows().stream().findFirst();
    }

    private static List<String> differences(Map<String, Object> stored, Map<String, BigDecimal> mapped) {
        List<String> diffs = new ArrayList<>();
        mapped.forEach((column, value) -> {
            if (value == null) {
                return;     // not disclosed in this filing (e.g. D&A of a comparative): nothing to compare
            }
            Object s = stored.get(column);
            BigDecimal sv = s == null ? null : new BigDecimal(s.toString());
            if (!sameAsStored(sv, value)) {
                diffs.add(column + ": stored " + (sv == null ? "NULL" : sv.toPlainString())
                        + ", filing " + (value == null ? "NULL" : value.toPlainString()));
            }
        });
        return diffs;
    }

    /**
     * Whether a stored value is the filing's value. The database keeps a column's scale (4 decimals
     * for amounts, 8 for EPS and share counts) and rounds half away from zero on insert, so a filing
     * value with more decimals, e.g. INDY's USD EPS 0.0868828938473089, is compared at the stored
     * scale (0.08688289).
     */
    public static boolean sameAsStored(BigDecimal stored, BigDecimal filed) {
        if (stored == null || filed == null) {
            return stored == null && filed == null;
        }
        BigDecimal comparable = filed.scale() > stored.scale() ? filed.setScale(stored.scale(), RoundingMode.HALF_UP) : filed;
        return stored.compareTo(comparable) == 0;
    }

    // ------------------------------------------------------------------ segments

    public record SegmentRow(long segmentId, String segmentType, String segmentName, String segmentNameEn) {
    }

    public List<SegmentRow> segments(long companyId) {
        return jdbc.sql("""
                        SELECT segment_id, segment_type, segment_name, segment_name_en FROM segment
                        WHERE company_id = :company ORDER BY segment_type, segment_name""")
                .param("company", companyId)
                .query((rs, i) -> new SegmentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    /** Existing segment with the same name is reused as is (its type is kept); otherwise inserted. */
    @Transactional
    public SegmentRow ensureSegment(long companyId, String type, String name, String nameEn) {
        Optional<SegmentRow> existing = segments(companyId).stream()
                .filter(s -> s.segmentName().equals(name)).findFirst();
        if (existing.isPresent()) {
            if (existing.get().segmentNameEn() == null && nameEn != null) {
                jdbc.sql("UPDATE segment SET segment_name_en = :en WHERE segment_id = :id")
                        .param("en", nameEn).param("id", existing.get().segmentId()).update();
            }
            return existing.get();
        }
        jdbc.sql("""
                        INSERT INTO segment (company_id, segment_type, segment_name, segment_name_en)
                        VALUES (:company, :type, :name, :en)""")
                .param("company", companyId).param("type", type).param("name", name).param("en", nameEn).update();
        return segments(companyId).stream().filter(s -> s.segmentName().equals(name)).findFirst().orElseThrow();
    }

    @Transactional
    public WriteOutcome writeSegmentRevenue(long companyId, long segmentId, long periodId, BigDecimal revenue, boolean overwrite) {
        Optional<BigDecimal> stored = jdbc.sql("SELECT revenue FROM segment_financial WHERE segment_id = :s AND period_id = :p")
                .param("s", segmentId).param("p", periodId).query(BigDecimal.class).optional();
        if (stored.isPresent() && !overwrite) {
            return WriteOutcome.KEPT_EXISTING;
        }
        jdbc.sql("""
                        INSERT INTO segment_financial (segment_id, company_id, period_id, revenue)
                        VALUES (:s, :c, :p, :r)
                        ON CONFLICT ON CONSTRAINT uq_segment_financial DO UPDATE SET revenue = EXCLUDED.revenue""")
                .param("s", segmentId).param("c", companyId).param("p", periodId).param("r", revenue).update();
        return stored.isPresent() ? WriteOutcome.UPDATED : WriteOutcome.INSERTED;
    }

    /** Segment ids that have revenue stored for the period. */
    public List<Long> segmentsWithRevenue(long periodId) {
        return jdbc.sql("SELECT segment_id FROM segment_financial WHERE period_id = :p ORDER BY segment_id")
                .param("p", periodId).query(Long.class).list();
    }

    /**
     * Deletes the period's segment revenue of every segment not in {@code keep}: a breakdown is
     * replaced as a whole, so a segment that only an older filing reported (e.g. a later year's
     * comparative split "Wholesale" into "Wholesale" + "Export") is not counted twice.
     *
     * @return the names of the removed segments
     */
    @Transactional
    public List<String> removeOtherSegmentRevenue(long periodId, List<Long> keep) {
        if (keep.isEmpty()) {
            throw new IllegalArgumentException("A breakdown keeps at least one segment");
        }
        List<String> removed = jdbc.sql("""
                        SELECT s.segment_name FROM segment_financial sf JOIN segment s ON s.segment_id = sf.segment_id
                        WHERE sf.period_id = :p AND sf.segment_id NOT IN (:keep) ORDER BY s.segment_name""")
                .param("p", periodId).param("keep", keep).query(String.class).list();
        jdbc.sql("DELETE FROM segment_financial WHERE period_id = :p AND segment_id NOT IN (:keep)")
                .param("p", periodId).param("keep", keep).update();
        return removed;
    }

    // ------------------------------------------------------------------ shares

    /** Upserts a share snapshot; known values are never replaced by NULL. */
    @Transactional
    public void upsertShareSnapshot(long companyId, ShareAt share) {
        upsertShareSnapshot(companyId, share, false);
    }

    /**
     * Upserts a share snapshot.
     *
     * @param fillOnly true for counts from outside the filing (a website): they only fill empty values and never
     *                 replace a count stored from a filing or a seed script
     */
    @Transactional
    public void upsertShareSnapshot(long companyId, ShareAt share, boolean fillOnly) {
        String merge = fillOnly
                ? """
                  basic_shares       = COALESCE(share_snapshot.basic_shares, EXCLUDED.basic_shares),
                  shares_outstanding = COALESCE(share_snapshot.shares_outstanding, EXCLUDED.shares_outstanding),
                  treasury_shares    = COALESCE(share_snapshot.treasury_shares, EXCLUDED.treasury_shares)"""
                : """
                  basic_shares       = COALESCE(EXCLUDED.basic_shares, share_snapshot.basic_shares),
                  shares_outstanding = COALESCE(EXCLUDED.shares_outstanding, share_snapshot.shares_outstanding),
                  treasury_shares    = COALESCE(EXCLUDED.treasury_shares, share_snapshot.treasury_shares)""";
        jdbc.sql("""
                        INSERT INTO share_snapshot (company_id, snapshot_date, basic_shares, shares_outstanding, treasury_shares)
                        VALUES (:c, :d, :basic, :outstanding, :treasury)
                        ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
                        """ + merge)
                .param("c", companyId).param("d", share.date())
                .param("basic", share.basicShares(), java.sql.Types.NUMERIC)
                .param("outstanding", share.sharesOutstanding(), java.sql.Types.NUMERIC)
                .param("treasury", share.treasuryShares(), java.sql.Types.NUMERIC)
                .update();
    }

    // ------------------------------------------------------------------ derived data and verification

    /** Re-runs V1.0.6: market_snapshot, valuation_snapshot and financial_metric for all companies. */
    public void refreshDerivedData() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        SHARE_SCRIPTS.forEach(s -> populator.addScript(new ClassPathResource(s)));
        populator.addScript(new ClassPathResource(DERIVED_SCRIPT));
        populator.execute(dataSource);
        // the script only inserts and updates: drop valuation metrics whose value became NULL (e.g. a
        // re-ingested period without EBITDA), as the price refresh does
        for (long companyId : jdbc.sql("SELECT company_id FROM company").query(Long.class).list()) {
            valuations.deleteStaleValuationMetrics(companyId);
        }
    }

    public record StoredPeriod(String period, boolean incomeStatement, boolean balanceSheet, boolean cashFlow,
                               int segments, BigDecimal segmentRevenue, BigDecimal revenue,
                               BigDecimal totalAssets, BigDecimal liabilitiesPlusEquity,
                               BigDecimal balanceSheetCash, BigDecimal endingCash, String sourceFiling) {
    }

    public Optional<StoredPeriod> storedPeriod(long companyId, PeriodRef period) {
        return periodId(companyId, period).map(id -> jdbc.sql("""
                        SELECT rp.source_filing,
                               i.period_id IS NOT NULL, b.period_id IS NOT NULL, cf.period_id IS NOT NULL,
                               (SELECT count(*) FROM segment_financial sf WHERE sf.period_id = rp.period_id),
                               (SELECT sum(revenue) FROM segment_financial sf WHERE sf.period_id = rp.period_id),
                               i.revenue, b.total_assets, b.total_liabilities + b.total_equity,
                               b.cash_and_equivalents, cf.ending_cash
                        FROM reporting_period rp
                        LEFT JOIN income_statement i     ON i.period_id  = rp.period_id
                        LEFT JOIN balance_sheet b        ON b.period_id  = rp.period_id
                        LEFT JOIN cash_flow_statement cf ON cf.period_id = rp.period_id
                        WHERE rp.period_id = :id""")
                .param("id", id)
                .query((rs, i) -> new StoredPeriod(period.key(), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getInt(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                        rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getString(1)))
                .single());
    }

    public Map<String, Long> derivedCounts(long companyId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("share_snapshot", "price_daily", "market_snapshot", "valuation_snapshot", "financial_metric")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM " + table + " WHERE company_id = :c")
                    .param("c", companyId).query(Long.class).single());
        }
        return counts;
    }
}
