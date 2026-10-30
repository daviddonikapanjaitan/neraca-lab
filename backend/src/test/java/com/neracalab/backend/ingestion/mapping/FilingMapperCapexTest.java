package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * A cash flow statement without a capital expenditure row: the full-text extraction stores 0, as the rule-based one
 * does, so the period has a free cash flow (FUJI and ADCP had no FCF yield).
 */
class FilingMapperCapexTest {

    @Test
    void aCashFlowWithoutCapitalExpenditureRowsIsTold() throws Exception {
        // FUJI (financing company): no investing section at all
        FilingMapper fuji = mapper("FUJI", "2026-III");
        assertThat(fuji.reportsCapitalExpenditure(StatementColumn.CURRENT_PERIOD)).isFalse();
        assertThat(fuji.reportsCapitalExpenditure(StatementColumn.PRIOR_PERIOD)).isFalse();
        // ADCP 9M 2025: land for development, a joint venture, restricted funds - no capital assets
        assertThat(mapper("ADCP", "2026-III").reportsCapitalExpenditure(StatementColumn.PRIOR_PERIOD)).isFalse();
        // MIKA FY2025 pays for intangible assets
        assertThat(mapper("MIKA", "2025-Tahunan").reportsCapitalExpenditure(StatementColumn.CURRENT_PERIOD)).isTrue();
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
