package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The filed EPS checked against the filing's share count. NCKL's FY2024 workbook files its current basic EPS as
 * 0.00001011: in its rounding unit (millions) and one more decimal place off, so 10.11 after the unit for an EPS of
 * 101.1 (its FY2025 workbook's comparative says 101.1). MYOR, NCKL and PTSN filings map without errors.
 */
class FilingMapperEpsTest {

    /**
     * NCKL FY2024: the current EPS (10.11) fits only par value 10, the prior column's (92.39) only par value 100. The
     * workbook gives no share counts (before: par 10, 631 billion shares, ten times too many) and neither column's
     * EPS is stored - as a rejected value, so a wrong EPS stored before is cleared; the FY2025 workbook's comparative
     * fills FY2024 with 101.1.
     */
    @Test
    void contradictingEpsColumnsGiveNoShareCountsAndNoEps() throws Exception {
        FilingMapper fy2024 = mapper("NCKL", "2024-Tahunan");
        ShareCapital shares = fy2024.shareCapital();

        assertThat(shares.resolved()).isFalse();
        assertThat(shares.snapshots()).allSatisfy(s -> assertThat(s.sharesOutstanding()).isNull());
        assertThat(shares.checks()).anyMatch(c -> c.rule().equals("eps_columns") && c.message().contains("[10]")
                && c.message().contains("[100]"));
        for (StatementColumn column : List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD)) {
            MappedStatement income = fy2024.incomeStatement(column, Map.of(), shares).orElseThrow();
            assertThat(income.hasErrors()).isFalse();
            assertThat(income.values()).containsEntry("basic_eps", null).containsEntry("diluted_eps", null);
            assertThat(income.rejected()).containsExactlyInAnyOrder("basic_eps", "diluted_eps", "basic_shares");
            assertThat(income.values().get("net_income_to_parent")).isNotNull();
        }
        MappedStatement balance = fy2024.balanceSheet(StatementColumn.CURRENT_PERIOD, shares).orElseThrow();
        assertThat(balance.values()).containsEntry("shares_outstanding", null);
        assertThat(balance.rejected()).containsExactly("shares_outstanding");

        FilingMapper fy2025 = mapper("NCKL", "2025-Tahunan");
        assertThat(fy2025.shareCapital().resolved()).isTrue();
        assertThat(fy2025.shareCapital().parValue()).isEqualByComparingTo("100");
        assertThat(fy2025.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), fy2025.shareCapital()).orElseThrow()
                .values().get("basic_eps")).isEqualByComparingTo("101.1");
    }

    @ParameterizedTest
    @CsvSource({"MYOR,2022-Tahunan", "MYOR,2023-Tahunan", "MYOR,2024-Tahunan", "MYOR,2025-Tahunan", "MYOR,2026-II",
            "NCKL,2023-Tahunan", "NCKL,2024-Tahunan", "NCKL,2025-Tahunan", "NCKL,2026-II",
            "PTSN,2022-Tahunan", "PTSN,2023-Tahunan", "PTSN,2024-Tahunan", "PTSN,2025-Tahunan", "PTSN,2026-II"})
    void newFilingsMapWithoutErrors(String ticker, String filing) throws Exception {
        FilingMapper mapper = mapper(ticker, filing);
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        for (StatementColumn column : mapper.columns()) {
            for (MappedStatement s : session.statements(column)) {
                assertThat(s.hasErrors()).as(ticker + " " + filing + " " + column + " " + s.table() + " " + s.checks()).isFalse();
                BigDecimal eps = s.values().get("basic_eps");
                assertThat(eps == null || eps.signum() != 0).isTrue();
            }
        }
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
