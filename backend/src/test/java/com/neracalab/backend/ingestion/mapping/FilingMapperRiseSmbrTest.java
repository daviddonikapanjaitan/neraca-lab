package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Filings of the error log ({@code ingestion_error}) of 2026-10-17:
 * <ul>
 *   <li>RISE H1 2026 (error 580): the H1 2025 comparative shows an FX effect its ending cash leaves out.</li>
 *   <li>SMBR FY2025 (error 579): the FY2024 comparative counts two investing cash flows twice; still refused.</li>
 * </ul>
 */
class FilingMapperRiseSmbrTest {

    /**
     * H1 2025: beginning 260,777,639,668 + net change -36,001,003,868 = 224,776,635,800, the ending cash as filed; the
     * effect of exchange rate changes, 398,002, is not in it. Every section adds up to its total.
     */
    @Test
    void anFxEffectTheFiledRollForwardLeavesOutIsNotCounted() throws Exception {
        MappedStatement prior = mapper("RISE", "2026-II").cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();

        assertThat(prior.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(prior.values().get("ending_cash")).isEqualByComparingTo("224776635800");
        assertThat(prior.values().get("cash_change")).isEqualByComparingTo("-36001003868");
        assertThat(prior.values().get("investing_cash_flow")).isEqualByComparingTo("-105347667876");
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("cash_rollforward") && c.severity() == Check.Severity.WARNING
                && c.message().startsWith("FX / other effects 398002 are not part of the roll-forward as filed"));

        // FY2025 includes its FX effect (258,277,639,668 - 100,820,257,101 - 217,488 = 157,457,165,079): unchanged
        MappedStatement fy2025 = mapper("RISE", "2025-Tahunan").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(fy2025.checks()).anyMatch(c -> c.rule().equals("cash_rollforward") && c.severity() == Check.Severity.OK);
    }

    /**
     * FY2024 in the FY2025 filing (thousands): PP&E 37,289,552 already includes the other non-financial assets
     * 20,131,637 also shown on their own line (the FY2024 filing: 17,157,915 + 20,131,637), and 14,581,729 is placed
     * both in short-term investments and in restricted funds. The lines give -86,664,147 for a filed total of
     * -51,950,781; no line of the column explains it alone. Refused; FY2024 is stored from its own filing, which adds up.
     */
    @Test
    void aComparativeThatCountsInvestingFlowsTwiceIsStillRefused() throws Exception {
        MappedStatement prior = mapper("SMBR", "2025-Tahunan").cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.checks()).anyMatch(c -> c.isError() && c.message().contains("difference 34713366000"));

        MappedStatement own = mapper("SMBR", "2024-Tahunan").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(own.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(own.values().get("investing_cash_flow")).isEqualByComparingTo("-51950781000");
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
