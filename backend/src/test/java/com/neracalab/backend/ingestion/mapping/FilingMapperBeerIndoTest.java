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

import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Errors 958 and 959.
 * <ul>
 *   <li>BEER FY2025 filing, comparative FY2024: the restatement moved the advances and loans to other parties (made
 *       98,006,019, repaid 77,432,853) into operating, but the column still lists them under investing; the investing
 *       total -3,273,103,330 is the sum of the other lines.</li>
 *   <li>INDO FY2022 filing: beginning cash 328,144,396,948 + net change -22,837,398,084 is 305,306,998,864, the
 *       filed ending cash 305,326,998,864 (difference 20,000,000) - the filing itself, an error that stays.</li>
 * </ul>
 */
class FilingMapperBeerIndoTest {

    @Test
    void theFy2024ComparativeOfBeerLeavesTheAdvancesToOtherPartiesOut() throws Exception {
        FilingMapper mapper = mapper("BEER", "2025-Tahunan");

        MappedStatement prior = mapper.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();

        assertThat(prior.period().key()).isEqualTo("2024 FY");
        assertThat(prior.checks()).noneMatch(Check::isError);
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("investing_section")
                && c.message().contains("'Cash advances and loans made to other parties, other than financing institutions' -98006019")
                && c.message().contains("'Cash receipts from the repayment of advances and loans made to other parties' 77432853")
                && c.message().contains("are not part of the filing's investing total -3273103330"));
        assertThat(prior.values().get("operating_cash_flow")).isEqualByComparingTo("-21402282339");
        assertThat(prior.values().get("investing_cash_flow")).isEqualByComparingTo("-3273103330");
        assertThat(prior.values().get("financing_cash_flow")).isEqualByComparingTo("-2976840884");
        assertThat(prior.values().get("capital_expenditure")).isEqualByComparingTo("-6656357485");
        assertThat(prior.values().get("ending_cash")).isEqualByComparingTo("3140826124");
    }

    @Test
    void everyStatementOfBeerFy2025IsStorable() throws Exception {
        FilingMapper mapper = mapper("BEER", "2025-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        for (StatementColumn column : mapper.columns()) {
            assertThat(mapper.incomeStatement(column, Map.of(), shares).orElseThrow().hasErrors()).as(column + " income").isFalse();
            assertThat(mapper.balanceSheet(column, shares).orElseThrow().hasErrors()).as(column + " balance").isFalse();
            assertThat(mapper.cashFlow(column).orElseThrow().hasErrors()).as(column + " cash flow").isFalse();
        }
    }

    @Test
    void theOwnFilingOfBeerFy2024StillCountsTheAdvances() throws Exception {
        FilingMapper mapper = mapper("BEER", "2024-Tahunan");

        MappedStatement current = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(current.checks()).noneMatch(Check::isError);
        assertThat(current.checks()).noneMatch(c -> c.message().contains("are not part of the filing's"));
        assertThat(current.values().get("investing_cash_flow")).isEqualByComparingTo("-3293676496");
    }

    @Test
    void theCashRollForwardOfIndoFy2022StaysAnError() throws Exception {
        FilingMapper mapper = mapper("INDO", "2022-Tahunan");

        MappedStatement current = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(current.checks()).anyMatch(c -> c.rule().equals("cash_rollforward") && c.isError());
        assertThat(current.checks()).noneMatch(c -> c.message().contains("are not part of the filing's"));
    }

    @Test
    void aPairIsLeftOutOnlyWhenItIsTheOnlyOne() {
        Map<String, BigDecimal> lines = new LinkedHashMap<>();
        lines.put("Payments a", new BigDecimal("-100"));
        lines.put("Receipts b", new BigDecimal("40"));
        lines.put("Payments c", new BigDecimal("-30"));
        assertThat(FilingMapper.leftOutPair(lines, new BigDecimal("-60"))).containsExactly("Payments a", "Receipts b");
        // two outflows are not a placement and its repayment
        assertThat(FilingMapper.leftOutPair(lines, new BigDecimal("-130"))).isNull();
        lines.put("Receipts d", new BigDecimal("70"));
        // opposite pairs: (a,b) -60, (a,d) -30, (b,c) 10, (c,d) 40
        assertThat(FilingMapper.leftOutPair(lines, new BigDecimal("-60"))).containsExactly("Payments a", "Receipts b");
        lines.put("Receipts e", new BigDecimal("10"));
        lines.put("Payments f", new BigDecimal("-70"));
        // (a,b) -60 and (f,e) -60: ambiguous
        assertThat(FilingMapper.leftOutPair(lines, new BigDecimal("-60"))).isNull();
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        return new FilingMapper(workbook);
    }
}
