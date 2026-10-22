package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The uploads of 2026-10-19 (errors 667 - 673) and the filings by nature behind them:
 * <ul>
 *   <li>COIN H1 2025: the final tax deducted above profit before tax and again as the tax below it;</li>
 *   <li>DEWA H1 2025: 'Other income' filed again inside 'Other gains (losses)';</li>
 *   <li>ENRG H1 2025: one amount on 'Other income' and 'Other gains (losses)', a unit apart;</li>
 *   <li>HEAL FY2023: the tax expense filed with the sign of a benefit;</li>
 *   <li>ESSA H1 2026: a cash roll-forward that contradicts itself (still refused);</li>
 *   <li>BUVA (by nature): share counts from the par value when no published count fits;</li>
 *   <li>GMFI, KRAS (USD): no rupiah par value divides a share capital in dollars;</li>
 *   <li>EXCL: the revenue a profit or loss by nature reports, from its breakdown where the row is empty.</li>
 * </ul>
 */
class FilingMapperCoinDewaEnrgHealTest {

    @Test
    void coinFinalTaxCountedOnceAsTheTaxBelowProfitBeforeTax() throws Exception {
        FilingMapper mapper = mapper("COIN", "2026-II");
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().contains("'Final tax expenses' and 'Tax benefit (expenses)' report the same amount"));
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("25573185374");
        assertThat(prior.values().get("income_tax")).isEqualByComparingTo("48187537");
        assertThat(prior.values().get("net_income")).isEqualByComparingTo("25524997837");

        // the current column files no final tax: read as filed
        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(current.hasErrors()).isFalse();
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("double_reported"));
    }

    @Test
    void dewaOtherIncomeIsPartOfOtherGains() throws Exception {
        FilingMapper mapper = mapper("DEWA", "2026-II");
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().startsWith("'Other income' and 'Other gains (losses)' report one amount twice"));
        assertThat(prior.derivations().get("double_reported")).contains("its amount is in 'Other gains (losses)'");
        // gross profit 495,874,409 - G&A 128,093,439 (thousand), without the other income of 1,543,226
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("367780970000");
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("246402270000");

        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(current.hasErrors()).isFalse();
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("double_reported"));
    }

    @Test
    void enrgOneAmountOnTwoLinesAUnitApart() throws Exception {
        FilingMapper mapper = mapper("ENRG", "2026-II");
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        // 'Other income' 1,903,917 counted, 'Other gains (losses)' 1,903,918 left out: only so is profit before tax exact
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().equals("'Other gains (losses)' and 'Other income' report the same amount in this column, "
                + "a filed unit apart; counted once, as 'Other income'"));
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("58365683");
    }

    @Test
    void healTaxExpenseFiledWithTheSignOfABenefit() throws Exception {
        FilingMapper mapper = mapper("HEAL", "2023-Tahunan");
        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(current.hasErrors()).as(current.checks().toString()).isFalse();
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("tax_sign") && c.message().contains("read as -151109000000"));
        assertThat(current.values().get("income_tax")).as("an expense, positive").isEqualByComparingTo("151109000000");
        assertThat(current.values().get("net_income")).isEqualByComparingTo("559284000000");

        // the comparative files its tax with the right sign (-101,315)
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        assertThat(prior.checks()).noneMatch(c -> c.rule().equals("tax_sign"));
        assertThat(prior.values().get("income_tax")).isEqualByComparingTo("101315000000");
    }

    /** Every section adds up and the ending cash is the balance sheet's, but beginning + net change is 6,950,000 short. */
    @Test
    void essaRollForwardThatContradictsItselfIsStillRefused() throws Exception {
        FilingMapper mapper = mapper("ESSA", "2026-II");
        MappedStatement current = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(current.checks()).anyMatch(c -> c.isError() && c.rule().equals("cash_rollforward")
                && c.message().contains("difference 6950000"));
        assertThat(current.checks()).anyMatch(c -> c.severity() == Check.Severity.OK && c.rule().equals("net_change"));
    }

    @Test
    void buvaByNatureGetsItsShareCountsFromTheParValueWhenNoPublishedCountFits() throws Exception {
        // Yahoo Finance publishes no count before 2023: share capital 340,563,460,000 / par 50 = 6,811,269,200 shares;
        // profit attributable to the parent -177,323,077,262 / 6,811,269,200 = -26.03, filed as 26
        FilingMapper fy2022 = mapper("BUVA", "2022-Tahunan");
        assertThat(fy2022.shareCapital().resolved()).as("published counts come first for a filing by nature").isFalse();
        ShareCapital shares = fy2022.withWebShareCounts(fy2022.shareCapital(), List.of(), "Yahoo Finance");
        assertThat(shares.resolved()).isTrue();
        assertThat(shares.parValue()).isEqualByComparingTo("50");
        assertThat(shares.basis()).isEqualTo("par value 50 (profit or loss by nature; no Yahoo Finance count fits the filing)");
        assertThat(shares.weightedCurrent()).isEqualByComparingTo("6811269200");
        assertThat(shares.weightedPrior()).isEqualByComparingTo("6811269200");
        assertThat(shares.snapshots()).anyMatch(s -> s.date().toString().equals("2021-12-31")
                && s.sharesOutstanding().compareTo(new BigDecimal("6811269200")) == 0);
        assertThat(fy2022.epsContradiction()).isNull();

        // a count that fits is used instead (BUVA H1 2026: Yahoo Finance's 24,617,054,642, the par value's count too)
        FilingMapper h1 = mapper("BUVA", "2026-II");
        ShareCapital web = h1.withWebShareCounts(h1.shareCapital(), List.of(new WebShareCount(java.time.LocalDate.parse("2026-06-30"),
                new BigDecimal("24617054642"), null, null)), "Yahoo Finance");
        assertThat(web.webSource()).isEqualTo("Yahoo Finance");
    }

    @Test
    void noRupiahParValueForAShareCapitalInDollars() throws Exception {
        // GMFI: USD 219,015,655 / par 5 = 43,803,131 "shares" reproduced the EPS 0.0001 x 1000
        FilingMapper gmfi = mapper("GMFI", "2022-Tahunan");
        assertThat(gmfi.epsScale()).isEqualByComparingTo("1");
        assertThat(gmfi.shareCapital().parValue()).isNull();

        // KRAS (USD, by function): basic EPS 0.001 as filed, no share count of 19,751,460
        FilingMapper kras = mapper("KRAS", "2022-Tahunan");
        ShareCapital shares = kras.shareCapital();
        assertThat(shares.parValue()).isNull();
        MappedStatement income = kras.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow();
        assertThat(income.values().get("basic_eps")).isEqualByComparingTo("0.001");
        assertThat(income.values().get("basic_shares")).isNull();
    }

    @Test
    void excelRevenueFromTheBreakdownWhereTheRowIsEmpty() throws Exception {
        FilingMapper fy2023 = mapper("EXCL", "2023-Tahunan");
        FilingMapper.FiledRevenue current = fy2023.filedRevenue(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(current.sheet()).isEqualTo("3312000");
        assertThat(current.amount()).isEqualByComparingTo("32322651000000");
        assertThat(current.filed()).isEqualByComparingTo("32322651000000");
        assertThat(current.breakdown()).isEqualTo("3617000");
        assertThat(fy2023.filedRevenue(StatementColumn.PRIOR_PERIOD).orElseThrow().amount()).isEqualByComparingTo("29141994000000");

        // filed on the face: the row, whatever the model adds to it
        FilingMapper.FiledRevenue fy2024 = mapper("EXCL", "2024-Tahunan").filedRevenue(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(fy2024.amount()).isEqualByComparingTo("34391597000000");
        assertThat(fy2024.breakdown()).isNull();
        assertThat(fy2024.row()).isEqualTo(current.row());

        // a bank has no such row
        assertThat(mapper("ARTO", "2022-Tahunan").filedRevenue(StatementColumn.CURRENT_PERIOD)).isEmpty();
        assertThat(List.of(fy2023.filedRevenue(StatementColumn.PRIOR_YEAR_END))).allMatch(java.util.Optional::isEmpty);
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
