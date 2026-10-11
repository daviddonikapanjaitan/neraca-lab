package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.mapping.SegmentExtraction.SegmentLine;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * MIKA's revenue breakdown (1617000) has, in the comparative column, an unnamed "Service revenue 5" that the
 * breakdown's own subtotal and total do not include (ingestion errors 96 and 97: "segments do not reconcile",
 * {@code saveRevenueSegments(PRIOR_PERIOD)} left open, the uploads ended INCOMPLETE). The total and the revenue of
 * the income statement are the sum of the other lines, so that one line is not counted.
 */
class FilingMapperMikaTest {

    @Test
    void aLineTheBreakdownsOwnTotalExcludesIsNotCountedFy2024() throws Exception {
        SegmentExtraction prior = segments("2025-Tahunan", StatementColumn.PRIOR_PERIOD);

        // four services 2,691,594,436,625 + products 2,182,774,929,966; the fifth service line repeats the fourth
        assertThat(prior.hasErrors()).isFalse();
        assertThat(prior.reportedTotal()).isEqualByComparingTo("4874369366591");
        assertThat(sum(prior)).isEqualByComparingTo("4874369366591");
        assertThat(prior.lines()).filteredOn(l -> l.revenue().compareTo(new BigDecimal("602309854653")) == 0).hasSize(1);
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("segment_line") && c.severity() == Check.Severity.WARNING
                && c.message().contains("602309854653") && c.message().contains("not counted"));
    }

    @Test
    void aLineTheBreakdownsOwnTotalExcludesIsNotCountedH12025() throws Exception {
        SegmentExtraction prior = segments("2026-II", StatementColumn.PRIOR_PERIOD);

        assertThat(prior.hasErrors()).isFalse();
        assertThat(prior.reportedTotal()).isEqualByComparingTo("2563460446888");
        assertThat(sum(prior)).isEqualByComparingTo("2563460446888");
        assertThat(prior.lines()).noneMatch(l -> l.revenue().compareTo(new BigDecimal("261182623893")) == 0);
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("segment_line") && c.message().contains("261182623893"));
    }

    @Test
    void theCurrentColumnsReconcileAsFiled() throws Exception {
        for (String filing : new String[] {"2025-Tahunan", "2026-II"}) {
            SegmentExtraction current = segments(filing, StatementColumn.CURRENT_PERIOD);

            assertThat(current.hasErrors()).as(filing).isFalse();
            assertThat(sum(current)).as(filing).isEqualByComparingTo(current.reportedTotal());
            assertThat(current.checks()).as(filing).noneMatch(c -> c.rule().equals("segment_line"));
        }
    }

    private static BigDecimal sum(SegmentExtraction extraction) {
        return extraction.lines().stream().map(SegmentLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static SegmentExtraction segments(String filing, StatementColumn column) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "MIKA", "xlsx", "FinancialStatement-" + filing + "-MIKA.xlsx");
        assumeTrue(Files.exists(file), "MIKA source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        FilingMapper mapper = new FilingMapper(workbook);
        BigDecimal revenue = mapper.incomeStatement(column, Map.of(), mapper.shareCapital()).orElseThrow().values().get("revenue");
        return mapper.segments(column, revenue).orElseThrow();
    }
}
