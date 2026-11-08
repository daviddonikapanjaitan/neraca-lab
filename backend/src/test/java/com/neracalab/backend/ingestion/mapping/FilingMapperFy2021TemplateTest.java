package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The FY2021 template names the finance cost line 'Finance costs' (later templates: 'Interest and finance costs'). As a
 * finance cost the profit or loss reconciles and the filing can take the fast path: BAUT FY2021 operating income
 * 1,096,421,746 ... profit before tax 5,645,981,484 with finance costs 1,467,215,810 (it failed by exactly that amount);
 * SIDO 862,000,000; CPIN 328,551 million.
 */
class FilingMapperFy2021TemplateTest {

    @ParameterizedTest
    @CsvSource({
            "BAUT, 1467215810, 5645981484",
            "SIDO, 862000000, 1613231000000",
            "CPIN, 328551000000, 4633546000000"})
    void financeCostsOfTheFy2021TemplateAreInterestExpense(String ticker, String financeCosts, String pretax) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-2021-Tahunan-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        FilingMapper mapper;
        try (InputStream in = Files.newInputStream(file)) {
            mapper = new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
        MappedStatement income = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), null).orElseThrow();
        assertThat(income.unclassified()).isEmpty();
        assertThat(income.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(income.values().get("interest_expense")).isEqualByComparingTo(financeCosts);
        assertThat(income.values().get("pretax_income")).isEqualByComparingTo(pretax);
    }
}
