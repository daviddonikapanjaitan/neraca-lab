package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * FLMC's annual workbooks file the profit itself in the basic EPS cell (FY2025: profit attributable to the parent
 * -22,991,911,126, basic EPS -22,991,911,126): profit / EPS gave 1 share outstanding at every year end, and with it
 * a market cap of one share's price.
 */
class FilingMapperEpsCellTest {

    @Test
    void anEpsCellHoldingTheProfitGivesNoShareCount() throws Exception {
        FilingMapper flmc = mapper("FLMC", "2025-Tahunan");
        ShareCapital shares = flmc.shareCapital();
        BigDecimal outstanding = flmc.balanceSheet(StatementColumn.CURRENT_PERIOD, shares).orElseThrow()
                .values().get("shares_outstanding");
        assertThat(outstanding == null || outstanding.compareTo(BigDecimal.ONE) != 0)
                .as("shares outstanding %s", outstanding).isTrue();
    }

    @Test
    void aSmallRealCountBeforeAnIpoIsKept() throws Exception {
        // CBPE FY2021, before its IPO: 30,160,409,174 / 861,726 = 35,000 shares
        FilingMapper cbpe = mapper("CBPE", "2022-Tahunan");
        assertThat(cbpe.balanceSheet(StatementColumn.PRIOR_PERIOD, cbpe.shareCapital()).orElseThrow()
                .values().get("shares_outstanding")).isEqualByComparingTo("35000");
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
