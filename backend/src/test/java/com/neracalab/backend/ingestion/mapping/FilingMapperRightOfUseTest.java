package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Right-of-use assets presented within property, plant and equipment (the first year of that presentation keeps the
 * former separate balance-sheet line in the prior column only): the PP&E roll-forward's additions to accumulated
 * depreciation are the whole depreciation. SGRO FY2025 'Right of use assets' 43,359 million at 2024-12-31 only, the PP&E
 * note's 'Building, right of use assets' 74,868 -> 54,107; depreciation was empty, so EBITDA and EV / EBITDA too.
 * A right-of-use line ending without either note (HELI FY2025) and a right-of-use note with amounts but no accumulated
 * depreciation (IATA FY2024) stay not disclosed.
 */
class FilingMapperRightOfUseTest {

    @ParameterizedTest
    @CsvSource({
            "SGRO, 2025-Tahunan, 215829000000",
            "KING, 2025-Tahunan, 6541123051",
            "OBAT, 2025-Tahunan, 3080662127",
            "UDNG, 2025-Tahunan, 4713069296",
            "JPFA, 2025-Tahunan, 1253236000000",
            "HELI, 2025-Tahunan, ",
            "IATA, 2024-Tahunan, ",
            "FISH, 2025-Tahunan, "})
    void rightOfUseAssetsWithinPpe(String ticker, String filing, String depreciation) throws Exception {
        FilingMapper mapper = mapper(ticker, filing);
        if (depreciation == null) {
            assertThat(mapper.depreciation(StatementColumn.CURRENT_PERIOD)).isNull();
        } else {
            assertThat(mapper.depreciation(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo(depreciation);
        }
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
