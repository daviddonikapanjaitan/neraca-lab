package com.neracalab.backend.ingestion.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

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
            "Comparative columns never overwrite stored data: KEPT_EXISTING is the expected outcome when a period is already stored.",
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
        if (!session.mapper().templateProblems().isEmpty()) {
            problems.addAll(session.mapper().templateProblems());
            return new Verification(false, pending, problems, expected, periods, Map.of(), notes);
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
            if (segments.isPresent() && !session.savedSegments().containsKey(column)) {
                pending.add("saveRevenueSegments(" + column + ") for " + period.key()
                        + (session.isSegmentsExtracted(column) ? "" : " (call extractRevenueSegments first)"));
            }
            var stored = repository.storedPeriod(companyId, period);
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
                problems.add(period.key() + ": total assets " + p.totalAssets() + " != liabilities + equity " + p.liabilitiesPlusEquity());
            }
            if (p.segments() > 0 && p.revenue() != null && p.segmentRevenue().compareTo(p.revenue()) != 0) {
                problems.add(period.key() + ": segment revenue " + p.segmentRevenue() + " != revenue " + p.revenue());
            }
            if (column == StatementColumn.CURRENT_PERIOD && p.balanceSheetCash() != null && p.endingCash() != null
                    && p.balanceSheetCash().compareTo(p.endingCash()) != 0) {
                problems.add(period.key() + ": balance-sheet cash " + p.balanceSheetCash() + " != cash-flow ending cash " + p.endingCash());
            }
            if (column == StatementColumn.CURRENT_PERIOD) {
                compareCurrent(session, companyId, column, problems);
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
                    + "empty by design: prices are not part of a financial statement filing and are loaded separately.");
        }
        return new Verification(pending.isEmpty() && problems.isEmpty(), pending, problems, expected, periods, rows, notes);
    }

    /** The stored current-period rows must equal the mapped values (they were written by this filing). */
    private void compareCurrent(IngestionSession session, long companyId, StatementColumn column, List<String> problems) {
        if (!session.savedStatements().containsKey(column)) {
            return;
        }
        var periodId = repository.periodId(companyId, session.mapper().period(column));
        if (periodId.isEmpty()) {
            return;
        }
        for (MappedStatement statement : session.statements(column)) {
            repository.readStatement(statement.table(), periodId.get()).ifPresent(row ->
                    statement.values().forEach((field, value) -> {
                        Object s = row.get(field);
                        BigDecimal stored = s == null ? null : new BigDecimal(s.toString());
                        boolean same = stored == null ? value == null : value != null && stored.compareTo(value) == 0;
                        if (!same) {
                            problems.add(statement.period().key() + " " + statement.table() + "." + field
                                    + ": stored " + stored + " but the filing says " + value);
                        }
                    }));
        }
    }

    static String blockReason(MappedStatement statement) {
        if (!statement.unclassified().isEmpty()) {
            return statement.table() + " has unclassified lines " + statement.unclassified().stream()
                    .map(MappedStatement.UnclassifiedLine::label).toList() + " (use classifyIncomeLines)";
        }
        return statement.table() + " failed checks " + statement.checks().stream()
                .filter(c -> c.isError()).map(c -> c.message()).toList();
    }
}
