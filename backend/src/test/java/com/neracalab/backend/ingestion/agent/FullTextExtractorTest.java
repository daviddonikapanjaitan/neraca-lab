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
import java.math.BigDecimal;
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

        // the model saw the whole workbook with row references and why the rule-based extraction failed; it was
        // asked for every statement of both columns (the empty 2024 column included: no rule decides beforehand
        // that there is nothing), and nothing of the rules is offered for a workbook they cannot map
        assertThat(prompts.getFirst()).contains("## Sheet 1000000: General information", "Entity code: TINS",
                "## Sheet 1210000", "| Total assets | c1=- | c2=12853277", "Unsupported filing",
                "- CURRENT_PERIOD (2024 FY) balance_sheet: value column c1",
                "- PRIOR_PERIOD (2023 FY) income_statement: value column c2");
        assertThat(prompts.getFirst()).doesNotContain("Reference - what the rule-based extraction read");
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

        // the amount in the answer is never used; and a field naming a row that does not exist gets no value at all
        // (the sum of its other rows is not what the model meant), which is an open item of the upload
        Map<String, BigDecimal> income = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow().values();
        assertThat(income.get("revenue")).isNull();
        assertThat(income.get("cost_of_revenue")).isNotNull();        // the other fields are stored as mapped
        assertThat(report.problems()).singleElement().asString()
                .contains("income_statement.revenue: '1311000!R9999' is not a row reference of the text")
                .endsWith("the field is left empty");
    }

    /**
     * NSSS FY2025 (profit or loss by nature, 1312000: no cost of sales, no gross profit row). The model's first
     * answer took the cost of revenue from a notes sheet (1670000!R11), which cannot be named: before, that row was
     * dropped and "revenue less nothing" was stored as gross profit (2,013,801 million, the revenue) in an upload
     * that ended SUCCEEDED. Now the two fields get no value, the model is asked again, and leaves them empty.
     */
    @Test
    void aRowOfANotesSheetIsNotUsedAndTheModelIsAskedAgain() throws Exception {
        IngestionSession session = session("NSSS", "2025-Tahunan");
        assertThat(session.mapper().incomeByNature()).isTrue();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String revenue = terms(text, "1312000", "+Sales and revenue");
        String fromTheNotes = nsss(text, "[{\"row\": \"1670000!R11\", \"sign\": 1}]",
                revenue.replace("]", ", {\"row\": \"1670000!R11\", \"sign\": -1}]"));
        answers(fromTheNotes, nsss(text, "[]", "[]"));

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        assertThat(report.modelCalls()).isEqualTo(2);
        assertThat(prompts.get(1)).contains("income_statement.cost_of_revenue: '1670000!R11' is not a row reference of the text",
                "income_statement.gross_profit: '1670000!R11' is not a row reference of the text", "the field is left empty");
        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isEqualByComparingTo("2013801000000");
        assertThat(income.values().get("gross_profit")).isNull();
        assertThat(income.values().get("cost_of_revenue")).isNull();
        assertThat(income.values().get("net_income")).isEqualByComparingTo("647822000000");
        // a gross profit stored by an earlier upload is cleared, not kept as a gap
        assertThat(income.rejected()).containsExactlyInAnyOrder("cost_of_revenue", "gross_profit");
        assertThat(report.problems()).isEmpty();
    }

    /** The same answer when it is the last one: nothing invented is stored, and the upload has open items. */
    @Test
    void aRowOfANotesSheetInTheLastAnswerLeavesTheFieldsEmptyAndOpen() throws Exception {
        IngestionSession session = session("NSSS", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String revenue = terms(text, "1312000", "+Sales and revenue");
        answers(nsss(text, "[{\"row\": \"1670000!R11\", \"sign\": 1}]",
                revenue.replace("]", ", {\"row\": \"1670000!R11\", \"sign\": -1}]")));

        FullTextExtractor.Report report = extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("gross_profit")).isNull();       // not the revenue
        assertThat(income.values().get("revenue")).isEqualByComparingTo("2013801000000");
        assertThat(report.problems()).hasSize(2).allMatch(p -> p.contains("'1670000!R11' is not a row reference of the text"));
    }

    /**
     * MSIN (a broadcaster, profit or loss by nature): the model named 'Program and broadcasting expenses' as cost of
     * revenue and no gross profit. An expense by nature is not a cost of sales: neither field is stored, whatever
     * the model answered, and nothing is reported as open. The EPS, filed in the rounding unit (0.00000655), is
     * stored in rupiah.
     */
    @Test
    void aCostOfRevenueTheModelBuiltFromExpensesByNatureIsNotStored() throws Exception {
        IngestionSession session = session("MSIN", "2024-Tahunan");
        assertThat(session.mapper().incomeByNature()).isTrue();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String programExpenses = terms(text, "1322000", "+Program and broadcasting expenses");
        answers(byNature(text, "1322000", "Interest income", programExpenses,
                terms(text, "1322000", "+Sales and revenue", "-Program and broadcasting expenses")));

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        assertThat(report.modelCalls()).isEqualTo(1);
        assertThat(report.problems()).isEmpty();
        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isEqualByComparingTo("3238780000000");
        assertThat(income.values().get("cost_of_revenue")).isNull();
        assertThat(income.values().get("gross_profit")).isNull();
        assertThat(income.rejected()).containsExactlyInAnyOrder("cost_of_revenue", "gross_profit");
        assertThat(income.values().get("net_income_to_parent")).isEqualByComparingTo("397637000000");
        assertThat(income.values().get("basic_eps")).isEqualByComparingTo("6.55");
    }

    /**
     * MNCN H1 2026 (by nature, error 806): the model named 'Program and broadcasting expenses' as cost of revenue and only
     * 'Other expenses' as operating expenses, while its operating income deducts both. The expense row the model named
     * is an operating expense, as its operating income counts it (before, it was in no field: operating expenses 864,240
     * million beside revenue 3,517,600 and an operating income of 519,964). The filing's own cost of sales note states
     * the cost of sales, 2,133,396 (the program and broadcasting expenses): cost of revenue and gross profit from it, the
     * operating expenses without it.
     */
    @Test
    void anExpenseByNatureNamedAsCostOfRevenueIsAnOperatingExpense() throws Exception {
        IngestionSession session = session("MNCN", "2026-II");
        assertThat(session.mapper().incomeByNature()).isTrue();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "1312000";
        String profitOrLoss = object(Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Sales and revenue")),
                Map.entry("cost_of_revenue", terms(text, sheet, "+Program and broadcasting expenses")),
                Map.entry("operating_expenses", terms(text, sheet, "+Other expenses")),
                Map.entry("operating_income", terms(text, sheet, "+Sales and revenue", "-Program and broadcasting expenses",
                        "-Other expenses")),
                Map.entry("interest_income", terms(text, sheet, "+Interest income")),
                Map.entry("interest_expense", terms(text, sheet, "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity"))));
        answers("{\"income_statement\": " + profitOrLoss + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}");

        extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        // 3,517,600 - 2,133,396 = 1,384,204; 1,384,204 - 864,240 = operating income 519,964 (million)
        assertThat(income.values().get("cost_of_revenue")).isEqualByComparingTo("2133396000000");
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo("1384204000000");
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("864240000000");
        assertThat(income.values().get("operating_income")).isEqualByComparingTo("519964000000");
        assertThat(income.rejected()).doesNotContain("cost_of_revenue", "gross_profit");
        assertThat(income.derivations().get("operating_expenses")).contains("'Program and broadcasting expenses'", "'Other expenses'",
                "less the cost of revenue of the cost of sales note");
        assertThat(income.derivations().get("cost_of_revenue")).contains("cost of sales note (1670000");
    }

    /**
     * BIPI FY2025 (by nature: revenue, depreciation and amortisation, other expenses; no gross profit on the face): its
     * cost of sales note (1670000) states 200,443,257 of revenue 235,089,682, so the gross profit is 34,646,425 and the
     * operating expenses by nature, 212,368,437, are 11,925,180 without it; operating income 22,721,245 as filed.
     */
    @Test
    void byNatureTheCostOfSalesNoteGivesTheGrossProfit() throws Exception {
        IngestionSession session = session("BIPI", "2025-Tahunan");
        assertThat(session.mapper().incomeByNature()).isTrue();
        assertThat(session.mapper().costOfSales(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("200443257");
        assertThat(session.mapper().costOfSales(StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("451661475");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "1312000";
        String profitOrLoss = object(Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Sales and revenue")),
                Map.entry("operating_expenses", terms(text, sheet, "+Depreciation and amortisation expenses",
                        "+Other expenses")),
                Map.entry("operating_income", terms(text, sheet, "+Sales and revenue",
                        "-Depreciation and amortisation expenses", "-Other expenses")),
                Map.entry("interest_income", terms(text, sheet, "+Finance income")),
                Map.entry("interest_expense", terms(text, sheet, "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity"))));
        answers("{\"income_statement\": " + profitOrLoss + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}");

        extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isEqualByComparingTo("235089682");
        assertThat(income.values().get("cost_of_revenue")).isEqualByComparingTo("200443257");
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo("34646425");
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("11925180");
        assertThat(income.values().get("operating_income")).isEqualByComparingTo("22721245");
        assertThat(income.checks()).anyMatch(c -> c.rule().equals("gross_profit") && c.message().contains("cost of sales note"));
    }

    /**
     * BUKA FY2025 (by nature): the note's cost of sales 5,990,751,222 thousand ('Raw materials and consumables used' on
     * the face) is more than the operating expenses by nature, 4,432,364,338, which a gain of 1,988,280,296 filed as
     * negative 'Other expenses' nets down - so the year had no gross profit. The positive expense rows hold the cost.
     */
    @Test
    void theCostOfSalesIsHeldByThePositiveExpenseRows() throws Exception {
        IngestionSession session = session("BUKA", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "1312000";
        String[] expenses = {"+Ticketing, sales and promotion expenses", "+Raw materials and consumables used",
                "+Employee benefits expenses", "+Depreciation and amortisation expenses", "+Final tax expenses",
                "+Other expenses"};
        List<String> operating = new ArrayList<>(List.of("+Sales and revenue", "+Other income"));
        for (String e : expenses) {
            operating.add("-" + e.substring(1));
        }
        String profitOrLoss = object(Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Sales and revenue")),
                Map.entry("operating_expenses", terms(text, sheet, expenses)),
                Map.entry("operating_income", terms(text, sheet, operating.toArray(String[]::new))),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity"))));
        answers("{\"income_statement\": " + profitOrLoss + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}");

        extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("cost_of_revenue")).isEqualByComparingTo("5990751222000");
        // 6,510,030,453 - 5,990,751,222
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo("519279231000");
        // 4,432,364,338 - 5,990,751,222: negative, the gain netted in
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("-1558386884000");
    }

    /**
     * The model names the row of the cost as cost of revenue and leaves it out of the operating expenses (PGEO FY2023:
     * 'Exploration expenses' 178,977,000, the cost of sales note's total, beside 'Other expenses' 5,526,000 as operating
     * expenses; it stayed without gross profit). The note gives the gross profit; the operating expenses stay as mapped
     * - taking the cost out of them would count it twice. Here BUKA FY2025, 'Raw materials and consumables used'
     * 5,990,751,222 thousand, the note's cost, answered the same way.
     */
    @Test
    void theModelsOwnCostRowsAreTheNotesCost() throws Exception {
        IngestionSession session = session("BUKA", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "1312000";
        String profitOrLoss = object(Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Sales and revenue")),
                Map.entry("cost_of_revenue", terms(text, sheet, "+Raw materials and consumables used")),
                Map.entry("operating_expenses", terms(text, sheet, "+Ticketing, sales and promotion expenses",
                        "+Employee benefits expenses", "+Depreciation and amortisation expenses", "+Other expenses")),
                Map.entry("operating_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)"))));
        answers("{\"income_statement\": " + profitOrLoss + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}");

        extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("cost_of_revenue")).isEqualByComparingTo("5990751222000");
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo("519279231000");
        // 212,681,610 + 108,376,169 + 108,835,633 - 1,988,280,296, as mapped
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("-1558386884000");
        assertThat(income.derivations().get("cost_of_revenue")).contains("the rows the model named as cost of revenue");
    }

    /**
     * PGEO FY2023 (by nature): its face leaves revenue empty (406,288,000 only in its breakdown by party), so no gross
     * profit can be derived here - but the note's cost 178,977,000 is stored and taken out of the operating expenses
     * (184,503,000 -> 5,526,000), and the gross profit another filing stored (the FY2024 filing's comparative) is kept,
     * not cleared.
     */
    @Test
    void withoutRevenueTheNotesCostIsStoredAndAGrossProfitKept() throws Exception {
        IngestionSession session = session("PGEO", "2023-Tahunan");
        assertThat(session.mapper().costOfSales(StatementColumn.CURRENT_PERIOD)).isEqualByComparingTo("178977000");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "3312000";
        String profitOrLoss = object(Map.ofEntries(
                Map.entry("operating_expenses", terms(text, sheet, "+Exploration expenses", "+Other expenses")),
                Map.entry("operating_income", terms(text, sheet, "+Total profit (loss) before tax", "-Other gains (losses)",
                        "-Finance income", "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)"))));
        answers("{\"income_statement\": " + profitOrLoss + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}");

        extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isNull();
        assertThat(income.values().get("cost_of_revenue")).isEqualByComparingTo("178977000");
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("5526000");
        assertThat(income.values().get("gross_profit")).isNull();
        assertThat(income.rejected()).doesNotContain("gross_profit", "cost_of_revenue");
    }

    /**
     * RAFI FY2021 (the prior period of its FY2022 filing, amounts with fractions of a rupiah): the note's cost
     * 89,901,586,207 is 0.36 above the expenses 89,901,586,206.6389 it is one of, so it was refused and the year had
     * no gross profit. Less than a filed unit apart is the same amount.
     */
    @Test
    void aCostOfSalesAFractionAboveTheExpensesIsTheSameAmount() throws Exception {
        IngestionSession session = session("RAFI", "2022-Tahunan");
        assertThat(session.mapper().costOfSales(StatementColumn.PRIOR_PERIOD)).isEqualByComparingTo("89901586207");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "1312000";
        String profitOrLoss = object(Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Sales and revenue")),
                Map.entry("operating_expenses", terms(text, sheet, "+Raw materials and consumables used")),
                Map.entry("operating_income", terms(text, sheet, "+Sales and revenue",
                        "-Raw materials and consumables used")),
                Map.entry("interest_expense", terms(text, sheet, "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity"))));
        answers("{\"income_statement\": " + profitOrLoss + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}");

        extractor(1).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(income.values().get("cost_of_revenue")).isEqualByComparingTo("89901586207");
        assertThat(income.values().get("gross_profit")).isEqualByComparingTo("27211617032");
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("0");
    }

    /**
     * MSIN FY2025 (by nature, no 'profit from operation' row): the model's answer left operating_income empty, the
     * upload ended SUCCEEDED, and the company had no EV / operating profit from then on. Profit from operations is
     * a total a profit or loss cannot do without: the model is asked again and gives the rows that compute it.
     */
    @Test
    void aProfitOrLossWithoutOperatingIncomeIsAskedForAgain() throws Exception {
        IngestionSession session = session("MSIN", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(byNature(text, "1322000", "Interest income", "[]", "[]", "[]"),
                byNature(text, "1322000", "Interest income", "[]", "[]"));

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        assertThat(report.modelCalls()).isEqualTo(2);
        assertThat(prompts.get(1)).contains("income_statement: operating_income has no amount");
        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        // profit before tax 1,049,325 + finance costs 6,633 - interest income 30,276 (million)
        assertThat(income.values().get("operating_income")).isEqualByComparingTo("1025682000000");
        assertThat(report.problems()).isEmpty();
    }

    /** Left empty in the last answer too: nothing is invented, and the upload has an open item that says so. */
    @Test
    void operatingIncomeLeftEmptyInTheLastAnswerIsAnOpenItem() throws Exception {
        IngestionSession session = session("MSIN", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(byNature(text, "1322000", "Interest income", "[]", "[]", "[]"));

        FullTextExtractor.Report report = extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("operating_income")).isNull();
        assertThat(income.values().get("pretax_income")).isEqualByComparingTo("1049325000000");
        assertThat(report.problems()).singleElement().asString()
                .contains("2025 FY income_statement", "stored as the model mapped it", "operating_income has no amount");
    }

    /**
     * BUVA FY2024 (by nature): the model added 'Other income' 15,260,830,841 to 'Sales and revenue' 355,259,333,593 and
     * the upload ended SUCCEEDED with revenue 370,520,164,434. Revenue is the row alone: the model is shown so.
     */
    @Test
    void revenueIsTheSalesAndRevenueRowAlone() throws Exception {
        IngestionSession session = session("BUVA", "2024-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(byNature(text, "1312000", "Finance income", terms(text, "1312000", "+Sales and revenue", "+Other income")),
                byNature(text, "1312000", "Finance income", "[]", "[]"));

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        assertThat(report.modelCalls()).isEqualTo(2);
        assertThat(prompts.get(1)).contains("revenue is 370520164434 but the profit or loss's own 'Sales and revenue' (1312000!R",
                ") is 355259333593 in c1: revenue is that row alone");
        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isEqualByComparingTo("355259333593");
        assertThat(income.checks()).anyMatch(c -> c.severity() == Check.Severity.OK && c.message().startsWith("revenue = 'Sales and revenue'"));
        assertThat(report.problems()).isEmpty();
    }

    /** Kept in the last answer: stored as the model mapped it, and the upload has an open item that says so. */
    @Test
    void revenueWithOtherIncomeInTheLastAnswerIsAnOpenItem() throws Exception {
        IngestionSession session = session("BUVA", "2024-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(byNature(text, "1312000", "Finance income", terms(text, "1312000", "+Sales and revenue", "+Other income")));

        FullTextExtractor.Report report = extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        assertThat(report.problems()).singleElement().asString().contains("2024 FY income_statement", "stored as the model mapped it",
                "revenue is 370520164434 but the profit or loss's own 'Sales and revenue'");
    }

    /**
     * EXCL FY2023 (by nature) leaves 'Sales and revenue' empty in both columns; the revenue is only in its breakdown by type
     * (3617000). The model mapped 'Other income' 424,062,000,000 as revenue and an operating loss of 27.6 trillion. The
     * text now shows the row with the breakdown's total, which the model can name like a filed amount.
     */
    @Test
    void revenueNotTaggedOnTheFaceIsShownWithTheBreakdownTotal() throws Exception {
        IngestionSession session = session("EXCL", "2023-Tahunan");
        assertThat(session.mapper().incomeByNature()).isTrue();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000, FullTextExtractor.revenueFills(session.mapper()));
        assertThat(text.text()).contains("| Sales and revenue | c1=32322651000000 (not tagged on the face: the total of the revenue "
                + "breakdown, sheet 3617000) | c2=29141994000000 (not tagged on the face");
        String sheet = "3312000";
        String profitOrLoss = object(Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Sales and revenue")),
                Map.entry("operating_income", terms(text, sheet, "+Sales and revenue", "+Other income", "-Interconnection expenses",
                        "-Ticketing, sales and promotion expenses", "-Network operation expenses", "-Employee benefits expenses",
                        "-Depreciation and amortisation expenses", "-Other expenses")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)"))));
        answers("{\"income_statement\": " + profitOrLoss + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}");

        FullTextExtractor.Report report = extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isEqualByComparingTo("32322651000000");
        // 32,322,651 + other income 424,062 - expenses by nature 28,023,797 (million); + other gains 12,977 + finance income
        // 100,096 - finance costs 2,939,979 - associates 191,493 = profit before tax 1,704,517
        assertThat(income.values().get("operating_income")).isEqualByComparingTo("4722916000000");
        assertThat(report.problems()).noneMatch(p -> p.contains("revenue is"));
    }

    /** Expense rows of ISAT's profit or loss by nature (3312000), as operating income subtracts them. */
    private static final String[] ISAT_EXPENSES = {"-Interconnection expenses", "-Ticketing, sales and promotion expenses",
            "-Maintenance and overhaul expenses", "-Network operation expenses", "-Employee benefits expenses",
            "-Depreciation and amortisation expenses", "-Other expenses"};

    /**
     * ISAT FY2023 (by nature): the model's operating income subtracted every expense row, its operating expenses only
     * depreciation, employee benefits and other expenses (19,150,447 of 41,911,981 million). Shown to the model; kept in
     * its last answer, the operating expenses are the rows its operating income subtracts: with them, operating income
     * and the rows below it give the filed profit before tax.
     */
    @Test
    void operatingExpensesByNatureAreTheRowsOperatingIncomeSubtracts() throws Exception {
        IngestionSession session = session("ISAT", "2023-Tahunan");
        assertThat(session.mapper().incomeByNature()).isTrue();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000, FullTextExtractor.revenueFills(session.mapper()));
        String answer = isat(text, operating("+Sales and revenue"),
                new String[] {"+Depreciation and amortisation expenses", "+Employee benefits expenses", "+Other expenses"});
        answers(answer, answer);

        FullTextExtractor.Report report = extractor(2).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature",
                new AgentTrace());

        assertThat(prompts.get(1)).contains("operating_expenses is 19150447000000 but in a profit or loss by nature it is "
                + "every expense row operating_income subtracts").contains("= 41911981000000");
        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("41911981000000");
        assertThat(income.values().get("operating_income")).isEqualByComparingTo("9316801000000");
        assertThat(income.checks()).anyMatch(c -> c.message().startsWith("Profit from operations as defined, from the model's "
                + "own rows: operating_expenses 41911981000000 (model: 19150447000000)"));
        assertThat(report.notes()).anyMatch(n -> n.contains("profit from operations as defined"));
        assertThat(report.problems()).noneMatch(p -> p.contains("operating_expenses is"));
    }

    /**
     * ISAT FY2022 (the comparative of its FY2023 filing): the model counted 'Other gains (losses)' - 4,969,515 million,
     * below operating profit - in operating income. Without it, operating income + the rows below it give the filed
     * profit before tax 6,535,789: 46,752,319 - 40,986,797 = 5,765,522.
     */
    @Test
    void aNonOperatingRowTheModelKeepsInOperatingIncomeIsLeftOut() throws Exception {
        IngestionSession session = session("ISAT", "2023-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000, FullTextExtractor.revenueFills(session.mapper()));
        String answer = isat(text, operating("+Sales and revenue", "+Other gains (losses)"),
                java.util.Arrays.stream(ISAT_EXPENSES).map(e -> "+" + e.substring(1)).toArray(String[]::new));
        answers(answer);

        extractor(1).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(income.values().get("operating_income")).isEqualByComparingTo("5765522000000");
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("40986797000000");
        assertThat(income.derivations().get("operating_income")).doesNotContain("Other gains (losses)");
    }

    /**
     * By nature, where no choice of the model's rows gives the filed profit before tax (depreciation left out of its
     * operating income), the template's own operating income is used: profit before tax less the rows below operating
     * profit, and every expense row by nature - only because revenue - the expense rows give the same figure.
     */
    @Test
    void byNatureTheTemplatesOperatingIncomeWhereTheModelsRowsDoNotReconcile() throws Exception {
        IngestionSession session = session("ISAT", "2023-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000, FullTextExtractor.revenueFills(session.mapper()));
        List<String> withoutDepreciation = new ArrayList<>(List.of(operating("+Sales and revenue")));
        withoutDepreciation.remove("-Depreciation and amortisation expenses");
        answers(isat(text, withoutDepreciation.toArray(String[]::new), new String[] {"+Other expenses"}));

        extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("operating_income")).isEqualByComparingTo("9316801000000");
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("41911981000000");
        assertThat(income.derivations().get("operating_income")).contains("+ 'Total profit (loss) before tax'");
    }

    /**
     * PGEO FY2023 (by nature) leaves 'Sales and revenue' empty and files no breakdown: the model's operating income was
     * the expense rows and 'Other gains (losses)', -163,283 thousand, its operating expenses 'Other expenses' alone.
     * Profit before tax 240,961 less the rows below operating profit gives 221,785; the expenses by nature 184,503.
     */
    @Test
    void byNatureWithoutRevenueOnTheFaceTheTemplatesOperatingIncome() throws Exception {
        IngestionSession session = session("PGEO", "2023-Tahunan");
        assertThat(session.mapper().incomeByNature()).isTrue();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000, FullTextExtractor.revenueFills(session.mapper()));
        String sheet = "3312000";
        Map<String, String> income = Map.ofEntries(
                Map.entry("operating_expenses", terms(text, sheet, "+Other expenses")),
                Map.entry("operating_income", terms(text, sheet, "-Exploration expenses", "-Other expenses", "+Other gains (losses)")),
                Map.entry("interest_income", terms(text, sheet, "+Finance income")),
                Map.entry("interest_expense", terms(text, sheet, "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity")));
        String answer = "{\"income_statement\": " + object(income) + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}";
        answers(answer, answer);

        extractor(2).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "by nature", new AgentTrace());

        assertThat(prompts.get(1)).contains("but operating income + the rows below it must give the filed profit before tax");
        MappedStatement statement = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(statement.values().get("operating_income")).isEqualByComparingTo("221785000");
        // the expenses by nature 184,503,000 less the cost of sales note's 178,977,000, stored as the cost of revenue
        // (withoutRevenueTheNotesCostIsStoredAndAGrossProfitKept)
        assertThat(statement.values().get("operating_expenses")).isEqualByComparingTo("5526000");
        assertThat(statement.values().get("cost_of_revenue")).isEqualByComparingTo("178977000");
        assertThat(statement.values().get("pretax_income")).isEqualByComparingTo("240961000");
    }

    private static String[] operating(String... income) {
        List<String> rows = new ArrayList<>(List.of(income));
        rows.addAll(List.of(ISAT_EXPENSES));
        return rows.toArray(String[]::new);
    }

    private static String isat(WorkbookText text, String[] operatingIncome, String[] operatingExpenses) {
        String sheet = "3312000";
        Map<String, String> income = Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Sales and revenue")),
                Map.entry("operating_expenses", terms(text, sheet, operatingExpenses)),
                Map.entry("operating_income", terms(text, sheet, operatingIncome)),
                Map.entry("interest_income", terms(text, sheet, "+Finance income")),
                Map.entry("interest_expense", terms(text, sheet, "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity")));
        return "{\"income_statement\": " + object(income) + ", \"balance_sheet\": {}, \"cash_flow_statement\": {}}";
    }

    /** A profit or loss by function is not touched: its gross profit is never cleared. */
    @Test
    void aProfitOrLossByFunctionKeepsItsGrossProfit() throws Exception {
        IngestionSession session = session("2024-Tahunan");
        assertThat(session.mapper().incomeByNature()).isFalse();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(mapping(text, "Total assets"));

        extractor(1).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "test", new AgentTrace());

        MappedStatement income = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(income.values().get("gross_profit")).isNotNull();
        assertThat(income.rejected()).isEmpty();
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
        // FY2024 as published: a workbook the rule-based extraction cannot map, so nothing of it can be adopted
        IngestionSession session = session("2024-Tahunan");
        answers("{\"adopt\": [\"PRIOR_PERIOD balance_sheet\", \"PRIOR_PERIOD income_statement\", "
                + "\"PRIOR_PERIOD cash_flow_statement\"], \"notes\": []}");

        FullTextExtractor.Report report = extractor(1).extract(session, List.of(StatementColumn.PRIOR_PERIOD), "test", new AgentTrace());

        assertThat(report.mapped()).isEmpty();
        assertThat(report.notes()).anyMatch(n -> n.contains("\"PRIOR_PERIOD balance_sheet\" cannot be adopted"));
        assertThat(report.problems()).hasSize(3).allMatch(p -> p.contains("not stored, no rows of the statement were named"));
        assertThat(session.balance(StatementColumn.PRIOR_PERIOD)).isEmpty();
        verify(agent, never()).run(any());
    }

    /**
     * BFI Finance files in the Financing Industry taxonomy (sheets 8220000, 8322000, 8510000), which the rule-based
     * extraction does not map. Every sheet of the file is in the text, and the model's rows are stored - the
     * upload does not end "no figures" because the rules do not know the sheet numbers.
     */
    @Test
    void aWorkbookOfATaxonomyTheRulesDoNotMapIsDecidedFromEverySheetOfTheFile() throws Exception {
        IngestionSession session = session("BFIN", "2025-Tahunan");
        assertThat(session.mapper().templateProblems()).singleElement().asString()
                .startsWith("The line items of the Financing Industry taxonomy (8220000, 8322000, 8510000) are not mapped");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        assertThat(text.text()).contains("## Sheet 8220000", "## Sheet 8322000", "## Sheet 8510000", "## Sheet 8410000",
                "| Income from finance lease | c1=3577276 | c2=3361141", "| Total assets | c1=25472524 | c2=25119820");
        answers("{\"balance_sheet\": {\"total_assets\": " + terms(text, "8220000", "+Total assets")
                + ", \"total_liabilities\": " + terms(text, "8220000", "+Total liabilities")
                + ", \"total_equity\": " + terms(text, "8220000", "+Total equity")
                + ", \"long_term_debt\": " + terms(text, "8220000", "+Borrowings third parties", "+Bonds payable") + "},"
                + " \"income_statement\": {\"revenue\": " + terms(text, "8322000", "+Income from finance lease", "+Income from consumer financing")
                + ", \"operating_income\": " + terms(text, "8322000", "+Total profit (loss) before tax")
                + ", \"pretax_income\": " + terms(text, "8322000", "+Total profit (loss) before tax")
                + ", \"income_tax\": " + terms(text, "8322000", "-Tax benefit (expenses)")
                + ", \"net_income\": " + terms(text, "8322000", "+Total profit (loss)")
                + ", \"basic_eps\": " + terms(text, "8322000", "+Basic earnings (loss) per share from continuing operations") + "},"
                + " \"cash_flow_statement\": {\"operating_cash_flow\": "
                + terms(text, "8510000", "+Total net cash flows received from (used in) operating activities")
                + ", \"investing_cash_flow\": " + terms(text, "8510000", "+Total net cash flows received from (used in) investing activities")
                + ", \"financing_cash_flow\": " + terms(text, "8510000", "+Total net cash flows received from (used in) financing activities")
                + ", \"dividends_paid\": " + terms(text, "8510000", "-Dividends paid from financing activities") + "}, \"notes\": []}");

        List<StatementColumn> columns = FullTextExtractor.columnsToExtract(session);
        FullTextExtractor.Report report = extractor(3).extract(session, columns, "Unsupported filing", new AgentTrace());

        assertThat(columns).containsExactly(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD);
        assertThat(report.modelCalls()).isEqualTo(1);
        assertThat(report.problems()).isEmpty();
        assertThat(report.mapped()).hasSize(6);
        MappedStatement balance = session.balance(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(balance.values().get("total_assets")).isEqualByComparingTo("25472524000000");
        assertThat(balance.values().get("total_liabilities")).isEqualByComparingTo("14819233000000");
        assertThat(balance.values().get("total_equity")).isEqualByComparingTo("10653291000000");
        assertThat(balance.values().get("long_term_debt")).isEqualByComparingTo("13595521000000");
        assertThat(balance.derivations().get("total_assets")).contains("(8220000!R");
        MappedStatement income = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(income.period().key()).isEqualTo("2024 FY");
        assertThat(income.values().get("revenue")).isEqualByComparingTo("5031129000000");
        assertThat(income.values().get("pretax_income")).isEqualByComparingTo("1926614000000");
        assertThat(income.values().get("income_tax")).isEqualByComparingTo("361940000000");
        assertThat(income.values().get("net_income")).isEqualByComparingTo("1564674000000");
        assertThat(income.values().get("basic_eps")).isEqualByComparingTo("104");
        MappedStatement cash = session.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(cash.values().get("operating_cash_flow")).isEqualByComparingTo("1413431000000");
        assertThat(cash.values().get("dividends_paid")).isEqualByComparingTo("-1000989000000");
        // nothing of the rules is shown for a taxonomy they do not map
        assertThat(prompts.getFirst()).doesNotContain("Reference - what the rule-based extraction read")
                .contains("## Sheet 8220000", "- PRIOR_PERIOD (2024 FY) cash_flow_statement: value column c2");
    }

    @Test
    void aSignThatContradictsTheFieldIsPointedOutOnceAndTheModelDecides() throws Exception {
        IngestionSession session = session("BFIN", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String totals = "\"operating_cash_flow\": " + terms(text, "8510000", "+Total net cash flows received from (used in) operating activities")
                + ", \"investing_cash_flow\": " + terms(text, "8510000", "+Total net cash flows received from (used in) investing activities")
                + ", \"financing_cash_flow\": " + terms(text, "8510000", "+Total net cash flows received from (used in) financing activities");
        // dividends paid 1,000,989 is a payment: with sign 1 it comes out positive
        answers("{\"cash_flow_statement\": {" + totals + ", \"dividends_paid\": "
                        + terms(text, "8510000", "+Dividends paid from financing activities") + "}}",
                "{\"cash_flow_statement\": {" + totals + ", \"dividends_paid\": "
                        + terms(text, "8510000", "-Dividends paid from financing activities") + "}}");

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "test", new AgentTrace());

        assertThat(prompts.get(1)).contains("dividends_paid is an outflow and must come out negative, but your rows give 1000989 in c1",
                "'Dividends paid from financing activities' holds 1000989 and has sign 1");
        assertThat(session.cashFlow(StatementColumn.CURRENT_PERIOD).orElseThrow().values().get("dividends_paid"))
                .isEqualByComparingTo("-1000989000000");
        // the balance sheet and profit or loss were not named at all although the file has them: reported
        assertThat(report.problems()).hasSize(2)
                .allMatch(p -> p.contains("no rows of the statement were named, although the workbook has figures"));
    }

    /** The full-text extraction asks its own model; without one configured, the standard extraction's. */
    @Test
    void usesItsOwnModelWhenOneIsConfigured() {
        when(agent.options()).thenAnswer(call -> OpenAiChatOptions.builder().model("test"));
        FullTextExtractor own = new FullTextExtractor(agent, null, null, JsonMapper.builder().build(),
                new FallbackProperties("anthropic/claude-sonnet-5.5", "low", true, Duration.ofMinutes(3), Duration.ofMinutes(3),
                        Duration.ofSeconds(150), 120_000, 3));

        assertThat(own.model()).isEqualTo("anthropic/claude-sonnet-5.5");
        assertThat(extractor(3).model()).isEqualTo("test");
    }

    /**
     * WINE FY2023 (error 878): Claude Sonnet 5.5 refuses a request with reasoning switched off ("Reasoning is
     * mandatory for this endpoint and cannot be disabled", HTTP 400), so every full-text extraction failed on its
     * first call. Its calls send the configured reasoning effort instead of the standard extraction's
     * {@code "enabled": false}, and keep the provider exclusions and the cost request.
     */
    @Test
    void sendsItsOwnReasoningEffortInsteadOfTheStandardExtractionsReasoningOff() {
        IngestionAgent standard = new IngestionAgent(null, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)),
                null, "deepseek/deepseek-v4.1-flash-1017", false, Duration.ofSeconds(45), List.of("OpenInference"));
        FullTextExtractor sonnet = new FullTextExtractor(standard, null, null, JsonMapper.builder().build(),
                new FallbackProperties("anthropic/claude-sonnet-5.5", "low", true, Duration.ofMinutes(3), Duration.ofMinutes(3),
                        Duration.ofSeconds(150), 120_000, 3));
        FullTextExtractor inherited = new FullTextExtractor(standard, null, null, JsonMapper.builder().build(),
                new FallbackProperties("", " ", true, Duration.ofMinutes(3), Duration.ofMinutes(3),
                        Duration.ofSeconds(150), 120_000, 3));

        OpenAiChatOptions options = sonnet.options();
        assertThat(options.getModel()).isEqualTo("anthropic/claude-sonnet-5.5");
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getExtraBody()).isEqualTo(Map.of("reasoning", Map.of("effort", "low"),
                "provider", Map.of("ignore", List.of("OpenInference")), "usage", Map.of("include", true)));
        // the standard extraction's own requests are unchanged
        assertThat(standard.options().build().getExtraBody().get("reasoning")).isEqualTo(Map.of("enabled", false));
        // no effort configured: the standard extraction's setting
        assertThat(inherited.options().getExtraBody().get("reasoning")).isEqualTo(Map.of("enabled", false));
    }

    /**
     * TUGU (an insurer, Insurance Industry taxonomy, error 347): the model is told it maps an insurer, sees the
     * temporary syirkah funds field and books the accumulated tabarru' funds there, so the balance sheet balances
     * (before: 29,487,507,698 vs 29,443,810,988, the tabarru' funds missing, and the upload INCOMPLETE). Reinsurance
     * premiums are deducted as filed in both columns (+714,569,758 and -1,307,049,634 thousand): revenue - cost of
     * revenue - G&amp;A is the filed profit before tax in each. Share counts come from the share capital at par 50.
     */
    @Test
    void anInsurerIsMappedWithItsTabarruFundsAndItsShareCapital() throws Exception {
        IngestionSession session = session("TUGU", "2026-II");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        answers(insurer(text, "6312000"));

        List<StatementColumn> columns = FullTextExtractor.columnsToExtract(session);
        FullTextExtractor.Report report = extractor(3).extract(session, columns, "Unsupported filing", new AgentTrace());

        assertThat(report.modelCalls()).isEqualTo(1);
        assertThat(report.problems()).isEmpty();
        assertThat(prompts.getFirst()).doesNotContain("Reference - what the rule-based extraction read");
        MappedStatement balance = session.balance(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(balance.values().get("total_assets")).isEqualByComparingTo("29487507698000");
        assertThat(balance.values().get("total_liabilities")).isEqualByComparingTo("19437509601000");
        assertThat(balance.values().get("temporary_syirkah_funds")).isEqualByComparingTo("43696710000");
        assertThat(balance.values().get("total_equity")).isEqualByComparingTo("10006301387000");
        assertThat(balance.values().get("shares_outstanding")).isEqualByComparingTo("3555575600");
        // an insurer is a financial company: no debt is assumed where it has no borrowing row (no EV, like a bank)
        assertThat(balance.values().get("short_term_debt")).isNull();
        assertThat(balance.values().get("long_term_debt")).isNull();
        assertThat(balance.checks()).noneMatch(c -> c.message().contains("does not hold"));
        assertThat(session.balance(StatementColumn.PRIOR_YEAR_END).orElseThrow().values().get("temporary_syirkah_funds"))
                .isEqualByComparingTo("40280576000");
        MappedStatement current = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(current.values().get("revenue")).isEqualByComparingTo("4269460227000");
        assertThat(current.values().get("revenue").subtract(current.values().get("cost_of_revenue"))
                .subtract(current.values().get("operating_expenses"))).isEqualByComparingTo(current.values().get("pretax_income"));
        assertThat(current.values().get("pretax_income")).isEqualByComparingTo("554777023000");
        // the answer names no gross profit: revenue - cost of revenue, by the field's definition (it was left empty)
        assertThat(current.values().get("gross_profit")).isEqualByComparingTo(
                current.values().get("revenue").subtract(current.values().get("cost_of_revenue")));
        assertThat(current.derivations().get("gross_profit")).isEqualTo("revenue - cost of revenue");
        assertThat(current.values().get("basic_eps")).isEqualByComparingTo("135");
        assertThat(current.values().get("basic_shares")).isEqualByComparingTo("3555575600");
        MappedStatement prior = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.values().get("revenue")).isEqualByComparingTo("5691810918000");
        assertThat(prior.values().get("revenue").subtract(prior.values().get("cost_of_revenue"))
                .subtract(prior.values().get("operating_expenses"))).isEqualByComparingTo("375254569000");
        assertThat(systemPrompt(session)).contains("The company is an insurer (IDX Insurance Industry taxonomy)",
                "- temporary_syirkah_funds: ").doesNotContain("The company is a bank or a financing company");
    }

    /**
     * TUGU FY2022: the model added 'Decrease (increase) in premium income ceded to reinsurancer' to revenue and 'Increase
     * (decrease) in insurance liabilities ceded to reinsurers' to cost of revenue; revenue - costs came to -404,637,096
     * thousand for a profit before tax of 517,197,214. An insurer's lines above profit before tax must give it: the
     * model is shown the difference, and only deducting both (the reinsurers' share) reconciles both columns.
     */
    @Test
    void anInsurersRevenueAndCostsMustGiveItsProfitBeforeTax() throws Exception {
        IngestionSession session = session("TUGU", "2022-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String wrong = insurerIncome(text, "+Decrease (increase) in premium income ceded to reinsurancer",
                "+Increase (decrease) in insurance liabilities ceded to reinsurers");
        String right = insurerIncome(text, "-Decrease (increase) in premium income ceded to reinsurancer",
                "-Increase (decrease) in insurance liabilities ceded to reinsurers");
        answers(wrong, right);

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.CURRENT_PERIOD,
                StatementColumn.PRIOR_PERIOD), "Unsupported filing", new AgentTrace());

        assertThat(report.modelCalls()).isEqualTo(2);
        assertThat(prompts.get(1)).contains("pretax_income = revenue - cost_of_revenue - operating_expenses - interest_expense "
                + "does not hold: 517197214 vs -404637096",
                // the only two rows whose reversed sign closes the gap (517,197,214 + 404,637,096 thousand) exactly
                "revenue - costs equal profit before tax in c1 exactly when the sign of 'Decrease (increase) in premium income "
                        + "ceded to reinsurancer' (6312000!R", ") in revenue and of 'Increase (decrease) in insurance liabilities "
                        + "ceded to reinsurers' (6312000!R", ") in cost_of_revenue is reversed");
        assertThat(report.problems()).isEmpty();
        MappedStatement current = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        // 6,705,580,219 - 3,518,922,914 - 341,732,165 - 502,133,301 + 369,439,119 + 507,219,646
        assertThat(current.values().get("revenue")).isEqualByComparingTo("3219450604000");
        // 2,284,625,795 - 972,264,314 + 1,162,635,982 - 802,649,320
        assertThat(current.values().get("cost_of_revenue")).isEqualByComparingTo("1672348143000");
        MappedStatement prior = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.values().get("revenue").subtract(prior.values().get("cost_of_revenue"))
                .subtract(prior.values().get("operating_expenses"))).isEqualByComparingTo("374456355000");
    }

    /**
     * PNLF FY2024 (error 582): its profit before tax adds 'Decrease (increase) in unearned premiums' as filed (3,269
     * million in FY2024, -760 in FY2023). The model deducted it and kept doing so after it was shown the only reversal
     * that closes the gap: revenue was stored 6,538 million short, operating income 4,248,950 beside a profit before tax
     * of 4,255,488 and the upload INCOMPLETE. The row is reversed for the model in every field that names it.
     */
    @Test
    void theOnlyReversalThatReconcilesAnInsurersProfitBeforeTaxIsMadeWhenTheModelKeepsTheSign() throws Exception {
        IngestionSession session = session("PNLF", "2024-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String[] revenue = {"+Revenue from insurance premiums", "-Reinsurance premiums", "-Retrocession premiums",
                "-Decrease (increase) in unearned premiums", "+Net investment income",
                "+Gains (losses) on sale of securities and mutual funds at fair value through profit of loss",
                "+Unrealised fair value gains (losses) on securities and mutual funds at fair value through profit or loss",
                "+Other underwriting income"};
        String[] cost = {"+Claim expenses", "-Reinsurance claims", "+Increase (decrease) in liability for future policy benefit",
                "-Increase (decrease) in insurance liabilities ceded to reinsurers", "+Commission expenses",
                "+Other underwriting expenses", "+Acquisition costs of insurance contracts"};
        List<String> operating = new ArrayList<>(List.of(revenue));
        for (String c : cost) {
            operating.add((c.charAt(0) == '-' ? "+" : "-") + c.substring(1));
        }
        operating.add("-General and administrative expenses");
        Map<String, String> profitOrLoss = Map.ofEntries(
                Map.entry("revenue", terms(text, "6312000", revenue)),
                Map.entry("cost_of_revenue", terms(text, "6312000", cost)),
                Map.entry("operating_expenses", terms(text, "6312000", "+General and administrative expenses")),
                Map.entry("operating_income", terms(text, "6312000", operating.toArray(String[]::new))),
                Map.entry("pretax_income", terms(text, "6312000", "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, "6312000", "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, "6312000", "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, "6312000", "+Profit (loss) attributable to parent entity")));
        String wrong = "{\"income_statement\": " + object(profitOrLoss) + ", \"notes\": []}";
        answers(wrong, wrong, wrong);

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.CURRENT_PERIOD,
                StatementColumn.PRIOR_PERIOD), "Unsupported filing", new AgentTrace());

        assertThat(prompts.get(1)).contains("revenue - costs equal profit before tax in c1 exactly when the sign of "
                + "'Decrease (increase) in unearned premiums' (6312000!R");
        assertThat(report.problems()).noneMatch(p -> p.contains("income_statement"));
        assertThat(report.notes()).anyMatch(n -> n.startsWith("Full-text extraction of CURRENT_PERIOD 2024 FY income_statement: "
                + "the sign of 'Decrease (increase) in unearned premiums'"));
        MappedStatement current = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        // 2,346,444 - 245,661 - 33,453 + 3,269 + 664,499 + 273,193 - 218,914 + 10,902,669
        assertThat(current.values().get("revenue")).isEqualByComparingTo("13692046000000");
        // 1,501,451 - 259,114 - 42,501 + 7,114 + 335,045 + 50,830 + 97,553
        assertThat(current.values().get("cost_of_revenue")).isEqualByComparingTo("1690378000000");
        assertThat(current.values().get("operating_income")).isEqualByComparingTo("4255488000000");
        assertThat(current.values().get("pretax_income")).isEqualByComparingTo("4255488000000");
        assertThat(current.checks()).anyMatch(c -> c.severity() == Check.Severity.WARNING
                && c.message().startsWith("The sign of 'Decrease (increase) in unearned premiums'"));
        // FY2023: -760 added as filed, the revenue of the FY2023 filing
        MappedStatement prior = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        assertThat(prior.values().get("revenue")).isEqualByComparingTo("14525080000000");
        assertThat(prior.values().get("operating_income")).isEqualByComparingTo("4405601000000");
    }

    /**
     * PEGE (an investment company, errors 1238 - 1245): its profit or loss has no revenue, only general and
     * administrative expenses, finance income (its investment results), other income and other expenses. The model made
     * a revenue of finance income (FY2025 19,282,262,084), of finance and other income in other years; such a revenue
     * is not stored, and a revenue stored before is cleared.
     */
    @Test
    void aRevenueMadeOfFinanceAndOtherIncomeIsNotStored() throws Exception {
        IngestionSession session = session("PEGE", "2025-Tahunan");
        assertThat(session.mapper().reportsNoRevenue(StatementColumn.CURRENT_PERIOD)).isTrue();
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "1311000";
        Map<String, String> profitOrLoss = Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Finance income")),
                Map.entry("operating_expenses", terms(text, sheet, "+General and administrative expenses", "+Other expenses")),
                Map.entry("operating_income", terms(text, sheet, "-General and administrative expenses", "-Other expenses")),
                Map.entry("interest_income", terms(text, sheet, "+Finance income")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity")));
        answers("{\"income_statement\": " + object(profitOrLoss) + ", \"notes\": []}");

        extractor(1).extract(session, List.of(StatementColumn.CURRENT_PERIOD), "No 'Sales and revenue' reported",
                new AgentTrace());

        MappedStatement income = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        assertThat(income.values().get("revenue")).isNull();
        assertThat(income.rejected()).contains("revenue");
        assertThat(income.derivations().get("revenue")).startsWith("not reported: the model named only + 'Finance income'");
        // the rest as the model mapped it: 12,499,052,288 + 35,021,744,977
        assertThat(income.values().get("operating_expenses")).isEqualByComparingTo("47520797265");
        assertThat(income.values().get("pretax_income")).isEqualByComparingTo("-28238535181");
    }

    /**
     * ABDA H1 2026 (error 1246), PSAK 117 from 2025: the H1 2025 column keeps four cells of the former presentation the
     * H1 2026 column leaves empty (unearned premiums -36,572,748, premium income ceded 2,178,670, estimated claims
     * liability -4,409,958, commission 54,538,876 thousand). With them revenue - costs were -2,274,276 for a profit
     * before tax of 86,606,060; the model kept them after it was shown so, and they are left out for it.
     */
    @Test
    void rowsOfAnInsurersComparativeAloneAreLeftOutWhenOnlySoItReconciles() throws Exception {
        IngestionSession session = session("ABDA", "2026-II");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        String sheet = "6312000";
        Map<String, String> profitOrLoss = Map.ofEntries(
                Map.entry("revenue", terms(text, sheet, "+Revenue from insurance premiums", "-Reinsurance premiums",
                        "-Decrease (increase) in premium income ceded to reinsurancer",
                        "+Decrease (increase) in unearned premiums", "+Net investment income", "+Other underwriting income")),
                Map.entry("cost_of_revenue", terms(text, sheet, "+Claim expenses", "-Reinsurance claims",
                        "+Increase (decrease) in estimated claims liability", "+Commission expenses",
                        "+Other underwriting expenses")),
                Map.entry("operating_expenses", terms(text, sheet, "+General and administrative expenses")),
                Map.entry("operating_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity")));
        String answer = "{\"income_statement\": " + object(profitOrLoss) + ", \"notes\": []}";
        answers(answer, answer);

        FullTextExtractor.Report report = extractor(2).extract(session, List.of(StatementColumn.CURRENT_PERIOD,
                StatementColumn.PRIOR_PERIOD), "Unsupported filing", new AgentTrace());

        assertThat(prompts.get(1)).contains("revenue - costs equal profit before tax in c2 exactly without "
                + "'Decrease (increase) in premium income ceded to reinsurancer' (6312000!R");
        assertThat(report.problems()).noneMatch(p -> p.contains("income_statement"));
        assertThat(report.notes()).anyMatch(n -> n.startsWith("Full-text extraction of PRIOR_PERIOD 2025 H1 income_statement: ")
                && n.contains("left out of the model's answer"));
        MappedStatement prior = session.income(StatementColumn.PRIOR_PERIOD).orElseThrow();
        // 513,344,921 - 26,051,158 + 73,538,385 + 6,204,879
        assertThat(prior.values().get("revenue")).isEqualByComparingTo("567037027000");
        // 403,267,530 + 20,187,790 + 25,551,031
        assertThat(prior.values().get("cost_of_revenue")).isEqualByComparingTo("449006351000");
        assertThat(prior.values().get("pretax_income")).isEqualByComparingTo("86606060000");
        // H1 2026 has none of the rows: as the model mapped it
        MappedStatement current = session.income(StatementColumn.CURRENT_PERIOD).orElseThrow();
        // 600,646,610 - 48,280,392 + 76,161,712 + 3,302,226
        assertThat(current.values().get("revenue")).isEqualByComparingTo("631830156000");
        assertThat(current.checks()).noneMatch(c -> c.message().startsWith("Left out of the model's answer"));
    }

    /**
     * PNLF FY2025 (error 581): its FY2024 comparative is restated in part (net investment income, other underwriting
     * income, G&A and profit before tax) beside insurance lines as filed a year before; no choice of signs gives its
     * profit before tax. Nothing is reversed: the model's answer is stored with the open item, as before.
     */
    @Test
    void noReversalIsMadeWhenNoneReconcilesTheProfitBeforeTax() throws Exception {
        IngestionSession session = session("PNLF", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        Map<String, String> profitOrLoss = Map.ofEntries(
                Map.entry("revenue", terms(text, "6312000", "+Revenue from insurance premiums", "-Reinsurance premiums",
                        "-Retrocession premiums", "+Decrease (increase) in unearned premiums", "+Net investment income",
                        "+Other underwriting income")),
                Map.entry("cost_of_revenue", terms(text, "6312000", "+Claim expenses", "-Reinsurance claims",
                        "+Commission expenses", "+Other underwriting expenses")),
                Map.entry("operating_expenses", terms(text, "6312000", "+General and administrative expenses")),
                Map.entry("pretax_income", terms(text, "6312000", "+Total profit (loss) before tax")),
                Map.entry("net_income", terms(text, "6312000", "+Total profit (loss)")));
        String answer = "{\"income_statement\": " + object(profitOrLoss) + ", \"notes\": []}";
        answers(answer, answer, answer);

        FullTextExtractor.Report report = extractor(3).extract(session, List.of(StatementColumn.PRIOR_PERIOD),
                "Unsupported filing", new AgentTrace());

        assertThat(report.problems()).anyMatch(p -> p.startsWith("Full-text extraction of PRIOR_PERIOD 2024 FY income_statement: "
                + "stored as the model mapped it, although ") && p.contains("pretax_income = revenue - cost_of_revenue "
                + "- operating_expenses - interest_expense does not hold: 3986888 vs "));
        assertThat(report.notes()).noneMatch(n -> n.contains("reversed from the model's answer"));
    }

    /**
     * TUGU FY2023 states its EPS after the 2:1 split of 2023-05-24 and the comparative before it, unrestated: the
     * columns contradict each other, and the EPS the model names is not stored either (the model stored 0.372 and
     * 0.195, the figures in thousands).
     */
    @Test
    void theEpsOfAFilingWhoseColumnsContradictEachOtherIsNotStored() throws Exception {
        IngestionSession session = session("TUGU", "2023-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        Map<String, String> profitOrLoss = Map.ofEntries(
                Map.entry("operating_income", terms(text, "6312000", "+Total profit (loss) before tax")),
                Map.entry("pretax_income", terms(text, "6312000", "+Total profit (loss) before tax")),
                Map.entry("net_income", terms(text, "6312000", "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, "6312000", "+Profit (loss) attributable to parent entity")),
                Map.entry("basic_eps", terms(text, "6312000", "+Basic earnings (loss) per share from continuing operations")));
        answers("{\"income_statement\": " + object(profitOrLoss) + ", \"notes\": []}");

        extractor(1).extract(session, FullTextExtractor.columnsToExtract(session), "Unsupported filing", new AgentTrace());

        for (StatementColumn column : List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD)) {
            MappedStatement income = session.income(column).orElseThrow();
            assertThat(income.values().get("basic_eps")).isNull();
            assertThat(income.rejected()).contains("basic_eps", "diluted_eps", "basic_shares");
            assertThat(income.checks()).anyMatch(c -> c.rule().equals("eps_columns"));
            assertThat(income.values().get("net_income_to_parent")).isNotNull();
        }
    }

    /**
     * CNMA (General Industry, balance sheet by order of liquidity) has no bank loans: the model names no debt rows. As
     * in the rule-based extraction, that is 0, not unknown - else total debt, net debt and EV stay empty (every CNMA
     * valuation had no EV / EBITDA and EV / operating profit).
     */
    @Test
    void aBalanceSheetWithoutBorrowingsHasNoDebt() throws Exception {
        IngestionSession session = session("CNMA", "2025-Tahunan");
        WorkbookText text = new WorkbookText(session.mapper().workbook(), 120_000);
        Map<String, String> balance = Map.ofEntries(
                Map.entry("cash_and_equivalents", terms(text, "1220000", "+Cash and cash equivalents")),
                Map.entry("total_assets", terms(text, "1220000", "+Total assets")),
                Map.entry("total_liabilities", terms(text, "1220000", "+Total liabilities")),
                Map.entry("lease_liabilities", terms(text, "1220000", "+Finance lease liabilities")),
                Map.entry("shareholders_equity", terms(text, "1220000", "+Total equity attributable to equity owners of parent entity")),
                Map.entry("non_controlling_interest", terms(text, "1220000", "+Non-controlling interests")),
                Map.entry("total_equity", terms(text, "1220000", "+Total equity")),
                Map.entry("short_term_debt", "[]"),
                Map.entry("long_term_debt", "[]"));
        answers("{\"balance_sheet\": " + object(balance) + ", \"notes\": []}");

        extractor(1).extract(session, FullTextExtractor.columnsToExtract(session), "Unsupported filing", new AgentTrace());

        for (StatementColumn column : List.of(StatementColumn.CURRENT_PERIOD, StatementColumn.PRIOR_PERIOD)) {
            MappedStatement mapped = session.balance(column).orElseThrow();
            assertThat(mapped.values().get("short_term_debt")).isEqualByComparingTo("0");
            assertThat(mapped.values().get("long_term_debt")).isEqualByComparingTo("0");
            assertThat(mapped.derivations().get("long_term_debt")).contains("no such row in the balance sheet");
        }
        assertThat(session.balance(StatementColumn.CURRENT_PERIOD).orElseThrow().values().get("lease_liabilities"))
                .isEqualByComparingTo("1677168000000");
    }

    // ------------------------------------------------------------------ helpers

    /** The system prompt of the full-text extraction for the session's workbook. */
    private String systemPrompt(IngestionSession session) throws Exception {
        var method = FullTextExtractor.class.getDeclaredMethod("systemPrompt", boolean.class, boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(null, session.mapper().financial(), session.mapper().insurance());
    }

    /**
     * TUGU FY2022 (pre-2023 template, no tabarru' funds): a mapping of its statements whose revenue and cost of revenue
     * take the reinsurers' share of premiums and of insurance liabilities with the given signs.
     */
    private static String insurerIncome(WorkbookText text, String cededPremiums, String cededLiabilities) {
        Map<String, String> profitOrLoss = Map.ofEntries(
                Map.entry("revenue", terms(text, "6312000", "+Revenue from insurance premiums", "-Reinsurance premiums",
                        cededPremiums, "+Commission income", "+Net investment income", "+Other underwriting income")),
                Map.entry("cost_of_revenue", terms(text, "6312000", "+Claim expenses", "-Reinsurance claims",
                        "+Increase (decrease) in estimated claims liability", cededLiabilities)),
                Map.entry("gross_profit", "[]"),
                Map.entry("operating_expenses", terms(text, "6312000", "+General and administrative expenses")),
                Map.entry("operating_income", terms(text, "6312000", "+Total profit (loss) before tax")),
                Map.entry("pretax_income", terms(text, "6312000", "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, "6312000", "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, "6312000", "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, "6312000", "+Profit (loss) attributable to parent entity")));
        Map<String, String> balance = Map.ofEntries(
                Map.entry("cash_and_equivalents", terms(text, "6220000", "+Cash and cash equivalents")),
                Map.entry("total_assets", terms(text, "6220000", "+Total assets")),
                Map.entry("total_liabilities", terms(text, "6220000", "+Total liabilities")),
                Map.entry("shareholders_equity", terms(text, "6220000", "+Total equity attributable to equity owners of parent entity")),
                Map.entry("non_controlling_interest", terms(text, "6220000", "+Non-controlling interests")),
                Map.entry("total_equity", terms(text, "6220000", "+Total equity")));
        Map<String, String> cash = Map.ofEntries(
                Map.entry("operating_cash_flow", terms(text, "6510000", "+Total net cash flows received from (used in) operating activities")),
                Map.entry("investing_cash_flow", terms(text, "6510000", "+Total net cash flows received from (used in) investing activities")),
                Map.entry("financing_cash_flow", terms(text, "6510000", "+Total net cash flows received from (used in) financing activities")),
                Map.entry("cash_change", terms(text, "6510000", "+Total net increase (decrease) in cash and cash equivalents")));
        return "{\"income_statement\": " + object(profitOrLoss) + ",\n \"balance_sheet\": " + object(balance)
                + ",\n \"cash_flow_statement\": " + object(cash) + ",\n \"notes\": [\"scripted\"]}";
    }

    /** A mapping of an insurer's statements (TUGU, Insurance Industry taxonomy), profit or loss from {@code income}. */
    private static String insurer(WorkbookText text, String income) {
        Map<String, String> profitOrLoss = Map.ofEntries(
                Map.entry("revenue", terms(text, income, "+Revenue from insurance premiums", "-Reinsurance premiums",
                        "+Net investment income", "+Other underwriting income")),
                Map.entry("cost_of_revenue", terms(text, income, "+Claim expenses", "+Other underwriting expenses")),
                Map.entry("operating_expenses", terms(text, income, "+General and administrative expenses")),
                Map.entry("operating_income", terms(text, income, "+Total profit (loss) before tax")),
                Map.entry("pretax_income", terms(text, income, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, income, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, income, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, income, "+Profit (loss) attributable to parent entity")),
                Map.entry("basic_eps", terms(text, income, "+Basic earnings (loss) per share from continuing operations")));
        Map<String, String> balance = Map.ofEntries(
                Map.entry("cash_and_equivalents", terms(text, "6220000", "+Cash and cash equivalents")),
                Map.entry("total_assets", terms(text, "6220000", "+Total assets")),
                Map.entry("total_liabilities", terms(text, "6220000", "+Total liabilities")),
                Map.entry("temporary_syirkah_funds", terms(text, "6220000", "+Total temporary syirkah funds")),
                Map.entry("shareholders_equity", terms(text, "6220000", "+Total equity attributable to equity owners of parent entity")),
                Map.entry("non_controlling_interest", terms(text, "6220000", "+Non-controlling interests")),
                Map.entry("total_equity", terms(text, "6220000", "+Total equity")));
        Map<String, String> cash = Map.ofEntries(
                Map.entry("operating_cash_flow", terms(text, "6510000", "+Total net cash flows received from (used in) operating activities")),
                Map.entry("investing_cash_flow", terms(text, "6510000", "+Total net cash flows received from (used in) investing activities")),
                Map.entry("financing_cash_flow", terms(text, "6510000", "+Total net cash flows received from (used in) financing activities")),
                Map.entry("dividends_paid", terms(text, "6510000", "-Dividends paid from financing activities")),
                Map.entry("cash_change", terms(text, "6510000", "+Total net increase (decrease) in cash and cash equivalents")),
                Map.entry("ending_cash", terms(text, "6510000", "+Cash and cash equivalents cash flows, end of the period")));
        return "{\"income_statement\": " + object(profitOrLoss) + ",\n \"balance_sheet\": " + object(balance)
                + ",\n \"cash_flow_statement\": " + object(cash) + ",\n \"notes\": [\"scripted\"]}";
    }

    private FullTextExtractor extractor(int rounds) {
        return new FullTextExtractor(agent, null, null, JsonMapper.builder().build(),
                new FallbackProperties("", "", true, Duration.ofMinutes(3), Duration.ofMinutes(3), Duration.ofSeconds(150), 120_000, rounds));
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

    /** A mapping of the NSSS statements (profit or loss by nature) with the given cost of revenue and gross profit. */
    private static String nsss(WorkbookText text, String costOfRevenue, String grossProfit) {
        return byNature(text, "1312000", "Finance income", costOfRevenue, grossProfit);
    }

    /** A mapping of a filing by nature whose profit or loss is {@code sheet} (1312000, or 1322000 for MSIN). */
    private static String byNature(WorkbookText text, String sheet, String financeIncome, String costOfRevenue, String grossProfit) {
        // no 'profit from operation' row by nature: the terms that compute it
        return byNature(text, sheet, financeIncome, costOfRevenue, grossProfit, terms(text, sheet,
                "+Total profit (loss) before tax", "+Interest and finance costs", "-" + financeIncome));
    }

    /** {@link #byNature(WorkbookText, String, String, String, String)} with the given revenue rows. */
    private static String byNature(WorkbookText text, String sheet, String financeIncome, String revenue) {
        return byNature(text, sheet, financeIncome, "[]", "[]", terms(text, sheet,
                "+Total profit (loss) before tax", "+Interest and finance costs", "-" + financeIncome), revenue);
    }

    private static String byNature(WorkbookText text, String sheet, String financeIncome, String costOfRevenue, String grossProfit,
                                   String operatingIncome) {
        return byNature(text, sheet, financeIncome, costOfRevenue, grossProfit, operatingIncome,
                terms(text, sheet, "+Sales and revenue"));
    }

    private static String byNature(WorkbookText text, String sheet, String financeIncome, String costOfRevenue, String grossProfit,
                                   String operatingIncome, String revenue) {
        Map<String, String> income = Map.ofEntries(
                Map.entry("revenue", revenue),
                Map.entry("cost_of_revenue", costOfRevenue),
                Map.entry("gross_profit", grossProfit),
                Map.entry("operating_income", operatingIncome),
                Map.entry("interest_income", terms(text, sheet, "+" + financeIncome)),
                Map.entry("interest_expense", terms(text, sheet, "+Interest and finance costs")),
                Map.entry("pretax_income", terms(text, sheet, "+Total profit (loss) before tax")),
                Map.entry("income_tax", terms(text, sheet, "-Tax benefit (expenses)")),
                Map.entry("net_income", terms(text, sheet, "+Total profit (loss)")),
                Map.entry("net_income_to_parent", terms(text, sheet, "+Profit (loss) attributable to parent entity")),
                Map.entry("basic_eps", terms(text, sheet, "+Basic earnings (loss) per share from continuing operations")));
        Map<String, String> balance = Map.ofEntries(
                Map.entry("total_assets", terms(text, "1210000", "+Total assets")),
                Map.entry("total_liabilities", terms(text, "1210000", "+Total liabilities")),
                Map.entry("shareholders_equity", terms(text, "1210000", "+Total equity attributable to equity owners of parent entity")),
                Map.entry("non_controlling_interest", terms(text, "1210000", "+Non-controlling interests")),
                Map.entry("total_equity", terms(text, "1210000", "+Total equity")));
        Map<String, String> cash = Map.ofEntries(
                Map.entry("operating_cash_flow", terms(text, "1510000", "+Total net cash flows received from (used in) operating activities")),
                Map.entry("investing_cash_flow", terms(text, "1510000", "+Total net cash flows received from (used in) investing activities")),
                Map.entry("financing_cash_flow", terms(text, "1510000", "+Total net cash flows received from (used in) financing activities")),
                Map.entry("cash_change", terms(text, "1510000", "+Total net increase (decrease) in cash and cash equivalents")));
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

    /**
     * AHAP FY2024 (errors 1146 - 1148), an insurer with no financing activity: no financing rows, no total; operating
     * 1,356,295,483 + investing -580,969,819 = net change 775,325,664. Financing is 0. A section that is not empty, or
     * two empty sections, or others that do not add up to the net change: left as they are.
     */
    @Test
    void anEmptyCashFlowSectionIsZeroWhenTheOthersGiveTheNetChange() {
        Map<String, BigDecimal> values = cashFlow("1356295483", "-580969819", null, "775325664");
        Map<String, String> how = new java.util.HashMap<>();
        FullTextExtractor.emptySectionAsZero(values, how, BigDecimal.valueOf(2));
        assertThat(values.get("financing_cash_flow")).isEqualByComparingTo("0");
        assertThat(how.get("financing_cash_flow")).contains("the other sections add up to the change in cash");

        Map<String, BigDecimal> notAddingUp = cashFlow("1356295483", "-580969819", null, "30775325664");
        FullTextExtractor.emptySectionAsZero(notAddingUp, new java.util.HashMap<>(), BigDecimal.valueOf(2));
        assertThat(notAddingUp.get("financing_cash_flow")).isNull();

        Map<String, BigDecimal> twoEmpty = cashFlow("775325664", null, null, "775325664");
        FullTextExtractor.emptySectionAsZero(twoEmpty, new java.util.HashMap<>(), BigDecimal.valueOf(2));
        assertThat(twoEmpty.get("investing_cash_flow")).isNull();
        assertThat(twoEmpty.get("financing_cash_flow")).isNull();
    }

    private static Map<String, BigDecimal> cashFlow(String operating, String investing, String financing, String change) {
        Map<String, BigDecimal> values = new java.util.HashMap<>();
        values.put("operating_cash_flow", operating == null ? null : new BigDecimal(operating));
        values.put("investing_cash_flow", investing == null ? null : new BigDecimal(investing));
        values.put("financing_cash_flow", financing == null ? null : new BigDecimal(financing));
        values.put("cash_change", change == null ? null : new BigDecimal(change));
        return values;
    }

    private static IngestionSession session(String filing) throws Exception {
        return session("TINS", filing);
    }

    private static IngestionSession session(String ticker, String filing) throws Exception {
        Path file = Path.of("..", "data", "IDX_XBRL", ticker, "xlsx", "FinancialStatement-" + filing + "-" + ticker + ".xlsx");
        assumeTrue(Files.exists(file), ticker + " source data not available: " + file);
        IdxWorkbook workbook;
        try (InputStream in = Files.newInputStream(file)) {
            workbook = new IdxWorkbookReader().read(in, file.getFileName().toString());
        }
        return new IngestionSession(new FilingMapper(workbook));
    }
}
