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
import com.neracalab.backend.ingestion.xlsx.IdxSheets;
import com.neracalab.backend.ingestion.xlsx.IdxTaxonomy;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The "Property and Real Estate Industry" taxonomy (CBDK, a property developer): the General Industry
 * roles and line items under sheet codes starting with 2 (2210000, 2311000, 2410000, 2510000, ...), plus
 * real estate assets (a developer's inventory). Amounts are filed in IDR thousands.
 */
class FilingMapperPropertyTest {

    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    @ParameterizedTest
    @ValueSource(strings = {"2024-Tahunan", "2025-Tahunan", "2026-II"})
    void everyCbdkFilingMapsWithoutErrors(String filing) throws Exception {
        FilingMapper mapper = mapper(filing);
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(mapper.taxonomy()).isEqualTo(IdxTaxonomy.PROPERTY);
        assertThat(mapper.info().ticker()).isEqualTo("CBDK");
        assertThat(mapper.info().currency()).isEqualTo("IDR");
        assertThat(mapper.balanceSheetSheet()).isEqualTo(IdxSheets.BALANCE_SHEET);     // read from 2210000
        for (StatementColumn column : mapper.columns()) {
            List<MappedStatement> statements = session.statements(column);
            assertThat(statements).isNotEmpty();
            for (MappedStatement s : statements) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
            }
        }
    }

    /** FY2025: real estate assets are the inventory, investment properties are capex. */
    @Test
    void realEstateIsInventoryAndInvestmentPropertiesAreCapex() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");
        Map<String, BigDecimal> b = mapper.balanceSheet(StatementColumn.CURRENT_PERIOD, mapper.shareCapital()).orElseThrow().values();
        Map<String, BigDecimal> i = mapper.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), mapper.shareCapital())
                .orElseThrow().values();
        Map<String, BigDecimal> c = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow().values();

        assertThat(b.get("total_assets")).isEqualByComparingTo(k(22576275771L));
        assertThat(b.get("inventory")).isEqualByComparingTo(k(6909521248L));          // current real estate assets
        assertThat(b.get("deferred_revenue")).isEqualByComparingTo(k(8410794418L));   // advances from home buyers
        assertThat(i.get("revenue")).isEqualByComparingTo(k(2503637505L));
        assertThat(i.get("net_income_to_parent")).isEqualByComparingTo(k(1364251522L));
        // 163,857,454 PP&E + 25,577,143 advances + 1,147,537,164 investment properties
        assertThat(c.get("capital_expenditure")).isEqualByComparingTo(k(-1336971761L));
        assertThat(c.get("operating_cash_flow")).isEqualByComparingTo(k(719356366L));
    }

    /**
     * The FY2025 filing restates FY2024 (total assets 19,081,600,423 -> 20,269,688,934 thousand): each filing
     * is mapped as filed, and the comparative column never replaces the stored FY2024 values.
     */
    @Test
    void fy2024IsRestatedInTheFy2025Filing() throws Exception {
        FilingMapper fy2024 = mapper("2024-Tahunan");
        FilingMapper fy2025 = mapper("2025-Tahunan");
        Map<String, BigDecimal> asFiled = fy2024.balanceSheet(StatementColumn.CURRENT_PERIOD, fy2024.shareCapital())
                .orElseThrow().values();
        Map<String, BigDecimal> restated = fy2025.balanceSheet(StatementColumn.PRIOR_PERIOD, fy2025.shareCapital())
                .orElseThrow().values();

        assertThat(asFiled.get("total_assets")).isEqualByComparingTo(k(19081600423L));
        assertThat(restated.get("total_assets")).isEqualByComparingTo(k(20269688934L));
        // revenue is unchanged by the restatement
        assertThat(fy2024.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2024.shareCapital()).orElseThrow()
                .values().get("revenue")).isEqualByComparingTo(fy2025.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(),
                fy2025.shareCapital()).orElseThrow().values().get("revenue"));
    }

    private static BigDecimal k(long thousands) {
        return BigDecimal.valueOf(thousands).multiply(THOUSAND);
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", "CBDK", "xlsx", "FinancialStatement-" + filing + "-CBDK.xlsx");
        assumeTrue(Files.exists(file), "CBDK source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
