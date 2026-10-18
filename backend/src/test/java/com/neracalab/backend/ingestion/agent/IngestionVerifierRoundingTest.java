package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.StoredPeriod;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * UNTR FY2022 (IDR millions) files its ending cash with more digits than its unit: 38281513.468848 beside a
 * balance-sheet cash of 38281513. Less than one unit apart: a note, not a problem.
 */
class IngestionVerifierRoundingTest {

    @Test
    void endingCashWithinTheRoundingUnitIsANote() throws Exception {
        IngestionSession session = session();
        BigDecimal cash = new BigDecimal("38281513000000");
        BigDecimal ending = new BigDecimal("38281513468848");

        assertThat(IngestionVerifier.roundingExplanation(session, stored(cash, ending)))
                .contains("differ by 468848").contains("less than the filing's rounding unit 1000000");
        assertThat(IngestionVerifier.roundingExplanation(session, stored(cash, cash.add(new BigDecimal("1000000"))))).isNull();
    }

    private static StoredPeriod stored(BigDecimal balanceSheetCash, BigDecimal endingCash) {
        return new StoredPeriod("FY", true, true, true, 0, BigDecimal.ZERO, null, null, null,
                balanceSheetCash, endingCash, null);
    }

    private static IngestionSession session() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "UNTR", "xlsx", "FinancialStatement-2022-Tahunan-UNTR.xlsx");
        assumeTrue(Files.exists(file), "UNTR source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
