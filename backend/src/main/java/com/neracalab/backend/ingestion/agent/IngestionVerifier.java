package com.neracalab.backend.ingestion.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.PeriodRef;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.StoredPeriod;

/**
 * Deterministic read-back of what an ingestion stored. Used by the agent's verifyStoredData tool
 * (reflection input) and as the final gate: an ingestion is only COMPLETED when this reports no
 * pending work and no problems, whatever the model concluded.
 */
@Component
public class IngestionVerifier {

    private static final List<String> NOTES = List.of(
            "Interim filings contain no balance sheet for the prior-year comparative period (only for the prior year end).",
            "Comparative columns never overwrite stored data of another filing: KEPT_EXISTING (or FILLED_GAPS, when they filled fields the stored row had empty) is the expected outcome when a period is already stored; a period this filing itself wrote earlier is refreshed (UPDATED).",
            "A field the filing does not report (e.g. revenue a filer did not tag) keeps the value another filing stored.",
            "A period's source_filing is the filing that reported it as its current period, else the first filing that stated it.");

    private final IngestionRepository repository;

    public IngestionVerifier(IngestionRepository repository) {
        this.repository = repository;
    }

    /**
     * @param complete true when nothing is pending and no problem was found
     * @param pending  work still to do, phrased as tool actions
     * @param problems inconsistencies found in the stored data
     */
    public record Verification(boolean complete, List<String> pending, List<String> problems,
                               Map<String, String> expectedPerPeriod, List<StoredPeriod> periods,
                               Map<String, Long> companyRows, List<String> notes) {
    }

    public Verification verify(IngestionSession session) {
        List<String> pending = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<StoredPeriod> periods = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (StatementColumn column : session.mapper().columns()) {
            List<String> tables = session.statements(column).stream().map(MappedStatement::table).toList();
            expected.put(column + " " + session.mapper().period(column).key(), (tables.isEmpty() ? "nothing" : String.join(", ", tables))
                    + (session.segments(column).isPresent() ? ", revenue segments" : "")
                    + (column == StatementColumn.CURRENT_PERIOD ? "" : " (comparative: fills gaps only, never overwrites)"));
        }
        List<String> notes = new ArrayList<>(NOTES);
        // a workbook the standard extraction cannot map is only storable through the full-text extraction
        if (!session.mapper().templateProblems().isEmpty() && session.fullTextColumns().isEmpty()) {
            problems.addAll(session.mapper().templateProblems());
            return new Verification(false, pending, problems, expected, periods, Map.of(), notes);
        }
        problems.addAll(session.fullTextProblems());
        if (!session.fullTextColumns().isEmpty()) {
            notes.add("Full-text extraction: the statements of " + session.fullTextColumns().stream().sorted()
                    .map(c -> c + " (" + session.mapper().period(c).key() + ")").toList()
                    + " were mapped by the model from the text of the workbook (every amount is read from its cells), "
                    + "because the standard extraction could not store them");
        }
        var company = session.company().or(() -> repository.findCompany(session.info().ticker()));
        if (company.isEmpty()) {
            pending.add("registerCompany: company " + session.info().ticker() + " does not exist yet");
            return new Verification(false, pending, problems, expected, periods, Map.of(), notes);
        }
        long companyId = company.get().companyId();

        for (StatementColumn column : session.mapper().columns()) {
            List<MappedStatement> statements = session.statements(column);
            if (statements.isEmpty()) {
                continue;
            }
            PeriodRef period = session.mapper().period(column);
            if (!session.savedStatements().containsKey(column)) {
                MappedStatement blocked = statements.stream().filter(MappedStatement::hasErrors).findFirst().orElse(null);
                pending.add(blocked == null
                        ? "saveStatements(" + column + ") for " + period.key()
                        : "saveStatements(" + column + ") is blocked: " + blockReason(blocked));
            }
            var segments = session.segments(column);
            var stored = repository.storedPeriod(companyId, period);
            if (segments.isPresent() && !session.savedSegments().containsKey(column)) {
                List<String> failed = segments.get().checks().stream().filter(Check::isError).map(Check::message).toList();
                if (column != StatementColumn.CURRENT_PERIOD && !failed.isEmpty() && stored.isPresent()
                        && hasReconciledSegments(stored.get())) {
                    // a comparative breakdown that contradicts itself, for a period whose breakdown is already stored and
                    // adds up to its revenue (from the period's own filing): a comparative never overwrites it, so there
                    // is nothing left to save (TPIA FY2024 in its FY2025 filing: the lines add up to 2,107,089 thousand
                    // under a product subtotal of 1,695,869)
                    notes.add(period.key() + ": the revenue breakdown of " + column + " does not reconcile " + failed
                            + "; the " + stored.get().segments() + " segments stored for " + period.key()
                            + " add up to its revenue and are kept (a comparative never overwrites them)");
                } else if (column != StatementColumn.CURRENT_PERIOD && !failed.isEmpty()) {
                    // the user's decision of 2026-10-31: a comparative breakdown that fails its own checks is the filing's,
                    // not an open item, also where no breakdown of the period is stored - the period's own filing stores
                    // one (BUKK FY2024 in its FY2025 filing, CASS, DKHH, FWCT H1 2025, GEMA 9M 2025: lines filed twice or
                    // adding up above the revenue; errors 1553 - 1590). It used to leave the upload INCOMPLETE.
                    notes.add(period.key() + ": the revenue breakdown of " + column + " does not reconcile " + failed
                            + "; it is not stored (a comparative breakdown that fails its checks is left out; the period's"
                            + " own filing stores its breakdown)");
                } else {
                    pending.add("saveRevenueSegments(" + column + ") for " + period.key()
                            + (session.isSegmentsExtracted(column) ? "" : " (call extractRevenueSegments first)"));
                }
            }
            if (stored.isEmpty()) {
                continue;
            }
            StoredPeriod p = stored.get();
            periods.add(p);
            boolean expectIncome = statements.stream().anyMatch(s -> s.table().equals("income_statement"));
            boolean expectBalance = statements.stream().anyMatch(s -> s.table().equals("balance_sheet"));
            boolean expectCash = statements.stream().anyMatch(s -> s.table().equals("cash_flow_statement"));
            if (session.savedStatements().containsKey(column)) {
                if (expectIncome && !p.incomeStatement()) problems.add(period.key() + ": income statement missing");
                if (expectBalance && !p.balanceSheet()) problems.add(period.key() + ": balance sheet missing");
                if (expectCash && !p.cashFlow()) problems.add(period.key() + ": cash flow statement missing");
            }
            if (p.totalAssets() != null && p.liabilitiesPlusEquity() != null
                    && p.totalAssets().compareTo(p.liabilitiesPlusEquity()) != 0) {
                String rounding = balanceRoundingExplanation(session, p);
                if (rounding != null) {
                    notes.add(period.key() + ": " + rounding);
                } else {
                    problems.add(period.key() + ": total assets " + p.totalAssets() + " != liabilities + equity " + p.liabilitiesPlusEquity());
                }
            }
            if (p.segments() > 0 && p.revenue() != null && p.segmentRevenue().compareTo(p.revenue()) != 0) {
                String rounding = segmentRoundingExplanation(session, p);
                if (rounding != null) {
                    notes.add(period.key() + ": " + rounding);
                } else {
                    problems.add(period.key() + ": segment revenue " + p.segmentRevenue() + " != revenue " + p.revenue());
                }
            }
            if (column == StatementColumn.CURRENT_PERIOD && p.balanceSheetCash() != null && p.endingCash() != null
                    && p.balanceSheetCash().compareTo(p.endingCash()) != 0) {
                String rounding = roundingExplanation(session, p);
                String immaterial = rounding != null ? null : immaterialExplanation(p);
                String overdrafts = rounding != null || immaterial != null ? null : overdraftExplanation(session, column, p,
                        storedField(companyId, period, "balance_sheet", "short_term_debt"));
                if (rounding != null) {
                    notes.add(period.key() + ": " + rounding);
                } else if (immaterial != null) {
                    notes.add(period.key() + ": " + immaterial);
                } else if (overdrafts != null) {
                    notes.add(period.key() + ": " + overdrafts);
                } else {
                    problems.add(period.key() + ": balance-sheet cash " + p.balanceSheetCash() + " != cash-flow ending cash " + p.endingCash());
                }
            }
            if (column == StatementColumn.CURRENT_PERIOD) {
                compareCurrent(session, companyId, column, problems, notes);
            }
        }
        if (session.shareCapital().resolved() && !session.isSharesSaved()) {
            pending.add("saveShareSnapshots");
        }
        if (session.hasWrites() && !session.isDerivedCurrent()) {
            pending.add("refreshDerivedData (data changed since the last refresh)");
        }
        Map<String, Long> rows = repository.derivedCounts(companyId);
        if (rows.getOrDefault("price_daily", 0L) == 0) {
            notes.add("No daily prices are stored for this company, so market_snapshot and valuation_snapshot are "
                    + "empty until its prices are loaded: prices are not part of a financial statement filing. The price "
                    + "ingestion of the company is queued when this filing is stored "
                    + "(neracalab.ingestion.prices-after-upload).");
        }
        return new Verification(pending.isEmpty() && problems.isEmpty(), pending, problems, expected, periods, rows, notes);
    }

    /** The stored period has a revenue breakdown and it adds up to the stored revenue. */
    static boolean hasReconciledSegments(StoredPeriod p) {
        return p.segments() > 0 && p.revenue() != null && p.segmentRevenue() != null
                && p.segmentRevenue().compareTo(p.revenue()) == 0;
    }

    /**
     * A filing in thousands or millions whose cash flow cell carries more digits than its rounding unit (UNTR FY2022:
     * ending cash 38281513.468848 million, the balance sheet 38281513), or whose two cells are one unit apart because
     * each is rounded on its own (BATA H1 2026: 26,809,539 thousand beside 26,809,538; BBLD FY2023, FY2024, H1 2026:
     * one million; errors 1414 - 1418): at most one unit, which the mapper's own check accepts. Alike in a filing of
     * full amounts, whose unit is one rupiah or dollar (BRAM H1 2026, full USD: the cash flow rolls forward 21,668,146
     * + 2,721,874 - 22,173 = 24,367,847 beside a balance-sheet cash of 24,367,846; BMBL FY2022, full rupiah: 355,711,311
     * + 123,852,944 = 479,564,255 beside 479,564,254; errors 1472, 1482): a balance too small for the billionth of
     * {@link #immaterialExplanation}, one filed unit as the mapper's full-amount roll-forward accepts (ALMI). Stored as
     * filed; {@code null} when the difference is more than a unit (BMBL FY2023: 2 rupiah, carried from FY2022).
     */
    static String roundingExplanation(IngestionSession session, StoredPeriod p) {
        BigDecimal unit = session.mapper().unit();
        BigDecimal difference = p.endingCash().subtract(p.balanceSheetCash()).abs();
        if (difference.compareTo(unit) > 0) {
            return null;
        }
        return "cash-flow ending cash " + p.endingCash().toPlainString() + " and balance-sheet cash "
                + p.balanceSheetCash().toPlainString() + " differ by " + difference.toPlainString()
                + ", at most the filing's rounding unit " + unit.toPlainString() + ": stored as filed";
    }

    /**
     * A filing whose cash flow and balance sheet disagree by a few units of a large cash balance (MGLV FY2024, full
     * rupiah: the cash flow rolls forward 6,824,569,377 + 959,414,303 = 7,783,983,680, every section adding up, beside a
     * balance-sheet cash of 7,783,983,677; its FY2025 filing files the same pair as comparatives; error 1067). The gap
     * is the filing's own, not a misread figure: under a billionth of the cash ({@link FilingMapper#FULL_AMOUNT_SLIP},
     * the slip the mapper accepts in a cash roll-forward, WIFI FY2022), a note; stored as filed. {@code null} when it is
     * larger (INDO FY2022's 20,000,000 of 305,326,998,864, error 959, stays a problem).
     */
    static String immaterialExplanation(StoredPeriod p) {
        BigDecimal difference = p.endingCash().subtract(p.balanceSheetCash()).abs();
        BigDecimal limit = p.balanceSheetCash().abs().max(p.endingCash().abs()).multiply(FilingMapper.FULL_AMOUNT_SLIP);
        if (difference.signum() == 0 || difference.compareTo(limit) >= 0) {
            return null;
        }
        return "cash-flow ending cash " + p.endingCash().toPlainString() + " and balance-sheet cash "
                + p.balanceSheetCash().toPlainString() + " differ by " + difference.toPlainString()
                + ", under a billionth of the cash: a slip in the filing, stored as filed";
    }

    /**
     * A balance sheet whose equity carries a fraction of the filing's rounding unit (MEDC FY2022, full USD amounts: common
     * stocks 121,798,945.72, so equity attributable to the parent 1,557,260,009.7 and liabilities + equity
     * 6,931,905,825.7 beside total assets 6,931,905,826 - its FY2023 filing states 1,557,260,010): the two differ by less
     * than one unit, which the mapper's own check accepts. Stored as filed; {@code null} when the difference is a unit or
     * more. Errors 711 and 712: both MEDC uploads ended INCOMPLETE over USD 0.30.
     */
    static String balanceRoundingExplanation(IngestionSession session, StoredPeriod p) {
        BigDecimal unit = session.mapper().precision().max(BigDecimal.ONE);
        BigDecimal difference = p.totalAssets().subtract(p.liabilitiesPlusEquity()).abs();
        if (difference.compareTo(unit) >= 0) {
            return null;
        }
        return "total assets " + p.totalAssets().toPlainString() + " and liabilities + equity "
                + p.liabilitiesPlusEquity().toPlainString() + " differ by " + difference.toPlainString()
                + ", less than the filing's rounding unit " + unit.toPlainString() + ": stored as filed";
    }

    /**
     * A revenue breakdown filed with more digits than the filing's rounding unit (EXCL FY2022, millions: Data & Digital
     * Services 26,546,951.253861 and two more lines with decimals, 29,141,993.900667 in all, beside revenue 29,141,994):
     * the two differ by less than one unit, which the mapper's own check accepts. Stored as filed; {@code null} when the
     * difference is a unit or more. Alike in a full-amount filing whose revenue carries a fraction of a rupiah (RAFI
     * FY2022: revenue 375,886,948,578.9396 beside a breakdown of 375,886,948,579; errors 1149, 1150).
     */
    static String segmentRoundingExplanation(IngestionSession session, StoredPeriod p) {
        BigDecimal unit = session.mapper().precision().max(BigDecimal.ONE);
        BigDecimal difference = p.segmentRevenue().subtract(p.revenue()).abs();
        if (difference.compareTo(unit) >= 0) {
            return null;
        }
        return "segment revenue " + p.segmentRevenue().toPlainString() + " and revenue " + p.revenue().toPlainString()
                + " differ by " + difference.toPlainString() + ", less than the filing's rounding unit "
                + unit.toPlainString() + " (the breakdown is filed with more digits): stored as filed";
    }

    /**
     * Many issuers (e.g. GGRM) present cash and cash equivalents net of bank overdrafts in the cash flow
     * statement while the balance sheet shows the overdrafts within short-term bank loans. That is the
     * filing, not a storage error: accepted when the stored cash figures are the filing's own and the
     * difference is positive and within the filing's short-term borrowings. Otherwise {@code null}.
     */
    static String overdraftExplanation(IngestionSession session, StatementColumn column, StoredPeriod p) {
        return overdraftExplanation(session, column, p, null);
    }

    /**
     * @param storedShortTermDebt the period's stored short-term debt, used when the filing's balance sheet leaves its
     *                            borrowing rows off the face (LPPF FY2023: cash flow ending cash 457,628 million is the
     *                            balance sheet's 507,631 less overdrafts of 50,003, within the bank loans of 550,003 that
     *                            only its bank loan note and the FY2024 comparative show; FilingMapper leaves the field empty)
     */
    static String overdraftExplanation(IngestionSession session, StatementColumn column, StoredPeriod p,
                                       BigDecimal storedShortTermDebt) {
        BigDecimal filedCash = mapped(session, column, "balance_sheet", "cash_and_equivalents");
        BigDecimal filedEnding = mapped(session, column, "cash_flow_statement", "ending_cash");
        BigDecimal mappedDebt = mapped(session, column, "balance_sheet", "short_term_debt");
        BigDecimal shortTermDebt = mappedDebt != null ? mappedDebt : storedShortTermDebt;
        if (filedCash == null || filedEnding == null
                || !asStored(filedCash, p.balanceSheetCash()) || !asStored(filedEnding, p.endingCash())) {
            return null;
        }
        BigDecimal difference = filedCash.subtract(filedEnding);
        if (difference.signum() < 0) {
            // needs no borrowings: an insurer has no short-term debt row (AMAG)
            return depositsExplanation(session, column, filedCash, filedEnding);
        }
        if (shortTermDebt == null || difference.signum() == 0 || difference.compareTo(shortTermDebt) > 0) {
            return null;
        }
        return "cash-flow ending cash " + filedEnding.toPlainString() + " is balance-sheet cash " + filedCash.toPlainString()
                + " less " + difference.toPlainString() + ", within short-term borrowings " + shortTermDebt.toPlainString()
                + ": bank overdrafts netted against cash in the cash flow statement, stored as filed";
    }

    /**
     * The mirror case (e.g. PWON): the cash flow statement's cash also counts time deposits or restricted funds
     * the balance sheet shows among other current financial assets. Accepted when the excess is within them. An
     * insurer's are its time deposits (AMAG FY2022 - 9M 2026: 485,425,155 thousand = cash 64,021,213 + 421,403,942
     * within time deposits of 651,473,868 at FY2025; ASDM FY2025 / 9M 2026; errors 1354, 1356 - 1360, 1407, 1409).
     */
    private static String depositsExplanation(IngestionSession session, StatementColumn column, BigDecimal filedCash,
                                              BigDecimal filedEnding) {
        BigDecimal excess = filedEnding.subtract(filedCash);
        BigDecimal deposits = session.mapper().currentFinancialAssetsOutsideCash(column);
        if (deposits == null || excess.compareTo(deposits) > 0) {
            return null;
        }
        return "cash-flow ending cash " + filedEnding.toPlainString() + " is balance-sheet cash " + filedCash.toPlainString()
                + " plus " + excess.toPlainString() + ", within other current financial assets " + deposits.toPlainString()
                + ": deposits or restricted funds counted as cash in the cash flow statement, stored as filed";
    }

    /**
     * A mapped amount equals its stored value at the stored scale (NUMERIC(24,4)): a cell can carry a binary fraction
     * beyond it (LAJU FY2022: ending cash 3.7643576861000004E9, stored 3,764,357,686.1000), which is the same amount.
     */
    private static boolean asStored(BigDecimal mapped, BigDecimal stored) {
        return mapped.setScale(Math.max(stored.scale(), 4), RoundingMode.HALF_UP).compareTo(stored) == 0;
    }

    /** A field of the period's stored row; {@code null} when not stored. */
    private BigDecimal storedField(long companyId, PeriodRef period, String table, String field) {
        return repository.periodId(companyId, period).flatMap(id -> repository.readStatement(table, id))
                .map(row -> row.get(field)).map(v -> new BigDecimal(v.toString())).orElse(null);
    }

    private static BigDecimal mapped(IngestionSession session, StatementColumn column, String table, String field) {
        return session.statements(column).stream().filter(s -> s.table().equals(table))
                .map(s -> s.values().get(field)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** The stored current-period rows must equal the mapped values (they were written by this filing). */
    private void compareCurrent(IngestionSession session, long companyId, StatementColumn column, List<String> problems,
                                List<String> notes) {
        if (!session.savedStatements().containsKey(column)) {
            return;
        }
        PeriodRef period = session.mapper().period(column);
        var periodId = repository.periodId(companyId, period);
        if (periodId.isEmpty()) {
            return;
        }
        // a share split after this period, restated by a later filing: per-share figures and share counts are
        // stored on the basis after the split, not as this filing states them
        boolean splitAdjusted = repository.splitAfter(companyId, period.end());
        List<String> adjusted = new ArrayList<>();
        for (MappedStatement statement : session.statements(column)) {
            repository.readStatement(statement.table(), periodId.get()).ifPresent(row ->
                    statement.values().forEach((field, value) -> {
                        Object s = row.get(field);
                        BigDecimal stored = s == null ? null : new BigDecimal(s.toString());
                        // a field the filing does not report keeps another filing's value; a rejected one is NULL
                        boolean expected = value != null || statement.rejected().contains(field);
                        if (expected && !IngestionRepository.sameAsStored(stored, value)) {
                            if (splitAdjusted && value != null && stored != null && SPLIT_ADJUSTED_FIELDS.contains(field)) {
                                adjusted.add(field + " " + value.stripTrailingZeros().toPlainString() + " -> "
                                        + stored.stripTrailingZeros().toPlainString());
                                return;
                            }
                            problems.add(statement.period().key() + " " + statement.table() + "." + field
                                    + ": stored " + stored + " but the filing says " + value);
                        }
                    }));
        }
        if (!adjusted.isEmpty()) {
            notes.add(period.key() + ": a later filing restates this period for a share split; stored split-adjusted, "
                    + "like the prices: " + String.join(", ", adjusted));
        }
    }

    /** Fields a share split after the period changes: stored on the basis after the split. */
    private static final java.util.Set<String> SPLIT_ADJUSTED_FIELDS = java.util.Set.of("basic_eps", "diluted_eps",
            "basic_shares", "diluted_shares", "shares_outstanding");

    static String blockReason(MappedStatement statement) {
        if (!statement.unclassified().isEmpty()) {
            return statement.table() + " has unclassified lines " + statement.unclassified().stream()
                    .map(MappedStatement.UnclassifiedLine::label).toList() + " (use classifyIncomeLines)";
        }
        return statement.table() + " failed checks " + statement.checks().stream()
                .filter(c -> c.isError()).map(c -> c.message()).toList();
    }
}
