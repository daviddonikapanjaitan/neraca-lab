package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * CASA (a bank holding with a Sharia insurer) files 'Total accumulated tabarru's funds' between its temporary syirkah
 * funds and its equity: FY2025 24,667,114 + 2,207,986 + 24,781 + 10,401,954 = 37,301,835 million. The balance check
 * left it out and every CASA filing went to the full-text extraction (errors 1558 - 1572).
 */
class FilingMapperTabarruTest {

    @Test
    void tabarruFundsBalanceTheBalanceSheetWithTheSyirkahFunds() throws Exception {
        FilingMapper casa = mapper("CASA", "2025-Tahunan");
        MappedStatement current = casa.balanceSheet(StatementColumn.CURRENT_PERIOD, casa.shareCapital()).orElseThrow();
        assertThat(current.hasErrors()).as(current.checks().toString()).isFalse();
        assertThat(current.values().get("temporary_syirkah_funds")).isEqualByComparingTo("2232767000000");
        MappedStatement prior = casa.balanceSheet(StatementColumn.PRIOR_PERIOD, casa.shareCapital()).orElseThrow();
        assertThat(prior.hasErrors()).as(prior.checks().toString()).isFalse();
        // 2,006,140 + 25,341
        assertThat(prior.values().get("temporary_syirkah_funds")).isEqualByComparingTo("2031481000000");
    }

    @Test
    void aSyirkahTotalFiledOnTheTabarruRowIsNotCountedTwice() throws Exception {
        // ARTO FY2022: mudharabah time deposits 269,558 + 256,501 = 526,059 million, entered on the tabarru row
        FilingMapper arto = mapper("ARTO", "2022-Tahunan");
        MappedStatement current = arto.balanceSheet(StatementColumn.CURRENT_PERIOD, arto.shareCapital()).orElseThrow();
        assertThat(current.hasErrors()).as(current.checks().toString()).isFalse();
        assertThat(current.values().get("temporary_syirkah_funds")).isEqualByComparingTo("526059000000");
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
