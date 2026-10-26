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
 * Errors 1019 - 1021: a comparative column with one line its own profit before tax leaves out.
 * <ul>
 *   <li>PTRO FY2025 filing, comparative FY2024: "Other gains (losses)" 3,804 is the 4,361 of the FY2024 filing net of
 *       the associates' -557, which the column lists again; profit before tax 7,868 is the lines without it.</li>
 *   <li>PTRO H1 2026 filing, comparative H1 2025: "Selling expenses" 3,514; profit before tax 7,846 without it.</li>
 *   <li>BNBR H1 2026 filing, comparative H1 2025: "Other expenses" 2,784; profit before tax 72,088 without it.</li>
 * </ul>
 */
class FilingMapperPtroBnbrTest {

    @Test
    void theFy2024ComparativeOfPtroLeavesTheAssociatesOut() throws Exception {
        MappedStatement prior = prior("PTRO", "2025-Tahunan");

        assertThat(prior.period().key()).isEqualTo("2024 FY");
        assertThat(prior.checks()).noneMatch(Check::isError);
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("left_out_line")
                && c.message().contains("'Share of profit (loss) of associates accounted for using equity method' -557000"));
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("7868000");
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("38662000");
    }

    @Test
    void theH12025ComparativeOfPtroLeavesTheSellingExpensesOut() throws Exception {
        MappedStatement prior = prior("PTRO", "2026-II");

        assertThat(prior.period().key()).isEqualTo("2025 H1");
        assertThat(prior.checks()).noneMatch(Check::isError);
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("left_out_line")
                && c.message().contains("'Selling expenses' 3514000"));
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("7846000");
        assertThat(prior.values().get("sga_expense")).isEqualByComparingTo("15410000");
    }

    @Test
    void theH12025ComparativeOfBnbrLeavesTheOtherExpensesOut() throws Exception {
        MappedStatement prior = prior("BNBR", "2026-II");

        assertThat(prior.period().key()).isEqualTo("2025 H1");
        assertThat(prior.checks()).noneMatch(Check::isError);
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("left_out_line")
                && c.message().contains("'Other expenses' 2784000000"));
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("72088000000");
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("81126000000");
    }

    @Test
    void everyStatementOfTheThreeFilingsIsStorable() throws Exception {
        for (String[] filing : new String[][] {{"PTRO", "2025-Tahunan"}, {"PTRO", "2026-II"}, {"BNBR", "2026-II"}}) {
            FilingMapper mapper = mapper(filing[0], filing[1]);
            ShareCapital shares = mapper.shareCapital();
            for (StatementColumn column : mapper.columns()) {
                String as = filing[0] + " " + filing[1] + " " + column;
                mapper.incomeStatement(column, Map.of(), shares)
                        .ifPresent(s -> assertThat(s.hasErrors()).as(as + " income").isFalse());
                mapper.balanceSheet(column, shares).ifPresent(s -> assertThat(s.hasErrors()).as(as + " balance").isFalse());
                mapper.cashFlow(column).ifPresent(s -> assertThat(s.hasErrors()).as(as + " cash flow").isFalse());
            }
        }
    }

    @Test
    void theOwnFilingsColumnIsNeverSecondGuessed() throws Exception {
        FilingMapper mapper = mapper("PTRO", "2024-Tahunan");

        MappedStatement current = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow();

        assertThat(current.checks()).noneMatch(Check::isError);
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("left_out_line"));
    }

    private static MappedStatement prior(String ticker, String filing) throws Exception {
        FilingMapper mapper = mapper(ticker, filing);
        return mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();
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
