package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Amortization = intangibles opening + purchases - closing, unless the goodwill fell by half or more: then a business
 * left the group or was written off and the roll holds more than amortization.
 */
class FilingMapperAmortizationTest {

    /**
     * PTRO H1 2026 (thousand USD): intangibles 134,250 -> 60,088, purchases 4,050, goodwill 21,395 -> 2,408. The roll
     * would give 78,212 while its cost of sales note states an amortization of 6,075 (EBITDA was stored 183,174 for an
     * EBIT of 21,592). CUAN H1 2026 alike (152,773 -> 72,543, goodwill 21,395 -> 2,426).
     */
    @Test
    void noAmortizationWhenTheGoodwillHalved() throws Exception {
        assertThat(mapper("PTRO", "2026-II").amortization(StatementColumn.CURRENT_PERIOD)).isNull();
        assertThat(mapper("CUAN", "2026-II").amortization(StatementColumn.CURRENT_PERIOD)).isNull();
    }

    /** ARKO H1 2026 has no goodwill: the roll as before. */
    @Test
    void theRollWithoutAGoodwillFall() throws Exception {
        assertThat(mapper("ARKO", "2026-II").amortization(StatementColumn.CURRENT_PERIOD)).isNotNull();
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
