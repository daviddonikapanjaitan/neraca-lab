package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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
import com.neracalab.backend.ingestion.mapping.PeriodRef;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.CompanyRow;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.StoredPeriod;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * TPIA's FY2025 filing (error 417): its FY2024 revenue breakdown contradicts itself (the product lines add up to
 * 2,107,089 thousand under a product subtotal of 1,695,869), so it is never saved. FY2024's breakdown is stored from
 * the FY2024 filing and adds up to its revenue; a comparative never overwrites it, so the refused breakdown is a note,
 * not an open item that leaves the upload INCOMPLETE.
 */
class IngestionVerifierSegmentsTest {

    private static final BigDecimal FY2024_REVENUE = new BigDecimal("1785364000");

    @Test
    void aRefusedComparativeBreakdownOfAPeriodWithAReconciledBreakdownIsANote() throws Exception {
        IngestionSession session = savedSession();
        IngestionVerifier verifier = new IngestionVerifier(repository(7, FY2024_REVENUE));

        var verification = verifier.verify(session);

        assertThat(verification.pending()).isEmpty();
        assertThat(verification.problems()).isEmpty();
        assertThat(verification.complete()).isTrue();
        assertThat(verification.notes()).anyMatch(n -> n.startsWith("2024 FY: the revenue breakdown of PRIOR_PERIOD does not reconcile")
                && n.contains("7 segments stored for 2024 FY"));
    }

    @Test
    void withoutAStoredBreakdownTheComparativeStaysOpen() throws Exception {
        var none = new IngestionVerifier(repository(0, null)).verify(savedSession());
        assertThat(none.pending()).containsExactly("saveRevenueSegments(PRIOR_PERIOD) for 2024 FY");

        // a stored breakdown that does not add up to the revenue is no reason to drop it either
        var partial = new IngestionVerifier(repository(3, new BigDecimal("1000"))).verify(savedSession());
        assertThat(partial.pending()).contains("saveRevenueSegments(PRIOR_PERIOD) for 2024 FY");
    }

    /** The FY2025 filing with both columns' statements and the FY2025 breakdown saved, FY2024's extracted, the derived data refreshed. */
    private static IngestionSession savedSession() throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "TPIA", "xlsx", "FinancialStatement-2025-Tahunan-TPIA.xlsx");
        assumeTrue(Files.exists(file), "TPIA source data not available: " + file);
        IngestionSession session;
        try (InputStream in = Files.newInputStream(file)) {
            session = new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
        assertThat(session.segments(StatementColumn.PRIOR_PERIOD).orElseThrow().hasErrors()).isTrue();
        session.company(new CompanyRow(1, "TPIA", "TPIA", "PT Chandra Asri Pacific Tbk", null, null, "USD",
                LocalDate.of(2025, 12, 31)));
        for (StatementColumn column : session.mapper().columns()) {
            session.statementsSaved(column, List.of());
        }
        session.segmentsSaved(StatementColumn.CURRENT_PERIOD, List.of("Infrastruktur", "Energi", "Kimia"));
        session.markSegmentsExtracted(StatementColumn.PRIOR_PERIOD);
        session.sharesSaved();
        session.derivedRefreshed();
        return session;
    }

    /** FY2024 stored with {@code segments} segments adding up to {@code segmentRevenue}; FY2025 stored as filed. */
    private static IngestionRepository repository(int segments, BigDecimal segmentRevenue) {
        IngestionRepository repository = mock(IngestionRepository.class);
        when(repository.storedPeriod(anyLong(), any())).thenAnswer(invocation -> {
            PeriodRef period = invocation.getArgument(1);
            if (period.key().equals("2024 FY")) {
                return Optional.of(new StoredPeriod("2024 FY", true, true, true, segments, segmentRevenue, FY2024_REVENUE,
                        null, null, null, null, "FinancialStatement-2024-Tahunan-TPIA.xlsx"));
            }
            return Optional.of(new StoredPeriod(period.key(), true, true, true, 3, new BigDecimal("7020007000"),
                    new BigDecimal("7020007000"), null, null, null, null, "FinancialStatement-2025-Tahunan-TPIA.xlsx"));
        });
        when(repository.derivedCounts(anyLong())).thenReturn(java.util.Map.of("price_daily", 1L));
        return repository;
    }
}
