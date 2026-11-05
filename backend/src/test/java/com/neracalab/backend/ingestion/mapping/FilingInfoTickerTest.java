package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * TIRA's FY2025 workbook files 7362110449 as its 'Entity code' (its other filings: TIRA; the same entity identification
 * number AA167): the upload created a company '7362110449' and stored TIRA's FY2025 there, leaving TIRA's FY2025
 * without an income statement. A code without a letter is no ticker: the IDX file name gives it.
 */
class FilingInfoTickerTest {

    @Test
    void aNumericEntityCodeTakesTheTickerOfTheFileName() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "TIRA", "xlsx", "FinancialStatement-2025-Tahunan-TIRA.xlsx");
        assumeTrue(Files.exists(file), "TIRA source data not available: " + file);
        FilingInfo info;
        try (InputStream in = Files.newInputStream(file)) {
            info = FilingInfo.from(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }

        assertThat(info.ticker()).isEqualTo("TIRA");
        assertThat(info.warnings()).anyMatch(w -> w.contains("is not a ticker: TIRA taken from the file name"));
    }

    @Test
    void anotherFileNameIsRefusedAsBefore() {
        List<String> warnings = new ArrayList<>();
        assertThat(FilingInfo.tickerOfFileName("FinancialStatement-2026-II-hrta.xlsx", "123", warnings)).isEqualTo("HRTA");
        assertThatThrownBy(() -> FilingInfo.tickerOfFileName("upload.xlsx", "7362110449", warnings))
                .isInstanceOf(IdxWorkbookException.class).hasMessageContaining("is not a ticker");
    }
}
