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
import org.junit.jupiter.params.provider.ValueSource;

import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * ASGR (Astra Graphia): its H1 2026 filing reports one H1 2025 loss twice, as "Other expenses" 2,750 and as
 * "Other gains (losses)" -2,750 (IDR millions); profit before tax 139,690 counts it once.
 */
class FilingMapperAsgrTest {

    @ParameterizedTest
    @ValueSource(strings = {"2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II"})
    void everyAsgrFilingMapsWithoutErrors(String filing) throws Exception {
        FilingMapper mapper = mapper(filing);
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        for (StatementColumn column : mapper.columns()) {
            for (MappedStatement s : session.statements(column)) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
            }
        }
    }

    @Test
    void doubleReportedLossIsCountedOnce() throws Exception {
        FilingMapper mapper = mapper("2026-II");
        MappedStatement h1 = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(h1.hasErrors()).isFalse();
        assertThat(h1.checks()).anyMatch(c -> c.severity() == Check.Severity.WARNING && c.rule().equals("double_reported")
                && c.message().contains("'Other expenses'") && c.message().contains("'Other gains (losses)'"));
        assertThat(h1.values().get("pretax_income")).isEqualByComparingTo(m(139690));
        // 321,474 gross profit - 63,522 selling - 141,580 G&A ("Other expenses" not counted again)
        assertThat(h1.values().get("operating_income")).isEqualByComparingTo(m(116372));
        assertThat(h1.values().get("operating_expenses")).isEqualByComparingTo(m(205102));
        assertThat(h1.derivations().get("double_reported")).contains("'Other expenses' ignored");
    }

    /**
     * ASGR's FY2023 workbook declares "Jutaan / In Million" but carries full amounts (total assets
     * 2,682,813,000,000): read as full amounts, not a million times too large.
     */
    @Test
    void fullAmountsUnderAMillionsLabelAreNotScaledTwice() throws Exception {
        FilingMapper mapper = mapper("2023-Tahunan");
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.info().unitMultiplier()).isEqualByComparingTo("1000000");
        assertThat(mapper.unit()).isEqualByComparingTo("1");
        assertThat(mapper.warnings()).singleElement().asString().contains("full amounts").contains("1348");
        assertThat(session.notes()).anyMatch(n -> n.contains("full amounts"));
        Map<String, BigDecimal> balance = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital()).orElseThrow().values();
        assertThat(balance.get("total_assets")).isEqualByComparingTo(m(2682813));
        Map<String, BigDecimal> income = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow().values();
        assertThat(income.get("revenue")).isEqualByComparingTo(m(2968952));
        assertThat(income.get("net_income_to_parent")).isEqualByComparingTo(m(141073));
        assertThat(income.get("basic_eps")).isEqualByComparingTo("104.58");
        // the same FY2022 figures as the FY2022 workbook, which is filed in millions
        FilingMapper fy2022 = mapper("2022-Tahunan");
        assertThat(fy2022.unit()).isEqualByComparingTo("1000000");
        assertThat(fy2022.warnings()).isEmpty();
        assertThat(mapper.balanceSheet(StatementColumn.PRIOR_PERIOD, mapper.shareCapital()).orElseThrow().values().get("total_assets"))
                .isEqualByComparingTo(fy2022.balanceSheet(StatementColumn.CURRENT_PERIOD, fy2022.shareCapital()).orElseThrow()
                        .values().get("total_assets"));
    }

    /**
     * SIMP (Salim Ivomas Pratama): its FY2023 workbook carries full amounts under "In Million" and tags no revenue
     * (only gross profit); its H1 2026 workbook files the EPS in millions (0.0000563514954 for Rp 56.35).
     */
    @Test
    void simpFilingQuirks() throws Exception {
        FilingMapper fy2023 = simp("2023-Tahunan");
        assertThat(fy2023.templateProblems()).isEmpty();
        assertThat(fy2023.unit()).isEqualByComparingTo("1");
        MappedStatement income = fy2023.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2023.shareCapital()).orElseThrow();
        assertThat(income.hasErrors()).isFalse();
        assertThat(income.checks()).anyMatch(c -> c.rule().equals("revenue") && c.severity() == Check.Severity.WARNING);
        assertThat(income.values().get("revenue")).isNull();
        assertThat(income.values().get("cost_of_revenue")).isNull();
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo(m(3358216));
        assertThat(income.values().get("pretax_income")).isEqualByComparingTo(m(1487689));
        // FY2024's comparative carries the FY2023 revenue the FY2023 filing did not tag
        FilingMapper fy2024 = simp("2024-Tahunan");
        assertThat(fy2024.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), fy2024.shareCapital()).orElseThrow()
                .values().get("revenue")).isEqualByComparingTo(m(16002643));

        FilingMapper h1 = simp("2026-II");
        assertThat(h1.templateProblems()).isEmpty();
        assertThat(h1.unit()).isEqualByComparingTo("1000000");
        assertThat(h1.warnings()).singleElement().asString().contains("EPS is filed in its rounding unit");
        MappedStatement h1Income = h1.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), h1.shareCapital()).orElseThrow();
        assertThat(h1Income.hasErrors()).isFalse();
        assertThat(h1Income.values().get("basic_eps")).isEqualByComparingTo("56.35149545425516");
        assertThat(h1Income.values().get("revenue")).isEqualByComparingTo(m(9624696));
        assertThat(h1.shareCapital().resolved()).isTrue();
        for (String filing : List.of("2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II")) {
            FilingMapper m = simp(filing);
            IngestionSession session = new IngestionSession(m);
            for (StatementColumn column : m.columns()) {
                for (MappedStatement s : session.statements(column)) {
                    assertThat(s.hasErrors()).as(filing + " " + column + " " + s.table()).isFalse();
                }
            }
        }
    }

    private static FilingMapper simp(String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "SIMP", "xlsx", "FinancialStatement-" + filing + "-SIMP.xlsx");
        assumeTrue(Files.exists(file), "SIMP source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }

    /** The current column reports the line once: nothing is changed there. */
    @Test
    void singleReportIsKept() throws Exception {
        FilingMapper mapper = mapper("2026-II");
        MappedStatement h1 = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital()).orElseThrow();

        assertThat(h1.checks()).noneMatch(c -> c.rule().equals("double_reported")).noneMatch(Check::isError);
        assertThat(h1.values().get("pretax_income")).isEqualByComparingTo(m(173680));
    }

    /** A classification given by the agent is never overridden by the rule. */
    @Test
    void agentClassificationIsKept() throws Exception {
        FilingMapper mapper = mapper("2026-II");
        MappedStatement h1 = mapper.incomeStatement(StatementColumn.PRIOR_PERIOD,
                Map.of("Other expenses", IncomeLineCategory.OTHER_OPERATING_EXPENSE), mapper.shareCapital()).orElseThrow();

        assertThat(h1.hasErrors()).isTrue();
        assertThat(h1.checks()).noneMatch(c -> c.rule().equals("double_reported"));
        assertThat(List.of(h1.values().get("pretax_income"))).containsExactly(m(139690));
    }

    private static BigDecimal m(long millions) {
        return BigDecimal.valueOf(millions).multiply(new BigDecimal("1000000"));
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "ASGR", "xlsx", "FinancialStatement-" + filing + "-ASGR.xlsx");
        assumeTrue(Files.exists(file), "ASGR source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
