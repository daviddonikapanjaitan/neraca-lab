package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Filings of the error log ({@code ingestion_error}) of 2026-10-15:
 * <ul>
 *   <li>WIFI FY2025 (error 418): the filed financing total leaves out the dividends paid; the net change in cash is
 *       the sum of the lines.</li>
 *   <li>WIFI H1 2026 (error 419): the H1 2025 comparative enters three financing cash flows twice.</li>
 *   <li>WIFI FY2022 (error 420): ending cash filed 3 rupiah below beginning cash + net change.</li>
 *   <li>TPIA H1 2026 and FY2025 (errors 416, 417): comparatives that contradict themselves; still refused.</li>
 * </ul>
 */
class FilingMapperWifiTpiaTest {

    /**
     * Financing filed 10,909,102,818,568; its lines add up to 10,904,384,108,332 (without the dividends paid,
     * 4,718,710,236, the filed total). Operating -814,048,360,263 + investing -3,944,132,255,717 + the sum of the
     * lines = 6,146,203,492,352, the net change as filed; beginning 18,495,026,165 + net change = ending
     * 6,164,698,518,517 as filed.
     */
    @Test
    void aFiledSectionTotalThatLeavesOutALineIsCountedAtTheSumOfItsLines() throws Exception {
        MappedStatement current = mapper("WIFI", "2025-Tahunan").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(current.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(current.values().get("financing_cash_flow")).isEqualByComparingTo("10904384108332");
        assertThat(current.values().get("operating_cash_flow")).isEqualByComparingTo("-814048360263");
        assertThat(current.values().get("investing_cash_flow")).isEqualByComparingTo("-3944132255717");
        assertThat(current.values().get("cash_change")).isEqualByComparingTo("6146203492352");
        assertThat(current.values().get("dividends_paid")).isEqualByComparingTo("-4718710236");
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("financing_total") && c.severity() == Check.Severity.WARNING
                && c.message().contains("10909102818568") && c.message().contains("10904384108332"));
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("net_change") && c.severity() == Check.Severity.OK);
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("financing_section") && c.severity() == Check.Severity.OK);

        // FY2024 (the comparative) adds up as filed: no correction
        MappedStatement prior = mapper("WIFI", "2025-Tahunan").cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.checks()).noneMatch(c -> c.rule().endsWith("_total"));
    }

    /**
     * H1 2025 in the H1 2026 filing: bank loans 783,412,016,329 also as unsecured loans, the repayment of other
     * borrowings 15,495,302,170 also as negative non-bank proceeds, consumer financing 674,885,668 as proceeds and as a
     * negative payment. Counted once, the lines add up to the filed total 2,094,131,138,414.
     */
    @Test
    void amountsEnteredTwiceAreCountedOnce() throws Exception {
        MappedStatement prior = mapper("WIFI", "2026-II").cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();

        assertThat(prior.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(prior.values().get("financing_cash_flow")).isEqualByComparingTo("2094131138414");
        assertThat(prior.values().get("debt_issued")).isEqualByComparingTo("784086901997");    // 783,412,016,329 + 674,885,668
        assertThat(prior.values().get("debt_repaid")).isEqualByComparingTo("-15495302170");
        assertThat(prior.values().get("lease_payments")).isEqualByComparingTo("-3088230911");
        assertThat(prior.values().get("stock_issuance")).isEqualByComparingTo("1000000000000");
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("financing_section") && c.severity() == Check.Severity.WARNING
                && c.message().contains("Proceeds from unsecured loans")
                && c.message().contains("Proceeds from non-bank financial institution loan")
                && c.message().contains("Payments of consumer financing payables"));

        // the current column is untouched
        MappedStatement current = mapper("WIFI", "2026-II").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(current.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(current.values().get("financing_cash_flow")).isEqualByComparingTo("4970641728646");
    }

    @Test
    void ofTwoLinesWithTheSameCashFlowTheOneWhoseSignContradictsItsLabelIsDropped() {
        Map<String, BigDecimal> signed = new LinkedHashMap<>();
        Map<String, BigDecimal> raw = new LinkedHashMap<>();
        put(signed, raw, "Proceeds from bank loans", "100", "100");
        put(signed, raw, "Proceeds from non-bank financial institution loan", "-7", "-7");
        put(signed, raw, "Proceeds from unsecured loans", "100", "100");
        put(signed, raw, "Payments of other borrowings", "-7", "7");
        put(signed, raw, "Proceeds from consumer financing payables", "3", "3");
        put(signed, raw, "Payments of consumer financing payables", "3", "-3");
        put(signed, raw, "Proceeds from due to related parties", "5", "5");
        put(signed, raw, "Payments of due to related parties", "5", "-5");
        put(signed, raw, "Proceeds from bonds payable", "5", "5");     // 5 three times: left alone

        assertThat(FilingMapper.enteredTwice(signed, raw)).containsExactly("Proceeds from unsecured loans",
                "Proceeds from non-bank financial institution loan", "Payments of consumer financing payables");
    }

    /** Beginning 10,565,474,157 + net change 10,363,301,088 = 20,928,775,245; ending cash filed 20,928,775,242. */
    @Test
    void aSlipOfAFewRupiahInAFullAmountFilingIsAWarning() throws Exception {
        MappedStatement current = mapper("WIFI", "2022-Tahunan").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(current.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(current.values().get("ending_cash")).isEqualByComparingTo("20928775242");
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("cash_rollforward") && c.severity() == Check.Severity.WARNING
                && c.message().contains("difference 3"));
    }

    /**
     * TPIA H1 2025 in the H1 2026 filing (USD thousand): its lines give profit before tax 1,572,723, the filed total
     * is 1,576,527; 3,804 is no line of the column and no reversed sign. The column contradicts itself and stays
     * refused (the full-text extraction stores it with the filed totals).
     */
    @Test
    void aComparativeIncomeStatementThatContradictsItselfIsStillRefused() throws Exception {
        FilingMapper mapper = mapper("TPIA", "2026-II");
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(prior.checks()).anyMatch(c -> c.isError() && c.message().contains("difference 3804000"));
        assertThat(current.checks().stream().filter(Check::isError).toList()).isEmpty();
    }

    /**
     * TPIA FY2024 in the FY2025 filing: the breakdown repeats figures of the FY2024 filing under other names, the product
     * lines add up to 2,107,089 thousand under a product subtotal of 1,695,869. Refused; FY2024 keeps the breakdown of
     * its own filing (IngestionVerifier: a note, not an open item).
     */
    @Test
    void aComparativeBreakdownThatContradictsItselfIsStillRefused() throws Exception {
        FilingMapper mapper = mapper("TPIA", "2025-Tahunan");
        BigDecimal revenue = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow().values().get("revenue");
        SegmentExtraction prior = mapper.segments(StatementColumn.PRIOR_PERIOD, revenue).orElseThrow();

        assertThat(prior.hasErrors()).isTrue();
        assertThat(mapper.segments(StatementColumn.CURRENT_PERIOD, new BigDecimal("7020007000")).orElseThrow().hasErrors()).isFalse();
    }

    private static void put(Map<String, BigDecimal> signed, Map<String, BigDecimal> raw, String label, String s, String r) {
        signed.put(label, new BigDecimal(s));
        raw.put(label, new BigDecimal(r));
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
