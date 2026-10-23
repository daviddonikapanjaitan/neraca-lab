package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.neracalab.backend.ingestion.IngestionResponse.Status;
import com.neracalab.backend.ingestion.agent.AgentTrace;
import com.neracalab.backend.ingestion.agent.FallbackProperties;
import com.neracalab.backend.ingestion.agent.FullTextExtractor;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.agent.IngestionVerifier.Verification;
import com.neracalab.backend.ingestion.agent.JobDeadline;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

/**
 * When the full-text extraction takes over from the standard extraction, with scripted stages and the real TINS
 * workbooks (no model, no database): an unsupported workbook, an INCOMPLETE result, a standard extraction past
 * its time, a failure - and never after a clean result or when it is switched off.
 */
class IngestionServiceFallbackTest {

    private static final Path DATA = Path.of("..", "data", "IDX_XBRL", "TINS", "xlsx");
    private static final String BLOCKED = "saveStatements(PRIOR_PERIOD) is blocked: income_statement failed checks";

    private final IngestionAgent agent = mock(IngestionAgent.class);
    private final IngestionRepository repository = mock(IngestionRepository.class);
    private final FullTextExtractor fullText = mock(FullTextExtractor.class);
    private final List<String> stages = new ArrayList<>();
    private IngestionService service;

    @BeforeEach
    void wire() {
        fallback(true);
        when(repository.findCompany(anyString())).thenReturn(Optional.empty());
        service = new IngestionService(new IdxWorkbookReader(), agent, repository, mock(WebShareCounts.class), fullText);
    }

    @Test
    void aCleanResultNeedsNoFullTextExtraction() throws Exception {
        IngestionSession session = session("2025-Tahunan");
        when(agent.run(session)).thenReturn(outcome(complete()));

        IngestionResponse response = service.run(session, stages::add);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.fallback()).isNull();
        assertThat(stages).isEmpty();
        verify(fullText, never()).run(any(), any());
    }

    @Test
    void anIncompleteResultIsTakenOverAndCanEndComplete() throws Exception {
        IngestionSession session = session("2025-Tahunan");
        when(agent.run(session)).thenReturn(outcome(incomplete(BLOCKED)));
        when(fullText.run(any(), any())).thenReturn(result(complete(), List.of("PRIOR_PERIOD"), 1));

        IngestionResponse response = service.run(session, stages::add);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.verification().complete()).isTrue();
        assertThat(response.fallback().trigger()).isEqualTo("INCOMPLETE");
        assertThat(response.fallback().reason()).isEqualTo(BLOCKED);
        assertThat(response.fallback().columns()).containsExactly("PRIOR_PERIOD");
        assertThat(response.metrics().modelCalls()).isEqualTo(1);
        assertThat(stages).singleElement().asString().startsWith("Full-text extraction of TINS 2025 FY: the model maps the text "
                + "of the whole workbook (the standard extraction left open items; stopped after 3 minutes)");
        verify(fullText).run(session, BLOCKED);
        // the full-text extraction runs on a time limit of its own
        assertThat(session.deadline().limit()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void anUnsupportedWorkbookSkipsTheAgentAndGoesToTheFullTextExtraction() throws Exception {
        IngestionSession session = session("2024-Tahunan");
        String missing = "CURRENT_PERIOD 2024 FY: the workbook has no figures for this period";
        when(fullText.run(any(), any())).thenReturn(result(incomplete(missing), List.of("CURRENT_PERIOD", "PRIOR_PERIOD"), 1));

        IngestionResponse response = service.run(session, stages::add);

        verify(agent, never()).run(any());
        assertThat(response.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(response.error()).isNull();
        assertThat(response.fallback().trigger()).isEqualTo("UNSUPPORTED_WORKBOOK");
        assertThat(response.fallback().reason()).isEqualTo(
                "Unsupported filing: No balance sheet data (1210000); No profit or loss data (1311000 / 1321000)");
        assertThat(response.verification().problems()).containsExactly(missing);
        assertThat(stages.getFirst()).contains("the standard extraction cannot read this workbook");
    }

    @Test
    void aStandardExtractionPastItsTimeIsTakenOver() throws Exception {
        IngestionSession session = session("2025-Tahunan");
        session.deadline(JobDeadline.after(Duration.ofMinutes(3)));
        when(agent.run(session)).thenThrow(new JobDeadline.JobTimeoutException(Duration.ofMinutes(3), "a model call"));
        when(fullText.run(any(), any())).thenReturn(result(complete(), List.of(), 0));

        IngestionResponse response = service.run(session, stages::add);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.fallback().trigger()).isEqualTo("SLOW_EXTRACTION");
        assertThat(response.fallback().reason()).isEqualTo("The standard extraction ran longer than 3 minutes");
        assertThat(stages.getFirst()).contains("the standard extraction took longer than 3 minutes");
    }

    @Test
    void aFailedStandardExtractionIsTakenOver() throws Exception {
        IngestionSession session = session("2025-Tahunan");
        when(agent.run(session)).thenThrow(new IllegalStateException("model offline"));
        when(fullText.run(any(), any())).thenReturn(result(complete(), List.of(), 0));

        IngestionResponse response = service.run(session, stages::add);

        assertThat(response.status()).isEqualTo(Status.COMPLETED);
        assertThat(response.fallback().trigger()).isEqualTo("EXTRACTION_FAILED");
        assertThat(response.fallback().reason()).isEqualTo("IllegalStateException: model offline");
    }

    @Test
    void aFullTextExtractionThatFailsLeavesWhatTheStandardExtractionReached() throws Exception {
        IngestionSession session = session("2025-Tahunan");
        when(agent.run(session)).thenReturn(outcome(incomplete(BLOCKED)));
        when(fullText.run(any(), any())).thenThrow(new IllegalStateException("no answer"));

        IngestionResponse response = service.run(session, stages::add);

        assertThat(response.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(response.verification().pending()).containsExactly(BLOCKED);
        assertThat(response.fallback().error()).isEqualTo("IllegalStateException: no answer");
    }

    @Test
    void aFullTextExtractionPastItsTimeFailsTheUpload() throws Exception {
        IngestionSession session = session("2025-Tahunan");
        when(agent.run(session)).thenReturn(outcome(incomplete(BLOCKED)));
        when(fullText.run(any(), any())).thenThrow(new JobDeadline.JobTimeoutException(Duration.ofMinutes(3), "a model call"));

        IngestionResponse response = service.run(session, stages::add);

        assertThat(response.status()).isEqualTo(Status.FAILED);
        assertThat(response.error()).contains("exceeded its time limit of 3 minutes");
        assertThat(response.fallback().error()).contains("exceeded its time limit of 3 minutes");
    }

    @Test
    void switchedOffAnUploadEndsWhereTheStandardExtractionEnds() throws Exception {
        fallback(false);
        IngestionSession session = session("2025-Tahunan");
        when(agent.run(session)).thenReturn(outcome(incomplete(BLOCKED)));

        IngestionResponse response = service.run(session, stages::add);

        assertThat(response.status()).isEqualTo(Status.INCOMPLETE);
        assertThat(response.fallback()).isNull();
        verify(fullText, never()).run(any(), any());
        // and the upload endpoint refuses a workbook nothing could store, as before
        byte[] unsupported = Files.readAllBytes(file("2024-Tahunan"));
        assertThatThrownBy(() -> service.check(unsupported, "FinancialStatement-2024-Tahunan-TINS.xlsx"))
                .isInstanceOf(IdxWorkbookException.class).hasMessageStartingWith("Unsupported filing: No balance sheet data");
        assertThat(service.standardLimit(Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theUploadEndpointAcceptsAnUnsupportedWorkbookAndRefusesAnUnreadableOne() throws Exception {
        byte[] unsupported = Files.readAllBytes(file("2024-Tahunan"));

        assertThat(service.check(unsupported, "FinancialStatement-2024-Tahunan-TINS.xlsx").info().ticker()).isEqualTo("TINS");
        assertThatThrownBy(() -> service.check("not a workbook".getBytes(), "x.xlsx")).isInstanceOf(IdxWorkbookException.class);
        // the standard extraction gets 3 of the job's 5 minutes, then the full-text extraction takes over
        assertThat(service.standardLimit(Duration.ofMinutes(5))).isEqualTo(Duration.ofMinutes(3));
        assertThat(service.standardLimit(Duration.ofSeconds(90))).isEqualTo(Duration.ofSeconds(90));
    }

    // ------------------------------------------------------------------ helpers

    private void fallback(boolean enabled) {
        when(fullText.properties()).thenReturn(new FallbackProperties("", "", enabled, Duration.ofMinutes(3), Duration.ofMinutes(3),
                Duration.ofSeconds(150), 120_000, 3));
    }

    private static Verification complete() {
        return new Verification(true, List.of(), List.of(), Map.of(), List.of(), Map.of(), List.of());
    }

    private static Verification incomplete(String open) {
        boolean pending = open.startsWith("saveStatements");
        return new Verification(false, pending ? List.of(open) : List.of(), pending ? List.of() : List.of(open), Map.of(),
                List.of(), Map.of(), List.of());
    }

    private static IngestionAgent.Outcome outcome(Verification verification) {
        return new IngestionAgent.Outcome(null, false, List.of(), new AgentTrace(), verification, List.of());
    }

    private static FullTextExtractor.Result result(Verification verification, List<String> columns, int modelCalls) {
        return new FullTextExtractor.Result(new FullTextExtractor.Report(columns, modelCalls, List.of(), List.of(), List.of()),
                List.of(), verification, new AgentTrace());
    }

    private static Path file(String filing) {
        Path file = DATA.resolve("FinancialStatement-" + filing + "-TINS.xlsx");
        assumeTrue(Files.exists(file), "TINS source data not available: " + file);
        return file;
    }

    private IngestionSession session(String filing) throws Exception {
        Path file = file(filing);
        return service.open(Files.readAllBytes(file), file.getFileName().toString());
    }
}
