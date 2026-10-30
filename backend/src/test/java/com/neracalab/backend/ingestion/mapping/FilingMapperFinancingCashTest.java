package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Errors 1243, 1244 and 1249 - 1254: a financing company's balance sheet row 'Cash and cash equivalents' holds the cash
 * alone, its current accounts and placements with banks are rows of their own, and its cash flow statement counts them
 * in cash. The cash and cash equivalents are the cash flow's when the bank rows make up the difference exactly.
 */
class FilingMapperFinancingCashTest {

    @Test
    void cashPlusCurrentAccountsWithBanksIsTheEndingCash() throws Exception {
        FilingMapper mapper = mapper("ADMF", "2025-Tahunan");

        FilingMapper.CashWithBanks current = mapper.cashWithBankBalances(StatementColumn.CURRENT_PERIOD);
        // 77,878 + 176,714 + 566,324 = 820,916 million
        assertThat(current.balanceSheetCash()).isEqualByComparingTo("77878000000");
        assertThat(current.cash()).isEqualByComparingTo("820916000000");
        assertThat(current.rows()).containsExactly("Current accounts with other banks third parties",
                "Current accounts with other banks related parties");
        assertThat(mapper.cashWithBankBalances(StatementColumn.PRIOR_PERIOD).cash()).isEqualByComparingTo("1959219000000");
    }

    @Test
    void placementsWithBanksCountToo() throws Exception {
        FilingMapper mapper = mapper("DEFI", "2023-Tahunan");

        // 8,801,000 + 4,487,369,146 + 8,500,000,000
        assertThat(mapper.cashWithBankBalances(StatementColumn.CURRENT_PERIOD).cash()).isEqualByComparingTo("12996170146");
        assertThat(mapper.cashWithBankBalances(StatementColumn.CURRENT_PERIOD).rows()).containsExactly(
                "Current accounts with other banks third parties",
                "Placements with Bank Indonesia and other banks third parties");
    }

    @Test
    void anInterimFilingHasNoBalanceSheetAtItsPriorPeriod() throws Exception {
        FilingMapper mapper = mapper("ADMF", "2026-II");

        assertThat(mapper.cashWithBankBalances(StatementColumn.CURRENT_PERIOD).cash()).isEqualByComparingTo("1391600000000");
        assertThat(mapper.cashWithBankBalances(StatementColumn.PRIOR_PERIOD)).isNull();
        assertThat(mapper.cashWithBankBalances(StatementColumn.PRIOR_YEAR_END)).isNull();
    }

    /** Nothing is changed where the bank rows do not make up the difference exactly. */
    @Test
    void otherwiseNothingIsChanged() throws Exception {
        // DEFI FY2022: its cash flow ends at 15,857,547,033, 501,250,000 below cash + bank rows (restated in FY2023)
        assertThat(mapper("DEFI", "2022-Tahunan").cashWithBankBalances(StatementColumn.CURRENT_PERIOD)).isNull();
        // SMMA FY2025: cash 9,180,682 + placements 8,655,785 against an ending cash of 22,053,772 million
        assertThat(mapper("SMMA", "2025-Tahunan").cashWithBankBalances(StatementColumn.CURRENT_PERIOD)).isNull();
        // BFIN: its 'Cash and cash equivalents' row is the ending cash already
        assertThat(mapper("BFIN", "2025-Tahunan").cashWithBankBalances(StatementColumn.CURRENT_PERIOD)).isNull();
        // not a financing company
        assertThat(mapper("HRTA", "2025-Tahunan").cashWithBankBalances(StatementColumn.CURRENT_PERIOD)).isNull();
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
