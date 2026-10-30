package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * EBITDA where amortization is not derivable (intangibles grown by more than the cash paid for them, or sold): EBIT +
 * depreciation when the intangible assets are under 1% of it, else nothing - the latest valuations of BNBR, DSNG, HATM,
 * KETR and WINS had no EV / EBITDA, their FY2025 EBITDA being empty.
 */
class FilingMapperImmaterialIntangiblesTest {

    @Test
    void ebitdaIsEbitPlusDepreciationWhenTheIntangiblesCannotMatter() throws Exception {
        MappedStatement income = income("KETR", "2025-Tahunan");

        // intangibles 241,481,810 -> 283,072,592 with no purchase: amortization not derivable
        assertThat(income.values().get("amortization")).isNull();
        assertThat(income.values().get("ebit")).isEqualByComparingTo("226164003189");
        assertThat(income.values().get("depreciation")).isEqualByComparingTo("19698673499");
        assertThat(income.values().get("ebitda")).isEqualByComparingTo("245862676688");
        assertThat(income.derivations().get("ebitda")).contains("intangible assets are under 1% of it");
    }

    @Test
    void ebitdaStaysEmptyWhenTheIntangiblesMatter() throws Exception {
        // GGRM FY2025: intangibles of 14,902,111 million beside EBIT + depreciation of some 5.6 trillion
        MappedStatement income = income("GGRM", "2025-Tahunan");

        assertThat(income.values().get("amortization")).isNull();
        assertThat(income.values().get("depreciation")).isNotNull();
        assertThat(income.values().get("ebitda")).isNull();
    }

    private static MappedStatement income(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            FilingMapper mapper = new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
            return mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), null).orElseThrow();
        }
    }
}
