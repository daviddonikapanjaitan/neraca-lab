package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.SegmentExtraction;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.CompanyRow;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.SegmentRow;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.StoredPeriod;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * A later filing that restates a period's revenue splits the restated figure: that breakdown is not one of the
 * revenue the period's own filing stored, and a period never shows a revenue beside the breakdown of another
 * (errors 1468, 1471, 1480). A breakdown of the same revenue at a coarser rounding is one of it (INPP).
 */
class IngestionToolsSegmentsTest {

    /**
     * BMHS's FY2024 filing restates FY2023's revenue 1,287,690,839,110 (its FY2023 filing) as 1,488,197,483,371 - cost
     * of revenue alike, gross profit 695,812,877,111 in both - and splits the restated figure into nine lines. The
     * comparative breakdown is not saved to FY2023.
     */
    @Test
    void aComparativeBreakdownOfARestatedRevenueIsKeptOut() throws Exception {
        IngestionSession session = session("BMHS", "2024-Tahunan");
        SegmentExtraction prior = session.segments(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.hasErrors()).isFalse();
        assertThat(total(prior)).isEqualByComparingTo("1488197483371");
        IngestionRepository repository = repository(new BigDecimal("1287690839110"), 0, null);

        var result = tools(session, repository).saveRevenueSegments(StatementColumn.PRIOR_PERIOD,
                DeterministicFinisher.namings(prior, List.of()));

        assertThat(result.saved()).isNotEmpty().allMatch(s -> s.contains("KEPT_OUT")
                && s.contains("1488197483371") && s.contains("1287690839110"));
        verify(repository, never()).writeSegmentRevenue(anyLong(), anyLong(), anyLong(), any(), anyBoolean());
        assertThat(session.savedSegments()).containsKey(StatementColumn.PRIOR_PERIOD);   // nothing left pending
    }

    /**
     * INPP's FY2025 filing (millions) splits FY2024's revenue as 1,309,662 million; its FY2024 filing (full rupiah)
     * states 1,309,661,867,481. The same revenue, rounded: the breakdown is saved.
     */
    @Test
    void aComparativeBreakdownOfTheSameRevenueAtACoarserRoundingIsSaved() throws Exception {
        IngestionSession session = session("INPP", "2025-Tahunan");
        SegmentExtraction prior = session.segments(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(total(prior)).isEqualByComparingTo("1309662000000");
        IngestionRepository repository = repository(new BigDecimal("1309661867481"), 0, null);

        var result = tools(session, repository).saveRevenueSegments(StatementColumn.PRIOR_PERIOD,
                DeterministicFinisher.namings(prior, List.of()));

        assertThat(result.saved()).noneMatch(s -> s.contains("KEPT_OUT"));
        verify(repository, times(prior.lines().size())).writeSegmentRevenue(anyLong(), anyLong(), anyLong(), any(), anyBoolean());
    }

    /**
     * BHIT's FY2025 filing, uploaded first, filled FY2024 with its restated revenue 15,139,665 million and ten lines
     * adding up to it; the FY2024 filing then stores its own 15,226,314 and has no breakdown of its own. Saving its
     * statements removes the ten lines.
     */
    @Test
    void theOwnFilingRemovesABreakdownOfAnotherRevenue() throws Exception {
        IngestionSession session = session("BHIT", "2024-Tahunan");
        assertThat(session.segments(StatementColumn.CURRENT_PERIOD)).isEmpty();
        IngestionRepository repository = repository(new BigDecimal("15226314000000"), 10, new BigDecimal("15139665000000"));
        when(repository.removeSegmentRevenue(anyLong())).thenReturn(List.of("Media Iklan - Digital", "Pertambangan"));
        session.markExtracted(StatementColumn.CURRENT_PERIOD);

        tools(session, repository).saveStatements(StatementColumn.CURRENT_PERIOD);

        verify(repository).removeSegmentRevenue(7L);
        assertThat(session.notes()).anyMatch(n -> n.startsWith("2024 FY: the stored revenue breakdown 15139665000000")
                && n.contains("not one of this filing's revenue 15226314000000") && n.contains("removed"));
    }

    /** A breakdown that adds up to the revenue, or within the slack of a coarser rounding, stays. */
    @Test
    void aBreakdownOfTheRevenueStays() throws Exception {
        IngestionSession session = session("BHIT", "2024-Tahunan");
        IngestionRepository repository = repository(new BigDecimal("1309661867481"), 2, new BigDecimal("1309662000000"));
        session.markExtracted(StatementColumn.CURRENT_PERIOD);

        tools(session, repository).saveStatements(StatementColumn.CURRENT_PERIOD);

        verify(repository, never()).removeSegmentRevenue(anyLong());
        assertThat(IngestionTools.describes(new BigDecimal("1309662000000"), new BigDecimal("1309661867481"))).isTrue();
        assertThat(IngestionTools.describes(new BigDecimal("15139665000000"), new BigDecimal("15226314000000"))).isFalse();
        assertThat(IngestionTools.describes(BigDecimal.TEN, null)).isTrue();
    }

    private static BigDecimal total(SegmentExtraction extraction) {
        return extraction.lines().stream().map(SegmentExtraction.SegmentLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static IngestionTools tools(IngestionSession session, IngestionRepository repository) {
        for (StatementColumn column : session.mapper().columns()) {
            session.statementsSaved(column, List.of());
            session.markSegmentsExtracted(column);
        }
        session.savedStatements().remove(StatementColumn.CURRENT_PERIOD);
        session.company(new CompanyRow(1, session.info().ticker(), session.info().ticker(), "PT Test Tbk", null, null, "IDR",
                LocalDate.of(2025, 12, 31)));
        return new IngestionTools(session, repository, mock(IngestionVerifier.class));
    }

    /** Every period stored with {@code revenue} and a breakdown of {@code segments} lines adding up to {@code segmentRevenue}. */
    private static IngestionRepository repository(BigDecimal revenue, int segments, BigDecimal segmentRevenue) {
        IngestionRepository repository = mock(IngestionRepository.class);
        when(repository.companyLock(any())).thenReturn(new java.util.concurrent.locks.ReentrantLock());
        when(repository.periodId(anyLong(), any())).thenReturn(Optional.of(7L));
        when(repository.upsertPeriod(anyLong(), any(), any(), any(), anyBoolean())).thenReturn(7L);
        when(repository.storedPeriod(anyLong(), any())).thenAnswer(invocation -> Optional.of(new StoredPeriod(
                invocation.getArgument(1, com.neracalab.backend.ingestion.mapping.PeriodRef.class).key(), true, true, true,
                segments, segmentRevenue, revenue, null, null, null, null, null)));
        when(repository.ensureSegment(anyLong(), anyString(), anyString(), any())).thenAnswer(invocation ->
                new SegmentRow(1, invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));
        return repository;
    }

    private static IngestionSession session(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
