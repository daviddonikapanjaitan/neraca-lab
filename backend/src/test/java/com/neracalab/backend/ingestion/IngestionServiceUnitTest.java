package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.agent.FullTextExtractor;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.CompanyRow;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * IMJS's FY2024 workbook declares "Satuan Penuh / Full Amount" over amounts in millions: total assets 29,410,622, the
 * prior year end 28,711,560 (FY2023 stored 28,711,561,013,612), profit attributable to the parent 228,477 / basic EPS
 * 26.4 = 8,654 shares as filed (8,654,431,818 in millions). Read as declared, FY2024 was stored a million times too small.
 */
class IngestionServiceUnitTest {

    private final IngestionRepository repository = mock(IngestionRepository.class);
    private final IngestionService service = new IngestionService(new IdxWorkbookReader(), mock(IngestionAgent.class),
            repository, mock(WebShareCounts.class), mock(FullTextExtractor.class));

    @Test
    void amountsInMillionsUnderAFullAmountDeclarationAreReadInMillionsWhenTheStoredPriorYearEndProvesIt() throws Exception {
        byte[] fy2024 = workbook("IMJS", "2024-Tahunan");
        company("IMJS", 3120);
        when(repository.totalAssets(3120, LocalDate.parse("2023-12-31")))
                .thenReturn(Optional.of(new BigDecimal("28711561013612")));

        IngestionSession session = service.open(fy2024, "FinancialStatement-2024-Tahunan-IMJS.xlsx");

        FilingMapper mapper = session.mapper();
        assertThat(mapper.unit()).isEqualByComparingTo("1000000");
        assertThat(mapper.templateProblems()).noneMatch(p -> p.startsWith("Amounts contradict"));
        assertThat(mapper.warnings()).anyMatch(w -> w.contains("its amounts are in units of 1000000")
                && w.contains("8654431818 shares"));
        assertThat(mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, null).orElseThrow().values().get("total_assets"))
                .isEqualByComparingTo("29410622000000");
    }

    @Test
    void theDeclaredUnitStandsWithoutAMatchingStoredFigure() throws Exception {
        byte[] fy2024 = workbook("IMJS", "2024-Tahunan");
        company("IMJS", 3120);
        when(repository.totalAssets(anyLong(), any())).thenReturn(Optional.empty());
        assertThat(service.open(fy2024, "FinancialStatement-2024-Tahunan-IMJS.xlsx").mapper().unit()).isEqualByComparingTo("1");

        // a stored figure that is not the filed one in any unit
        when(repository.totalAssets(3120, LocalDate.parse("2023-12-31"))).thenReturn(Optional.of(new BigDecimal("30000000000000")));
        assertThat(service.open(fy2024, "FinancialStatement-2024-Tahunan-IMJS.xlsx").mapper().unit()).isEqualByComparingTo("1");
    }

    @Test
    void aWorkbookWhoseRoundingIsNotContradictedKeepsItsUnit() throws Exception {
        // IMJS FY2025: "Jutaan / In Million", FY2024 at 29,410,622 - stored a million times smaller would match x1000
        // only if the EPS contradicted its rounding, which it does not
        byte[] fy2025 = workbook("IMJS", "2025-Tahunan");
        company("IMJS", 3120);
        when(repository.totalAssets(3120, LocalDate.parse("2024-12-31"))).thenReturn(Optional.of(new BigDecimal("29410622")));
        assertThat(service.open(fy2025, "FinancialStatement-2025-Tahunan-IMJS.xlsx").mapper().unit()).isEqualByComparingTo("1000000");
    }

    /**
     * IPCC's 9M 2026 workbook files its EPS in hundred-thousandths (0.000974 thousand for Rp 97.4): the par value inferred
     * from it is Rp 1 and gives 181,838,482,000 shares at 2025-12-31, where its FY2025 filing stored 1,818,384,820.
     */
    @Test
    void shareCountsContradictingTheStoredCountOfTheSameDateAreDropped() throws Exception {
        IngestionSession session = service.open(workbook("IPCC", "2026-III"), "FinancialStatement-2026-III-IPCC.xlsx");
        assertThat(session.shareCapital().resolved()).isTrue();
        company("IPCC", 4001);
        when(repository.storedShareCount(4001, LocalDate.parse("2025-12-31"))).thenReturn(Optional.of(new BigDecimal("1818384820")));

        service.dropContradictedShareCounts(session);

        assertThat(session.shareCapital().resolved()).isFalse();
        assertThat(session.shareCapital().snapshots()).allMatch(s -> s.sharesOutstanding() == null);
        assertThat(session.notes()).anyMatch(n -> n.contains("181838482000 shares at 2025-12-31 contradict the stored 1818384820"));

        // a split recorded since explains it
        IngestionSession again = service.open(workbook("IPCC", "2026-III"), "FinancialStatement-2026-III-IPCC.xlsx");
        when(repository.splitBetween(4001, LocalDate.parse("2025-12-31"), LocalDate.parse("2026-09-30"))).thenReturn(true);
        service.dropContradictedShareCounts(again);
        assertThat(again.shareCapital().resolved()).isTrue();
    }

    @Test
    void aShareIssueAfterTheStoredDateKeepsTheCounts() throws Exception {
        // ATIC 9M 2026: 2,315,361,355 at 2025-12-31 as stored, 2,894,201,693 at 2026-09-30 after its share issue
        IngestionSession session = service.open(workbook("ATIC", "2026-III"), "FinancialStatement-2026-III-ATIC.xlsx");
        company("ATIC", 4002);
        when(repository.storedShareCount(anyLong(), any())).thenReturn(Optional.empty());
        when(repository.storedShareCount(4002, LocalDate.parse("2025-12-31"))).thenReturn(Optional.of(new BigDecimal("2315361355")));

        service.dropContradictedShareCounts(session);

        assertThat(session.shareCapital().resolved()).isTrue();
        assertThat(session.shareCapital().snapshots()).anyMatch(s -> s.date().equals(LocalDate.parse("2026-09-30"))
                && s.sharesOutstanding().compareTo(new BigDecimal("2894201693")) == 0);
    }

    private void company(String ticker, long id) {
        when(repository.findCompany(ticker)).thenReturn(Optional.of(new CompanyRow(id, ticker, ticker, ticker, "Transportation",
                "Transportation", "IDR", LocalDate.parse("2025-12-31"))));
        when(repository.latestPeriodEnd(id)).thenReturn(Optional.of(LocalDate.parse("2026-06-30")));
    }

    private static byte[] workbook(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        return Files.readAllBytes(file);
    }
}
