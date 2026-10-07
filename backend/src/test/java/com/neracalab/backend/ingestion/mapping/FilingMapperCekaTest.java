package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.mapping.SegmentExtraction.SegmentLine;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * CEKA (Wilmar Cahaya Indonesia), full amounts: one product in two revenue slots (domestic and export), an H1
 * 2026 cash flow without financing activity, and an H1 2025 interest cost also filed as "Other expenses".
 */
class FilingMapperCekaTest {

    @ParameterizedTest
    @ValueSource(strings = {"2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II"})
    void everyCekaFilingMapsWithoutErrors(String filing) throws Exception {
        FilingMapper mapper = mapper(filing);
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(session.shareCapital().resolved()).isTrue();
        for (StatementColumn column : mapper.columns()) {
            for (MappedStatement s : session.statements(column)) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
            }
            session.segments(column).ifPresent(seg -> {
                assertThat(seg.hasErrors()).as(filing + " " + column + " segments").isFalse();
                assertThat(seg.lines().stream().map(SegmentLine::name).distinct().count())
                        .as(filing + " " + column + " distinct segment names").isEqualTo(seg.lines().size());
            });
        }
    }

    /** FY2025: "Produk Palm Kernel" is domestic revenue 2 and export revenue 1; both are kept, the total is revenue. */
    @Test
    void oneProductInTwoSlotsIsTwoSegments() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");
        IngestionSession session = new IngestionSession(mapper);
        SegmentExtraction seg = session.segments(StatementColumn.CURRENT_PERIOD).orElseThrow();

        Map<String, BigDecimal> byName = new java.util.LinkedHashMap<>();
        seg.lines().forEach(l -> byName.put(l.name(), l.revenue()));
        assertThat(byName).containsEntry("Produk Palm Kernel (domestik)", new BigDecimal("3391840269264"))
                .containsEntry("Produk Palm Kernel (ekspor)", new BigDecimal("210909304151"))
                .containsEntry("Produk Crude Palm Oil", new BigDecimal("4669065940965"))
                .doesNotContainKey("Produk Palm Kernel");
        assertThat(seg.lines().stream().map(SegmentLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("9733304188977");
        assertThat(seg.checks()).anyMatch(c -> c.rule().equals("segment_names") && c.severity() == Check.Severity.WARNING);
    }

    @Test
    void namesThatStillRepeatBlockTheBreakdown() {
        List<SegmentLine> lines = List.of(new SegmentLine("A", "GEOGRAPHY", BigDecimal.ONE, false),
                new SegmentLine("A", "GEOGRAPHY", BigDecimal.TEN, false));
        List<Check> checks = new ArrayList<>();

        FilingMapper.distinctSegmentNames(lines, List.of("Pendapatan dari ekspor 1", "Pendapatan dari ekspor 2"), checks);

        assertThat(checks).singleElement().satisfies(c -> assertThat(c.isError()).isTrue());
        assertThat(FilingMapper.slotQualifier("Pendapatan dari ekspor 1")).isEqualTo("ekspor");
        assertThat(FilingMapper.slotQualifier("Pendapatan domestik lainnya")).isEqualTo("domestik lainnya");
        assertThat(FilingMapper.slotQualifier("Export revenue 1")).isEqualTo("export");
    }

    /** H1 2026: no financing lines and no financing total; operating + investing = net change, so financing is 0. */
    @Test
    void cashFlowWithoutFinancingActivity() throws Exception {
        FilingMapper mapper = mapper("2026-II");
        MappedStatement cash = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(cash.hasErrors()).isFalse();
        assertThat(cash.values().get("financing_cash_flow")).isEqualByComparingTo("0");
        assertThat(cash.values().get("operating_cash_flow")).isEqualByComparingTo("228589953937");
        assertThat(cash.values().get("investing_cash_flow")).isEqualByComparingTo("-9003638218");
        assertThat(cash.checks()).anyMatch(c -> c.rule().equals("financing_total") && c.severity() == Check.Severity.WARNING);
        // the prior column reports its financing total: used as filed
        assertThat(mapper.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow().values().get("financing_cash_flow"))
                .isEqualByComparingTo("-79134");
    }

    /** H1 2025 (in the H1 2026 filing): interest 79,134 filed as "Interest and finance costs" and "Other expenses". */
    @Test
    void interestCostAlsoFiledAsOtherExpensesIsCountedOnce() throws Exception {
        FilingMapper mapper = mapper("2026-II");
        MappedStatement h1 = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(h1.hasErrors()).isFalse();
        assertThat(h1.values().get("pretax_income")).isEqualByComparingTo("155064703480");
        assertThat(h1.values().get("interest_expense")).isEqualByComparingTo("79134");
        assertThat(h1.checks()).anyMatch(c -> c.rule().equals("double_reported")
                && c.message().contains("'Other expenses' and 'Interest and finance costs'"));
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "CEKA", "xlsx", "FinancialStatement-" + filing + "-CEKA.xlsx");
        assumeTrue(Files.exists(file), "CEKA source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
