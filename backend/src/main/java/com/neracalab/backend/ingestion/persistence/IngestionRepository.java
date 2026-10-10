package com.neracalab.backend.ingestion.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    /** One {@link #refreshDerivedData} at a time. */
    private final ReentrantLock derivedDataLock = new ReentrantLock();
    /** {@link #companyLock}: one lock per ticker. */
    private final Map<String, ReentrantLock> companyLocks = new ConcurrentHashMap<>();

    public IngestionRepository(JdbcClient jdbc, DataSource dataSource, ValuationRepository valuations) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.valuations = valuations;
    }

    public record CompanyRow(long companyId, String ticker, String companyName, String legalName, String sector,
                             String industry, String currency, LocalDate fiscalYearEnd) {
    }

    /**
     * Outcome of writing one row. INSERTED / UPDATED: written by the period's own filing; FILLED_GAPS: a comparative
     * filled fields the stored row had empty (e.g. revenue a filing did not tag); SPLIT_ADJUSTED: a comparative
     * restated the per-share figures for a share split (see {@link #adjustForShareSplit}); KEPT_EXISTING: a
     * comparative changed nothing.
     */
    public enum WriteOutcome { INSERTED, UPDATED, FILLED_GAPS, SPLIT_ADJUSTED, KEPT_EXISTING }

    /** Per-share fields of the income statement: an EPS and its share count always come from the same filing. */
    static final List<String> PER_SHARE = List.of("basic_eps", "diluted_eps", "basic_shares", "diluted_shares");
    /** Largest split (or reverse split) ratio recognised. */
    private static final int MAX_SPLIT = 100;

    public record WriteResult(String table, String period, WriteOutcome outcome, List<String> differences) {
    }

    // ------------------------------------------------------------------ company

    /**
     * The write lock of a company. Several filings of one company are stored at the same time, each by its own
     * agent; every write step of an agent (register the company, save the statements of a column, save its
     * revenue segments, save share counts) reads the stored rows and then writes, and holds this lock meanwhile.
     * So a step never sees another filing's step half done: a comparative cannot overwrite the data the period's
     * own filing saves at the same moment, and two breakdowns of one period are never mixed.
     */
    public ReentrantLock companyLock(String ticker) {
        return companyLocks.computeIfAbsent(ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT),
                t -> new ReentrantLock());
    }

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
        return writeStatement(companyId, periodId, statement, overwrite, false);
    }

    /**
     * As {@link #writeStatement(long, long, MappedStatement, boolean)}.
     *
     * @param storedByOtherFiling the period's stored data was written by another filing (a later filing's
     *                            comparative, stored before this filing): when that comparative restated the
     *                            per-share figures for a share split, this filing's own figures do not replace
     *                            them (see {@link #restatedFactor})
     */
    @Transactional
    public WriteResult writeStatement(long companyId, long periodId, MappedStatement statement, boolean overwrite,
                                      boolean storedByOtherFiling) {
        String table = statement.table();
        Map<String, BigDecimal> values = statement.values();
        Optional<Map<String, Object>> existing = readStatement(table, periodId);
        if (existing.isPresent() && !overwrite) {
            List<String> split = table.equals("income_statement")
                    ? adjustForShareSplit(companyId, periodId, existing.get(), values) : List.of();
            Map<String, Object> stored = split.isEmpty() ? existing.get() : readStatement(table, periodId).orElseThrow();
            // an EPS and its share count come from one filing: a comparative fills a share count only where the
            // stored EPS is its own (BMRI FY2022: a post-split count beside the pre-split EPS gave EPS x shares =
            // twice the profit)
            boolean perShareConsistent = split.isEmpty() && samePerShareBasis(stored, values);
            List<String> gaps = values.keySet().stream()
                    .filter(c -> values.get(c) != null && stored.get(c) == null)
                    .filter(c -> !PER_SHARE.contains(c) || perShareConsistent).toList();
            if (gaps.isEmpty()) {
                List<String> notes = new ArrayList<>(split);
                notes.addAll(differences(stored, values));
                return new WriteResult(table, statement.period().key(),
                        split.isEmpty() ? WriteOutcome.KEPT_EXISTING : WriteOutcome.SPLIT_ADJUSTED, notes);
            }
            var fill = jdbc.sql("UPDATE " + table + " SET "
                            + String.join(", ", gaps.stream().map(c -> c + " = COALESCE(" + c + ", :" + c + ")").toList())
                            + " WHERE period_id = :period_id")
                    .param("period_id", periodId);
            for (String c : gaps) {
                fill = fill.param(c, values.get(c), java.sql.Types.NUMERIC);
            }
            fill.update();
            List<String> notes = new ArrayList<>(split);
            gaps.stream().map(c -> c + ": filled " + values.get(c).toPlainString()).forEach(notes::add);
            differences(stored, values).stream()
                    .filter(d -> gaps.stream().noneMatch(g -> d.startsWith(g + ":")))
                    .forEach(notes::add);
            return new WriteResult(table, statement.period().key(),
                    split.isEmpty() ? WriteOutcome.FILLED_GAPS : WriteOutcome.SPLIT_ADJUSTED, notes);
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
        BigDecimal restated = existing.isPresent() && table.equals("income_statement")
                ? restatedFactor(companyId, periodId, existing.get(), values, storedByOtherFiling) : null;
        if (restated != null) {
            return new WriteResult(table, statement.period().key(), WriteOutcome.SPLIT_ADJUSTED,
                    keepRestatedPerShare(companyId, periodId, existing.get(), values, restated));
        }
        List<String> diffs = existing.map(e -> differences(e, values)).orElse(List.of());
        return new WriteResult(table, statement.period().key(),
                existing.isPresent() ? WriteOutcome.UPDATED : WriteOutcome.INSERTED, diffs);
    }

    /**
     * The mirror of {@link #adjustForShareSplit}, for the other upload order: the period's own filing is written
     * over a row whose per-share figures a later filing's comparative had already restated for a share split (MAPA
     * split 1:10 in 2023; its FY2023 filing states FY2022's EPS as 41, its FY2022 filing as 412). Recognised by the
     * same test - the profit attributable to the parent unchanged (0.5%), this filing's EPS a whole multiple or
     * fraction k = 2..100 of the stored one (1%) - when the stored row came from another filing, or when that split
     * was already detected for the period (the own filing uploaded again).
     *
     * @return own EPS / stored EPS (the split factor), or null: the own figures replace the stored ones
     */
    private BigDecimal restatedFactor(long companyId, long periodId, Map<String, Object> stored, Map<String, BigDecimal> own,
                                      boolean storedByOtherFiling) {
        BigDecimal factor = splitBetween(own.get("basic_eps"), own.get("net_income_to_parent"),
                decimal(stored.get("basic_eps")), decimal(stored.get("net_income_to_parent")));
        if (factor == null) {
            return null;
        }
        if (storedByOtherFiling) {
            return factor;
        }
        LocalDate periodEnd = periodEnd(periodId);
        return detectedSplits(companyId).stream().anyMatch(s -> s.effectiveFrom().isAfter(periodEnd)
                && s.factor().compareTo(factor) == 0) ? factor : null;
    }

    /** Puts the restated per-share figures back after the own filing's write; a missing one: the own one / k, x k. */
    private List<String> keepRestatedPerShare(long companyId, long periodId, Map<String, Object> stored,
                                              Map<String, BigDecimal> own, BigDecimal factor) {
        BigDecimal eps = decimal(stored.get("basic_eps"));
        updatePerShare(periodId, eps,
                orElse(decimal(stored.get("diluted_eps")), scaled(own.get("diluted_eps"), factor, false)),
                orElse(decimal(stored.get("basic_shares")), scaled(own.get("basic_shares"), factor, true)),
                orElse(decimal(stored.get("diluted_shares")), scaled(own.get("diluted_shares"), factor, true)));
        BigDecimal oldBasis = own.get("net_income_to_parent").divide(own.get("basic_eps"), 0, RoundingMode.HALF_UP);
        List<String> notes = new ArrayList<>();
        notes.add("share split " + ratio(factor) + " restated by a later filing's comparative: basic_eps " + plain(eps)
                + " kept (this filing states " + plain(own.get("basic_eps")) + ", before the split)");
        recordSplit(companyId, periodId, factor, oldBasis);
        notes.addAll(applyDetectedSplits(companyId));
        return notes;
    }

    private static BigDecimal orElse(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
    }

    /** "10:1" for a split into ten, "1:5" for a reverse split. */
    private static String ratio(BigDecimal factor) {
        return factor.compareTo(BigDecimal.ONE) > 0 ? factor.toPlainString() + ":1"
                : "1:" + BigDecimal.ONE.divide(factor, 0, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The split factor between a period's per-share figures before a split and after it: the profit attributable
     * to the parent is the same (within 0.5%) and the EPS before is k or 1/k times the EPS after, k = 2..100.
     *
     * @return EPS before / EPS after (k, or 1/k for a reverse split); null: no split
     */
    static BigDecimal splitBetween(BigDecimal epsBefore, BigDecimal profitBefore, BigDecimal epsAfter, BigDecimal profitAfter) {
        if (epsBefore == null || epsAfter == null || profitBefore == null || profitAfter == null || epsBefore.signum() == 0
                || epsAfter.signum() == 0 || epsBefore.signum() != epsAfter.signum() || profitBefore.signum() == 0
                || profitAfter.subtract(profitBefore).abs().compareTo(profitBefore.abs().multiply(new BigDecimal("0.005"))) > 0) {
            return null;
        }
        return splitFactor(epsBefore, epsAfter);
    }

    /**
     * Whether a comparative's share counts may fill the stored row: the stored row has no EPS, the comparative has
     * none, or both state the same EPS (within half a cent).
     */
    static boolean samePerShareBasis(Map<String, Object> stored, Map<String, BigDecimal> values) {
        BigDecimal storedEps = decimal(stored.get("basic_eps"));
        BigDecimal eps = values.get("basic_eps");
        return storedEps == null || eps == null || storedEps.subtract(eps).abs().compareTo(new BigDecimal("0.005")) <= 0;
    }

    /**
     * A share split restated in a later filing's comparative (BMRI split 2:1 in 2023; its FY2023 filing states FY2022's
     * EPS as 441.26, its FY2022 filing as 882.52). Recognised when the profit attributable to the parent is unchanged
     * (within 0.5%) and the stored EPS is a whole multiple (or fraction) k = 2..100 of the comparative's (within 1%).
     * Share counts, market data and prices are split-adjusted, so the per-share figures are too:
     * <ul>
     *   <li>the period: EPS and share counts from the comparative (a missing one: the stored one / k, x k)</li>
     *   <li>every earlier period of the company whose EPS is on the old basis - its profit / EPS gives the same share
     *       count (within 10%) as the period's old figures (BMRI FY2021: 601.06 -> 300.53). The same test keeps a
     *       period from being adjusted twice when filings are uploaded again.</li>
     * </ul>
     *
     * @return notes of the adjustment (empty: no split)
     */
    List<String> adjustForShareSplit(long companyId, long periodId, Map<String, Object> stored, Map<String, BigDecimal> values) {
        BigDecimal storedEps = decimal(stored.get("basic_eps"));
        BigDecimal eps = values.get("basic_eps");
        BigDecimal storedProfit = decimal(stored.get("net_income_to_parent"));
        BigDecimal factor = splitBetween(storedEps, storedProfit, eps, values.get("net_income_to_parent"));
        if (factor == null) {
            return List.of();
        }
        BigDecimal oldBasis = storedProfit.divide(storedEps, 0, RoundingMode.HALF_UP);   // implied share count before
        List<String> notes = new ArrayList<>();
        String ratio = ratio(factor);
        BigDecimal diluted = values.get("diluted_eps") != null ? values.get("diluted_eps")
                : scaled(decimal(stored.get("diluted_eps")), factor, false);
        BigDecimal shares = values.get("basic_shares") != null ? values.get("basic_shares")
                : scaled(decimal(stored.get("basic_shares")), factor, true);
        BigDecimal dilutedShares = values.get("diluted_shares") != null ? values.get("diluted_shares")
                : scaled(decimal(stored.get("diluted_shares")), factor, true);
        updatePerShare(periodId, eps, diluted, shares, dilutedShares);
        notes.add("share split " + ratio + " restated by this comparative: basic_eps " + plain(storedEps) + " -> "
                + plain(eps) + " (per-share figures replaced)");

        List<Map<String, Object>> earlier = jdbc.sql("""
                        SELECT i.period_id, rp.fiscal_year, rp.period_type, i.basic_eps, i.diluted_eps, i.basic_shares,
                               i.diluted_shares, i.net_income_to_parent
                        FROM income_statement i JOIN reporting_period rp ON rp.period_id = i.period_id
                        WHERE i.company_id = :c AND i.period_id <> :p
                          AND rp.period_end < (SELECT period_end FROM reporting_period WHERE period_id = :p)
                          AND i.basic_eps IS NOT NULL AND i.basic_eps <> 0 AND i.net_income_to_parent IS NOT NULL
                        ORDER BY rp.period_end""")
                .param("c", companyId).param("p", periodId).query().listOfRows();
        for (Map<String, Object> row : earlier) {
            BigDecimal rowEps = decimal(row.get("basic_eps"));
            BigDecimal implied = decimal(row.get("net_income_to_parent")).divide(rowEps, 0, RoundingMode.HALF_UP);
            if (implied.signum() <= 0 || implied.subtract(oldBasis).abs()
                    .compareTo(oldBasis.abs().multiply(new BigDecimal("0.10"))) > 0) {
                continue;   // not on the old basis (already adjusted, or another share count)
            }
            BigDecimal adjusted = scaled(rowEps, factor, false);
            updatePerShare(((Number) row.get("period_id")).longValue(), adjusted,
                    scaled(decimal(row.get("diluted_eps")), factor, false),
                    scaled(decimal(row.get("basic_shares")), factor, true),
                    scaled(decimal(row.get("diluted_shares")), factor, true));
            notes.add("share split " + ratio + ": " + row.get("fiscal_year") + " " + row.get("period_type") + " basic_eps "
                    + plain(rowEps) + " -> " + plain(adjusted));
        }
        recordSplit(companyId, periodId, factor, oldBasis);
        notes.addAll(applyDetectedSplits(companyId));
        return notes;
    }

    // ------------------------------------------------------------------ detected share splits

    /** Start of the description of a corporate_action row written by {@link #recordSplit}. */
    static final String DETECTED_SPLIT = "Restated in the filings: ";
    /** A stored share count within this share of the count before a split is on the old basis. */
    private static final BigDecimal OLD_BASIS_TOLERANCE = new BigDecimal("0.10");

    /**
     * A share split detected from the filings.
     *
     * @param effectiveFrom  the day after the latest period end a later filing restated; the split took effect
     *                       on or after it
     * @param factor         new shares per old share (k; 1/k for a reverse split)
     * @param oldBasisShares share count before the split
     */
    public record DetectedSplit(LocalDate effectiveFrom, BigDecimal factor, BigDecimal oldBasisShares) {
    }

    private LocalDate periodEnd(long periodId) {
        return jdbc.sql("SELECT period_end FROM reporting_period WHERE period_id = :p").param("p", periodId)
                .query((rs, i) -> rs.getObject(1, LocalDate.class)).single();
    }

    /**
     * Records a detected split in corporate_action (STOCK_SPLIT 1:k or REVERSE_SPLIT k:1), once: a split of the
     * same ratio detected within a year of it is the same split, seen in another restated period, and only moves
     * its date to the later period end. shares_issued holds the shares the split added (old count x (k - 1)).
     */
    private void recordSplit(long companyId, long periodId, BigDecimal factor, BigDecimal oldBasisShares) {
        LocalDate from = periodEnd(periodId).plusDays(1);
        boolean split = factor.compareTo(BigDecimal.ONE) > 0;
        BigDecimal k = split ? factor : BigDecimal.ONE.divide(factor, 0, RoundingMode.HALF_UP);
        BigDecimal ratioFrom = split ? BigDecimal.ONE : k;
        BigDecimal ratioTo = split ? k : BigDecimal.ONE;
        int moved = jdbc.sql("""
                        UPDATE corporate_action SET action_date = GREATEST(action_date, :d)
                        WHERE company_id = :c AND action_type = :type AND ratio_from = :rf AND ratio_to = :rt
                          AND description LIKE :marker AND action_date BETWEEN :lo AND :hi""")
                .param("d", from).param("c", companyId).param("type", split ? "STOCK_SPLIT" : "REVERSE_SPLIT")
                .param("rf", ratioFrom).param("rt", ratioTo).param("marker", DETECTED_SPLIT + "%")
                .param("lo", from.minusYears(1)).param("hi", from.plusYears(1)).update();
        if (moved > 0) {
            return;
        }
        BigDecimal newBasis = scaled(oldBasisShares, factor, true);
        jdbc.sql("""
                        INSERT INTO corporate_action (company_id, action_date, action_type, ratio_from, ratio_to,
                                                      shares_issued, description)
                        VALUES (:c, :d, :type, :rf, :rt, :issued, :description)""")
                .param("c", companyId).param("d", from).param("type", split ? "STOCK_SPLIT" : "REVERSE_SPLIT")
                .param("rf", ratioFrom).param("rt", ratioTo).param("issued", newBasis.subtract(oldBasisShares))
                .param("description", DETECTED_SPLIT + (split ? "stock split 1:" + plain(k) : "reverse split " + plain(k) + ":1")
                        + " (about " + oldBasisShares.toPlainString() + " -> " + newBasis.toPlainString() + " shares). A later "
                        + "filing restates the earnings per share of the period ending " + from.minusDays(1)
                        + " for it; the split took effect after that date (exact date not in the filings). Share counts "
                        + "and per-share figures of earlier dates are stored split-adjusted, like the prices.")
                .update();
    }

    /** The splits recorded by {@link #recordSplit}, oldest first. */
    public List<DetectedSplit> detectedSplits(long companyId) {
        return jdbc.sql("""
                        SELECT action_date, ratio_from, ratio_to, shares_issued FROM corporate_action
                        WHERE company_id = :c AND action_type IN ('STOCK_SPLIT', 'REVERSE_SPLIT')
                          AND description LIKE :marker AND ratio_from > 0 AND ratio_to > 0 AND ratio_from <> ratio_to
                          AND shares_issued IS NOT NULL
                        ORDER BY action_date, corporate_action_id""")
                .param("c", companyId).param("marker", DETECTED_SPLIT + "%")
                .query((rs, i) -> {
                    BigDecimal factor = rs.getBigDecimal(3).compareTo(rs.getBigDecimal(2)) > 0
                            ? rs.getBigDecimal(3).divide(rs.getBigDecimal(2), 0, RoundingMode.HALF_UP)
                            : BigDecimal.ONE.divide(rs.getBigDecimal(2).divide(rs.getBigDecimal(3), 0, RoundingMode.HALF_UP),
                                    12, RoundingMode.HALF_UP);
                    BigDecimal oldBasis = rs.getBigDecimal(4).divide(factor.subtract(BigDecimal.ONE), 0, RoundingMode.HALF_UP);
                    return new DetectedSplit(rs.getObject(1, LocalDate.class), factor, oldBasis);
                }).list();
    }

    /** Whether a detected split took effect after {@code date}: figures of that date are stored split-adjusted. */
    public boolean splitAfter(long companyId, LocalDate date) {
        return detectedSplits(companyId).stream().anyMatch(s -> s.effectiveFrom().isAfter(date));
    }

    /**
     * Brings everything stored for dates before a detected split onto the basis after it, whatever the order the
     * filings were stored in: the per-share figures of the earlier periods (EPS / k, share counts x k), the share
     * count of their balance sheets and the share snapshots. Prices are split-adjusted, so share counts of earlier
     * dates must be too (market cap = price x shares). Only rows still on the old basis are changed - their share
     * count (for an income statement without one: profit / EPS) is within 10% of the count before the split - so
     * running it again changes nothing and a period stored after the split is never touched.
     *
     * @return what was adjusted
     */
    @Transactional
    public List<String> applyDetectedSplits(long companyId) {
        List<String> notes = new ArrayList<>();
        for (DetectedSplit split : detectedSplits(companyId)) {
            BigDecimal factor = split.factor();
            String ratio = ratio(factor);
            List<Map<String, Object>> incomes = jdbc.sql("""
                            SELECT i.period_id, rp.fiscal_year, rp.period_type, i.basic_eps, i.diluted_eps, i.basic_shares,
                                   i.diluted_shares, i.net_income_to_parent
                            FROM income_statement i JOIN reporting_period rp ON rp.period_id = i.period_id
                            WHERE i.company_id = :c AND rp.period_end < :d ORDER BY rp.period_end""")
                    .param("c", companyId).param("d", split.effectiveFrom()).query().listOfRows();
            for (Map<String, Object> row : incomes) {
                BigDecimal eps = decimal(row.get("basic_eps"));
                BigDecimal profit = decimal(row.get("net_income_to_parent"));
                BigDecimal shares = decimal(row.get("basic_shares"));
                BigDecimal basis = shares != null ? shares
                        : eps != null && eps.signum() != 0 && profit != null ? profit.divide(eps, 0, RoundingMode.HALF_UP) : null;
                if (!onOldBasis(basis, split)) {
                    continue;
                }
                updatePerShare(((Number) row.get("period_id")).longValue(), scaled(eps, factor, false),
                        scaled(decimal(row.get("diluted_eps")), factor, false), scaled(shares, factor, true),
                        scaled(decimal(row.get("diluted_shares")), factor, true));
                notes.add("share split " + ratio + ": " + row.get("fiscal_year") + " " + row.get("period_type")
                        + (eps == null ? " share count" : " basic_eps " + plain(eps) + " -> " + plain(scaled(eps, factor, false))));
            }
            List<Map<String, Object>> balances = jdbc.sql("""
                            SELECT b.period_id, rp.fiscal_year, rp.period_type, b.shares_outstanding
                            FROM balance_sheet b JOIN reporting_period rp ON rp.period_id = b.period_id
                            WHERE b.company_id = :c AND rp.period_end < :d AND b.shares_outstanding IS NOT NULL
                            ORDER BY rp.period_end""")
                    .param("c", companyId).param("d", split.effectiveFrom()).query().listOfRows();
            for (Map<String, Object> row : balances) {
                BigDecimal shares = decimal(row.get("shares_outstanding"));
                if (!onOldBasis(shares, split)) {
                    continue;
                }
                jdbc.sql("UPDATE balance_sheet SET shares_outstanding = :s WHERE period_id = :p")
                        .param("s", scaled(shares, factor, true)).param("p", ((Number) row.get("period_id")).longValue()).update();
                notes.add("share split " + ratio + ": " + row.get("fiscal_year") + " " + row.get("period_type")
                        + " balance sheet shares " + plain(shares) + " -> " + plain(scaled(shares, factor, true)));
            }
            List<Map<String, Object>> snapshots = jdbc.sql("""
                            SELECT share_snapshot_id, snapshot_date, basic_shares, shares_outstanding, treasury_shares
                            FROM share_snapshot WHERE company_id = :c AND snapshot_date < :d ORDER BY snapshot_date""")
                    .param("c", companyId).param("d", split.effectiveFrom()).query().listOfRows();
            for (Map<String, Object> row : snapshots) {
                BigDecimal outstanding = decimal(row.get("shares_outstanding"));
                BigDecimal basic = decimal(row.get("basic_shares"));
                if (!onOldBasis(outstanding != null ? outstanding : basic, split)) {
                    continue;
                }
                jdbc.sql("""
                                UPDATE share_snapshot SET basic_shares = :basic, shares_outstanding = :outstanding,
                                       treasury_shares = :treasury
                                WHERE share_snapshot_id = :id""")
                        .param("basic", scaled(basic, factor, true), java.sql.Types.NUMERIC)
                        .param("outstanding", scaled(outstanding, factor, true), java.sql.Types.NUMERIC)
                        .param("treasury", scaled(decimal(row.get("treasury_shares")), factor, true), java.sql.Types.NUMERIC)
                        .param("id", ((Number) row.get("share_snapshot_id")).longValue()).update();
                notes.add("share split " + ratio + ": share count at " + row.get("snapshot_date") + " "
                        + plain(outstanding != null ? outstanding : basic) + " -> "
                        + plain(scaled(outstanding != null ? outstanding : basic, factor, true)));
            }
        }
        return notes;
    }

    private static boolean onOldBasis(BigDecimal shares, DetectedSplit split) {
        BigDecimal old = split.oldBasisShares();
        return shares != null && shares.signum() > 0
                && shares.subtract(old).abs().compareTo(old.abs().multiply(OLD_BASIS_TOLERANCE)) <= 0;
    }

    private void updatePerShare(long periodId, BigDecimal eps, BigDecimal diluted, BigDecimal shares, BigDecimal dilutedShares) {
        jdbc.sql("""
                        UPDATE income_statement SET basic_eps = :eps, diluted_eps = :diluted,
                               basic_shares = :shares, diluted_shares = :dilutedShares
                        WHERE period_id = :p""")
                .param("eps", eps, java.sql.Types.NUMERIC)
                .param("diluted", diluted, java.sql.Types.NUMERIC)
                .param("shares", shares, java.sql.Types.NUMERIC)
                .param("dilutedShares", dilutedShares, java.sql.Types.NUMERIC)
                .param("p", periodId).update();
    }

    /** stored / restated EPS when it is k or 1/k for a whole k = 2..100 (within 1%); else null. */
    static BigDecimal splitFactor(BigDecimal storedEps, BigDecimal eps) {
        double ratio = storedEps.doubleValue() / eps.doubleValue();
        boolean reverse = ratio < 1;
        double r = reverse ? 1 / ratio : ratio;
        long k = Math.round(r);
        if (k < 2 || k > MAX_SPLIT || Math.abs(r - k) > 0.01 * k) {
            return null;
        }
        return reverse ? BigDecimal.ONE.divide(BigDecimal.valueOf(k), 12, RoundingMode.HALF_UP) : BigDecimal.valueOf(k);
    }

    /** A per-share value after a split with {@code factor} (EPS / factor; share count x factor); null stays null. */
    private static BigDecimal scaled(BigDecimal value, BigDecimal factor, boolean shareCount) {
        if (value == null) {
            return null;
        }
        return shareCount ? value.multiply(factor).setScale(0, RoundingMode.HALF_UP)
                : value.divide(factor, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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

    /**
     * Re-runs V1.0.6: market_snapshot, valuation_snapshot and financial_metric for all companies. One run at a
     * time: several uploads are stored at the same time, and two runs rewriting the rows of every company
     * together could block each other (a database deadlock fails one of them). A run that waited starts after
     * the other one ended, so it sees everything its own upload saved.
     */
    public void refreshDerivedData() {
        derivedDataLock.lock();
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            SHARE_SCRIPTS.forEach(s -> populator.addScript(new ClassPathResource(s)));
            populator.addScript(new ClassPathResource(DERIVED_SCRIPT));
            populator.execute(dataSource);
            // the script only inserts and updates: drop valuation metrics whose value became NULL (e.g. a
            // re-ingested period without EBITDA), as the price refresh does
            for (long companyId : jdbc.sql("SELECT company_id FROM company").query(Long.class).list()) {
                valuations.deleteStaleValuationMetrics(companyId);
            }
        } finally {
            derivedDataLock.unlock();
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
                               i.revenue, b.total_assets,
                               b.total_liabilities + COALESCE(b.temporary_syirkah_funds, 0) + b.total_equity,
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
