package com.neracalab.backend.ingestion.mapping;

import static java.math.BigDecimal.ZERO;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
            "Payments for acquisition of intangible assets",
            // a property developer's malls, offices and other rental assets (CBDK, PWON)
            "Payments for acquisition of investment properties");
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

    // 15: RAFI (common stocks 46,921,350,000 = 3,128,090,000 shares at Rp 15; FY2023 EPS 40.75)
    private static final List<BigDecimal> STANDARD_PAR_VALUES = List.of(1, 5, 10, 15, 20, 25, 50, 100, 125, 200, 250,
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
    private BigDecimal epsScale;     // 1/1000 once published counts show the EPS is filed per 1,000 shares
    /** Power of ten one duration column's EPS alone is filed off by (current, prior), see {@link #withWebShareCounts}. */
    private final BigDecimal[] columnEpsFactor = {BigDecimal.ONE, BigDecimal.ONE};
    private final IdxTaxonomy taxonomy;
    /** Financial and Sharia Industry taxonomy (banks): own statements and line items. */
    private final boolean financial;
    private final StatementTable balanceSheet;
    private final StatementTable income;
    private final StatementTable cashFlow;
    private final List<String> templateProblems = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    /** Set by {@link #shareCapital()}: why the two columns' EPS contradict each other, {@code null} when they do not. */
    private volatile String epsContradiction;
    private volatile boolean epsChecked;
    /** Set while {@link #parValueFallback} judges the par value on a profit or loss by nature. */
    private boolean parFromProfitOrLossByNature;

    public FilingMapper(IdxWorkbook workbook) {
        this(workbook, null);
    }

    /**
     * A mapper whose amounts are in {@code provenUnit} whatever the workbook declares: the unit another filing's
     * stored figures prove ({@code IngestionService.provenUnit}). IMJS's FY2024 workbook declares "Satuan Penuh /
     * Full Amount" but files total assets 29,410,622 and a prior year end of 28,711,560 - FY2023's stored
     * 28,711,561,013,612 in millions - and its profit attributable to the parent 228,477 / basic EPS 26.4 gives 8,654
     * shares as filed, 8.65 billion in millions. {@code null} resolves the unit from the workbook alone.
     */
    public FilingMapper(IdxWorkbook workbook, BigDecimal provenUnit) {
        this.workbook = workbook;
        this.info = FilingInfo.from(workbook);
        this.taxonomy = workbook.taxonomy();
        // the Financing and the Insurance Industry taxonomies have the Financial one's sheets (by order of
        // liquidity, by nature)
        this.financial = taxonomy == IdxTaxonomy.FINANCIAL || modelTaxonomy();
        if (financial) {
            this.balanceSheet = data(IdxSheets.BALANCE_SHEET_LIQUIDITY).orElse(null);
            this.income = data(IdxSheets.INCOME_BY_NATURE_BEFORE_TAX).or(() -> data(IdxSheets.INCOME_BY_NATURE)).orElse(null);
        } else {
            this.balanceSheet = data(IdxSheets.BALANCE_SHEET).orElse(null);
            this.income = data(IdxSheets.INCOME_BY_FUNCTION).or(() -> data(IdxSheets.INCOME_BY_FUNCTION_BEFORE_TAX)).orElse(null);
        }
        this.cashFlow = data(IdxSheets.CASH_FLOW_DIRECT).or(() -> data(IdxSheets.CASH_FLOW_INDIRECT)).orElse(null);
        Units units = provenUnit != null ? provenUnits(provenUnit) : resolveUnits(info.unitMultiplier());
        this.unit = units.amounts();
        this.epsScale = units.eps();
        this.precision = provenUnit != null ? provenUnit : info.unitMultiplier();
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
        if (modelTaxonomy()) {
            // its line items are not the bank template's: no rule here decides them
            templateProblems.add("The line items of the " + taxonomyName() + " taxonomy (" + code(IdxSheets.BALANCE_SHEET_LIQUIDITY)
                    + ", " + code(IdxSheets.INCOME_BY_NATURE_BEFORE_TAX) + ", " + code(IdxSheets.CASH_FLOW_DIRECT)
                    + ") are not mapped by the rule-based extraction; the model decides them from the text of the workbook");
        }
    }

    /**
     * Profit or loss as filed, whatever its template: the sheet the rule-based extraction maps, else the other
     * presentation (by nature for the General taxonomies, e.g. NSSS). Its totals - profit attributable to the
     * parent, EPS - carry the same labels in both, so a published share count can be checked against either.
     */
    private StatementTable profitOrLossAsFiled() {
        if (income != null) {
            return income;
        }
        List<String> other = financial
                ? List.of(IdxSheets.INCOME_BY_FUNCTION, IdxSheets.INCOME_BY_FUNCTION_BEFORE_TAX)
                : List.of(IdxSheets.INCOME_BY_NATURE, IdxSheets.INCOME_BY_NATURE_BEFORE_TAX);
        return other.stream().map(this::data).flatMap(Optional::stream).findFirst().orElse(null);
    }

    /**
     * Balance sheet as filed, whatever its template: the sheet the rule-based extraction maps, else the other
     * presentation (CNMA files the General Industry balance sheet by order of liquidity, 1220000). Both carry the same
     * line items, e.g. 'Right of use assets'.
     */
    private StatementTable balanceSheetAsFiled() {
        if (balanceSheet != null) {
            return balanceSheet;
        }
        return data(financial ? IdxSheets.BALANCE_SHEET : IdxSheets.BALANCE_SHEET_LIQUIDITY).orElse(null);
    }

    /**
     * Whether profit or loss is presented by nature in a taxonomy whose rule-based extraction reads it by function
     * (e.g. NSSS): expenses by type, no 'Cost of sales and revenue' and no 'Total gross profit' row.
     */
    public boolean incomeByNature() {
        return !financial && income == null && profitOrLossAsFiled() != null;
    }

    /** The workbook as read (every sheet), for the full-text extraction. */
    public IdxWorkbook workbook() {
        return workbook;
    }

    /**
     * Whether the statements of this workbook are the model's to decide by design: a taxonomy the rule-based
     * extraction does not map (Financing Industry, Insurance Industry), a profit or loss presented by nature where
     * the rule-based extraction reads it by function (NSSS, MSIN), or a balance sheet by order of liquidity where it
     * reads the current / non-current one (CNMA), and nothing else wrong with the workbook. Such an upload goes
     * straight to the full-text extraction; when that stores it, nothing went wrong.
     */
    public boolean decidedByModel() {
        int byDesign = (modelTaxonomy() ? 1 : 0) + (incomeByNature() ? 1 : 0) + (balanceByLiquidity() ? 1 : 0);
        return byDesign > 0 && templateProblems.size() == byDesign;
    }

    /**
     * Whether the balance sheet is presented by order of liquidity (1220000) in a taxonomy whose rule-based extraction
     * reads the current / non-current one (CNMA, General Industry): no current assets or liabilities.
     */
    public boolean balanceByLiquidity() {
        return !financial && balanceSheet == null && balanceSheetAsFiled() != null;
    }

    /**
     * A taxonomy whose line items the rule-based extraction does not map: Financing Industry (multifinance, BFIN) and
     * Insurance Industry (insurers, TUGU). Its sheets are read under the General codes (share capital, per-share unit,
     * notes); its statements are the full-text extraction's.
     */
    private boolean modelTaxonomy() {
        return taxonomy == IdxTaxonomy.FINANCING || taxonomy == IdxTaxonomy.INSURANCE;
    }

    /** Whether the workbook uses the Insurance Industry taxonomy (an insurer). */
    public boolean insurance() {
        return taxonomy == IdxTaxonomy.INSURANCE;
    }

    /** Whether the workbook uses the Financial and Sharia Industry taxonomy (a bank). */
    public boolean financial() {
        return financial;
    }

    /** Factor of the filed per-share figures (1, or the rounding unit when the EPS is filed in it). */
    public BigDecimal epsScale() {
        return epsScale;
    }

    public IdxTaxonomy taxonomy() {
        return taxonomy;
    }

    /** Multiplier from the filed amounts to full currency units (the declared rounding unless contradicted). */
    public BigDecimal unit() {
        return unit;
    }

    /** The declared rounding in full units, also when the amounts are written in full (EXCL FY2023: 1,000,000). */
    public BigDecimal precision() {
        return precision;
    }

    /** Notes about the filing that do not block it, e.g. a corrected rounding level. */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Equity row between the parent's equity and the non-controlling interests: the equity of an entity combined
     * under common control, restated into a comparative column (IMPC FY2023 in its FY2024 filing: 138,044,698,258;
     * MSIN FY2021 in its FY2022 filing). Total equity includes it, the parent's equity does not; it is stored with
     * the non-controlling interests, so that parent + non-controlling = total holds.
     */
    static final String PROFORMA_EQUITY = "Proforma equity";

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
     * <p>
     * Where both share counts are possible, the share capital decides ({@link #epsScaleFromShareCapital}): MOLI's H1
     * 2026 workbook files 0.02321 for Rp 23.21 (thousands), TUGU's FY2024 workbook 0.0002 for Rp 197 (millions, in a
     * workbook in thousands).
     * <p>
     * Where both share counts are possible and every one of at least {@link #FULL_AMOUNTS_WITHOUT_EPS} amounts is a whole
     * multiple of the declared unit, the amounts are full amounts all the same: ITMG's FY2023 workbook declares "Ribuan /
     * In Thousand" and files total assets 2,187,847,000 (USD), and its EPS 0.44 gives 1.1 billion shares as filed or 1.1
     * trillion in thousands, which is no impossible count (GOTO has 1.2 trillion). Read in thousands, every amount was
     * stored a thousand times too large over the FY2024 filing's comparative. Of the 419 workbooks of 2021 - 2026 that
     * declare thousands, millions or billions, the 20 with every amount a multiple of the unit are all such FY2023
     * workbooks with full amounts; ITMG's is the only one the EPS does not decide.
     */
    private Units resolveUnits(BigDecimal declared) {
        BigDecimal impliedFull = impliedSharesPerUnit();   // shares if amounts and EPS were in the same unit
        boolean impossibleWithDeclared = declared.compareTo(BigDecimal.ONE) > 0 && impliedFull != null
                && plausibleShares(impliedFull) && !plausibleShares(impliedFull.multiply(declared));
        boolean undecided = declared.compareTo(BigDecimal.ONE) > 0 && impliedFull != null
                && plausibleShares(impliedFull) && plausibleShares(impliedFull.multiply(declared));
        if (impossibleWithDeclared && allAmountsMultipleOf(declared, 10)
                || undecided && allAmountsMultipleOf(declared, FULL_AMOUNTS_WITHOUT_EPS)) {
            warnings.add("The workbook declares '" + info.rounding() + "' but its amounts are full amounts (every amount is a "
                    + "multiple of " + declared.toPlainString() + " and the EPS implies "
                    + impliedFull.setScale(0, RoundingMode.HALF_UP).toPlainString() + " shares"
                    + (undecided ? " as filed, " + impliedFull.multiply(declared).setScale(0, RoundingMode.HALF_UP).toPlainString()
                            + " in the declared unit" : "") + "); amounts are read as full amounts");
            return new Units(BigDecimal.ONE, BigDecimal.ONE);
        }
        BigDecimal fromCapital = epsScaleFromShareCapital(declared);
        if (fromCapital != null) {
            warnings.add("The workbook's EPS is filed in " + (fromCapital.compareTo(declared) == 0 ? "its rounding unit ('"
                    + info.rounding() + "')" : "units of " + fromCapital.toPlainString()) + ": basic EPS "
                    + profitOrLossAsFiled().value(EPS_BASIC, 0).toPlainString() + " reproduces profit attributable to the parent / "
                    + "(share capital / a standard par value) only when multiplied by " + fromCapital.toPlainString()
                    + "; per-share figures are multiplied by " + fromCapital.toPlainString());
            return new Units(declared, fromCapital);
        }
        if (impossibleWithDeclared) {
            String shares = impliedFull.setScale(0, RoundingMode.HALF_UP).toPlainString();
            warnings.add("The workbook's EPS is filed in its rounding unit ('" + info.rounding() + "': basic EPS "
                    + profitOrLossAsFiled().value(EPS_BASIC, 0).toPlainString() + " implies " + shares + " shares only when multiplied by "
                    + declared.toPlainString() + "); per-share figures are multiplied by " + declared.toPlainString());
            return new Units(declared, declared);
        }
        if (impliedFull != null && !plausibleShares(impliedFull.multiply(declared))) {
            roundingContradicted = true;
            templateProblems.add("Amounts contradict the declared rounding '" + info.rounding() + "': profit / basic EPS implies "
                    + impliedFull.multiply(declared).setScale(0, RoundingMode.HALF_UP).toPlainString() + " shares");
        }
        return new Units(declared, BigDecimal.ONE);
    }

    /** Set by {@link #resolveUnits}: profit / basic EPS gives an impossible share count in the declared unit. */
    private boolean roundingContradicted;

    private Units provenUnits(BigDecimal proven) {
        warnings.add("The workbook declares '" + info.rounding() + "' but its amounts are in units of " + proven.toPlainString()
                + ": its prior year end total assets are the stored figure in that unit, and profit / basic EPS then implies "
                + impliedSharesPerUnit().multiply(proven).setScale(0, RoundingMode.HALF_UP).toPlainString() + " shares");
        return new Units(proven, BigDecimal.ONE);
    }

    /**
     * Whether the filed EPS contradicts the declared rounding (an impossible share count) while it gives a plausible
     * one with the amounts in {@code unit}: one of the two signals that a workbook's amounts are in another unit
     * than declared; the stored figures of another filing are the other ({@code IngestionService.provenUnit}).
     */
    public boolean roundingContradictedButPlausibleIn(BigDecimal unit) {
        BigDecimal implied = impliedSharesPerUnit();
        return roundingContradicted && implied != null && plausibleShares(implied.multiply(unit));
    }

    /** Total assets at the prior year end as filed (not scaled), {@code null} when the balance sheet has none. */
    public BigDecimal filedPriorYearEndTotalAssets() {
        StatementTable b = balanceSheetAsFiled();
        return b == null ? null : b.value("Total assets", 1);
    }

    /** Multipliers an EPS may be filed off by: as filed, in thousands, in millions. */
    private static final List<BigDecimal> EPS_SCALES = List.of(BigDecimal.ONE, new BigDecimal("1000"), new BigDecimal("1000000"));

    /**
     * The multiplier of the filed EPS, judged by the share capital of the current period end: profit attributable to
     * the parent / (share capital / par value) must reproduce the basic EPS within its rounding for a standard par
     * value. The EPS of a workbook in thousands can be filed in rupiah (as it should), in thousands (MOLI H1 2026:
     * 0.02321 for 63,221,614 thousand / 2,724,036,580 shares = Rp 23.21, par 100; TUGU FY2023: 0.372 for Rp 371.92,
     * par 50) or even in millions (TUGU FY2024: 0.0002 for Rp 197.1, par 50, one digit left). Where profit / EPS
     * gives a plausible share count either way, only the share capital tells them apart.
     *
     * @return the multiplier (1000 or 1,000,000) when exactly that one fits; {@code null} when the EPS fits as filed,
     *         when two multipliers fit (MOLI FY2025: 0.277 is Rp 0.277 at par 1 or Rp 277 at par 1000; neither is the
     *         Rp 27.70 of par 100), or when there is no evidence (no profit or EPS, treasury stock, no share capital)
     */
    private BigDecimal epsScaleFromShareCapital(BigDecimal declared) {
        StatementTable income = profitOrLossAsFiled();
        if (income == null || !rupiahShareCapital()) {
            return null;
        }
        BigDecimal parent = income.value(PROFIT_PARENT, 0);
        BigDecimal eps = income.value(EPS_BASIC, 0);
        BigDecimal[] position = equityPosition(IdxSheets.EQUITY, "Equity position, end of the period");
        if (parent == null || eps == null || position == null || position[0] == null || position[0].signum() <= 0
                || parent.signum() == 0 || eps.signum() != parent.signum()
                || (position[1] != null && position[1].signum() != 0)) {
            return null;
        }
        BigDecimal parentFull = parent.multiply(declared).abs();
        BigDecimal common = position[0].multiply(declared);
        BigDecimal halfUlp = halfUlp(eps);
        List<BigDecimal> fitting = new ArrayList<>();
        for (BigDecimal scale : EPS_SCALES) {
            BigDecimal scaled = eps.abs().multiply(scale);
            BigDecimal tolerance = halfUlp.multiply(scale).add(new BigDecimal("1e-9"));
            for (BigDecimal par : STANDARD_PAR_VALUES) {
                BigDecimal shares = common.divide(par, 10, RoundingMode.HALF_UP).stripTrailingZeros();
                if (shares.scale() > 0 || shares.signum() <= 0) {
                    continue;
                }
                if (parentFull.divide(shares, 12, RoundingMode.HALF_UP).subtract(scaled).abs().compareTo(tolerance) <= 0) {
                    fitting.add(scale);
                    break;
                }
            }
        }
        return fitting.size() == 1 && fitting.getFirst().compareTo(BigDecimal.ONE) != 0 ? fitting.getFirst() : null;
    }

    /**
     * Whether the share capital is in rupiah, so that a standard (rupiah) par value can divide it. A filing in another
     * currency shows its rupiah share capital converted at historical rates (GMFI: USD 219,015,655 for 28.2 billion
     * shares; INDY: USD 56,892,154): a par value that fits it fits by chance. GMFI's EPS 0.0001 (USD) was read as filed
     * in thousands - 219,015,655 / par 5 = 43,803,131 shares reproduced it - and stored as 0.1.
     */
    private boolean rupiahShareCapital() {
        return "IDR".equals(info.currency());
    }

    /** Half a unit of the last decimal place of a filed figure (0.0002 -> 0.00005, 135 -> 0.5). */
    private static BigDecimal halfUlp(BigDecimal filed) {
        return BigDecimal.ONE.movePointLeft(Math.max(0, filed.stripTrailingZeros().scale())).divide(BigDecimal.valueOf(2));
    }

    /** Half a unit of the last decimal place of the filed EPS, in currency units per share (scaled like the EPS). */
    private BigDecimal epsHalfUlp(StatementTable profitOrLoss, String label, int ctx) {
        BigDecimal raw = profitOrLoss.value(label, ctx);
        return raw == null ? null : halfUlp(raw).multiply(epsFactor(ctx));
    }

    /**
     * Common stock and treasury stock (as filed, unscaled) of a statement of changes in equity on its row with the
     * given English label, e.g. "Equity position, end of the period"; {@code null} when the sheet has no such row.
     */
    private BigDecimal[] equityPosition(String sheetName, String english) {
        RawSheet sheet = workbook.sheet(sheetName).orElse(null);
        if (sheet == null) {
            return null;
        }
        int commonCol = -1;
        int treasuryCol = -1;
        int headerRow = -1;
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
            return null;
        }
        for (int r = headerRow + 1; r < sheet.rowCount(); r++) {
            if (english.equals(sheet.text(r, sheet.width(r) - 1))) {
                return new BigDecimal[] {sheet.number(r, commonCol), treasuryCol < 0 ? null : sheet.number(r, treasuryCol)};
            }
        }
        return null;
    }

    /** A per-share figure as filed, scaled to currency units per share. */
    private BigDecimal eps(String label, int ctx) {
        return eps(shareProfitOrLoss(), label, ctx);
    }

    /**
     * The profit or loss share counts are judged on: the one the rules map; a profit or loss by nature only for the
     * fallback of {@link #withWebShareCounts} ({@link #parValueFallback}).
     */
    private StatementTable shareProfitOrLoss() {
        return income != null ? income : parFromProfitOrLossByNature ? profitOrLossAsFiled() : null;
    }

    private BigDecimal eps(StatementTable profitOrLoss, String label, int ctx) {
        BigDecimal raw = profitOrLoss.value(label, ctx);
        BigDecimal factor = epsFactor(ctx);
        if (raw == null || factor.compareTo(BigDecimal.ONE) == 0) {
            return raw;
        }
        BigDecimal v = raw.multiply(factor).stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    /** Multiplier of the per-share figures of a duration column: the filing's, and the column's own power of ten. */
    private BigDecimal epsFactor(int ctx) {
        return ctx >= 0 && ctx < columnEpsFactor.length ? epsScale.multiply(columnEpsFactor[ctx]) : epsScale;
    }

    /** {@link #epsScale()} of one column: also the power of ten its EPS alone is filed off by ({@link #withWebShareCounts}). */
    public BigDecimal epsScale(StatementColumn column) {
        return epsFactor(durationIndex(column));
    }

    /**
     * Basic or diluted EPS of a column: continuing + discontinued operations, as filed (scaled). An EPS from
     * discontinued operations that the profit from discontinued operations cannot give is left out (a warning says
     * so): MEDC files its total EPS a second time on the discontinued row (FY2022: 0.021503 and 0.021503 beside a
     * discontinued profit of 13,780,735 of 551,410,338), HRUM its continuing EPS on it without any discontinued
     * operations (FY2025: 0.00284 and 0.00284), and the sum stored twice the EPS (MEDC 0.043006 for 530,882,675 / 25.0
     * billion shares = 0.0212). The two per-share figures have one denominator, so their ratio is the ratio of the two
     * profits attributable to the parent; it is checked against the ratio of the two profits (the non-controlling
     * interests' share of either may differ: a factor of 2 either way, beyond the filed rounding).
     */
    private BigDecimal epsWithDiscontinued(StatementTable profitOrLoss, String continuing, String discontinued, int ctx,
                                           List<Check> checks) {
        BigDecimal fromContinuing = eps(profitOrLoss, continuing, ctx);
        BigDecimal fromDiscontinued = eps(profitOrLoss, discontinued, ctx);
        String misfiled = fromDiscontinued == null ? null : discontinuedEpsMisfit(profitOrLoss, continuing, discontinued, ctx);
        if (misfiled == null) {
            return sumNullable(fromContinuing, fromDiscontinued);
        }
        if (checks != null) {
            checks.add(Check.warning("eps_discontinued", "'" + discontinued + "' " + fromDiscontinued.toPlainString()
                    + " is not an EPS of the discontinued operations: " + misfiled + "; left out of the EPS"));
        }
        return fromContinuing;
    }

    /**
     * Whether a row of an EPS from discontinued operations is left out of the column's EPS ({@link #epsWithDiscontinued}),
     * for the full-text extraction, whose model may name it.
     */
    public boolean discontinuedEpsLeftOut(StatementColumn column, String label) {
        StatementTable profitOrLoss = profitOrLossAsFiled();
        int ctx = durationIndex(column);
        String continuing = EPS_BASIC_DISC.equals(label) ? EPS_BASIC : EPS_DILUTED_DISC.equals(label) ? EPS_DILUTED : null;
        return profitOrLoss != null && ctx >= 0 && continuing != null
                && discontinuedEpsMisfit(profitOrLoss, continuing, label, ctx) != null;
    }

    /** Why the filed EPS from discontinued operations of a column is not one, {@code null} when it may be. */
    private String discontinuedEpsMisfit(StatementTable profitOrLoss, String continuing, String discontinued, int ctx) {
        BigDecimal disc = profitOrLoss.value(discontinued, ctx);
        if (disc == null || disc.signum() == 0) {
            return null;
        }
        BigDecimal profitDisc = amount(profitOrLoss.value(PROFIT_DISCONTINUED, ctx));
        if (profitDisc == null || profitDisc.signum() == 0) {
            return "the column has no profit (loss) from discontinued operations";
        }
        BigDecimal cont = profitOrLoss.value(continuing, ctx);
        BigDecimal profitCont = amount(profitOrLoss.value(PROFIT_CONTINUING, ctx));
        BigDecimal total = amount(profitOrLoss.value(PROFIT, ctx));
        if (profitCont == null && total != null) {
            profitCont = total.subtract(profitDisc);
        }
        if (cont == null || cont.signum() == 0 || profitCont == null || profitCont.signum() == 0) {
            return null;    // nothing to judge it by
        }
        BigDecimal expected = cont.multiply(profitDisc).divide(profitCont, MathContext.DECIMAL64);
        String why = "continuing EPS " + cont.toPlainString() + " x discontinued / continuing profit "
                + profitDisc.toPlainString() + " / " + profitCont.toPlainString() + " = "
                + expected.round(new MathContext(3)).toPlainString();
        if (expected.signum() != disc.signum()) {
            return why + ", of the other sign";
        }
        BigDecimal halfUlp = halfUlp(disc);
        BigDecimal low = disc.abs().subtract(halfUlp).max(ZERO).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
        BigDecimal high = disc.abs().add(halfUlp).multiply(BigDecimal.valueOf(2));
        return expected.abs().compareTo(low) < 0 || expected.abs().compareTo(high) > 0 ? why : null;
    }

    /**
     * Profit attributable to the parent (as filed, unscaled) / basic EPS of the current period; null when not
     * available. A current period whose EPS contradicts the sign of its profit (a loss filed without the sign,
     * TINS FY2023) says nothing about the unit: the comparative column is used instead.
     */
    private BigDecimal impliedSharesPerUnit() {
        // the profit or loss as filed: a filing by nature has the same totals (MSIN FY2024: basic EPS 0.00000655
        // for Rp 6.55, amounts in millions)
        StatementTable income = profitOrLossAsFiled();
        if (income == null) {
            return null;
        }
        for (int ctx = 0; ctx <= 1; ctx++) {
            BigDecimal parent = income.value(PROFIT_PARENT, ctx);
            BigDecimal eps = income.value(EPS_BASIC, ctx);
            if (parent != null && eps != null && parent.signum() != 0 && eps.signum() != 0 && parent.signum() == eps.signum()) {
                return parent.divide(eps, MathContext.DECIMAL64);
            }
            if (ctx == 0 && (parent == null || eps == null || parent.signum() == 0 || eps.signum() == 0)) {
                return null;       // no EPS or no profit in the current period: nothing to judge the unit by, as before
            }
        }
        return null;
    }

    private static boolean plausibleShares(BigDecimal shares) {
        return shares.compareTo(MIN_PLAUSIBLE_SHARES) >= 0 && shares.compareTo(MAX_PLAUSIBLE_SHARES) <= 0;
    }

    /**
     * Fewest amounts, all whole multiples of the declared unit, that make a workbook one of full amounts when its EPS
     * allows both units (ITMG FY2023: 207 of them in its three statements; a filing in the unit has one of them a
     * multiple by chance in a thousand).
     */
    private static final int FULL_AMOUNTS_WITHOUT_EPS = 100;

    private boolean allAmountsMultipleOf(BigDecimal declared, int fewest) {
        int amounts = 0;
        for (StatementTable t : java.util.Arrays.asList(balanceSheet, profitOrLossAsFiled(), cashFlow)) {
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
        return amounts >= fewest;
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
        Optional<MappedStatement> statement = incomeStatementReconciled(column, overrides, shares);
        if (statement.isPresent() && !statement.get().unclassified().isEmpty()) {
            Optional<MappedStatement> memo = withoutUnclassifiedMemoLines(column, overrides, shares, statement.get());
            if (memo.isPresent()) {
                return memo;
            }
        }
        return statement;
    }

    /**
     * Lines the mapper cannot classify that the filing's own totals do not include (BRI H1 2025 in its H1 2026
     * filing: "Other insurance income" 4,466,709 and "Other insurance expenses" 4,051,819 beside insurance premiums
     * 4,488,953 and claims 4,051,846; "Total profit from operation" 35,004,968 and profit before tax are exactly
     * the other lines). Such a line is shown for information: it is ignored, with a warning, instead of being
     * left to the agent - which could only find that no category makes the statement reconcile.
     * <p>
     * Only on arithmetic proof: with every unclassified line left out the statement passes every check, the
     * reconciliation checks really ran (profit before tax, and for a bank its profit from operation, against the
     * filed totals), and no combination of the unclassified amounts cancels out - two lines that offset each
     * other could both belong to the totals, so those stay with the agent.
     */
    private Optional<MappedStatement> withoutUnclassifiedMemoLines(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                                   ShareCapital shares, MappedStatement statement) {
        List<UnclassifiedLine> lines = statement.unclassified();
        if (lines.size() > 4 || lines.stream().anyMatch(l -> l.value() == null) || cancelsOut(lines)) {
            return Optional.empty();
        }
        Map<String, IncomeLineCategory> without = new LinkedHashMap<>(overrides);
        lines.forEach(l -> without.put(l.label(), IncomeLineCategory.IGNORE));
        MappedStatement retry = incomeStatementReconciled(column, without, shares).orElse(null);
        if (retry == null || retry.hasErrors() || !reconciled(retry, "profit_before_tax")
                || (financial && !reconciled(retry, "operating_income"))) {
            return Optional.empty();
        }
        List<Check> checks = new ArrayList<>(retry.checks());
        Map<String, String> how = new LinkedHashMap<>(retry.derivations());
        for (UnclassifiedLine line : lines) {
            checks.add(Check.warning("memo_line", "'" + line.label() + "' " + line.value().stripTrailingZeros().toPlainString()
                    + " is not part of the filing's own totals in this column: " + (financial ? "'" + PROFIT_FROM_OPERATION
                    + "' and " : "") + "profit before tax are exactly the other lines (shown for information); ignored"));
        }
        how.put("memo_line", lines.stream().map(UnclassifiedLine::label).toList() + " ignored: the filing's totals "
                + "reconcile exactly without them");
        return Optional.of(new MappedStatement(retry.table(), retry.column(), retry.period(), retry.sourceSheet(),
                retry.values(), how, checks, retry.unclassified(), retry.rejected()));
    }

    /** The check of that rule ran against a filed total and passed. */
    private static boolean reconciled(MappedStatement statement, String rule) {
        return statement.checks().stream().anyMatch(c -> c.rule().equals(rule) && c.severity() == Check.Severity.OK);
    }

    /** Whether some of the amounts, each added or subtracted, cancel out (within the filing's rounding). */
    private boolean cancelsOut(List<UnclassifiedLine> lines) {
        BigDecimal tolerance = precision.compareTo(BigDecimal.ONE) > 0 ? precision : ZERO;
        int combinations = (int) Math.pow(3, lines.size());
        for (int code = 1; code < combinations; code++) {
            BigDecimal sum = ZERO;
            int rest = code;
            for (UnclassifiedLine line : lines) {
                int sign = rest % 3;       // 0: left out, 1: added, 2: subtracted
                rest /= 3;
                sum = sign == 1 ? sum.add(line.value()) : sign == 2 ? sum.subtract(line.value()) : sum;
            }
            if (sum.abs().compareTo(tolerance) <= 0) {
                return true;
            }
        }
        return false;
    }

    /** The income statement with an amount the filing reports twice counted once, see {@link #incomeStatement}. */
    private Optional<MappedStatement> incomeStatementReconciled(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                                ShareCapital shares) {
        Optional<MappedStatement> filed = incomeStatementAsFiled(column, overrides, shares);
        if (filed.isPresent() && financial
                && filed.get().checks().stream().anyMatch(c -> c.isError() && c.rule().equals("operating_income"))) {
            Optional<MappedStatement> memo = withoutMemoInsuranceLine(column, overrides, shares);
            if (memo.isPresent()) {
                return memo;
            }
        }
        if (filed.isEmpty() || filed.get().checks().stream().noneMatch(c -> c.isError() && c.rule().equals("profit_before_tax"))) {
            return filed;
        }
        for (Repeat pair : doubleReportedLines(durationIndex(column), overrides)) {
            Map<String, IncomeLineCategory> once = new LinkedHashMap<>(overrides);
            once.put(pair.ignored(), IncomeLineCategory.IGNORE);
            MappedStatement retry = incomeStatementAsFiled(column, once, shares).orElseThrow();
            if (retry.checks().stream().noneMatch(Check::isError)) {
                List<Check> checks = new ArrayList<>(retry.checks());
                checks.add(Check.warning("double_reported", "'" + pair.ignored() + "' and '" + pair.kept() + "' "
                        + pair.relation() + "; counted once, as '" + pair.kept() + "'"));
                Map<String, String> how = new LinkedHashMap<>(retry.derivations());
                how.put("double_reported", "'" + pair.ignored() + "' ignored: " + (pair.relation().startsWith(Repeat.SAME)
                        ? "the same amount is reported on '" + pair.kept() + "'" : "its amount is in '" + pair.kept() + "'")
                        + " (profit before tax only reconciles with it counted once)");
                return Optional.of(new MappedStatement(retry.table(), retry.column(), retry.period(), retry.sourceSheet(),
                        retry.values(), how, checks, retry.unclassified()));
            }
        }
        for (Part part : partsOfResidualLines(durationIndex(column), overrides)) {
            MappedStatement retry = incomeStatementAsFiled(column, overrides, Map.of(part.residual(), part.raw()), shares).orElseThrow();
            if (retry.checks().stream().noneMatch(Check::isError)) {
                String amount = amount(part.raw()).stripTrailingZeros().toPlainString();
                String without = amount(income.value(part.residual(), durationIndex(column)).subtract(part.raw()))
                        .stripTrailingZeros().toPlainString();
                List<Check> checks = new ArrayList<>(retry.checks());
                checks.add(Check.warning("double_reported", "'" + part.line() + "' " + amount + " is also counted in '"
                        + part.residual() + "' in this column; '" + part.residual() + "' counted without it, " + without));
                Map<String, String> how = new LinkedHashMap<>(retry.derivations());
                how.put("double_reported", "'" + part.residual() + "' less '" + part.line() + "' " + amount
                        + " (profit before tax only reconciles with the amount counted once)");
                return Optional.of(new MappedStatement(retry.table(), retry.column(), retry.period(), retry.sourceSheet(),
                        retry.values(), how, checks, retry.unclassified()));
            }
        }
        if (column == StatementColumn.PRIOR_PERIOD) {
            Optional<MappedStatement> leftOut = withoutLeftOutLine(column, overrides, shares);
            if (leftOut.isPresent()) {
                return leftOut;
            }
        }
        return filed;
    }

    /**
     * The comparative of a restated period with one line its own profit before tax leaves out: the H1 2025 columns of
     * the H1 2026 filings of PTRO ("Selling expenses" 3,514, profit before tax 7,846 the other lines) and BNBR ("Other
     * expenses" 2,784, profit before tax 72,088), and PTRO's FY2024 column of its FY2025 filing ("Other gains (losses)"
     * 3,804, the 4,361 of the FY2024 filing net of the associates' -557, which the column lists again). The line is
     * ignored, with a warning - only in a comparative column (the period's own filing is never second-guessed), when
     * the statement then passes every check and exactly one line does so. Not once the agent has classified lines of
     * the column: the agent's reading of it is never second-guessed.
     */
    private Optional<MappedStatement> withoutLeftOutLine(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                         ShareCapital shares) {
        if (financial || !overrides.isEmpty()) {
            return Optional.empty();
        }
        int ctx = durationIndex(column);
        int profitIndex = income.indexOf(PROFIT);
        java.util.Set<String> labels = new java.util.LinkedHashSet<>();
        for (int i = 0; i < income.lines().size() && (profitIndex < 0 || i < profitIndex); i++) {
            StatementTable.Line line = income.lines().get(i);
            BigDecimal raw = line.value(ctx);
            IncomeLineCategory category = IncomeLineCategory.KNOWN.get(line.label());
            if (raw != null && raw.signum() != 0 && category != null && category != IncomeLineCategory.IGNORE
                    && !STRUCTURAL_INCOME_LINES.contains(line.label())) {
                labels.add(line.label());
            }
        }
        String found = null;
        MappedStatement statement = null;
        for (String label : labels) {
            MappedStatement retry = incomeStatementAsFiled(column, Map.of(label, IncomeLineCategory.IGNORE), shares).orElseThrow();
            if (retry.checks().stream().noneMatch(Check::isError) && reconciled(retry, "profit_before_tax")) {
                if (found != null) {
                    return Optional.empty();    // ambiguous
                }
                found = label;
                statement = retry;
            }
        }
        if (found == null) {
            return Optional.empty();
        }
        String amount = amount(income.value(found, ctx)).stripTrailingZeros().toPlainString();
        List<Check> checks = new ArrayList<>(statement.checks());
        checks.add(Check.warning("left_out_line", "'" + found + "' " + amount + " is not part of the filing's profit "
                + "before tax in this comparative column (profit before tax is exactly the other lines); ignored"));
        Map<String, String> how = new LinkedHashMap<>(statement.derivations());
        how.put("left_out_line", "'" + found + "' ignored: the comparative's profit before tax only reconciles without it");
        return Optional.of(new MappedStatement(statement.table(), statement.column(), statement.period(),
                statement.sourceSheet(), statement.values(), how, checks, statement.unclassified()));
    }

    /** A line whose amount ({@code raw}, as filed) one column counts again within a residual line of its direction. */
    private record Part(String line, String residual, BigDecimal raw) {
    }

    /**
     * Lines of one column that may be filed again within a residual "Other ..." line of their direction: an income
     * (expense) line that is no residual line itself, smaller than a residual income (expense) line. KETR FY2024 in its
     * FY2025 filing: 'Finance income' 3,820,523,591 within 'Other income' 15,061,778,585 - the FY2024 filing has 'Other
     * income' 11,241,254,994 beside the same finance income - and profit before tax 101,667,066,908 counts it once. The
     * line keeps its amount and the residual line is counted without it, as the period's own filing presents them; only
     * when the statement then passes every check. A classification given by the agent is never second-guessed.
     */
    private List<Part> partsOfResidualLines(int ctx, Map<String, IncomeLineCategory> overrides) {
        if (financial) {
            return List.of();
        }
        Map<String, BigDecimal> lines = new LinkedHashMap<>();   // label -> contribution to profit before tax
        for (StatementTable.Line line : income.lines()) {
            BigDecimal raw = line.value(ctx);
            IncomeLineCategory category = IncomeLineCategory.KNOWN.get(line.label());
            if (raw == null || raw.signum() <= 0 || category == null || overrides.containsKey(line.label())) {
                continue;
            }
            if (EXPENSE_CATEGORIES.contains(category)) {
                lines.putIfAbsent(line.label(), raw.negate());
            } else if (INCOME_CATEGORIES.contains(category)) {
                lines.putIfAbsent(line.label(), raw);
            }
        }
        List<Part> parts = new ArrayList<>();
        lines.forEach((residual, whole) -> lines.forEach((line, part) -> {
            if (isResidual(residual) && !isResidual(line) && whole.signum() == part.signum()
                    && whole.abs().compareTo(part.abs()) > 0) {
                parts.add(new Part(line, residual, part.abs()));
            }
        }));
        return parts;
    }

    /** Insurance expense lines of the bank template (a bank's insurance subsidiary). */
    static final List<String> INSURANCE_EXPENSE_LINES = List.of("Claim expenses", "Reinsurance claims",
            "Retrocession claims", "Increase (decrease) in estimated claims liability",
            "Increase (decrease) in liability for future policy benefit",
            "Increase (decrease) in provision for losses arising from liability adequacy test",
            "Increase (decrease) in insurance liabilities ceded to reinsurers",
            "Increase (decrease) in liabilities to policyholder in unit-linked contracts", "Insurance commission expenses",
            "Ujrah paid", "Acquisition costs of insurance contracts", "Other insurance expenses");

    /**
     * The bank income statement of a column with an insurance expense line that the filing shows for information
     * only: its "Total profit from operation" does not deduct it because the premium line is already net of it
     * (BMRI's FY2024 comparative in its FY2025 filing: "Claim expenses" 10,574,450 million beside premiums of
     * 2,520,813 net of claims; profit from operation 76,059,595 reconciles exactly without the claims). The line
     * is ignored and a warning says so - only when the statement then passes every check. A classification given
     * by the agent is never second-guessed.
     */
    private Optional<MappedStatement> withoutMemoInsuranceLine(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                               ShareCapital shares) {
        int ctx = durationIndex(column);
        for (String label : INSURANCE_EXPENSE_LINES) {
            BigDecimal raw = income.value(label, ctx);
            if (raw == null || raw.signum() == 0 || overrides.containsKey(label)
                    || IncomeLineCategory.FINANCIAL_KNOWN.get(label) == null) {
                continue;
            }
            Map<String, IncomeLineCategory> without = new LinkedHashMap<>(overrides);
            without.put(label, IncomeLineCategory.IGNORE);
            MappedStatement retry = incomeStatementAsFiled(column, without, shares).orElseThrow();
            if (retry.checks().stream().noneMatch(Check::isError)) {
                List<Check> checks = new ArrayList<>(retry.checks());
                checks.add(Check.warning("memo_line", "'" + label + "' " + amount(raw).stripTrailingZeros().toPlainString()
                        + " is not deducted in the filing's '" + PROFIT_FROM_OPERATION + "' of this column (shown for "
                        + "information; the premiums are net of it); ignored"));
                Map<String, String> how = new LinkedHashMap<>(retry.derivations());
                how.put("memo_line", "'" + label + "' ignored: '" + PROFIT_FROM_OPERATION
                        + "' only reconciles without it (insurance premiums reported net of it)");
                return Optional.of(new MappedStatement(retry.table(), retry.column(), retry.period(), retry.sourceSheet(),
                        retry.values(), how, checks, retry.unclassified()));
            }
        }
        return Optional.empty();
    }

    /**
     * A line one column may report a second time: {@code ignored} is left out, {@code kept} counts the amount.
     *
     * @param relation how the two lines relate, for the warning ("report the same amount in this column", ...)
     */
    private record Repeat(String ignored, String kept, String relation) {

        static final String SAME = "report the same amount in this column";

        Repeat(String ignored, String kept) {
            this(ignored, kept, SAME);
        }
    }

    /**
     * Pairs {unsigned line, signed line} of one column that carry the same contribution to profit before tax: an
     * expense (or income) line and a signed gains / losses line of the opposite (same) sign and equal size.
     */
    private List<Repeat> doubleReportedLines(int ctx, Map<String, IncomeLineCategory> overrides) {
        Map<String, IncomeLineCategory> known = financial ? IncomeLineCategory.FINANCIAL_KNOWN : IncomeLineCategory.KNOWN;
        Map<String, BigDecimal> unsigned = new LinkedHashMap<>();   // label -> contribution to profit before tax
        Map<String, BigDecimal> signed = new LinkedHashMap<>();
        Map<String, IncomeLineCategory> categories = new LinkedHashMap<>();
        for (StatementTable.Line line : income.lines()) {
            BigDecimal raw = asCost(line.label(), line.value(ctx));
            IncomeLineCategory category = overrides.getOrDefault(line.label(), known.get(line.label()));
            if (raw == null || raw.signum() == 0 || category == null || overrides.containsKey(line.label())) {
                continue;   // a classification given by the agent is never second-guessed
            }
            categories.putIfAbsent(line.label(), category);
            if (EXPENSE_CATEGORIES.contains(category)) {
                unsigned.putIfAbsent(line.label(), raw.negate());
            } else if (INCOME_CATEGORIES.contains(category)) {
                unsigned.putIfAbsent(line.label(), raw);
            } else if (category == IncomeLineCategory.NON_OPERATING_GAIN_OR_LOSS) {
                signed.putIfAbsent(line.label(), raw);
            }
        }
        List<Repeat> pairs = new ArrayList<>();
        unsigned.forEach((u, contribution) -> signed.forEach((s, value) -> {
            if (contribution.compareTo(value) == 0) {
                pairs.add(new Repeat(u, s));
            }
        }));
        // two expense (income) lines with the same amount, one of them a residual "Other ..." line (CEKA H1 2025:
        // "Interest and finance costs" 79,134 and "Other expenses" 79,134; interest paid 79,134): the residual
        // line is the repeat
        unsigned.forEach((a, ca) -> unsigned.forEach((b, cb) -> {
            if (!a.equals(b) && ca.compareTo(cb) == 0 && isResidual(a) && !isResidual(b)) {
                pairs.add(new Repeat(a, b));
            }
        }));
        // two lines of the same kind with the same amount, neither a residual line (WIIM H1 2025 in its H1 2026
        // filing: "Finance income" 5,797,003,720 and "Interest income" 5,797,003,720; profit before tax reconciles
        // with the amount counted once): the line filed first is the repeat
        List<String> labels = new ArrayList<>(unsigned.keySet());
        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                String a = labels.get(i);
                String b = labels.get(j);
                if (!isResidual(a) && !isResidual(b) && categories.get(a) == categories.get(b)
                        && unsigned.get(a).compareTo(unsigned.get(b)) == 0) {
                    pairs.add(new Repeat(a, b));
                }
            }
        }
        // a residual line that repeats the net of the other residual lines (TINS FY2024 in its FY2025 filing:
        // "Other expenses" 18,883 = -("Other income" 2,250 + "Other gains (losses)" -21,133); profit before tax
        // 1,657,269 reconciles with the two, not with all three)
        Map<String, BigDecimal> residual = new LinkedHashMap<>();
        unsigned.forEach((label, contribution) -> {
            if (isResidual(label)) {
                residual.put(label, contribution);
            }
        });
        signed.forEach((label, value) -> {
            if (isResidual(label)) {
                residual.putIfAbsent(label, value);
            }
        });
        if (residual.size() >= 3) {
            residual.forEach((label, contribution) -> {
                List<String> others = residual.keySet().stream().filter(l -> !l.equals(label)).toList();
                BigDecimal net = others.stream().map(residual::get).reduce(ZERO, BigDecimal::add);
                if (unsigned.containsKey(label) && net.compareTo(contribution) == 0) {
                    pairs.add(new Repeat(label, String.join(" + ", others)));
                }
            });
        }
        // a final tax deducted above profit before tax and again as the tax below it (COIN H1 2025 in its H1 2026
        // filing: "Final tax expenses" 48,187,537 and "Tax benefit (expenses)" -48,187,537; profit before tax
        // 25,573,185,374 is the other lines without it)
        BigDecimal tax = income.value(TAX, ctx);
        unsigned.forEach((label, contribution) -> {
            if (categories.get(label) == IncomeLineCategory.FINAL_TAX_EXPENSE && tax != null && contribution.compareTo(tax) == 0) {
                pairs.add(new Repeat(label, TAX));
            }
        });
        // a residual line that is a part of the signed residual line (DEWA H1 2025 in its H1 2026 filing: "Other income"
        // 1,543,226 within "Other gains (losses)" 2,327,443 = 1,543,226 + the 784,217 gain on the fixed assets sold for
        // 784,217; profit before tax 246,402,270 counts it once, in the gains)
        unsigned.forEach((u, contribution) -> signed.forEach((s, value) -> {
            if (isResidual(u) && isResidual(s) && contribution.signum() == value.signum()
                    && value.abs().compareTo(contribution.abs()) > 0) {
                pairs.add(new Repeat(u, s, "report one amount twice in this column (the first is part of the second)"));
            }
        }));
        // and the other way round: the signed residual line a part of a residual income or expense line of its
        // direction (the H1 2025 columns of the H1 2026 filings: MDKA "Other gains (losses)" 3,434,017 within "Other
        // income" 5,849,538, profit before tax 24,671,192 without it; ITMG "Other gains (losses)" -144 within "Other
        // expenses" 1,644 thousand, profit before tax 137,383 without it, while the H1 2026 column files no such line)
        signed.forEach((s, value) -> unsigned.forEach((u, contribution) -> {
            if (isResidual(u) && isResidual(s) && contribution.signum() == value.signum()
                    && contribution.abs().compareTo(value.abs()) > 0) {
                pairs.add(new Repeat(s, u, "report one amount twice in this column (the first is part of the second)"));
            }
        }));
        // one amount on two residual lines a filed unit apart (ENRG H1 2025 in its H1 2026 filing: "Other income"
        // 1,903,917 and "Other gains (losses)" 1,903,918; profit before tax 58,365,683 counts it once, as the
        // gains): either may be the repeat, the reconciliation decides
        unsigned.forEach((u, contribution) -> signed.forEach((s, value) -> {
            if (isResidual(u) && isResidual(s) && contribution.subtract(value).abs().compareTo(BigDecimal.ONE) == 0) {
                String relation = "report the same amount in this column, a filed unit apart";
                pairs.add(new Repeat(u, s, relation));
                pairs.add(new Repeat(s, u, relation));
            }
        }));
        return pairs;
    }

    /** A bank cost line filed with either sign ({@link IncomeLineCategory#FINANCIAL_UNSIGNED_COSTS}) as a positive cost. */
    private BigDecimal asCost(String label, BigDecimal raw) {
        return raw != null && financial && IncomeLineCategory.FINANCIAL_UNSIGNED_COSTS.contains(label) ? raw.abs() : raw;
    }

    /** "Other income", "Other expenses", ...: a catch-all line of the template. */
    private static boolean isResidual(String label) {
        return label.toLowerCase(Locale.ROOT).startsWith("other ");
    }

    private Optional<MappedStatement> incomeStatementAsFiled(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                             ShareCapital shares) {
        return incomeStatementAsFiled(column, overrides, Map.of(), shares);
    }

    /** @param less amounts (as filed) a line is counted without: a part filed again within it ({@link #partsOfResidualLines}) */
    private Optional<MappedStatement> incomeStatementAsFiled(StatementColumn column, Map<String, IncomeLineCategory> overrides,
                                                             Map<String, BigDecimal> less, ShareCapital shares) {
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
            BigDecimal raw = asCost(line.label(), line.value(ctx));
            if (raw == null || STRUCTURAL_INCOME_LINES.contains(line.label())) {
                continue;
            }
            if (less.containsKey(line.label())) {
                raw = raw.subtract(less.get(line.label()));
            }
            IncomeLineCategory category = overrides.getOrDefault(line.label(), known.get(line.label()));
            if (category == null) {
                if (profitIndex >= 0 && i > profitIndex) {
                    continue;   // other comprehensive income / comprehensive totals: not profit or loss
                }
                if (raw.signum() == 0) {
                    continue;   // filed as 0 (BBCA files 0 on its unused insurance lines): nothing to classify
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
        BigDecimal breakdown = revenue == null && cost == null && grossReported != null ? revenueBreakdownTotal(column) : null;
        if (breakdown != null) {
            // only the gross profit is tagged, but the filing's own revenue breakdown gives the revenue (ADRO FY2023:
            // 6,517,556,000 by type, gross profit 2,537,284,000, cost 3,980,272,000 in the notes). Left empty, the
            // FY2024 filing's comparative - restated for the discontinued coal business, 2,135,412,000 - stayed
            // beside this filing's gross profit and its 6,517,556,000 breakdown
            revenue = breakdown;
            cost = breakdown.subtract(grossReported);
            checks.add(Check.warning("revenue", "'Sales and revenue' and its cost are not tagged in this column (only '"
                    + GROSS_PROFIT + "'); revenue " + breakdown.toPlainString() + " from the revenue breakdown, cost of "
                    + "revenue = revenue - gross profit"));
            how.put("revenue", "not tagged on the face of this filing (only gross profit): the total of its revenue breakdown");
            how.put("cost_of_revenue", "revenue (from the breakdown) - gross profit");
        } else if (revenue == null && cost == null && grossReported != null) {
            // some filers tag only the gross profit and no breakdown: the statement still reconciles from it; revenue and
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
        tax = taxSign(pretax, tax, discontinued, net, checks);
        profitChecks(checks, pretax, tax, discontinued, net, parent, nci);

        BigDecimal ebit = pretax == null ? null : pretax.add(financeCost).subtract(financeIncome);
        BigDecimal depreciation = depreciation(column);
        BigDecimal amortization = amortization(column);
        // the D&A the profit or loss states wins: the same in every column (see statedDepreciationAndAmortization)
        BigDecimal statedDa = statedDepreciationAndAmortization(column);
        BigDecimal ebitda = ebit == null ? null : statedDa != null ? ebit.add(statedDa)
                : depreciation == null ? null
                : amortization != null ? ebit.add(depreciation).add(amortization)
                : immaterialIntangibles(column, ebit.add(depreciation)) ? ebit.add(depreciation) : null;

        BigDecimal basicEps = signOfProfit(epsWithDiscontinued(income, EPS_BASIC, EPS_BASIC_DISC, ctx, checks), parent, checks, "Basic");
        BigDecimal dilutedEps = signOfProfit(epsWithDiscontinued(income, EPS_DILUTED, EPS_DILUTED_DISC, ctx, checks), parent, checks, "Diluted");
        BigDecimal basicShares = shares == null ? null
                : column == StatementColumn.CURRENT_PERIOD ? shares.weightedCurrent() : shares.weightedPrior();
        BigDecimal[] reconciled = reconcileEps(basicEps, dilutedEps, parent, basicShares, checks);
        basicEps = reconciled[0];
        dilutedEps = reconciled[1];
        java.util.Set<String> rejected = java.util.Set.of();
        if (epsContradiction() != null) {
            // neither the EPS nor a share count of this filing can be trusted: cleared even where stored before
            checks.add(Check.warning("eps_columns", epsContradiction() + "; the EPS and share count of this column are not stored"));
            basicEps = null;
            dilutedEps = null;
            rejected = java.util.Set.of("basic_eps", "diluted_eps", "basic_shares");
        }

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
        how.put("ebitda", ebitda == null ? "not derivable: depreciation or amortization not disclosed for this column"
                : statedDa != null ? "ebit + '" + STATED_DEPRECIATION_AND_AMORTISATION + "' as the profit or loss states it"
                : amortization == null ? "ebit + depreciation (roll-forward notes); amortization not derivable, but the "
                + "intangible assets are under 1% of it"
                : "ebit + depreciation + amortization (roll-forward notes)");
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
                v, how, checks, unclassified, rejected));
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
        tax = taxSign(pretax, tax, discontinued, net, checks);
        profitChecks(checks, pretax, tax, discontinued, net, parent, nci);

        BigDecimal depreciation = depreciation(column);
        BigDecimal amortization = amortization(column);
        BigDecimal basicEps = signOfProfit(epsWithDiscontinued(income, EPS_BASIC, EPS_BASIC_DISC, ctx, checks), parent, checks, "Basic");
        BigDecimal dilutedEps = signOfProfit(epsWithDiscontinued(income, EPS_DILUTED, EPS_DILUTED_DISC, ctx, checks), parent, checks, "Diluted");
        BigDecimal basicShares = shares == null ? null
                : column == StatementColumn.CURRENT_PERIOD ? shares.weightedCurrent() : shares.weightedPrior();
        BigDecimal[] reconciled = reconcileEps(basicEps, dilutedEps, parent, basicShares, checks);
        basicEps = reconciled[0];
        dilutedEps = reconciled[1];
        java.util.Set<String> rejected = java.util.Set.of();
        if (epsContradiction() != null) {
            // neither the EPS nor a share count of this filing can be trusted: cleared even where stored before
            checks.add(Check.warning("eps_columns", epsContradiction() + "; the EPS and share count of this column are not stored"));
            basicEps = null;
            dilutedEps = null;
            rejected = java.util.Set.of("basic_eps", "diluted_eps", "basic_shares");
        }

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
        return new MappedStatement("income_statement", column, period(column), income.sheet(), v, how, checks, unclassified,
                rejected);
    }

    /** Why the two columns' EPS contradict each other ({@link #epsColumnsContradict}), {@code null} when they do not. */
    public String epsContradiction() {
        if (!epsChecked) {
            shareCapital();
        }
        return epsContradiction;
    }

    /**
     * The EPS of a column fits a par value when profit attributable to the parent / EPS (within the EPS's rounding)
     * lies in the share count range the share capital gives over the period: from 80% of the smaller count (treasury
     * shares) to 105% of the larger one. Each column with that evidence must fit the same par value. NCKL's FY2024
     * workbook files a current EPS of 10.11 (101.1, one more decimal place off): with share capital 6,309,860 million
     * it fits only par 10 (631 billion shares), while the prior column's 92.39 fits only par 100 (share capital
     * 5,510,100 .. 6,309,860 million = 55.1 .. 63.1 billion shares). Which column is wrong is not decidable from the
     * workbook, so it gives no share counts and neither column's EPS is stored; the other filings of the company
     * agree and supply them. {@code null} when the columns do not contradict (or cannot be checked).
     */
    private String epsColumnsContradict(BigDecimal[] currentStartEnd, BigDecimal[] priorStartEnd) {
        List<BigDecimal> current = supportedPars(StatementColumn.CURRENT_PERIOD, currentStartEnd, BigDecimal.ONE);
        List<BigDecimal> prior = supportedPars(StatementColumn.PRIOR_PERIOD, priorStartEnd, BigDecimal.ONE);
        if (current == null || prior == null) {
            return null;
        }
        // A column that fits no par value as filed may have its EPS in the rounding unit (INDF's H1 2025 workbook files
        // the H1 2024 EPS 439 as 0.000439 in millions): it is corrected within the column once the par value is known,
        // and is judged at that unit here. It must then fit the other column's par value. MOLI's FY2025 workbook files
        // 0.277 (Rp 27.70) and 0.00475 (Rp 4.75): the current EPS fits only par 1, the prior one only par 100 in
        // thousands - par 1 gave 272,403,658,000 shares (100 times too many) and a share split 1:100 that never was.
        String inUnit = "";
        if (current.isEmpty() != prior.isEmpty() && unit.compareTo(BigDecimal.ONE) > 0) {
            if (prior.isEmpty()) {
                prior = supportedPars(StatementColumn.PRIOR_PERIOD, priorStartEnd, unit);
                inUnit = " (" + period(StatementColumn.PRIOR_PERIOD).key();
            } else {
                current = supportedPars(StatementColumn.CURRENT_PERIOD, currentStartEnd, unit);
                inUnit = " (" + period(StatementColumn.CURRENT_PERIOD).key();
            }
            inUnit += ": fits no par value as filed, only multiplied by the rounding unit " + unit.toPlainString() + ")";
        }
        List<BigDecimal> priorPars = prior;
        if (current.isEmpty() || prior.isEmpty() || current.stream().anyMatch(p -> priorPars.stream().anyMatch(q -> q.compareTo(p) == 0))) {
            return null;
        }
        return "The two columns' EPS contradict each other: " + period(StatementColumn.CURRENT_PERIOD).key() + " basic EPS "
                + eps(EPS_BASIC, 0).toPlainString() + " fits par value " + plain(current) + ", "
                + period(StatementColumn.PRIOR_PERIOD).key() + " basic EPS " + eps(EPS_BASIC, 1).toPlainString()
                + " fits par value " + plain(prior) + inUnit + "; no share counts are derived from this filing";
    }

    /**
     * Why a par value inferred from one column's EPS does not fit the other column's ({@link #supportedPars}, also in
     * the rounding unit), {@code null} when it does or that column gives no evidence. A coarse EPS fits several share
     * counts: BKSL's FY2025 workbook files FY2024's EPS as 0.10 (17,355 million profit: 116 .. 347 billion shares),
     * which par 50 fits (12,562,189 million share capital = 251 billion shares), while FY2025's 4.95 (830,295 million
     * profit: 167.7 billion shares) fits no standard par value - its shares have several par values. Par 50 made
     * every count 50% too high; without a par value the published counts, checked against the EPS, are used.
     * Only a column whose share capital did not change in the period is evidence: OMED's FY2021 capital went from
     * 4,500 to 575,000 million and its EPS is restated for that, beyond the counts the capital gives.
     */
    private String parMisfit(BigDecimal par, StatementColumn column, BigDecimal[] startEnd) {
        if (startEnd == null || startEnd[0] == null || startEnd[1] == null || startEnd[0].compareTo(startEnd[1]) != 0) {
            return null;
        }
        List<BigDecimal> fits = supportedPars(column, startEnd, BigDecimal.ONE);
        if (fits == null || fits.stream().anyMatch(p -> p.compareTo(par) == 0)) {
            return null;
        }
        if (unit.compareTo(BigDecimal.ONE) > 0) {
            List<BigDecimal> inUnit = supportedPars(column, startEnd, unit);
            if (inUnit != null && inUnit.stream().anyMatch(p -> p.compareTo(par) == 0)) {
                return null;
            }
        }
        BigDecimal shares = startEnd[1].divide(par, 0, RoundingMode.HALF_UP);
        BigDecimal parent = amount(shareProfitOrLoss().value(PROFIT_PARENT, durationIndex(column)));
        BigDecimal eps = eps(EPS_BASIC, durationIndex(column));
        return "Par value " + par.toPlainString() + " fits only one column's EPS: " + period(column).key()
                + " share capital " + startEnd[1].toPlainString() + " / " + par.toPlainString() + " = "
                + shares.toPlainString() + " shares give profit attributable to the parent / shares = "
                + parent.divide(shares, Math.max(0, eps.scale()) + 2, RoundingMode.HALF_UP).toPlainString()
                + ", but the filed basic EPS is " + eps.toPlainString() + " (fits par value " + plain(fits)
                + "); no par value is used";
    }

    private static String plain(List<BigDecimal> values) {
        return values.stream().map(BigDecimal::toPlainString).toList().toString();
    }

    /**
     * Standard par values the column's EPS fits, {@code null} without evidence (no EPS, profit or share capital).
     *
     * @param factor 1 for the EPS as filed (scaled), the rounding unit for an EPS filed in it
     */
    private List<BigDecimal> supportedPars(StatementColumn column, BigDecimal[] startEnd, BigDecimal factor) {
        int ctx = durationIndex(column);
        StatementTable profitOrLoss = shareProfitOrLoss();
        if (profitOrLoss == null || !rupiahShareCapital() || ctx < 0 || startEnd == null || startEnd[0] == null
                || startEnd[1] == null) {
            return null;
        }
        BigDecimal parent = amount(profitOrLoss.value(PROFIT_PARENT, ctx));
        BigDecimal eps = eps(EPS_BASIC, ctx);
        if (parent == null || eps == null || parent.signum() <= 0 || eps.signum() <= 0) {
            return null;
        }
        eps = eps.multiply(factor);
        BigDecimal halfUlp = epsHalfUlp(profitOrLoss, EPS_BASIC, ctx).multiply(factor);
        BigDecimal low = parent.divide(eps.add(halfUlp), MathContext.DECIMAL64);
        BigDecimal high = eps.subtract(halfUlp).signum() > 0 ? parent.divide(eps.subtract(halfUlp), MathContext.DECIMAL64) : null;
        BigDecimal min = startEnd[0].min(startEnd[1]);
        BigDecimal max = startEnd[0].max(startEnd[1]);
        List<BigDecimal> fits = new ArrayList<>();
        for (BigDecimal par : STANDARD_PAR_VALUES) {
            BigDecimal from = min.divide(par, MathContext.DECIMAL64).multiply(new BigDecimal("0.80"));
            BigDecimal to = max.divide(par, MathContext.DECIMAL64).multiply(new BigDecimal("1.05"));
            if ((high == null || high.compareTo(from) >= 0) && low.compareTo(to) <= 0) {
                fits.add(par);
            }
        }
        return fits;
    }

    /**
     * An EPS filed without the sign of its loss (TINS FY2023: 60 per share for a loss of 449,690 million attributable
     * to the parent, filed as 0.00006 in millions) or with a wrong one: earnings per share is the profit attributable
     * to the parent divided by a share count, so it has the profit's sign. Stored with it; a warning says so.
     */
    private static BigDecimal signOfProfit(BigDecimal eps, BigDecimal parent, List<Check> checks, String which) {
        if (eps == null || parent == null || eps.signum() == 0 || parent.signum() == 0 || eps.signum() == parent.signum()) {
            return eps;
        }
        checks.add(Check.warning("eps_sign", which + " EPS " + eps.toPlainString() + " is filed with the opposite sign of the "
                + "profit attributable to the parent (" + parent.toPlainString() + "); stored as " + eps.negate().toPlainString()));
        return eps.negate();
    }

    private static final BigDecimal EPS_MISMATCH = new BigDecimal("3");

    /**
     * Checks the filed EPS against the filing's own share count (weighted shares from the share capital, known when
     * it did not change in the period). Within a factor of 3 the EPS is kept as filed (the usual check then reports
     * small differences). A filed EPS that is off by a power of ten - NCKL's FY2024 workbook files 0.00001011,
     * i.e. 10.11 after its rounding unit, for an EPS of 101.1 (6,379,504 million / 63.1 billion shares; its FY2025
     * workbook's comparative says 101.1) - is corrected when profit / shares then confirms it within 2%. Any other
     * large mismatch is not stored (NULL, so a later filing's comparative can fill it). Diluted EPS follows basic.
     */
    private static BigDecimal[] reconcileEps(BigDecimal basic, BigDecimal diluted, BigDecimal parent, BigDecimal shares,
                                             List<Check> checks) {
        if (basic == null || parent == null || shares == null || shares.signum() <= 0 || basic.signum() == 0
                || parent.signum() == 0 || parent.signum() != basic.signum()) {
            return new BigDecimal[] {basic, diluted};
        }
        BigDecimal perShare = parent.divide(shares, MathContext.DECIMAL64);
        BigDecimal ratio = perShare.divide(basic, MathContext.DECIMAL64);    // how many times the filed EPS is too small
        if (ratio.compareTo(EPS_MISMATCH) < 0 && ratio.compareTo(BigDecimal.ONE.divide(EPS_MISMATCH, MathContext.DECIMAL64)) > 0) {
            return new BigDecimal[] {basic, diluted};
        }
        int k = (int) Math.round(Math.log10(ratio.doubleValue()));
        BigDecimal factor = BigDecimal.ONE.scaleByPowerOfTen(k);
        if (k != 0 && ratio.divide(factor, MathContext.DECIMAL64).subtract(BigDecimal.ONE).abs()
                .compareTo(WEB_SHARES_EPS_TOLERANCE) <= 0) {
            BigDecimal corrected = normalized(basic.multiply(factor));
            checks.add(Check.warning("eps_scale", "Basic EPS " + basic.toPlainString() + " is " + factor.toPlainString()
                    + " times off: profit attributable to the parent / " + shares.toPlainString() + " shares = "
                    + perShare.setScale(4, RoundingMode.HALF_UP).toPlainString() + "; stored as " + corrected.toPlainString()));
            return new BigDecimal[] {corrected, diluted == null ? null : normalized(diluted.multiply(factor))};
        }
        checks.add(Check.warning("eps", "Basic EPS " + basic.toPlainString() + " contradicts profit attributable to the parent / "
                + shares.toPlainString() + " shares = " + perShare.setScale(4, RoundingMode.HALF_UP).toPlainString()
                + "; EPS not stored"));
        return new BigDecimal[] {null, null};
    }

    private static BigDecimal normalized(BigDecimal v) {
        BigDecimal n = v.stripTrailingZeros();
        return n.scale() < 0 ? n.setScale(0) : n;
    }

    /**
     * A tax filed on 'Tax benefit (expenses)' with the wrong sign (HEAL FY2023: an expense filed as 151,109 million
     * beside the prior year's -101,315; profit before tax 710,393 - 151,109 = total profit 559,284). Read with the
     * other sign when only that makes profit before tax + tax + discontinued operations give the total profit; a
     * warning says so.
     */
    private BigDecimal taxSign(BigDecimal pretax, BigDecimal tax, BigDecimal discontinued, BigDecimal net, List<Check> checks) {
        if (pretax == null || tax == null || net == null || tax.signum() == 0) {
            return tax;
        }
        String rule = "profit before tax + tax + discontinued operations = total profit";
        if (!equal("net_income", rule, pretax.add(tax).add(nz(discontinued)), net).isError()
                || equal("net_income", rule, pretax.subtract(tax).add(nz(discontinued)), net).isError()) {
            return tax;
        }
        checks.add(Check.warning("tax_sign", "'" + TAX + "' " + tax.toPlainString() + " is filed with the wrong sign: profit "
                + "before tax " + pretax.toPlainString() + (tax.signum() > 0 ? " - " : " + ") + tax.abs().toPlainString()
                + " is the total profit " + net.toPlainString() + "; read as " + tax.negate().toPlainString()));
        return tax.negate();
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

    /**
     * Current financial assets outside cash (time deposits, restricted funds, short-term investments) of a
     * column's balance sheet, which some issuers count as cash in the cash flow statement; {@code null} when
     * none is reported. An insurer's balance sheet (Insurance Industry, 6220000) is not split into current and
     * non-current: its time deposits, which its cash flow statement counts as cash when they mature within three months
     * (AMAG FY2025: ending cash 485,425,155 thousand beside cash 64,021,213 and time deposits 651,473,868; ASDM).
     */
    public BigDecimal currentFinancialAssetsOutsideCash(StatementColumn column) {
        int ctx = instantIndex(column);
        if (ctx >= 0 && balanceSheet != null && taxonomy == IdxTaxonomy.INSURANCE) {
            return sumOrNull(balanceSheet, List.of("Investments in time deposits"), ctx);
        }
        if (ctx < 0 || balanceSheet == null || financial) {
            return null;
        }
        return sumOrNull(balanceSheet, List.of("Other current financial assets", "Current other financial assets",
                "Short-term investments", "Current restricted funds"), ctx);
    }

    /** Balance-sheet rows of a financing company's balances with banks (Financing Industry, 8220000). */
    private static final List<String> BANK_BALANCE_PREFIXES = List.of("Current accounts with ", "Placements with ");

    /**
     * @param balanceSheetCash the balance sheet's 'Cash and cash equivalents' row
     * @param cash             the cash flow statement's cash and cash equivalents at the end of the period
     * @param rows             the bank rows that make up the difference
     */
    public record CashWithBanks(BigDecimal balanceSheetCash, BigDecimal cash, List<String> rows) {
    }

    /**
     * Cash and cash equivalents of a financing company (Financing Industry taxonomy) as its cash flow statement counts
     * them, where its balance sheet splits them: the row 'Cash and cash equivalents' holds the cash alone and the current
     * accounts and placements with banks are rows of their own (ADMF FY2025: 77,878 + 176,714 + 566,324 = 820,916 million,
     * the ending cash; DEFI FY2023: 8,801,000 + 4,487,369,146 + 8,500,000,000 = 12,996,170,146). Only when the ending cash
     * is the row plus some of those bank rows, exactly or within one filed unit; {@code null} otherwise (BFIN: the row is
     * the ending cash already;
     * SMMA: the ending cash counts more than its bank rows give).
     */
    public CashWithBanks cashWithBankBalances(StatementColumn column) {
        int ctx = instantIndex(column);
        int flow = durationIndex(column);
        if (taxonomy != IdxTaxonomy.FINANCING || ctx < 0 || flow < 0 || balanceSheet == null || cashFlow == null) {
            return null;
        }
        BigDecimal cash = amount(balanceSheet.value("Cash and cash equivalents", ctx));
        BigDecimal ending = amount(cashFlow.value(CASH_END, flow));
        if (cash == null || ending == null || ending.compareTo(cash) <= 0) {
            return null;
        }
        List<StatementTable.Line> banks = balanceSheet.lines().stream()
                .filter(l -> BANK_BALANCE_PREFIXES.stream().anyMatch(l.label()::startsWith))
                .filter(l -> l.value(ctx) != null && l.value(ctx).signum() != 0)
                .toList();
        if (banks.isEmpty() || banks.size() > 12) {
            return null;
        }
        BigDecimal gap = ending.subtract(cash);
        // exactly, else within one filed unit: each cell rounded on its own (HDFA H1 2026, thousands: 5,013 + 56,101,280 =
        // 56,106,293 beside an ending cash of 56,106,292; its prior year end adds up exactly, 4,492 + 149,282,128 =
        // 149,286,620), as IngestionVerifier.roundingExplanation accepts between the two statements
        for (BigDecimal slack : List.of(ZERO, unit)) {
            // the most rows first: a placement counted in the cash flow is counted with the current accounts
            for (int size = banks.size(); size > 0; size--) {
                for (int mask = 1; mask < (1 << banks.size()); mask++) {
                    if (Integer.bitCount(mask) != size) {
                        continue;
                    }
                    BigDecimal sum = ZERO;
                    List<String> rows = new ArrayList<>();
                    for (int i = 0; i < banks.size(); i++) {
                        if ((mask & (1 << i)) != 0) {
                            sum = sum.add(amount(banks.get(i).value(ctx)));
                            rows.add(banks.get(i).label());
                        }
                    }
                    if (sum.subtract(gap).abs().compareTo(slack) <= 0) {
                        return new CashWithBanks(cash, ending, rows);
                    }
                }
            }
        }
        return null;
    }

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
        BigDecimal proformaEquity = sum(b, List.of(PROFORMA_EQUITY), ctx);
        BigDecimal nci = sum(b, List.of("Non-controlling interests"), ctx).add(proformaEquity);
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
        if (proformaEquity.signum() != 0) {
            checks.add(Check.warning("proforma_equity", "'" + PROFORMA_EQUITY + "' " + proformaEquity.stripTrailingZeros().toPlainString()
                    + " (equity of an entity combined under common control, restated into this column) is not attributable"
                    + " to the owners of the parent; counted with the non-controlling interests"));
        }
        if (!b.duplicates().isEmpty()) {
            checks.add(Check.warning("duplicates", "Labels reported on more than one row: " + b.duplicates()));
        }

        Map<String, BigDecimal> v = new LinkedHashMap<>();
        v.put("cash_and_equivalents", sum(b, List.of("Cash and cash equivalents"), ctx));
        v.put("marketable_securities", sumOrNull(b, MARKETABLE_SECURITIES, ctx));
        v.put("accounts_receivable", sum(b, List.of("Trade receivables third parties", "Trade receivables related parties"), ctx));
        // a property developer's inventory is its land, houses and apartments for sale (CBDK, PWON)
        v.put("inventory", sum(b, List.of("Current inventories", "Current real estate assets"), ctx));
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
        withoutRowsMissingOnTheFace(b, ctx, v, how, checks);
        return Optional.of(new MappedStatement("balance_sheet", column, period(column), b.sheet(), v, how, checks, List.of(),
                rejectedShares(shares)));
    }

    /** A section of the balance sheet: its header and total rows, and the fields made up of its rows. */
    private record Section(String header, String total, Map<String, List<String>> fields) {
    }

    private static final List<Section> SECTIONS = List.of(
            new Section("Current assets", "Total current assets", Map.of(
                    "cash_and_equivalents", List.of("Cash and cash equivalents"),
                    "accounts_receivable", List.of("Trade receivables third parties", "Trade receivables related parties"),
                    "inventory", List.of("Current inventories", "Current real estate assets"))),
            new Section("Non-current assets", "Total non-current assets", Map.of(
                    "goodwill", List.of("Goodwill"),
                    "intangible_assets", List.of("Intangible assets other than goodwill"))),
            new Section("Current liabilities", "Total current liabilities", Map.of(
                    "accounts_payable", List.of("Trade payables third parties", "Trade payables related parties"),
                    "deferred_revenue", List.of("Current advances from customers third parties",
                            "Current advances from customers related parties", "Current contract liabilities",
                            "Current deferred revenue"),
                    "short_term_debt", SHORT_TERM_DEBT,
                    "lease_liabilities", LEASES)),
            new Section("Non-current liabilities", "Total non-current liabilities", Map.of(
                    "long_term_debt", LONG_TERM_DEBT,
                    "lease_liabilities", LEASES)));

    /**
     * A field of a balance sheet section whose rows fall short of its filed total, and none of whose rows is filed, is
     * not known to be 0: rows are missing on the face of the balance sheet. It is left empty (a value another filing
     * stored stays) and a warning says so. LPPF FY2023 files 'Total current assets' 1,448,030 million beside rows of
     * 655,249 - no 'Current inventories', 792,781 in its inventory note and in the FY2024 comparative - and 'Total
     * current liabilities' 2,952,977 beside rows of 2,402,974, without its bank loans of 550,003 (the bank loan note);
     * the upload had stored inventory and short-term debt as 0 over the comparative's figures. A gap within the
     * filing's rounding (one unit per row) is not a missing row.
     */
    private void withoutRowsMissingOnTheFace(StatementTable b, int ctx, Map<String, BigDecimal> v, Map<String, String> how,
                                             List<Check> checks) {
        for (MissingRows missing : rowsMissingOnTheFace(b, ctx)) {
            for (String field : missing.unknown()) {
                v.put(field, null);
                how.put(field, "not stored: none of its rows is filed, and the rows of '" + missing.total()
                        + "' fall short of it - rows are missing on the face of the balance sheet");
            }
            checks.add(Check.warning("missing_rows", "'" + missing.total() + "' " + missing.filed().toPlainString()
                    + " is more than the sum of its rows " + missing.rows().toPlainString() + " ("
                    + missing.filed().subtract(missing.rows()).toPlainString() + " not on the face of the balance sheet)"
                    + (missing.unknown().isEmpty() ? "" : "; " + String.join(", ", missing.unknown()) + " not stored as 0")));
        }
    }

    /**
     * A section of the balance sheet whose rows fall short of its filed total.
     *
     * @param unknown fields of the section none of whose rows is filed
     */
    private record MissingRows(String total, BigDecimal filed, BigDecimal rows, List<String> unknown) {
    }

    private List<MissingRows> rowsMissingOnTheFace(StatementTable b, int ctx) {
        List<MissingRows> result = new ArrayList<>();
        for (Section section : SECTIONS) {
            int from = b.indexOf(section.header());
            int to = b.indexOf(section.total());
            BigDecimal total = amount(b.value(section.total(), ctx));
            if (from < 0 || to <= from || total == null) {
                continue;
            }
            BigDecimal rows = ZERO;
            int filed = 0;
            for (StatementTable.Line line : b.lines().subList(from + 1, to)) {
                BigDecimal value = amount(line.value(ctx));
                if (value != null) {
                    rows = rows.add(value);
                    filed++;
                }
            }
            BigDecimal gap = total.subtract(rows);
            if (gap.compareTo(precision.max(BigDecimal.ONE).multiply(BigDecimal.valueOf(Math.max(filed, 1)))) <= 0) {
                continue;
            }
            List<String> unknown = new ArrayList<>();
            section.fields().forEach((field, labels) -> {
                if (labels.stream().noneMatch(l -> b.value(l, ctx) != null)) {
                    unknown.add(field);
                }
            });
            unknown.sort(null);
            result.add(new MissingRows(section.total(), total, rows, List.copyOf(unknown)));
        }
        return result;
    }

    /**
     * Balance sheet fields of a column that are not known to be 0 although none of their rows is filed: rows are missing
     * on the face of its balance sheet ({@link #withoutRowsMissingOnTheFace}). Empty for a bank.
     */
    public java.util.Set<String> fieldsMissingOnTheFace(StatementColumn column) {
        int ctx = instantIndex(column);
        if (ctx < 0 || balanceSheet == null || financial) {
            return java.util.Set.of();
        }
        java.util.Set<String> fields = new java.util.TreeSet<>();
        rowsMissingOnTheFace(balanceSheet, ctx).forEach(m -> fields.addAll(m.unknown()));
        return fields;
    }

    private static String sharesHow(BigDecimal outstanding, ShareCapital shares) {
        if (outstanding == null) {
            return "not derivable";
        }
        return shares.webSource() != null
                ? "shares outstanding at period end from " + shares.webSource() + ", checked against the filing's EPS"
                : shares.parValue() != null ? "common stock / par value, no treasury shares" : shares.basis();
    }

    /** A filing whose EPS columns contradict each other gives no share count: a count stored from it before is cleared. */
    private java.util.Set<String> rejectedShares(ShareCapital shares) {
        boolean fromWeb = shares != null && shares.webSource() != null;
        return epsContradiction() != null && !fromWeb ? java.util.Set.of("shares_outstanding") : java.util.Set.of();
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
    /** Sum of the lines between 'Temporary syirkah funds' and its total row; {@code null} when none is reported. */
    private BigDecimal syirkahParts(StatementTable b, int ctx) {
        int from = b.indexOf("Temporary syirkah funds");
        int to = b.indexOf("Total temporary syirkah funds");
        if (from < 0 || to <= from) {
            return null;
        }
        BigDecimal sum = null;
        for (StatementTable.Line line : b.lines().subList(from + 1, to)) {
            BigDecimal v = amount(line.value(ctx));
            if (v != null) {
                sum = sum == null ? v : sum.add(v);
            }
        }
        return sum;
    }

    private MappedStatement financialBalanceSheet(StatementColumn column, int ctx, ShareCapital shares) {
        StatementTable b = balanceSheet;
        List<Check> checks = new ArrayList<>();
        BigDecimal totalAssets = req(b, "Total assets", ctx, checks);
        BigDecimal liabilities = req(b, "Total liabilities", ctx, checks);
        BigDecimal syirkah = sum(b, List.of("Total temporary syirkah funds"), ctx);
        BigDecimal parentEquity = req(b, "Total equity attributable to equity owners of parent entity", ctx, checks);
        BigDecimal proformaEquity = sum(b, List.of(PROFORMA_EQUITY), ctx);
        BigDecimal nci = sum(b, List.of("Non-controlling interests"), ctx).add(proformaEquity);
        BigDecimal totalEquity = req(b, "Total equity", ctx, checks);
        BigDecimal liabilitiesAndEquity = amount(b.value("Total liabilities, temporary syirkah funds and equity", ctx));
        BigDecimal syirkahReported = sumOrNull(b, List.of("Total temporary syirkah funds"), ctx);
        BigDecimal syirkahParts = syirkahReported == null ? syirkahParts(b, ctx) : null;
        if (syirkahParts != null && liabilities != null && totalEquity != null && totalAssets != null
                && liabilities.add(syirkahParts).add(totalEquity).compareTo(totalAssets) == 0) {
            // the total row left empty, its parts filed (ARTO FY2022: mudharabah time deposits 269,558 + 256,501 =
            // 526,059 million, the total entered on the next row, "Total accumulated tabarru's funds"): the sum of
            // the parts, when the balance sheet then balances exactly
            checks.add(Check.warning("syirkah", "'Total temporary syirkah funds' is not reported; the sum of its lines, "
                    + syirkahParts.stripTrailingZeros().toPlainString() + ", balances the balance sheet"));
            syirkah = syirkahParts;
            syirkahReported = syirkahParts;
        }
        BigDecimal tabarru = sumOrNull(b, List.of("Total accumulated tabarru's funds"), ctx);
        if (tabarru != null && tabarru.signum() != 0 && liabilities != null && totalEquity != null && totalAssets != null
                && liabilities.add(syirkah).add(totalEquity).compareTo(totalAssets) != 0
                && liabilities.add(syirkah).add(tabarru).add(totalEquity).compareTo(totalAssets) == 0) {
            // a Sharia insurer's participants' fund between the liabilities and the equity, as the syirkah funds are
            // (CASA FY2025: 24,667,114 + 2,207,986 + 24,781 + 10,401,954 = 37,301,835 million; FY2022 - H1 2026 alike,
            // errors 1558 - 1572): stored with the temporary syirkah funds. Only where the balance sheet then balances
            // and does not without it - ARTO FY2022 filed its syirkah total on this row instead (above).
            checks.add(Check.warning("tabarru", "'Total accumulated tabarru's funds' " + tabarru.stripTrailingZeros().toPlainString()
                    + " balances the balance sheet; stored with the temporary syirkah funds"));
            syirkah = syirkah.add(tabarru);
            syirkahReported = syirkahReported == null ? tabarru : syirkahReported.add(tabarru);
        }
        if (liabilities != null && totalEquity != null && totalAssets != null) {
            checks.add(equal("balance", "total liabilities + temporary syirkah funds + total equity = total assets",
                    liabilities.add(syirkah).add(totalEquity), totalAssets));
        }
        if (liabilitiesAndEquity != null && totalAssets != null) {
            checks.add(equal("balance_total", "total liabilities, temporary syirkah funds and equity = total assets",
                    liabilitiesAndEquity, totalAssets));
        }
        if (parentEquity != null && totalEquity != null) {
            checks.add(equal("equity", "parent equity + non-controlling interests = total equity", parentEquity.add(nci), totalEquity));
        }
        if (proformaEquity.signum() != 0) {
            checks.add(Check.warning("proforma_equity", "'" + PROFORMA_EQUITY + "' " + proformaEquity.stripTrailingZeros().toPlainString()
                    + " (equity of an entity combined under common control, restated into this column) is not attributable"
                    + " to the owners of the parent; counted with the non-controlling interests"));
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
        v.put("total_liabilities", liabilities);
        v.put("temporary_syirkah_funds", syirkahReported);
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
        how.put("total_liabilities", "'Total liabilities' as filed; temporary syirkah funds (sharia depositors' funds, "
                + "neither liabilities nor equity under PSAK) are stored separately");
        how.put("temporary_syirkah_funds", syirkahReported == null ? "not reported (no sharia unit)"
                : "'Total temporary syirkah funds'; total assets = liabilities + these funds + equity");
        how.put("short_term_debt", "not reported: bank borrowings have no maturity split (all in long_term_debt)");
        how.put("long_term_debt", "borrowings + securities issued (bonds, sukuk, MTN, subordinated bonds) + subordinated "
                + "loans, all maturities; customer deposits, interbank deposits and repos excluded");
        how.put("lease_liabilities", "finance lease liabilities (NULL when not reported separately)");
        how.put("retained_earnings", "appropriated (general, legal and specific reserves) + unappropriated retained earnings");
        how.put("shares_outstanding", sharesHow(outstanding, shares));
        return new MappedStatement("balance_sheet", column, period(column), b.sheet(), v, how, checks, List.of(),
                rejectedShares(shares));
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
        BigDecimal beginning = amount(c.value(CASH_BEGINNING, ctx));
        BigDecimal ending = req(c, CASH_END, ctx, checks);
        correctSectionTotal(c, ctx, totals, change, beginning, ending, checks);
        ending = correctEndingCash(c, ctx, column, totals, change, beginning, ending, checks);
        BigDecimal operating = totals[0];
        BigDecimal investing = totals[1];
        BigDecimal financing = totals[2];
        if (operating != null && investing != null && financing != null && change != null) {
            checks.add(equal("net_change", "operating + investing + financing = net change in cash",
                    operating.add(investing).add(financing), change));
        }
        if (beginning != null && change != null && ending != null) {
            String deconsolidatedLine = "Cash and cash equivalent of deconsolidated subsidiaries";
            BigDecimal deconsolidated = sum(c, List.of(deconsolidatedLine), ctx);
            BigDecimal other = sum(c, List.of("Effect of exchange rate changes on cash and cash equivalents",
                    "Other increase (decrease) in cash and cash equivalents"), ctx);
            // The cash of a deconsolidated subsidiary is a step of the roll-forward - unless the filing shows it for
            // information only: MIDI's H1 2025 comparative lists -79,630 million although the proceeds from the
            // disposal are already in investing, and its ending cash is beginning + net change exactly (298,489 +
            // 238,397 = 536,886). Proven by the filing's own figures: the roll-forward holds without the line and
            // not with it. Then the line is not counted; a warning says so.
            if (deconsolidated.signum() != 0 && beginning.add(change).add(other).compareTo(ending) == 0) {
                checks.add(Check.warning("cash_rollforward", "'" + deconsolidatedLine + "' "
                        + deconsolidated.stripTrailingZeros().toPlainString() + " is not part of the roll-forward as filed "
                        + "(beginning cash + net change + FX / other effects = ending cash without it); not counted"));
                deconsolidated = ZERO;
            }
            // The same for the FX / other effects: RISE's H1 2025 comparative (H1 2026 filing) shows an exchange-rate
            // effect of 398,002 that its ending cash leaves out - beginning 260,777,639,668 + net change -36,001,003,868
            // = 224,776,635,800, the ending cash as filed, and every section adds up to its total. Only when the
            // roll-forward fails with the effects and holds exactly without them.
            if (other.signum() != 0 && equal("cash_rollforward", "", beginning.add(change).add(other).add(deconsolidated), ending).isError()
                    && beginning.add(change).add(deconsolidated).compareTo(ending) == 0) {
                checks.add(Check.warning("cash_rollforward", "FX / other effects " + other.stripTrailingZeros().toPlainString()
                        + " are not part of the roll-forward as filed (beginning cash + net change = ending cash without "
                        + "them); not counted"));
                other = ZERO;
            }
            BigDecimal rolled = beginning.add(change).add(other).add(deconsolidated);
            Check rollforward = equal("cash_rollforward", "beginning cash + net change + FX / other effects = ending cash",
                    rolled, ending);
            // a full-amount filing rounds every line to the unit as well: a roll-forward one unit apart is its rounding,
            // the ending cash as filed (ALMI FY2024: 507,776 - 480,438 = 27,338, filed 27,337; ARTI FY2023 995,261,912 /
            // 995,261,913; ASHA FY2025 230,491,386 / 230,491,387; errors 1355, 1408, 1410). Only here: elsewhere a unit
            // apart still tells two candidate lines apart (ENRG's double-reported other income)
            if (rollforward.isError() && rolled.subtract(ending).abs().compareTo(BigDecimal.ONE) <= 0) {
                rollforward = Check.warning("cash_rollforward", rollforward.message()
                        + " (one unit apart: the filing's rounding; the reported figure is stored)");
            }
            checks.add(rollforward);
        }
        // the filed totals proven by the filing's own figures: they give the net change, which rolls forward to the
        // ending cash
        boolean totalsConfirmed = operating != null && investing != null && financing != null && change != null
                && beginning != null && ending != null
                && operating.add(investing).add(financing).compareTo(change) == 0
                && checks.stream().noneMatch(ch -> ch.rule().equals("cash_rollforward") && ch.isError());
        // lines a section shows twice (dropped once from its sum): not counted in the fields either
        Set<String> repeated = new java.util.HashSet<>();
        if (investing != null) {
            checks.addAll(section(c, ctx, "Cash flows from investing activities",
                    "Total net cash flows received from (used in) investing activities", investing, "investing", repeated,
                    totalsConfirmed));
        }
        if (financing != null) {
            checks.addAll(section(c, ctx, "Cash flows from financing activities",
                    "Total net cash flows received from (used in) financing activities", financing, "financing", repeated,
                    totalsConfirmed));
        }
        if (!repeated.isEmpty()) {
            c = c.without(repeated, ctx);
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
                        + " differs from balance-sheet cash " + bsCash + " (e.g. bank overdrafts netted, or deposits counted, in cash)"));
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
        how.put("capital_expenditure", "-(PP&E + advances for PP&E + intangibles + investment properties) " + present(c, CAPEX, ctx));
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

    /**
     * True when the section's header and total are in the sheet and no line between them has a value other than 0 in
     * the column (AMMS H1 2026: its one investing line, receipts from stockholders, filed as 0, no total, no financing
     * section; operating -44,107,270 is the net change).
     */
    private static boolean sectionHasNoLines(StatementTable c, int ctx, String headerLabel, String totalLabel) {
        int from = c.indexOf(headerLabel);
        int to = c.indexOf(totalLabel);
        if (from < 0 || to <= from) {
            return false;
        }
        return c.lines().subList(from + 1, to).stream().noneMatch(l -> l.hasValue(ctx) && l.value(ctx).signum() != 0);
    }

    /**
     * A filed investing or financing total that the filing's own figures contradict: the section's lines add up to
     * another amount, and with that amount (and not with the filed total) operating + investing + financing gives the
     * net change in cash, whose roll-forward to the ending cash holds. Then the filed total left out a line, and the
     * section is counted at the sum of its lines. WIFI FY2025: financing filed 10,909,102,818,568, its lines add up to
     * 10,904,384,108,332 (the total leaves out the dividends paid, 4,718,710,236), and -814,048,360,263
     * - 3,944,132,255,717 + 10,904,384,108,332 = 6,146,203,492,352, the net change as filed (18,495,026,165 +
     * 6,146,203,492,352 = 6,164,698,518,517, the ending cash as filed). Only when exactly one section explains it.
     */
    private void correctSectionTotal(StatementTable c, int ctx, BigDecimal[] totals, BigDecimal change,
                                     BigDecimal beginning, BigDecimal ending, List<Check> checks) {
        if (change == null || beginning == null || ending == null
                || totals[0] == null || totals[1] == null || totals[2] == null) {
            return;
        }
        BigDecimal sum = totals[0].add(totals[1]).add(totals[2]);
        if (!equal("net_change", "", sum, change).isError()) {
            return;
        }
        BigDecimal other = sum(c, List.of("Effect of exchange rate changes on cash and cash equivalents",
                "Other increase (decrease) in cash and cash equivalents"), ctx);
        BigDecimal deconsolidated = sum(c, List.of("Cash and cash equivalent of deconsolidated subsidiaries"), ctx);
        BigDecimal rolled = beginning.add(change).add(other);
        if (rolled.compareTo(ending) != 0 && rolled.add(deconsolidated).compareTo(ending) != 0) {
            return;     // the net change itself is not confirmed by the filing
        }
        int section = -1;
        BigDecimal lines = null;
        for (int s = 1; s < 3; s++) {
            BigDecimal l = sectionLineSum(c, ctx, CASH_FLOW_SECTIONS[s][1], CASH_FLOW_SECTIONS[s][2]);
            if (l == null || l.compareTo(totals[s]) == 0
                    || equal("net_change", "", sum.subtract(totals[s]).add(l), change).isError()) {
                continue;
            }
            if (section >= 0) {
                return;     // two sections could explain it: ambiguous
            }
            section = s;
            lines = l;
        }
        if (section < 0) {
            return;
        }
        String name = CASH_FLOW_SECTIONS[section][0];
        checks.add(Check.warning(name + "_total", "The filed " + name + " total " + totals[section].stripTrailingZeros()
                .toPlainString() + " is not the sum of its lines " + lines.stripTrailingZeros().toPlainString()
                + " (difference " + totals[section].subtract(lines).stripTrailingZeros().toPlainString() + "); with the sum "
                + "of the lines operating + investing + financing = the net change in cash as filed, and beginning cash + "
                + "net change = ending cash: counted at the sum of its lines"));
        totals[section] = lines;
    }

    /**
     * A filed ending cash that the filing's own figures contradict: operating + investing + financing gives the net
     * change in cash, beginning cash + net change + FX / other effects gives the balance-sheet cash of the period end
     * exactly, and only the ending cash cell differs. Then the cell is a slip, and the ending cash is the balance
     * sheet's. UVCR 9M 2026: 1,805,621,319 + 568,862,927 = 2,374,484,246, the balance-sheet cash, beside an ending cash
     * filed 2,824,484,246 (error 1153). Not for a bank, whose balance-sheet cash is taken from this statement, and not
     * where the filed ending cash differs from the balance sheet for a reason (overdrafts, deposits): there the
     * roll-forward reaches the filed ending cash.
     */
    private BigDecimal correctEndingCash(StatementTable c, int ctx, StatementColumn column, BigDecimal[] totals,
                                         BigDecimal change, BigDecimal beginning, BigDecimal ending, List<Check> checks) {
        if (financial || column != StatementColumn.CURRENT_PERIOD || balanceSheet == null || change == null
                || beginning == null || ending == null || totals[0] == null || totals[1] == null || totals[2] == null
                || totals[0].add(totals[1]).add(totals[2]).compareTo(change) != 0) {
            return ending;
        }
        BigDecimal bsCash = amount(balanceSheet.value("Cash and cash equivalents", 0));
        BigDecimal other = sum(c, List.of("Effect of exchange rate changes on cash and cash equivalents",
                "Other increase (decrease) in cash and cash equivalents"), ctx);
        BigDecimal rolled = beginning.add(change).add(other);
        if (bsCash == null || rolled.compareTo(bsCash) != 0 || rolled.compareTo(ending) == 0
                || sum(c, List.of("Cash and cash equivalent of deconsolidated subsidiaries"), ctx).signum() != 0) {
            return ending;
        }
        checks.add(Check.warning("cash_rollforward", "The filed ending cash " + ending.stripTrailingZeros().toPlainString()
                + " is not beginning cash + net change + FX / other effects " + rolled.stripTrailingZeros().toPlainString()
                + " (difference " + ending.subtract(rolled).stripTrailingZeros().toPlainString() + "), which is the "
                + "balance-sheet cash; the sections add up to the net change: the ending cash is the balance sheet's"));
        return rolled;
    }

    /** Signed sum of the lines of a cash flow section; {@code null} when its bounds or a line's direction are unknown. */
    private BigDecimal sectionLineSum(StatementTable c, int ctx, String headerLabel, String totalLabel) {
        int from = c.indexOf(headerLabel);
        int to = c.indexOf(totalLabel);
        if (from < 0 || to <= from) {
            return null;
        }
        BigDecimal sum = ZERO;
        for (StatementTable.Line line : c.lines().subList(from + 1, to)) {
            BigDecimal raw = line.value(ctx);
            if (raw == null) {
                continue;
            }
            BigDecimal signed = signedFlow(line.label(), amount(raw));
            if (signed == null) {
                return null;
            }
            sum = sum.add(signed);
        }
        return sum;
    }

    /**
     * Signs every line of a cash flow section and checks it against the reported section total. Lines the section
     * shows twice and that are not counted are added to {@code repeated}.
     *
     * @param totalsConfirmed the filed operating + investing + financing totals give the net change in cash, whose
     *                        roll-forward to the ending cash holds
     */
    private List<Check> section(StatementTable c, int ctx, String headerLabel, String totalLabel, BigDecimal total, String name,
                                Set<String> repeated, boolean totalsConfirmed) {
        int from = c.indexOf(headerLabel);
        int to = c.indexOf(totalLabel);
        if (from < 0 || to < 0) {
            return List.of(Check.warning(name + "_section", "Section boundaries not found; section not re-added"));
        }
        BigDecimal sum = ZERO;
        List<String> unknown = new ArrayList<>();
        Map<String, BigDecimal> residual = new LinkedHashMap<>();
        Map<String, BigDecimal> specific = new LinkedHashMap<>();
        Map<String, BigDecimal> signedLines = new LinkedHashMap<>();
        Map<String, BigDecimal> rawLines = new LinkedHashMap<>();
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
                if (signed.signum() != 0) {
                    (isResidual(line.label()) ? residual : specific).putIfAbsent(line.label(), signed);
                    signedLines.putIfAbsent(line.label(), signed);
                    rawLines.putIfAbsent(line.label(), amount(raw));
                }
            }
        }
        if (!unknown.isEmpty()) {
            return List.of(Check.error(name + "_section", "Cannot determine the cash direction of " + unknown));
        }
        // one line that the filing's section total does not include: the total is exactly the sum of the other
        // lines. A residual "Other cash ..." line shown for information (TINS FY2024 in its FY2025 filing: "Other
        // cash inflows (outflows) from investing activities" 25,395 beside a total of -453,950), or a line that
        // repeats another one (BRI's comparatives: "Withdrawal of financial assets amortized cost investments"
        // 9,884,450 beside "Withdrawal (placement) of marketable securities" 9,884,449 in FY2024; "Increase
        // (decrease) in securities issued" 13,426,357 beside "Proceeds from bonds issuance" 13,426,357 in H1 2025).
        // Residual lines are tried first. The statement's fields are read from their own rows, not from this sum.
        if (total != null && sum.compareTo(total) != 0) {
            Map<String, BigDecimal> candidates = new LinkedHashMap<>(residual);
            candidates.putAll(specific);
            for (Map.Entry<String, BigDecimal> extra : candidates.entrySet()) {
                if (sum.subtract(extra.getValue()).compareTo(total) == 0) {
                    return List.of(Check.warning(name + "_section", "'" + extra.getKey() + "' "
                            + extra.getValue().stripTrailingZeros().toPlainString() + " is not part of the filing's " + name
                            + " total " + total.stripTrailingZeros().toPlainString() + " in this column (the total is the sum "
                            + "of the other lines); not counted"));
                }
            }
            List<String> twice = enteredTwice(signedLines, rawLines);
            BigDecimal once = sum;
            for (String label : twice) {
                once = once.subtract(signedLines.get(label));
            }
            if (!twice.isEmpty() && once.compareTo(total) == 0) {
                repeated.addAll(twice);
                return List.of(Check.warning(name + "_section", "Amounts entered twice in the " + name + " section: "
                        + twice.stream().map(l -> "'" + l + "' " + signedLines.get(l).stripTrailingZeros().toPlainString())
                        .toList() + " repeat another line's cash flow; counted once, the lines then add up to the filing's "
                        + name + " total " + total.stripTrailingZeros().toPlainString() + " (not counted in the fields either)"));
            }
            // lines filed with decimals in a filing rounded to thousands or millions, which the total leaves out:
            // ADMR's H1 2025 comparative (USD thousand) shows exploration and evaluation assets 124.222 and intangible
            // assets 90.109 beside whole amounts; the other lines add up to the filed total -322,409 exactly
            List<String> fractional = unit.compareTo(BigDecimal.ONE) <= 0 ? List.of() : rawLines.keySet().stream()
                    .filter(l -> rawLines.get(l).divide(unit).stripTrailingZeros().scale() > 0).toList();
            BigDecimal whole = sum;
            for (String label : fractional) {
                whole = whole.subtract(signedLines.get(label));
            }
            if (!fractional.isEmpty() && whole.compareTo(total) == 0) {
                repeated.addAll(fractional);
                return List.of(Check.warning(name + "_section", "Lines filed with decimals in a filing rounded to "
                        + unit.toPlainString() + ": " + fractional.stream().map(l -> "'" + l + "' "
                        + signedLines.get(l).stripTrailingZeros().toPlainString()).toList() + " are not part of the filing's "
                        + name + " total " + total.stripTrailingZeros().toPlainString() + " (the total is the sum of the "
                        + "other lines); not counted, in the fields either"));
            }
            // an outflow and an inflow that a restated comparative moved to another section but still lists here:
            // BEER's FY2024 comparative in its FY2025 filing keeps "Cash advances and loans made to other parties"
            // 98,006,019 and their repayment 77,432,853 under investing, while its investing total -3,273,103,330 is
            // the sum of the other lines and operating takes the net -20,573,166 (-21,381,709,173 in the FY2024
            // filing, -21,402,282,339 restated). Only when the filed totals give the net change in cash, whose
            // roll-forward holds, and exactly one such pair explains the difference.
            if (totalsConfirmed) {
                List<String> pair = leftOutPair(signedLines, sum.subtract(total));
                if (pair != null) {
                    return List.of(Check.warning(name + "_section", pair.stream().map(l -> "'" + l + "' "
                            + signedLines.get(l).stripTrailingZeros().toPlainString()).toList() + " are not part of the "
                            + "filing's " + name + " total " + total.stripTrailingZeros().toPlainString() + " in this column "
                            + "(the total is the sum of the other lines, and the filed totals give the net change in cash); "
                            + "not counted"));
                }
            }
        }
        return List.of(equal(name + "_section", "sum of " + name + " lines (payments negative) = " + name + " total", sum, total));
    }

    /**
     * The one pair of lines, an outflow and an inflow, whose signed amounts add up to {@code difference}; {@code null}
     * when no pair or more than one does.
     */
    static List<String> leftOutPair(Map<String, BigDecimal> signedLines, BigDecimal difference) {
        List<String> labels = new ArrayList<>(signedLines.keySet());
        List<String> found = null;
        for (int i = 0; i < labels.size(); i++) {
            for (int j = i + 1; j < labels.size(); j++) {
                BigDecimal a = signedLines.get(labels.get(i));
                BigDecimal b = signedLines.get(labels.get(j));
                if (a.signum() == b.signum() || a.add(b).compareTo(difference) != 0) {
                    continue;
                }
                if (found != null) {
                    return null;    // ambiguous
                }
                found = List.of(labels.get(i), labels.get(j));
            }
        }
        return found;
    }

    /**
     * Lines of a section that repeat another line's cash flow (the same signed amount), one per pair: the line whose
     * filed sign contradicts its label (proceeds or payments entered negative), else the later one. WIFI's H1 2025
     * comparative in its H1 2026 filing: bank loans 783,412,016,329 also as unsecured loans, the repayment of other
     * borrowings 15,495,302,170 also as negative non-bank proceeds, consumer financing 674,885,668 as proceeds and as
     * a negative payment. An amount found on three or more lines is left alone.
     */
    static List<String> enteredTwice(Map<String, BigDecimal> signedLines, Map<String, BigDecimal> rawLines) {
        Map<BigDecimal, List<String>> byAmount = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : signedLines.entrySet()) {
            byAmount.computeIfAbsent(e.getValue().stripTrailingZeros(), k -> new ArrayList<>()).add(e.getKey());
        }
        List<String> twice = new ArrayList<>();
        for (List<String> labels : byAmount.values()) {
            if (labels.size() != 2) {
                continue;
            }
            String first = labels.get(0);
            String second = labels.get(1);
            twice.add(contradictsLabel(first, rawLines.get(first)) && !contradictsLabel(second, rawLines.get(second))
                    ? first : second);
        }
        return twice;
    }

    /** A line whose direction is given by its label ("Proceeds ...", "Payments ..."), entered as a negative amount. */
    private static boolean contradictsLabel(String label, BigDecimal raw) {
        return raw != null && raw.signum() < 0 && !label.contains("(");
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

    /** The total of the cost of sales note (1670000; cost of goods manufactured and the change in inventories). */
    public static final String COST_OF_SALES_TOTAL = "Cost of sales and revenue";

    /**
     * The cost of sales of a column from the filing's own cost of sales note (sheet 1670000, "Cost of sales and
     * revenue"), in full units; null when the filing has no such note or no value for the column, and for a bank. A
     * profit or loss presented by nature shows no cost of sales on its face, but many such filers state it in this note
     * (BIPI FY2025: 200,443,257 beside revenue 235,089,682), so their gross profit is the filing's own figure.
     */
    public BigDecimal costOfSales(StatementColumn column) {
        int ctx = durationIndex(column);
        if (financial || ctx < 0) {
            return null;
        }
        return table(IdxSheets.COST_OF_SALES).map(t -> amount(t.value(COST_OF_SALES_TOTAL, ctx))).orElse(null);
    }

    /** The line of a profit or loss by nature that states the period's depreciation and amortisation. */
    public static final String STATED_DEPRECIATION_AND_AMORTISATION = "Depreciation and amortisation expenses";

    /**
     * The depreciation and amortisation a profit or loss by nature states for the column (1312000 / 1322000, 3312000 of
     * the Infrastructure Industry: "Depreciation and amortisation expenses"), in full units; {@code null} when it does
     * not state it. It is the company's own expense of the period, in every column alike, so the EBITDA of a period is
     * EBIT plus it: the roll-forward additions to accumulated depreciation (current period only, and capitalised
     * depreciation included) differ from it (TLKM H1 2026: 14,370 billion beside the 17,203 stated; NSSS 32,899 million
     * beside 80,672), and a trailing twelve months of H1 2026 + FY2025 - H1 2025 must take all three periods' D&A
     * alike. The interim comparative (H1 2025) has no roll-forward at all, only this line.
     */
    public BigDecimal statedDepreciationAndAmortization(StatementColumn column) {
        int ctx = durationIndex(column);
        StatementTable profitOrLoss = profitOrLossAsFiled();
        if (financial || ctx < 0 || profitOrLoss == null) {
            return null;
        }
        BigDecimal value = profitOrLoss.value(STATED_DEPRECIATION_AND_AMORTISATION, ctx);
        return value == null ? null : amount(value).abs();
    }

    /**
     * Whether the column's cash flow statement has a row of capital expenditure with an amount (the rows the rule-based
     * extraction counts, bank template included); {@code true} when it cannot tell (no cash flow statement read or no
     * figures in the column). FUJI, a financing company, has no investing section at all; ADCP's 9M 2025 comparative
     * invests only in land for development, a joint venture and restricted funds: neither spends on capital assets.
     */
    public boolean reportsCapitalExpenditure(StatementColumn column) {
        int ctx = durationIndex(column);
        if (cashFlow == null || ctx < 0 || !cashFlow.hasData(ctx)) {
            return true;
        }
        return java.util.stream.Stream.concat(CAPEX.stream(), FIN_CAPEX.stream())
                .anyMatch(label -> cashFlow.value(label, ctx) != null);
    }

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
        StatementTable balance = balanceSheetAsFiled();
        if (balance == null) {
            return true;
        }
        int ctx = instantIndex(column) < 0 ? 0 : instantIndex(column);
        BigDecimal closing = balance.value("Right of use assets", ctx);
        BigDecimal opening = balance.value("Right of use assets", 1);
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
     * Whether the column's amortization cannot matter to its EBITDA: the intangible assets other than goodwill, opening
     * and closing, are both under 1% of EBIT + depreciation, so a year's amortization of them - at most the balance
     * itself - changes it by less than 1%. Where {@link #amortization} is not derivable - intangibles grown by more than
     * the cash paid for them (acquired, capitalised: KETR FY2025 241,481,810 -> 283,072,592 with no purchase) or sold
     * (MIKA FY2025) - EBITDA is then EBIT + depreciation instead of nothing (KETR: intangibles 283 million beside
     * EBIT + depreciation of 249 billion). Current column only (needs the opening balance), like the amortization.
     */
    private boolean immaterialIntangibles(StatementColumn column, BigDecimal base) {
        if (column != StatementColumn.CURRENT_PERIOD || balanceSheet == null || financial || base == null || base.signum() <= 0) {
            return false;
        }
        BigDecimal limit = base.multiply(new BigDecimal("0.01"));
        BigDecimal opening = nz(amount(balanceSheet.value("Intangible assets other than goodwill", 1)));
        BigDecimal closing = nz(amount(balanceSheet.value("Intangible assets other than goodwill", 0)));
        return opening.abs().compareTo(limit) < 0 && closing.abs().compareTo(limit) < 0;
    }

    /**
     * Intangibles opening + purchases - closing; current column only (needs the opening balance). The bank
     * template reports acquisitions net of disposals in one signed line: a net disposal leaves the carrying
     * amount of what was sold unknown, so no amortization is derived then. Neither when the goodwill fell by half or
     * more: goodwill is not amortized, so a business left the group or was written off, and the intangibles roll holds
     * more than amortization - PTRO H1 2026: intangibles 134,250 -> 60,088 thousand USD and goodwill 21,395 -> 2,408,
     * so the roll gives 134,250 + 4,050 - 60,088 = 78,212 while its cost of sales note states an amortization of 6,075;
     * CUAN H1 2026 152,773 -> 72,543, goodwill 21,395 -> 2,426. A smaller fall (exchange rates, a partial impairment:
     * TOBA H1 2026, TLKM, RAJA) leaves the roll as it is; of the 2021 - 2026 workbooks 14 have a halved goodwill.
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
        if (result.signum() > 0 && goodwillHalvedOrMore()) {
            return null;    // intangibles left with a business or were written off: the roll is not the amortization
        }
        return result.signum() < 0 ? null : result;
    }

    /** Whether the goodwill at the end of the current period is half or less of a positive opening goodwill. */
    private boolean goodwillHalvedOrMore() {
        if (balanceSheet == null) {
            return false;
        }
        BigDecimal opening = amount(balanceSheet.value("Goodwill", 1));
        BigDecimal closing = nz(amount(balanceSheet.value("Goodwill", 0)));
        return opening != null && opening.signum() > 0 && closing.multiply(BigDecimal.TWO).compareTo(opening) <= 0;
    }

    // ------------------------------------------------------------------ revenue segments

    /**
     * The revenue of a column by its revenue breakdown: the breakdown's reported total, else the sum of its lines;
     * {@code null} when there is none, it does not add up to its own total, or it is not positive.
     */
    private BigDecimal revenueBreakdownTotal(StatementColumn column) {
        SegmentExtraction e = segments(column, null).filter(s -> !s.hasErrors()).orElse(null);
        if (e == null) {
            return null;
        }
        BigDecimal total = e.reportedTotal() != null ? e.reportedTotal()
                : e.lines().stream().map(SegmentLine::revenue).reduce(ZERO, BigDecimal::add);
        return total.signum() > 0 ? total : null;
    }

    public static final String SALES = "Sales and revenue";

    /**
     * The revenue a non-financial profit or loss reports for a column, whatever its template: the 'Sales and revenue'
     * row, else - the row empty in the column - the total of the column's own revenue breakdown.
     *
     * @param sheet         the profit or loss sheet under its name in the file (e.g. 3312000)
     * @param row           the row of 'Sales and revenue', 1-based as in Excel
     * @param amount        full currency units
     * @param filed         the amount in the filing's unit, as the cell holds it (or would)
     * @param breakdown     the breakdown sheet it comes from, {@code null} when the row is filed
     */
    public record FiledRevenue(String sheet, int row, BigDecimal amount, BigDecimal filed, String breakdown) {
    }

    /**
     * The revenue of a column as the filing reports it ({@link FiledRevenue}); empty for a bank, financing company or
     * insurer (no such row), and when neither the row nor a breakdown that adds up gives it. EXCL's FY2023 workbook
     * (by nature) leaves the row empty in both columns - the revenue, 32,322,651,000,000, is only in its breakdown by
     * type - and the model mapped 'Other income' 424,062,000,000 as revenue.
     */
    /**
     * Whether a column's profit or loss reports no revenue at all: a General taxonomy profit or loss whose 'Sales and
     * revenue' row is empty or missing, without a revenue breakdown. PEGE (an investment company) files only general and administrative
     * expenses, finance income (its investment results, -101,635,308,012 in FY2022), other income and other expenses,
     * in every filing; the model made up a different revenue each year (FY2022 -94,297,438,110: finance + other income;
     * FY2024 28,879,851,458; FY2025 the finance income 19,282,262,084). {@code false} for a bank, financing company or
     * insurer, and when the workbook has no profit or loss.
     */
    public boolean reportsNoRevenue(StatementColumn column) {
        StatementTable profitOrLoss = profitOrLossAsFiled();
        int ctx = durationIndex(column);
        if (financial || profitOrLoss == null || ctx < 0) {
            return false;
        }
        return filedRevenue(column).isEmpty();
    }

    public Optional<FiledRevenue> filedRevenue(StatementColumn column) {
        StatementTable profitOrLoss = profitOrLossAsFiled();
        int ctx = durationIndex(column);
        if (financial || profitOrLoss == null || ctx < 0) {
            return Optional.empty();
        }
        StatementTable.Line line = profitOrLoss.lines().stream().filter(l -> l.label().equals(SALES)).findFirst().orElse(null);
        if (line == null) {
            return Optional.empty();
        }
        String sheet = code(profitOrLoss.sheet());
        if (line.value(ctx) != null) {
            return Optional.of(new FiledRevenue(sheet, line.row(), amount(line.value(ctx)), line.value(ctx), null));
        }
        BigDecimal breakdown = revenueBreakdownTotal(column);
        if (breakdown == null) {
            return Optional.empty();
        }
        String source = segments(column, null).map(SegmentExtraction::sourceSheet).map(this::code).orElse(null);
        return Optional.of(new FiledRevenue(sheet, line.row(), breakdown, breakdown.divide(unit), source));
    }

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
        // one line that the breakdown's own total does not include: the reported total - and the revenue of the
        // income statement - are exactly the sum of the other lines (MIKA's comparatives: an unnamed "Service
        // revenue 5" of 602,309,854,653, the amount of "Jasa tenaga ahli" again, in FY2024; one of 261,182,623,893
        // in H1 2025; the services subtotal and the total are the four named lines). Not counted; a warning says so.
        BigDecimal filed = lines.stream().map(SegmentLine::revenue).reduce(ZERO, BigDecimal::add);
        if (total != null && incomeRevenue != null && total.compareTo(incomeRevenue) == 0 && filed.compareTo(total) != 0) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).revenue().signum() != 0 && filed.subtract(lines.get(i).revenue()).compareTo(total) == 0) {
                    SegmentLine extra = lines.remove(i);
                    String slot = slots.remove(i);
                    checks.add(Check.warning("segment_line", "'" + (extra.name().equals(slot) ? slot : extra.name() + "' ('" + slot + "')")
                            + " " + extra.revenue().stripTrailingZeros().toPlainString() + " is not part of the breakdown's total "
                            + total.stripTrailingZeros().toPlainString() + " in this column (the total and the revenue are the sum "
                            + "of the other lines); not counted"));
                    break;
                }
            }
        }
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
     * Segments are stored by name, so one name on two lines of a breakdown would store only one of them.
     * <ul>
     *   <li>One name on several lines of the same kind is one segment filed in parts: TOTL files "Jasa Konstruksi"
     *       as service revenue 1 .. 4 (331,002,951 + 306,031,443 + 451,135 + 1,410,223,936 thousand in H1 2026) and
     *       as domestic revenue 1 .. 5 in FY2023, where its later filings show the one line "Jasa Konstruksi"
     *       (3,006,816,008 for FY2023, the sum of the five). The lines are added up; a warning says so.</li>
     *   <li>One name on lines of different kinds is two segments: CEKA files "Produk Palm Kernel" both as domestic
     *       revenue 2 (3,391,840,269,264) and as export revenue 1 (210,909,304,151). Such names get their slot as a
     *       qualifier, "Produk Palm Kernel (domestik)" / "Produk Palm Kernel (ekspor)"; names that occur once are
     *       kept as filed.</li>
     * </ul>
     * A name that still repeats is an error (nothing is saved).
     */
    static List<SegmentLine> distinctSegmentNames(List<SegmentLine> filedLines, List<String> filedSlots, List<Check> checks) {
        Map<String, Integer> filed = new LinkedHashMap<>();
        filedLines.forEach(l -> filed.merge(l.name(), 1, Integer::sum));
        if (filed.values().stream().allMatch(n -> n == 1)) {
            return filedLines;
        }
        // the same name, kind of slot and type: one segment, its lines added up (at the place of the first)
        List<SegmentLine> lines = new ArrayList<>();
        List<String> slots = new ArrayList<>();
        Map<String, Integer> parts = new LinkedHashMap<>();
        for (int i = 0; i < filedLines.size(); i++) {
            SegmentLine l = filedLines.get(i);
            String qualifier = slotQualifier(filedSlots.get(i));
            int same = -1;
            for (int j = 0; j < lines.size() && same < 0; j++) {
                SegmentLine m = lines.get(j);
                if (m.name().equals(l.name()) && m.filingType().equals(l.filingType()) && m.residualSlot() == l.residualSlot()
                        && slotQualifier(slots.get(j)).equals(qualifier)) {
                    same = j;
                }
            }
            if (same < 0) {
                lines.add(l);
                slots.add(filedSlots.get(i));
            } else {
                SegmentLine m = lines.get(same);
                lines.set(same, new SegmentLine(m.name(), m.filingType(), m.revenue().add(l.revenue()), m.residualSlot()));
                parts.merge(m.name() + " (" + qualifier + ")", 1, Integer::sum);
            }
        }
        if (!parts.isEmpty()) {
            checks.add(Check.warning("segment_names", "Names filed on several lines of the same kind are one segment, "
                    + "its lines added up: " + parts.entrySet().stream()
                    .map(e -> e.getKey() + " on " + (e.getValue() + 1) + " lines").toList()));
        }
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
            epsContradiction = null;
            epsChecked = true;
            checks.add(Check.warning("share_capital", "No share capital in the statements of changes in equity"));
            return new ShareCapital(null, null, List.of(), null, null, checks, null);
        }

        BigDecimal[] currentYear = startEnd.get(IdxSheets.EQUITY);
        BigDecimal[] priorYear = startEnd.get(IdxSheets.EQUITY_PRIOR_YEAR);
        String contradiction = epsColumnsContradict(currentYear, priorYear);
        epsContradiction = contradiction;
        epsChecked = true;
        if (contradiction != null) {
            checks.add(Check.warning("eps_columns", contradiction));
        }
        BigDecimal par = contradiction != null ? null
                : inferParValue(StatementColumn.CURRENT_PERIOD, currentYear == null ? null : currentYear[1], checks);
        StatementColumn other = StatementColumn.PRIOR_PERIOD;
        if (par == null && contradiction == null) {
            par = inferParValue(StatementColumn.PRIOR_PERIOD, priorYear == null ? null : priorYear[1], checks);
            other = StatementColumn.CURRENT_PERIOD;
        }
        String misfit = par == null ? null
                : parMisfit(par, other, other == StatementColumn.CURRENT_PERIOD ? currentYear : priorYear);
        if (misfit != null) {
            checks.add(Check.warning("par_value", misfit));
            par = null;
        }
        EpsShares epsShares = null;
        String basis = null;
        if (par != null) {
            basis = "par value " + par.toPlainString();
            checks.add(Check.ok("par_value", "Inferred par value " + par.toPlainString() + " per share"));
        } else if (contradiction == null) {
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
        BigDecimal weightedCurrent = epsShares == null ? weighted(currentYear, treasuryStartEnd.get(IdxSheets.EQUITY), par)
                : epsShares.column() == StatementColumn.CURRENT_PERIOD ? epsShares.shares() : null;
        BigDecimal weightedPrior = epsShares == null ? weighted(priorYear, treasuryStartEnd.get(IdxSheets.EQUITY_PRIOR_YEAR), par)
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
                shares = par == null || treasury ? null : sharesAtPar(p.common(), par);
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
    /** Largest relative change of the shares issued over a period in which only treasury shares may have moved. */
    static final BigDecimal WEB_SHARES_ISSUED_DRIFT = new BigDecimal("0.01");

    /** {@link #withWebShareCounts(ShareCapital, List, List, String)} without published splits. */
    public ShareCapital withWebShareCounts(ShareCapital filing, List<WebShareCount> counts, String source) {
        return withWebShareCounts(filing, counts, List.of(), source);
    }

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
     * Two cases the website's own figures explain:
     * <ul>
     *   <li>A split after the filing. Published counts are split-adjusted to today, while a filing of before a split
     *       states its EPS on the count of before it (CUAN FY2024: 160,785,000 / 0.014 = 11.5 billion shares; Yahoo
     *       Finance: 112,418,900,000 after the split 1:10 of 2025-07-15). A count that misses the EPS as published
     *       is also checked on the count of before the latest published split after its date, the latest two, ...
     *       (a filing reflects the splits before it was issued, not those after), and used when exactly one of
     *       these fits. It is stored as published - split-adjusted, like the prices - and the splits it needed are
     *       returned ({@link ShareCapital#webSplits()}), so the per-share figures of before them are adjusted too.</li>
     *   <li>A buyback in the period. With treasury shares bought during the period the end count is below the
     *       weighted one the EPS uses (BUKA FY2025: 92,970,828,660 shares at the end, 103,121,636,167 at the start,
     *       EPS 31.91 = 98.5 billion weighted shares). A period-end count that misses the EPS is accepted when the
     *       EPS lies between profit / the published count at the start of the period and profit / the end count,
     *       both consistent, with the shares issued unchanged (within 1%): only treasury shares moved.</li>
     * </ul>
     * A website publishes only a few year ends (Yahoo Finance: four), so the opening dates of an older filing have
     * no count of their own (BBCA's FY2022 filing: 2022-12-31 published, 2021-12-31 and 2020-12-31 not) and the
     * periods ending there had no market cap and no valuation. An accepted count is therefore carried to another
     * date of the filing when share capital is the same at both dates, neither has treasury stock, and the count
     * passes the same EPS check at that date: with share capital unchanged and no treasury stock only a split
     * changes the number of shares, and a split the filing does not restate fails the EPS check.
     * <p>
     * The result keeps the filing's share capital; weighted shares stay unknown. Without an accepted count the
     * filing's own (unresolved) share capital is returned with the reasons.
     *
     * @param splits the website's splits (any order; {@link WebSplit#sharesBefore()} is derived here)
     */
    public ShareCapital withWebShareCounts(ShareCapital filing, List<WebShareCount> counts, List<WebSplit> splits,
                                           String source) {
        if (filing.resolved()) {
            return filing;
        }
        ShareCapital asFiled = webShareCounts(filing, counts, splits, source);
        ShareCapital columnOff = columnEpsOffByPowerOfTen(filing, asFiled, counts, splits, source);
        if (columnOff != null) {
            return columnOff;
        }
        if (asFiled.resolved()) {
            return asFiled;
        }
        if (epsScale.compareTo(BigDecimal.ONE) != 0) {
            return parValueFallback(asFiled, source);
        }
        // an EPS filed per 1,000 shares (BRMS, full USD amounts: FY2025 basic EPS 0.35 for 50,009,777 / 142.9 billion
        // shares = USD 0.00035): no published count fits it as filed. Read so only when every published count at a
        // date of the filing then fits; the per-share figures are then stored per share
        epsScale = PER_THOUSAND_SHARES;
        ShareCapital perThousand = webShareCounts(filing, counts, splits, source);
        if (perThousand.resolved() && perThousand.checks().stream().noneMatch(c -> "web_shares".equals(c.rule())
                && c.severity() == Check.Severity.WARNING && c.message().contains("not used"))) {
            String note = "The workbook's EPS is filed per 1,000 shares: no " + source + " count fits it as filed, every one "
                    + "fits it divided by 1,000; per-share figures are divided by 1,000";
            warnings.add(note);
            List<Check> checks = new ArrayList<>(perThousand.checks());
            checks.add(Check.warning("eps_unit", note));
            return new ShareCapital(perThousand.parValue(), perThousand.basis(), perThousand.snapshots(),
                    perThousand.weightedCurrent(), perThousand.weightedPrior(), checks, perThousand.webSource(),
                    perThousand.webSplits());
        }
        epsScale = BigDecimal.ONE;
        return parValueFallback(asFiled, source);
    }

    /**
     * Share counts of a filing by nature from its share capital and par value, as a filing by function has them, when
     * no published count fits it. Published counts come first for such a filing: they count the treasury shares a par
     * value cannot (BUKA FY2025: 92,970,828,660 after its buyback). BUVA FY2022 has none - Yahoo Finance publishes four
     * year ends - so FY2021 and FY2022 had no market cap and no valuation: 340,563,460,000 / par 50 = 6,811,269,200
     * shares, profit attributable to the parent -177,323,077,262 / 6,811,269,200 = -26.03 (filed as 26).
     */
    private ShareCapital parValueFallback(ShareCapital unresolved, String source) {
        if (!incomeByNature()) {
            return unresolved;
        }
        String contradiction = epsContradiction;
        boolean checked = epsChecked;
        ShareCapital byPar;
        parFromProfitOrLossByNature = true;
        try {
            byPar = shareCapital();
        } finally {
            // the EPS of a filing by nature is judged as before: only the share counts come from this
            parFromProfitOrLossByNature = false;
            epsContradiction = contradiction;
            epsChecked = checked;
        }
        if (!byPar.resolved()) {
            return unresolved;
        }
        List<Check> checks = new ArrayList<>(unresolved.checks().stream().filter(c -> "web_shares".equals(c.rule())).toList());
        checks.addAll(byPar.checks());
        String basis = byPar.basis() + " (profit or loss by nature; no " + source + " count fits the filing)";
        checks.add(Check.ok("par_value", "Share counts from the share capital: " + basis));
        return new ShareCapital(byPar.parValue(), basis, byPar.snapshots(), byPar.weightedCurrent(), byPar.weightedPrior(),
                checks, null);
    }

    private static final BigDecimal PER_THOUSAND_SHARES = new BigDecimal("0.001");

    /**
     * One column's EPS filed a power of ten off while the other column's is right. ITMG's H1 2026 workbook (thousands,
     * USD) files the H1 2025 EPS 0.08 in its rounding unit as 0.00008 beside 0.1 for H1 2026; MDKA's (full USD amounts)
     * files the H1 2026 EPS as 0.00042 for 101,558,808 / 24.4 billion shares = 0.0042 beside -0.0006 for H1 2025. With
     * no rupiah share capital no par value tells the columns apart, and every published count at the dates of the column
     * was refused, so ITMG's H1 2025 and MDKA's H1 2026 had no share count and an EPS 1,000 / 10 times too small.
     * <p>
     * The column's EPS is read multiplied by the power of ten only when the published counts are evidence: the other
     * column's EPS fits a published count as filed (the counts are on the filing's basis: same company, unit, splits),
     * every published count at the dates of this column misses its EPS by the same power of ten, and with it every count
     * of the filing fits. The per-share figures of the column are then stored so (a warning says so).
     *
     * @return the share capital with the counts, {@code null} when no column's EPS is so
     */
    private ShareCapital columnEpsOffByPowerOfTen(ShareCapital filing, ShareCapital asFiled, List<WebShareCount> counts,
                                                  List<WebSplit> splits, String source) {
        StatementTable profitOrLoss = profitOrLossAsFiled();
        if (profitOrLoss == null || epsScale.compareTo(BigDecimal.ONE) != 0) {
            return null;
        }
        Map<LocalDate, WebShareCount> byDate = new LinkedHashMap<>();
        counts.forEach(c -> byDate.putIfAbsent(c.date(), c));
        Set<LocalDate> acceptedDates = asFiled.snapshots().stream().filter(s -> s.sharesOutstanding() != null
                && s.source() != null && s.source().startsWith(source + " count at ")).map(ShareAt::date)
                .collect(java.util.stream.Collectors.toSet());
        for (StatementColumn column : List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD)) {
            StatementColumn other = column == StatementColumn.CURRENT_PERIOD ? StatementColumn.PRIOR_PERIOD
                    : StatementColumn.CURRENT_PERIOD;
            List<LocalDate> own = filing.snapshots().stream().map(ShareAt::date).filter(d -> columnOfDate(d) == column)
                    .filter(byDate::containsKey).distinct().toList();
            boolean otherFits = filing.snapshots().stream().map(ShareAt::date)
                    .anyMatch(d -> columnOfDate(d) == other && acceptedDates.contains(d));
            if (own.isEmpty() || !otherFits || own.stream().anyMatch(acceptedDates::contains)) {
                continue;
            }
            int ctx = durationIndex(column);
            BigDecimal parent = amount(profitOrLoss.value(PROFIT_PARENT, ctx));
            BigDecimal eps = epsWithDiscontinued(profitOrLoss, EPS_BASIC, EPS_BASIC_DISC, ctx, null);
            if (parent == null || eps == null || parent.signum() == 0 || eps.signum() != parent.signum()) {
                continue;
            }
            Integer power = null;
            for (LocalDate date : own) {
                BigDecimal outstanding = byDate.get(date).outstanding();
                if (outstanding == null || outstanding.signum() <= 0) {
                    power = null;
                    break;
                }
                double ratio = parent.divide(outstanding, MathContext.DECIMAL64).divide(eps, MathContext.DECIMAL64).doubleValue();
                int k = (int) Math.round(Math.log10(ratio));
                if (k == 0 || Math.abs(k) > 6 || power != null && power != k) {
                    power = null;
                    break;
                }
                power = k;
            }
            if (power == null) {
                continue;
            }
            BigDecimal factor = BigDecimal.ONE.scaleByPowerOfTen(power);
            columnEpsFactor[ctx] = factor;
            ShareCapital fixed = webShareCounts(filing, counts, splits, source);
            boolean ownFit = own.stream().allMatch(d -> fixed.snapshots().stream().anyMatch(s -> s.date().equals(d)
                    && s.sharesOutstanding() != null && s.source() != null && s.source().startsWith(source + " count at ")));
            if (!fixed.resolved() || !ownFit || fixed.checks().stream().anyMatch(c -> "web_shares".equals(c.rule())
                    && c.severity() == Check.Severity.WARNING && c.message().contains("not used"))) {
                columnEpsFactor[ctx] = BigDecimal.ONE;
                continue;
            }
            String note = "The workbook's " + period(column).key() + " basic EPS " + profitOrLoss.value(EPS_BASIC, ctx)
                    .stripTrailingZeros().toPlainString() + " is filed " + factor.stripTrailingZeros().toPlainString()
                    + " times too " + (power > 0 ? "small" : "large") + ": every " + source + " count at its dates fits "
                    + "it only so, while the " + period(other).key() + " EPS fits the published counts as filed; per-share "
                    + "figures of " + period(column).key() + " are multiplied by " + factor.stripTrailingZeros().toPlainString();
            warnings.add(note);
            List<Check> checks = new ArrayList<>(fixed.checks());
            checks.add(Check.warning("eps_scale", note));
            return new ShareCapital(fixed.parValue(), fixed.basis(), fixed.snapshots(), fixed.weightedCurrent(),
                    fixed.weightedPrior(), checks, fixed.webSource(), fixed.webSplits());
        }
        return null;
    }

    /** The duration column a share count date opens or closes, as {@link #webShareCountProblem} judges it. */
    private StatementColumn columnOfDate(LocalDate date) {
        return date.equals(info.current().end()) ? StatementColumn.CURRENT_PERIOD
                : date.equals(info.prior().end()) ? StatementColumn.PRIOR_PERIOD
                : date.equals(info.current().start().minusDays(1)) ? StatementColumn.CURRENT_PERIOD
                : date.equals(info.prior().start().minusDays(1)) ? StatementColumn.PRIOR_PERIOD : null;
    }

    private ShareCapital webShareCounts(ShareCapital filing, List<WebShareCount> counts, List<WebSplit> splits,
                                        String source) {
        Map<LocalDate, WebShareCount> byDate = new LinkedHashMap<>();
        counts.forEach(c -> byDate.putIfAbsent(c.date(), c));
        List<WebSplit> ordered = splits.stream().filter(s -> s.factor() != null && s.factor().signum() > 0
                        && s.factor().compareTo(BigDecimal.ONE) != 0)
                .sorted(Comparator.comparing(WebSplit::date)).toList();
        List<Check> checks = new ArrayList<>();
        List<ShareAt> snapshots = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        Set<LocalDate> unreflected = new TreeSet<>();
        for (ShareAt p : filing.snapshots()) {
            WebShareCount c = byDate.get(p.date());
            if (c == null) {
                snapshots.add(p);
                continue;
            }
            WebShareFit fit = webShareCountFit(c, p.date(), ordered, byDate);
            if (fit.problem() != null) {
                checks.add(Check.warning("web_shares", source + " count at " + p.date() + " not used: " + fit.problem()));
                snapshots.add(p);
                continue;
            }
            BigDecimal treasury = c.treasury() != null ? c.treasury()
                    : c.issued() != null ? c.issued().subtract(c.outstanding()) : null;
            snapshots.add(new ShareAt(p.date(), p.commonStock(), c.outstanding(), treasury, null,
                    source + " count at " + p.date() + ", checked against the filing's EPS" + fit.basis()));
            accepted.add(p.date() + ": " + c.outstanding().toPlainString() + fit.basis());
            fit.splits().forEach(s -> unreflected.add(s.date()));
        }
        accepted.addAll(carryWebShareCounts(filing.snapshots(), snapshots, byDate, ordered, unreflected, source));
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
        List<WebSplit> used = new ArrayList<>();
        for (WebSplit s : ordered) {
            if (!unreflected.contains(s.date())) {
                continue;
            }
            BigDecimal before = sharesBefore(s, ordered, byDate);
            if (before == null) {
                checks.add(Check.warning("web_shares", source + " split at " + s.date() + ": no published count before "
                        + "it, so the per-share figures of before it are not adjusted"));
                continue;
            }
            used.add(new WebSplit(s.date(), s.factor(), before));
        }
        String basis = "shares outstanding from " + source + " (checked against the filing's EPS): " + String.join(", ", accepted);
        checks.add(Check.ok("web_shares", basis));
        return new ShareCapital(null, basis, List.copyOf(snapshots), null, null, checks, source, List.copyOf(used));
    }

    /**
     * How a published count fits the filing: {@code problem} null when it does, with the splits after its date the
     * filing's EPS predates (empty: the count fits as published).
     */
    private record WebShareFit(String problem, List<WebSplit> splits) {

        /** " (the filing's EPS is on the count of before the split 1:10 of 2025-07-15)", or "" */
        String basis() {
            return splits.isEmpty() ? "" : " (the filing's EPS is on the count of before the split"
                    + (splits.size() > 1 ? "s " : " ")
                    + String.join(" and ", splits.stream().map(s -> ratio(s.factor()) + " of " + s.date()).toList()) + ")";
        }
    }

    /** "1:10" for a split into ten shares, "5:1" for a reverse split of five shares into one. */
    private static String ratio(BigDecimal factor) {
        return factor.compareTo(BigDecimal.ONE) >= 0 ? "1:" + factor.stripTrailingZeros().toPlainString()
                : BigDecimal.ONE.divide(factor, 0, RoundingMode.HALF_UP).toPlainString() + ":1";
    }

    private static BigDecimal product(List<WebSplit> splits) {
        BigDecimal f = BigDecimal.ONE;
        for (WebSplit s : splits) {
            f = f.multiply(s.factor());
        }
        return f;
    }

    /**
     * Shares outstanding just before a split, on the count of before it: the latest published count before the split
     * divided by that split and every later one. {@code null} when nothing is published before it.
     */
    private static BigDecimal sharesBefore(WebSplit split, List<WebSplit> ordered, Map<LocalDate, WebShareCount> byDate) {
        WebShareCount latest = byDate.values().stream()
                .filter(c -> c.date().isBefore(split.date()) && c.outstanding() != null && c.outstanding().signum() > 0)
                .max(Comparator.comparing(WebShareCount::date)).orElse(null);
        if (latest == null) {
            return null;
        }
        BigDecimal factor = product(ordered.stream().filter(s -> !s.date().isBefore(split.date())).toList());
        return latest.outstanding().divide(factor, 0, RoundingMode.HALF_UP);
    }

    /**
     * Fills the dates the website has no count for from an accepted count of the same filing: same share capital,
     * no treasury stock at either date, and the EPS of the period that date opens or closes reproduced
     * ({@link #withWebShareCounts}). Two accepted counts that differ for the same share capital carry nothing.
     *
     * @param filed       the filing's dates as {@link #shareCapital} read them (a treasury share count of zero: the
     *                    statement of changes in equity shows no treasury stock at that date)
     * @param snapshots   the same dates in the same order, with the accepted counts; completed in place
     * @param byDate      the published counts (a date with a figure, accepted or rejected, is never carried to)
     * @param unreflected dates of the splits the carried counts need; completed in place
     * @return what was carried, e.g. "2021-12-31: 123275050000 (as at 2022-12-31)"
     */
    private List<String> carryWebShareCounts(List<ShareAt> filed, List<ShareAt> snapshots, Map<LocalDate, WebShareCount> byDate,
                                             List<WebSplit> ordered, Set<LocalDate> unreflected, String source) {
        List<String> carried = new ArrayList<>();
        List<ShareAt> accepted = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            ShareAt a = snapshots.get(i);
            // accepted from the website, and neither the filing nor the website shows treasury shares there
            if (a.sharesOutstanding() != null && noTreasuryStock(filed.get(i))
                    && (a.treasuryShares() == null || a.treasuryShares().signum() == 0)) {
                accepted.add(a);
            }
        }
        for (int i = 0; i < snapshots.size(); i++) {
            ShareAt p = snapshots.get(i);
            if (p.sharesOutstanding() != null || byDate.containsKey(p.date()) || !noTreasuryStock(filed.get(i))) {
                continue;
            }
            List<ShareAt> same = accepted.stream().filter(a -> a.commonStock().compareTo(p.commonStock()) == 0).toList();
            if (same.isEmpty() || same.stream().anyMatch(a -> a.sharesOutstanding().compareTo(same.getFirst().sharesOutstanding()) != 0)) {
                continue;
            }
            ShareAt from = same.getFirst();
            WebShareFit fit = webShareCountFit(new WebShareCount(p.date(), from.sharesOutstanding(), null, null), p.date(),
                    ordered, Map.of());
            if (fit.problem() != null) {
                continue;
            }
            snapshots.set(i, new ShareAt(p.date(), p.commonStock(), from.sharesOutstanding(), ZERO, null,
                    source + " count at " + from.date() + ", carried to " + p.date()
                            + " (share capital unchanged, no treasury stock), checked against the filing's EPS" + fit.basis()));
            carried.add(p.date() + ": " + from.sharesOutstanding().toPlainString() + " (as at " + from.date() + ")" + fit.basis());
            fit.splits().forEach(s -> unreflected.add(s.date()));
        }
        return carried;
    }

    private static boolean noTreasuryStock(ShareAt filed) {
        return filed.treasuryShares() != null && filed.treasuryShares().signum() == 0;
    }

    /** Why a published count is not consistent in itself, {@code null} when it is. */
    private static String inconsistency(WebShareCount c) {
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
        return null;
    }

    /**
     * How a published count fits this filing: as published, else on the count of before the published splits after
     * its date that the filing may predate - the latest, the latest two, ... - when exactly one of these fits.
     */
    private WebShareFit webShareCountFit(WebShareCount c, LocalDate date, List<WebSplit> ordered,
                                         Map<LocalDate, WebShareCount> byDate) {
        String inconsistent = inconsistency(c);
        if (inconsistent != null) {
            return new WebShareFit(inconsistent, List.of());
        }
        String asPublished = webShareCountProblem(c, date, BigDecimal.ONE, ordered, byDate);
        if (asPublished == null) {
            return new WebShareFit(null, List.of());
        }
        List<WebSplit> after = ordered.stream().filter(s -> s.date().isAfter(date)).toList();
        List<List<WebSplit>> fitting = new ArrayList<>();
        for (int j = after.size() - 1; j >= 0; j--) {
            List<WebSplit> predated = after.subList(j, after.size());
            if (webShareCountProblem(c, date, product(predated), ordered, byDate) == null) {
                fitting.add(List.copyOf(predated));
            }
        }
        if (fitting.size() == 1) {
            return new WebShareFit(null, fitting.getFirst());
        }
        if (fitting.size() > 1) {
            return new WebShareFit(asPublished + "; it fits on the count of before more than one choice of the published "
                    + "splits" + fitting.stream().map(f -> new WebShareFit(null, f).basis()).toList()
                    + ", so which ones the filing predates is not decidable", List.of());
        }
        return new WebShareFit(asPublished + (after.isEmpty() ? "" : " (nor on the count of before the published split"
                + (after.size() > 1 ? "s " : " ") + String.join(", ", after.stream()
                .map(s -> ratio(s.factor()) + " of " + s.date()).toList()) + ")"), List.of());
    }

    /**
     * Why a consistent published count does not fit this filing's EPS, {@code null} when it does.
     *
     * @param factor the splits the filing predates: the filing's count is the published one / factor
     * @param byDate the published counts, for the count at the start of a period the date ends (buybacks; not across
     *               a published split in the period)
     */
    private String webShareCountProblem(WebShareCount c, LocalDate date, BigDecimal factor, List<WebSplit> ordered,
                                        Map<LocalDate, WebShareCount> byDate) {
        StatementColumn column = date.equals(info.current().end()) ? StatementColumn.CURRENT_PERIOD
                : date.equals(info.prior().end()) ? StatementColumn.PRIOR_PERIOD
                : date.equals(info.current().start().minusDays(1)) ? StatementColumn.CURRENT_PERIOD
                : date.equals(info.prior().start().minusDays(1)) ? StatementColumn.PRIOR_PERIOD : null;
        int ctx = column == null ? -1 : durationIndex(column);
        StatementTable profitOrLoss = profitOrLossAsFiled();
        if (ctx < 0) {
            return "no profit or loss period of this filing opens or closes on that date";
        }
        if (profitOrLoss == null) {
            return "the filing has no profit or loss to check the count against";
        }
        BigDecimal parent = amount(profitOrLoss.value(PROFIT_PARENT, ctx));
        BigDecimal eps = epsWithDiscontinued(profitOrLoss, EPS_BASIC, EPS_BASIC_DISC, ctx, null);
        if (parent == null || eps == null || parent.signum() == 0 || eps.signum() == 0 || parent.signum() != eps.signum()) {
            return "the " + period(column).key() + " profit attributable to the parent and basic EPS do not allow a check";
        }
        BigDecimal implied = parent.divide(c.outstanding().divide(factor, MathContext.DECIMAL64), MathContext.DECIMAL64);
        BigDecimal halfUlp = BigDecimal.ONE.movePointLeft(Math.max(0, eps.stripTrailingZeros().scale())).divide(BigDecimal.valueOf(2));
        BigDecimal tolerance = halfUlp.add(eps.abs().multiply(WEB_SHARES_EPS_TOLERANCE));
        if (implied.subtract(eps).abs().compareTo(tolerance) <= 0) {
            return null;
        }
        String scaled = factor.compareTo(BigDecimal.ONE) == 0 ? "count" : "(count / " + factor.stripTrailingZeros().toPlainString() + ")";
        String problem = "profit attributable to the parent / " + scaled + " = "
                + implied.setScale(Math.max(0, eps.scale()) + 2, RoundingMode.HALF_UP).toPlainString()
                + " but the " + period(column).key() + " basic EPS is " + eps.toPlainString();
        // a buyback in the period the date ends: the EPS lies between profit / the start count and profit / the end count
        PeriodRef ended = period(column);
        WebShareCount opening = date.equals(ended.end()) ? byDate.get(ended.start().minusDays(1)) : null;
        WebShareCount start = opening != null
                && ordered.stream().noneMatch(s -> s.date().isAfter(opening.date()) && !s.date().isAfter(date)) ? opening : null;
        if (start != null && inconsistency(start) == null && c.issued() != null && start.issued() != null
                && c.issued().subtract(start.issued()).abs().compareTo(start.issued().multiply(WEB_SHARES_ISSUED_DRIFT)) <= 0
                && start.outstanding().compareTo(c.outstanding()) != 0) {
            BigDecimal atStart = parent.divide(start.outstanding().divide(factor, MathContext.DECIMAL64), MathContext.DECIMAL64);
            if (eps.compareTo(implied.min(atStart).subtract(tolerance)) >= 0 && eps.compareTo(implied.max(atStart).add(tolerance)) <= 0) {
                return null;
            }
            problem += ", nor between it and profit / the count at " + start.date() + " = "
                    + atStart.setScale(Math.max(0, eps.scale()) + 2, RoundingMode.HALF_UP).toPlainString();
        }
        return problem;
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
        StatementTable profitOrLoss = shareProfitOrLoss();
        if (profitOrLoss == null || ctx < 0 || commonStartEnd == null || treasuryStartEnd == null
                || commonStartEnd[0] == null || commonStartEnd[1] == null
                || commonStartEnd[0].compareTo(commonStartEnd[1]) != 0
                || treasuryStartEnd[0] == null || treasuryStartEnd[1] == null
                || treasuryStartEnd[0].compareTo(treasuryStartEnd[1]) != 0) {
            return null;
        }
        BigDecimal discontinued = amount(profitOrLoss.value(PROFIT_DISCONTINUED, ctx));
        BigDecimal epsDiscontinued = eps(EPS_BASIC_DISC, ctx);
        if ((discontinued != null && discontinued.signum() != 0) || (epsDiscontinued != null && epsDiscontinued.signum() != 0)) {
            return null;
        }
        BigDecimal parent = amount(profitOrLoss.value(PROFIT_PARENT, ctx));
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
        // one share is an EPS cell that holds the profit itself: FLMC's FY2022 - FY2025 workbooks file it as basic EPS
        // (FY2025: -22,991,911,126 = profit attributable to the parent), which gave 1 share outstanding at every year
        // end. A small count can be real (CBPE FY2021 before its IPO: 30,160,409,174 / 861,726 = 35,000 shares).
        if (shares.signum() <= 0 || implied.subtract(shares).abs().compareTo(new BigDecimal("0.01")) > 0
                || shares.compareTo(BigDecimal.ONE) == 0) {
            return null;
        }
        // nor more shares than rupiah of share capital: no IDX share has a par value under Rp 1 (GOTO's is Rp 1). CCSI's
        // 9M 2026 workbook (thousands) files its EPS in thousands too, 0.0099177777 for Rp 9.92: 12,933,869,000 / 0.0099177777
        // = 1,304,109,585,324 shares beside a share capital of 133,333,333,000 (Rp 0.10 a share; 1,333,333,330 at par 100)
        if (rupiahShareCapital() && commonStartEnd[1].signum() > 0
                && commonStartEnd[1].divide(shares, MathContext.DECIMAL64).compareTo(BigDecimal.ONE) < 0) {
            return null;
        }
        return new EpsShares(column, shares, commonStartEnd[1], treasuryStartEnd[1],
                period(column).key() + ": profit attributable to the parent " + parent.toPlainString()
                        + " / basic EPS " + eps.toPlainString() + " = " + shares.toPlainString() + " shares outstanding");
    }

    /**
     * Weighted shares = shares outstanding when share capital did not change during the period and the company
     * held no treasury stock in it. Treasury shares are not outstanding and their count is not in the filing, so
     * with treasury stock at the start or the end of the period the weighted count is unknown (ULTJ FY2022:
     * 11,553,520,000 issued shares of which 1,155,352,800 bought back; its EPS 92 is per outstanding share).
     */
    private static BigDecimal weighted(BigDecimal[] startEnd, BigDecimal[] treasuryStartEnd, BigDecimal par) {
        if (par == null || startEnd == null || startEnd[0] == null || startEnd[1] == null
                || startEnd[0].compareTo(startEnd[1]) != 0) {
            return null;
        }
        if (treasuryStartEnd != null && ((treasuryStartEnd[0] != null && treasuryStartEnd[0].signum() != 0)
                || (treasuryStartEnd[1] != null && treasuryStartEnd[1].signum() != 0))) {
            return null;
        }
        return sharesAtPar(startEnd[1], par);
    }

    /** Share capital / par value; exact where the quotient terminates (not always for par 15). */
    private static BigDecimal sharesAtPar(BigDecimal commonStock, BigDecimal par) {
        try {
            return commonStock.divide(par);
        } catch (ArithmeticException nonTerminating) {
            return commonStock.divide(par, MathContext.DECIMAL64);
        }
    }

    private BigDecimal inferParValue(StatementColumn column, BigDecimal commonStock, List<Check> checks) {
        int ctx = durationIndex(column);
        StatementTable profitOrLoss = shareProfitOrLoss();
        if (profitOrLoss == null || !rupiahShareCapital() || ctx < 0 || commonStock == null) {
            return null;
        }
        BigDecimal parent = amount(profitOrLoss.value(PROFIT_PARENT, ctx));
        BigDecimal eps = eps(EPS_BASIC, ctx);
        if (parent == null || eps == null || eps.signum() == 0) {
            return null;
        }
        int decimals = Math.max(0, eps.stripTrailingZeros().scale());
        // the rounding of the figure as filed: TUGU FY2024's 0.0002 (millions) is 150 .. 250 rupiah, not 199.5 .. 200.5
        BigDecimal tolerance = epsHalfUlp(profitOrLoss, EPS_BASIC, ctx).add(new BigDecimal("1e-9"));
        List<BigDecimal> matches = new ArrayList<>();
        for (BigDecimal par : STANDARD_PAR_VALUES) {
            if (commonStock.remainder(par).signum() != 0) {
                continue;   // not a whole number of shares (and 1 / 15 has no exact decimal quotient)
            }
            BigDecimal shares = commonStock.divide(par, 10, RoundingMode.UNNECESSARY).stripTrailingZeros();
            if (shares.scale() > 0 || shares.signum() <= 0) {
                continue;
            }
            BigDecimal implied = parent.divide(shares, decimals + 6, RoundingMode.HALF_UP);
            // an EPS filed without the sign of its loss (BUVA FY2022: 26 and 51) fits by its size, as signOfProfit reads it
            if (implied.abs().subtract(eps.abs()).abs().compareTo(tolerance) <= 0) {
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

    /** Sheet code as filed in this taxonomy, for messages ("1670000" -> "3670000" for the Infrastructure Industry). */
    public String sheetCode(String generalCode) {
        return code(generalCode);
    }

    /** Sheet code as filed in this taxonomy, for messages ("1220000" -> "4220000"). */
    private String code(String sheet) {
        return taxonomy.code(sheet);
    }

    private String taxonomyName() {
        return switch (taxonomy) {
            case GENERAL -> "General Industry";
            case PROPERTY -> "Property and Real Estate Industry";
            case INFRASTRUCTURE -> "Infrastructure Industry";
            case FINANCIAL -> "Financial and Sharia Industry";
            case FINANCING -> "Financing Industry";
            case INSURANCE -> "Insurance Industry";
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
        // filings in thousands / millions may be off by one reported unit because every line is rounded separately
        if (precision.compareTo(BigDecimal.ONE) > 0 && diff.compareTo(precision) <= 0) {
            return Check.ok(rule, description);
        }
        if (diff.signum() == 0) {
            return Check.ok(rule, description);
        }
        // otherwise they must match, up to a slip of under a billionth of the amount (WIFI FY2022, full amounts: beginning
        // cash 10,565,474,157 + net change 10,363,301,088 = 20,928,775,245, ending cash filed 20,928,775,242; AGRO FY2022,
        // thousands: 2,140,267,174 - 9,894,016 = 2,130,373,158, ending cash filed 2,130,373,160)
        BigDecimal tolerance = computed.abs().max(reported.abs()).multiply(FULL_AMOUNT_SLIP);
        return diff.compareTo(tolerance) < 0
                ? Check.warning(rule, description + ": computed " + computed.toPlainString() + " vs reported "
                + reported.toPlainString() + " (difference " + diff.toPlainString() + ", under a billionth of the "
                + "amount: a slip in the filing; the reported figure is stored)")
                : Check.error(rule, description + ": computed " + computed.toPlainString()
                + " vs reported " + reported.toPlainString() + " (difference " + diff.toPlainString() + ")");
    }

    /**
     * Largest relative difference of a full-amount filing's own figures that is taken as a slip, not a missing line
     * (also the verifier's for ending cash against balance-sheet cash).
     */
    public static final BigDecimal FULL_AMOUNT_SLIP = new BigDecimal("1e-9");

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
