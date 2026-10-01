package com.neracalab.backend.ingestion.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ingestion.mapping.FilingInfo;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.PeriodRef;
import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;

/**
 * Writes ingested filings with the same upsert semantics as the SQL data scripts.
 * <p>
 * Rules: data of the filing's current period always replaces stored data (latest filing wins);
 * comparative columns only fill gaps and never overwrite an existing row.
 */
@Repository
public class IngestionRepository {

    public static final String EXCHANGE = "IDX";
    private static final String DERIVED_SCRIPT = "db/V1.0.6__data_metrics_valuation.sql";

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    public IngestionRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public record CompanyRow(long companyId, String ticker, String companyName, String legalName, String sector,
                             String industry, String currency, LocalDate fiscalYearEnd) {
    }

    /** Outcome of writing one row. */
    public enum WriteOutcome { INSERTED, UPDATED, KEPT_EXISTING }

    public record WriteResult(String table, String period, WriteOutcome outcome, List<String> differences) {
    }

    // ------------------------------------------------------------------ company

    public Optional<CompanyRow> findCompany(String ticker) {
        return jdbc.sql("""
                        SELECT company_id, ticker, company_name, legal_name, sector, industry, currency, fiscal_year_end
                        FROM company WHERE ticker = :ticker AND exchange = :exchange""")
                .param("ticker", ticker).param("exchange", EXCHANGE)
                .query((rs, i) -> new CompanyRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getObject(8, LocalDate.class)))
                .optional();
    }

    @Transactional
    public CompanyRow upsertCompany(FilingInfo info, String companyName) {
        LocalDate fiscalYearEnd = info.current().isFullYear() ? info.current().end() : info.priorYearEnd().end();
        jdbc.sql("""
                        INSERT INTO company (ticker, exchange, company_name, legal_name, industry, sector,
                                             country, currency, fiscal_year_end, active)
                        VALUES (:ticker, :exchange, :name, :legal, :industry, :sector, 'Indonesia', :currency, :fye, TRUE)
                        ON CONFLICT ON CONSTRAINT uq_company_ticker_exchange DO UPDATE SET
                            company_name    = EXCLUDED.company_name,
                            legal_name      = EXCLUDED.legal_name,
                            industry        = COALESCE(EXCLUDED.industry, company.industry),
                            sector          = COALESCE(EXCLUDED.sector, company.sector),
                            currency        = EXCLUDED.currency,
                            fiscal_year_end = GREATEST(company.fiscal_year_end, EXCLUDED.fiscal_year_end),
                            updated_at      = now()""")
                .param("ticker", info.ticker()).param("exchange", EXCHANGE).param("name", companyName)
                .param("legal", info.legalName()).param("industry", info.industry()).param("sector", info.sector())
                .param("currency", info.currency()).param("fye", fiscalYearEnd)
                .update();
        return findCompany(info.ticker()).orElseThrow();
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
     * period; otherwise an existing row is kept and only compared.
     */
    @Transactional
    public WriteResult writeStatement(long companyId, long periodId, MappedStatement statement, boolean overwrite) {
        String table = statement.table();
        Map<String, BigDecimal> values = statement.values();
        Optional<Map<String, Object>> existing = readStatement(table, periodId);
        if (existing.isPresent() && !overwrite) {
            return new WriteResult(table, statement.period().key(), WriteOutcome.KEPT_EXISTING,
                    differences(existing.get(), values));
        }
        List<String> columns = new ArrayList<>(values.keySet());
        String insertCols = String.join(", ", columns);
        String params = String.join(", ", columns.stream().map(c -> ":" + c).toList());
        String updates = String.join(",\n    ", columns.stream().map(c -> c + " = EXCLUDED." + c).toList());
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
            boolean same = sv == null ? value == null : value != null && sv.compareTo(value) == 0;
            if (!same) {
                diffs.add(column + ": stored " + (sv == null ? "NULL" : sv.toPlainString())
                        + ", filing " + (value == null ? "NULL" : value.toPlainString()));
            }
        });
        return diffs;
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

    // ------------------------------------------------------------------ shares

    /** Upserts a share snapshot; known values are never replaced by NULL. */
    @Transactional
    public void upsertShareSnapshot(long companyId, ShareAt share) {
        jdbc.sql("""
                        INSERT INTO share_snapshot (company_id, snapshot_date, basic_shares, shares_outstanding, treasury_shares)
                        VALUES (:c, :d, :basic, :outstanding, :treasury)
                        ON CONFLICT ON CONSTRAINT uq_share_snapshot DO UPDATE SET
                            basic_shares       = COALESCE(EXCLUDED.basic_shares, share_snapshot.basic_shares),
                            shares_outstanding = COALESCE(EXCLUDED.shares_outstanding, share_snapshot.shares_outstanding),
                            treasury_shares    = COALESCE(EXCLUDED.treasury_shares, share_snapshot.treasury_shares)""")
                .param("c", companyId).param("d", share.date())
                .param("basic", share.basicShares(), java.sql.Types.NUMERIC)
                .param("outstanding", share.sharesOutstanding(), java.sql.Types.NUMERIC)
                .param("treasury", share.treasuryShares(), java.sql.Types.NUMERIC)
                .update();
    }

    // ------------------------------------------------------------------ derived data and verification

    /** Re-runs V1.0.6: market_snapshot, valuation_snapshot and financial_metric for all companies. */
    public void refreshDerivedData() {
        new ResourceDatabasePopulator(new ClassPathResource(DERIVED_SCRIPT)).execute(dataSource);
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
