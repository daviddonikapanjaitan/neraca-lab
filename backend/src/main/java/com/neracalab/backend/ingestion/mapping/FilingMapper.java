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

    private static final List<String> STRUCTURAL_INCOME_LINES = List.of(GROSS_PROFIT, PROFIT_BEFORE_TAX, TAX,
            PROFIT_CONTINUING, PROFIT_DISCONTINUED, PROFIT, PROFIT_PARENT, PROFIT_NCI,
            EPS_BASIC, EPS_BASIC_DISC, EPS_DILUTED, EPS_DILUTED_DISC);

    private static final List<String> SHORT_TERM_DEBT = List.of(
            "Short term bank loans", "Trust receipts payables",
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

    private final IdxWorkbook workbook;
    private final FilingInfo info;
    private final BigDecimal unit;
    private final StatementTable balanceSheet;
    private final StatementTable income;
    private final StatementTable cashFlow;
    private final List<String> templateProblems = new ArrayList<>();

    public FilingMapper(IdxWorkbook workbook) {
        this.workbook = workbook;
        this.info = FilingInfo.from(workbook);
        this.unit = info.unitMultiplier();
        this.balanceSheet = table(IdxSheets.BALANCE_SHEET).filter(t -> t.hasData(0)).orElse(null);
        this.income = table(IdxSheets.INCOME_BY_FUNCTION).filter(t -> t.hasData(0))
                .or(() -> table(IdxSheets.INCOME_BY_FUNCTION_BEFORE_TAX).filter(t -> t.hasData(0))).orElse(null);
        this.cashFlow = table(IdxSheets.CASH_FLOW_DIRECT).filter(t -> t.hasData(0))
                .or(() -> table(IdxSheets.CASH_FLOW_INDIRECT).filter(t -> t.hasData(0))).orElse(null);
        if (balanceSheet == null) {
            templateProblems.add(table(IdxSheets.BALANCE_SHEET_LIQUIDITY).filter(t -> t.hasData(0)).isPresent()
                    ? "Balance sheet uses the order-of-liquidity template (1220000), which is not supported"
                    : "No balance sheet data (1210000)");
        }
        if (income == null) {
            boolean byNature = table(IdxSheets.INCOME_BY_NATURE).filter(t -> t.hasData(0)).isPresent()
                    || table(IdxSheets.INCOME_BY_NATURE_BEFORE_TAX).filter(t -> t.hasData(0)).isPresent();
            templateProblems.add(byNature
                    ? "Profit or loss uses the 'by nature' template (1312000/1322000), which is not supported"
                    : "No profit or loss data (1311000 / 1321000)");
        }
        if (cashFlow == null) {
            templateProblems.add("No cash flow data (1510000 / 1520000)");
        }
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

    public Optional<MappedStatement> incomeStatement(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                     ShareCapital shares) {
        int ctx = durationIndex(column);
        if (ctx < 0 || income == null || !income.hasData(ctx)) {
            return Optional.empty();
        }
        Map<IncomeLineCategory, BigDecimal> sums = new LinkedHashMap<>();
        Map<IncomeLineCategory, List<String>> sources = new LinkedHashMap<>();
        List<UnclassifiedLine> unclassified = new ArrayList<>();
        int profitIndex = income.indexOf(PROFIT);
        for (int i = 0; i < income.lines().size(); i++) {
            StatementTable.Line line = income.lines().get(i);
            BigDecimal raw = line.value(ctx);
            if (raw == null || STRUCTURAL_INCOME_LINES.contains(line.label())) {
                continue;
            }
            IncomeLineCategory category = overrides.getOrDefault(line.label(), IncomeLineCategory.KNOWN.get(line.label()));
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

        BigDecimal revenue = sums.get(IncomeLineCategory.REVENUE);
        BigDecimal cost = sums.get(IncomeLineCategory.COST_OF_REVENUE);
        BigDecimal grossReported = amount(income.value(GROSS_PROFIT, ctx));
        BigDecimal selling = nz(sums.get(IncomeLineCategory.SELLING_EXPENSE));
        BigDecimal ga = nz(sums.get(IncomeLineCategory.GENERAL_ADMINISTRATIVE_EXPENSE));
        BigDecimal otherIncome = nz(sums.get(IncomeLineCategory.OTHER_OPERATING_INCOME));
        BigDecimal otherExpense = nz(sums.get(IncomeLineCategory.OTHER_OPERATING_EXPENSE));
        BigDecimal financeIncome = nz(sums.get(IncomeLineCategory.FINANCE_INCOME));
        BigDecimal financeCost = nz(sums.get(IncomeLineCategory.FINANCE_COST));
        BigDecimal nonOperating = nz(sums.get(IncomeLineCategory.NON_OPERATING_GAIN_OR_LOSS));
        BigDecimal finalTax = nz(sums.get(IncomeLineCategory.FINAL_TAX_EXPENSE));
        BigDecimal pretax = amount(income.value(PROFIT_BEFORE_TAX, ctx));
        BigDecimal tax = amount(income.value(TAX, ctx));
        BigDecimal discontinued = amount(income.value(PROFIT_DISCONTINUED, ctx));
        BigDecimal net = amount(income.value(PROFIT, ctx));
        BigDecimal parent = amount(income.value(PROFIT_PARENT, ctx));
        BigDecimal nci = amount(income.value(PROFIT_NCI, ctx));

        List<Check> checks = new ArrayList<>();
        Map<String, String> how = new LinkedHashMap<>();
        if (revenue == null) {
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
        if (pretax != null && net != null) {
            checks.add(equal("net_income", "profit before tax + tax + discontinued operations = total profit",
                    pretax.add(nz(tax)).add(nz(discontinued)), net));
        }
        if (parent != null && nci != null && net != null) {
            checks.add(equal("attribution", "profit to parent + profit to NCI = total profit", parent.add(nci), net));
        } else if (parent == null) {
            checks.add(Check.warning("attribution", "Profit attributable to the parent is not reported"));
        }

        BigDecimal ebit = pretax == null ? null : pretax.add(financeCost).subtract(financeIncome);
        BigDecimal depreciation = depreciation(column);
        BigDecimal amortization = amortization(column);
        BigDecimal ebitda = ebit == null || depreciation == null || amortization == null ? null
                : ebit.add(depreciation).add(amortization);

        BigDecimal basicEps = sumNullable(income.value(EPS_BASIC, ctx), income.value(EPS_BASIC_DISC, ctx));
        BigDecimal dilutedEps = sumNullable(income.value(EPS_DILUTED, ctx), income.value(EPS_DILUTED_DISC, ctx));
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
        LocalDate date = period(column).end();
        BigDecimal outstanding = shares == null ? null : shares.snapshots().stream()
                .filter(s -> s.date().equals(date)).map(ShareAt::sharesOutstanding).filter(Objects::nonNull)
                .findFirst().orElse(null);
        v.put("shares_outstanding", outstanding);

        how.put("accounts_receivable", "trade receivables (customer / pawn receivables excluded)");
        how.put("deferred_revenue", "advances from customers + contract liabilities + deferred revenue (current)");
        how.put("short_term_debt", "short-term loans + trust receipts + current maturities of borrowings, excl. leases "
                + present(b, SHORT_TERM_DEBT, ctx));
        how.put("long_term_debt", "long-term borrowings net of current maturities, excl. leases " + present(b, LONG_TERM_DEBT, ctx));
        how.put("lease_liabilities", "current + long-term finance lease liabilities");
        how.put("marketable_securities", "short-term investments + current financial assets at fair value (NULL when none)");
        how.put("shares_outstanding", outstanding == null ? "not derivable" : "common stock / par value, no treasury shares");
        return Optional.of(new MappedStatement("balance_sheet", column, period(column), b.sheet(), v, how, checks, List.of()));
    }

    // ------------------------------------------------------------------ cash flow

    public Optional<MappedStatement> cashFlow(StatementColumn column) {
        int ctx = durationIndex(column);
        if (ctx < 0 || cashFlow == null || !cashFlow.hasData(ctx)) {
            return Optional.empty();
        }
        StatementTable c = cashFlow;
        List<Check> checks = new ArrayList<>();
        BigDecimal operating = req(c, "Total net cash flows received from (used in) operating activities", ctx, checks);
        BigDecimal investing = req(c, "Total net cash flows received from (used in) investing activities", ctx, checks);
        BigDecimal financing = req(c, "Total net cash flows received from (used in) financing activities", ctx, checks);
        BigDecimal change = req(c, "Total net increase (decrease) in cash and cash equivalents", ctx, checks);
        BigDecimal beginning = amount(c.value("Cash and cash equivalents cash flows, beginning of the period", ctx));
        BigDecimal ending = req(c, "Cash and cash equivalents cash flows, end of the period", ctx, checks);
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
                || l.contains("(purchases)") || l.contains("(increase)") || l.contains("(decrease)")) {
            return value;
        }
        if (l.startsWith("payment") || l.startsWith("purchases") || l.startsWith("placement")
                || l.startsWith("cash advances and loans made") || l.startsWith("dividends paid")
                || l.startsWith("interests paid") || l.startsWith("income taxes paid")) {
            return value.negate();
        }
        if (l.startsWith("proceed") || l.startsWith("receipts") || l.startsWith("withdrawal of")
                || l.startsWith("cash receipts") || l.startsWith("dividends received")
                || l.startsWith("interests received") || l.startsWith("subordinated bonds issued")) {
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

    /** Intangibles opening + purchases - closing; current column only (needs the opening balance). */
    public BigDecimal amortization(StatementColumn column) {
        if (column != StatementColumn.CURRENT_PERIOD || balanceSheet == null || cashFlow == null) {
            return null;
        }
        BigDecimal disposals = cashFlow.value("Proceeds from disposal of intangible assets", 0);
        if (disposals != null && disposals.signum() != 0) {
            return null;    // carrying amount of disposals unknown
        }
        BigDecimal opening = nz(amount(balanceSheet.value("Intangible assets other than goodwill", 1)));
        BigDecimal closing = nz(amount(balanceSheet.value("Intangible assets other than goodwill", 0)));
        BigDecimal purchases = nz(amount(cashFlow.value("Payments for acquisition of intangible assets", 0)));
        BigDecimal result = opening.add(purchases).subtract(closing);
        return result.signum() < 0 ? null : result;
    }

    // ------------------------------------------------------------------ revenue segments

    public Optional<SegmentExtraction> segments(StatementColumn column, BigDecimal incomeRevenue) {
        int side = durationIndex(column);
        if (side < 0) {
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
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        List<Check> checks = new ArrayList<>();
        BigDecimal sum = lines.stream().map(SegmentLine::revenue).reduce(ZERO, BigDecimal::add);
        if (total != null) {
            checks.add(equal("segment_total", "sum of segments = reported total of the breakdown", sum, total));
        }
        if (incomeRevenue != null) {
            checks.add(equal("segment_revenue", "sum of segments = income statement revenue", sum, incomeRevenue));
        }
        return Optional.of(new SegmentExtraction(column, period(column), sheet.name(), lines, total, checks));
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
            return new ShareCapital(null, null, List.of(), null, null, checks);
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
        return new ShareCapital(par, basis, List.copyOf(byDate.values()), weightedCurrent, weightedPrior, checks);
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
        BigDecimal epsDiscontinued = income.value(EPS_BASIC_DISC, ctx);
        if ((discontinued != null && discontinued.signum() != 0) || (epsDiscontinued != null && epsDiscontinued.signum() != 0)) {
            return null;
        }
        BigDecimal parent = amount(income.value(PROFIT_PARENT, ctx));
        BigDecimal eps = income.value(EPS_BASIC, ctx);
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
        BigDecimal eps = income.value(EPS_BASIC, ctx);
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
        BigDecimal tolerance = unit.compareTo(BigDecimal.ONE) > 0 ? unit : ZERO;
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
