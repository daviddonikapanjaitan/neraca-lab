package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Filings of the error log ({@code ingestion_error}) of 2026-10-22:
 * <ul>
 *   <li>KETR FY2024 in its FY2025 filing (error 802): 'Finance income' filed again within 'Other income';</li>
 *   <li>LPPF FY2023 (error 809): rows left off the face of the balance sheet - inventories, property, plant and
 *       equipment, right-of-use assets, bank loans - which its totals include.</li>
 * </ul>
 */
class FilingMapperKetrLppfTest {

    @Test
    void ketrFinanceIncomeFiledAgainWithinOtherIncomeIsCountedOnce() throws Exception {
        FilingMapper mapper = mapper("KETR", "2025-Tahunan");

        MappedStatement prior = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("double_reported") && c.message().equals("'Finance income' "
                + "3820523591 is also counted in 'Other income' in this column; 'Other income' counted without it, 11241254994"));
        // the FY2024 filing's own figures: other income 11,241,254,994 beside the finance income
        assertThat(prior.values().get("interest_income")).isEqualByComparingTo("3820523591");
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("181136399270");
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("101667066908");

        // the FY2024 filing itself reconciles without it
        FilingMapper fy2024 = mapper("KETR", "2024-Tahunan");
        MappedStatement own = fy2024.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2024.shareCapital()).orElseThrow();
        assertThat(own.hasErrors()).isFalse();
        assertThat(own.checks()).noneMatch(c -> c.rule().equals("double_reported"));
        assertThat(own.values().get("operating_income")).isEqualByComparingTo("181136399270");
    }

    @Test
    void lppfRowsMissingOnTheFaceOfTheBalanceSheetAreNotStoredAsZero() throws Exception {
        FilingMapper mapper = mapper("LPPF", "2023-Tahunan");

        MappedStatement balance = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital()).orElseThrow();

        assertThat(balance.hasErrors()).isFalse();
        assertThat(balance.values().get("inventory")).isNull();
        assertThat(balance.values().get("short_term_debt")).isNull();
        assertThat(balance.values().get("goodwill")).isNull();
        assertThat(balance.values().get("intangible_assets")).isNull();
        // fields whose rows are filed stay as filed
        assertThat(balance.values().get("cash_and_equivalents")).isEqualByComparingTo("507631000000");
        assertThat(balance.values().get("accounts_receivable")).isEqualByComparingTo("59832000000");
        assertThat(balance.values().get("lease_liabilities")).isEqualByComparingTo("3050500000000");
        assertThat(balance.values().get("current_assets")).isEqualByComparingTo("1448030000000");
        assertThat(balance.checks()).anyMatch(c -> c.rule().equals("missing_rows") && c.message().equals("'Total current assets' "
                + "1448030000000 is more than the sum of its rows 655249000000 (792781000000 not on the face of the balance "
                + "sheet); inventory not stored as 0"));
        assertThat(balance.checks()).anyMatch(c -> c.rule().equals("missing_rows") && c.message().startsWith("'Total current "
                + "liabilities' 2952977000000 is more than the sum of its rows 2402974000000 (550003000000 not on the face")
                && c.message().endsWith("short_term_debt not stored as 0"));
        assertThat(mapper.fieldsMissingOnTheFace(StatementColumn.CURRENT_PERIOD))
                .containsExactly("goodwill", "intangible_assets", "inventory", "short_term_debt");

        // the FY2024 filing's comparative has the rows: 792,781 inventories and 550,003 bank loans (million)
        FilingMapper fy2024 = mapper("LPPF", "2024-Tahunan");
        MappedStatement comparative = fy2024.balanceSheet(StatementColumn.PRIOR_PERIOD, fy2024.shareCapital()).orElseThrow();
        assertThat(comparative.values().get("inventory")).isEqualByComparingTo("792781000000");
        assertThat(comparative.values().get("short_term_debt")).isEqualByComparingTo("550003000000");
        assertThat(comparative.checks()).noneMatch(c -> c.rule().equals("missing_rows"));
        assertThat(fy2024.fieldsMissingOnTheFace(StatementColumn.CURRENT_PERIOD)).isEmpty();
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
