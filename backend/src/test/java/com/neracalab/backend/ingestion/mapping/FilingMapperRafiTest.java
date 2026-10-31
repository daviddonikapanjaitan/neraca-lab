package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * RAFI (profit or loss by nature, no Yahoo Finance count) has a par value of Rp 15: common stocks 46,921,350,000 =
 * 3,128,090,000 shares after its IPO of 2022-07-28 (the FY2023 note), FY2023 EPS 127,469,201,017 / 3,128,090,000 =
 * 40.75. Rp 15 was not a par value the mapper tried, so RAFI had no share counts and no valuation at all.
 */
class FilingMapperRafiTest {

    @Test
    void sharesFromAParValueOfFifteenRupiah() throws Exception {
        assertThat(shares("2023-Tahunan")).containsEntry(LocalDate.parse("2023-12-31"), "3128090000")
                .containsEntry(LocalDate.parse("2022-12-31"), "3128090000")
                .containsEntry(LocalDate.parse("2021-12-31"), "2180000000");
        // 46,922,107,125 at 2024-12-31; FY2024 EPS -6,193,433,773 / 3,128,140,475 = -1.98
        assertThat(shares("2024-Tahunan")).containsEntry(LocalDate.parse("2024-12-31"), "3128140475");
    }

    private static Map<LocalDate, String> shares(String filing) throws Exception {
        FilingMapper mapper = mapper("RAFI", filing);
        ShareCapital capital = mapper.withWebShareCounts(mapper.shareCapital(), List.of(), List.of(), "Yahoo Finance");
        assertThat(capital.resolved()).as(capital.checks().toString()).isTrue();
        assertThat(capital.parValue()).isEqualByComparingTo("15");
        return capital.snapshots().stream().filter(s -> s.sharesOutstanding() != null).collect(Collectors.toMap(
                ShareCapital.ShareAt::date, s -> s.sharesOutstanding().stripTrailingZeros().toPlainString()));
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
