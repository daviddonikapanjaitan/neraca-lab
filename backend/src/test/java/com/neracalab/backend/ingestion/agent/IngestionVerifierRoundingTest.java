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
 * balance-sheet cash of 38281513; EXCL FY2022 its revenue breakdown. Less than one unit apart: a note, not a problem.
 */
class IngestionVerifierRoundingTest {

    @Test
    void endingCashWithinTheRoundingUnitIsANote() throws Exception {
        IngestionSession session = session();
        BigDecimal cash = new BigDecimal("38281513000000");
        BigDecimal ending = new BigDecimal("38281513468848");

        assertThat(IngestionVerifier.roundingExplanation(session, stored(cash, ending)))
                .contains("differ by 468848").contains("at most the filing's rounding unit 1000000");
        // one unit apart is each cell rounded on its own, as the mapper's checks accept (changed 2026-10-30: before, a
        // whole unit was a problem; BATA H1 2026 and BBLD ended INCOMPLETE over it); more than a unit stays a problem
        assertThat(IngestionVerifier.roundingExplanation(session, stored(cash, cash.add(new BigDecimal("1000000")))))
                .contains("differ by 1000000");
        assertThat(IngestionVerifier.roundingExplanation(session, stored(cash, cash.add(new BigDecimal("1000001"))))).isNull();
    }

    /**
     * BATA H1 2026 (thousands, error 1414): balance-sheet cash 26,809,538 and the cash flow's ending cash 26,809,539 -
     * one unit, a note; BBLD H1 2026 (millions, error 1416): 580,173 and 580,174. BBLD FY2022's 69 million (514,065
     * beside 514,134, error 1419) stays a problem.
     */
    @Test
    void endingCashOneUnitApartIsANote() throws Exception {
        assertThat(IngestionVerifier.roundingExplanation(session("BATA", "2026-II"),
                stored(new BigDecimal("26809538000"), new BigDecimal("26809539000"))))
                .contains("differ by 1000").contains("rounding unit 1000");
        assertThat(IngestionVerifier.roundingExplanation(session("BBLD", "2026-II"),
                stored(new BigDecimal("580173000000"), new BigDecimal("580174000000")))).contains("differ by 1000000");
        assertThat(IngestionVerifier.roundingExplanation(session("BBLD", "2022-Tahunan"),
                stored(new BigDecimal("514065000000"), new BigDecimal("514134000000")))).isNull();
    }

    /**
     * MGLV FY2024 (full rupiah, error 1067): the cash flow rolls forward to 7,783,983,680, the balance sheet shows
     * 7,783,983,677 (its FY2025 filing repeats both as comparatives). Three rupiah of 7.8 billion, under a billionth of
     * the cash: a note. A larger gap stays a problem: INDO FY2022's 20,000,000 of 305,326,998,864 (error 959), MGLV
     * FY2025's 707,000 of 3,156,869,509, or 8 rupiah of 7.8 billion.
     */
    @Test
    void anImmaterialCashGapOfTheFilingIsANote() {
        assertThat(IngestionVerifier.immaterialExplanation(stored(new BigDecimal("7783983677"),
                new BigDecimal("7783983680"))))
                .contains("differ by 3").contains("under a billionth of the cash");
        assertThat(IngestionVerifier.immaterialExplanation(stored(new BigDecimal("305306998864"),
                new BigDecimal("305326998864")))).isNull();
        assertThat(IngestionVerifier.immaterialExplanation(stored(new BigDecimal("3156869509"),
                new BigDecimal("3156162509")))).isNull();
        assertThat(IngestionVerifier.immaterialExplanation(stored(new BigDecimal("7783983677"),
                new BigDecimal("7783983677")))).isNull();
        assertThat(IngestionVerifier.immaterialExplanation(stored(new BigDecimal("7783983677"),
                new BigDecimal("7783983685")))).isNull();
    }

    /**
     * EXCL FY2022 (millions) files its revenue breakdown with decimals: 29,141,993.900667 beside revenue 29,141,994.
     * Less than one unit apart: a note. The FY2023 filing (amounts in full, 'In Million' declared) judges it alike.
     */
    @Test
    void segmentRevenueWithinTheRoundingUnitIsANote() throws Exception {
        BigDecimal revenue = new BigDecimal("29141994000000");
        StoredPeriod within = new StoredPeriod("FY", true, true, true, 6, new BigDecimal("29141993900667"), revenue, null, null,
                null, null, null);
        StoredPeriod beyond = new StoredPeriod("FY", true, true, true, 6, revenue.subtract(new BigDecimal("1000000")), revenue,
                null, null, null, null, null);
        for (String filing : new String[] {"2022-Tahunan", "2023-Tahunan"}) {
            IngestionSession excl = session("EXCL", filing);
            assertThat(IngestionVerifier.segmentRoundingExplanation(excl, within))
                    .contains("differ by 99333").contains("less than the filing's rounding unit 1000000");
            assertThat(IngestionVerifier.segmentRoundingExplanation(excl, beyond)).isNull();
        }
    }

    /**
     * RAFI FY2022 (full rupiah, errors 1149, 1150) files its revenue with a fraction: 375,886,948,578.9396 beside a
     * breakdown of 375,886,948,579. Less than one rupiah apart: a note, in its own filing and its FY2023 one; a rupiah
     * or more stays a problem.
     */
    @Test
    void segmentRevenueWithinARupiahOfAFullAmountFilingIsANote() throws Exception {
        BigDecimal segments = new BigDecimal("375886948579");
        StoredPeriod within = new StoredPeriod("FY", true, true, true, 6, segments, new BigDecimal("375886948578.9396"),
                null, null, null, null, null);
        StoredPeriod beyond = new StoredPeriod("FY", true, true, true, 6, segments, new BigDecimal("375886948577.9396"),
                null, null, null, null, null);
        for (String filing : new String[] {"2022-Tahunan", "2023-Tahunan"}) {
            IngestionSession rafi = session("RAFI", filing);
            assertThat(IngestionVerifier.segmentRoundingExplanation(rafi, within))
                    .contains("differ by 0.0604").contains("less than the filing's rounding unit 1");
            assertThat(IngestionVerifier.segmentRoundingExplanation(rafi, beyond)).isNull();
        }
    }

    /**
     * MEDC FY2022 (full USD amounts) files common stocks 121,798,945.72: liabilities + equity 6,931,905,825.7 beside total
     * assets 6,931,905,826 (errors 711, 712). Less than one unit apart: a note, in its own filing and its FY2023 one.
     */
    @Test
    void balanceSheetWithinTheRoundingUnitIsANote() throws Exception {
        BigDecimal assets = new BigDecimal("6931905826");
        StoredPeriod within = new StoredPeriod("FY", true, true, true, 0, BigDecimal.ZERO, null, assets,
                new BigDecimal("6931905825.7"), null, null, null);
        StoredPeriod beyond = new StoredPeriod("FY", true, true, true, 0, BigDecimal.ZERO, null, assets,
                new BigDecimal("6931905825"), null, null, null);
        for (String filing : new String[] {"2022-Tahunan", "2023-Tahunan"}) {
            IngestionSession medc = session("MEDC", filing);
            assertThat(IngestionVerifier.balanceRoundingExplanation(medc, within))
                    .contains("differ by 0.3").contains("less than the filing's rounding unit 1");
            assertThat(IngestionVerifier.balanceRoundingExplanation(medc, beyond)).isNull();
        }
        // a filing in millions: a difference of a unit or more stays a problem
        assertThat(IngestionVerifier.balanceRoundingExplanation(session(), new StoredPeriod("FY", true, true, true, 0,
                BigDecimal.ZERO, null, new BigDecimal("100000000"), new BigDecimal("99000000"), null, null, null))).isNull();
    }

    private static StoredPeriod stored(BigDecimal balanceSheetCash, BigDecimal endingCash) {
        return new StoredPeriod("FY", true, true, true, 0, BigDecimal.ZERO, null, null, null,
                balanceSheetCash, endingCash, null);
    }

    private static IngestionSession session() throws Exception {
        return session("UNTR", "2022-Tahunan");
    }

    private static IngestionSession session(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
