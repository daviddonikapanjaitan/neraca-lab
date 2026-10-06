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
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.neracalab.backend.ingestion.xlsx.IdxSheets;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The pre-2023 IDX template (statement headers "31 December 2022", sheets "1410000 1 CurrentYear" /
 * "1410000 2 PriorYear"): the INDF FY2022 filing maps to the same FY2022 figures as the comparative
 * column of the INDF FY2023 filing (current template).
 */
class FilingMapperLegacyTemplateTest {

    private static final Path DATA = Path.of("..", "data", "INDF", "xlsx");

    @Test
    void legacyTemplateMatchesComparativeOfNextYear() throws Exception {
        FilingMapper legacy = mapper("FinancialStatement-2022-Tahunan-INDF.xlsx");
        FilingMapper next = mapper("FinancialStatement-2023-Tahunan-INDF.xlsx");

        assertThat(legacy.templateProblems()).isEmpty();
        assertThat(legacy.info().ticker()).isEqualTo("INDF");
        assertThat(legacy.info().current().key()).isEqualTo(next.info().prior().key());
        assertThat(legacy.balanceSheetSheet()).isEqualTo(IdxSheets.BALANCE_SHEET);

        ShareCapital legacyShares = legacy.shareCapital();
        ShareCapital nextShares = next.shareCapital();
        assertThat(legacyShares.snapshots()).isNotEmpty();
        assertThat(legacyShares.parValue()).isEqualByComparingTo(nextShares.parValue());

        StatementColumn fy2022 = StatementColumn.CURRENT_PERIOD;
        StatementColumn comparative = StatementColumn.PRIOR_PERIOD;
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        compared += compare(legacy.incomeStatement(fy2022, Map.of(), legacyShares),
                next.incomeStatement(comparative, Map.of(), nextShares), mismatches);
        compared += compare(legacy.balanceSheet(fy2022, legacyShares), next.balanceSheet(comparative, nextShares), mismatches);
        compared += compare(legacy.cashFlow(fy2022), next.cashFlow(comparative), mismatches);
        // the FY2023 report reclassified Rp 36,509 million of FY2022 "other operating payments" to investing
        assertThat(mismatches).containsExactlyInAnyOrder(
                "cash_flow_statement.operating_cash_flow: FY2023 filing 13624195000000, FY2022 filing 13587686000000",
                "cash_flow_statement.investing_cash_flow: FY2023 filing -3899503000000, FY2022 filing -3862994000000");
        assertThat(compared).isGreaterThan(40);

        assertThat(legacy.depreciation(fy2022)).isNotNull();    // from "1611000 1 CurrentYear" / "1612000 1 CurrentYear"
        assertThat(legacy.segments(fy2022, null)).isEmpty();      // INDF leaves 1617000 / 1618000 blank
    }

    /** Revenue by type of the pre-2023 template: one table, the period dates above the value columns. */
    @ParameterizedTest
    @CsvSource({"GGRM, 3", "HRTA, 5", "INDY, 3"})
    void legacyRevenueSegmentsReconcileInBothColumns(String ticker, int segmentsFy2022) throws Exception {
        FilingMapper legacy = mapper(ticker, "2022-Tahunan");
        ShareCapital shares = legacy.shareCapital();
        for (StatementColumn column : legacy.columns()) {
            BigDecimal revenue = legacy.incomeStatement(column, Map.of(), shares).orElseThrow().values().get("revenue");
            SegmentExtraction segments = legacy.segments(column, revenue).orElseThrow();
            assertThat(segments.hasErrors()).as(ticker + " " + column + " " + segments.checks()).isFalse();
            assertThat(segments.lines().stream().map(SegmentExtraction.SegmentLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(revenue);
        }
        assertThat(legacy.segments(StatementColumn.CURRENT_PERIOD, null).orElseThrow().lines()).hasSize(segmentsFy2022);
    }

    /** FY2022 by the FY2022 filing (pre-2023 template) = FY2022 by the FY2023 filing's comparative. */
    @ParameterizedTest
    @CsvSource({"HRTA", "INDY"})
    void legacyRevenueSegmentsMatchTheNextFilingsComparative(String ticker) throws Exception {
        Map<String, BigDecimal> legacy = segmentRevenue(mapper(ticker, "2022-Tahunan"), StatementColumn.CURRENT_PERIOD);
        Map<String, BigDecimal> next = segmentRevenue(mapper(ticker, "2023-Tahunan"), StatementColumn.PRIOR_PERIOD);
        assertThat(legacy).isNotEmpty();
        assertThat(legacy.keySet()).isEqualTo(next.keySet());
        legacy.forEach((name, revenue) -> assertThat(revenue).as(ticker + " " + name).isEqualByComparingTo(next.get(name)));
    }

    private static Map<String, BigDecimal> segmentRevenue(FilingMapper mapper, StatementColumn column) {
        Map<String, BigDecimal> byName = new TreeMap<>();
        mapper.segments(column, null).orElseThrow().lines().forEach(l -> byName.put(l.name(), l.revenue()));
        return byName;
    }

    private static int compare(Optional<MappedStatement> legacy, Optional<MappedStatement> next, List<String> mismatches) {
        assertThat(legacy).isPresent();
        assertThat(next).isPresent();
        int compared = 0;
        for (var e : next.get().values().entrySet()) {
            // D&A of a comparative column comes from that period's own filing, not the next one
            if (List.of("depreciation", "amortization", "ebitda").contains(e.getKey())) {
                continue;
            }
            BigDecimal actual = legacy.get().values().get(e.getKey());
            BigDecimal want = e.getValue();
            compared++;
            boolean same = actual == null || want == null ? actual == want : actual.compareTo(want) == 0;
            if (!same) {
                mismatches.add(next.get().table() + "." + e.getKey() + ": FY2023 filing " + want + ", FY2022 filing " + actual);
            }
        }
        return compared;
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        return mapper(Path.of("..", "data", ticker, "xlsx").resolve("FinancialStatement-" + filing + "-" + ticker + ".xlsx"));
    }

    private static FilingMapper mapper(String fileName) throws Exception {
        return mapper(DATA.resolve(fileName));
    }

    private static FilingMapper mapper(Path file) throws Exception {
        String fileName = file.getFileName().toString();
        assumeTrue(Files.exists(file), "source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, fileName);
        }
        return new FilingMapper(workbook);
    }
}
