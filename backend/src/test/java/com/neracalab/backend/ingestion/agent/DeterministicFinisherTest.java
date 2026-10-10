package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
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
import com.openai.errors.OpenAIInvalidDataException;

import tools.jackson.databind.json.JsonMapper;

/**
 * A model that keeps failing (every call a read timeout) after the plan: the agent finishes the standard steps
 * without it and the deterministic verification passes. HRTA H1 2026 against the Docker Postgres; rolled back.
 */
@SpringBootTest
@Transactional
class DeterministicFinisherTest {

    private static final Path WORKBOOK = Path.of("..", "data", "IDX_XBRL", "HRTA", "xlsx", "FinancialStatement-2026-II-HRTA.xlsx");

    @Autowired
    private IngestionRepository repository;

    @Autowired
    private IngestionVerifier verifier;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aFailingModelDoesNotFailAStandardFiling() throws Exception {
        assumeTrue(Files.exists(WORKBOOK), "HRTA source data not available");
        assumeTrue(!jdbc.sql("SELECT company_id FROM company WHERE ticker = 'HRTA'").query(Long.class).list().isEmpty(),
                "HRTA is not in the database");
        IngestionSession session;
        try (InputStream in = Files.newInputStream(WORKBOOK)) {
            session = new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, WORKBOOK.getFileName().toString())));
        }
        ChatModel down = prompt -> {
            throw new OpenAIInvalidDataException("Error reading response", new InterruptedIOException("timeout"));
        };
        IngestionAgent agent = new IngestionAgent(down, repository, verifier,
                new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)), JsonMapper.builder().build(), ConfiguredChatModel.name());

        IngestionAgent.Outcome outcome = agent.run(session);

        assertThat(outcome.verification().complete()).as(outcome.verification().toString()).isTrue();
        assertThat(session.savedStatements().keySet()).containsExactlyInAnyOrderElementsOf(session.mapper().columns());
        assertThat(session.isDerivedCurrent()).isTrue();
        assertThat(outcome.notes()).anyMatch(n -> n.contains("done without it") && n.contains("saveStatements(CURRENT_PERIOD) done"));
        assertThat(outcome.rounds()).singleElement().satisfies(r -> assertThat(r.reflection().complete()).isTrue());
    }

    @Test
    void displayNames() {
        assertThat(DeterministicFinisher.displayName("PT Wilmar Cahaya Indonesia Tbk", "CEKA")).isEqualTo("Wilmar Cahaya Indonesia");
        assertThat(DeterministicFinisher.displayName("PT. Hartadinata Abadi, Tbk.", "HRTA")).isEqualTo("Hartadinata Abadi");
        assertThat(DeterministicFinisher.displayName("PT Tbk", "XXXX")).isEqualTo("XXXX");
        assertThat(DeterministicFinisher.displayName(null, "XXXX")).isEqualTo("XXXX");
    }

    @Test
    void segmentsKeepTheFilingsNamesAndTypes() {
        SegmentExtraction extraction = new SegmentExtraction(StatementColumn.CURRENT_PERIOD,
                PeriodRef.fullYearEnding(java.time.LocalDate.parse("2025-12-31")), "1618000", List.of(
                        new SegmentLine("Produk Tepung", "GEOGRAPHY", BigDecimal.TEN, false),
                        new SegmentLine("Jasa", "SERVICE", BigDecimal.ONE, false)), BigDecimal.valueOf(11), List.of());

        var namings = DeterministicFinisher.namings(extraction, List.of(new SegmentRow(1, "GEOGRAPHY", "Produk Tepung", "Flour products")));

        assertThat(namings).extracting(IngestionTools.SegmentNaming::segmentNameEn).containsExactly("Flour products", "Jasa");
        assertThat(namings).extracting(IngestionTools.SegmentNaming::segmentType)
                .containsExactly(IngestionTools.SegmentType.GEOGRAPHY, IngestionTools.SegmentType.SERVICE);
    }
}
