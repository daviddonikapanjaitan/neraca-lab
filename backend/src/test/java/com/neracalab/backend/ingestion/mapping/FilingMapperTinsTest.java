package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The two TINS uploads that failed.
 * <ul>
 *   <li>FY2025 filing, comparative FY2024: "Other expenses" 18,883 repeats the net of "Other income" 2,250 and
 *       "Other gains (losses)" -21,133, so profit before tax 1,657,269 only reconciles with the residual lines
 *       counted once. The upload ended INCOMPLETE ("income_statement failed checks ... difference
 *       18883000000").</li>
 *   <li>FY2024 filing: IDX published the workbook with an empty current-year column, only the comparative 2023
 *       is filled ("No balance sheet data"). The mapper cannot store it; it reports why (the full-text
 *       extraction then takes the comparative and says that 2024 is not in the file).</li>
 * </ul>
 */
class FilingMapperTinsTest {

    @Test
    void theComparativeOfFy2025CountsItsResidualLinesOnce() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow();

        assertThat(prior.period().key()).isEqualTo("2024 FY");
        assertThat(prior.checks()).noneMatch(Check::isError);
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().contains("'Other expenses' and 'Other income + Other gains (losses)' report the same amount"));
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("1657269000000");
        assertThat(prior.values().get("revenue")).isEqualByComparingTo("10856422000000");
        assertThat(prior.values().get("gross_profit")).isEqualByComparingTo("2749782000000");
        // selling 104,163 + G&A 835,533; the repeated "Other expenses" 18,883 is not an operating expense on top
        assertThat(prior.values().get("operating_expenses")).isEqualByComparingTo("939696000000");
        assertThat(prior.values().get("net_income")).isEqualByComparingTo("1249398000000");
        assertThat(prior.values().get("net_income_to_parent")).isEqualByComparingTo("1249347000000");
        assertThat(prior.values().get("basic_eps")).isEqualByComparingTo("168");
    }

    @Test
    void theCurrentYearOfFy2025StillCountsEveryLine() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");

        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        // 2025: "Other expenses" 32,341 and "Other gains (losses)" 88,850 are two amounts; both count
        assertThat(current.checks()).noneMatch(Check::isError);
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("double_reported"));
        assertThat(current.values().get("pretax_income")).isEqualByComparingTo("1753848000000");
        assertThat(current.values().get("operating_expenses")).isEqualByComparingTo("972166000000");
    }

    @Test
    void everyStatementOfFy2025IsStorable() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        assertThat(mapper.templateProblems()).isEmpty();
        for (StatementColumn column : mapper.columns()) {
            assertThat(mapper.incomeStatement(column, Map.of(), shares).orElseThrow().hasErrors()).as(column + " income").isFalse();
            assertThat(mapper.balanceSheet(column, shares).orElseThrow().hasErrors()).as(column + " balance").isFalse();
            assertThat(mapper.cashFlow(column).orElseThrow().hasErrors()).as(column + " cash flow").isFalse();
        }
    }

    @Test
    void fy2024AsPublishedHasNoCurrentYearFigures() throws Exception {
        FilingMapper mapper = mapper("2024-Tahunan");

        assertThat(mapper.info().ticker()).isEqualTo("TINS");
        assertThat(mapper.info().current().key()).isEqualTo("2024 FY");
        assertThat(mapper.templateProblems()).contains("No balance sheet data (1210000)",
                "No profit or loss data (1311000 / 1321000)");
    }

    /**
     * FY2023: a loss of 449,690 million attributable to the parent, EPS filed as 0.00006 - in millions like the
     * comparative's 0.00014 (Rp 140, FY2022) and without the sign of the loss. Stored as -60.
     */
    @Test
    void theLossPerShareOfFy2023IsScaledAndGetsItsSign() throws Exception {
        FilingMapper mapper = mapper("2023-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow();
        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow();

        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(mapper.warnings()).anyMatch(w -> w.contains("EPS is filed in its rounding unit"));
        assertThat(current.values().get("net_income_to_parent")).isEqualByComparingTo("-449690000000");
        assertThat(current.values().get("basic_eps")).isEqualByComparingTo("-60");
        assertThat(current.values().get("diluted_eps")).isEqualByComparingTo("-60");
        assertThat(current.checks()).noneMatch(Check::isError);
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("eps_sign"));
        assertThat(prior.values().get("basic_eps")).isEqualByComparingTo("140");
        assertThat(prior.checks()).noneMatch(c -> c.rule().equals("eps_sign"));
        // the amounts stay in millions
        assertThat(current.values().get("revenue")).isEqualByComparingTo("8391907000000");
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "TINS", "xlsx", "FinancialStatement-" + filing + "-TINS.xlsx");
        assumeTrue(Files.exists(file), "TINS source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        return new FilingMapper(workbook);
    }
}
