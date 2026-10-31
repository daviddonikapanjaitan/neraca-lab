package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.PeriodRef;
import com.neracalab.backend.ingestion.mapping.SegmentExtraction;
import com.neracalab.backend.ingestion.mapping.SegmentExtraction.SegmentLine;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.persistence.IngestionRepository.SegmentRow;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

import tools.jackson.databind.json.JsonMapper;

/**
 * The fast path: a filing whose every column maps without a failed check or an unclassified line is stored without
 * the agent (85% of the workbooks of 2026-10-31), the deterministic verification decides; a filing with a judgement
 * left goes to the agent as before. HRTA H1 2026 against the Docker Postgres; rolled back.
 */
@SpringBootTest
@Transactional
class IngestionAgentFastPathTest {

    @Autowired
    private IngestionRepository repository;

    @Autowired
    private IngestionVerifier verifier;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aCleanFilingIsStoredWithoutTheAgent() throws Exception {
        IngestionSession session = session("HRTA", "2026-II");
        assumeTrue(!jdbc.sql("SELECT company_id FROM company WHERE ticker = 'HRTA'").query(Long.class).list().isEmpty(),
                "HRTA is not in the database");
        assertThat(IngestionAgent.mapsCleanly(session)).isTrue();
        List<String> stages = new ArrayList<>();
        // only the English names of segments HRTA does not have yet may be asked; the planner, executor and reviewer never
        ChatModel model = prompt -> {
            throw new AssertionError("the agent's model was called: " + prompt.getContents());
        };
        session.modelCalls((stage, asked, response, durationMs, error) -> stages.add(stage));

        IngestionAgent.Outcome outcome = agent(model).run(session);

        assertThat(stages).isEmpty();
        assertThat(outcome.verification().complete()).as(outcome.verification().toString()).isTrue();
        assertThat(outcome.planFromModel()).isFalse();
        assertThat(outcome.rounds()).singleElement().satisfies(r -> assertThat(r.finalMessage()).contains("fast path"));
        assertThat(session.notes()).anyMatch(n -> n.startsWith("Stored by the fast path without the agent"));
        assertThat(session.savedStatements().keySet()).containsExactlyInAnyOrderElementsOf(session.mapper().columns());
        assertThat(session.isDerivedCurrent()).isTrue();
    }

    /** A new company's segments are named by one model call; nothing else is asked. */
    @Test
    void theOnlyModelCallNamesTheNewSegments() throws Exception {
        IngestionSession session = session("HRTA", "2026-II");
        assumeTrue(!jdbc.sql("SELECT company_id FROM company WHERE ticker = 'HRTA'").query(Long.class).list().isEmpty(),
                "HRTA is not in the database");
        assumeTrue(session.segments(StatementColumn.CURRENT_PERIOD).isPresent(), "HRTA H1 2026 has a breakdown");
        // as for a company that has no segments yet
        jdbc.sql("DELETE FROM segment_financial WHERE company_id = (SELECT company_id FROM company WHERE ticker = 'HRTA')").update();
        jdbc.sql("DELETE FROM segment WHERE company_id = (SELECT company_id FROM company WHERE ticker = 'HRTA')").update();
        List<String> asked = new ArrayList<>();
        ChatModel model = prompt -> {
            asked.add(prompt.getContents());
            StringBuilder names = new StringBuilder("{\"segments\": [");
            String sep = "";
            for (SegmentLine line : session.mapper().columns().stream().flatMap(c -> session.segments(c).stream())
                    .flatMap(e -> e.lines().stream()).toList()) {
                names.append(sep).append("{\"segmentName\": ").append(JsonMapper.builder().build().writeValueAsString(line.name()))
                        .append(", \"segmentNameEn\": \"EN ").append(line.name().length()).append("\", \"segmentType\": \"")
                        .append(line.filingType()).append("\"}");
                sep = ", ";
            }
            names.append("]}");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(names.toString()))));
        };
        List<String> stages = new ArrayList<>();
        session.modelCalls((stage, m, response, durationMs, error) -> stages.add(stage));

        IngestionAgent.Outcome outcome = agent(model).run(session);

        assertThat(stages).containsOnly(ModelCallListener.SEGMENT_NAMES);
        assertThat(asked).hasSize(1);
        assertThat(outcome.verification().complete()).as(outcome.verification().toString()).isTrue();
        List<String> english = jdbc.sql("""
                        SELECT segment_name_en FROM segment WHERE company_id = (SELECT company_id FROM company WHERE ticker = 'HRTA')""")
                .query(String.class).list();
        assertThat(english).isNotEmpty().allMatch(n -> n.startsWith("EN "));
    }

    /** A failed check or a judgement left (or a template the model maps by design): the agent runs, as before. */
    @Test
    void aFilingWithAFailedCheckIsNotForTheFastPath() throws Exception {
        // BUDI FY2025: its FY2024 column files 1,052 million on two rows (dev_context 157): the income check fails
        assertThat(IngestionAgent.mapsCleanly(session("BUDI", "2025-Tahunan"))).isFalse();
        // BMBL FY2023: a row missing on the face, profit before tax off by 94,581,941 (dev_context 153)
        assertThat(IngestionAgent.mapsCleanly(session("BMBL", "2023-Tahunan"))).isFalse();
        // BTEL FY2024: a profit or loss by nature, which the model maps by design
        assertThat(IngestionAgent.mapsCleanly(session("BTEL", "2024-Tahunan"))).isFalse();
    }

    /** The model's English name for a new segment; its type only where saveRevenueSegments would accept it. */
    @Test
    void theModelsNamesAreUsedForNewSegmentsOnly() {
        SegmentExtraction extraction = new SegmentExtraction(StatementColumn.CURRENT_PERIOD,
                PeriodRef.fullYearEnding(java.time.LocalDate.parse("2025-12-31")), "1618000", List.of(
                        new SegmentLine("Penjualan perhiasan", "PRODUCT", BigDecimal.TEN, false),
                        new SegmentLine("Jasa", "SERVICE", BigDecimal.ONE, false),
                        new SegmentLine("Lainnya", "SERVICE", BigDecimal.ONE, true),
                        new SegmentLine("Ekspor", "GEOGRAPHY", BigDecimal.ONE, false)), BigDecimal.valueOf(13), List.of());
        Map<String, IngestionTools.SegmentNaming> named = Map.of(
                "Penjualan perhiasan", new IngestionTools.SegmentNaming("Penjualan perhiasan", "Jewellery sales", IngestionTools.SegmentType.PRODUCT),
                "Jasa", new IngestionTools.SegmentNaming("Jasa", "Services", IngestionTools.SegmentType.OTHER),
                "Lainnya", new IngestionTools.SegmentNaming("Lainnya", "Fair-value gains", IngestionTools.SegmentType.OTHER),
                "Ekspor", new IngestionTools.SegmentNaming("Ekspor", "Something else", IngestionTools.SegmentType.GEOGRAPHY));

        var namings = DeterministicFinisher.namings(extraction, List.of(new SegmentRow(1, "GEOGRAPHY", "Ekspor", "Export")), named);

        assertThat(namings).extracting(IngestionTools.SegmentNaming::segmentNameEn)
                .containsExactly("Jewellery sales", "Services", "Fair-value gains", "Export");   // a known segment keeps its name
        assertThat(namings).extracting(IngestionTools.SegmentNaming::segmentType).containsExactly(
                IngestionTools.SegmentType.PRODUCT,
                IngestionTools.SegmentType.SERVICE,     // OTHER only in the residual slot
                IngestionTools.SegmentType.OTHER,
                IngestionTools.SegmentType.GEOGRAPHY);
    }

    private IngestionAgent agent(ChatModel model) {
        return new IngestionAgent(model, repository, verifier, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)),
                JsonMapper.builder().build(), ConfiguredChatModel.name(), false, Duration.ZERO, List.of(), null, true);
    }

    private static IngestionSession session(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        try (InputStream in = Files.newInputStream(file)) {
            return new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
    }
}
