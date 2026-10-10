package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.ingestion.agent.AgentTrace;
import com.neracalab.backend.ingestion.agent.FullTextExtractor;
import com.neracalab.backend.ingestion.agent.FullTextExtractorTest;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.IngestionSession;
import com.neracalab.backend.ingestion.agent.JobDeadline;
import com.neracalab.backend.ingestion.agent.WorkbookText;
import com.neracalab.backend.ingestion.error.IngestionErrorRepository;
import com.neracalab.backend.ingestion.error.IngestionErrorRepository.NewError;
import com.neracalab.backend.ingestion.mapping.StatementColumn;

/**
 * The full-text extraction against the Docker Postgres with a scripted model, rolled back: the workbook TINS
 * FY2024 as IDX published it (an empty current-year column, "No balance sheet data") is taken over, its
 * comparative 2023 is saved with the standard tools and rules, and the verification says that 2024 is not in the
 * file. Also the error log {@code ingestion_error}. (Same application context as FinancialStatementUploadTest.)
 */
@SpringBootTest(properties = "neracalab.ingestion.fallback.enabled=false")
@AutoConfigureMockMvc
class FullTextExtractionDatabaseTest {

    private static final Path TINS_2024 = Path.of("..", "data", "IDX_XBRL", "TINS", "xlsx", "FinancialStatement-2024-Tahunan-TINS.xlsx");

    @MockitoBean
    private IngestionAgent agent;

    @Autowired
    private IngestionService service;

    @Autowired
    private FullTextExtractor fullText;

    @Autowired
    private IngestionErrorRepository errors;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aWorkbookTheStandardExtractionCannotMapIsStoredFromItsText() throws Exception {
        assumeTrue(Files.exists(TINS_2024), "TINS source data not available");
        byte[] content = Files.readAllBytes(TINS_2024);
        transaction.executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            IngestionSession session = service.open(content, TINS_2024.getFileName().toString());
            session.deadline(JobDeadline.after(Duration.ofMinutes(3)));
            String mapping = FullTextExtractorTest.mapping(new WorkbookText(session.mapper().workbook(), 120_000), "Total assets");
            when(agent.options()).thenAnswer(call -> OpenAiChatOptions.builder().model("test"));
            when(agent.call(any(Prompt.class), any(AgentTrace.class), any(Duration.class))).thenAnswer(call -> {
                call.<AgentTrace>getArgument(1).modelCall();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(mapping))));
            });

            FullTextExtractor.Result result = fullText.run(session,
                    "Unsupported filing: No balance sheet data (1210000); No profit or loss data (1311000 / 1321000)");

            assertThat(result.report().columns()).containsExactly("CURRENT_PERIOD", "PRIOR_PERIOD");
            assertThat(result.report().modelCalls()).isEqualTo(1);
            assertThat(result.report().mapped()).containsExactly("PRIOR_PERIOD 2023 FY balance_sheet",
                    "PRIOR_PERIOD 2023 FY income_statement", "PRIOR_PERIOD 2023 FY cash_flow_statement");
            // the comparative is saved by the standard steps: three statements of 2023
            assertThat(result.steps()).contains("saveStatements(PRIOR_PERIOD) done");
            assertThat(session.savedStatements()).containsOnlyKeys(StatementColumn.PRIOR_PERIOD);
            assertThat(session.savedStatements().get(StatementColumn.PRIOR_PERIOD)).hasSize(3);
            Map<String, Object> stored = jdbc.sql("""
                            SELECT b.total_assets, b.total_liabilities, b.total_equity, i.revenue, i.pretax_income, i.net_income
                            FROM company c JOIN reporting_period rp ON rp.company_id = c.company_id
                            JOIN balance_sheet b ON b.period_id = rp.period_id
                            JOIN income_statement i ON i.period_id = rp.period_id
                            WHERE c.ticker = 'TINS' AND c.exchange = 'IDX' AND rp.fiscal_year = 2023 AND rp.period_type = 'FY'""")
                    .query().singleRow();
            assertThat((BigDecimal) stored.get("total_assets")).isEqualByComparingTo("12853277000000");
            assertThat((BigDecimal) stored.get("total_liabilities")).isEqualByComparingTo("6610928000000");
            assertThat((BigDecimal) stored.get("total_equity")).isEqualByComparingTo("6242349000000");
            assertThat((BigDecimal) stored.get("revenue")).isEqualByComparingTo("8391907000000");
            assertThat((BigDecimal) stored.get("pretax_income")).isEqualByComparingTo("-446698000000");
            assertThat((BigDecimal) stored.get("net_income")).isEqualByComparingTo("-449672000000");
            // nothing is stored for 2024 by this workbook: its column is empty, and the verification says so
            assertThat(session.savedStatements()).doesNotContainKey(StatementColumn.CURRENT_PERIOD);
            assertThat(result.verification().complete()).isFalse();
            assertThat(result.verification().problems()).anyMatch(p ->
                    p.startsWith("CURRENT_PERIOD 2024 FY: the workbook has no figures for this period"));
            assertThat(result.verification().notes()).anyMatch(n -> n.startsWith("Full-text extraction: the statements of"));
        });
        verify(agent, never()).run(any());
    }

    @Test
    void theErrorLogKeepsWhatWentWrongAndStartsUnfixed() {
        String file = "error-log-test-" + UUID.randomUUID() + ".xlsx";
        try {
            long before = errors.openCount();
            errors.record(new NewError(null, null, file, "IDX", "TINS", "2025 FY", IngestionErrorRepository.Type.INCOMPLETE,
                    "saveStatements(PRIOR_PERIOD) is blocked: income_statement failed checks",
                    Map.of("pending", List.of("saveStatements(PRIOR_PERIOD)")), true, "PRIOR_PERIOD", "SUCCEEDED",
                    "Stored and verified TINS 2025 FY"));

            Map<String, Object> row = jdbc.sql("""
                            SELECT error_type, ticker, period, message, details ->> 'pending' AS pending, fallback_used,
                                   fallback_columns, final_status, fixed_by_claude_code, fixed_at, fix_note
                            FROM ingestion_error WHERE file_name = :f""").param("f", file).query().singleRow();
            assertThat(row.get("error_type")).isEqualTo("INCOMPLETE");
            assertThat(row.get("ticker")).isEqualTo("TINS");
            assertThat(row.get("period")).isEqualTo("2025 FY");
            assertThat((String) row.get("message")).startsWith("saveStatements(PRIOR_PERIOD) is blocked");
            assertThat((String) row.get("pending")).contains("saveStatements(PRIOR_PERIOD)");
            assertThat(row.get("fallback_used")).isEqualTo(true);
            assertThat(row.get("fallback_columns")).isEqualTo("PRIOR_PERIOD");
            assertThat(row.get("final_status")).isEqualTo("SUCCEEDED");
            assertThat(row.get("fixed_by_claude_code")).isEqualTo(false);
            assertThat(row.get("fixed_at")).isNull();
            assertThat(errors.openCount()).isEqualTo(before + 1);

            // Claude Code marks it once the cause is fixed
            jdbc.sql("UPDATE ingestion_error SET fixed_by_claude_code = TRUE, fixed_at = now(), fix_note = 'test' WHERE file_name = :f")
                    .param("f", file).update();
            assertThat(errors.openCount()).isEqualTo(before);
        } finally {
            jdbc.sql("DELETE FROM ingestion_error WHERE file_name = :f").param("f", file).update();
        }
    }
}
