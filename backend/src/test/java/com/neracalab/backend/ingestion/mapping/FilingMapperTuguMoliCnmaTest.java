package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.ShareCapital.ShareAt;
import com.neracalab.backend.ingestion.xlsx.IdxTaxonomy;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Filings of the error log of 2026-10-14 (errors 346 - 355) and the valuations they left wrong or empty:
 * <ul>
 *   <li>TUGU (an insurer) files in the IDX Insurance Industry taxonomy (sheets 6220000, 6312000, 6410000, 6510000),
 *       which the reader did not know: not even its share capital was read, so TUGU had no share counts and no
 *       valuation. Its EPS is filed in rupiah, in thousands (FY2023) and in millions (FY2024).</li>
 *   <li>MOLI files its EPS in other units from one filing to the next: FY2025 0.277 for Rp 27.70 and 0.00475 for
 *       Rp 4.75. Par value 1 fitted the current column, gave 272,403,658,000 shares (100 times too many) and a share
 *       split 1:100 that never was: every MOLI market cap was 100 times too large (P/E 1,105 - 7,626).</li>
 *   <li>CNMA files its balance sheet by order of liquidity (1220000), which the rules do not map: depreciation was
 *       not taken from its PP&amp;E note, so it had no EBITDA.</li>
 * </ul>
 */
class FilingMapperTuguMoliCnmaTest {

    private static final BigDecimal TUGU_SHARES = new BigDecimal("3555575600");
    private static final BigDecimal MOLI_SHARES = new BigDecimal("2724036580");

    @Test
    void anInsurerIsReadInTheInsuranceIndustryTaxonomyAndDecidedByTheModel() throws Exception {
        FilingMapper mapper = mapper("TUGU", "2026-II");

        assertThat(mapper.taxonomy()).isEqualTo(IdxTaxonomy.INSURANCE);
        assertThat(mapper.financial()).isTrue();
        assertThat(mapper.insurance()).isTrue();
        assertThat(mapper.templateProblems()).singleElement().asString()
                .isEqualTo("The line items of the Insurance Industry taxonomy (6220000, 6322000, 6510000) are not mapped by the "
                        + "rule-based extraction; the model decides them from the text of the workbook");
        assertThat(mapper.decidedByModel()).isTrue();
        // the statement sheets are read under the General codes: the balance sheet by order of liquidity
        assertThat(mapper.balanceSheetSheet()).isEqualTo("1220000");
    }

    /** H1 2026: share capital Rp 177,778,780,000 at par 50; 480,285,633 thousand / 3,555,575,600 = 135.08 (filed 135). */
    @Test
    void theShareCapitalOfAnInsurerGivesItsShareCounts() throws Exception {
        ShareCapital shares = mapper("TUGU", "2026-II").shareCapital();

        assertThat(shares.parValue()).isEqualByComparingTo("50");
        assertThat(counts(shares)).containsExactlyInAnyOrderEntriesOf(Map.of(
                LocalDate.parse("2024-12-31"), TUGU_SHARES, LocalDate.parse("2025-06-30"), TUGU_SHARES,
                LocalDate.parse("2025-12-31"), TUGU_SHARES, LocalDate.parse("2026-06-30"), TUGU_SHARES));
    }

    /**
     * FY2024 files the EPS in millions with four decimals in a workbook in thousands: 0.0002 for 700,854,404 thousand /
     * 3,555,575,600 = Rp 197.11, 0.00037 for Rp 371.92. Only the share capital tells the unit: at 1,000,000 par 50 fits
     * within the filed rounding (150 .. 250), as filed or in thousands no par value does.
     */
    @Test
    void anEpsFiledInMillionsIsRecognisedByTheShareCapital() throws Exception {
        FilingMapper mapper = mapper("TUGU", "2024-Tahunan");

        assertThat(mapper.epsScale()).isEqualByComparingTo("1000000");
        assertThat(mapper.warnings()).anyMatch(w -> w.startsWith("The workbook's EPS is filed in units of 1000000: basic EPS 0.0002"));
        ShareCapital shares = mapper.shareCapital();
        assertThat(mapper.epsContradiction()).isNull();
        assertThat(shares.parValue()).isEqualByComparingTo("50");
        assertThat(counts(shares)).containsExactlyInAnyOrderEntriesOf(Map.of(
                LocalDate.parse("2022-12-31"), TUGU_SHARES, LocalDate.parse("2023-12-31"), TUGU_SHARES,
                LocalDate.parse("2024-12-31"), TUGU_SHARES));
    }

    /**
     * FY2023 files 0.372 (thousands) for Rp 371.92 at par 50, and its comparative 0.195 for the FY2022 EPS of before
     * the 2:1 split of 2023-05-24 (Rp 195 at par 100), which it does not restate. The two columns fit different par
     * values: no share counts and no EPS from this filing (the FY2024 filing's comparative and the split recorded by
     * V1.0.22 supply them).
     */
    @Test
    void aSplitTheComparativeDoesNotRestateGivesNoShareCounts() throws Exception {
        FilingMapper mapper = mapper("TUGU", "2023-Tahunan");

        assertThat(mapper.epsScale()).isEqualByComparingTo("1000");
        ShareCapital shares = mapper.shareCapital();
        assertThat(mapper.epsContradiction()).contains("2023 FY basic EPS 372 fits par value [50]",
                "2022 FY basic EPS 195 fits par value [100]");
        assertThat(shares.resolved()).isFalse();
        assertThat(shares.snapshots()).allMatch(s -> s.sharesOutstanding() == null);
    }

    /** FY2022, before the split: par 100, 1,777,787,800 shares (V1.0.22 records the split; they are stored doubled). */
    @Test
    void theFilingOfBeforeTheSplitHasThePreSplitParValue() throws Exception {
        ShareCapital shares = mapper("TUGU", "2022-Tahunan").shareCapital();

        assertThat(shares.parValue()).isEqualByComparingTo("100");
        assertThat(counts(shares).get(LocalDate.parse("2022-12-31"))).isEqualByComparingTo("1777787800");
    }

    /**
     * MOLI FY2025: 0.277 fits only par 1 (272,403,658,000 shares), the comparative 0.00475 fits no par value as filed and
     * only par 100 in thousands (Rp 4.75, the EPS of MOLI's own FY2024 filing). The columns contradict each other: no
     * share counts, no EPS - not par 1 and a split 1:100.
     */
    @Test
    void anEpsInAnotherUnitInEachColumnGivesNoParValue() throws Exception {
        FilingMapper mapper = mapper("MOLI", "2025-Tahunan");
        ShareCapital shares = mapper.shareCapital();

        assertThat(mapper.epsContradiction()).isEqualTo("The two columns' EPS contradict each other: 2025 FY basic EPS 0.277 "
                + "fits par value [1], 2024 FY basic EPS 0.00475 fits par value [100] (2024 FY: fits no par value as filed, only "
                + "multiplied by the rounding unit 1000); no share counts are derived from this filing");
        assertThat(shares.parValue()).isNull();
        assertThat(shares.snapshots()).allMatch(s -> s.sharesOutstanding() == null);
        for (StatementColumn column : mapper.columns()) {
            MappedStatement income = mapper.incomeStatement(column, Map.of(), shares).orElseThrow();
            assertThat(income.values().get("basic_eps")).isNull();
            assertThat(income.rejected()).contains("basic_eps", "basic_shares");
        }
    }

    /** MOLI H1 2026: 0.02321 and 0.00729 in thousands, Rp 23.21 and Rp 7.29 at par 100 (2,724,036,580 shares). */
    @Test
    void anEpsInThousandsIsRecognisedByTheShareCapital() throws Exception {
        FilingMapper mapper = mapper("MOLI", "2026-II");

        assertThat(mapper.epsScale()).isEqualByComparingTo("1000");
        ShareCapital shares = mapper.shareCapital();
        assertThat(shares.parValue()).isEqualByComparingTo("100");
        assertThat(counts(shares).values()).containsOnly(MOLI_SHARES);
        assertThat(mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), shares).orElseThrow().values().get("basic_eps"))
                .isEqualByComparingTo("23.21");
        assertThat(mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), shares).orElseThrow().values().get("basic_eps"))
                .isEqualByComparingTo("7.29");
    }

    /** MOLI FY2024 files its EPS in rupiah (4.75): as before, par 100 and no other unit. */
    @Test
    void anEpsFiledInRupiahKeepsItsUnit() throws Exception {
        FilingMapper mapper = mapper("MOLI", "2024-Tahunan");

        assertThat(mapper.epsScale()).isEqualByComparingTo("1");
        assertThat(mapper.shareCapital().parValue()).isEqualByComparingTo("100");
        assertThat(counts(mapper.shareCapital()).values()).containsOnly(MOLI_SHARES);
    }

    /**
     * CNMA FY2025 (balance sheet by order of liquidity): its PP&amp;E note carries the right-of-use assets as well, the
     * balance sheet has no 'Right of use assets' row of its own, and the right-of-use note is empty. Depreciation is the
     * PP&amp;E note's (647,035 + 144,916 million); before, the missing current / non-current balance sheet was taken for
     * right-of-use assets without a note, and depreciation was left empty.
     */
    @Test
    void depreciationOfABalanceSheetByOrderOfLiquidity() throws Exception {
        FilingMapper mapper = mapper("CNMA", "2025-Tahunan");

        assertThat(mapper.balanceByLiquidity()).isTrue();
        assertThat(mapper.decidedByModel()).isTrue();
        assertThat(mapper.depreciation(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("791951000000");
        assertThat(mapper.depreciation(StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("757450000000");
    }

    private static Map<LocalDate, BigDecimal> counts(ShareCapital shares) {
        Map<LocalDate, BigDecimal> counts = new java.util.LinkedHashMap<>();
        for (ShareAt s : shares.snapshots()) {
            if (s.sharesOutstanding() != null) {
                counts.put(s.date(), s.sharesOutstanding());
            }
        }
        return counts;
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
