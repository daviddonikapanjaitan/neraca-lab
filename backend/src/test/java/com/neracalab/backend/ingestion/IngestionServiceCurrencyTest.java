package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.agent.FullTextExtractor;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.CompanyRow;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * CUAN presented its statements in IDR up to FY2023 and in USD from FY2024. Its FY2023 filing stored FY2022 and FY2023
 * in rupiah beside the dollar years and switched company.currency to IDR: a filing in another currency than the
 * company's stored data is refused.
 */
class IngestionServiceCurrencyTest {

    private final IngestionRepository repository = mock(IngestionRepository.class);
    private final IngestionService service = new IngestionService(new IdxWorkbookReader(), mock(IngestionAgent.class),
            repository, mock(WebShareCounts.class), mock(FullTextExtractor.class));

    @Test
    void olderFilingInAFormerCurrencyIsRefused() throws Exception {
        byte[] fy2023 = workbook("2023-Tahunan");
        company("USD", "2026-06-30");

        assertThatThrownBy(() -> service.open(fy2023, "FinancialStatement-2023-Tahunan-CUAN.xlsx"))
                .isInstanceOf(IdxWorkbookException.class)
                .hasMessageContaining("presents its statements in IDR, but CUAN's stored statements and prices are in USD")
                .hasMessageContaining("CUAN no longer reports in IDR");
    }

    @Test
    void newerFilingInAnotherCurrencyIsRefusedToo() throws Exception {
        byte[] fy2023 = workbook("2023-Tahunan");
        company("USD", "2022-12-31");

        assertThatThrownBy(() -> service.open(fy2023, "FinancialStatement-2023-Tahunan-CUAN.xlsx"))
                .isInstanceOf(IdxWorkbookException.class)
                .hasMessageContaining("seems to have changed its presentation currency after 2022-12-31");
    }

    @Test
    void sameCurrencyNewCompanyOrNoStoredPeriodIsAccepted() throws Exception {
        byte[] fy2024 = workbook("2024-Tahunan");
        company("USD", "2026-06-30");
        assertThat(service.open(fy2024, "FinancialStatement-2024-Tahunan-CUAN.xlsx").info().currency()).isEqualTo("USD");

        byte[] fy2023 = workbook("2023-Tahunan");
        company("USD", null);
        assertThat(service.open(fy2023, "FinancialStatement-2023-Tahunan-CUAN.xlsx").info().currency()).isEqualTo("IDR");

        when(repository.findCompany("CUAN")).thenReturn(Optional.empty());
        assertThat(service.open(fy2023, "FinancialStatement-2023-Tahunan-CUAN.xlsx").info().ticker()).isEqualTo("CUAN");
    }

    private void company(String currency, String latestPeriodEnd) {
        when(repository.findCompany("CUAN")).thenReturn(Optional.of(new CompanyRow(2780, "CUAN", "Petrindo Jaya Kreasi",
                "PT Petrindo Jaya Kreasi Tbk", "Energy", "Coal", currency, LocalDate.parse("2025-12-31"))));
        when(repository.latestPeriodEnd(2780)).thenReturn(Optional.ofNullable(latestPeriodEnd).map(LocalDate::parse));
    }

    private static byte[] workbook(String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "CUAN", "xlsx", "FinancialStatement-" + filing + "-CUAN.xlsx");
        assumeTrue(Files.exists(file), "CUAN source data not available: " + file);
        return Files.readAllBytes(file);
    }
}
