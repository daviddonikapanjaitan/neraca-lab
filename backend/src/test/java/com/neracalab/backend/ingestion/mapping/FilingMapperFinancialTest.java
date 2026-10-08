package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.xlsx.IdxSheets;
import com.neracalab.backend.ingestion.xlsx.IdxTaxonomy;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The "Financial and Sharia Industry" taxonomy (BNGA, a bank): sheet codes starting with 4, a balance
 * sheet by order of liquidity (4220000), profit or loss by nature (4322000) and bank line items. Amounts
 * are filed in IDR millions.
 */
class FilingMapperFinancialTest {

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    @ParameterizedTest
    @ValueSource(strings = {"2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II"})
    void everyBngaFilingMapsWithoutErrors(String filing) throws Exception {
        FilingMapper mapper = mapper(filing);
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(mapper.taxonomy()).isEqualTo(IdxTaxonomy.FINANCIAL);
        assertThat(mapper.info().ticker()).isEqualTo("BNGA");
        assertThat(mapper.info().currency()).isEqualTo("IDR");
        assertThat(mapper.balanceSheetSheet()).isEqualTo(IdxSheets.BALANCE_SHEET_LIQUIDITY);    // read from 4220000
        assertThat(mapper.incomeSheet()).isEqualTo(IdxSheets.INCOME_BY_NATURE_BEFORE_TAX);      // read from 4322000
        for (StatementColumn column : mapper.columns()) {
            List<MappedStatement> statements = session.statements(column);
            assertThat(statements).hasSize(column == StatementColumn.PRIOR_YEAR_END ? 1 : column == StatementColumn.PRIOR_PERIOD
                    && !mapper.info().current().isFullYear() ? 2 : 3);
            for (MappedStatement s : statements) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(c -> c.severity() == Check.Severity.WARNING).toList())
                        .as(filing + " " + column + " " + s.table()).isEmpty();
            }
            assertThat(session.segments(column)).isEmpty();
        }
    }

    /** FY2025 annual report: profit or loss of a bank. */
    @Test
    void incomeStatementOfABank() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");
        Map<String, BigDecimal> v = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow().values();

        // 24,671,960 interest + 2,168,955 + 127,445 trading + 2,958,926 fees - 64,241 FX + 768,314 other
        assertThat(v.get("revenue")).isEqualByComparingTo(m(30631359));
        assertThat(v.get("cost_of_revenue")).isEqualByComparingTo(m(11195671));
        assertThat(v.get("gross_profit")).isEqualByComparingTo(m(19435688));
        // 1,448,283 + 337,792 impairment + 3,668,085 G&A + 5,300,061 other
        assertThat(v.get("operating_expenses")).isEqualByComparingTo(m(10754221));
        assertThat(v.get("sga_expense")).isEqualByComparingTo(m(3668085));
        assertThat(v.get("operating_income")).isEqualByComparingTo(m(8782085));
        assertThat(v.get("interest_income")).isEqualByComparingTo(m(24671960));
        assertThat(v.get("interest_expense")).isEqualByComparingTo(m(11195671));
        assertThat(v.get("pretax_income")).isEqualByComparingTo(m(8825765));
        assertThat(v.get("income_tax")).isEqualByComparingTo(m(1890380));
        assertThat(v.get("net_income")).isEqualByComparingTo(m(6935385));
        assertThat(v.get("net_income_to_parent")).isEqualByComparingTo(m(6876537));
        assertThat(v.get("basic_eps")).isEqualByComparingTo("273.53");
        // PP&E roll-forward (incl. right-of-use): 614,178 additions to accumulated depreciation
        assertThat(v.get("depreciation")).isEqualByComparingTo(m(614178));
        // intangibles 2,044,380 + 456,923 net acquisitions - 2,028,737
        assertThat(v.get("amortization")).isEqualByComparingTo(m(472566));
        assertThat(v).containsEntry("ebit", null).containsEntry("ebitda", null).containsEntry("rd_expense", null);
    }

    /** FY2025: no current / non-current split, deposits are not debt, cash equivalents from the cash flow statement. */
    @Test
    void balanceSheetOfABank() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");
        Map<String, BigDecimal> current = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital())
                .orElseThrow().values();
        Map<String, BigDecimal> prior = mapper.balanceSheet(StatementColumn.PRIOR_PERIOD, mapper.shareCapital())
                .orElseThrow().values();

        assertThat(current.get("total_assets")).isEqualByComparingTo(m(372698893));
        assertThat(current.get("total_liabilities")).isEqualByComparingTo(m(314541710));
        assertThat(current.get("shareholders_equity")).isEqualByComparingTo(m(57939857));
        assertThat(current.get("non_controlling_interest")).isEqualByComparingTo(m(217326));
        assertThat(current.get("total_equity")).isEqualByComparingTo(m(58157183));
        assertThat(current.get("cash_and_equivalents")).isEqualByComparingTo(m(29750458));
        assertThat(prior.get("cash_and_equivalents")).isEqualByComparingTo(m(24337359));
        // 20,326,814 marketable securities - 782 allowance
        assertThat(current.get("marketable_securities")).isEqualByComparingTo(m(20326032));
        // 4,455,255 borrowings + 3,685,222 bonds + 0 sukuk / subordinated bonds / MTN (subordinated loans repaid)
        assertThat(current.get("long_term_debt")).isEqualByComparingTo(m(8140477));
        // FY2024: 8,487,935 borrowings + 1,687,452 bonds + 38,747 + 35,767 subordinated loans
        assertThat(prior.get("long_term_debt")).isEqualByComparingTo(m(10249901));
        // 371,525 general and legal reserves + 43,740,945 unappropriated
        assertThat(current.get("retained_earnings")).isEqualByComparingTo(m(44112470));
        assertThat(current.get("intangible_assets")).isEqualByComparingTo(m(2028737));
        for (String notApplicable : List.of("current_assets", "current_liabilities", "accounts_receivable", "inventory",
                "short_term_debt", "accounts_payable", "lease_liabilities")) {
            assertThat(current).as(notApplicable).containsEntry(notApplicable, null);
        }
    }

    /** FY2025: investing lines are net of disposals and signed; securities issued is one net line. */
    @Test
    void cashFlowOfABank() throws Exception {
        Map<String, BigDecimal> v = mapper("2025-Tahunan").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow().values();

        assertThat(v.get("operating_cash_flow")).isEqualByComparingTo(m(4150199));
        assertThat(v.get("investing_cash_flow")).isEqualByComparingTo(m(7643004));
        assertThat(v.get("financing_cash_flow")).isEqualByComparingTo(m(-6459266));
        // -363,617 property and equipment - 456,923 intangibles
        assertThat(v.get("capital_expenditure")).isEqualByComparingTo(m(-820540));
        // 20,983,000 borrowings + 3,100,000 net increase in securities issued
        assertThat(v.get("debt_issued")).isEqualByComparingTo(m(24083000));
        // 25,018,464 borrowings + 75,000 subordinated loans + 1,306,192 bonds
        assertThat(v.get("debt_repaid")).isEqualByComparingTo(m(-26399656));
        assertThat(v.get("dividends_paid")).isEqualByComparingTo(m(-3954222));
        assertThat(v.get("share_buybacks")).isEqualByComparingTo("0");     // 3,283 net sales of treasury stocks
        assertThat(v.get("cash_change")).isEqualByComparingTo(m(5333937));
        assertThat(v.get("ending_cash")).isEqualByComparingTo(m(29750458));
        assertThat(v).containsEntry("lease_payments", null);
    }

    /** Interim filing (2026 H1): the prior year end column has a balance sheet with the opening cash. */
    @Test
    void interimFilingHasThePriorYearEnd() throws Exception {
        FilingMapper mapper = mapper("2026-II");
        Map<String, BigDecimal> yearEnd = mapper.balanceSheet(StatementColumn.PRIOR_YEAR_END, mapper.shareCapital())
                .orElseThrow().values();

        assertThat(mapper.columns()).containsExactly(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD,
                StatementColumn.PRIOR_YEAR_END);
        assertThat(yearEnd.get("total_assets")).isEqualByComparingTo(m(372698893));
        assertThat(yearEnd.get("cash_and_equivalents")).isEqualByComparingTo(m(29750458));
        assertThat(mapper.balanceSheet(StatementColumn.PRIOR_PERIOD, mapper.shareCapital())).isEmpty();
    }

    /** Each annual filing's comparative column equals the previous filing's current column. */
    @Test
    void comparativesMatchThePreviousFiling() throws Exception {
        List<String> years = List.of("2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan");
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (int i = 1; i < years.size(); i++) {
            FilingMapper previous = mapper(years.get(i - 1));
            FilingMapper next = mapper(years.get(i));
            List<MappedStatement> a = List.of(
                    previous.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), previous.shareCapital()).orElseThrow(),
                    previous.balanceSheet(StatementColumn.CURRENT_PERIOD, previous.shareCapital()).orElseThrow(),
                    previous.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow());
            List<MappedStatement> b = List.of(
                    next.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), next.shareCapital()).orElseThrow(),
                    next.balanceSheet(StatementColumn.PRIOR_PERIOD, next.shareCapital()).orElseThrow(),
                    next.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow());
            for (int s = 0; s < a.size(); s++) {
                for (var e : a.get(s).values().entrySet()) {
                    // D&A are only derived for a filing's current column
                    if (List.of("depreciation", "amortization").contains(e.getKey())) {
                        continue;
                    }
                    BigDecimal x = e.getValue();
                    BigDecimal y = b.get(s).values().get(e.getKey());
                    compared++;
                    boolean same = x == null || y == null ? x == y : x.compareTo(y) == 0;
                    if (!same) {
                        mismatches.add(years.get(i) + " " + a.get(s).table() + "." + e.getKey() + ": " + x + " vs " + y);
                    }
                }
            }
        }
        assertThat(mismatches).isEmpty();
        assertThat(compared).isGreaterThan(100);
    }

    /**
     * BNGA has two share classes (different par values) and treasury stock, and its EPS has 2 decimals:
     * no par value fits and profit / EPS is not exact, so no share count is guessed.
     */
    @Test
    void noShareCountIsGuessed() throws Exception {
        ShareCapital shares = mapper("2025-Tahunan").shareCapital();

        assertThat(shares.resolved()).isFalse();
        assertThat(shares.snapshots()).isNotEmpty().allSatisfy(s -> assertThat(s.sharesOutstanding()).isNull());
        assertThat(shares.snapshots().getFirst().commonStock()).isEqualByComparingTo(m(1612787));
    }

    /** BTPN (another bank): every filing maps; FY2023's bancassurance fees ("Insurance commission income") are revenue. */
    @ParameterizedTest
    @ValueSource(strings = {"2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan"})
    void everyBtpnFilingMapsWithoutErrors(String filing) throws Exception {
        Path file = Path.of("..", "data", "BTPN", "xlsx", "FinancialStatement-" + filing + "-BTPN.xlsx");
        assumeTrue(Files.exists(file), "BTPN source data not available: " + file);
        FilingMapper mapper;
        try (InputStream in = Files.newInputStream(file)) {
            mapper = new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(mapper.taxonomy()).isEqualTo(IdxTaxonomy.FINANCIAL);
        for (StatementColumn column : mapper.columns()) {
            for (MappedStatement s : session.statements(column)) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
            }
        }
    }

    /** BMRI (a bank with an insurance subsidiary): every filing maps; insurance premiums are revenue. */
    @ParameterizedTest
    @ValueSource(strings = {"2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II"})
    void everyBmriFilingMapsWithoutErrors(String filing) throws Exception {
        FilingMapper mapper = bmri(filing);
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(mapper.taxonomy()).isEqualTo(IdxTaxonomy.FINANCIAL);
        for (StatementColumn column : mapper.columns()) {
            for (MappedStatement s : session.statements(column)) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
            }
        }
    }

    /**
     * BMRI FY2025, FY2024 comparative: "Claim expenses" 10,574,450 is shown beside premiums of 2,520,813 that are already
     * net of it; "Total profit from operation" 76,059,595 does not deduct it. Deducted, the job was INCOMPLETE.
     */
    @Test
    void anInsuranceClaimLineShownForInformationIsNotDeductedTwice() throws Exception {
        FilingMapper mapper = bmri("2025-Tahunan");
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(prior.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(prior.checks()).anySatisfy(c -> {
            assertThat(c.rule()).isEqualTo("memo_line");
            assertThat(c.message()).startsWith("'Claim expenses' 10574450000000 is not deducted");
        });
        Map<String, BigDecimal> v = prior.values();
        // 151,236,027 interest + 2,520,813 premiums + 150,297 + 23,447,520 fees + 4,483,298 trading + 4,929,641 other
        assertThat(v.get("revenue")).isEqualByComparingTo(m(186767596));
        assertThat(v.get("cost_of_revenue")).isEqualByComparingTo(m(49479107));     // interest only, no claims
        assertThat(v.get("operating_income")).isEqualByComparingTo(m(76059595));
        assertThat(v.get("net_income_to_parent")).isEqualByComparingTo(m(55782742));

        // FY2025 itself has no claim line: premiums (net insurance result 550,415) are revenue, nothing is ignored
        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow();
        assertThat(current.checks()).noneMatch(c -> c.isError() || c.rule().equals("memo_line"));
        assertThat(current.values().get("operating_income")).isEqualByComparingTo(m(76310739));

        // a classification given by the agent is never second-guessed: the check then fails and says why
        MappedStatement agent = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD,
                Map.of("Claim expenses", IncomeLineCategory.COST_OF_REVENUE), mapper.shareCapital()).orElseThrow();
        assertThat(agent.checks()).anyMatch(c -> c.isError() && c.rule().equals("operating_income"));
    }

    /**
     * BMRI FY2025 (Bank Syariah Indonesia, a subsidiary): temporary syirkah funds 289,620,824 million are neither
     * liabilities nor equity; total liabilities stays the filing's 2,212,925,204 (it was stored with the funds added).
     */
    @Test
    void temporarySyirkahFundsAreNotLiabilities() throws Exception {
        FilingMapper mapper = bmri("2025-Tahunan");
        MappedStatement b = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital()).orElseThrow();

        assertThat(b.checks().stream().filter(Check::isError).toList()).isEmpty();
        Map<String, BigDecimal> v = b.values();
        assertThat(v.get("total_liabilities")).isEqualByComparingTo(m(2212925204L));
        assertThat(v.get("temporary_syirkah_funds")).isEqualByComparingTo(m(289620824));
        assertThat(v.get("total_equity")).isEqualByComparingTo(m(327401998));
        assertThat(v.get("total_liabilities").add(v.get("temporary_syirkah_funds")).add(v.get("total_equity")))
                .isEqualByComparingTo(v.get("total_assets"));

        // BNGA (sharia unit): liabilities + syirkah funds + equity = assets as well
        Map<String, BigDecimal> bnga = mapper("2025-Tahunan").balanceSheet(StatementColumn.CURRENT_PERIOD, null)
                .orElseThrow().values();
        BigDecimal syirkah = bnga.get("temporary_syirkah_funds") == null ? BigDecimal.ZERO : bnga.get("temporary_syirkah_funds");
        assertThat(bnga.get("total_liabilities").add(syirkah).add(bnga.get("total_equity")))
                .isEqualByComparingTo(bnga.get("total_assets"));
    }

    private static FilingMapper bmri(String filing) throws Exception {
        Path file = Path.of("..", "data", "BMRI", "xlsx", "FinancialStatement-" + filing + "-BMRI.xlsx");
        assumeTrue(Files.exists(file), "BMRI source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }

    private static BigDecimal m(long millions) {
        return BigDecimal.valueOf(millions).multiply(MILLION);
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "BNGA", "xlsx", "FinancialStatement-" + filing + "-BNGA.xlsx");
        assumeTrue(Files.exists(file), "BNGA source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
