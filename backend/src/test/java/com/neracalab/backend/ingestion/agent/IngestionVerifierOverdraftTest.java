package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.StoredPeriod;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * GGRM presents cash net of bank overdrafts in the cash flow statement; the overdrafts sit in
 * "Short term bank loans" on the balance sheet. The difference is the filing, not a storage error,
 * as long as the stored figures are the filing's own.
 */
class IngestionVerifierOverdraftTest {

    private static final Path DATA = Path.of("..", "data", "IDX_XBRL", "GGRM", "xlsx");
    private static final StatementColumn CURRENT = StatementColumn.CURRENT_PERIOD;

    @ParameterizedTest
    @CsvSource({
            "2025-Tahunan, 3613292000000, 3351361000000",    // overdrafts 261,931 million
            "2024-Tahunan, 3705754000000, 3330356000000",    // 375,398 million
            "2022-Tahunan, 4407033000000, 3709026000000"})   // 698,007 million (pre-2023 template)
    void overdraftNettingAsFiledIsANoteNotAProblem(String filing, String balanceSheetCash, String endingCash) throws Exception {
        IngestionSession session = session(filing);
        assertThat(session.mapper().templateProblems()).isEmpty();
        for (MappedStatement s : session.statements(CURRENT)) {
            assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + s.table()).isEmpty();
        }
        BigDecimal cash = new BigDecimal(balanceSheetCash);
        BigDecimal ending = new BigDecimal(endingCash);

        String note = IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending));
        assertThat(note).contains("bank overdrafts").contains(cash.subtract(ending).toPlainString());

        // stored figures that are not the filing's own are still a problem
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending.add(BigDecimal.ONE)))).isNull();
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash.add(BigDecimal.ONE), ending))).isNull();
    }

    /**
     * LPPF FY2023 (error 809) leaves its bank loans off the face of the balance sheet, so the filing gives no short-term
     * debt; the cash flow's 457,628 million is the balance sheet's 507,631 less overdrafts of 50,003, within the 550,003
     * stored for that date from the FY2024 comparative.
     */
    @Test
    void overdraftsWithinTheStoredShortTermDebtWhenTheFilingLeavesItsLoansOffTheFace() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "LPPF", "xlsx", "FinancialStatement-2023-Tahunan-LPPF.xlsx");
        assumeTrue(Files.exists(file), "LPPF source data not available: " + file);
        IngestionSession session;
        try (InputStream in = Files.newInputStream(file)) {
            session = new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
        BigDecimal cash = new BigDecimal("507631000000");
        BigDecimal ending = new BigDecimal("457628000000");

        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending))).isNull();
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending), new BigDecimal("550003000000")))
                .contains("bank overdrafts", "less 50003000000", "within short-term borrowings 550003000000");
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending), new BigDecimal("50002000000")))
                .isNull();
    }

    /**
     * LAJU FY2022 (error 1622): 14,795,628,146 - short-term bank loans 11,031,270,459 = 3,764,357,687, the ending cash cell
     * 3.7643576861000004E9 (stored 3,764,357,686.1000; its prior year: 1,000,933,932 - 10,230,902,120 = -9,229,968,188, the
     * beginning cash). The cell's binary fraction beyond the stored scale made the stored figures look like another filing's.
     */
    @Test
    void anEndingCashCellWithABinaryFractionIsTheStoredAmount() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "LAJU", "xlsx", "FinancialStatement-2022-Tahunan-LAJU.xlsx");
        assumeTrue(Files.exists(file), "LAJU source data not available: " + file);
        IngestionSession session;
        try (InputStream in = Files.newInputStream(file)) {
            session = new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
        BigDecimal cash = new BigDecimal("14795628146.0000");
        BigDecimal ending = new BigDecimal("3764357686.1000");

        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending)))
                .contains("bank overdrafts netted against cash");
        // another stored figure is still a problem
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending.add(new BigDecimal("0.0001")))))
                .isNull();
    }

    /**
     * The FY2021 template files short-term borrowings on one row, "Short-term loans" (later: "Short term bank loans" and
     * "Short-term non-bank loans"). BAUT FY2021: the cash flow's -11,707,986,630 is the balance sheet's 241,086,275 less
     * the loans of 11,949,072,905 exactly; SKLT FY2021: 127,460,249,506 - 100,255,079,540 = 27,205,169,966; CLPI within
     * its loans of 124,328,312,016 (errors 1973, 2045, 1995).
     */
    @ParameterizedTest
    @CsvSource({
            "BAUT, 241086275, -11707986630, 12286039664",   // loans 11,949,072,905 + current bank loans 257,854,988 + consumer financing 79,111,771
            "SKLT, 127460249506, 27205169966, ",
            "CLPI, 191559102091, 171543788955, "})
    void theFy2021ShortTermLoansRowIsDebtAndExplainsTheOverdrafts(String ticker, String balanceSheetCash, String endingCash,
                                                                  String shortTermDebt) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-2021-Tahunan-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        IngestionSession session;
        try (InputStream in = Files.newInputStream(file)) {
            session = new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
        MappedStatement balance = session.statements(CURRENT).stream()
                .filter(s -> s.table().equals("balance_sheet")).findFirst().orElseThrow();
        BigDecimal debt = balance.values().get("short_term_debt");
        assertThat(balance.derivations().get("short_term_debt")).contains("Short-term loans");
        if (shortTermDebt != null) {
            assertThat(debt).isEqualByComparingTo(shortTermDebt);
        }
        BigDecimal cash = new BigDecimal(balanceSheetCash);
        BigDecimal ending = new BigDecimal(endingCash);
        assertThat(debt).isGreaterThanOrEqualTo(cash.subtract(ending));
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending)))
                .contains("bank overdrafts netted against cash");
    }

    private static StoredPeriod stored(BigDecimal balanceSheetCash, BigDecimal endingCash) {
        return new StoredPeriod("FY", true, true, true, 0, BigDecimal.ZERO, null, null, null,
                balanceSheetCash, endingCash, null);
    }

    private static IngestionSession session(String filing) throws Exception {
        Path file = DATA.resolve("FinancialStatement-" + filing + "-GGRM.xlsx");
        assumeTrue(Files.exists(file), "GGRM source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
