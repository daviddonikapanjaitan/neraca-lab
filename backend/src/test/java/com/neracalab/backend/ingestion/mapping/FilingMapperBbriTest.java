package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.MappedStatement.UnclassifiedLine;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * BRI's comparative columns repeat lines, which blocked two uploads (ingestion_error 27 and 28).
 * <ul>
 *   <li>Cash flow: a line the filed section total does not include - FY2024 in the FY2025 filing ("Withdrawal of
 *       financial assets amortized cost investments" 9,884,450 beside "Withdrawal (placement) of marketable
 *       securities" 9,884,449), H1 2025 in the H1 2026 filing (investment properties and property and equipment
 *       both 19,245; "Increase (decrease) in securities issued" and "Proceeds from bonds issuance" both
 *       13,426,357). The total is exactly the sum of the other lines, so the statement is stored with a warning.</li>
 *   <li>Profit or loss of H1 2025: "Other insurance income" and "Other insurance expenses" appear only in the
 *       comparative, cannot be classified - and are not part of the filing's totals, which reconcile exactly
 *       without them. They are ignored on that proof (error 41).</li>
 * </ul>
 */
class FilingMapperBbriTest {

    @Test
    void theComparativeCashFlowOfFy2025IsStorable() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");

        MappedStatement prior = mapper.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();

        assertThat(prior.period().key()).isEqualTo("2024 FY");
        assertThat(prior.hasErrors()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("investing_section") && c.severity() == Check.Severity.WARNING
                && c.message().contains("is not part of the filing's investing total -188189000000"));
        assertThat(prior.values().get("investing_cash_flow")).isEqualByComparingTo("-188189000000");
        assertThat(prior.values().get("operating_cash_flow")).isNotNull();
        assertThat(prior.values().get("ending_cash")).isEqualByComparingTo("205328380000000");
        // the current year has no such line: its section check passes as before
        assertThat(mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow().checks())
                .noneMatch(c -> c.severity() != Check.Severity.OK && c.rule().endsWith("_section"));
    }

    @Test
    void theComparativeCashFlowOfH12026IsStorable() throws Exception {
        FilingMapper mapper = mapper("2026-II");

        MappedStatement prior = mapper.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();

        assertThat(prior.period().key()).isEqualTo("2025 H1");
        assertThat(prior.hasErrors()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("investing_section") && c.severity() == Check.Severity.WARNING)
                .anyMatch(c -> c.rule().equals("financing_section") && c.severity() == Check.Severity.WARNING);
        assertThat(prior.values().get("investing_cash_flow")).isEqualByComparingTo("-12247448000000");
        assertThat(prior.values().get("financing_cash_flow")).isEqualByComparingTo("-38695211000000");
    }

    /**
     * H1 2025 in the H1 2026 filing: "Other insurance income" 4,466,709 and "Other insurance expenses" 4,051,819 are
     * lines the mapper cannot classify - and lines the filing's totals do not include: profit from operation
     * 35,004,968 is exactly the other lines. They are ignored on that proof (ingestion_error 41: before, the agent
     * searched three minutes for a category that would reconcile, and the full-text extraction had to take over).
     */
    @Test
    void linesOutsideTheFilingsTotalsAreIgnoredInTheComparativeProfitOrLossOfH12026() throws Exception {
        FilingMapper mapper = mapper("2026-II");
        ShareCapital shares = mapper.shareCapital();

        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow();
        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow();

        assertThat(prior.period().key()).isEqualTo("2025 H1");
        assertThat(prior.unclassified()).isEmpty();
        assertThat(prior.hasErrors()).as("storable by the standard extraction").isFalse();
        assertThat(prior.checks()).filteredOn(c -> c.rule().equals("memo_line")).extracting(Check::message)
                .hasSize(2)
                .anyMatch(m -> m.startsWith("'Other insurance income' 4466709000000 is not part of the filing's own totals"))
                .anyMatch(m -> m.startsWith("'Other insurance expenses' 4051819000000 is not part of the filing's own totals"));
        // "Non-operating income" -260,772 and "Non-operating expenses" 260,772 are one amount, counted once
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported"));
        assertThat(prior.values().get("revenue")).isEqualByComparingTo("123348477000000");
        assertThat(prior.values().get("cost_of_revenue")).isEqualByComparingTo("33153552000000");
        assertThat(prior.values().get("gross_profit")).isEqualByComparingTo("90194925000000");
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("35004968000000");
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("34744196000000");
        // the current year has no such lines and is unchanged
        assertThat(current.hasErrors()).isFalse();
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("memo_line"));
        assertThat(current.values().get("operating_income")).isEqualByComparingTo("40179716000000");
        // every statement of the filing is storable now
        for (StatementColumn column : mapper.columns()) {
            assertThat(mapper.balanceSheet(column, shares).map(MappedStatement::hasErrors).orElse(false)).as(column + " balance").isFalse();
            assertThat(mapper.cashFlow(column).map(MappedStatement::hasErrors).orElse(false)).as(column + " cash flow").isFalse();
        }
    }

    /**
     * An unclassified line the totals DO include stays with the agent: BBCA FY2025's "Other insurance expenses"
     * 1,858,302 is part of its profit from operation (without it the check fails by exactly that amount).
     */
    @Test
    void aLineTheTotalsIncludeIsNotIgnored() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "BBCA", "xlsx", "FinancialStatement-2025-Tahunan-BBCA.xlsx");
        assumeTrue(Files.exists(file), "BBCA source data not available");
        FilingMapper mapper;
        try (InputStream in = Files.newInputStream(file)) {
            mapper = new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }

        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(current.unclassified()).extracting(UnclassifiedLine::label).containsExactly("Other insurance expenses");
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("memo_line"));
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "BBRI", "xlsx", "FinancialStatement-" + filing + "-BBRI.xlsx");
        assumeTrue(Files.exists(file), "BBRI source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        return new FilingMapper(workbook);
    }
}
