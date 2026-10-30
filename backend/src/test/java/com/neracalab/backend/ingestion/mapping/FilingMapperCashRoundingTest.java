package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Cash flows the standard extraction refused although the filing is right (errors 1355, 1371, 1408, 1410; the
 * full-text extraction stored them).
 */
class FilingMapperCashRoundingTest {

    /**
     * Full-amount filings whose cash roll-forward is one rupiah apart, every line rounded on its own (ALMI FY2024:
     * 507,776 - 480,438 = 27,338, ending cash filed 27,337; ARTI FY2023 995,261,912 / 995,261,913; ASHA FY2025
     * 230,491,386 / 230,491,387): a warning, the filed ending cash is stored.
     */
    @Test
    void aRollForwardOneRupiahApartIsTheFilingsRounding() throws Exception {
        for (String[] f : new String[][] {{"ALMI", "2024-Tahunan", "27337"}, {"ARTI", "2023-Tahunan", "995261913"},
                {"ASHA", "2025-Tahunan", "230491387"}}) {
            MappedStatement cash = mapper(f[0], f[1]).cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
            assertThat(cash.hasErrors()).as(f[0] + " " + cash.checks()).isFalse();
            assertThat(cash.values().get("ending_cash")).isEqualByComparingTo(f[2]);
            assertThat(cash.checks()).anyMatch(c -> !c.isError() && c.message().contains("one unit apart"));
        }
    }

    /**
     * AMMS H1 2026: one investing line, receipts from stockholders, filed as 0, no investing total, no financing
     * section; operating -44,107,270 is the net change (6,669,173,684 - 44,107,270 = 6,625,066,414, the ending cash).
     * Investing and financing count as 0.
     */
    @Test
    void aSectionWhoseOnlyLineIsZeroCountsAsZero() throws Exception {
        MappedStatement cash = mapper("AMMS", "2026-II").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(cash.hasErrors()).as(cash.checks().toString()).isFalse();
        assertThat(cash.values().get("ending_cash")).isEqualByComparingTo("6625066414");
        assertThat(cash.checks()).anyMatch(c -> c.message().startsWith("No investing cash flows are reported"));
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
