package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.IncomeLineCategory;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * BMRI FY2025 (2026-10-08): the agent classified "Claim expenses" of the FY2024 column, the column still failed its
 * check, and classifyIncomeLines had been withdrawn, so the agent could not correct its classification for three
 * rounds. A classification that leaves its column failing stays revisable.
 */
class ReclassificationTest {

    @Test
    void aClassificationThatLeavesItsColumnFailingCanBeRevised() throws Exception {
        Path file = Path.of("..", "data", "BMRI", "xlsx", "FinancialStatement-2025-Tahunan-BMRI.xlsx");
        assumeTrue(Files.exists(file), "BMRI source data not available: " + file);
        FilingMapper mapper;
        try (InputStream in = Files.newInputStream(file)) {
            mapper = new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString()));
        }
        IngestionSession session = new IngestionSession(mapper);
        Map<String, ToolCallback> catalog = new LinkedHashMap<>();
        catalog.put("classifyIncomeLines", mock(ToolCallback.class));
        catalog.put("extractStatements", mock(ToolCallback.class));

        session.markExtracted(StatementColumn.PRIOR_PERIOD);
        assertThat(session.hasUnclassifiedLines()).isFalse();
        assertThat(session.hasRevisableClassifications()).isFalse();
        assertThat(IngestionAgent.offeredTools(session, catalog)).doesNotContainKey("classifyIncomeLines");

        // the agent's classification deducts the claims a second time: the column fails, the tool stays offered
        session.classifications(StatementColumn.PRIOR_PERIOD).put("Claim expenses", IncomeLineCategory.COST_OF_REVENUE);
        assertThat(session.isRevisable(StatementColumn.PRIOR_PERIOD)).isTrue();
        assertThat(IngestionAgent.offeredTools(session, catalog)).containsKey("classifyIncomeLines");

        // revised to IGNORE, the column passes and the tool is withdrawn again
        session.classifications(StatementColumn.PRIOR_PERIOD).put("Claim expenses", IncomeLineCategory.IGNORE);
        assertThat(session.income(StatementColumn.PRIOR_PERIOD).orElseThrow().hasErrors()).isFalse();
        assertThat(session.hasRevisableClassifications()).isFalse();
        assertThat(IngestionAgent.offeredTools(session, catalog)).doesNotContainKey("classifyIncomeLines");
    }
}
