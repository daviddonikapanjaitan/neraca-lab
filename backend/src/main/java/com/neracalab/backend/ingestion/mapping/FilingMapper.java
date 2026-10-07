package com.neracalab.backend.ingestion.mapping;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.neracalab.backend.ingestion.mapping.MappedStatement.UnclassifiedLine;
import com.neracalab.backend.ingestion.mapping.SegmentExtraction.SegmentLine;
import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.ingestion.xlsx.IdxSheets;
import com.neracalab.backend.ingestion.xlsx.IdxTaxonomy;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.RawSheet;

/**
 * Deterministic mapping of an IDX XBRL workbook to the database tables. All amounts are multiplied
 * by the filing's rounding unit; every statement is validated with accounting identities, and
 * nothing here guesses: unknown line items are reported as unclassified, underivable values are
 * {@code null}.
 */
public final class FilingMapper {

    // ---- template labels (English column of the IDX taxonomy) ----
    static final String GROSS_PROFIT = "Total gross profit";
    static final String PROFIT_BEFORE_TAX = "Total profit (loss) before tax";
    static final String TAX = "Tax benefit (expenses)";
    static final String PROFIT_CONTINUING = "Total profit (loss) from continuing operations";
    static final String PROFIT_DISCONTINUED = "Profit (loss) from discontinued operations";
    static final String PROFIT = "Total profit (loss)";
    static final String PROFIT_PARENT = "Profit (loss) attributable to parent entity";
    static final String PROFIT_NCI = "Profit (loss) attributable to non-controlling interests";
    static final String EPS_BASIC = "Basic earnings (loss) per share from continuing operations";
    static final String EPS_BASIC_DISC = "Basic earnings (loss) per share from discontinued operations";
    static final String EPS_DILUTED = "Diluted earnings (loss) per share from continuing operations";
    static final String EPS_DILUTED_DISC = "Diluted earnings (loss) per share from discontinued operations";
    /** Financial and Sharia Industry: operating profit, reported before non-operating items. */
    static final String PROFIT_FROM_OPERATION = "Total profit from operation";
    static final String CASH_BEGINNING = "Cash and cash equivalents cash flows, beginning of the period";
    static final String CASH_END = "Cash and cash equivalents cash flows, end of the period";

    private static final List<String> STRUCTURAL_INCOME_LINES = List.of(GROSS_PROFIT, PROFIT_BEFORE_TAX, TAX,
            PROFIT_CONTINUING, PROFIT_DISCONTINUED, PROFIT, PROFIT_PARENT, PROFIT_NCI,
            EPS_BASIC, EPS_BASIC_DISC, EPS_DILUTED, EPS_DILUTED_DISC, PROFIT_FROM_OPERATION);

    // labels of the General and the Infrastructure Industry taxonomy (the latter: "Short-term non-bank loans",
    // "... property and equipment" instead of "... property, plant and equipment")
    private static final List<String> SHORT_TERM_DEBT = List.of(
            "Short term bank loans", "Short-term non-bank loans", "Trust receipts payables",
            "Current maturities of bank loans", "Current maturities of non-bank financial insitutions loan",
            "Current maturities of secured loans", "Current maturities of unsecured loans",
            "Current maturities of step loans", "Current maturities of loans from government of the republic of indonesia",
            "Current maturities of subordinated loans", "Current maturities of consumer financing payables",
            "Current maturities of notes payable", "Current maturities of medium term notes",
            "Current maturities of bonds payable", "Current maturities of sukuk",
            "Current maturities of subordinated bonds", "Current maturities of other borrowings");
    private static final List<String> LONG_TERM_DEBT = List.of(
            "Long-term bank loans", "Long term non-bank financial insitutions loan", "Long-term step loans",
            "Long-term secured loans", "Long-term unsecured loans",
            "Long-term loans from government of the republic of indonesia", "Long-term subordinated loans",
            "Long-term consumer financing payables", "Long-term notes payable", "Long-term medium term notes",
            "Long-term bonds payable", "Long-term sukuk", "Long-term subordinated bonds",
            "Long-term other borrowings", "Convertible bonds");
    private static final List<String> LEASES = List.of(
            "Current maturities of finance lease liabilities", "Long-term finance lease liabilities");
    private static final List<String> MARKETABLE_SECURITIES = List.of(
            "Short-term investments", "Current financial assets at fair value through profit or loss",
            "Current financial assets fair value through other comprehensive income");

    private static final List<String> CAPEX = List.of(
            "Payments for acquisition of property, plant and equipment",
            "Payments for advances for purchase of property, plant and equipment",
            "Payments for acquisition of property and equipment",
            "Payments for advances for purchase of property and equipment",
            "Payments for acquisition of intangible assets");
    private static final List<String> ACQUISITIONS = List.of(
            "Payments for acquisition of subsidiaries", "Payments for acquisition of interests in joint ventures",
            "Payments for acquisition of interests in associates");
    private static final List<String> STOCK_ISSUANCE = List.of(
            "Proceeds from issuance of common stocks", "Proceeds from issuance of preferred stocks",
            "Proceeds from issuing other equity instruments", "Proceeds from employee stock options program");
    private static final List<String> DEBT_PROCEEDS = List.of(
            "Proceeds from bank loans", "Proceeds from non-bank financial institution loan", "Proceeds from secured loans",
            "Proceeds from unsecured loans", "Proceeds from step loans",
            "Proceeds from loan from government of the republic of indonesia", "Proceeds from subordinated loans",
            "Proceeds from consumer financing payables", "Proceeds from notes payable", "Proceeds from medium term notes",
            "Proceeds from bonds payable", "Subordinated bonds issued", "Proceeds from sukuk",
            "Proceeds from other borrowings", "Proceeds from convertible bonds issuance");
    private static final List<String> DEBT_REPAYMENTS = List.of(
            "Payments of bank loans", "Payments of non-bank financial institution loan", "Payments of secured loans",
            "Payments of unsecured loans", "Payments of step loans",
            "Payments of loan from government of the republic of indonesia", "Payments of subordinated loans",
            "Payments of consumer financing payables", "Payments of notes payable", "Payments of medium term notes",
            "Payments of bonds payable", "Payments of subordinated bonds", "Payments of sukuk",
            "Payments of other borrowings", "Payments of convertible bonds");
    private static final List<String> DIVIDENDS_PAID = List.of(
            "Dividends paid from financing activities", "Dividends paid from operating activities");

    private static final List<BigDecimal> STANDARD_PAR_VALUES = List.of(1, 5, 10, 20, 25, 50, 100, 125, 200, 250,
            500, 1000).stream().map(BigDecimal::valueOf).toList();

    // ---- Financial and Sharia Industry (banks): balance sheet groups as {header, parts...}
    private static final List<List<String>> FIN_DEBT = List.of(
            List.of("Borrowings", "Borrowings third parties", "Borrowings related parties",
                    "Borrowings payables to clearing and settlement guarantee institution"),
            List.of("Securities issued", "Bonds payable", "Sukuk", "Subordinated bonds", "Medium term notes",
                    "Others securities issued"),
            List.of("Subordinated loans", "Subordinated loans third parties", "Subordinated loans related parties"));
    private static final List<String> FIN_MARKETABLE_SECURITIES = List.of("Marketable securities",
            "Marketable securities third parties", "Marketable securities related parties");
    private static final List<String> FIN_RETAINED_EARNINGS = List.of("Appropriated retained earnings",
            "General and legal reserves", "Specific reserves", "Unappropriated retained earnings");
    // cash flow lines of the bank template: acquisitions net of disposals, signed (negative = net acquisition)
    private static final String FIN_INTANGIBLES_NET = "Proceeds from disposal (acquisition) of intangible assets other than goodwill";
    private static final List<String> FIN_CAPEX = List.of(
            "Proceeds from disposal (acquisition) of property and equipment", FIN_INTANGIBLES_NET);
    private static final String FIN_SECURITIES_ISSUED_NET = "Increase (decrease) in securities issued";
    private static final List<String> FIN_STOCK_ISSUANCE = List.of("Proceeds from issuance of new stocks",
            "Proceeds from capital contributions", "Proceeds from employee stock options program");
    private static final List<String> FIN_DEBT_PROCEEDS = List.of("Proceeds from borrowings", "Proceeds from subordinated loans",
            "Proceeds from bonds issuance", "Subordinated bonds issued", "Proceeds from medium term notes",
            "Issuance of mudharabah sukuk");
    private static final List<String> FIN_DEBT_REPAYMENTS = List.of("Payments for borrowings", "Payments of subordinated loans",
            "Payments of bonds payable", "Payments of subordinated bonds", "Payments of medium term notes");

    private final IdxWorkbook workbook;
    private final FilingInfo info;
    private final BigDecimal unit;
    /** Rounding of the filed amounts in full units: the declared rounding (also when the amounts are written in full). */
    private final BigDecimal precision;
    /** Multiplier from the filed per-share figures (EPS) to currency units per share; 1 unless filed in the rounding unit. */
    private final BigDecimal epsScale;
    private final IdxTaxonomy taxonomy;
    /** Financial and Sharia Industry taxonomy (banks): own statements and line items. */
    private final boolean financial;
    private final StatementTable balanceSheet;
    private final StatementTable income;
    private final StatementTable cashFlow;
    private final List<String> templateProblems = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public FilingMapper(IdxWorkbook workbook) {
        this.workbook = workbook;
        this.info = FilingInfo.from(workbook);
        this.taxonomy = workbook.taxonomy();
        this.financial = taxonomy == IdxTaxonomy.FINANCIAL;
        if (financial) {
            this.balanceSheet = data(IdxSheets.BALANCE_SHEET_LIQUIDITY).orElse(null);
            this.income = data(IdxSheets.INCOME_BY_NATURE_BEFORE_TAX).or(() -> data(IdxSheets.INCOME_BY_NATURE)).orElse(null);
        } else {
            this.balanceSheet = data(IdxSheets.BALANCE_SHEET).orElse(null);
            this.income = data(IdxSheets.INCOME_BY_FUNCTION).or(() -> data(IdxSheets.INCOME_BY_FUNCTION_BEFORE_TAX)).orElse(null);
        }
        this.cashFlow = data(IdxSheets.CASH_FLOW_DIRECT).or(() -> data(IdxSheets.CASH_FLOW_INDIRECT)).orElse(null);
        Units units = resolveUnits(info.unitMultiplier());
        this.unit = units.amounts();
        this.epsScale = units.eps();
        this.precision = info.unitMultiplier();
        if (balanceSheet == null) {
            String supported = financial ? IdxSheets.BALANCE_SHEET_LIQUIDITY : IdxSheets.BALANCE_SHEET;
            String other = financial ? IdxSheets.BALANCE_SHEET : IdxSheets.BALANCE_SHEET_LIQUIDITY;
            templateProblems.add(data(other).isPresent()
                    ? "Balance sheet uses the " + (financial ? "current / non-current" : "order-of-liquidity") + " template ("
                    + code(other) + "), which is not supported for the " + taxonomyName() + " taxonomy"
                    : "No balance sheet data (" + code(supported) + ")");
        }
        if (income == null) {
            List<String> supported = financial
                    ? List.of(IdxSheets.INCOME_BY_NATURE, IdxSheets.INCOME_BY_NATURE_BEFORE_TAX)
                    : List.of(IdxSheets.INCOME_BY_FUNCTION, IdxSheets.INCOME_BY_FUNCTION_BEFORE_TAX);
            List<String> other = financial
                    ? List.of(IdxSheets.INCOME_BY_FUNCTION, IdxSheets.INCOME_BY_FUNCTION_BEFORE_TAX)
                    : List.of(IdxSheets.INCOME_BY_NATURE, IdxSheets.INCOME_BY_NATURE_BEFORE_TAX);
            templateProblems.add(other.stream().anyMatch(s -> data(s).isPresent())
                    ? "Profit or loss uses the '" + (financial ? "by function" : "by nature") + "' template ("
                    + code(other.get(0)) + " / " + code(other.get(1)) + "), which is not supported for the "
                    + taxonomyName() + " taxonomy"
                    : "No profit or loss data (" + code(supported.get(0)) + " / " + code(supported.get(1)) + ")");
        }
        if (cashFlow == null) {
            templateProblems.add("No cash flow data (" + code(IdxSheets.CASH_FLOW_DIRECT) + " / "
                    + code(IdxSheets.CASH_FLOW_INDIRECT) + ")");
        }
    }

    public IdxTaxonomy taxonomy() {
        return taxonomy;
    }

    /** Multiplier from the filed amounts to full currency units (the declared rounding unless contradicted). */
    public BigDecimal unit() {
        return unit;
    }

    /** Notes about the filing that do not block it, e.g. a corrected rounding level. */
    public List<String> warnings() {
        return warnings;
    }

    /** Fewest / most shares a listed company could have; an EPS-implied count outside means a wrong unit. */
    private static final BigDecimal MIN_PLAUSIBLE_SHARES = new BigDecimal("1e6");
    private static final BigDecimal MAX_PLAUSIBLE_SHARES = new BigDecimal("1e13");

    /** Units of the filed figures: amounts and per-share figures (EPS). */
    private record Units(BigDecimal amounts, BigDecimal eps) {
    }

    /**
     * The rounding unit the amounts are actually in. Some workbooks declare a rounding level but carry full
     * amounts (ASGR FY2023: "Jutaan / In Million", total assets 2,682,813,000,000 instead of 2,682,813); taken at
     * its word, every amount would be stored a million times too large. The declared unit is replaced by 1 when
     * every amount of the three statements (at least 10, EPS excluded) is a whole multiple of it, which a filing
     * in that unit practically never is, and the filing's own EPS confirms it: profit attributable to the parent
     * / basic EPS must give a plausible share count with the corrected unit and an impossible one with the
     * declared unit.
     * <p>
     * Other workbooks apply the rounding to the EPS as well (SIMP H1 2026: basic EPS 0.0000563514954 for Rp 56.35,
     * amounts correctly in millions): profit / EPS then gives the real share count (15.5 billion) from the filed
     * figures, the declared unit an impossible one, but the amounts are not whole multiples of the unit. The
     * per-share figures are then scaled by the unit. A unit that leaves the EPS-implied share count impossible
     * either way is a template problem.
     */
    private Units resolveUnits(BigDecimal declared) {
        BigDecimal impliedFull = impliedSharesPerUnit();   // shares if amounts and EPS were in the same unit
        if (declared.compareTo(BigDecimal.ONE) > 0 && impliedFull != null && plausibleShares(impliedFull)
                && !plausibleShares(impliedFull.multiply(declared))) {
            String shares = impliedFull.setScale(0, RoundingMode.HALF_UP).toPlainString();
            if (allAmountsMultipleOf(declared)) {
                warnings.add("The workbook declares '" + info.rounding() + "' but its amounts are full amounts (every amount is a "
                        + "multiple of " + declared.toPlainString() + " and the EPS implies " + shares + " shares); amounts are "
                        + "read as full amounts");
                return new Units(BigDecimal.ONE, BigDecimal.ONE);
            }
            warnings.add("The workbook's EPS is filed in its rounding unit ('" + info.rounding() + "': basic EPS "
                    + income.value(EPS_BASIC, 0).toPlainString() + " implies " + shares + " shares only when multiplied by "
                    + declared.toPlainString() + "); per-share figures are multiplied by " + declared.toPlainString());
            return new Units(declared, declared);
        }
        if (impliedFull != null && !plausibleShares(impliedFull.multiply(declared))) {
            templateProblems.add("Amounts contradict the declared rounding '" + info.rounding() + "': profit / basic EPS implies "
                    + impliedFull.multiply(declared).setScale(0, RoundingMode.HALF_UP).toPlainString() + " shares");
        }
        return new Units(declared, BigDecimal.ONE);
    }

    /** A per-share figure as filed, scaled to currency units per share. */
    private BigDecimal eps(String label, int ctx) {
        BigDecimal raw = income.value(label, ctx);
        if (raw == null || epsScale.compareTo(BigDecimal.ONE) == 0) {
            return raw;
        }
        BigDecimal v = raw.multiply(epsScale).stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    /** Profit attributable to the parent (as filed, unscaled) / basic EPS of the current period; null when not available. */
    private BigDecimal impliedSharesPerUnit() {
        if (income == null) {
            return null;
        }
        BigDecimal parent = income.value(PROFIT_PARENT, 0);
        BigDecimal eps = income.value(EPS_BASIC, 0);
        if (parent == null || eps == null || parent.signum() == 0 || eps.signum() == 0 || parent.signum() != eps.signum()) {
            return null;
        }
        return parent.divide(eps, MathContext.DECIMAL64);
    }

    private static boolean plausibleShares(BigDecimal shares) {
        return shares.compareTo(MIN_PLAUSIBLE_SHARES) >= 0 && shares.compareTo(MAX_PLAUSIBLE_SHARES) <= 0;
    }

    private boolean allAmountsMultipleOf(BigDecimal declared) {
        int amounts = 0;
        for (StatementTable t : java.util.Arrays.asList(balanceSheet, income, cashFlow)) {
            if (t == null) {
                continue;
            }
            for (StatementTable.Line line : t.lines()) {
                if (line.label().toLowerCase(Locale.ROOT).contains("per share")) {
                    continue;
                }
                for (BigDecimal v : line.values()) {
                    if (v == null || v.signum() == 0) {
                        continue;
                    }
                    if (v.remainder(declared).signum() != 0) {
                        return false;
                    }
                    amounts++;
                }
            }
        }
        return amounts >= 10;
    }

    public FilingInfo info() {
        return info;
    }

    /** Template problems that make the filing unusable (empty when supported). */
    public List<String> templateProblems() {
        return templateProblems;
    }

    public String balanceSheetSheet() {
        return balanceSheet == null ? null : balanceSheet.sheet();
    }

    public String incomeSheet() {
        return income == null ? null : income.sheet();
    }

    public String cashFlowSheet() {
        return cashFlow == null ? null : cashFlow.sheet();
    }

    // ------------------------------------------------------------------ columns

    public List<StatementColumn> columns() {
        return info.current().isFullYear()
                ? List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD)
                : List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD, StatementColumn.PRIOR_YEAR_END);
    }

    public PeriodRef period(StatementColumn column) {
        return switch (column) {
            case CURRENT_PERIOD -> info.current();
            case PRIOR_PERIOD -> info.prior();
            case PRIOR_YEAR_END -> info.priorYearEnd();
        };
    }

    /** Context index of the income statement / cash flow, -1 when the column has no flows. */
    public int durationIndex(StatementColumn column) {
        return switch (column) {
            case CURRENT_PERIOD -> 0;
            case PRIOR_PERIOD -> 1;
            case PRIOR_YEAR_END -> -1;
        };
    }

    /** Context index of the balance sheet, -1 when the column has no balance sheet. */
    public int instantIndex(StatementColumn column) {
        boolean annual = info.current().isFullYear();
        return switch (column) {
            case CURRENT_PERIOD -> 0;
            case PRIOR_PERIOD -> annual ? 1 : -1;      // annual filing: prior period end = prior year end
            case PRIOR_YEAR_END -> annual ? -1 : 1;
        };
    }

    // ------------------------------------------------------------------ income statement

    /** Expense categories (reported positive, subtracted on the way to profit before tax). */
    private static final List<IncomeLineCategory> EXPENSE_CATEGORIES = List.of(IncomeLineCategory.SELLING_EXPENSE,
            IncomeLineCategory.GENERAL_ADMINISTRATIVE_EXPENSE, IncomeLineCategory.OTHER_OPERATING_EXPENSE,
            IncomeLineCategory.FINANCE_COST, IncomeLineCategory.NON_OPERATING_EXPENSE, IncomeLineCategory.FINAL_TAX_EXPENSE);
    /** Income categories outside revenue (reported positive, added). */
    private static final List<IncomeLineCategory> INCOME_CATEGORIES = List.of(
            IncomeLineCategory.OTHER_OPERATING_INCOME, IncomeLineCategory.FINANCE_INCOME);

    /**
     * The income statement of a column. When profit before tax does not reconcile because the filing reports one
     * amount twice in the same column - as an expense or income line and again on a signed gains / losses line
     * (ASGR H1 2025 in its H1 2026 filing: "Other expenses" 2,750 and "Other gains (losses)" -2,750, profit before
     * tax 139,690 = the lines with the loss counted once), or on a specific line and again on a residual "Other ..."
     * line (CEKA H1 2025: "Interest and finance costs" and "Other expenses" 79,134) - the amount is counted once, on
     * the signed (specific) line, and a warning says so. Only applied when it makes profit before tax reconcile
     * exactly.
     */
    public Optional<MappedStatement> incomeStatement(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                     ShareCapital shares) {
        Optional<MappedStatement> filed = incomeStatementAsFiled(column, overrides, shares);
        if (filed.isEmpty() || filed.get().checks().stream().noneMatch(c -> c.isError() && c.rule().equals("profit_before_tax"))) {
            return filed;
        }
        for (String[] pair : doubleReportedLines(durationIndex(column), overrides)) {
            Map<String, IncomeLineCategory> once = new LinkedHashMap<>(overrides);
            once.put(pair[0], IncomeLineCategory.IGNORE);
            MappedStatement retry = incomeStatementAsFiled(column, once, shares).orElseThrow();
            if (retry.checks().stream().noneMatch(Check::isError)) {
                List<Check> checks = new ArrayList<>(retry.checks());
                checks.add(Check.warning("double_reported", "'" + pair[0] + "' and '" + pair[1]
                        + "' report the same amount in this column; counted once, as '" + pair[1] + "'"));
                Map<String, String> how = new LinkedHashMap<>(retry.derivations());
                how.put("double_reported", "'" + pair[0] + "' ignored: the same amount is reported on '" + pair[1]
                        + "' (profit before tax only reconciles with it counted once)");
                return Optional.of(new MappedStatement(retry.table(), retry.column(), retry.period(), retry.sourceSheet(),
                        retry.values(), how, checks, retry.unclassified()));
            }
        }
        return filed;
    }

    /**
     * Pairs {unsigned line, signed line} of one column that carry the same contribution to profit before tax: an
     * expense (or income) line and a signed gains / losses line of the opposite (same) sign and equal size.
     */
    private List<String[]> doubleReportedLines(int ctx, Map<String, IncomeLineCategory> overrides) {
        Map<String, IncomeLineCategory> known = financial ? IncomeLineCategory.FINANCIAL_KNOWN : IncomeLineCategory.KNOWN;
        Map<String, BigDecimal> unsigned = new LinkedHashMap<>();   // label -> contribution to profit before tax
        Map<String, BigDecimal> signed = new LinkedHashMap<>();
        for (StatementTable.Line line : income.lines()) {
            BigDecimal raw = line.value(ctx);
            IncomeLineCategory category = overrides.getOrDefault(line.label(), known.get(line.label()));
            if (raw == null || raw.signum() == 0 || category == null || overrides.containsKey(line.label())) {
                continue;   // a classification given by the agent is never second-guessed
            }
            if (EXPENSE_CATEGORIES.contains(category)) {
                unsigned.putIfAbsent(line.label(), raw.negate());
            } else if (INCOME_CATEGORIES.contains(category)) {
                unsigned.putIfAbsent(line.label(), raw);
            } else if (category == IncomeLineCategory.NON_OPERATING_GAIN_OR_LOSS) {
                signed.putIfAbsent(line.label(), raw);
            }
        }
        List<String[]> pairs = new ArrayList<>();
        unsigned.forEach((u, contribution) -> signed.forEach((s, value) -> {
            if (contribution.compareTo(value) == 0) {
                pairs.add(new String[] {u, s});
            }
        }));
        // two expense (income) lines with the same amount, one of them a residual "Other ..." line (CEKA H1 2025:
        // "Interest and finance costs" 79,134 and "Other expenses" 79,134; interest paid 79,134): the residual
        // line is the repeat
        unsigned.forEach((a, ca) -> unsigned.forEach((b, cb) -> {
            if (!a.equals(b) && ca.compareTo(cb) == 0 && isResidual(a) && !isResidual(b)) {
                pairs.add(new String[] {a, b});
            }
        }));
        return pairs;
    }

    /** "Other income", "Other expenses", ...: a catch-all line of the template. */
    private static boolean isResidual(String label) {
        return label.toLowerCase(Locale.ROOT).startsWith("other ");
    }

    private Optional<MappedStatement> incomeStatementAsFiled(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                             ShareCapital shares) {
        int ctx = durationIndex(column);
        if (ctx < 0 || income == null || !income.hasData(ctx)) {
            return Optional.empty();
        }
        Map<IncomeLineCategory, BigDecimal> sums = new LinkedHashMap<>();
        Map<IncomeLineCategory, List<String>> sources = new LinkedHashMap<>();
        List<UnclassifiedLine> unclassified = new ArrayList<>();
        Map<String, IncomeLineCategory> known = financial ? IncomeLineCategory.FINANCIAL_KNOWN : IncomeLineCategory.KNOWN;
        int profitIndex = income.indexOf(PROFIT);
        for (int i = 0; i < income.lines().size(); i++) {
            StatementTable.Line line = income.lines().get(i);
            BigDecimal raw = line.value(ctx);
            if (raw == null || STRUCTURAL_INCOME_LINES.contains(line.label())) {
                continue;
            }
            IncomeLineCategory category = overrides.getOrDefault(line.label(), known.get(line.label()));
            if (category == null) {
                if (profitIndex >= 0 && i > profitIndex) {
                    continue;   // other comprehensive income / comprehensive totals: not profit or loss
                }
                unclassified.add(new UnclassifiedLine(line.label(), amount(raw)));
                continue;
            }
            if (category != IncomeLineCategory.IGNORE) {
                sums.merge(category, amount(raw), BigDecimal::add);
                sources.computeIfAbsent(category, k -> new ArrayList<>()).add(line.label());
            }
        }
        if (financial) {
            return Optional.of(financialIncomeStatement(column, ctx, sums, sources, unclassified, shares));
        }

        BigDecimal revenue = sums.get(IncomeLineCategory.REVENUE);
        BigDecimal cost = sums.get(IncomeLineCategory.COST_OF_REVENUE);
        BigDecimal grossReported = amount(income.value(GROSS_PROFIT, ctx));
        BigDecimal selling = nz(sums.get(IncomeLineCategory.SELLING_EXPENSE));
        BigDecimal ga = nz(sums.get(IncomeLineCategory.GENERAL_ADMINISTRATIVE_EXPENSE));
        BigDecimal otherIncome = nz(sums.get(IncomeLineCategory.OTHER_OPERATING_INCOME));
        BigDecimal otherExpense = nz(sums.get(IncomeLineCategory.OTHER_OPERATING_EXPENSE));
        BigDecimal financeIncome = nz(sums.get(IncomeLineCategory.FINANCE_INCOME));
        BigDecimal financeCost = nz(sums.get(IncomeLineCategory.FINANCE_COST));
        BigDecimal nonOperating = nz(sums.get(IncomeLineCategory.NON_OPERATING_GAIN_OR_LOSS))
                .subtract(nz(sums.get(IncomeLineCategory.NON_OPERATING_EXPENSE)));
        BigDecimal finalTax = nz(sums.get(IncomeLineCategory.FINAL_TAX_EXPENSE));
        BigDecimal pretax = amount(income.value(PROFIT_BEFORE_TAX, ctx));
        BigDecimal tax = amount(income.value(TAX, ctx));
        BigDecimal discontinued = amount(income.value(PROFIT_DISCONTINUED, ctx));
        BigDecimal net = amount(income.value(PROFIT, ctx));
        BigDecimal parent = amount(income.value(PROFIT_PARENT, ctx));
        BigDecimal nci = amount(income.value(PROFIT_NCI, ctx));

        List<Check> checks = new ArrayList<>();
        Map<String, String> how = new LinkedHashMap<>();
        if (revenue == null && cost == null && grossReported != null) {
            // some filers tag only the gross profit (SIMP FY2023): the statement still reconciles from it; revenue and
            // cost stay empty here and are filled by a later filing's comparative (stored values are never replaced)
            checks.add(Check.warning("revenue", "'Sales and revenue' and its cost are not tagged in this column (only '"
                    + GROSS_PROFIT + "'); revenue and cost of revenue are left empty"));
            how.put("revenue", "not tagged in this filing (only gross profit); a later filing's comparative fills it");
        } else if (revenue == null) {
            checks.add(Check.error("revenue", "No 'Sales and revenue' reported"));
        }
        BigDecimal gross = grossReported;
        if (revenue != null && cost != null) {
            BigDecimal computed = revenue.subtract(cost);
            if (gross == null) {
                gross = computed;
            } else {
                checks.add(equal("gross_profit", "revenue - cost of revenue = gross profit", computed, gross));
            }
        }
        BigDecimal operatingIncome = gross == null ? null
                : gross.subtract(selling).subtract(ga).add(otherIncome).subtract(otherExpense);
        if (pretax == null || net == null) {
            checks.add(Check.error("profit", "Profit before tax or total profit is not reported"));
        }
        if (operatingIncome != null && pretax != null) {
            checks.add(equal("profit_before_tax",
                    "operating income + finance income - finance costs + non-operating items - final tax = profit before tax",
                    operatingIncome.add(financeIncome).subtract(financeCost).add(nonOperating).subtract(finalTax), pretax));
        }
        profitChecks(checks, pretax, tax, discontinued, net, parent, nci);

        BigDecimal ebit = pretax == null ? null : pretax.add(financeCost).subtract(financeIncome);
        BigDecimal depreciation = depreciation(column);
        BigDecimal amortization = amortization(column);
        BigDecimal ebitda = ebit == null || depreciation == null || amortization == null ? null
                : ebit.add(depreciation).add(amortization);

        BigDecimal basicEps = sumNullable(eps(EPS_BASIC, ctx), eps(EPS_BASIC_DISC, ctx));
        BigDecimal dilutedEps = sumNullable(eps(EPS_DILUTED, ctx), eps(EPS_DILUTED_DISC, ctx));
        BigDecimal basicShares = shares == null ? null
                : column == StatementColumn.CURRENT_PERIOD ? shares.weightedCurrent() : shares.weightedPrior();

        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("revenue", revenue);
        v.put("cost_of_revenue", cost);
        v.put("gross_profit", gross);
        v.put("operating_expenses", selling.add(ga).add(otherExpense));
        v.put("sga_expense", selling.add(ga));
        v.put("rd_expense", null);
        v.put("depreciation", depreciation);
        v.put("amortization", amortization);
        v.put("operating_income", operatingIncome);
        v.put("ebit", ebit);
        v.put("ebitda", ebitda);
        v.put("interest_income", financeIncome);
        v.put("interest_expense", financeCost);
        v.put("pretax_income", pretax);
        v.put("income_tax", tax == null ? null : tax.negate());
        v.put("net_income", net);
        v.put("net_income_to_parent", parent);
        v.put("basic_eps", basicEps);
        v.put("diluted_eps", dilutedEps);
        v.put("basic_shares", basicShares);
        v.put("diluted_shares", null);

        how.put("operating_expenses", "selling + G&A + other operating expenses " + sources(sources,
                IncomeLineCategory.SELLING_EXPENSE, IncomeLineCategory.GENERAL_ADMINISTRATIVE_EXPENSE,
                IncomeLineCategory.OTHER_OPERATING_EXPENSE));
        how.put("operating_income", "gross profit - selling - G&A + other operating income - other operating expenses");
        how.put("ebit", "profit before tax + finance costs - finance income");
        how.put("depreciation", depreciation == null ? "not disclosed for this column"
                : "PP&E + right-of-use additions to accumulated depreciation (roll-forward notes)");
        how.put("amortization", amortization == null ? "not derivable for this column"
                : "intangibles opening + purchases - closing");
        how.put("income_tax", "tax expense, sign flipped from 'Tax benefit (expenses)'");
        how.put("basic_shares", basicShares == null ? "not derivable" : "common stock / par value (unchanged in the period)");
        if (basicEps != null && parent != null && basicShares != null && basicShares.signum() != 0) {
            checks.add(epsCheck(parent.divide(basicShares, MathContext.DECIMAL64), basicEps));
        }
        return Optional.of(new MappedStatement("income_statement", column, period(column), income.sheet(),
                v, how, checks, unclassified));
    }

    /**
     * Profit or loss of the Financial and Sharia Industry template (banks). A bank has no sales, cost of
     * sales or financing costs in the industrial sense: revenue = interest and sharia income + fee,
     * commission, trading and other operating income; cost of revenue = interest expense; gross profit =
     * revenue less interest expense (net operating revenue). The reported "Total profit from operation"
     * must be re-added from the classified lines. EBIT and EBITDA are left empty: interest is a bank's
     * operating revenue and cost, so "earnings before interest" (and EV / EBITDA) has no meaning.
     */
    private MappedStatement financialIncomeStatement(StatementColumn column, int ctx, Map<IncomeLineCategory, BigDecimal> sums,
                                                     Map<IncomeLineCategory, List<String>> sources,
                                                     List<UnclassifiedLine> unclassified, ShareCapital shares) {
        BigDecimal revenue = sums.get(IncomeLineCategory.REVENUE);
        BigDecimal cost = sums.get(IncomeLineCategory.COST_OF_REVENUE);
        BigDecimal selling = nz(sums.get(IncomeLineCategory.SELLING_EXPENSE));
        BigDecimal ga = nz(sums.get(IncomeLineCategory.GENERAL_ADMINISTRATIVE_EXPENSE));
        BigDecimal otherIncome = nz(sums.get(IncomeLineCategory.OTHER_OPERATING_INCOME));
        BigDecimal otherExpense = nz(sums.get(IncomeLineCategory.OTHER_OPERATING_EXPENSE));
        BigDecimal financeIncome = nz(sums.get(IncomeLineCategory.FINANCE_INCOME));
        BigDecimal financeCost = nz(sums.get(IncomeLineCategory.FINANCE_COST));
        BigDecimal nonOperating = nz(sums.get(IncomeLineCategory.NON_OPERATING_GAIN_OR_LOSS))
                .subtract(nz(sums.get(IncomeLineCategory.NON_OPERATING_EXPENSE)));
        BigDecimal finalTax = nz(sums.get(IncomeLineCategory.FINAL_TAX_EXPENSE));
        BigDecimal operatingReported = amount(income.value(PROFIT_FROM_OPERATION, ctx));
        BigDecimal pretax = amount(income.value(PROFIT_BEFORE_TAX, ctx));
        BigDecimal tax = amount(income.value(TAX, ctx));
        BigDecimal discontinued = amount(income.value(PROFIT_DISCONTINUED, ctx));
        BigDecimal net = amount(income.value(PROFIT, ctx));
        BigDecimal parent = amount(income.value(PROFIT_PARENT, ctx));
        BigDecimal nci = amount(income.value(PROFIT_NCI, ctx));

        List<Check> checks = new ArrayList<>();
        if (revenue == null) {
            checks.add(Check.error("revenue", "No interest income or other operating income reported"));
        }
        BigDecimal gross = revenue == null ? null : revenue.subtract(nz(cost));
        BigDecimal operatingComputed = gross == null ? null
                : gross.subtract(selling).subtract(ga).add(otherIncome).subtract(otherExpense);
        if (operatingComputed != null && operatingReported != null) {
            checks.add(equal("operating_income",
                    "revenue - interest expense - operating expenses and impairment + recoveries = total profit from operation",
                    operatingComputed, operatingReported));
        } else if (operatingReported == null) {
            checks.add(Check.warning("operating_income", "'" + PROFIT_FROM_OPERATION + "' is not reported; computed from the lines"));
        }
        BigDecimal operatingIncome = operatingReported != null ? operatingReported : operatingComputed;
        if (pretax == null || net == null) {
            checks.add(Check.error("profit", "Profit before tax or total profit is not reported"));
        }
        if (operatingIncome != null && pretax != null) {
            checks.add(equal("profit_before_tax",
                    "profit from operation + non-operating items + finance income - finance costs - final tax = profit before tax",
                    operatingIncome.add(nonOperating).add(financeIncome).subtract(financeCost).subtract(finalTax), pretax));
        }
        profitChecks(checks, pretax, tax, discontinued, net, parent, nci);

        BigDecimal depreciation = depreciation(column);
        BigDecimal amortization = amortization(column);
        BigDecimal basicEps = sumNullable(eps(EPS_BASIC, ctx), eps(EPS_BASIC_DISC, ctx));
        BigDecimal dilutedEps = sumNullable(eps(EPS_DILUTED, ctx), eps(EPS_DILUTED_DISC, ctx));
        BigDecimal basicShares = shares == null ? null
                : column == StatementColumn.CURRENT_PERIOD ? shares.weightedCurrent() : shares.weightedPrior();

        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("revenue", revenue);
        v.put("cost_of_revenue", cost);
        v.put("gross_profit", gross);
        v.put("operating_expenses", selling.add(ga).add(otherExpense));
        v.put("sga_expense", selling.add(ga));
        v.put("rd_expense", null);
        v.put("depreciation", depreciation);
        v.put("amortization", amortization);
        v.put("operating_income", operatingIncome);
        v.put("ebit", null);
        v.put("ebitda", null);
        v.put("interest_income", amount(income.value("Interest income", ctx)));
        v.put("interest_expense", amount(income.value("Interest expenses", ctx)));
        v.put("pretax_income", pretax);
        v.put("income_tax", tax == null ? null : tax.negate());
        v.put("net_income", net);
        v.put("net_income_to_parent", parent);
        v.put("basic_eps", basicEps);
        v.put("diluted_eps", dilutedEps);
        v.put("basic_shares", basicShares);
        v.put("diluted_shares", null);

        Map<String, String> how = new LinkedHashMap<>();
        how.put("revenue", "bank: interest and sharia income + fee, commission, trading, FX and other operating income "
                + sources(sources, IncomeLineCategory.REVENUE));
        how.put("cost_of_revenue", "bank: interest expense (and the syirkah fund holders' share) "
                + sources(sources, IncomeLineCategory.COST_OF_REVENUE));
        how.put("gross_profit", "bank: revenue - interest expense (net operating revenue)");
        how.put("operating_expenses", "G&A + selling + impairment charges + other operating expenses " + sources(sources,
                IncomeLineCategory.SELLING_EXPENSE, IncomeLineCategory.GENERAL_ADMINISTRATIVE_EXPENSE,
                IncomeLineCategory.OTHER_OPERATING_EXPENSE));
        how.put("operating_income", operatingReported != null ? "'" + PROFIT_FROM_OPERATION + "' as reported"
                : "computed: gross profit - operating expenses + recoveries");
        how.put("ebit", "not applicable to a bank (interest is operating revenue and cost)");
        how.put("ebitda", "not applicable to a bank (interest is operating revenue and cost)");
        how.put("depreciation", depreciation == null ? "not disclosed for this column"
                : "PP&E (incl. right-of-use) + right-of-use additions to accumulated depreciation (roll-forward notes)");
        how.put("amortization", amortization == null ? "not derivable for this column"
                : "intangibles opening + net acquisitions - closing (the bank cash flow nets disposals)");
        how.put("income_tax", "tax expense, sign flipped from 'Tax benefit (expenses)'");
        how.put("basic_shares", basicShares == null ? "not derivable" : "common stock / par value (unchanged in the period)");
        if (basicEps != null && parent != null && basicShares != null && basicShares.signum() != 0) {
            checks.add(epsCheck(parent.divide(basicShares, MathContext.DECIMAL64), basicEps));
        }
        return new MappedStatement("income_statement", column, period(column), income.sheet(), v, how, checks, unclassified);
    }

    /** Profit before tax -> total profit -> attribution, shared by both income statement templates. */
    private void profitChecks(List<Check> checks, BigDecimal pretax, BigDecimal tax, BigDecimal discontinued,
                              BigDecimal net, BigDecimal parent, BigDecimal nci) {
        if (pretax != null && net != null) {
            checks.add(equal("net_income", "profit before tax + tax + discontinued operations = total profit",
                    pretax.add(nz(tax)).add(nz(discontinued)), net));
        }
        if (parent != null && nci != null && net != null) {
            checks.add(equal("attribution", "profit to parent + profit to NCI = total profit", parent.add(nci), net));
        } else if (parent == null) {
            checks.add(Check.warning("attribution", "Profit attributable to the parent is not reported"));
        }
    }

    /** Labels of income-statement lines with a value in the column that the mapper cannot classify. */
    public List<UnclassifiedLine> unclassifiedIncomeLines(StatementColumn column, Map<String, IncomeLineCategory> overrides) {
        return incomeStatement(column, overrides, null).map(MappedStatement::unclassified).orElse(List.of());
    }

    // ------------------------------------------------------------------ balance sheet

    public Optional<MappedStatement> balanceSheet(StatementColumn column, ShareCapital shares) {
        int ctx = instantIndex(column);
        if (ctx < 0 || balanceSheet == null || !balanceSheet.hasData(ctx)) {
            return Optional.empty();
        }
        if (financial) {
            return Optional.of(financialBalanceSheet(column, ctx, shares));
        }
        StatementTable b = balanceSheet;
        List<Check> checks = new ArrayList<>();
        Map<String, String> how = new LinkedHashMap<>();
        BigDecimal currentAssets = req(b, "Total current assets", ctx, checks);
        BigDecimal nonCurrentAssets = req(b, "Total non-current assets", ctx, checks);
        BigDecimal totalAssets = req(b, "Total assets", ctx, checks);
        BigDecimal currentLiabilities = req(b, "Total current liabilities", ctx, checks);
        BigDecimal nonCurrentLiabilities = req(b, "Total non-current liabilities", ctx, checks);
        BigDecimal totalLiabilities = req(b, "Total liabilities", ctx, checks);
        BigDecimal parentEquity = req(b, "Total equity attributable to equity owners of parent entity", ctx, checks);
        BigDecimal nci = sum(b, List.of("Non-controlling interests"), ctx);
        BigDecimal totalEquity = req(b, "Total equity", ctx, checks);
        BigDecimal liabilitiesAndEquity = amount(b.value("Total liabilities and equity", ctx));
        if (currentAssets != null && nonCurrentAssets != null && totalAssets != null) {
            checks.add(equal("assets", "current + non-current assets = total assets", currentAssets.add(nonCurrentAssets), totalAssets));
        }
        if (currentLiabilities != null && nonCurrentLiabilities != null && totalLiabilities != null) {
            checks.add(equal("liabilities", "current + non-current liabilities = total liabilities",
                    currentLiabilities.add(nonCurrentLiabilities), totalLiabilities));
        }
        if (totalLiabilities != null && totalEquity != null && totalAssets != null) {
            checks.add(equal("balance", "total liabilities + total equity = total assets", totalLiabilities.add(totalEquity), totalAssets));
        }
        if (liabilitiesAndEquity != null && totalAssets != null) {
            checks.add(equal("balance_total", "total liabilities and equity = total assets", liabilitiesAndEquity, totalAssets));
        }
        if (parentEquity != null && totalEquity != null) {
            checks.add(equal("equity", "parent equity + non-controlling interests = total equity", parentEquity.add(nci), totalEquity));
        }
        if (!b.duplicates().isEmpty()) {
            checks.add(Check.warning("duplicates", "Labels reported on more than one row: " + b.duplicates()));
        }

        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("cash_and_equivalents", sum(b, List.of("Cash and cash equivalents"), ctx));
        v.put("marketable_securities", sumOrNull(b, MARKETABLE_SECURITIES, ctx));
        v.put("accounts_receivable", sum(b, List.of("Trade receivables third parties", "Trade receivables related parties"), ctx));
        v.put("inventory", sum(b, List.of("Current inventories"), ctx));
        v.put("current_assets", currentAssets);
        v.put("total_assets", totalAssets);
        v.put("accounts_payable", sum(b, List.of("Trade payables third parties", "Trade payables related parties"), ctx));
        v.put("deferred_revenue", sum(b, List.of("Current advances from customers third parties",
                "Current advances from customers related parties", "Current contract liabilities", "Current deferred revenue"), ctx));
        v.put("current_liabilities", currentLiabilities);
        v.put("total_liabilities", totalLiabilities);
        v.put("short_term_debt", sum(b, SHORT_TERM_DEBT, ctx));
        v.put("long_term_debt", sum(b, LONG_TERM_DEBT, ctx));
        v.put("lease_liabilities", sum(b, LEASES, ctx));
        v.put("shareholders_equity", parentEquity);
        v.put("non_controlling_interest", nci);
        v.put("total_equity", totalEquity);
        v.put("retained_earnings", sum(b, List.of("Appropriated retained earnings", "Unappropriated retained earnings"), ctx));
        v.put("goodwill", sum(b, List.of("Goodwill"), ctx));
        v.put("intangible_assets", sum(b, List.of("Intangible assets other than goodwill"), ctx));
        BigDecimal outstanding = sharesOutstanding(column, shares);
        v.put("shares_outstanding", outstanding);

        how.put("accounts_receivable", "trade receivables (customer / pawn receivables excluded)");
        how.put("deferred_revenue", "advances from customers + contract liabilities + deferred revenue (current)");
        how.put("short_term_debt", "short-term loans + trust receipts + current maturities of borrowings, excl. leases "
                + present(b, SHORT_TERM_DEBT, ctx));
        how.put("long_term_debt", "long-term borrowings net of current maturities, excl. leases " + present(b, LONG_TERM_DEBT, ctx));
        how.put("lease_liabilities", "current + long-term finance lease liabilities");
        how.put("marketable_securities", "short-term investments + current financial assets at fair value (NULL when none)");
        how.put("shares_outstanding", sharesHow(outstanding, shares));
        return Optional.of(new MappedStatement("balance_sheet", column, period(column), b.sheet(), v, how, checks, List.of()));
    }

    private static String sharesHow(BigDecimal outstanding, ShareCapital shares) {
        if (outstanding == null) {
            return "not derivable";
        }
        return shares.webSource() != null
                ? "shares outstanding at period end from " + shares.webSource() + ", checked against the filing's EPS"
                : shares.parValue() != null ? "common stock / par value, no treasury shares" : shares.basis();
    }

    /** Shares outstanding at the column's balance-sheet date, from the statements of changes in equity. */
    private BigDecimal sharesOutstanding(StatementColumn column, ShareCapital shares) {
        LocalDate date = period(column).end();
        return shares == null ? null : shares.snapshots().stream()
                .filter(s -> s.date().equals(date)).map(ShareAt::sharesOutstanding).filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    /**
     * Balance sheet of the Financial and Sharia Industry template (order of liquidity, banks). It has no
     * current / non-current split, trade receivables, inventories or trade payables: those columns stay
     * empty, so the ratios built on them (current ratio, working capital, NCAV, receivable days) are not
     * computed for a bank. Customer deposits are not debt. Debt has no maturity split: all borrowings,
     * securities issued and subordinated loans are long_term_debt and short_term_debt stays empty, which
     * also leaves total debt, net debt and enterprise value empty (not meaningful for a bank).
     */
    private MappedStatement financialBalanceSheet(StatementColumn column, int ctx, ShareCapital shares) {
        StatementTable b = balanceSheet;
        List<Check> checks = new ArrayList<>();
        BigDecimal totalAssets = req(b, "Total assets", ctx, checks);
        BigDecimal liabilities = req(b, "Total liabilities", ctx, checks);
        BigDecimal syirkah = sum(b, List.of("Total temporary syirkah funds"), ctx);
        BigDecimal parentEquity = req(b, "Total equity attributable to equity owners of parent entity", ctx, checks);
        BigDecimal nci = sum(b, List.of("Non-controlling interests"), ctx);
        BigDecimal totalEquity = req(b, "Total equity", ctx, checks);
        BigDecimal liabilitiesAndEquity = amount(b.value("Total liabilities, temporary syirkah funds and equity", ctx));
        BigDecimal totalLiabilities = liabilities == null ? null : liabilities.add(syirkah);
        if (totalLiabilities != null && totalEquity != null && totalAssets != null) {
            checks.add(equal("balance", "total liabilities + temporary syirkah funds + total equity = total assets",
                    totalLiabilities.add(totalEquity), totalAssets));
        }
        if (liabilitiesAndEquity != null && totalAssets != null) {
            checks.add(equal("balance_total", "total liabilities, temporary syirkah funds and equity = total assets",
                    liabilitiesAndEquity, totalAssets));
        }
        if (parentEquity != null && totalEquity != null) {
            checks.add(equal("equity", "parent equity + non-controlling interests = total equity", parentEquity.add(nci), totalEquity));
        }
        if (!b.duplicates().isEmpty()) {
            checks.add(Check.warning("duplicates", "Labels reported on more than one row: " + b.duplicates()));
        }
        BigDecimal cash = cashEquivalentsAt(ctx, checks);
        BigDecimal securities = group(b, FIN_MARKETABLE_SECURITIES, ctx);
        if (securities != null) {
            securities = securities.subtract(sum(b, List.of("Allowance for impairment losses for marketable securities"), ctx));
        }
        BigDecimal debt = ZERO;
        for (List<String> g : FIN_DEBT) {
            debt = debt.add(nz(group(b, g, ctx)));
        }

        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("cash_and_equivalents", cash);
        v.put("marketable_securities", securities);
        v.put("accounts_receivable", null);
        v.put("inventory", null);
        v.put("current_assets", null);
        v.put("total_assets", totalAssets);
        v.put("accounts_payable", sumOrNull(b, List.of("Accounts payable"), ctx));
        v.put("deferred_revenue", sumOrNull(b, List.of("Contract liabilities", "Deferred income"), ctx));
        v.put("current_liabilities", null);
        v.put("total_liabilities", totalLiabilities);
        v.put("short_term_debt", null);
        v.put("long_term_debt", debt);
        v.put("lease_liabilities", sumOrNull(b, List.of("Finance lease liabilities"), ctx));
        v.put("shareholders_equity", parentEquity);
        v.put("non_controlling_interest", nci);
        v.put("total_equity", totalEquity);
        v.put("retained_earnings", sum(b, FIN_RETAINED_EARNINGS, ctx));
        v.put("goodwill", sum(b, List.of("Goodwill"), ctx));
        v.put("intangible_assets", sum(b, List.of("Intangible assets other than goodwill"), ctx));
        BigDecimal outstanding = sharesOutstanding(column, shares);
        v.put("shares_outstanding", outstanding);

        Map<String, String> how = new LinkedHashMap<>();
        how.put("cash_and_equivalents", cash == null ? "not reported: the bank balance sheet has no cash-equivalents total"
                : "cash and cash equivalents of the cash flow statement at this date (cash, current accounts with Bank "
                + "Indonesia and other banks, short placements); the bank balance sheet shows only 'Cash'");
        how.put("marketable_securities", "marketable securities less allowance for impairment (government bonds excluded)");
        how.put("accounts_receivable", "not applicable to a bank (loans are earning assets, not trade receivables)");
        how.put("inventory", "not applicable to a bank");
        how.put("current_assets", "not reported: a bank balance sheet is presented by order of liquidity");
        how.put("current_liabilities", "not reported: a bank balance sheet is presented by order of liquidity");
        how.put("total_liabilities", "total liabilities + temporary syirkah funds (non-equity funds of sharia depositors)");
        how.put("short_term_debt", "not reported: bank borrowings have no maturity split (all in long_term_debt)");
        how.put("long_term_debt", "borrowings + securities issued (bonds, sukuk, MTN, subordinated bonds) + subordinated "
                + "loans, all maturities; customer deposits, interbank deposits and repos excluded");
        how.put("lease_liabilities", "finance lease liabilities (NULL when not reported separately)");
        how.put("retained_earnings", "appropriated (general, legal and specific reserves) + unappropriated retained earnings");
        how.put("shares_outstanding", sharesHow(outstanding, shares));
        return new MappedStatement("balance_sheet", column, period(column), b.sheet(), v, how, checks, List.of());
    }

    /**
     * Cash and cash equivalents of the cash flow statement at a balance-sheet date: the end of the current
     * period for context 0, its beginning (= the prior year end) for context 1.
     */
    private BigDecimal cashEquivalentsAt(int instantCtx, List<Check> checks) {
        if (cashFlow == null) {
            return null;
        }
        if (instantCtx == 0) {
            return amount(cashFlow.value(CASH_END, 0));
        }
        BigDecimal opening = amount(cashFlow.value(CASH_BEGINNING, 0));
        BigDecimal priorEnding = info.current().isFullYear() ? amount(cashFlow.value(CASH_END, 1)) : null;
        if (opening != null && priorEnding != null) {
            checks.add(opening.compareTo(priorEnding) == 0
                    ? Check.ok("cash_opening", "opening cash = prior period ending cash")
                    : Check.warning("cash_opening", "opening cash " + opening + " differs from the prior period's ending cash "
                    + priorEnding + " (restated comparative)"));
        }
        return opening;
    }

    // ------------------------------------------------------------------ cash flow

    public Optional<MappedStatement> cashFlow(StatementColumn column) {
        int ctx = durationIndex(column);
        if (ctx < 0 || cashFlow == null || !cashFlow.hasData(ctx)) {
            return Optional.empty();
        }
        StatementTable c = cashFlow;
        List<Check> checks = new ArrayList<>();
        BigDecimal change = req(c, "Total net increase (decrease) in cash and cash equivalents", ctx, checks);
        BigDecimal[] totals = sectionTotals(c, ctx, change, checks);
        BigDecimal operating = totals[0];
        BigDecimal investing = totals[1];
        BigDecimal financing = totals[2];
        BigDecimal beginning = amount(c.value(CASH_BEGINNING, ctx));
        BigDecimal ending = req(c, CASH_END, ctx, checks);
        if (operating != null && investing != null && financing != null && change != null) {
            checks.add(equal("net_change", "operating + investing + financing = net change in cash",
                    operating.add(investing).add(financing), change));
        }
        if (beginning != null && change != null && ending != null) {
            BigDecimal other = sum(c, List.of("Effect of exchange rate changes on cash and cash equivalents",
                    "Cash and cash equivalent of deconsolidated subsidiaries",
                    "Other increase (decrease) in cash and cash equivalents"), ctx);
            checks.add(equal("cash_rollforward", "beginning cash + net change + FX / other effects = ending cash",
                    beginning.add(change).add(other), ending));
        }
        if (investing != null) {
            checks.addAll(section(c, ctx, "Cash flows from investing activities",
                    "Total net cash flows received from (used in) investing activities", investing, "investing"));
        }
        if (financing != null) {
            checks.addAll(section(c, ctx, "Cash flows from financing activities",
                    "Total net cash flows received from (used in) financing activities", financing, "financing"));
        }
        if (financial) {
            // no cash_vs_balance_sheet check: the bank balance sheet's cash is taken from this statement
            Map<String, String> how = new LinkedHashMap<>();
            return Optional.of(new MappedStatement("cash_flow_statement", column, period(column), c.sheet(),
                    financialCashFlowValues(c, ctx, operating, investing, financing, change, ending, how), how, checks, List.of()));
        }
        if (column == StatementColumn.CURRENT_PERIOD && balanceSheet != null && ending != null) {
            BigDecimal bsCash = amount(balanceSheet.value("Cash and cash equivalents", 0));
            if (bsCash != null && bsCash.compareTo(ending) != 0) {
                checks.add(Check.warning("cash_vs_balance_sheet", "Ending cash " + ending
                        + " differs from balance-sheet cash " + bsCash + " (e.g. bank overdrafts in cash)"));
            } else if (bsCash != null) {
                checks.add(Check.ok("cash_vs_balance_sheet", "ending cash = balance-sheet cash"));
            }
        }
        BigDecimal treasury = amount(c.value("Proceeds from sales (purchases) of treasury stocks", ctx));

        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("operating_cash_flow", operating);
        v.put("capital_expenditure", sum(c, CAPEX, ctx).negate());
        v.put("investing_cash_flow", investing);
        v.put("financing_cash_flow", financing);
        v.put("acquisitions", sum(c, ACQUISITIONS, ctx).negate());
        v.put("share_buybacks", treasury == null || treasury.signum() > 0 ? ZERO : treasury);
        v.put("stock_issuance", sum(c, STOCK_ISSUANCE, ctx));
        v.put("dividends_paid", sum(c, DIVIDENDS_PAID, ctx).negate());
        v.put("debt_issued", sum(c, DEBT_PROCEEDS, ctx));
        v.put("debt_repaid", sum(c, DEBT_REPAYMENTS, ctx).negate());
        v.put("lease_payments", sum(c, List.of("Payments of finance lease liabilities"), ctx).negate());
        v.put("cash_change", change);
        v.put("ending_cash", ending);
        Map<String, String> how = new LinkedHashMap<>();
        how.put("capital_expenditure", "-(PP&E + advances for PP&E + intangibles) " + present(c, CAPEX, ctx));
        how.put("debt_issued", "proceeds from borrowings and bonds " + present(c, DEBT_PROCEEDS, ctx));
        how.put("debt_repaid", "-(repayments of borrowings and bonds) " + present(c, DEBT_REPAYMENTS, ctx));
        how.put("dividends_paid", "-(dividends paid) " + present(c, DIVIDENDS_PAID, ctx));
        how.put("lease_payments", "-(finance lease principal payments)");
        how.put("stock_issuance", "proceeds from issuing shares of the parent (NCI contributions stay in financing only)");
        return Optional.of(new MappedStatement("cash_flow_statement", column, period(column), c.sheet(), v, how, checks, List.of()));
    }

    /**
     * Cash flow values of the Financial and Sharia Industry template (banks). Its investing lines are net
     * of disposals and signed ("Proceeds from disposal (acquisition) of property and equipment": negative =
     * net acquisition), and the net change in securities issued is one signed line.
     */
    private Map<String, BigDecimal> financialCashFlowValues(StatementTable c, int ctx, BigDecimal operating,
                                                            BigDecimal investing, BigDecimal financing, BigDecimal change,
                                                            BigDecimal ending, Map<String, String> how) {
        BigDecimal treasury = amount(c.value("Sales (purchase) of treasury stocks", ctx));
        BigDecimal securitiesIssued = nz(amount(c.value(FIN_SECURITIES_ISSUED_NET, ctx)));
        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("operating_cash_flow", operating);
        v.put("capital_expenditure", sum(c, FIN_CAPEX, ctx));
        v.put("investing_cash_flow", investing);
        v.put("financing_cash_flow", financing);
        v.put("acquisitions", sum(c, ACQUISITIONS, ctx).negate());
        v.put("share_buybacks", treasury == null || treasury.signum() > 0 ? ZERO : treasury);
        v.put("stock_issuance", sum(c, FIN_STOCK_ISSUANCE, ctx));
        v.put("dividends_paid", sum(c, DIVIDENDS_PAID, ctx).negate());
        v.put("debt_issued", sum(c, FIN_DEBT_PROCEEDS, ctx).add(securitiesIssued.max(ZERO)));
        v.put("debt_repaid", sum(c, FIN_DEBT_REPAYMENTS, ctx).negate().add(securitiesIssued.min(ZERO)));
        v.put("lease_payments", null);
        v.put("cash_change", change);
        v.put("ending_cash", ending);
        how.put("capital_expenditure", "bank: PP&E + intangibles acquisitions net of disposals, as filed (negative = outflow) "
                + present(c, FIN_CAPEX, ctx));
        how.put("debt_issued", "proceeds from borrowings, bonds, MTN, sukuk and subordinated loans + net increase in "
                + "securities issued " + present(c, FIN_DEBT_PROCEEDS, ctx));
        how.put("debt_repaid", "-(repayments of borrowings, bonds, MTN and subordinated loans) + net decrease in securities issued "
                + present(c, FIN_DEBT_REPAYMENTS, ctx));
        how.put("dividends_paid", "-(dividends paid) " + present(c, DIVIDENDS_PAID, ctx));
        how.put("share_buybacks", "purchases of treasury stocks (net sales of treasury stocks count as 0)");
        how.put("stock_issuance", "proceeds from new shares, capital contributions and employee stock options");
        how.put("lease_payments", "not reported separately in the bank template");
        return v;
    }

    private static final String[][] CASH_FLOW_SECTIONS = {
            {"operating", "Cash flows from operating activities", "Total net cash flows received from (used in) operating activities"},
            {"investing", "Cash flows from investing activities", "Total net cash flows received from (used in) investing activities"},
            {"financing", "Cash flows from financing activities", "Total net cash flows received from (used in) financing activities"}};

    /**
     * Operating, investing and financing totals. A section without any reported line and without a total (CEKA
     * H1 2026: no financing activity, operating 228,589,953,937 + investing -9,003,638,218 = net change
     * 219,586,315,719) counts as 0, but only when the other totals then add up to the net change exactly;
     * otherwise a missing total is an error.
     */
    private BigDecimal[] sectionTotals(StatementTable c, int ctx, BigDecimal change, List<Check> checks) {
        BigDecimal[] totals = new BigDecimal[3];
        List<Integer> empty = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            totals[s] = amount(c.value(CASH_FLOW_SECTIONS[s][2], ctx));
            if (totals[s] == null && sectionHasNoLines(c, ctx, CASH_FLOW_SECTIONS[s][1], CASH_FLOW_SECTIONS[s][2])) {
                empty.add(s);
            }
        }
        boolean allKnown = true;
        BigDecimal sum = ZERO;
        for (int s = 0; s < 3; s++) {
            if (totals[s] == null && !empty.contains(s)) {
                allKnown = false;
            }
            sum = sum.add(nz(totals[s]));
        }
        boolean emptyIsZero = !empty.isEmpty() && empty.size() < 3 && allKnown && change != null
                && !equal("net_change", "", sum, change).isError();
        for (int s = 0; s < 3; s++) {
            if (totals[s] != null) {
                continue;
            }
            if (emptyIsZero && empty.contains(s)) {
                totals[s] = ZERO;
                checks.add(Check.warning(CASH_FLOW_SECTIONS[s][0] + "_total", "No " + CASH_FLOW_SECTIONS[s][0]
                        + " cash flows are reported in this column (no lines, no total); counted as 0, the other totals "
                        + "add up to the net change in cash"));
            } else {
                checks.add(Check.error("required", "'" + CASH_FLOW_SECTIONS[s][2] + "' is not reported"));
            }
        }
        return totals;
    }

    /** True when the section's header and total are in the sheet and no line between them has a value in the column. */
    private static boolean sectionHasNoLines(StatementTable c, int ctx, String headerLabel, String totalLabel) {
        int from = c.indexOf(headerLabel);
        int to = c.indexOf(totalLabel);
        if (from < 0 || to <= from) {
            return false;
        }
        return c.lines().subList(from + 1, to).stream().noneMatch(l -> l.hasValue(ctx));
    }

    /** Signs every line of a cash flow section and checks it against the reported section total. */
    private List<Check> section(StatementTable c, int ctx, String headerLabel, String totalLabel, BigDecimal total, String name) {
        int from = c.indexOf(headerLabel);
        int to = c.indexOf(totalLabel);
        if (from < 0 || to < 0) {
            return List.of(Check.warning(name + "_section", "Section boundaries not found; section not re-added"));
        }
        BigDecimal sum = ZERO;
        List<String> unknown = new ArrayList<>();
        for (StatementTable.Line line : c.lines().subList(from + 1, to)) {
            BigDecimal raw = line.value(ctx);
            if (raw == null) {
                continue;
            }
            BigDecimal signed = signedFlow(line.label(), amount(raw));
            if (signed == null) {
                unknown.add(line.label());
            } else {
                sum = sum.add(signed);
            }
        }
        if (!unknown.isEmpty()) {
            return List.of(Check.error(name + "_section", "Cannot determine the cash direction of " + unknown));
        }
        return List.of(equal(name + "_section", "sum of " + name + " lines (payments negative) = " + name + " total", sum, total));
    }

    /** Cash direction of an IDX cash flow line: payments are entered positive, "(..)" labels are signed. */
    static BigDecimal signedFlow(String label, BigDecimal value) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains("(payments") || l.contains("(outflows)") || l.contains("(placement)") || l.contains("(paid)")
                || l.contains("(purchases)") || l.contains("(purchase)") || l.contains("(acquisition)")
                || l.contains("(increase)") || l.contains("(decrease)")) {
            return value;
        }
        // bank template: "Subordinated bonds issuance costs", "Issuance cost of mudharabah sukuk"
        if (l.startsWith("payment") || l.startsWith("purchases") || l.startsWith("placement")
                || l.startsWith("cash advances and loans made") || l.startsWith("dividends paid")
                || l.startsWith("interests paid") || l.startsWith("income taxes paid")
                || l.contains("issuance cost")) {
            return value.negate();
        }
        if (l.startsWith("proceed") || l.startsWith("receipts") || l.startsWith("withdrawal of")
                || l.startsWith("cash receipts") || l.startsWith("dividends received")
                || l.startsWith("interests received") || l.startsWith("subordinated bonds issued")
                || l.startsWith("issuance of")) {
            return value;
        }
        return null;
    }

    // ------------------------------------------------------------------ D&A

    /** PP&E + right-of-use depreciation of the column's period, {@code null} when not disclosed. */
    public BigDecimal depreciation(StatementColumn column) {
        String ppeSheet;
        String rouSheet;
        if (column == StatementColumn.CURRENT_PERIOD) {
            ppeSheet = IdxSheets.PPE;
            rouSheet = IdxSheets.RIGHT_OF_USE;
        } else if (column == StatementColumn.PRIOR_PERIOD && info.current().isFullYear()) {
            ppeSheet = IdxSheets.PPE_PRIOR_YEAR;     // *PY roll-forwards cover the full prior year
            rouSheet = IdxSheets.RIGHT_OF_USE_PRIOR_YEAR;
        } else {
            return null;                             // interim comparatives: no roll-forward in the filing
        }
        BigDecimal ppe = rollForwardAdditions(ppeSheet, "Property, plant, and equipment");
        BigDecimal rou = rollForwardAdditions(rouSheet, "Right of use assets");
        if (rou == null && !hasRightOfUseAssets(column)) {
            rou = ZERO;
        }
        return ppe == null || rou == null ? null : ppe.add(rou);
    }

    private boolean hasRightOfUseAssets(StatementColumn column) {
        if (balanceSheet == null) {
            return true;
        }
        int ctx = instantIndex(column) < 0 ? 0 : instantIndex(column);
        BigDecimal closing = balanceSheet.value("Right of use assets", ctx);
        BigDecimal opening = balanceSheet.value("Right of use assets", 1);
        return (closing != null && closing.signum() != 0) || (opening != null && opening.signum() != 0);
    }

    /** "Additions" column of the accumulated-depreciation total row of a roll-forward note. */
    private BigDecimal rollForwardAdditions(String sheetName, String totalLabel) {
        RawSheet sheet = workbook.sheet(sheetName).orElse(null);
        if (sheet == null) {
            return null;
        }
        int additionsCol = -1;
        int blockRow = -1;
        int blockCol = -1;
        for (int r = 0; r < sheet.rowCount(); r++) {
            for (int c = 0; c < sheet.width(r); c++) {
                String t = sheet.text(r, c);
                if (t == null) {
                    continue;
                }
                if (additionsCol < 0 && t.startsWith("Penambahan")) {
                    additionsCol = c;
                }
                if (blockRow < 0 && t.equals("Carrying amount, accumulated depreciation")) {
                    blockRow = r;
                    blockCol = c;
                }
            }
        }
        if (additionsCol < 0 || blockRow < 0) {
            return null;
        }
        int labelCol = blockCol - 1;
        for (int r = blockRow; r < sheet.rowCount(); r++) {
            if (totalLabel.equals(sheet.text(r, labelCol))) {
                BigDecimal additions = sheet.number(r, additionsCol);
                boolean rowHasData = false;
                for (int c = 2; c < labelCol; c++) {
                    rowHasData |= sheet.number(r, c) != null;
                }
                return additions != null ? amount(additions) : rowHasData ? ZERO : null;
            }
        }
        return null;
    }

    /**
     * Intangibles opening + purchases - closing; current column only (needs the opening balance). The bank
     * template reports acquisitions net of disposals in one signed line: a net disposal leaves the carrying
     * amount of what was sold unknown, so no amortization is derived then.
     */
    public BigDecimal amortization(StatementColumn column) {
        if (column != StatementColumn.CURRENT_PERIOD || balanceSheet == null || cashFlow == null) {
            return null;
        }
        BigDecimal purchases;
        if (financial) {
            BigDecimal net = amount(cashFlow.value(FIN_INTANGIBLES_NET, 0));
            if (net != null && net.signum() > 0) {
                return null;
            }
            purchases = net == null ? ZERO : net.negate();
        } else {
            BigDecimal disposals = cashFlow.value("Proceeds from disposal of intangible assets", 0);
            if (disposals != null && disposals.signum() != 0) {
                return null;    // carrying amount of disposals unknown
            }
            purchases = nz(amount(cashFlow.value("Payments for acquisition of intangible assets", 0)));
        }
        BigDecimal opening = nz(amount(balanceSheet.value("Intangible assets other than goodwill", 1)));
        BigDecimal closing = nz(amount(balanceSheet.value("Intangible assets other than goodwill", 0)));
        BigDecimal result = opening.add(purchases).subtract(closing);
        return result.signum() < 0 ? null : result;
    }

    // ------------------------------------------------------------------ revenue segments

    public Optional<SegmentExtraction> segments(StatementColumn column, BigDecimal incomeRevenue) {
        int side = durationIndex(column);
        // the Financial and Sharia Industry taxonomy has no revenue-by-type / -by-source notes
        if (side < 0 || financial) {
            return Optional.empty();
        }
        for (String sheetName : List.of(IdxSheets.REVENUE_BY_TYPE, IdxSheets.REVENUE_BY_SOURCE)) {
            Optional<SegmentExtraction> e = workbook.sheet(sheetName).flatMap(s -> segments(s, column, side, incomeRevenue));
            if (e.isPresent()) {
                return e;
            }
        }
        return Optional.empty();
    }

    private Optional<SegmentExtraction> segments(RawSheet sheet, StatementColumn column, int side, BigDecimal incomeRevenue) {
        // current template: one block per context, "CurrentYearDuration" above
        //   Indonesian slot | name | value | English slot
        // pre-2023 template: one table, period dates above the value columns
        //   Indonesian slot | name | value current | value prior | English slot
        int headerRow = -1;
        int nameCol = -1;
        int valueCol = -1;
        int englishCol = -1;
        String context = side == 0 ? "CurrentYearDuration" : "PriorYearDuration";
        for (int r = 0; r < sheet.rowCount() && headerRow < 0; r++) {
            for (int c = 0; c < sheet.width(r); c++) {
                if (context.equals(sheet.text(r, c))) {
                    headerRow = r;
                    nameCol = c + 1;
                    valueCol = c + 2;
                    englishCol = c + 3;
                    break;
                }
            }
        }
        for (int r = 0; r < sheet.rowCount() && headerRow < 0; r++) {
            List<Integer> dates = new ArrayList<>();
            for (int c = 0; c < sheet.width(r); c++) {
                String t = sheet.text(r, c);
                if (t != null && StatementTable.isDateHeader(t)) {
                    dates.add(c);
                }
            }
            if (dates.size() > side && dates.size() >= 2) {
                headerRow = r;
                nameCol = dates.getFirst() - 1;
                valueCol = dates.get(side);
                englishCol = dates.getLast() + 1;
            }
        }
        if (headerRow < 0 || nameCol < 0) {
            return Optional.empty();
        }
        List<SegmentLine> lines = new ArrayList<>();
        List<String> slots = new ArrayList<>();    // Indonesian slot of each line, e.g. "Pendapatan dari ekspor 1"
        BigDecimal total = null;
        for (int r = headerRow + 1; r < sheet.rowCount(); r++) {
            String english = sheet.text(r, englishCol);
            BigDecimal value = sheet.number(r, valueCol);
            if (english == null || value == null) {
                continue;
            }
            if (english.equals("Type of revenue") || english.equals("Source of revenue")) {
                total = amount(value);
                continue;
            }
            String type = segmentType(english);
            if (type == null) {
                continue;   // subtotal (Service revenue / Product revenue / Domestic revenue / ...)
            }
            String name = sheet.text(r, nameCol);
            lines.add(new SegmentLine(name == null ? english : name, type, amount(value),
                    english.toLowerCase(Locale.ROOT).startsWith("other ")));
            String slot = nameCol > 0 ? sheet.text(r, nameCol - 1) : null;
            slots.add(slot == null ? english : slot);
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        List<Check> checks = new ArrayList<>();
        lines = distinctSegmentNames(lines, slots, checks);
        BigDecimal sum = lines.stream().map(SegmentLine::revenue).reduce(ZERO, BigDecimal::add);
        if (total != null) {
            checks.add(equal("segment_total", "sum of segments = reported total of the breakdown", sum, total));
        }
        if (incomeRevenue != null) {
            checks.add(equal("segment_revenue", "sum of segments = income statement revenue", sum, incomeRevenue));
        }
        return Optional.of(new SegmentExtraction(column, period(column), sheet.name(), lines, total, checks));
    }

    /**
     * Segments are stored by name, so one name on two lines of a breakdown would store only one of them. CEKA files
     * "Produk Palm Kernel" both as domestic revenue 2 (3,391,840,269,264) and as export revenue 1 (210,909,304,151):
     * such names get their slot as a qualifier, "Produk Palm Kernel (domestik)" / "Produk Palm Kernel (ekspor)";
     * names that occur once are kept as filed. A name that still repeats is an error (nothing is saved).
     */
    static List<SegmentLine> distinctSegmentNames(List<SegmentLine> lines, List<String> slots, List<Check> checks) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        lines.forEach(l -> occurrences.merge(l.name(), 1, Integer::sum));
        if (occurrences.values().stream().allMatch(n -> n == 1)) {
            return lines;
        }
        List<SegmentLine> named = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            SegmentLine l = lines.get(i);
            named.add(occurrences.get(l.name()) == 1 ? l
                    : new SegmentLine(l.name() + " (" + slotQualifier(slots.get(i)) + ")", l.filingType(), l.revenue(), l.residualSlot()));
        }
        Map<String, Integer> after = new LinkedHashMap<>();
        named.forEach(l -> after.merge(l.name(), 1, Integer::sum));
        List<String> repeated = after.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!repeated.isEmpty()) {
            checks.add(Check.error("segment_names", "Segment names repeat within the breakdown even with their slots: " + repeated));
        } else {
            checks.add(Check.warning("segment_names", "Names filed on several lines of the breakdown are qualified by their slot: "
                    + named.stream().filter(l -> !occurrences.containsKey(l.name())).map(SegmentLine::name).toList()));
        }
        return named;
    }

    /** "Pendapatan dari ekspor 1" -> "ekspor", "Pendapatan domestik lainnya" -> "domestik lainnya", "Export revenue 1" -> "export". */
    static String slotQualifier(String slot) {
        String s = slot.trim().replaceFirst("\\s+\\d+$", "");
        s = s.replaceFirst("(?i)^pendapatan( dari)?\\s+", "").replaceFirst("(?i)\\s+revenue$", "").replaceFirst("(?i)^revenue from\\s+", "");
        return s.isBlank() ? slot.trim() : s.toLowerCase(Locale.ROOT);
    }

    /** "Service revenue 2" / "Other service revenue" -> SERVICE; subtotals -> null. */
    static String segmentType(String englishLineId) {
        String l = englishLineId.toLowerCase(Locale.ROOT);
        boolean item = l.matches(".*\\s\\d+$") || l.startsWith("other ");
        if (!item) {
            return null;
        }
        if (l.contains("service")) {
            return "SERVICE";
        }
        if (l.contains("product")) {
            return "PRODUCT";
        }
        if (l.contains("domestic") || l.contains("export")) {
            return "GEOGRAPHY";
        }
        return null;
    }

    // ------------------------------------------------------------------ share capital

    public ShareCapital shareCapital() {
        List<Check> checks = new ArrayList<>();
        record Position(LocalDate date, BigDecimal common, BigDecimal treasury, String source) {
        }
        List<Position> positions = new ArrayList<>();
        Map<String, BigDecimal[]> startEnd = new LinkedHashMap<>();   // sheet -> {start common, end common}
        Map<String, BigDecimal[]> treasuryStartEnd = new LinkedHashMap<>();   // sheet -> {start, end}, 0 when none
        for (String sheetName : List.of(IdxSheets.EQUITY, IdxSheets.EQUITY_PRIOR_YEAR)) {
            RawSheet sheet = workbook.sheet(sheetName).orElse(null);
            if (sheet == null) {
                continue;
            }
            int headerRow = -1;
            int commonCol = -1;
            int treasuryCol = -1;
            for (int r = 0; r < sheet.rowCount() && headerRow < 0; r++) {
                for (int c = 0; c < sheet.width(r); c++) {
                    if ("Common stocks".equals(sheet.text(r, c))) {
                        headerRow = r;
                        commonCol = c;
                    }
                    if ("Treasury stocks".equals(sheet.text(r, c))) {
                        treasuryCol = c;
                    }
                }
            }
            if (commonCol < 0) {
                continue;
            }
            boolean currentYear = sheetName.equals(IdxSheets.EQUITY);
            LocalDate startDate = (currentYear ? info.current() : info.prior()).start().minusDays(1);
            LocalDate endDate = (currentYear ? info.current() : info.prior()).end();
            BigDecimal[] se = new BigDecimal[2];
            BigDecimal[] te = new BigDecimal[2];
            for (int r = headerRow + 1; r < sheet.rowCount(); r++) {
                String english = sheet.text(r, sheet.width(r) - 1);
                boolean start = "Equity position, beginning of the period".equals(english);
                boolean end = "Equity position, end of the period".equals(english);
                if (!start && !end) {
                    continue;
                }
                BigDecimal common = amount(sheet.number(r, commonCol));
                BigDecimal treasury = treasuryCol < 0 ? null : amount(sheet.number(r, treasuryCol));
                if (common == null) {
                    continue;
                }
                se[start ? 0 : 1] = common;
                te[start ? 0 : 1] = treasury == null ? ZERO : treasury;
                positions.add(new Position(start ? startDate : endDate, common, treasury,
                        sheetName + (start ? " beginning" : " end") + " of period"));
            }
            startEnd.put(sheetName, se);
            treasuryStartEnd.put(sheetName, te);
        }
        if (positions.isEmpty()) {
            checks.add(Check.warning("share_capital", "No share capital in the statements of changes in equity"));
            return new ShareCapital(null, null, List.of(), null, null, checks, null);
        }

        BigDecimal[] currentYear = startEnd.get(IdxSheets.EQUITY);
        BigDecimal[] priorYear = startEnd.get(IdxSheets.EQUITY_PRIOR_YEAR);
        BigDecimal par = inferParValue(StatementColumn.CURRENT_PERIOD, currentYear == null ? null : currentYear[1], checks);
        if (par == null) {
            par = inferParValue(StatementColumn.PRIOR_PERIOD, priorYear == null ? null : priorYear[1], checks);
        }
        EpsShares epsShares = null;
        String basis = null;
        if (par != null) {
            basis = "par value " + par.toPlainString();
            checks.add(Check.ok("par_value", "Inferred par value " + par.toPlainString() + " per share"));
        } else {
            epsShares = sharesFromEps(StatementColumn.CURRENT_PERIOD, currentYear, treasuryStartEnd.get(IdxSheets.EQUITY));
            if (epsShares == null) {
                epsShares = sharesFromEps(StatementColumn.PRIOR_PERIOD, priorYear, treasuryStartEnd.get(IdxSheets.EQUITY_PRIOR_YEAR));
            }
            if (epsShares != null) {
                basis = epsShares.basis();
                checks.add(Check.ok("shares_from_eps", "Par value not inferable (share capital in "
                        + info.currency() + "); shares outstanding from the EPS denominator: " + basis));
            } else {
                checks.add(Check.warning("par_value", "Par value could not be inferred uniquely from share capital and EPS, "
                        + "and the EPS is not precise enough to give the share count; share counts are left empty"));
            }
        }

        // weighted shares only for the period whose EPS gave the count: another period's EPS may use another
        // denominator (INDY's FY2022 EPS implies 5,210,191,995 shares, FY2023's exactly 5,202,692,000)
        BigDecimal weightedCurrent = epsShares == null ? weighted(currentYear, par)
                : epsShares.column() == StatementColumn.CURRENT_PERIOD ? epsShares.shares() : null;
        BigDecimal weightedPrior = epsShares == null ? weighted(priorYear, par)
                : epsShares.column() == StatementColumn.PRIOR_PERIOD ? epsShares.shares() : null;
        Map<LocalDate, ShareAt> byDate = new LinkedHashMap<>();
        for (Position p : positions) {
            boolean treasury = p.treasury() != null && p.treasury().signum() != 0;
            BigDecimal shares;
            if (epsShares != null) {
                // the EPS denominator excludes treasury shares: valid wherever share capital and treasury
                // stock are those of the period it was derived from
                shares = epsShares.matches(p.common(), p.treasury()) ? epsShares.shares() : null;
            } else {
                shares = par == null || treasury ? null : p.common().divide(par);
            }
            if (treasury && shares == null) {
                checks.add(Check.warning("treasury", "Treasury stock at " + p.date()
                        + "; the treasury share count is not in the filing, shares outstanding left empty"));
            }
            BigDecimal basic = p.date().equals(info.current().end()) ? weightedCurrent
                    : p.date().equals(info.prior().end()) ? weightedPrior : null;
            ShareAt existing = byDate.get(p.date());
            if (existing != null && existing.commonStock().compareTo(p.common()) != 0) {
                checks.add(Check.error("share_capital", "Share capital at " + p.date() + " differs between sheets"));
            }
            if (existing == null || (existing.basicShares() == null && basic != null)) {
                byDate.put(p.date(), new ShareAt(p.date(), p.common(), shares, treasury ? null : ZERO, basic, p.source()));
            }
        }
        return new ShareCapital(par, basis, List.copyOf(byDate.values()), weightedCurrent, weightedPrior, checks, null);
    }

    /** Largest relative gap accepted between the filing's basic EPS and profit / a published period-end count. */
    static final BigDecimal WEB_SHARES_EPS_TOLERANCE = new BigDecimal("0.02");

    /**
     * Share counts published on a website, for a filing whose statements give none ({@link ShareCapital#resolved()}
     * false). Only dates of this filing's statements of changes in equity are used, and a count is accepted only
     * when
     * <ul>
     *   <li>it is consistent in itself: outstanding + treasury = issued, when all three are published;</li>
     *   <li>it reproduces the filing's own basic EPS of the period it opens or closes: profit attributable to the
     *       parent / count is within half a unit of the EPS's last decimal plus 2% of it. EPS is per weighted
     *       share, so a period-end count differs slightly (BNGA FY2022: 0.2%); a wrong company, unit or a stock
     *       split not reflected in this filing is off by far more and is rejected.</li>
     * </ul>
     * The result keeps the filing's share capital; weighted shares stay unknown. Without an accepted count the
     * filing's own (unresolved) share capital is returned with the reasons.
     */
    public ShareCapital withWebShareCounts(ShareCapital filing, List<WebShareCount> counts, String source) {
        if (filing.resolved()) {
            return filing;
        }
        Map<LocalDate, WebShareCount> byDate = new LinkedHashMap<>();
        counts.forEach(c -> byDate.putIfAbsent(c.date(), c));
        List<Check> checks = new ArrayList<>();
        List<ShareAt> snapshots = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        for (ShareAt p : filing.snapshots()) {
            WebShareCount c = byDate.get(p.date());
            String rejection = c == null ? null : webShareCountProblem(c, p.date());
            if (c == null || rejection != null) {
                if (rejection != null) {
                    checks.add(Check.warning("web_shares", source + " count at " + p.date() + " not used: " + rejection));
                }
                snapshots.add(p);
                continue;
            }
            BigDecimal treasury = c.treasury() != null ? c.treasury()
                    : c.issued() != null ? c.issued().subtract(c.outstanding()) : null;
            snapshots.add(new ShareAt(p.date(), p.commonStock(), c.outstanding(), treasury, null,
                    source + " count at " + p.date() + ", checked against the filing's EPS"));
            accepted.add(p.date() + ": " + c.outstanding().toPlainString());
        }
        if (accepted.isEmpty()) {
            List<Check> unresolved = new ArrayList<>(filing.checks());
            unresolved.addAll(checks);
            if (checks.isEmpty()) {
                unresolved.add(Check.warning("web_shares", source + " has no share count at the dates of this filing"));
            }
            return new ShareCapital(null, null, filing.snapshots(), null, null, unresolved, null);
        }
        // the filing's warnings about missing counts no longer apply
        filing.checks().stream()
                .filter(c -> !(c.severity() == Check.Severity.WARNING && List.of("par_value", "treasury").contains(c.rule())))
                .forEach(checks::add);
        String basis = "shares outstanding from " + source + " (checked against the filing's EPS): " + String.join(", ", accepted);
        checks.add(Check.ok("web_shares", basis));
        return new ShareCapital(null, basis, List.copyOf(snapshots), null, null, checks, source);
    }

    /** Why a published count does not fit this filing, {@code null} when it does. */
    private String webShareCountProblem(WebShareCount c, LocalDate date) {
        if (c.outstanding() == null || c.outstanding().signum() <= 0) {
            return "no positive shares-outstanding figure";
        }
        if (c.issued() != null && c.treasury() != null && c.outstanding().add(c.treasury()).compareTo(c.issued()) != 0) {
            return "outstanding " + c.outstanding().toPlainString() + " + treasury " + c.treasury().toPlainString()
                    + " != issued " + c.issued().toPlainString();
        }
        if (c.issued() != null && c.issued().compareTo(c.outstanding()) < 0) {
            return "issued " + c.issued().toPlainString() + " < outstanding " + c.outstanding().toPlainString();
        }
        StatementColumn column = date.equals(info.current().end()) ? StatementColumn.CURRENT_PERIOD
                : date.equals(info.prior().end()) ? StatementColumn.PRIOR_PERIOD
                : date.equals(info.current().start().minusDays(1)) ? StatementColumn.CURRENT_PERIOD
                : date.equals(info.prior().start().minusDays(1)) ? StatementColumn.PRIOR_PERIOD : null;
        int ctx = column == null ? -1 : durationIndex(column);
        if (income == null || ctx < 0) {
            return "no profit or loss period of this filing opens or closes on that date";
        }
        BigDecimal parent = amount(income.value(PROFIT_PARENT, ctx));
        BigDecimal eps = sumNullable(eps(EPS_BASIC, ctx), eps(EPS_BASIC_DISC, ctx));
        if (parent == null || eps == null || parent.signum() == 0 || eps.signum() == 0 || parent.signum() != eps.signum()) {
            return "the " + period(column).key() + " profit attributable to the parent and basic EPS do not allow a check";
        }
        BigDecimal implied = parent.divide(c.outstanding(), MathContext.DECIMAL64);
        BigDecimal halfUlp = BigDecimal.ONE.movePointLeft(Math.max(0, eps.stripTrailingZeros().scale())).divide(BigDecimal.valueOf(2));
        BigDecimal tolerance = halfUlp.add(eps.abs().multiply(WEB_SHARES_EPS_TOLERANCE));
        if (implied.subtract(eps).abs().compareTo(tolerance) > 0) {
            return "profit attributable to the parent / count = " + implied.setScale(Math.max(0, eps.scale()) + 2, RoundingMode.HALF_UP)
                    .toPlainString() + " but the " + period(column).key() + " basic EPS is " + eps.toPlainString();
        }
        return null;
    }

    /**
     * Shares outstanding from the EPS denominator of one period, at the share capital and treasury
     * stock that held throughout it.
     */
    record EpsShares(StatementColumn column, BigDecimal shares, BigDecimal common, BigDecimal treasury, String basis) {

        boolean matches(BigDecimal otherCommon, BigDecimal otherTreasury) {
            BigDecimal t = otherTreasury == null ? ZERO : otherTreasury;
            return otherCommon != null && otherCommon.compareTo(common) == 0 && t.compareTo(treasury) == 0;
        }
    }

    /**
     * Fallback when no par value fits, e.g. a USD reporter whose share capital is the rupiah par value
     * converted at historical rates (INDY: share capital USD 56,892,154). Basic EPS
     * = profit attributable to the parent / weighted shares outstanding (treasury shares excluded), so
     * the share count is profit / EPS - but only when the result is exact:
     * <ul>
     *   <li>share capital and treasury stock unchanged through the period (weighted = outstanding),</li>
     *   <li>no discontinued operations (EPS is "from continuing operations"),</li>
     *   <li>the EPS has enough decimals to fix the count to within one share (INDY's 0.0019 does not:
     *       it allows 5.17 .. 5.45 billion shares),</li>
     *   <li>profit / EPS is a whole number (within 0.01; INDY FY2023: 119,683,800 / 0.0230042062839776 =
     *       5,202,692,000.0000005).</li>
     * </ul>
     */
    private EpsShares sharesFromEps(StatementColumn column, BigDecimal[] commonStartEnd, BigDecimal[] treasuryStartEnd) {
        int ctx = durationIndex(column);
        if (income == null || ctx < 0 || commonStartEnd == null || treasuryStartEnd == null
                || commonStartEnd[0] == null || commonStartEnd[1] == null
                || commonStartEnd[0].compareTo(commonStartEnd[1]) != 0
                || treasuryStartEnd[0] == null || treasuryStartEnd[1] == null
                || treasuryStartEnd[0].compareTo(treasuryStartEnd[1]) != 0) {
            return null;
        }
        BigDecimal discontinued = amount(income.value(PROFIT_DISCONTINUED, ctx));
        BigDecimal epsDiscontinued = eps(EPS_BASIC_DISC, ctx);
        if ((discontinued != null && discontinued.signum() != 0) || (epsDiscontinued != null && epsDiscontinued.signum() != 0)) {
            return null;
        }
        BigDecimal parent = amount(income.value(PROFIT_PARENT, ctx));
        BigDecimal eps = eps(EPS_BASIC, ctx);
        if (parent == null || eps == null || parent.signum() == 0 || eps.signum() == 0 || parent.signum() != eps.signum()) {
            return null;
        }
        // half a unit in the EPS's last decimal moves profit / EPS by about profit * halfUlp / EPS^2
        BigDecimal halfUlp = BigDecimal.ONE.movePointLeft(Math.max(0, eps.stripTrailingZeros().scale()))
                .divide(BigDecimal.valueOf(2));
        BigDecimal uncertainty = parent.abs().multiply(halfUlp).divide(eps.multiply(eps), 10, RoundingMode.HALF_UP);
        if (uncertainty.compareTo(new BigDecimal("0.5")) >= 0) {
            return null;
        }
        BigDecimal implied = parent.divide(eps, 6, RoundingMode.HALF_UP);
        BigDecimal shares = implied.setScale(0, RoundingMode.HALF_UP);
        if (shares.signum() <= 0 || implied.subtract(shares).abs().compareTo(new BigDecimal("0.01")) > 0) {
            return null;
        }
        return new EpsShares(column, shares, commonStartEnd[1], treasuryStartEnd[1],
                period(column).key() + ": profit attributable to the parent " + parent.toPlainString()
                        + " / basic EPS " + eps.toPlainString() + " = " + shares.toPlainString() + " shares outstanding");
    }

    /** Weighted shares = shares outstanding when share capital did not change during the period. */
    private static BigDecimal weighted(BigDecimal[] startEnd, BigDecimal par) {
        if (par == null || startEnd == null || startEnd[0] == null || startEnd[1] == null
                || startEnd[0].compareTo(startEnd[1]) != 0) {
            return null;
        }
        return startEnd[1].divide(par);
    }

    private BigDecimal inferParValue(StatementColumn column, BigDecimal commonStock, List<Check> checks) {
        int ctx = durationIndex(column);
        if (income == null || ctx < 0 || commonStock == null) {
            return null;
        }
        BigDecimal parent = amount(income.value(PROFIT_PARENT, ctx));
        BigDecimal eps = eps(EPS_BASIC, ctx);
        if (parent == null || eps == null || eps.signum() == 0) {
            return null;
        }
        int decimals = Math.max(0, eps.stripTrailingZeros().scale());
        BigDecimal tolerance = BigDecimal.ONE.movePointLeft(decimals).divide(BigDecimal.valueOf(2)).add(new BigDecimal("1e-9"));
        List<BigDecimal> matches = new ArrayList<>();
        for (BigDecimal par : STANDARD_PAR_VALUES) {
            BigDecimal shares = commonStock.divide(par, 10, RoundingMode.UNNECESSARY).stripTrailingZeros();
            if (shares.scale() > 0 || shares.signum() <= 0) {
                continue;
            }
            BigDecimal implied = parent.divide(shares, decimals + 6, RoundingMode.HALF_UP);
            if (implied.subtract(eps).abs().compareTo(tolerance) <= 0) {
                matches.add(par);
            }
        }
        if (matches.size() > 1) {
            checks.add(Check.warning("par_value", "Several par values fit " + column + ": " + matches));
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    // ------------------------------------------------------------------ helpers

    private Optional<StatementTable> table(String sheet) {
        return workbook.sheet(sheet).map(StatementTable::parse);
    }

    /** The statement sheet when it has data for the current period. */
    private Optional<StatementTable> data(String sheet) {
        return table(sheet).filter(t -> t.hasData(0));
    }

    /** Sheet code as filed in this taxonomy, for messages ("1220000" -> "4220000"). */
    private String code(String sheet) {
        return taxonomy.code(sheet);
    }

    private String taxonomyName() {
        return switch (taxonomy) {
            case GENERAL -> "General Industry";
            case INFRASTRUCTURE -> "Infrastructure Industry";
            case FINANCIAL -> "Financial and Sharia Industry";
        };
    }

    /**
     * A balance sheet group given as {header, parts...}: the sum of the parts when any is reported, else
     * the header line (filers report one or the other); {@code null} when neither is reported.
     */
    private BigDecimal group(StatementTable t, List<String> headerAndParts, int ctx) {
        List<String> parts = headerAndParts.subList(1, headerAndParts.size());
        BigDecimal p = sumOrNull(t, parts, ctx);
        return p != null ? p : amount(t.value(headerAndParts.get(0), ctx));
    }

    /** Reported value scaled to full currency units, normalised (12345.0 -> 12345, 0.50 -> 0.5). */
    private BigDecimal amount(BigDecimal raw) {
        if (raw == null) {
            return null;
        }
        BigDecimal v = raw.multiply(unit).stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    private BigDecimal req(StatementTable t, String label, int ctx, List<Check> checks) {
        BigDecimal v = amount(t.value(label, ctx));
        if (v == null) {
            checks.add(Check.error("required", "'" + label + "' is not reported"));
        }
        return v;
    }

    /** Sum of the reported lines, 0 when none is reported (a complete statement without the line). */
    private BigDecimal sum(StatementTable t, List<String> labels, int ctx) {
        BigDecimal s = ZERO;
        for (String label : labels) {
            BigDecimal v = amount(t.value(label, ctx));
            if (v != null) {
                s = s.add(v);
            }
        }
        return s;
    }

    private BigDecimal sumOrNull(StatementTable t, List<String> labels, int ctx) {
        boolean any = labels.stream().anyMatch(l -> t.value(l, ctx) != null);
        return any ? sum(t, labels, ctx) : null;
    }

    private static String present(StatementTable t, List<String> labels, int ctx) {
        List<String> used = labels.stream().filter(l -> t.value(l, ctx) != null).toList();
        return used.isEmpty() ? "(none reported)" : used.toString();
    }

    private static String sources(Map<IncomeLineCategory, List<String>> sources, IncomeLineCategory... categories) {
        List<String> labels = new ArrayList<>();
        for (IncomeLineCategory c : categories) {
            labels.addAll(sources.getOrDefault(c, List.of()));
        }
        return labels.toString();
    }

    private Check equal(String rule, String description, BigDecimal computed, BigDecimal reported) {
        BigDecimal diff = computed.subtract(reported).abs();
        // full-amount filings must match exactly; filings in thousands / millions may be off by one
        // reported unit because every line is rounded separately
        BigDecimal tolerance = precision.compareTo(BigDecimal.ONE) > 0 ? precision : ZERO;
        return diff.compareTo(tolerance) <= 0
                ? Check.ok(rule, description)
                : Check.error(rule, description + ": computed " + computed.toPlainString()
                + " vs reported " + reported.toPlainString() + " (difference " + diff.toPlainString() + ")");
    }

    private static Check epsCheck(BigDecimal implied, BigDecimal reported) {
        int decimals = Math.max(0, reported.stripTrailingZeros().scale());
        BigDecimal tolerance = BigDecimal.ONE.movePointLeft(decimals).divide(BigDecimal.valueOf(2)).add(new BigDecimal("1e-9"));
        return implied.subtract(reported).abs().compareTo(tolerance) <= 0
                ? Check.ok("eps", "profit to parent / basic shares = reported basic EPS")
                : Check.warning("eps", "profit to parent / basic shares = " + implied.setScale(decimals + 2, RoundingMode.HALF_UP)
                + " vs reported basic EPS " + reported);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static BigDecimal sumNullable(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return null;
        }
        return nz(a).add(nz(b));
    }
}
