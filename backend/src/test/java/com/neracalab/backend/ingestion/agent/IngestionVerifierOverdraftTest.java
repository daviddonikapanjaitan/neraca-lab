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
