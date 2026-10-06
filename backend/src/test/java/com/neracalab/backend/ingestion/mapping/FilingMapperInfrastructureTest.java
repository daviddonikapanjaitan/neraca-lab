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
import com.neracalab.backend.ingestion.xlsx.IdxSheets;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * The "Infrastructure Industry" taxonomy (SMDR, transportation and logistics): the same roles and line
 * items as the General Industry one under sheet codes starting with 3 (3210000, 3311000, 3410000,
 * 3510000, ...), with a few own labels ("Payments for acquisition of property and equipment").
 */
class FilingMapperInfrastructureTest {

    @ParameterizedTest
    @ValueSource(strings = {"2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan", "2026-II"})
    void everySmdrFilingMapsWithoutErrors(String filing) throws Exception {
        FilingMapper mapper = mapper(filing);
        IngestionSession session = new IngestionSession(mapper);

        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(mapper.info().ticker()).isEqualTo("SMDR");
        assertThat(mapper.info().currency()).isEqualTo("USD");
        assertThat(mapper.balanceSheetSheet()).isEqualTo(IdxSheets.BALANCE_SHEET);     // read from 3210000
        for (StatementColumn column : mapper.columns()) {
            List<MappedStatement> statements = session.statements(column);
            assertThat(statements).isNotEmpty();
            for (MappedStatement s : statements) {
                assertThat(s.unclassified()).as(filing + " " + column + " " + s.table()).isEmpty();
                assertThat(s.checks().stream().filter(Check::isError).toList()).as(filing + " " + column + " " + s.table()).isEmpty();
            }
        }
    }

    /** Capex under the infrastructure labels: 80,318,765 acquisitions + 1,331,912 advances (FY2025). */
    @Test
    void capexIncludesTheInfrastructureLabels() throws Exception {
        FilingMapper mapper = mapper("2025-Tahunan");
        MappedStatement cash = mapper.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();

        assertThat(cash.values().get("capital_expenditure")).isEqualByComparingTo("-81650677");
        var segments = mapper.segments(StatementColumn.CURRENT_PERIOD, new BigDecimal("801694731")).orElseThrow();
        assertThat(segments.lines()).hasSize(5);
        assertThat(segments.hasErrors()).isFalse();
    }

    /** Each annual filing's comparative column equals the previous filing's current column (one reclassification). */
    @Test
    void comparativesMatchThePreviousFiling() throws Exception {
        List<String> years = List.of("2022-Tahunan", "2023-Tahunan", "2024-Tahunan", "2025-Tahunan");
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (int i = 1; i < years.size(); i++) {
            FilingMapper previous = mapper(years.get(i - 1));
            FilingMapper next = mapper(years.get(i));
            ShareCapital prevShares = previous.shareCapital();
            ShareCapital nextShares = next.shareCapital();
            List<MappedStatement> a = List.of(
                    previous.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), prevShares).orElseThrow(),
                    previous.balanceSheet(StatementColumn.CURRENT_PERIOD, prevShares).orElseThrow(),
                    previous.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow());
            List<MappedStatement> b = List.of(
                    next.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), nextShares).orElseThrow(),
                    next.balanceSheet(StatementColumn.PRIOR_PERIOD, nextShares).orElseThrow(),
                    next.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow());
            for (int s = 0; s < a.size(); s++) {
                for (var e : a.get(s).values().entrySet()) {
                    // D&A of a comparative column and the EPS (restated for the 2023 stock split) differ by design
                    if (List.of("depreciation", "amortization", "ebitda", "basic_eps", "diluted_eps").contains(e.getKey())) {
                        continue;
                    }
                    BigDecimal x = e.getValue();
                    BigDecimal y = b.get(s).values().get(e.getKey());
                    compared++;
                    boolean same = x == null || y == null ? x == y : x.compareTo(y) == 0;
                    if (!same) {
                        mismatches.add(years.get(i) + " " + a.get(s).table() + "." + e.getKey() + ": " + x + " vs " + y);
                    }
                }
            }
        }
        // the FY2025 filing moved 161,196 of FY2024 "Other income" (operating) to "Other gains (losses)"
        assertThat(mismatches).containsExactly("2025-Tahunan income_statement.operating_income: 97502289 vs 97341093");
        assertThat(compared).isGreaterThan(100);
    }

    /** SMDR's EPS has 3 decimals (0.003): no exact share count, so none is stored. */
    @Test
    void noShareCountIsGuessed() throws Exception {
        ShareCapital shares = mapper("2025-Tahunan").shareCapital();

        assertThat(shares.resolved()).isFalse();
        assertThat(shares.snapshots()).allSatisfy(s -> assertThat(s.sharesOutstanding()).isNull());
    }

    private static FilingMapper mapper(String filing) throws Exception {
        Path file = Path.of("..", "data", "SMDR", "xlsx", "FinancialStatement-" + filing + "-SMDR.xlsx");
        assumeTrue(Files.exists(file), "SMDR source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
