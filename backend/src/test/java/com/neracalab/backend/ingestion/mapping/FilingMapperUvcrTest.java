package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * UVCR 9M 2026 (error 1153): the ending cash cell is filed 2,824,484,246, while operating -64,336,448,129 + investing
 * 70,780,538,456 + financing -5,875,227,400 = net change 568,862,927 and beginning 1,805,621,319 + net change =
 * 2,374,484,246, the balance-sheet cash. The ending cash is the balance sheet's.
 */
class FilingMapperUvcrTest {

    @Test
    void anEndingCashCellTheFilingContradictsIsTheBalanceSheetCash() throws Exception {
        FilingMapper mapper = mapper("UVCR", "2026-III");

        MappedStatement cash = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(cash.hasErrors()).as(cash.checks().toString()).isFalse();
        assertThat(cash.values().get("ending_cash")).isEqualByComparingTo("2374484246");
        assertThat(cash.values().get("cash_change")).isEqualByComparingTo("568862927");
        assertThat(cash.checks()).anyMatch(c -> c.rule().equals("cash_rollforward") && c.message().contains(
                "The filed ending cash 2824484246 is not beginning cash + net change + FX / other effects 2374484246"));
        assertThat(cash.checks()).anyMatch(c -> c.rule().equals("cash_vs_balance_sheet") && !c.isError()
                && c.message().equals("ending cash = balance-sheet cash"));

        // the 9M 2025 comparative rolls forward as filed: nothing corrected
        MappedStatement prior = mapper.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.hasErrors()).isFalse();
        assertThat(prior.values().get("ending_cash")).isEqualByComparingTo("967794984");
        assertThat(prior.checks()).noneMatch(c -> c.message().startsWith("The filed ending cash"));
    }

    @Test
    void anEndingCashThatRollsForwardIsKept() throws Exception {
        FilingMapper mapper = mapper("UVCR", "2026-II");

        MappedStatement cash = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(cash.hasErrors()).isFalse();
        assertThat(cash.checks()).noneMatch(c -> c.message().startsWith("The filed ending cash"));
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
