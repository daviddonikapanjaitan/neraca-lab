package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.ingestion.xlsx.IdxTaxonomy;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Securities companies (brokers: PADI, PANS, RELI, TRIM, YULE) file in the IDX "Securities" taxonomy, sheets
 * 5220000 (balance sheet by order of liquidity), 5311000 / 5321000 (profit or loss), 5410000 (changes in equity),
 * 5510000 (cash flow). Until 2026-11-05 the reader did not know it (errors 1707 - 1816): no sheet was read, the
 * full-text extraction stored the statements, but not even the share capital was read - the five companies had no
 * share count and no valuation.
 */
class FilingMapperSecuritiesTest {

    @Test
    void aBrokerIsReadInTheSecuritiesTaxonomyAndDecidedByTheModel() throws Exception {
        FilingMapper mapper = mapper("PADI", "2025-Tahunan");

        assertThat(mapper.taxonomy()).isEqualTo(IdxTaxonomy.SECURITIES);
        assertThat(mapper.financial()).isFalse();
        assertThat(mapper.insurance()).isFalse();
        assertThat(mapper.balanceByLiquidity()).isTrue();
        assertThat(mapper.templateProblems()).containsExactly(
                "Balance sheet uses the order-of-liquidity template (5220000), which is not supported for the "
                        + "Securities taxonomy",
                "The line items of the Securities taxonomy (5220000, 5311000, 5510000) are not mapped by the "
                        + "rule-based extraction; the model decides them from the text of the workbook");
        assertThat(mapper.decidedByModel()).isTrue();
    }

    /**
     * FY2025: 'Common stocks' Rp 282,681,163,100 (5410000) at par Rp 25 = 11,307,246,524 shares; profit attributable
     * to the parent 1,812,542,233 / 11,307,246,524 = Rp 0.16, the filed basic EPS 0.16.
     */
    @Test
    void theShareCapitalOfABrokerGivesItsShareCounts() throws Exception {
        ShareCapital shares = mapper("PADI", "2025-Tahunan").shareCapital();

        assertThat(shares.parValue()).isEqualByComparingTo("25");
        BigDecimal count = new BigDecimal("11307246524");
        assertThat(counts(shares)).containsExactlyInAnyOrderEntriesOf(Map.of(
                LocalDate.parse("2023-12-31"), count, LocalDate.parse("2024-12-31"), count,
                LocalDate.parse("2025-12-31"), count));
    }

    /**
     * TRIM FY2025, a workbook in thousands: 'Common stocks' 355,465,000 thousand at par Rp 50 = 7,109,300,000 shares;
     * 593,457,069 thousand / 7,109,300,000 = Rp 83.476, the filed basic EPS 83.47616.
     */
    @Test
    void aWorkbookInThousandsGivesTheSameCount() throws Exception {
        ShareCapital shares = mapper("TRIM", "2025-Tahunan").shareCapital();

        assertThat(shares.parValue()).isEqualByComparingTo("50");
        assertThat(counts(shares).get(LocalDate.parse("2025-12-31"))).isEqualByComparingTo("7109300000");
    }

    private static Map<LocalDate, BigDecimal> counts(ShareCapital shares) {
        return shares.snapshots().stream().collect(Collectors.toMap(ShareAt::date, ShareAt::sharesOutstanding));
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
