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
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps the six HRTA filings in data/HRTA/xlsx and compares every field with the values that were
 * validated for V1.0.4__data_HRTA_financials.sql (src/test/resources/ingestion/hrta_expected.json).
 */
class FilingMapperHrtaTest {

    private static final Path DATA = Path.of("..", "data", "HRTA", "xlsx");
    private static final JsonNode EXPECTED = loadExpected();

    static Stream<String> filings() {
        return Stream.of("2025-I", "2025-II", "2025-III", "2025-Tahunan", "2026-I", "2026-II");
    }

    @ParameterizedTest
    @MethodSource("filings")
    void mapsEveryColumnExactly(String filing) throws Exception {
        Path file = DATA.resolve("FinancialStatement-" + filing + "-HRTA.xlsx");
        assumeTrue(Files.exists(file), "HRTA source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        FilingMapper mapper = new FilingMapper(workbook);
        assertThat(mapper.templateProblems()).isEmpty();
        assertThat(mapper.info().ticker()).isEqualTo("HRTA");
        assertThat(mapper.info().unitMultiplier()).isEqualByComparingTo("1");
        assertThat(mapper.info().audited()).isEqualTo(filing.equals("2025-Tahunan"));

        ShareCapital shares = mapper.shareCapital();
        assertThat(shares.parValue()).isEqualByComparingTo(EXPECTED.get("_meta").get("par_value").asString());
        assertThat(shares.snapshots()).allSatisfy(s ->
                assertThat(s.sharesOutstanding()).isEqualByComparingTo(EXPECTED.get("_meta").get("shares_outstanding").asString()));

        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (StatementColumn column : mapper.columns()) {
            PeriodRef period = mapper.period(column);
            JsonNode expected = EXPECTED.get(period.fiscalYear() + "-" + period.periodType());
            assertThat(expected).as("expected values for " + period.key()).isNotNull();

            var income = mapper.incomeStatement(column, Map.of(), shares);
            var balance = mapper.balanceSheet(column, shares);
            var cash = mapper.cashFlow(column);
            assertThat(income.isPresent()).as("income " + column).isEqualTo(mapper.durationIndex(column) >= 0);
            assertThat(cash.isPresent()).as("cash flow " + column).isEqualTo(mapper.durationIndex(column) >= 0);
            assertThat(balance.isPresent()).as("balance " + column).isEqualTo(mapper.instantIndex(column) >= 0);

            for (var statement : Stream.of(income, balance, cash).flatMap(java.util.Optional::stream).toList()) {
                assertThat(statement.unclassified()).as(statement.table() + " " + column + " unclassified").isEmpty();
                assertThat(statement.checks().stream().filter(Check::isError).toList())
                        .as(statement.table() + " " + column + " errors").isEmpty();
                JsonNode table = expected.get(statement.table());
                assertThat(table).as(statement.table() + " expected for " + period.key()).isNotNull();
                for (var field : table.properties()) {
                    BigDecimal actual = statement.values().get(field.getKey());
                    String want = field.getValue().isNull() ? null : field.getValue().asString();
                    compared++;
                    // a comparative column's own filing has no D&A roll-forward for that period: the
                    // mapper leaves it empty and the value comes from the period's own filing instead
                    boolean daFromOwnFiling = column.isComparative() && actual == null
                            && List.of("depreciation", "amortization", "ebitda").contains(field.getKey());
                    if (!daFromOwnFiling && !same(actual, want)) {
                        mismatches.add(filing + " " + column + " " + statement.table() + "." + field.getKey()
                                + ": expected " + want + " but was " + (actual == null ? null : actual.toPlainString()));
                    }
                }
            }

            BigDecimal revenue = income.map(i -> i.values().get("revenue")).orElse(null);
            var segments = mapper.segments(column, revenue);
            assertThat(segments.isPresent()).isEqualTo(mapper.durationIndex(column) >= 0);
            if (segments.isPresent()) {
                assertThat(segments.get().hasErrors()).as("segment errors " + column).isFalse();
                Map<String, String> actual = new TreeMap<>();
                segments.get().lines().forEach(l -> actual.put(l.name(), l.revenue().toPlainString()));
                Map<String, String> want = new TreeMap<>();
                expected.get("segments").properties().forEach(e -> want.put(e.getKey(), e.getValue().asString()));
                assertThat(actual).as("segments " + filing + " " + column).isEqualTo(want);
            }
        }
        assertThat(mismatches).isEmpty();
        assertThat(compared).isGreaterThan(80);
    }

    private static boolean same(BigDecimal actual, String expected) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        return actual.compareTo(new BigDecimal(expected)) == 0;
    }

    private static JsonNode loadExpected() {
        try (InputStream in = FilingMapperHrtaTest.class.getResourceAsStream("/ingestion/hrta_expected.json")) {
            return JsonMapper.builder().build().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
