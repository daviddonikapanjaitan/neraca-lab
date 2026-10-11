package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.StoredPeriod;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * PWON's cash flow statement counts more cash than its balance sheet: deposits or restricted funds shown
 * among "Other current financial assets". The excess is the filing, not a storage error, as long as it is
 * within those assets and the stored figures are the filing's own. Amounts are filed in IDR thousands.
 */
class IngestionVerifierDepositsTest {

    private static final Path DATA = Path.of("..", "data", "IDX_XBRL", "PWON", "xlsx");
    private static final StatementColumn CURRENT = StatementColumn.CURRENT_PERIOD;
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    @ParameterizedTest
    @CsvSource({
            "2022-Tahunan, 7444244953, 7461776255",    // excess 17,531,302; other current financial assets 409,027,884
            "2023-Tahunan, 7599820229, 7633001017",    // 33,180,788
            "2024-Tahunan, 9154064042, 9203607591",    // 49,543,549
            "2025-Tahunan, 5186756706, 5243521448",    // 56,764,742
            "2026-II,      4461085075, 4526147274"})   // 65,062,199
    void depositsCountedAsCashAreANoteNotAProblem(String filing, long balanceSheetCash, long endingCash) throws Exception {
        IngestionSession session = session(filing);
        assertThat(session.mapper().templateProblems()).isEmpty();
        for (StatementColumn column : session.mapper().columns()) {
            for (MappedStatement s : session.statements(column)) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
            }
        }
        BigDecimal cash = BigDecimal.valueOf(balanceSheetCash).multiply(THOUSAND);
        BigDecimal ending = BigDecimal.valueOf(endingCash).multiply(THOUSAND);

        String note = IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending));
        assertThat(note).contains("deposits or restricted funds").contains(ending.subtract(cash).toPlainString());

        // stored figures that are not the filing's own are still a problem
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending.add(BigDecimal.ONE)))).isNull();
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash.add(BigDecimal.ONE), ending))).isNull();
    }

    private static StoredPeriod stored(BigDecimal balanceSheetCash, BigDecimal endingCash) {
        return new StoredPeriod("FY", true, true, true, 0, BigDecimal.ZERO, null, null, null,
                balanceSheetCash, endingCash, null);
    }

    private static IngestionSession session(String filing) throws Exception {
        Path file = DATA.resolve("FinancialStatement-" + filing + "-PWON.xlsx");
        assumeTrue(Files.exists(file), "PWON source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
