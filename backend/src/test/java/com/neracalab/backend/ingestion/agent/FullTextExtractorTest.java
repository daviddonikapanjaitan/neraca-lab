package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbook;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;

import tools.jackson.databind.json.JsonMapper;

/**
 * The full-text extraction with a scripted model and the real TINS workbooks, no database:
 * <ul>
 *   <li>FY2024 as IDX published it has an empty current-year column ("No balance sheet data"): nothing is
 *       invented for 2024, its comparative 2023 is mapped from the text;</li>
 *   <li>the model decides every value: it names the rows, the amounts are read from the cells. Where its mapping
 *       contradicts itself it is shown that and may correct it; its last answer is used as it is, and what still
 *       does not add up is reported, never dropped. It may adopt a statement the rule-based extraction read without
 *       failed checks.</li>
 * </ul>
 */
public class FullTextExtractorTest {

    private static final Path DATA = Path.of("..", "data", "IDX_XBRL", "TINS", "xlsx");

    private final IngestionAgent agent = mock(IngestionAgent.class);
    private final List<String> prompts = new ArrayList<>();

    @Test
    void aWorkbookWithoutCurrentYearFiguresGetsItsComparativeAndNoInventedYear() throws Exception {
        IngestionSession session = session("2024-Tahunan");
        assertThat(session.mapper().templateProblems()).contains("No balance sheet data (1210000)",
                "No profit or loss data (1311000 / 1321000)");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(mapping(text, "Total assets"));

        List<StatementColumn> columns = FullTextExtractor.columnsToExtract(session);
        FullTextExtractor.Report report = extractor(3).extract(session, columns, "Unsupported filing", new AgentTrace());

        assertThat(columns).containsExactly(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD);
        assertThat(report.modelCalls()).isEqualTo(1);
        // the model decides every statement of 2023
        assertThat(report.mapped()).containsExactly("PRIOR_PERIOD 2023 FY balance_sheet", "PRIOR_PERIOD 2023 FY income_statement",
                "PRIOR_PERIOD 2023 FY cash_flow_statement");
        // 2024: the column is empty in the file, so there is nothing to store and it is said plainly
        assertThat(report.problems()).singleElement().asString()
                .startsWith("CURRENT_PERIOD 2024 FY: the workbook has no figures for this period");
        assertThat(session.statements(StatementColumn.CURRENT_PERIOD)).isEmpty();
        assertThat(session.fullTextProblems()).hasSize(1);

        // 2023: every amount is the workbook's cell x its rounding unit (millions)
        MappedStatement balance = session.balance(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(balance.values().get("total_assets")).isEqualByComparingTo("12853277000000");
        assertThat(balance.values().get("total_liabilities")).isEqualByComparingTo("6610928000000");
        assertThat(balance.values().get("total_equity")).isEqualByComparingTo("6242349000000");
        assertThat(balance.values().get("cash_and_equivalents")).isEqualByComparingTo("1526601000000");
        assertThat(balance.values().get("non_controlling_interest")).isEqualByComparingTo("292000000");
        assertThat(balance.hasErrors()).isFalse();
        assertThat(balance.derivations().get("total_assets")).startsWith("full-text extraction: + 'Total assets' (1210000!R");
        MappedStatement income = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isEqualByComparingTo("8391907000000");
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo("465941000000");
        assertThat(income.values().get("pretax_income")).isEqualByComparingTo("-446698000000");
        assertThat(income.values().get("income_tax")).as("an expense, positive").isEqualByComparingTo("2974000000");
        assertThat(income.values().get("net_income")).isEqualByComparingTo("-449672000000");
        assertThat(income.values().get("sga_expense")).isEqualByComparingTo("991944000000");
        assertThat(income.values().get("ebit")).as("profit before tax + finance costs - finance income")
                .isEqualByComparingTo("-262505000000");
        MappedStatement cash = session.cashFlow(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(cash.values().get("operating_cash_flow")).isEqualByComparingTo("681969000000");
        assertThat(cash.values().get("capital_expenditure")).as("payments, negative").isEqualByComparingTo("-683949000000");
        assertThat(cash.values().get("dividends_paid")).isEqualByComparingTo("-312445000000");
        assertThat(cash.values().get("ending_cash")).isEqualByComparingTo("1526601000000");
        assertThat(cash.derivations().get("capital_expenditure")).startsWith("full-text extraction: - 'Payments for acquisition of");
        assertThat(cash.checks()).noneMatch(Check::isError);

        // the model saw the whole workbook with row references, why the rule-based extraction failed, and - as a
        // reference only - the one statement that extraction did read
        assertThat(prompts.getFirst()).contains("## Sheet 1000000: General information", "Entity code: TINS",
                "## Sheet 1210000", "| Total assets | c1=- | c2=12853277", "Unsupported filing",
                "- PRIOR_PERIOD (2023 FY) income_statement: value column c2",
                "\"PRIOR_PERIOD cash_flow_statement\" (passed every check; may be adopted)", "operating_cash_flow=681969");
    }

    @Test
    void aMappingThatContradictsItselfIsShownToTheModelWhichCorrectsIt() throws Exception {
        IngestionSession session = session("2024-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        // first answer: total assets mapped to the current assets total, so assets != liabilities + equity
        answers(mapping(text, "Total current assets"), mapping(text, "Total assets"));

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "test", new AgentTrace());

        assertThat(report.modelCalls()).isEqualTo(2);
        assertThat(report.problems()).isEmpty();
        assertThat(session.balance(StatementColumn.PRIOR_PERIOD).orElseThrow().values().get("total_assets"))
                .isEqualByComparingTo("12853277000000");
        assertThat(prompts.get(1)).contains("total_assets = total_liabilities + temporary_syirkah_funds + total_equity does not hold: "
                + "5519186 vs 12853277", "If the filing itself is inconsistent, keep your mapping");
    }

    @Test
    void theModelsLastAnswerIsStoredEvenWhenItDoesNotAddUpAndThatIsReported() throws Exception {
        IngestionSession session = session("2024-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(mapping(text, "Total current assets"), mapping(text, "Total current assets"));

        FullTextExtractor.Report report = extractor(2).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "test", new AgentTrace());

        // no rule of the code overrules the model: the balance sheet is used as it mapped it ...
        assertThat(report.mapped()).containsExactly("PRIOR_PERIOD 2023 FY balance_sheet", "PRIOR_PERIOD 2023 FY income_statement",
                "PRIOR_PERIOD 2023 FY cash_flow_statement");
        MappedStatement balance = session.balance(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(balance.values().get("total_assets")).isEqualByComparingTo("5519186000000");
        assertThat(balance.hasErrors()).as("storable").isFalse();
        assertThat(balance.checks()).anyMatch(c -> c.severity() == Check.Severity.WARNING && c.message().contains("does not hold"));
        // ... and what does not add up is a problem of the upload (INCOMPLETE, recorded in ingestion_error)
        assertThat(report.problems()).singleElement().asString().startsWith("Full-text extraction of PRIOR_PERIOD 2023 FY "
                + "balance_sheet: stored as the model mapped it, although total_assets = total_liabilities + temporary_syirkah_funds "
                + "+ total_equity does not hold: 5519186 vs 12853277");
        assertThat(session.fullTextProblems()).hasSize(1);
        verify(agent, times(2)).call(any(Prompt.class), any(AgentTrace.class), any(Duration.class));
    }

    @Test
    void amountsAndUnknownRowsInTheAnswerAreNeverUsed() throws Exception {
        IngestionSession session = session("2024-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String invented = mapping(text, "Total assets")
                .replace("\"revenue\": [", "\"revenue\": [{\"row\": \"1311000!R9999\", \"sign\": 1, \"amount\": 99999999}, ");
        answers(invented);

        FullTextExtractor.Report report = extractor(1).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "test", new AgentTrace());

        // the unknown row is dropped and its amount ignored: revenue is the real row's cell
        assertThat(session.income(StatementColumn.PRIOR_PERIOD).orElseThrow().values().get("revenue"))
                .isEqualByComparingTo("8391907000000");
        assertThat(report.notes()).anyMatch(n -> n.contains("'1311000!R9999' is not a row reference of the text"));
        assertThat(report.problems()).isEmpty();
    }

    @Test
    void aFieldCanBeMadeUpOfOtherRowsInOneColumn() throws Exception {
        IngestionSession session = session("2024-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String withOverride = mapping(text, "Total assets").replace("\"notes\":", "\"overrides\": {\"PRIOR_PERIOD\": "
                + "{\"income_statement\": {\"sga_expense\": " + terms(text, "1311000", "+Selling expenses") + "}}}, \"notes\":");
        answers(withOverride);

        extractor(1).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "test", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(income.values().get("sga_expense")).as("the override of this column").isEqualByComparingTo("69037000000");
        assertThat(income.values().get("operating_expenses")).as("not overridden").isEqualByComparingTo("991944000000");
    }

    @Test
    void theModelMayAdoptAStatementTheRuleBasedExtractionReadWithoutFailedChecks() throws Exception {
        // FY2025: every statement passes the rule-based checks; the model decides to adopt them all
        IngestionSession session = session("2025-Tahunan");
        IngestionSession rules = session("2025-Tahunan");
        answers("{\"adopt\": [\"CURRENT_PERIOD balance_sheet\", \"CURRENT_PERIOD income_statement\", "
                + "\"CURRENT_PERIOD cash_flow_statement\", \"PRIOR_PERIOD balance_sheet\", \"PRIOR_PERIOD income_statement\", "
                + "\"PRIOR_PERIOD cash_flow_statement\"], \"notes\": []}");

        List<StatementColumn> columns = FullTextExtractor.columnsToExtract(session);
        FullTextExtractor.Report report = extractor(3).extract(session, columns, "slow", new AgentTrace());

        assertThat(columns).as("everything not stored yet is the model's to decide")
                .containsExactly(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD);
        assertThat(report.modelCalls()).isEqualTo(1);
        assertThat(report.mapped()).hasSize(6);
        assertThat(report.problems()).isEmpty();
        for (StatementColumn column : columns) {
            assertThat(session.statements(column)).hasSize(3).noneMatch(MappedStatement::hasErrors);
            assertThat(session.income(column).orElseThrow().values()).isEqualTo(rules.income(column).orElseThrow().values());
            assertThat(session.balance(column).orElseThrow().values()).isEqualTo(rules.balance(column).orElseThrow().values());
            assertThat(session.cashFlow(column).orElseThrow().values()).isEqualTo(rules.cashFlow(column).orElseThrow().values());
            assertThat(session.income(column).orElseThrow().checks()).anyMatch(c -> c.message().startsWith("Adopted by the model"));
        }
    }

    @Test
    void aStatementThatFailedItsChecksCannotBeAdopted() throws Exception {
        // FY2024 as published: the rule-based extraction has no balance sheet at all, so there is nothing to adopt
        IngestionSession session = session("2024-Tahunan");
        answers("{\"adopt\": [\"PRIOR_PERIOD balance_sheet\", \"PRIOR_PERIOD income_statement\", "
                + "\"PRIOR_PERIOD cash_flow_statement\"], \"notes\": []}");

        FullTextExtractor.Report report = extractor(1).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "test", new AgentTrace());

        assertThat(report.mapped()).containsExactly("PRIOR_PERIOD 2023 FY cash_flow_statement");
        assertThat(report.notes()).anyMatch(n -> n.contains("\"PRIOR_PERIOD balance_sheet\" cannot be adopted"));
        assertThat(report.problems()).hasSize(2).allMatch(p -> p.contains("not stored, no rows of the statement were named"));
        assertThat(session.balance(StatementColumn.PRIOR_PERIOD)).isEmpty();
        verify(agent, never()).run(any());
    }

    // ------------------------------------------------------------------ helpers

    private FullTextExtractor extractor(int rounds) {
        return new FullTextExtractor(agent, null, null, JsonMapper.builder().build(),
                new FallbackProperties(true, Duration.ofMinutes(3), Duration.ofMinutes(3), Duration.ofSeconds(150), 120_000, rounds));
    }

    private void answers(String... answers) {
        when(agent.options()).thenAnswer(call -> OpenAiChatOptions.builder().model("test"));
        var stub = when(agent.call(any(Prompt.class), any(AgentTrace.class), any(Duration.class)));
        for (String answer : answers) {
            stub = stub.thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                call.<AgentTrace>getArgument(1).modelCall();
                prompts.add(prompt.getInstructions().getLast().getText());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Here is the mapping:\n" + answer))));
            });
        }
    }

    /** A complete, correct mapping of the TINS statements, with {@code totalAssetsRow} as total_assets. */
    public static String mapping(WorkbookText text, String totalAssetsRow) {
        Map<String, String> income = Map.ofEntries(
                Map.entry("revenue", terms(text, "1311000", "+Sales and revenue")),
                Map.entry("cost_of_revenue", terms(text, "1311000", "+Cost of sales and revenue")),
                Map.entry("gross_profit", terms(text, "1311000", "+Total gross profit")),
                Map.entry("sga_expense", terms(text, "1311000", "+Selling expenses", "+General and administrative expenses")),
                Map.entry("operating_expenses", terms(text, "1311000", "+Selling expenses", "+General and administrative expenses")),
                Map.entry("operating_income", terms(text, "1311000", "+Total gross profit", "-Selling expenses",
                        "-General and administrative expenses", "+Other income")),
                Map.entry("interest_income", terms(text, "1311000", "+Finance income")),
                Map.entry("interest_expense", terms(text, "1311000", "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, "1311000", "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, "1311000", "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, "1311000", "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, "1311000", "+Profit (loss) attributable to parent entity")),
                Map.entry("basic_eps", terms(text, "1311000", "+Basic earnings (loss) per share from continuing operations")),
                Map.entry("diluted_eps", "[]"));
        Map<String, String> balance = Map.ofEntries(
                Map.entry("cash_and_equivalents", terms(text, "1210000", "+Cash and cash equivalents")),
                Map.entry("current_assets", terms(text, "1210000", "+Total current assets")),
                Map.entry("total_assets", terms(text, "1210000", "+" + totalAssetsRow)),
                Map.entry("current_liabilities", terms(text, "1210000", "+Total current liabilities")),
                Map.entry("total_liabilities", terms(text, "1210000", "+Total liabilities")),
                Map.entry("short_term_debt", terms(text, "1210000", "+Short term bank loans")),
                Map.entry("shareholders_equity", terms(text, "1210000", "+Total equity attributable to equity owners of parent entity")),
                Map.entry("non_controlling_interest", terms(text, "1210000", "+Non-controlling interests")),
                Map.entry("total_equity", terms(text, "1210000", "+Total equity")),
                Map.entry("goodwill", "[]"));
        Map<String, String> cash = Map.ofEntries(
                Map.entry("operating_cash_flow", terms(text, "1510000", "+Total net cash flows received from (used in) operating activities")),
                Map.entry("investing_cash_flow", terms(text, "1510000", "+Total net cash flows received from (used in) investing activities")),
                Map.entry("financing_cash_flow", terms(text, "1510000", "+Total net cash flows received from (used in) financing activities")),
                Map.entry("capital_expenditure", terms(text, "1510000", "-Payments for acquisition of property, plant and equipment",
                        "-Payments for acquisition of investment properties", "-Payments for acquisition of mining properties")),
                Map.entry("dividends_paid", terms(text, "1510000", "-Dividends paid from financing activities")),
                Map.entry("debt_issued", terms(text, "1510000", "+Proceeds from bank loans")),
                Map.entry("debt_repaid", terms(text, "1510000", "-Payments of bank loans")),
                Map.entry("cash_change", terms(text, "1510000", "+Total net increase (decrease) in cash and cash equivalents")),
                Map.entry("ending_cash", terms(text, "1510000", "+Cash and cash equivalents cash flows, end of the period")));
        return "{\"income_statement\": " + object(income) + ",\n \"balance_sheet\": " + object(balance)
                + ",\n \"cash_flow_statement\": " + object(cash) + ",\n \"notes\": [\"scripted\"]}";
    }

    private static String object(Map<String, String> fields) {
        List<String> entries = new ArrayList<>();
        fields.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> entries.add("\"" + e.getKey() + "\": " + e.getValue()));
        return "{" + String.join(", ", entries) + "}";
    }

    /** [{"row": "1311000!R6", "sign": 1}, ...] for rows given as "+label" / "-label". */
    private static String terms(WorkbookText text, String sheet, String... signedLabels) {
        List<String> terms = new ArrayList<>();
        for (String signed : signedLabels) {
            String label = signed.substring(1);
            String ref = text.text().lines().filter(l -> l.startsWith(sheet + "!R") && l.contains(" | " + label + " | "))
                    .map(l -> l.substring(0, l.indexOf(' '))).findFirst()
                    .orElseThrow(() -> new AssertionError("no row '" + label + "' in sheet " + sheet));
            terms.add("{\"row\": \"" + ref + "\", \"sign\": " + (signed.charAt(0) == '-' ? -1 : 1) + "}");
        }
        return "[" + String.join(", ", terms) + "]";
    }

    private static IngestionSession session(String filing) throws Exception {
        Path file = DATA.resolve("FinancialStatement-" + filing + "-TINS.xlsx");
        assumeTrue(Files.exists(file), "TINS source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        return new IngestionSession(new FilingMapper(workbook));
    }
}
