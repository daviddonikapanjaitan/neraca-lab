package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * INDY reports in USD: its share capital (USD 56,892,154) fits no rupiah par value, so share counts come
 * from an exact EPS denominator. Only the FY2023 filing has one: 119,683,800 / 0.0230042062839776 =
 * 5,202,692,000 shares outstanding (5,210,192,000 listed less 7,500,000 treasury shares, 0.144%).
 */
class FilingMapperIndySharesTest {

    private static final BigDecimal SHARES = new BigDecimal("5202692000");

    @Test
    void fy2023DerivesSharesOutstandingFromTheExactEpsDenominator() throws Exception {
        ShareCapital shares = mapper("2023-Tahunan").shareCapital();

        assertThat(shares.resolved()).isTrue();
        assertThat(shares.parValue()).isNull();
        assertThat(shares.basis()).contains("2023 FY").contains("119683800").contains("0.0230042062839776")
                .contains("5202692000");
        // share capital and treasury stock are the same at every date, so is the share count
        assertThat(shares.snapshots()).extracting(ShareAt::date).containsExactlyInAnyOrder(
                LocalDate.of(2021, 12, 31), LocalDate.of(2022, 12, 31), LocalDate.of(2023, 12, 31));
        assertThat(shares.snapshots()).allSatisfy(s -> {
            assertThat(s.sharesOutstanding()).isEqualByComparingTo(SHARES);
            assertThat(s.treasuryShares()).isNull();               // the treasury share count is not in the filing
        });
        assertThat(shares.weightedCurrent()).isEqualByComparingTo(SHARES);
        assertThat(shares.weightedPrior()).isNull();               // FY2022's EPS uses another denominator
        assertThat(shares.checks()).noneMatch(Check::isError);
    }

    /** EPS too coarse (0.0019 allows 5.17 .. 5.45 billion shares) or not exact (FY2022: 5,210,191,995): no counts. */
    @ParameterizedTest
    @ValueSource(strings = {"2022-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II"})
    void otherFilingsDoNotGuess(String filing) throws Exception {
        ShareCapital shares = mapper(filing).shareCapital();

        assertThat(shares.resolved()).isFalse();
        assertThat(shares.snapshots()).allSatisfy(s -> assertThat(s.sharesOutstanding()).isNull());
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("par_value") && c.message().contains("not precise enough"));
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "INDY", "xlsx", "FinancialStatement-" + filing + "-INDY.xlsx");
        assumeTrue(Files.exists(file), "INDY source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
