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

import com.neracalab.backend.ingestion.mapping.SegmentExtraction.SegmentLine;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * Filings that ended in the error log ({@code ingestion_error}) on 2026-10-13 although nothing is wrong with them:
 * <ul>
 *   <li>TOTL H1 2026 and FY2023 (errors 322, 323): "segments do not reconcile", both revenue breakdowns left open,
 *       the uploads INCOMPLETE. The breakdown does reconcile; it names several lines of one kind "Jasa Konstruksi",
 *       which was refused as a repeated segment name.</li>
 *   <li>MIDI H1 2026 (error 321): the H1 2025 cash flow statement failed the cash roll-forward by 79,630 million,
 *       the cash of a deconsolidated subsidiary, which the filing lists but does not count.</li>
 * </ul>
 */
class FilingMapperTotlMidiTest {

    /** H1 2026: service revenue 1 .. 4 are all "Jasa Konstruksi"; with "Lainnya" they are the filed revenue. */
    @Test
    void oneNameOnSeveralLinesOfTheSameKindIsOneSegment() throws Exception {
        SegmentExtraction current = segments("TOTL", "2026-II", StatementColumn.CURRENT_PERIOD);

        assertThat(current.hasErrors()).isFalse();
        assertThat(byName(current)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Jasa Konstruksi", new BigDecimal("2047709465000"),     // 331,002,951 + 306,031,443 + 451,135 + 1,410,223,936 thousand
                "Lainnya", new BigDecimal("10338237000")));
        assertThat(current.reportedTotal()).isEqualByComparingTo("2058047702000");
        assertThat(sum(current)).isEqualByComparingTo("2058047702000");
        assertThat(current.lines()).filteredOn(l -> l.name().equals("Jasa Konstruksi")).singleElement()
                .satisfies(l -> {
                    assertThat(l.filingType()).isEqualTo("SERVICE");
                    assertThat(l.residualSlot()).isFalse();
                });
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("segment_names") && c.severity() == Check.Severity.WARNING
                && c.message().contains("Jasa Konstruksi (jasa) on 4 lines"));
    }

    /**
     * The H1 2025 column also has an unnamed "Service revenue 5" of 924,785,317 thousand that the breakdown's own
     * total leaves out (as in MIKA's comparatives): not counted, and the four named lines are one segment.
     */
    @Test
    void theComparativeIsOneSegmentWithoutTheLineItsTotalExcludes() throws Exception {
        SegmentExtraction prior = segments("TOTL", "2026-II", StatementColumn.PRIOR_PERIOD);

        assertThat(prior.hasErrors()).isFalse();
        assertThat(byName(prior)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Jasa Konstruksi", new BigDecimal("1666743232000"),
                "Lainnya", new BigDecimal("8590270000")));
        assertThat(sum(prior)).isEqualByComparingTo("1675333502000");
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("segment_line") && c.message().contains("924785317000"));
    }

    /**
     * FY2023 files revenue by source: domestic revenue 1 .. 5, all "Jasa Konstruksi". Their sum is the one line
     * the FY2024 filing shows for FY2023, 3,006,816,008 thousand.
     */
    @Test
    void theSumOfTheLinesIsTheSegmentALaterFilingShows() throws Exception {
        SegmentExtraction fy2023 = segments("TOTL", "2023-Tahunan", StatementColumn.CURRENT_PERIOD);
        SegmentExtraction fy2022 = segments("TOTL", "2023-Tahunan", StatementColumn.PRIOR_PERIOD);
        SegmentExtraction fy2023InFy2024 = segments("TOTL", "2024-Tahunan", StatementColumn.PRIOR_PERIOD);

        assertThat(fy2023.hasErrors()).isFalse();
        assertThat(byName(fy2023)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Jasa Konstruksi", new BigDecimal("3006816008000"),
                "Lainnya", new BigDecimal("20367060000")));
        assertThat(byName(fy2023InFy2024).get("Jasa Konstruksi")).isEqualByComparingTo(byName(fy2023).get("Jasa Konstruksi"));
        assertThat(fy2023.lines()).allMatch(l -> l.filingType().equals("GEOGRAPHY"));

        assertThat(fy2022.hasErrors()).isFalse();
        assertThat(byName(fy2022)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Jasa Konstruksi", new BigDecimal("2256946056000"),
                "Lainnya", new BigDecimal("19869732000")));
        assertThat(sum(fy2022)).isEqualByComparingTo("2276815788000");
    }

    /** A name on lines of different kinds stays two segments, each the sum of its own lines. */
    @Test
    void linesOfDifferentKindsAreNotAddedUp() {
        List<SegmentLine> lines = List.of(
                new SegmentLine("A", "GEOGRAPHY", new BigDecimal("1"), false),
                new SegmentLine("A", "GEOGRAPHY", new BigDecimal("10"), false),
                new SegmentLine("A", "GEOGRAPHY", new BigDecimal("100"), false),
                new SegmentLine("B", "GEOGRAPHY", new BigDecimal("1000"), false));
        List<Check> checks = new ArrayList<>();

        List<SegmentLine> named = FilingMapper.distinctSegmentNames(lines, List.of("Pendapatan dari domestik 1",
                "Pendapatan dari domestik 2", "Pendapatan dari ekspor 1", "Pendapatan dari ekspor 2"), checks);

        assertThat(named).extracting(SegmentLine::name).containsExactly("A (domestik)", "A (ekspor)", "B");
        assertThat(named).extracting(SegmentLine::revenue).containsExactly(new BigDecimal("11"), new BigDecimal("100"),
                new BigDecimal("1000"));
        assertThat(checks).noneMatch(Check::isError);
    }

    /**
     * MIDI H1 2026, comparative H1 2025: beginning cash 298,489 + net increase 238,397 = ending cash 536,886
     * (million), as filed. The row 'Cash and cash equivalent of deconsolidated subsidiaries' -79,630 is listed
     * between them but not counted by the filing (the proceeds from the disposal, 200,455, are in investing).
     */
    @Test
    void aDeconsolidationLineTheFiledEndingCashExcludesIsNotCounted() throws Exception {
        MappedStatement prior = mapper("MIDI", "2026-II").cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();

        assertThat(prior.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(prior.values().get("cash_change")).isEqualByComparingTo("238397000000");
        assertThat(prior.values().get("ending_cash")).isEqualByComparingTo("536886000000");
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("cash_rollforward") && c.severity() == Check.Severity.WARNING
                && c.message().contains("-79630000000") && c.message().contains("not counted"));
        assertThat(prior.checks()).anyMatch(c -> c.rule().equals("cash_rollforward") && c.severity() == Check.Severity.OK);

        // the current column has no such row and passes as before, without the warning
        MappedStatement current = mapper("MIDI", "2026-II").cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(current.checks().stream().filter(Check::isError).toList()).isEmpty();
        assertThat(current.checks()).noneMatch(c -> c.rule().equals("cash_rollforward") && c.severity() == Check.Severity.WARNING);
    }

    private static Map<String, BigDecimal> byName(SegmentExtraction extraction) {
        return extraction.lines().stream().collect(java.util.stream.Collectors.toMap(SegmentLine::name, SegmentLine::revenue));
    }

    private static BigDecimal sum(SegmentExtraction extraction) {
        return extraction.lines().stream().map(SegmentLine::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static SegmentExtraction segments(String ticker, String filing, StatementColumn column) throws Exception {
        FilingMapper mapper = mapper(ticker, filing);
        BigDecimal revenue = mapper.incomeStatement(column, Map.of(), mapper.shareCapital()).orElseThrow().values().get("revenue");
        return mapper.segments(column, revenue).orElseThrow();
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
