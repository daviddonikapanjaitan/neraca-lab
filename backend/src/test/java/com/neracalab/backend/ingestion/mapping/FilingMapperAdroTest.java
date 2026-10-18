package com.neracalab.backend.ingestion.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * ADRO FY2023 tags only the gross profit on the face of its profit or loss; its revenue is in the breakdown by
 * type (6,517,556,000 USD) and its cost of sales in the notes.
 */
class FilingMapperAdroTest {

    @Test
    void adroRevenueFromItsBreakdownWhenOnlyGrossProfitIsTagged() throws Exception {
        FilingMapper fy2023 = mapper("ADRO", "2023-Tahunan");
        MappedStatement current = fy2023.incomeStatement(StatementColumn.CURRENT_PERIOD, Map.of(), fy2023.shareCapital()).orElseThrow();
        assertThat(current.hasErrors()).isFalse();
        assertThat(current.values().get("revenue")).isEqualByComparingTo("6517556000");
        assertThat(current.values().get("cost_of_revenue")).isEqualByComparingTo("3980272000");   // the notes' cost of sales
        assertThat(current.values().get("gross_profit")).isEqualByComparingTo("2537284000");
        assertThat(current.checks()).anyMatch(c -> c.rule().equals("revenue") && c.message().contains("revenue breakdown"));
        // the breakdown now reconciles with the revenue
        assertThat(fy2023.segments(StatementColumn.CURRENT_PERIOD, current.values().get("revenue")).orElseThrow().hasErrors()).isFalse();

        MappedStatement prior = fy2023.incomeStatement(StatementColumn.PRIOR_PERIOD, Map.of(), fy2023.shareCapital()).orElseThrow();
        assertThat(prior.values().get("revenue")).isEqualByComparingTo("8102399000");
        assertThat(prior.values().get("cost_of_revenue")).isEqualByComparingTo("3449427000");
    }

    private static FilingMapper mapper(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
    }
}
