package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.StoredPeriod;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * AMAG (an insurer, Insurance Industry taxonomy, thousands; errors 1354, 1356 - 1360): its cash flow statement counts
 * the time deposits maturing within three months as cash, its balance sheet shows them among 'Investments in time
 * deposits' (not split into current and non-current). FY2025: ending cash 485,425,155 = cash 64,021,213 + 421,403,942,
 * within time deposits of 651,473,868. An insurer has no short-term debt row: the excess needs none. A note, not a
 * problem, as long as the stored figures are the filing's own.
 */
class IngestionVerifierInsurerDepositsTest {

    private static final StatementColumn CURRENT = StatementColumn.CURRENT_PERIOD;

    @Test
    void anInsurersTimeDepositsCountedAsCashAreANote() throws Exception {
        IngestionSession session = session("AMAG", "2025-Tahunan");
        assertThat(session.mapper().currentFinancialAssetsOutsideCash(CURRENT)).isEqualByComparingTo("651473868000");
        BigDecimal cash = new BigDecimal("64021213000");
        BigDecimal ending = new BigDecimal("485425155000");
        fullText(session, cash, ending);

        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending)))
                .contains("plus 421403942000").contains("within other current financial assets 651473868000");
        // stored figures that are not the filing's own are still a problem
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, ending.add(BigDecimal.ONE)))).isNull();
        // more than the time deposits stays a problem
        BigDecimal beyond = new BigDecimal("715495082000");     // cash + 651,473,869: a thousand rupiah beyond
        fullText(session, cash, beyond);
        assertThat(IngestionVerifier.overdraftExplanation(session, CURRENT, stored(cash, beyond))).isNull();
    }

    private static void fullText(IngestionSession session, BigDecimal cash, BigDecimal ending) {
        var period = session.mapper().period(CURRENT);
        session.fullTextStatements(CURRENT, List.of(
                new MappedStatement("balance_sheet", CURRENT, period, "6220000", Map.of("cash_and_equivalents", cash),
                        Map.of(), List.of(), List.of()),
                new MappedStatement("cash_flow_statement", CURRENT, period, "6510000", Map.of("ending_cash", ending),
                        Map.of(), List.of(), List.of())));
    }

    private static StoredPeriod stored(BigDecimal balanceSheetCash, BigDecimal endingCash) {
        return new StoredPeriod("FY", true, true, true, 0, BigDecimal.ZERO, null, null, null,
                balanceSheetCash, endingCash, null);
    }

    private static IngestionSession session(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
