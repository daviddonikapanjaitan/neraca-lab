package com.neracalab.backend.ingestion.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.IncomeLineCategory;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.PeriodRef;
import com.neracalab.backend.ingestion.mapping.SegmentExtraction;
import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.CompanyRow;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.SegmentRow;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.WriteResult;

/**
 * Tools the model can call during one ingestion (Tool Calling Pattern). They operate on the
 * server-side {@link IngestionSession}: the model chooses columns, names and categories, never
 * amounts. Write tools enforce their own preconditions and validation, so a wrong call returns an
 * explanation instead of corrupting data.
 */
public class IngestionTools {

    /** Tools that only read: the executor may run them in parallel. */
    public static final Set<String> READ_ONLY = Set.of("getFilingOverview", "findCompany", "extractStatements",
            "extractRevenueSegments", "verifyStoredData");

    private final IngestionSession session;
    private final IngestionRepository repository;
    private final IngestionVerifier verifier;

    public IngestionTools(IngestionSession session, IngestionRepository repository, IngestionVerifier verifier) {
        this.session = session;
        this.repository = repository;
        this.verifier = verifier;
    }

    // ================================================================== read tools

    public record ColumnOverview(StatementColumn column, String period, String periodStart, String periodEnd,
                                 List<String> statements, boolean hasRevenueSegments) {
    }

    public record FilingOverview(String fileName, String ticker, String legalName, String sector, String industry,
                                 String currency, String rounding, String submission, boolean audited,
                                 String currentPeriod, List<ColumnOverview> columns, boolean shareCapitalResolvable,
                                 String shareCountBasis, List<String> templateProblems, List<String> warnings) {
    }

    @Tool(description = """
            Overview of the uploaded IDX filing: company identity, reporting periods, which statement \
            columns exist (CURRENT_PERIOD, PRIOR_PERIOD and, for interim filings, PRIOR_YEAR_END), \
            which statements and revenue segments each column has, and template problems. Read-only.""")
    public FilingOverview getFilingOverview() {
        var mapper = session.mapper();
        var info = session.info();
        List<ColumnOverview> columns = new ArrayList<>();
        for (StatementColumn column : mapper.columns()) {
            PeriodRef p = mapper.period(column);
            columns.add(new ColumnOverview(column, p.key(), p.start().toString(), p.end().toString(),
                    session.statements(column).stream().map(MappedStatement::table).toList(),
                    session.segments(column).isPresent()));
        }
        var shares = session.shareCapital();
        List<String> warnings = new ArrayList<>(info.warnings());
        warnings.addAll(mapper.warnings());
        shares.checks().stream().filter(c -> c.severity() == Check.Severity.WARNING).map(Check::message).forEach(warnings::add);
        return new FilingOverview(info.fileName(), info.ticker(), info.legalName(), info.sector(), info.industry(),
                info.currency(), info.rounding(), info.submission(), info.audited(), info.current().key(), columns,
                shares.resolved(), shares.basis(),
                session.mapper().templateProblems(), warnings);
    }

    public record CompanyLookup(boolean found, String ticker, Long companyId, String companyName, String legalName,
                                List<String> existingSegments, String advice) {
    }

    @Tool(description = """
            Looks up the filing's company (by its ticker) in the database. Read-only. \
            If found=false the company must be registered before anything can be saved.""")
    public CompanyLookup findCompany() {
        String ticker = session.info().ticker();
        Optional<CompanyRow> row = repository.findCompany(ticker);
        session.companyLookedUp();
        if (row.isEmpty()) {
            return new CompanyLookup(false, ticker, null, null, session.info().legalName(), List.of(),
                    "Company is new: call registerCompany with a short display name.");
        }
        session.company(row.get());
        List<String> segments = repository.segments(row.get().companyId()).stream()
                .map(s -> s.segmentType() + " | " + s.segmentName() + " | " + s.segmentNameEn()).toList();
        return new CompanyLookup(true, ticker, row.get().companyId(), row.get().companyName(), row.get().legalName(),
                segments, "Company exists: do not register it again.");
    }

    public record StatementSummary(String table, String sourceSheet, Map<String, String> keyFigures,
                                   List<String> failedChecks, List<String> warnings, int passedChecks,
                                   List<MappedStatement.UnclassifiedLine> unclassifiedLines) {
    }

    public record ExtractionResult(StatementColumn column, String period, boolean readyToSave,
                                   List<StatementSummary> statements, String next) {
    }

    @Tool(description = """
            Extracts and validates the income statement, balance sheet and cash flow of one column of the \
            workbook. Amounts stay on the server; you get key figures and the validation result. Read-only; \
            independent columns can be extracted in parallel.""")
    public ExtractionResult extractStatements(
            @ToolParam(description = "Column to extract") StatementColumn column) {
        if (!session.mapper().columns().contains(column)) {
            throw new IllegalArgumentException(column + " does not exist in this filing; columns: " + session.mapper().columns());
        }
        session.markExtracted(column);
        return extractionResult(column);
    }

    private ExtractionResult extractionResult(StatementColumn column) {
        List<StatementSummary> summaries = new ArrayList<>();
        boolean ready = true;
        for (MappedStatement s : session.statements(column)) {
            ready &= !s.hasErrors();
            summaries.add(new StatementSummary(s.table(), s.sourceSheet(), keyFigures(s),
                    s.checks().stream().filter(Check::isError).map(Check::message).toList(),
                    s.checks().stream().filter(c -> c.severity() == Check.Severity.WARNING).map(Check::message).toList(),
                    (int) s.checks().stream().filter(c -> c.severity() == Check.Severity.OK).count(),
                    s.unclassified()));
        }
        String next;
        if (summaries.isEmpty()) {
            ready = false;
            next = "This column has no statements; nothing to save.";
        } else if (session.hasUnclassifiedLines() && summaries.stream().anyMatch(s -> !s.unclassifiedLines().isEmpty())) {
            next = "Classify the unclassified income lines with classifyIncomeLines, then extract again.";
        } else if (!ready) {
            next = "Validation failed; this column cannot be saved. Report the failed checks.";
        } else {
            next = "Ready: call saveStatements(" + column + ").";
        }
        return new ExtractionResult(column, session.mapper().period(column).key(), ready, summaries, next);
    }

    private static Map<String, String> keyFigures(MappedStatement s) {
        List<String> keys = switch (s.table()) {
            case "income_statement" -> List.of("revenue", "gross_profit", "operating_income", "pretax_income",
                    "net_income_to_parent", "basic_eps", "ebitda");
            case "balance_sheet" -> List.of("total_assets", "total_liabilities", "total_equity", "cash_and_equivalents",
                    "shares_outstanding");
            default -> List.of("operating_cash_flow", "investing_cash_flow", "financing_cash_flow", "ending_cash");
        };
        Map<String, String> figures = new LinkedHashMap<>();
        keys.forEach(k -> {
            BigDecimal v = s.values().get(k);
            figures.put(k, v == null ? "n/a" : v.toPlainString());
        });
        return figures;
    }

    /** @param hint guidance for the segment type when the filing's slot is not conclusive */
    public record SegmentLineView(String segmentName, String filingType, String revenue, String hint) {
    }

    public record SegmentResult(StatementColumn column, String period, boolean available, String sourceSheet,
                                List<SegmentLineView> lines, List<String> failedChecks,
                                List<String> existingCompanySegments, String next) {
    }

    @Tool(description = """
            Extracts the revenue breakdown (revenue by type, else by source) of a CURRENT_PERIOD or \
            PRIOR_PERIOD column, with the company's existing segments for reuse. Read-only.""")
    public SegmentResult extractRevenueSegments(
            @ToolParam(description = "CURRENT_PERIOD or PRIOR_PERIOD") StatementColumn column) {
        Optional<SegmentExtraction> e = session.segments(column);
        List<String> existing = session.company().map(c -> repository.segments(c.companyId()).stream()
                .map(s -> s.segmentType() + " | " + s.segmentName() + " | " + s.segmentNameEn()).toList()).orElse(List.of());
        if (e.isEmpty()) {
            return new SegmentResult(column, session.mapper().period(column).key(), false, null, List.of(), List.of(),
                    existing, "No revenue breakdown in this column: skip segments for it.");
        }
        session.markSegmentsExtracted(column);
        List<String> failed = e.get().checks().stream().filter(Check::isError).map(Check::message).toList();
        return new SegmentResult(column, e.get().period().key(), true, e.get().sourceSheet(),
                e.get().lines().stream().map(l -> new SegmentLineView(l.name(), l.filingType(), l.revenue().toPlainString(),
                        l.residualSlot() ? "Filed in the residual 'Other " + l.filingType().toLowerCase(java.util.Locale.ROOT)
                                + " revenue' slot: use segmentType OTHER unless it is clearly goods or services sold to customers"
                                : null)).toList(),
                failed, existing,
                failed.isEmpty() ? "Call saveRevenueSegments(" + column + ") naming every segment exactly once."
                        : "Segments do not reconcile; they cannot be saved.");
    }

    @Tool(description = """
            Re-reads everything stored for this filing and reports what is still pending and any \
            inconsistency (balance sheet identity, segment totals, cash, stored vs filed values). Read-only.""")
    public IngestionVerifier.Verification verifyStoredData() {
        return verifier.verify(session);
    }

    // ================================================================== write tools

    public record CompanyResult(boolean registered, long companyId, String ticker, String companyName, String legalName,
                                String sector, String industry) {
    }

    @Tool(description = """
            Registers the filing's company. Only offered when findCompany reported it missing. Identity, \
            sector and currency come from the filing; you only provide the short display name.""")
    public CompanyResult registerCompany(
            @ToolParam(description = "Short display name without legal form, e.g. 'Hartadinata Abadi' for 'PT Hartadinata Abadi Tbk'")
            String companyName) {
        return locked(() -> {
            String name = companyName == null ? "" : companyName.trim();
            if (name.isEmpty() || name.length() > 120) {
                throw new IllegalArgumentException("companyName must be 1-120 characters");
            }
            if (repository.findCompany(session.info().ticker()).isPresent()) {
                throw new IllegalStateException("Company " + session.info().ticker() + " already exists; use findCompany");
            }
            CompanyRow row = repository.upsertCompany(session.info(), name);
            session.company(row);
            session.touched();
            return new CompanyResult(true, row.companyId(), row.ticker(), row.companyName(), row.legalName(),
                    row.sector(), row.industry());
        });
    }

    public record LineClassification(
            @ToolParam(description = "Label exactly as listed in unclassifiedLines") String label,
            @ToolParam(description = "Meaning of the line") IncomeLineCategory category) {
    }

    @Tool(description = """
            Classifies income-statement lines that extractStatements reported as unclassified. Only offered \
            while such lines exist. The statement is re-validated (profit before tax must reconcile), so a \
            wrong classification is rejected by the checks.""")
    public ExtractionResult classifyIncomeLines(
            @ToolParam(description = "Column the lines belong to") StatementColumn column,
            @ToolParam(description = "One entry per unclassified line") List<LineClassification> classifications) {
        return locked(() -> {
            var unclassified = session.income(column).map(MappedStatement::unclassified).orElse(List.of());
            Set<String> open = new HashSet<>(unclassified.stream().map(MappedStatement.UnclassifiedLine::label).toList());
            for (LineClassification c : classifications) {
                if (!open.contains(c.label())) {
                    throw new IllegalArgumentException("'" + c.label() + "' is not an unclassified line of " + column
                            + "; unclassified: " + open);
                }
            }
            classifications.forEach(c -> session.classifications(column).put(c.label(), c.category()));
            return extractionResult(column);
        });
    }

    public record SaveResult(StatementColumn column, String period, String mode, List<WriteResult> writes, String next) {
    }

    @Tool(description = """
            Saves the reporting period and its validated statements of one column. CURRENT_PERIOD replaces \
            stored data of that period; comparative columns only fill gaps and never overwrite. Requires the \
            company and a successful extractStatements of the column.""")
    public SaveResult saveStatements(
            @ToolParam(description = "Column to save") StatementColumn column) {
        return locked(() -> {
            CompanyRow company = requireCompany();
            if (!session.isExtracted(column)) {
                throw new IllegalStateException("Call extractStatements(" + column + ") first");
            }
            List<MappedStatement> statements = session.statements(column);
            if (statements.isEmpty()) {
                throw new IllegalStateException(column + " has no statements");
            }
            for (MappedStatement s : statements) {
                if (s.hasErrors()) {
                    throw new IllegalStateException("Not saved: " + IngestionVerifier.blockReason(s));
                }
            }
            boolean current = column == StatementColumn.CURRENT_PERIOD;
            PeriodRef period = session.mapper().period(column);
            // annual filings carry audited comparatives; interim comparatives are unaudited; a prior
            // year end carried by an interim filing has an unknown audit status (its own filing sets it)
            Boolean audited = switch (column) {
                case CURRENT_PERIOD -> session.info().audited();
                case PRIOR_PERIOD -> session.info().current().isFullYear() ? session.info().audited() : Boolean.FALSE;
                case PRIOR_YEAR_END -> null;
            };
            long periodId = repository.upsertPeriod(company.companyId(), period, session.info().fileName(), audited, current);
            if (current) {
                var info = session.info();
                repository.advanceFiscalYearEnd(company.companyId(),
                        info.current().isFullYear() ? info.current().end() : info.priorYearEnd().end());
            }
            List<WriteResult> writes = new ArrayList<>();
            for (MappedStatement s : statements) {
                writes.add(repository.writeStatement(company.companyId(), periodId, s, current));
            }
            session.statementsSaved(column, writes);
            return new SaveResult(column, period.key(), current ? "REPLACE (current period)" : "FILL GAPS (comparative)",
                    writes, "Saved. Remaining work is listed by verifyStoredData.");
        });
    }

    public record SegmentNaming(
            @ToolParam(description = "Segment name exactly as extracted") String segmentName,
            @ToolParam(description = "Concise English name") String segmentNameEn,
            @ToolParam(description = "The line's filingType; OTHER only for residual-slot lines that are not revenue from customers")
            SegmentType segmentType) {
    }

    public enum SegmentType { PRODUCT, SERVICE, GEOGRAPHY, CUSTOMER, OTHER }

    public record SegmentSaveResult(StatementColumn column, String period, List<String> saved, List<String> reusedExisting,
                                    List<String> removedFromPeriod) {
    }

    @Tool(description = """
            Saves the revenue segments of a column. Name every extracted segment exactly once; revenue \
            amounts come from the filing. An existing segment with the same name is reused with its stored \
            type. CURRENT_PERIOD replaces the period's whole breakdown (segments of older filings that this \
            filing does not report are removed from the period); PRIOR_PERIOD only fills a period that has \
            no breakdown yet. Requires saveStatements of the same column first.""")
    public SegmentSaveResult saveRevenueSegments(
            @ToolParam(description = "CURRENT_PERIOD or PRIOR_PERIOD") StatementColumn column,
            @ToolParam(description = "One entry per extracted segment") List<SegmentNaming> segments) {
        return locked(() -> {
            CompanyRow company = requireCompany();
            SegmentExtraction extraction = session.segments(column)
                    .orElseThrow(() -> new IllegalStateException(column + " has no revenue segments"));
            if (!session.isSegmentsExtracted(column)) {
                throw new IllegalStateException("Call extractRevenueSegments(" + column + ") first");
            }
            if (extraction.hasErrors()) {
                throw new IllegalStateException("Segments do not reconcile with revenue; not saved");
            }
            if (!session.savedStatements().containsKey(column)) {
                throw new IllegalStateException("Call saveStatements(" + column + ") first");
            }
            Map<String, SegmentNaming> byName = new LinkedHashMap<>();
            for (SegmentNaming s : segments) {
                if (byName.put(s.segmentName(), s) != null) {
                    throw new IllegalArgumentException("Segment '" + s.segmentName() + "' named twice");
                }
            }
            List<String> expected = extraction.lines().stream().map(SegmentExtraction.SegmentLine::name).toList();
            if (!byName.keySet().equals(new HashSet<>(expected))) {
                throw new IllegalArgumentException("Name exactly these segments: " + expected);
            }
            // the filing's slot decides the type; only residual "Other ..." lines may be typed OTHER
            List<String> wrongTypes = new ArrayList<>();
            for (SegmentExtraction.SegmentLine line : extraction.lines()) {
                String chosen = byName.get(line.name()).segmentType().name();
                boolean allowed = chosen.equals(line.filingType()) || (line.residualSlot() && chosen.equals("OTHER"));
                if (!allowed) {
                    wrongTypes.add("'" + line.name() + "' must be " + line.filingType()
                            + (line.residualSlot() ? " or OTHER" : "") + " (filed as " + line.filingType() + "), not " + chosen);
                }
            }
            if (!wrongTypes.isEmpty()) {
                throw new IllegalArgumentException("Segment types must follow the filing: " + wrongTypes);
            }
            long periodId = repository.periodId(company.companyId(), extraction.period()).orElseThrow();
            boolean current = column == StatementColumn.CURRENT_PERIOD;
            List<String> existingNames = repository.segments(company.companyId()).stream().map(SegmentRow::segmentName).toList();
            List<String> saved = new ArrayList<>();
            List<String> reused = new ArrayList<>();
            List<String> removed = List.of();
            // a breakdown is stored as a whole: filings may split the same revenue differently, and mixing
            // two breakdowns of one period counts revenue twice
            if (!current && !repository.segmentsWithRevenue(periodId).isEmpty()) {
                for (SegmentExtraction.SegmentLine line : extraction.lines()) {
                    saved.add(line.name() + " KEPT_EXISTING (the period already has a stored breakdown)");
                }
                session.segmentsSaved(column, saved);
                return new SegmentSaveResult(column, extraction.period().key(), saved, reused, removed);
            }
            List<Long> segmentIds = new ArrayList<>();
            for (SegmentExtraction.SegmentLine line : extraction.lines()) {
                SegmentNaming naming = byName.get(line.name());
                SegmentRow row = repository.ensureSegment(company.companyId(), naming.segmentType().name(),
                        line.name(), naming.segmentNameEn());
                if (existingNames.contains(line.name())) {
                    reused.add(line.name() + " (" + row.segmentType() + ")");
                }
                var outcome = repository.writeSegmentRevenue(company.companyId(), row.segmentId(), periodId, line.revenue(), current);
                saved.add(row.segmentNameEn() + " [" + row.segmentType() + "] " + outcome);
                segmentIds.add(row.segmentId());
            }
            if (current) {
                removed = repository.removeOtherSegmentRevenue(periodId, segmentIds);
            }
            session.segmentsSaved(column, saved);
            return new SegmentSaveResult(column, extraction.period().key(), saved, reused, removed);
        });
    }

    public record ShareSaveResult(String basis, List<String> snapshots, List<String> warnings) {
    }

    @Tool(description = """
            Saves share counts at every date disclosed in the statements of changes in equity (par value \
            inferred from share capital and EPS, an exact EPS denominator when share capital is in another \
            currency, or - when the filing gives none - published counts checked against the filing's EPS, \
            which only fill dates without a stored count). Requires the company.""")
    public ShareSaveResult saveShareSnapshots() {
        return locked(() -> {
            CompanyRow company = requireCompany();
            var shares = session.shareCapital();
            if (!shares.resolved()) {
                throw new IllegalStateException("Share counts could not be derived (no par value fits, the EPS is "
                        + "not precise enough and no published count fits the filing); share counts are not saved");
            }
            List<String> rows = new ArrayList<>();
            for (ShareAt s : shares.snapshots()) {
                repository.upsertShareSnapshot(company.companyId(), s, shares.webSource() != null);
                rows.add(s.date() + ": " + (s.sharesOutstanding() == null ? "n/a" : s.sharesOutstanding().toPlainString())
                        + " shares (" + s.source() + ")");
            }
            session.sharesSaved();
            return new ShareSaveResult(shares.basis(), rows,
                    shares.checks().stream().filter(c -> c.severity() == Check.Severity.WARNING).map(Check::message).toList());
        });
    }

    public record RefreshResult(boolean refreshed, Map<String, Long> companyRows) {
    }

    @Tool(description = """
            Recalculates market snapshots, valuation snapshots and financial metrics from the stored data. \
            Call after the last save.""")
    public RefreshResult refreshDerivedData() {
        return locked(() -> {
            CompanyRow company = requireCompany();
            repository.refreshDerivedData();
            session.derivedRefreshed();
            return new RefreshResult(true, repository.derivedCounts(company.companyId()));
        });
    }

    // ================================================================== helpers

    private CompanyRow requireCompany() {
        return session.company().or(() -> repository.findCompany(session.info().ticker()).map(c -> {
            session.company(c);
            return c;
        })).orElseThrow(() -> new IllegalStateException("Company " + session.info().ticker()
                + " is not registered: call findCompany, then registerCompany"));
    }

    /** Writes are serialised per session even when the model requests them in parallel. */
    private <T> T locked(Supplier<T> action) {
        session.writeLock().lock();
        try {
            return action.get();
        } finally {
            session.writeLock().unlock();
        }
    }
}
