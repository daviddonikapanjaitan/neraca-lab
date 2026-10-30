package com.neracalab.backend.ingestion.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import com.neracalab.backend.ingestion.agent.WorkbookText.Kind;
import com.neracalab.backend.ingestion.mapping.Check;
import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.mapping.IncomeLineCategory;
import com.neracalab.backend.ingestion.mapping.MappedStatement;
import com.neracalab.backend.ingestion.mapping.StatementColumn;
import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.neracalab.backend.ingestion.xlsx.IdxSheets;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The full-text extraction: the fallback for a workbook the standard extraction cannot store (it takes too
 * long, the workbook fails a validation, the result is incomplete, or it failed). The text of the whole workbook
 * ({@link WorkbookText}) is given to the model, and <b>the model decides every value that is stored</b>: for
 * every database field of every statement that is not stored yet, which rows of the workbook make it up.
 * <p>
 * No rule of the Java code decides or overrules a value here. The code only
 * <ul>
 *   <li>reads the amounts of the rows the model names from the workbook's cells and scales them by its rounding
 *       unit - the model answers with row references and signs, so a stored figure always exists in the workbook
 *       and cannot be mistyped;</li>
 *   <li>tells the model what the rule-based extraction had read and which of its checks failed (a reference
 *       only; it may be wrong, which is why the fallback runs);</li>
 *   <li>shows the model where its own mapping contradicts itself (assets = liabilities + equity, gross profit =
 *       revenue - cost of revenue, a required total without rows) and lets it answer again, up to
 *       {@code neracalab.ingestion.fallback.rounds} times.</li>
 * </ul>
 * The model's last answer is what is stored. A contradiction that remains is not a reason to drop the statement:
 * it is saved as mapped, the upload ends INCOMPLETE and the contradiction is recorded in {@code ingestion_error}.
 * <p>
 * The mapped statements replace the mapper's in the session ({@link IngestionSession#fullTextStatements}); they
 * are saved by the same tools as every other upload (the current period replaces, comparatives fill gaps) and
 * the result is verified by the database read-back. Columns the standard extraction had already saved and
 * verified are not touched.
 */
@Component
public class FullTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(FullTextExtractor.class);

    /**
     * What the full-text extraction did.
     *
     * @param columns    columns it worked on
     * @param modelCalls model calls made (0: nothing to ask, e.g. the column is empty in the workbook)
     * @param mapped     statements it provides, e.g. "PRIOR_PERIOD 2024 FY income_statement"
     * @param problems   what it could not provide
     */
    public record Report(List<String> columns, int modelCalls, List<String> mapped, List<String> problems, List<String> notes) {
    }

    /** A field the model maps: how it is defined and signed. */
    private record Field(String name, String definition) {
    }

    private static final List<Field> INCOME_FIELDS = List.of(
            new Field("revenue", "total sales and revenue. A financing company has no such row either: income from consumer financing, finance lease, factoring, murabahah / ijarah and other sharia financing + administration income + interest and finance income + other income. A bank has no such row: interest income + sharia income + insurance premium and other insurance income + fees and commissions + gains on trading, on marketable securities and on foreign exchange + other operating income; NOT recoveries of written-off assets and reversals of impairment (those are other operating income below it)"),
            new Field("cost_of_revenue", "cost of sales and revenue, POSITIVE (a bank or financing company: interest and finance costs + the syirkah fund holders' share + insurance claims). A profit or loss presented BY NATURE - expenses by type (raw materials and consumables, employee benefits, depreciation, other expenses) and no 'Cost of sales and revenue' row - has no cost of revenue: []. Never build it from expense rows by nature, and never from a notes sheet"),
            new Field("gross_profit", "total gross profit (a bank or financing company: the rows of revenue with sign 1 and the rows of cost_of_revenue with sign -1). A profit or loss presented BY NATURE has no 'Total gross profit' row and no gross profit: []"),
            new Field("sga_expense", "selling expenses + general and administrative expenses (+ salaries and employee benefits where they are a row of their own), POSITIVE"),
            new Field("operating_expenses", "every operating expense below gross profit: selling, general and administrative, salaries and employee benefits, depreciation, other operating expenses (a bank or financing company: also impairment charges / provision for impairment losses), POSITIVE"),
            new Field("operating_income", "profit from operations. Use the filing's own 'profit from operation' row when it has one; otherwise ALWAYS give the terms that compute it: the gross profit row with sign 1, every operating expense row (selling, general and administrative, other operating expenses) with sign -1, other operating income rows with sign 1 (a profit or loss BY NATURE: the revenue and other income rows with sign 1 and every operating expense row by nature with sign -1). The template's 'Other income' row (sign 1) and 'Other expenses' row (sign -1) are operating and belong to it, as in the rule-based extraction - except a row the filing's own profit before tax does not count (then it is in no field). Without finance income and costs, share of associates, foreign exchange, 'Other gains (losses)' and the other non-operating gains and losses"),
            new Field("depreciation", "depreciation EXPENSE of the period, POSITIVE - only when profit or loss or the cash flow statement shows it as a row of its own (a financing company's 'Depreciation of ... property and equipment'); never accumulated depreciation of the balance sheet; else []"),
            new Field("amortization", "amortization expense of intangible assets of the period, POSITIVE - only when shown as a row of its own; else []"),
            new Field("interest_income", "finance / interest income, POSITIVE"),
            new Field("interest_expense", "interest and finance costs, POSITIVE"),
            new Field("pretax_income", "total profit (loss) before tax, as reported"),
            new Field("income_tax", "income tax EXPENSE as a POSITIVE number: a row 'Tax benefit (expenses)' shows an expense negative, so reference it with sign -1"),
            new Field("net_income", "total profit (loss) of the period (continuing and discontinued operations)"),
            new Field("net_income_to_parent", "profit (loss) attributable to the parent entity"),
            new Field("basic_eps", "basic earnings (loss) per share"),
            new Field("diluted_eps", "diluted earnings (loss) per share"));

    private static final List<Field> BALANCE_FIELDS = List.of(
            new Field("cash_and_equivalents", "cash and cash equivalents. A bank's balance sheet shows only 'Cash': use the cash flow statement's row 'cash and cash equivalents, end of the period' instead (a row of sheet 15...)"),
            new Field("marketable_securities", "short-term investments and current financial assets at fair value through profit or loss (not 'other current financial assets', not derivatives)"),
            new Field("accounts_receivable", "trade receivables, third and related parties (not other receivables)"),
            new Field("inventory", "current inventories"),
            new Field("current_assets", "total current assets"),
            new Field("total_assets", "total assets"),
            new Field("accounts_payable", "trade payables, third and related parties"),
            new Field("deferred_revenue", "current advances from customers, contract liabilities and unearned revenue"),
            new Field("current_liabilities", "total current liabilities"),
            new Field("total_liabilities", "total liabilities"),
            new Field("short_term_debt", "short-term loans + current maturities of long-term borrowings and bonds, WITHOUT lease liabilities"),
            new Field("long_term_debt", "long-term borrowings and bonds net of current maturities, WITHOUT lease liabilities"),
            new Field("lease_liabilities", "lease liabilities, current + non-current: rows of the LIABILITIES side only (never 'Right of use assets', which is an asset); [] when the balance sheet has no lease liability row"),
            new Field("shareholders_equity", "total equity attributable to the owners of the parent"),
            new Field("non_controlling_interest", "non-controlling interests, plus the 'Proforma equity' row when the column has one (equity of an entity combined under common control; total equity includes it, the parent's equity does not)"),
            new Field("total_equity", "total equity"),
            new Field("retained_earnings", "retained earnings: BOTH rows, appropriated (also filed as 'General and legal reserves') + unappropriated"),
            new Field("goodwill", "goodwill"),
            new Field("intangible_assets", "intangible assets other than goodwill"),
            new Field("temporary_syirkah_funds", "total temporary syirkah funds (sharia banks; an insurer: 'Total temporary syirkah funds' = accumulated tabarru' funds of its sharia participants), neither liabilities nor equity: total assets = total liabilities + these + total equity"));

    /**
     * Debt fields a balance sheet without any borrowing row has as 0, as the rule-based extraction stores them (the sum
     * of no rows). Left empty they made total debt, net debt and the enterprise value unknown (CNMA, by order of
     * liquidity: no bank loans since FY2023, every valuation without EV / EBITDA and EV / operating profit).
     */
    private static final List<String> DEBT_FIELDS = List.of("short_term_debt", "long_term_debt", "lease_liabilities");

    /**
     * Per-share fields of the profit or loss, not stored when the filing's EPS columns contradict each other (as in the
     * rule-based extraction: FilingMapper#epsContradiction).
     */
    private static final List<String> EPS_FIELDS = List.of("basic_eps", "diluted_eps", "basic_shares");

    private static final List<Field> CASH_FLOW_FIELDS = List.of(
            new Field("operating_cash_flow", "total net cash flows from (used in) operating activities"),
            new Field("investing_cash_flow", "total net cash flows from (used in) investing activities"),
            new Field("financing_cash_flow", "total net cash flows from (used in) financing activities"),
            new Field("capital_expenditure", "cash paid for property, plant and equipment, intangible assets and investment properties, NEGATIVE (an outflow). 'Payments for acquisition of ...' rows hold positive amounts: sign -1. A bank's 'Proceeds from disposal (acquisition) of property and equipment / intangible assets' rows are signed already (negative = acquisition): sign 1"),
            new Field("acquisitions", "payments to acquire subsidiaries, associates and other businesses, NEGATIVE"),
            new Field("share_buybacks", "cash paid to buy back the company's own shares (treasury stock), NEGATIVE: a 'Payments ...' row with sign -1, a signed row that is already negative with sign 1"),
            new Field("stock_issuance", "proceeds from issuing shares of the parent, POSITIVE"),
            new Field("dividends_paid", "dividends paid, NEGATIVE"),
            new Field("debt_issued", "proceeds from borrowings, bonds, medium term notes, sukuk and subordinated loans, POSITIVE; a signed 'increase (decrease)' row of securities issued counts here when positive"),
            new Field("debt_repaid", "repayments of borrowings, bonds, medium term notes, sukuk and subordinated loans, NEGATIVE"),
            new Field("lease_payments", "payments of lease liabilities (principal), NEGATIVE"),
            new Field("cash_change", "total net increase (decrease) in cash and cash equivalents"),
            new Field("ending_cash", "cash and cash equivalents at the end of the period"));

    /** Fields the model does not map: kept from the standard extraction's statement, else computed or left empty. */
    private static final Map<String, List<String>> KEPT_FIELDS = Map.of(
            "income_statement", List.of("rd_expense", "ebit", "ebitda", "basic_shares", "diluted_shares"),
            "balance_sheet", List.of("shares_outstanding"),
            "cash_flow_statement", List.of());

    /**
     * Totals a statement cannot do without: the model is asked again when it names no rows for one. Profit from
     * operations is one of them: every profit or loss has it or the rows that compute it, and without it the
     * company has no EV / operating profit (MSIN FY2025, by nature: left empty in the model's only answer).
     */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "income_statement", List.of("operating_income", "pretax_income", "net_income"),
            "balance_sheet", List.of("total_assets", "total_liabilities", "total_equity"),
            "cash_flow_statement", List.of("operating_cash_flow", "investing_cash_flow", "financing_cash_flow"));

    private static final Set<String> PER_SHARE = Set.of("basic_eps", "diluted_eps");

    /**
     * End of the message about a field whose answer named a row that cannot be used. Such a field gets no value;
     * the model is asked again, and a field still broken in its last answer is an open item of the upload.
     */
    private static final String LEFT_EMPTY = "; the field is left empty";

    /** Fields a profit or loss by nature does not have; left empty by the model, a value stored before is cleared. */
    private static final List<String> BY_FUNCTION_ONLY = List.of("cost_of_revenue", "gross_profit");

    /** Fields defined as an outflow (negative) or as a positive amount: a value of the other sign is pointed out to the model. */
    private static final Set<String> NEGATIVE = Set.of("capital_expenditure", "acquisitions", "share_buybacks", "dividends_paid",
            "debt_repaid", "lease_payments");
    private static final Set<String> POSITIVE = Set.of("cost_of_revenue", "sga_expense", "operating_expenses", "depreciation",
            "amortization", "interest_income", "interest_expense", "stock_issuance", "debt_issued", "total_assets",
            "total_liabilities", "cash_and_equivalents", "ending_cash");

    private static final Map<Kind, List<Field>> MODEL_FIELDS = Map.of(Kind.INCOME, INCOME_FIELDS, Kind.BALANCE_SHEET, BALANCE_FIELDS,
            Kind.CASH_FLOW, CASH_FLOW_FIELDS);

    private final IngestionAgent agent;
    private final IngestionRepository repository;
    private final IngestionVerifier verifier;
    private final JsonMapper json;
    private final FallbackProperties properties;

    public FullTextExtractor(IngestionAgent agent, IngestionRepository repository, IngestionVerifier verifier,
                             JsonMapper json, FallbackProperties properties) {
        this.agent = agent;
        this.repository = repository;
        this.verifier = verifier;
        this.json = json;
        this.properties = properties;
    }

    public FallbackProperties properties() {
        return properties;
    }

    /**
     * The agent's request options with the full-text extraction's own model and reasoning effort, when configured.
     */
    OpenAiChatOptions options() {
        OpenAiChatOptions.Builder builder = agent.options();
        if (properties.model() != null && !properties.model().isBlank()) {
            builder.model(properties.model().trim());
        }
        OpenAiChatOptions options = builder.build();
        if (properties.reasoningEffort() == null || properties.reasoningEffort().isBlank()) {
            return options;
        }
        Map<String, Object> extra = new LinkedHashMap<>(options.getExtraBody() == null ? Map.of() : options.getExtraBody());
        extra.put("reasoning", Map.of("effort", properties.reasoningEffort().trim()));
        OpenAiChatOptions.Builder withEffort = options.mutate();
        withEffort.extraBody(extra);
        return withEffort.build();
    }

    /** The model that maps the workbook: its own, or the standard extraction's when none is configured. */
    public String model() {
        return options().getModel();
    }

    /**
     * Outcome of {@link #run}.
     *
     * @param report       what the model mapped
     * @param steps        the saves done after it (the standard steps, without the model)
     * @param verification the database read-back after them
     */
    public record Result(Report report, List<String> steps, IngestionVerifier.Verification verification, AgentTrace trace) {
    }

    /**
     * Takes over an upload the standard extraction could not finish: the model maps every statement that is not
     * stored yet from the text of the workbook, then every step still open is done - register the company, save the
     * statements, the revenue segments and the share counts, refresh the derived data - with the standard
     * tools (so the rules of the database apply unchanged: the current period replaces, comparatives fill
     * gaps) and verifies the result. Statements the standard extraction had already saved are not touched.
     * Runs within the session's deadline.
     *
     * @param reason why the standard extraction did not store the workbook
     */
    public Result run(IngestionSession session, String reason) {
        AgentTrace trace = new AgentTrace(session.deadline(), session.modelCalls());
        trace.stage(ModelCallListener.FULL_TEXT);
        List<StatementColumn> columns = columnsToExtract(session);
        Report report = columns.isEmpty() ? new Report(List.of(), 0, List.of(), List.of(), List.of())
                : extract(session, columns, reason, trace);
        IngestionTools tools = new IngestionTools(session, repository, verifier);
        List<String> steps = DeterministicFinisher.finish(tools, session, repository, session.deadline());
        session.deadline().check("the final refresh and verification");
        if (session.hasWrites() && !session.isDerivedCurrent()) {
            repository.refreshDerivedData();
            session.derivedRefreshed();
        }
        return new Result(report, steps, verifier.verify(session), trace);
    }

    /** A term of a field: a statement row and the sign it is added with. */
    record Term(WorkbookText.Row row, int sign) {
    }

    /** One statement of one column that the model has to map. */
    private record Target(StatementColumn column, Kind kind, int valueColumn, Optional<MappedStatement> standard) {

        String name(FilingMapper mapper) {
            return column + " " + mapper.period(column).key() + " " + kind.table();
        }

        /** How the model names the statement in "adopt": "PRIOR_PERIOD income_statement". */
        String key() {
            return column + " " + kind.table();
        }

        /** The rule-based extraction read the statement and it passed every check: the model may adopt it. */
        boolean adoptable() {
            return standard.isPresent() && !standard.get().hasErrors();
        }
    }

    /**
     * The columns the model decides: every column that is not stored yet. A column the standard extraction saved
     * (and verified) is left alone.
     */
    public static List<StatementColumn> columnsToExtract(IngestionSession session) {
        List<StatementColumn> columns = new ArrayList<>();
        for (StatementColumn column : session.mapper().columns()) {
            if (!session.savedStatements().containsKey(column) && !session.fullTextColumns().contains(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * Maps the statements of {@code columns} from the text of the workbook and puts them into the session.
     *
     * @param reason why the standard extraction did not store them (shown to the model)
     */
    public Report extract(IngestionSession session, List<StatementColumn> columns, String reason, AgentTrace trace) {
        FilingMapper mapper = session.mapper();
        WorkbookText text = new WorkbookText(mapper.workbook(), properties.maxChars(), revenueFills(mapper));
        List<String> mapped = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<StatementColumn, List<MappedStatement>> result = new LinkedHashMap<>();
        List<Target> targets = new ArrayList<>();
        // every statement of every column goes to the model: no rule here decides beforehand that there is
        // nothing to extract
        for (StatementColumn column : columns) {
            for (Kind kind : Kind.values()) {
                int valueColumn = kind == Kind.BALANCE_SHEET ? mapper.instantIndex(column) : mapper.durationIndex(column);
                if (valueColumn < 0) {
                    continue;       // the column has no such statement (e.g. the prior year end of an interim filing)
                }
                // what the rule-based extraction read is passed to the model as a reference only
                targets.add(new Target(column, kind, valueColumn, standard(session, column, kind)));
            }
            result.put(column, new ArrayList<>());
        }

        int callsBefore = trace.modelCalls();
        Map<Target, MappedStatement> statements = map(session, text, targets, reason, trace, problems, notes);
        for (Target target : targets) {
            MappedStatement statement = statements.get(target);
            if (statement != null) {
                result.get(target.column()).add(statement);
                mapped.add(target.name(mapper));
            }
        }
        // a column the model found nothing for, because the file has nothing for it: said plainly, after the fact
        for (StatementColumn column : columns) {
            boolean emptyInFile = targets.stream().filter(t -> t.column() == column)
                    .noneMatch(t -> text.hasFigures(t.kind(), t.valueColumn()));
            if (!result.get(column).isEmpty() || !emptyInFile) {
                continue;
            }
            if (column == StatementColumn.CURRENT_PERIOD) {
                problems.add(column + " " + mapper.period(column).key() + ": the workbook has no figures for this "
                        + "period - its column of the balance sheet, profit or loss and cash flow is empty - so nothing "
                        + "can be stored for it. The file itself lacks the data: download it again from IDX, or upload the "
                        + "next filing, whose comparative column carries this period");
            } else {
                notes.add(column + " " + mapper.period(column).key() + ": the workbook has no figures in this comparative "
                        + "column; nothing to store for it");
            }
        }
        result.forEach(session::fullTextStatements);
        problems.forEach(session::fullTextProblem);
        if (text.truncated()) {
            notes.add("Some notes sheets were left out of the text given to the model (limit " + properties.maxChars()
                    + " characters); the statement sheets were complete");
        }
        notes.forEach(session::note);
        return new Report(columns.stream().map(Enum::name).toList(), trace.modelCalls() - callsBefore, mapped, problems, notes);
    }

    /**
     * The revenue of a column whose profit or loss leaves 'Sales and revenue' empty, from its own revenue breakdown
     * ({@link FilingMapper#filedRevenue}): shown to the model in the empty cell, so that it can name the row. EXCL's
     * FY2023 workbook files 32,322,651,000,000 only in its breakdown; without the row the model took 'Other income'.
     */
    static List<WorkbookText.Fill> revenueFills(FilingMapper mapper) {
        List<WorkbookText.Fill> fills = new ArrayList<>();
        for (StatementColumn column : mapper.columns()) {
            try {
                mapper.filedRevenue(column).filter(r -> r.breakdown() != null).ifPresent(r -> fills.add(new WorkbookText.Fill(
                        r.sheet(), r.row(), mapper.durationIndex(column), r.filed(),
                        "not tagged on the face: the total of the revenue breakdown, sheet " + r.breakdown())));
            } catch (RuntimeException e) {
                // a workbook the mapper cannot read: nothing to fill in
            }
        }
        return fills;
    }

    /** The statement of the rule-based extraction, when it can produce one (it cannot for a workbook it cannot map). */
    private static Optional<MappedStatement> standard(IngestionSession session, StatementColumn column, Kind kind) {
        if (!session.mapper().templateProblems().isEmpty()) {
            return Optional.empty();       // a workbook the rules do not cover: nothing of theirs is shown or adoptable
        }
        try {
            return session.statements(column).stream().filter(s -> s.table().equals(kind.table())).findFirst();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ the model's mapping

    /** The model's answer: the rows of every field, for all columns and - where a column differs - for that column. */
    private record Mapping(Map<Kind, Map<String, List<Term>>> shared,
                           Map<StatementColumn, Map<Kind, Map<String, List<Term>>>> overrides, Set<String> adopt) {

        Map<String, List<Term>> of(Target target) {
            Map<String, List<Term>> terms = new LinkedHashMap<>(shared.getOrDefault(target.kind(), Map.of()));
            terms.putAll(overrides.getOrDefault(target.column(), Map.of()).getOrDefault(target.kind(), Map.of()));
            return terms;
        }
    }

    /**
     * Asks the model and returns the statements of its last answer. What contradicts itself is shown to the model,
     * which may answer again; whatever it then decides is used.
     */
    private Map<Target, MappedStatement> map(IngestionSession session, WorkbookText text, List<Target> targets, String reason,
                                             AgentTrace trace, List<String> problems, List<String> notes) {
        FilingMapper mapper = session.mapper();
        List<Kind> kinds = targets.stream().map(Target::kind).distinct().toList();
        List<StatementColumn> columns = targets.stream().map(Target::column).distinct().toList();
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt(mapper.financial(), mapper.insurance())));
        history.add(new UserMessage(userPrompt(mapper, text, targets, kinds, reason)));
        Map<Target, MappedStatement> statements = new LinkedHashMap<>();
        Map<Target, List<String>> open = new LinkedHashMap<>();
        Map<Target, List<String>> hints = new LinkedHashMap<>();
        // the model's terms of each stored statement, and the rows whose reversed sign would reconcile it
        Map<Target, Map<String, List<Term>>> termsOf = new LinkedHashMap<>();
        Map<Target, List<Term>> reversals = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (int round = 1; round <= properties.rounds(); round++) {
            trace.deadline().check("the full-text extraction's model call");
            String answer;
            try {
                answer = agent.call(new Prompt(history, options()), trace, properties.modelCallTimeout())
                        .getResult().getOutput().getText();
            } catch (JobDeadline.JobTimeoutException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("full-text extraction: model call failed in round {}: {}", round, ToolExecutor.rootMessage(e));
                problems.add("Full-text extraction: the model call failed (" + ToolExecutor.rootMessage(e) + ")");
                break;
            }
            errors.clear();
            Mapping mapping;
            try {
                mapping = parse(answer, text, kinds, columns, errors);
            } catch (RuntimeException e) {
                // nothing usable in this answer: the statements of the answer before (if any) stay
                errors.add("The answer is not the JSON document asked for: " + ToolExecutor.rootMessage(e));
                if (round < properties.rounds()) {
                    history.add(new AssistantMessage(answer == null ? "" : answer));
                    history.add(new UserMessage(feedback(mapper, Map.of(), errors)));
                }
                continue;
            }
            open.clear();
            hints.clear();
            for (Target target : targets) {
                List<String> issues = new ArrayList<>();
                termsOf.remove(target);
                reversals.remove(target);
                if (mapping.adopt().contains(target.key()) && target.adoptable()) {
                    statements.put(target, adopted(target));
                    continue;
                }
                if (mapping.adopt().contains(target.key())) {
                    errors.add("\"" + target.key() + "\" cannot be adopted: the rule-based extraction did not read it without "
                            + "failed checks; map its fields");
                }
                List<Term> reversal = new ArrayList<>();
                MappedStatement statement = statement(session, text, target, mapping.of(target), issues, reversal);
                // a value the model's rows gave (not one filled in from the notes)
                if (MODEL_FIELDS.get(target.kind()).stream().map(Field::name)
                        .anyMatch(f -> statement.values().get(f) != null
                                && String.valueOf(statement.derivations().get(f)).startsWith("full-text extraction: "))) {
                    statements.put(target, statement);
                    termsOf.put(target, mapping.of(target));
                    reversals.put(target, reversal);
                    operatingIssue(mapper, text, target, mapping.of(target), statement).ifPresent(c -> issues.add(c.issue()));
                } else if (!text.hasFigures(target.kind(), target.valueColumn())) {
                    statements.remove(target);
                    continue;       // nothing in the file for this statement's column: reported once, per column
                } else {
                    statements.remove(target);
                    issues.clear();
                    issues.add("no rows of the statement were named, although the workbook has figures for it in c"
                            + (target.valueColumn() + 1));
                }
                if (!issues.isEmpty()) {
                    open.put(target, issues);
                }
                List<String> signs = signHints(mapper, statement, text, target, mapping.of(target));
                if (!signs.isEmpty() && statements.containsKey(target)) {
                    hints.put(target, signs);
                }
            }
            log.info("full-text extraction round {}: {} of {} statements mapped, {} with open checks, {} unusable references",
                    round, statements.size(), targets.size(), open.size(), errors.size());
            boolean broken = errors.stream().anyMatch(e -> e.endsWith(LEFT_EMPTY));
            if ((open.isEmpty() && hints.isEmpty() && !broken) || round == properties.rounds()) {
                break;
            }
            // a sign that looks wrong is pointed out once; the model's second answer stands
            if (open.isEmpty() && !broken && round > 1) {
                break;
            }
            Map<Target, List<String>> points = new LinkedHashMap<>(open);
            hints.forEach((target, signs) -> points.merge(target, signs, (a, b) -> {
                List<String> all = new ArrayList<>(a);
                all.addAll(b);
                return all;
            }));
            history.add(new AssistantMessage(answer));
            history.add(new UserMessage(feedback(mapper, points, errors)));
        }
        // a reversed sign the model was shown and kept: when it is the only choice that reconciles an insurer's profit
        // before tax exactly and the statement then has no open check, it is reversed for the model - not stored known wrong
        for (Target target : List.copyOf(open.keySet())) {
            List<Term> rows = reversals.getOrDefault(target, List.of());
            if (rows.isEmpty() || !statements.containsKey(target)) {
                continue;
            }
            List<String> issues = new ArrayList<>();
            MappedStatement corrected = statement(session, text, target, reversed(termsOf.get(target), rows), issues, new ArrayList<>());
            if (!issues.isEmpty()) {
                continue;
            }
            String which = String.join(" and ", rows.stream().map(t -> "'" + t.row().label() + "' (" + t.row().ref() + ")").toList());
            List<Check> checks = new ArrayList<>(corrected.checks());
            checks.add(Check.warning("full_text", "The sign of " + which + " reversed from the model's answer: only so do "
                    + "revenue - costs equal the filed profit before tax"));
            statements.put(target, new MappedStatement(corrected.table(), corrected.column(), corrected.period(),
                    corrected.sourceSheet(), corrected.values(), corrected.derivations(), checks, corrected.unclassified(),
                    corrected.rejected()));
            open.remove(target);
            notes.add("Full-text extraction of " + target.name(mapper) + ": the sign of " + which + " reversed from the "
                    + "model's answer, the only choice that makes revenue - costs equal the filed profit before tax");
        }
        // profit from operations as the fields define it, which the model was shown and did not follow: its own rows
        // without the non-operating ones (and by nature the expense rows it subtracts as the operating expenses), when
        // that gives the filed profit before tax exactly - not stored known wrong
        for (Target target : List.copyOf(open.keySet())) {
            Map<String, List<Term>> terms = termsOf.get(target);
            Optional<OperatingCorrection> correction = terms == null || !statements.containsKey(target) ? Optional.empty()
                    : operatingIssue(mapper, text, target, terms, statements.get(target));
            if (correction.isEmpty()) {
                continue;
            }
            List<String> issues = new ArrayList<>();
            MappedStatement corrected = statement(session, text, target, correction.get().terms(), issues, new ArrayList<>());
            if (!open.get(target).containsAll(issues)) {
                continue;       // the correction would open a check the model's answer passed
            }
            String what = operatingChange(statements.get(target), corrected);
            List<Check> checks = new ArrayList<>(corrected.checks());
            checks.add(Check.warning("full_text", "Profit from operations as defined, from the model's own rows: " + what));
            statements.put(target, new MappedStatement(corrected.table(), corrected.column(), corrected.period(),
                    corrected.sourceSheet(), corrected.values(), corrected.derivations(), checks, corrected.unclassified(),
                    corrected.rejected()));
            if (issues.isEmpty()) {
                open.remove(target);
            } else {
                open.put(target, issues);
            }
            notes.add("Full-text extraction of " + target.name(mapper) + ": profit from operations as defined, from the "
                    + "model's own rows (with them it and the non-operating rows give the filed profit before tax): " + what);
        }
        // a field left empty because its rows cannot be read is an open item; the rest is for the audit trail
        errors.forEach(e -> (e.endsWith(LEFT_EMPTY) ? problems : notes).add("Full-text extraction: " + e));
        // the model's last answer is stored as it is; what does not add up is reported, not dropped
        open.forEach((target, issues) -> problems.add("Full-text extraction of " + target.name(mapper) + ": "
                + (statements.containsKey(target) ? "stored as the model mapped it, although " : "not stored, ")
                + String.join("; ", issues)));
        for (Target target : targets) {
            if (!statements.containsKey(target) && !open.containsKey(target) && text.hasFigures(target.kind(), target.valueColumn())) {
                problems.add("Full-text extraction of " + target.name(mapper) + ": the model gave no usable answer");
            }
        }
        return statements;
    }

    private static String systemPrompt(boolean bank, boolean insurer) {
        StringBuilder sb = new StringBuilder("""
                You map an Indonesian listed company's financial statements, filed as an IDX XBRL workbook, to the \
                fields of a database. You get the text of the whole workbook. Each row of the three statements has \
                a reference such as 1311000!R6 (sheet!row); c1 is the current period, c2 the comparative.

                You decide every value that is stored. A rule-based extraction ran before you and did not get \
                there; what it read and which of its checks failed is given as a reference. Its figures can be \
                right - reuse them when they are - or wrong: where a filing reports an amount twice, shows a line \
                for information only or files it under an unusual label, map what the statement really says, so \
                that the filing's own totals hold.

                For every field, answer which rows make it up: a list of terms {"row": "<reference>", "sign": 1 or -1}. \
                The amounts are read from the workbook by the system and the same rows are used for every period, so:
                - never write an amount, only references that appear in the text;
                - a field that is one row has one term; a field that is a sum lists every row of the sum;
                - use sign -1 to subtract a row or to turn it into the sign the field asks for;
                - prefer the filing's own total row over adding up its parts;
                - never use a row twice within one field, and do not add a subtotal together with its parts;
                - a field the filing does not report is an empty list [];
                - a row whose label names both directions - "Gains (losses) ...", "Increase (decrease) ...", \
                "Proceeds from disposal (acquisition) of ...", "... received (paid)" - is signed already: a negative \
                amount is the loss, the decrease, the acquisition, the payment. A row that names one direction \
                ("Payments ...", "... expenses") holds a positive amount.
                A field normally uses rows of its own statement (balance sheet 12..., profit or loss 13..., cash flow \
                15...); a row of another statement may be used where the field definition says so.

                A statement of the reference that is marked "passed every check" was read completely and \
                consistently by the rule-based extraction. You may adopt it as it is instead of mapping it again: \
                list it in "adopt". Adopt it unless you see that it is wrong.

                Answer with one JSON document and nothing else:
                {"income_statement": {"revenue": [{"row": "1311000!R6", "sign": 1}], "...": []},
                 "balance_sheet": {"...": []},
                 "cash_flow_statement": {"...": []},
                 "overrides": {"PRIOR_PERIOD": {"income_statement": {"operating_expenses": [...]}}},
                 "adopt": ["CURRENT_PERIOD balance_sheet"],
                 "notes": ["anything unusual about the filing, one short sentence each"]}
                Include only the statements you are asked for; a statement that is adopted for every column \
                it is needed for can be left out. "overrides" is optional: use it only when a field \
                is made up of other rows in one column than in the others (a line that counts in one period and \
                repeats another line in the other); its keys are the column names you are given.

                Fields.
                """);
        fields(sb, "income_statement", INCOME_FIELDS, bank);
        fields(sb, "balance_sheet", BALANCE_FIELDS, bank);
        fields(sb, "cash_flow_statement", CASH_FLOW_FIELDS, bank);
        if (bank && !insurer) {
            sb.append("""

                    The company is a bank or a financing company: its balance sheet is presented by order of liquidity, \
                    without a current / non-current split (leave current_assets, current_liabilities, inventory, \
                    accounts_receivable empty) and all its borrowings, bonds and securities issued go to long_term_debt \
                    (customer deposits are not debt). When the balance sheet has its own 'Cash and cash equivalents' \
                    row (a financing company), use that row for cash_and_equivalents. Without a 'profit from operation' \
                    row and without non-operating lines, operating_income is the profit before tax row.
                    """);
        }
        if (insurer) {
            sb.append("""

                    The company is an insurer (IDX Insurance Industry taxonomy). Its balance sheet is presented by order \
                    of liquidity: leave current_assets, current_liabilities, inventory, accounts_receivable and \
                    accounts_payable empty; use its own 'Cash and cash equivalents' row; insurance contract liabilities \
                    are not debt (bonds payable, sukuk and borrowings, if any, go to long_term_debt). Its accumulated \
                    tabarru' funds ('Total temporary syirkah funds') are temporary_syirkah_funds, never part of \
                    total_liabilities. Its profit or loss has no gross profit and no 'profit from operation' row: \
                    revenue = revenue from insurance premiums - reinsurance and retrocession premiums (sign -1, as filed, \
                    in every column: the filing's profit before tax deducts the filed amount whatever its sign) - 'Decrease \
                    (increase) in premium income ceded to reinsurancer' (sign -1) + 'Decrease (increase) in unearned premiums' \
                    + commission income (as filed, also when negative) + net investment income + ujrah received + gains on \
                    investments + other underwriting income; cost_of_revenue = claim expenses - reinsurance and \
                    retrocession claims (sign -1) + 'Increase (decrease) in estimated claims liability' and the other \
                    increases in insurance liabilities - 'Increase (decrease) in insurance liabilities ceded to reinsurers' \
                    (sign -1: the reinsurers' share) + commission, acquisition and other underwriting expenses; \
                    gross_profit = revenue - cost_of_revenue; operating_expenses = general and administrative expenses; \
                    operating_income = profit before tax + interest and finance costs (the profit before tax row alone \
                    when there are none). Check that revenue - cost_of_revenue - operating_expenses - interest and finance \
                    costs reproduces profit before tax in every column.
                    """);
        }
        return sb.toString();
    }

    private static void fields(StringBuilder sb, String table, List<Field> fields, boolean bank) {
        sb.append('\n').append(table).append(":\n");
        for (Field f : fields) {
            if (f.name().equals("temporary_syirkah_funds") && !bank) {
                continue;
            }
            sb.append("- ").append(f.name()).append(": ").append(f.definition()).append('\n');
        }
    }

    private static String userPrompt(FilingMapper mapper, WorkbookText text, List<Target> targets, List<Kind> kinds, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("Filing ").append(mapper.info().fileName()).append(" of ").append(mapper.info().ticker())
                .append(", current period ").append(mapper.info().current().key()).append(".\n");
        sb.append("Statements to map: ").append(kinds.stream().map(Kind::table).toList()).append(".\n");
        sb.append("They are stored for these columns:\n");
        for (Target target : targets) {
            sb.append("- ").append(target.column()).append(" (").append(mapper.period(target.column()).key()).append(") ")
                    .append(target.kind().table()).append(": value column c").append(target.valueColumn() + 1).append('\n');
        }
        if (reason != null && !reason.isBlank()) {
            sb.append("\nWhy the rule-based extraction did not store the filing: ").append(reason).append("\n");
        }
        StringBuilder reference = new StringBuilder();
        for (Target target : targets) {
            target.standard().ifPresent(standard -> reference.append(reference(mapper, target, standard)));
        }
        if (!reference.isEmpty()) {
            sb.append("\nReference - what the rule-based extraction read (amounts in the workbook's unit; it may be wrong "
                    + "or incomplete, you decide):\n").append(reference);
        }
        sb.append("\nText of the workbook:\n\n").append(text.text());
        return sb.toString();
    }

    /** One statement of the rule-based extraction as a reference for the model: its values and what failed. */
    private static String reference(FilingMapper mapper, Target target, MappedStatement standard) {
        StringBuilder sb = new StringBuilder("- ").append(target.adoptable() ? "\"" + target.key() + "\" (passed every check; may be adopted)"
                : "\"" + target.key() + "\" (FAILED, map it yourself)").append(", ").append(mapper.period(target.column()).key()).append(": ");
        List<String> values = new ArrayList<>();
        standard.values().forEach((field, value) -> {
            if (value != null && !KEPT_FIELDS.get(standard.table()).contains(field)) {
                values.add(field + "=" + (PER_SHARE.contains(field) ? value.stripTrailingZeros().toPlainString() : filed(value, mapper)));
            }
        });
        sb.append(String.join(", ", values)).append('\n');
        standard.checks().stream().filter(Check::isError)
                .forEach(c -> sb.append("    failed check: ").append(c.message()).append('\n'));
        if (!standard.unclassified().isEmpty()) {
            sb.append("    lines it could not classify: ").append(standard.unclassified().stream()
                    .map(MappedStatement.UnclassifiedLine::label).toList()).append('\n');
        }
        return sb.toString();
    }

    private static String feedback(FilingMapper mapper, Map<Target, List<String>> open, List<String> errors) {
        StringBuilder sb = new StringBuilder("Your mapping was applied to the workbook. These points do not add up:\n");
        errors.forEach(e -> sb.append("- ").append(e).append('\n'));
        open.forEach((target, issues) -> issues.forEach(i -> sb.append("- ").append(target.name(mapper)).append(": ")
                .append(i).append('\n')));
        sb.append("If a field is mapped to the wrong rows, correct it. If the filing itself is inconsistent, keep your "
                + "mapping and say so in \"notes\". Answer again with the complete JSON document.");
        return sb.toString();
    }

    /** Values whose sign contradicts the field's definition (an outflow that came out positive, ...). */
    private static List<String> signHints(FilingMapper mapper, MappedStatement statement, WorkbookText text, Target target,
                                          Map<String, List<Term>> terms) {
        List<String> hints = new ArrayList<>();
        if (statement.checks().stream().anyMatch(c -> c.message().startsWith("Adopted by the model"))) {
            return hints;
        }
        statement.values().forEach((field, value) -> {
            if (value == null) {
                return;
            }
            boolean outflowPositive = NEGATIVE.contains(field) && value.signum() > 0;
            boolean amountNegative = POSITIVE.contains(field) && value.signum() < 0;
            if (!outflowPositive && !amountNegative) {
                return;
            }
            List<String> rows = new ArrayList<>();
            for (Term term : terms.getOrDefault(field, List.of())) {
                Kind rowKind = Kind.ofSheet(term.row().sheet()).orElse(target.kind());
                int column = rowKind == target.kind() ? target.valueColumn()
                        : rowKind == Kind.BALANCE_SHEET ? mapper.instantIndex(target.column()) : mapper.durationIndex(target.column());
                BigDecimal raw = text.value(term.row(), column);
                if (raw != null) {
                    rows.add(term.row().ref() + " '" + term.row().label() + "' holds " + raw.stripTrailingZeros().toPlainString()
                            + " and has sign " + term.sign());
                }
            }
            hints.add(field + (outflowPositive ? " is an outflow and must come out negative" : " is a positive amount by definition")
                    + ", but your rows give " + filed(value, mapper) + " in c" + (target.valueColumn() + 1) + " (" + String.join("; ", rows)
                    + "). A row that holds a positive payment needs sign -1; a row that is signed already needs sign 1");
        });
        if (target.kind() == Kind.BALANCE_SHEET) {
            // an asset row among the lease liabilities; retained earnings without the appropriated part
            terms.getOrDefault("lease_liabilities", List.of()).stream()
                    .filter(t -> t.row().label().toLowerCase(java.util.Locale.ROOT).contains("asset"))
                    .forEach(t -> hints.add("lease_liabilities uses " + t.row().ref() + " '" + t.row().label()
                            + "', which is an asset: lease liabilities are rows of the liabilities side, or [] when there is none"));
            List<Term> retained = terms.getOrDefault("retained_earnings", List.of());
            if (!retained.isEmpty()) {
                text.rows().stream()
                        .filter(r -> r.sheet().equals(retained.getFirst().row().sheet()))
                        .filter(r -> r.label().equals("Appropriated retained earnings") || r.label().equals("General and legal reserves"))
                        .filter(r -> retained.stream().noneMatch(t -> t.row().ref().equals(r.ref())))
                        .filter(r -> text.value(r, target.valueColumn()) != null && text.value(r, target.valueColumn()).signum() != 0)
                        .forEach(r -> hints.add("retained_earnings leaves out " + r.ref() + " '" + r.label()
                                + "': it is appropriated + unappropriated retained earnings"));
            }
        }
        return hints;
    }

    /** The rule-based statement the model adopted, marked as its decision. */
    private static MappedStatement adopted(Target target) {
        MappedStatement standard = target.standard().orElseThrow();
        List<Check> checks = new ArrayList<>(standard.checks());
        checks.add(Check.ok("full_text", "Adopted by the model: the statement as the rule-based extraction read it (every check passed)"));
        return new MappedStatement(standard.table(), standard.column(), standard.period(), standard.sourceSheet(), standard.values(),
                standard.derivations(), checks, standard.unclassified(), standard.rejected());
    }

    /** The answer as terms per statement and field; references that cannot be used are reported in {@code errors}. */
    private Mapping parse(String answer, WorkbookText text, List<Kind> kinds, List<StatementColumn> columns, List<String> errors) {
        if (answer == null) {
            throw new IllegalStateException("empty answer");
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("no JSON object in the answer");
        }
        JsonNode root = json.readTree(answer.substring(start, end + 1));
        Map<Kind, Map<String, List<Term>>> shared = new LinkedHashMap<>();
        for (Kind kind : kinds) {
            shared.put(kind, table(root.path(kind.table()), kind, text, errors, kind.table(), true));
        }
        Map<StatementColumn, Map<Kind, Map<String, List<Term>>>> overrides = new LinkedHashMap<>();
        for (StatementColumn column : columns) {
            JsonNode forColumn = root.path("overrides").path(column.name());
            for (Kind kind : kinds) {
                Map<String, List<Term>> fields = table(forColumn.path(kind.table()), kind, text, errors,
                        "overrides." + column + "." + kind.table(), false);
                if (!fields.isEmpty()) {
                    overrides.computeIfAbsent(column, c -> new LinkedHashMap<>()).put(kind, fields);
                }
            }
        }
        JsonNode notes = root.path("notes");
        if (notes.isArray()) {
            log.info("full-text extraction notes of the model: {}", notes);
        }
        Set<String> adopt = new java.util.LinkedHashSet<>();
        if (root.path("adopt").isArray()) {
            root.path("adopt").forEach(a -> adopt.add(a.asString("").trim().replaceAll("\\s+", " ")));
        }
        return new Mapping(shared, overrides, adopt);
    }

    /**
     * The fields of one statement in the answer.
     *
     * @param every true: every field of the statement (one the answer leaves out has no rows); false: only the
     *              fields the answer names (an override)
     */
    private static Map<String, List<Term>> table(JsonNode table, Kind kind, WorkbookText text, List<String> errors, String where,
                                                 boolean every) {
        Map<String, List<Term>> fields = new LinkedHashMap<>();
        for (Field field : fieldsOf(kind)) {
            JsonNode terms = table.path(field.name());
            if (!every && !terms.isArray()) {
                continue;
            }
            List<Term> list = new ArrayList<>();
            boolean broken = false;
            if (terms.isArray()) {
                for (JsonNode term : terms) {
                    String ref = term.path("row").asString("");
                    Optional<WorkbookText.Row> row = text.row(ref);
                    if (row.isEmpty()) {
                        errors.add(where + "." + field.name() + ": '" + ref + "' is not a row reference of the text"
                                + " (only the rows of the balance sheet, profit or loss and cash flow sheets can be named)"
                                + LEFT_EMPTY);
                        broken = true;
                        continue;
                    }
                    if (list.stream().anyMatch(t -> t.row().ref().equals(row.get().ref()))) {
                        errors.add(where + "." + field.name() + ": " + ref + " is used twice");
                        continue;
                    }
                    list.add(new Term(row.get(), sign(term.path("sign"))));
                }
            }
            if (broken) {
                // the rest of its rows is not what the model meant: revenue less a row that cannot be read is not
                // a gross profit. The field has no value until the model names rows that exist.
                list.clear();
            }
            fields.put(field.name(), list);
        }
        return fields;
    }

    private static int sign(JsonNode node) {
        if (node.isNumber()) {
            return node.asDouble() < 0 ? -1 : 1;
        }
        return node.asString("1").trim().startsWith("-") ? -1 : 1;
    }

    private static List<Field> fieldsOf(Kind kind) {
        return switch (kind) {
            case INCOME -> INCOME_FIELDS;
            case BALANCE_SHEET -> BALANCE_FIELDS;
            case CASH_FLOW -> CASH_FLOW_FIELDS;
        };
    }

    // ------------------------------------------------------------------ values and checks

    /**
     * The statement of a target from the model's terms. Where the mapping contradicts itself - an identity that
     * does not hold, a required total without rows - is added to {@code issues} (shown to the model, and kept in
     * the statement as warnings); it never keeps the statement from being stored. The rows whose reversed sign
     * reconciles an insurer's profit before tax, when exactly one choice does, are added to {@code reversal}.
     */
    private MappedStatement statement(IngestionSession session, WorkbookText text, Target target, Map<String, List<Term>> terms,
                                      List<String> issues, List<Term> reversal) {
        FilingMapper mapper = session.mapper();
        String table = target.kind().table();
        boolean bank = mapper.financial();
        if (target.kind() == Kind.INCOME && mapper.incomeByNature()) {
            terms = costAsOperatingExpenses(terms);
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, String> how = new LinkedHashMap<>();
        // every row as counted in its field (signed, full units), to point out a row whose sign breaks an identity
        Map<String, List<Counted>> counted = new LinkedHashMap<>();
        String sheet = null;
        for (Field field : fieldsOf(target.kind())) {
            if (field.name().equals("temporary_syirkah_funds") && !bank) {
                continue;
            }
            List<Term> list = terms.getOrDefault(field.name(), List.of());
            BigDecimal sum = null;
            List<String> parts = new ArrayList<>();
            for (Term term : list) {
                // a row of another statement (a bank's cash from the cash flow) is read from that statement's column
                Kind rowKind = Kind.ofSheet(term.row().sheet()).orElse(target.kind());
                int valueColumn = rowKind == target.kind() ? target.valueColumn()
                        : rowKind == Kind.BALANCE_SHEET ? mapper.instantIndex(target.column()) : mapper.durationIndex(target.column());
                BigDecimal raw = text.value(term.row(), valueColumn);
                if (raw == null) {
                    continue;
                }
                if (PER_SHARE.contains(field.name()) && mapper.discontinuedEpsLeftOut(target.column(), term.row().label())) {
                    // the rule-based extraction leaves it out too (FilingMapper#epsWithDiscontinued): MEDC FY2022 files
                    // its total EPS a second time on the row of the discontinued operations
                    parts.add("(not '" + term.row().label() + "' (" + term.row().ref() + "): no EPS of the discontinued operations)");
                    continue;
                }
                sum = (sum == null ? BigDecimal.ZERO : sum).add(term.sign() < 0 ? raw.negate() : raw);
                counted.computeIfAbsent(field.name(), k -> new ArrayList<>())
                        .add(new Counted(term, (term.sign() < 0 ? raw.negate() : raw).multiply(mapper.unit())));
                parts.add((term.sign() < 0 ? "-" : "+") + " '" + term.row().label() + "' (" + term.row().ref() + ")");
                sheet = sheet == null ? term.row().sheet() : sheet;
            }
            BigDecimal value = sum == null ? null
                    : plainScale(sum.multiply(PER_SHARE.contains(field.name()) ? mapper.epsScale(target.column()) : mapper.unit()));
            values.put(field.name(), value);
            how.put(field.name(), value == null ? "full-text extraction: not reported"
                    : "full-text extraction: " + String.join(" ", parts));
        }
        if (target.kind() == Kind.INCOME && mapper.incomeByNature()) {
            // before the checks: a gross profit the model built from expense rows by nature is not held against it
            BY_FUNCTION_ONLY.forEach(field -> values.put(field, null));
        }
        Map<String, BigDecimal> standard = target.standard().map(MappedStatement::values).orElse(Map.of());
        for (String kept : KEPT_FIELDS.get(table)) {
            values.put(kept, standard.get(kept));
            how.put(kept, standard.get(kept) == null ? "not derived by the full-text extraction" : "from the standard extraction");
        }
        if (target.kind() == Kind.INCOME) {
            // depreciation and amortization the statements do not show as rows: from the roll-forward notes
            for (String field : List.of("depreciation", "amortization")) {
                if (values.get(field) == null && standard.get(field) != null) {
                    values.put(field, standard.get(field));
                    how.put(field, "from the rule-based extraction (roll-forward notes)");
                } else if (values.get(field) == null) {
                    noteValue(values, how, field, () -> field.equals("depreciation") ? mapper.depreciation(target.column())
                            : mapper.amortization(target.column()));
                }
            }
        }
        if (target.standard().isEmpty()) {
            shareCounts(session, target, values, how);
        }
        if (target.kind() == Kind.INCOME && !bank) {
            derive(values, how, statedDa(mapper, target.column()));
        }
        Set<String> rejected = new java.util.LinkedHashSet<>();
        String epsContradiction = target.kind() == Kind.INCOME ? mapper.epsContradiction() : null;
        if (epsContradiction != null) {
            // the rule-based extraction does not store them either (FilingMapper#incomeStatement): an EPS whose unit or
            // share basis the filing itself contradicts is not stored, also not over a value stored before
            for (String eps : EPS_FIELDS) {
                values.put(eps, null);
                how.put(eps, "not stored: " + epsContradiction);
                rejected.add(eps);
            }
        }
        if (target.kind() == Kind.BALANCE_SHEET && !bank && values.get("total_liabilities") != null) {
            // not where the balance sheet leaves rows off its face (LPPF FY2023: no row for its bank loans of 550,003
            // million, which 'Total current liabilities' includes): no row there is no evidence of no debt
            Set<String> missing = mapper.fieldsMissingOnTheFace(target.column());
            for (String debt : DEBT_FIELDS) {
                if (values.get(debt) == null && !missing.contains(debt)) {
                    values.put(debt, BigDecimal.ZERO);
                    how.put(debt, "full-text extraction: no such row in the balance sheet (0, as the rule-based extraction stores it)");
                }
            }
        }
        if (target.kind() == Kind.INCOME) {
            // earnings per share has the sign of the profit attributable to the parent (a loss filed without it)
            BigDecimal parent = values.get("net_income_to_parent");
            for (String eps : PER_SHARE) {
                BigDecimal filedEps = values.get(eps);
                if (filedEps != null && parent != null && filedEps.signum() * parent.signum() < 0) {
                    values.put(eps, filedEps.negate());
                    how.put(eps, how.get(eps) + "; sign of the profit attributable to the parent");
                }
            }
        }

        List<Check> checks = new ArrayList<>();
        BigDecimal tolerance = mapper.unit().max(BigDecimal.ONE).multiply(BigDecimal.valueOf(2));
        if (target.kind() == Kind.CASH_FLOW) {
            emptySectionAsZero(values, how, tolerance);
        }
        for (String required : REQUIRED.get(table)) {
            if (values.get(required) == null) {
                issues.add(required + " has no amount: its rows are empty in " + columnName(target) + " or it is not mapped");
            }
        }
        switch (target.kind()) {
            case BALANCE_SHEET -> {
                identity(issues, checks, tolerance, mapper, "total_assets = total_liabilities + temporary_syirkah_funds + total_equity",
                        values.get("total_assets"), sum(values.get("total_liabilities"), values.get("temporary_syirkah_funds"),
                                values.get("total_equity")), values.get("total_liabilities"), values.get("total_equity"));
                identity(issues, checks, tolerance, mapper, "total_equity = shareholders_equity + non_controlling_interest",
                        values.get("total_equity"), sum(values.get("shareholders_equity"), values.get("non_controlling_interest")),
                        values.get("shareholders_equity"));
            }
            case INCOME -> {
                if (!bank || mapper.insurance()) {
                    identity(issues, checks, tolerance, mapper, "gross_profit = revenue - cost_of_revenue", values.get("gross_profit"),
                            sum(values.get("revenue"), negate(values.get("cost_of_revenue"))), values.get("revenue"),
                            values.get("cost_of_revenue"));
                }
                if (!bank) {
                    revenueAsFiled(mapper, target, values.get("revenue"), tolerance, issues, checks);
                }
                if (mapper.insurance()) {
                    // an insurer has no 'profit from operation' row and every line of its profit or loss above profit
                    // before tax is in revenue, cost of revenue, operating expenses or finance costs: they must give it.
                    // TUGU FY2022: 'Decrease (increase) in premium income ceded to reinsurancer' and 'Increase (decrease)
                    // in insurance liabilities ceded to reinsurers' added instead of deducted left revenue - costs at
                    // -404,637,096 thousand for a profit before tax of 517,197,214
                    BigDecimal fromLines = sum(values.get("revenue"), negate(values.get("cost_of_revenue")),
                            negate(values.get("operating_expenses")), negate(values.get("interest_expense")));
                    int before = issues.size();
                    identity(issues, checks, tolerance, mapper,
                            "pretax_income = revenue - cost_of_revenue - operating_expenses - interest_expense",
                            values.get("pretax_income"), fromLines,
                            values.get("revenue"), values.get("cost_of_revenue"), values.get("operating_expenses"));
                    if (issues.size() > before) {
                        signHint(counted, values.get("pretax_income").subtract(fromLines), tolerance, columnName(target))
                                .ifPresent(hint -> {
                                    issues.add(hint.getKey());
                                    reversal.addAll(hint.getValue());
                                });
                    }
                }
                BigDecimal afterTax = sum(values.get("pretax_income"), negate(values.get("income_tax")));
                if (values.get("net_income") != null && values.get("income_tax") != null
                        && afterTax.subtract(values.get("net_income")).abs().compareTo(tolerance) > 0) {
                    checks.add(Check.warning("full_text", "profit before tax - income tax = " + afterTax.toPlainString()
                            + " but the profit of the period is " + values.get("net_income").toPlainString()
                            + " (discontinued operations or a tax row not found)"));
                }
            }
            case CASH_FLOW -> {
                BigDecimal flows = sum(values.get("operating_cash_flow"), values.get("investing_cash_flow"),
                        values.get("financing_cash_flow"));
                if (values.get("cash_change") != null && flows != null
                        && flows.subtract(values.get("cash_change")).abs().compareTo(tolerance) > 0) {
                    checks.add(Check.warning("full_text", "operating + investing + financing cash flow = " + flows.toPlainString()
                            + " but the change in cash is " + values.get("cash_change").toPlainString()
                            + " (effect of exchange rate changes)"));
                }
            }
        }
        issues.forEach(issue -> checks.add(Check.warning("full_text", issue)));
        // what the rule-based extraction read differently, for the audit trail
        List<String> differences = new ArrayList<>();
        standard.forEach((field, read) -> {
            BigDecimal mappedValue = values.get(field);
            if (read != null && mappedValue != null && read.compareTo(mappedValue) != 0 && !KEPT_FIELDS.get(table).contains(field)) {
                differences.add(field + " " + mappedValue.toPlainString() + " (standard extraction: " + read.toPlainString() + ")");
            }
        });
        if (!differences.isEmpty()) {
            checks.add(Check.warning("full_text", "The model decided differently from the rule-based extraction: "
                    + String.join(", ", differences)));
        }
        checks.add(Check.ok("full_text", "Every value decided by the model from the text of the workbook; amounts read from its cells"));
        if (epsContradiction != null) {
            checks.add(Check.warning("eps_columns", epsContradiction + "; the EPS of this column is not stored"));
        }
        Set<String> cleared = new java.util.LinkedHashSet<>(rejected);
        if (target.kind() == Kind.INCOME && mapper.incomeByNature() && costOfSalesFromTheNote(mapper, target, values, how, checks)) {
            // the filing's own cost of sales note gives the cost of revenue and the gross profit
        } else if (target.kind() == Kind.INCOME && mapper.incomeByNature()) {
            for (String field : BY_FUNCTION_ONLY) {
                // not a gap to fill: the filing has no such figure, so one stored by an earlier upload goes. Rows
                // the model named all the same (MSIN: 'Program and broadcasting expenses' as cost of revenue, and
                // no gross profit beside it) are expenses by nature, not a cost of sales: left out
                values.put(field, null);
                cleared.add(field);
                how.put(field, "full-text extraction: not reported (profit or loss by nature has no such row)");
            }
        }
        return new MappedStatement(table, target.column(), mapper.period(target.column()),
                sheet == null ? target.kind().table() : sheet, values, how, checks, List.of(), cleared);
    }

    /**
     * The cost of revenue and gross profit of a profit or loss by nature from the filing's own cost of sales note
     * ({@link FilingMapper#costOfSales}): the face shows no cost of sales, but the note states it (BIPI FY2025: 200,443,257
     * of revenue 235,089,682, gross profit 34,646,425). The cost is one of the expenses by nature the operating income
     * deducts, so it moves out of the operating expenses (212,368,437 -> 11,925,180) and the operating income stays as
     * filed. Only when the note has a positive cost for the column, the revenue is known and the operating expenses hold
     * at least the cost; otherwise the two fields stay empty, as before. A gross profit the model builds from expense rows
     * by nature is still never stored.
     *
     * @return whether the fields were set
     */
    private static boolean costOfSalesFromTheNote(FilingMapper mapper, Target target, Map<String, BigDecimal> values,
                                                  Map<String, String> how, List<Check> checks) {
        BigDecimal cost = mapper.costOfSales(target.column());
        BigDecimal revenue = values.get("revenue");
        BigDecimal expenses = values.get("operating_expenses");
        // a cost less than a filed unit above the expenses is the same amount (RAFI FY2021: 89,901,586,207 in the
        // note, 89,901,586,206.6389 among the expenses of a filing with fractions of a rupiah)
        BigDecimal slack = mapper.precision().max(BigDecimal.ONE);
        if (cost == null || cost.signum() <= 0 || revenue == null || expenses == null
                || cost.compareTo(expenses.add(slack)) >= 0) {
            return false;
        }
        values.put("cost_of_revenue", cost);
        values.put("gross_profit", plainScale(revenue.subtract(cost)));
        values.put("operating_expenses", plainScale(expenses.subtract(cost).max(BigDecimal.ZERO)));
        how.put("cost_of_revenue", "the cost of sales note (" + mapper.sheetCode(IdxSheets.COST_OF_SALES) + ", '"
                + FilingMapper.COST_OF_SALES_TOTAL + "'): the profit or loss by nature shows no cost of sales on its face");
        how.put("gross_profit", "revenue - cost of revenue (from the cost of sales note)");
        how.put("operating_expenses", how.getOrDefault("operating_expenses", "") + "; less the cost of revenue of the "
                + "cost of sales note (" + cost.toPlainString() + ")");
        checks.add(Check.ok("gross_profit", "Profit or loss by nature: cost of revenue " + cost.toPlainString()
                + " from the filing's cost of sales note, gross profit = revenue - cost, the operating expenses without it"));
        return true;
    }

    /**
     * A profit or loss by nature has no cost of sales: rows the model names as cost of revenue are expense rows by
     * nature, and those its operating income subtracts are operating expenses. Cleared with the cost of revenue, they
     * were in no field (MNCN H1 2026: 'Program and broadcasting expenses' 2,133,396 million as cost of revenue, so
     * operating expenses 864,240 beside an operating income of 519,964 that deducts both; 3,517,600 - 2,997,636).
     */
    private static Map<String, List<Term>> costAsOperatingExpenses(Map<String, List<Term>> terms) {
        Set<String> subtracted = terms.getOrDefault("operating_income", List.of()).stream()
                .filter(t -> t.sign() < 0).map(t -> t.row().ref()).collect(java.util.stream.Collectors.toSet());
        List<Term> expenses = new ArrayList<>(terms.getOrDefault("operating_expenses", List.of()));
        Set<String> named = new java.util.HashSet<>(expenses.stream().map(t -> t.row().ref()).toList());
        boolean added = false;
        for (Term cost : terms.getOrDefault("cost_of_revenue", List.of())) {
            if (cost.sign() > 0 && subtracted.contains(cost.row().ref()) && named.add(cost.row().ref())) {
                expenses.add(cost);
                added = true;
            }
        }
        if (!added) {
            return terms;
        }
        Map<String, List<Term>> result = new LinkedHashMap<>(terms);
        result.put("operating_expenses", List.copyOf(expenses));
        return result;
    }

    /**
     * Share counts are not rows of the statements: they come from the share capital of the equity statement (par
     * value, treasury stock, the EPS denominator), as for every upload. Used when the rule-based extraction has no
     * statement to take them from.
     */
    private static void shareCounts(IngestionSession session, Target target, Map<String, BigDecimal> values, Map<String, String> how) {
        try {
            var shares = session.shareCapital();
            StatementColumn column = target.column();
            if (target.kind() == Kind.INCOME && column != StatementColumn.PRIOR_YEAR_END) {
                BigDecimal weighted = column == StatementColumn.CURRENT_PERIOD ? shares.weightedCurrent() : shares.weightedPrior();
                if (weighted != null) {
                    values.put("basic_shares", weighted);
                    how.put("basic_shares", "share capital / par value (unchanged in the period)");
                }
            }
            if (target.kind() == Kind.BALANCE_SHEET) {
                java.time.LocalDate end = session.mapper().period(column).end();
                shares.snapshots().stream().filter(s -> s.date().equals(end) && s.sharesOutstanding() != null).findFirst()
                        .ifPresent(s -> {
                            values.put("shares_outstanding", s.sharesOutstanding());
                            how.put("shares_outstanding", "share count at " + end + " (" + s.source() + ")");
                        });
            }
        } catch (RuntimeException e) {
            log.debug("full-text extraction: share counts not available: {}", e.getMessage());
        }
    }

    /** A value the standard extraction derives from the notes; left empty when it cannot. */
    private static void noteValue(Map<String, BigDecimal> values, Map<String, String> how, String field,
                                  java.util.function.Supplier<BigDecimal> source) {
        try {
            BigDecimal value = source.get();
            if (value != null) {
                values.put(field, value);
                how.put(field, "from the roll-forward notes (standard extraction)");
            }
        } catch (RuntimeException e) {
            log.debug("full-text extraction: {} not derivable from the notes: {}", field, e.getMessage());
        }
    }

    private static String columnName(Target target) {
        return "c" + (target.valueColumn() + 1);
    }

    /**
     * A cash flow section without any row the model could map counts as 0 when the other two add up to the change in
     * cash (as {@code FilingMapper#sectionTotals} counts it): AHAP FY2024, an insurer with no financing activity -
     * no lines, no total - operating 1,356,295,483 + investing -580,969,819 = net change 775,325,664 (errors 1146 -
     * 1148). Otherwise it stays empty, and a required field without an amount is pointed out.
     */
    static void emptySectionAsZero(Map<String, BigDecimal> values, Map<String, String> how, BigDecimal tolerance) {
        List<String> sections = List.of("operating_cash_flow", "investing_cash_flow", "financing_cash_flow");
        List<String> empty = sections.stream().filter(s -> values.get(s) == null).toList();
        BigDecimal change = values.get("cash_change");
        if (empty.size() != 1 || change == null) {
            return;
        }
        BigDecimal others = sections.stream().filter(s -> !empty.contains(s)).map(values::get)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (others.subtract(change).abs().compareTo(tolerance) > 0) {
            return;
        }
        values.put(empty.get(0), BigDecimal.ZERO);
        how.put(empty.get(0), "full-text extraction: no rows in this column; 0, the other sections add up to the change in cash");
    }

    /** The D&A the profit or loss states for the column ({@link FilingMapper#statedDepreciationAndAmortization}). */
    private static BigDecimal statedDa(FilingMapper mapper, StatementColumn column) {
        try {
            return mapper.statedDepreciationAndAmortization(column);
        } catch (RuntimeException e) {
            log.debug("full-text extraction: stated D&A not readable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * ebit and ebitda as the standard extraction derives them: with the D&A the profit or loss states when it states
     * it (the same in every column, as the standard extraction), else depreciation + amortization.
     */
    static void derive(Map<String, BigDecimal> values, Map<String, String> how, BigDecimal statedDa) {
        BigDecimal pretax = values.get("pretax_income");
        if (pretax == null) {
            return;
        }
        BigDecimal ebit = pretax.add(orZero(values.get("interest_expense"))).subtract(orZero(values.get("interest_income")));
        values.put("ebit", ebit);
        how.put("ebit", "profit before tax + finance costs - finance income");
        if (statedDa != null) {
            values.put("ebitda", ebit.add(statedDa));
            how.put("ebitda", "ebit + '" + FilingMapper.STATED_DEPRECIATION_AND_AMORTISATION
                    + "' as the profit or loss states it");
        } else if (values.get("depreciation") != null) {
            values.put("ebitda", ebit.add(values.get("depreciation")).add(orZero(values.get("amortization"))));
            how.put("ebitda", "ebit + depreciation + amortization");
        } else {
            values.put("ebitda", null);
            how.put("ebitda", "not derived: depreciation unknown");
        }
    }

    /** A row as counted in a field: its term and its amount with the term's sign, in full units. */
    private record Counted(Term term, BigDecimal amount) {
    }

    /** Fields of an insurer's revenue - costs, with the sign they take in it (in this order in a hint). */
    private static final List<Map.Entry<String, Integer>> INSURER_RESULT = List.of(Map.entry("revenue", 1),
            Map.entry("cost_of_revenue", -1), Map.entry("operating_expenses", -1), Map.entry("interest_expense", -1));

    /**
     * The row - or the two rows - of revenue, cost of revenue, operating expenses and finance costs whose sign, reversed,
     * makes revenue - costs equal profit before tax exactly; only when exactly one such choice exists. A hint for the
     * model, which decides: TUGU's 'Decrease (increase) in premium income ceded to reinsurancer' reads like a row signed
     * already, but its profit before tax deducts the amount as filed (FY2023: 261,564,001 thousand added instead of
     * deducted, revenue - costs 2,155,458,543 for a profit before tax of 1,632,330,541). When the model's last answer
     * keeps the sign all the same, the rows are reversed for it (see {@link #reversed}).
     *
     * @param gap profit before tax - (revenue - costs), full units
     * @return the hint and the terms whose sign is to be reversed
     */
    private static Optional<Map.Entry<String, List<Term>>> signHint(Map<String, List<Counted>> counted, BigDecimal gap,
                                                                    BigDecimal tolerance, String column) {
        record Candidate(String field, Counted row, BigDecimal effect) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> field : INSURER_RESULT) {
            for (Counted row : counted.getOrDefault(field.getKey(), List.of())) {
                if (row.amount().signum() != 0) {
                    // reversing the row changes revenue - costs by -2 x what it adds now
                    candidates.add(new Candidate(field.getKey(), row, row.amount().multiply(BigDecimal.valueOf(-2L * field.getValue()))));
                }
            }
        }
        List<List<Candidate>> fits = new ArrayList<>();
        for (Candidate one : candidates) {
            if (one.effect().subtract(gap).abs().compareTo(tolerance) <= 0) {
                fits.add(List.of(one));
            }
        }
        if (fits.isEmpty()) {
            for (int i = 0; i < candidates.size(); i++) {
                for (int j = i + 1; j < candidates.size(); j++) {
                    if (candidates.get(i).effect().add(candidates.get(j).effect()).subtract(gap).abs().compareTo(tolerance) <= 0) {
                        fits.add(List.of(candidates.get(i), candidates.get(j)));
                    }
                }
            }
        }
        if (fits.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(Map.entry("revenue - costs equal profit before tax in " + column + " exactly when the sign of "
                + String.join(" and of ", fits.getFirst().stream().map(c -> "'" + c.row().term().row().label() + "' ("
                + c.row().term().row().ref() + ") in " + c.field()).toList())
                + " is reversed: check how the filing's profit before tax counts it",
                fits.getFirst().stream().map(c -> c.row().term()).toList()));
    }

    /**
     * The terms of a statement with the sign of the given rows reversed in every field that names them (revenue and
     * the gross profit or operating income built from the same rows). PNLF FY2024 (error 582): its profit before tax
     * adds 'Decrease (increase) in unearned premiums' as filed (3,269 million in FY2024, -760 in FY2023); the model
     * deducted it, was shown the only reversal that closes the gap (6,538 = 2 x 3,269) and answered the same, so revenue
     * was stored 6,538 million short and operating income 4,248,950 beside a profit before tax of 4,255,488.
     */
    private static Map<String, List<Term>> reversed(Map<String, List<Term>> terms, List<Term> rows) {
        Set<String> refs = rows.stream().map(t -> t.row().ref()).collect(java.util.stream.Collectors.toSet());
        Map<String, List<Term>> result = new LinkedHashMap<>();
        terms.forEach((field, list) -> result.put(field, list.stream()
                .map(t -> refs.contains(t.row().ref()) ? new Term(t.row(), -t.sign()) : t).toList()));
        return result;
    }

    private static final String PROFIT_BEFORE_TAX = "Total profit (loss) before tax";

    /**
     * Where the model's profit from operations is not the one its field defines, although its own rows give it: rows
     * below operating profit counted in operating_income (ISAT FY2022 'Other gains (losses)' 4,969,515 million, the
     * sale of its towers in FY2021; MDKA H1 2025 the foreign exchange, 'Other gains (losses)' and the final tax), and,
     * in a profit or loss by nature, operating_expenses other than the expense rows operating_income subtracts (ISAT
     * FY2023 19,150,447 million of 41,911,981: network operation, interconnection, maintenance and marketing left out;
     * PGEO FY2023 'Other expenses' 5,526 thousand without the exploration expenses of 178,977). Pointed out only when the
     * operating income so corrected and the profit or loss's own rows below it - finance income and costs, the
     * non-operating gains and losses, final tax - give the filed profit before tax exactly. By nature, where no choice of
     * the model's rows reconciles, the template's own operating income is pointed out instead ({@link #byNatureTemplate});
     * by function a filing whose lines do not reconcile is not judged here. A non-financial profit or loss only (banks
     * and insurers define these otherwise), and only when the operating income is built from rows, not from a profit
     * total. The model is shown the issue; the correction is used only when its last answer keeps it.
     */
    private static Optional<OperatingCorrection> operatingIssue(FilingMapper mapper, WorkbookText text, Target target,
                                                                Map<String, List<Term>> terms, MappedStatement statement) {
        if (target.kind() != Kind.INCOME || mapper.financial()) {
            return Optional.empty();
        }
        List<Term> operating = terms.getOrDefault("operating_income", List.of());
        BigDecimal pretax = statement.values().get("pretax_income");
        BigDecimal stored = statement.values().get("operating_income");
        if (operating.isEmpty() || pretax == null || stored == null || operating.stream()
                .anyMatch(t -> t.row().label().toLowerCase(java.util.Locale.ROOT).contains("profit (loss)"))) {
            return Optional.empty();
        }
        String sheet = operating.getFirst().row().sheet();
        if (Kind.ofSheet(sheet).orElse(null) != Kind.INCOME || operating.stream().anyMatch(t -> !t.row().sheet().equals(sheet))) {
            return Optional.empty();
        }
        WorkbookText.Row pretaxRow = text.rows().stream()
                .filter(r -> r.sheet().equals(sheet) && PROFIT_BEFORE_TAX.equals(r.label())).findFirst().orElse(null);
        if (pretaxRow == null) {
            return Optional.empty();
        }
        BigDecimal tolerance = mapper.unit().max(BigDecimal.ONE).multiply(BigDecimal.valueOf(2));
        BigDecimal storedExpenses = statement.values().get("operating_expenses");
        // the rows below operating profit, as filed, and their sum as they enter profit before tax
        List<Term> belowRows = new ArrayList<>();
        BigDecimal below = BigDecimal.ZERO;
        for (WorkbookText.Row row : text.rows()) {
            IncomeLineCategory category = IncomeLineCategory.ofTemplateLine(row.label());
            BigDecimal raw = row.sheet().equals(sheet) && row.index() < pretaxRow.index() && category != null
                    && IncomeLineCategory.BELOW_OPERATING.contains(category) ? text.value(row, target.valueColumn()) : null;
            if (raw != null) {
                below = below.add(category.deducted() ? raw.negate() : raw);
                belowRows.add(new Term(row, category.deducted() ? -1 : 1));
            }
        }
        below = below.multiply(mapper.unit());

        Map<String, List<Term>> defined = operatingAsDefined(terms, mapper.incomeByNature());
        BigDecimal operatingIncome = filedSum(mapper, text, target, defined.get("operating_income"));
        if (operatingIncome != null && operatingIncome.add(below).subtract(pretax).abs().compareTo(tolerance) <= 0) {
            BigDecimal expenses = mapper.incomeByNature() ? filedSum(mapper, text, target, defined.get("operating_expenses")) : null;
            boolean incomeOff = operatingIncome.subtract(stored).abs().compareTo(tolerance) > 0;
            boolean expensesOff = expenses != null
                    && (storedExpenses == null || expenses.subtract(storedExpenses).abs().compareTo(tolerance) > 0);
            if (!incomeOff && !expensesOff) {
                return Optional.empty();
            }
            List<String> parts = new ArrayList<>();
            List<Term> left = operating.stream().filter(FullTextExtractor::belowOperating).toList();
            if (!left.isEmpty()) {
                parts.add("operating_income counts " + rowsOf(left) + ", below profit from operations (the field is without "
                        + "finance income and costs, foreign exchange, 'Other gains (losses)' and the other non-operating gains "
                        + "and losses): without them it is " + filed(operatingIncome, mapper) + " instead of " + filed(stored, mapper));
            }
            if (expensesOff) {
                parts.add("operating_expenses is " + (storedExpenses == null ? "not mapped" : filed(storedExpenses, mapper))
                        + " but in a profit or loss by nature it is every expense row operating_income subtracts: "
                        + rowsOf(defined.get("operating_expenses")) + " = " + filed(expenses, mapper));
            }
            return Optional.of(new OperatingCorrection(String.join("; ", parts) + " (so operating income + the rows below it "
                    + "give the filed profit before tax " + filed(pretax, mapper) + " in " + columnName(target) + ")", defined));
        }
        if (!mapper.incomeByNature()) {
            return Optional.empty();    // by function the rule-based extraction reads it; a filing that does not reconcile is not judged
        }
        // by nature, no choice of the model's rows gives an operating income that reconciles: the template's own (PGEO
        // FY2023 leaves 'Sales and revenue' empty on the face and files no breakdown; the model's operating income of
        // -163,283 thousand was the expense rows and 'Other gains (losses)' without revenue)
        BigDecimal fromTemplate = pretax.subtract(below);
        return byNatureTemplate(mapper, text, target, sheet, pretaxRow, belowRows, fromTemplate, tolerance).map(template -> {
            Map<String, List<Term>> result = new LinkedHashMap<>(terms);
            result.putAll(template);
            BigDecimal expenses = filedSum(mapper, text, target, template.get("operating_expenses"));
            return new OperatingCorrection("operating_income is " + filed(stored, mapper) + ", but operating income + the rows "
                    + "below it must give the filed profit before tax " + filed(pretax, mapper) + " in " + columnName(target)
                    + ": it is " + filed(fromTemplate, mapper) + " = " + rowsOf(template.get("operating_income"))
                    + "; operating_expenses is every expense row by nature: " + rowsOf(template.get("operating_expenses"))
                    + " = " + (expenses == null ? "0" : filed(expenses, mapper)), result);
        });
    }

    /** A correction of the model's operating income: the issue shown to it, and the terms used when it keeps its answer. */
    private record OperatingCorrection(String issue, Map<String, List<Term>> terms) {
    }

    /**
     * Operating income and operating expenses of a profit or loss by nature from its template: profit before tax less the
     * rows below operating profit, and the rows above them that are expenses by nature. Only when every other row with an
     * amount is revenue, other income or an expense row ('... expenses', impairment) and, where revenue is filed on the
     * face, revenue + other income - the expenses give that operating income.
     */
    private static Optional<Map<String, List<Term>>> byNatureTemplate(FilingMapper mapper, WorkbookText text, Target target,
                                                                      String sheet, WorkbookText.Row pretaxRow, List<Term> belowRows,
                                                                      BigDecimal operatingIncome, BigDecimal tolerance) {
        List<Term> expenses = new ArrayList<>();
        List<Term> income = new ArrayList<>();
        for (WorkbookText.Row row : text.rows()) {
            if (!row.sheet().equals(sheet) || row.index() >= pretaxRow.index() || text.value(row, target.valueColumn()) == null) {
                continue;
            }
            IncomeLineCategory category = IncomeLineCategory.ofTemplateLine(row.label());
            if (category != null && IncomeLineCategory.BELOW_OPERATING.contains(category)) {
                continue;
            }
            String label = row.label().toLowerCase(java.util.Locale.ROOT);
            if (category == IncomeLineCategory.REVENUE || category == IncomeLineCategory.OTHER_OPERATING_INCOME) {
                income.add(new Term(row, 1));
            } else if (label.contains("expense") || label.contains("impairment")) {
                expenses.add(new Term(row, 1));
            } else {
                return Optional.empty();   // a row the template does not explain
            }
        }
        if (income.stream().anyMatch(t -> IncomeLineCategory.ofTemplateLine(t.row().label()) == IncomeLineCategory.REVENUE)) {
            BigDecimal spent = filedSum(mapper, text, target, expenses);
            BigDecimal byRows = filedSum(mapper, text, target, income).subtract(spent == null ? BigDecimal.ZERO : spent);
            if (byRows.subtract(operatingIncome).abs().compareTo(tolerance) > 0) {
                return Optional.empty();
            }
        }
        List<Term> operating = new ArrayList<>();
        operating.add(new Term(pretaxRow, 1));
        belowRows.forEach(t -> operating.add(new Term(t.row(), -t.sign())));
        return Optional.of(Map.of("operating_income", List.copyOf(operating), "operating_expenses", List.copyOf(expenses)));
    }

    /**
     * The model's terms with operating income as its field defines it: without the rows below operating profit, and in a
     * profit or loss by nature operating expenses = the expense rows operating income subtracts.
     */
    private static Map<String, List<Term>> operatingAsDefined(Map<String, List<Term>> terms, boolean byNature) {
        List<Term> operating = terms.getOrDefault("operating_income", List.of()).stream()
                .filter(t -> !belowOperating(t)).toList();
        Map<String, List<Term>> result = new LinkedHashMap<>(terms);
        result.put("operating_income", operating);
        if (byNature) {
            result.put("operating_expenses", operating.stream().filter(t -> t.sign() < 0).map(t -> new Term(t.row(), 1)).toList());
        }
        return result;
    }

    private static boolean belowOperating(Term term) {
        IncomeLineCategory category = IncomeLineCategory.ofTemplateLine(term.row().label());
        return category != null && IncomeLineCategory.BELOW_OPERATING.contains(category);
    }

    /** The signed sum of terms of the target's column, in full units; {@code null} without an amount. */
    private static BigDecimal filedSum(FilingMapper mapper, WorkbookText text, Target target, List<Term> terms) {
        BigDecimal sum = null;
        for (Term term : terms) {
            BigDecimal raw = text.value(term.row(), target.valueColumn());
            if (raw != null) {
                sum = (sum == null ? BigDecimal.ZERO : sum).add(term.sign() < 0 ? raw.negate() : raw);
            }
        }
        return sum == null ? null : sum.multiply(mapper.unit());
    }

    private static String rowsOf(List<Term> terms) {
        return String.join(", ", terms.stream().map(t -> (t.sign() < 0 ? "-" : "+") + " '" + t.row().label() + "' ("
                + t.row().ref() + ")").toList());
    }

    /** What the correction of {@link #operatingAsDefined} changed, e.g. "operating_income 5765522000000 (model: ...)". */
    private static String operatingChange(MappedStatement before, MappedStatement after) {
        List<String> changed = new ArrayList<>();
        for (String field : List.of("operating_income", "operating_expenses")) {
            BigDecimal was = before.values().get(field);
            BigDecimal now = after.values().get(field);
            if (now != null && (was == null || was.compareTo(now) != 0)) {
                changed.add(field + " " + now.toPlainString() + " (model: " + (was == null ? "not mapped" : was.toPlainString()) + ")");
            }
        }
        return String.join(", ", changed);
    }

    /**
     * Revenue is the profit or loss's own 'Sales and revenue' row, by nature as by function ({@link FilingMapper#filedRevenue}).
     * The model added 'Other income' to it in filings by nature - EXCL FY2024 34,391,597 + 415,641 million, H1 2025 and
     * H1 2026 (70,613); BUVA FY2024 (15,260,830,841); TLKM FY2023 (252 billion), H1 2025, H1 2026 (-75) - and took
     * 'Other income' alone where the row is empty (EXCL FY2023).
     */
    private static void revenueAsFiled(FilingMapper mapper, Target target, BigDecimal revenue, BigDecimal tolerance,
                                       List<String> issues, List<Check> checks) {
        FilingMapper.FiledRevenue filed;
        try {
            filed = mapper.filedRevenue(target.column()).orElse(null);
        } catch (RuntimeException e) {
            return;     // a workbook the mapper cannot read: nothing to compare with
        }
        if (filed == null) {
            return;
        }
        String row = "'" + FilingMapper.SALES + "' (" + filed.sheet() + "!R" + filed.row() + ")";
        if (revenue != null && revenue.subtract(filed.amount()).abs().compareTo(tolerance) <= 0) {
            checks.add(Check.ok("full_text", "revenue = " + row));
            return;
        }
        issues.add("revenue is " + (revenue == null ? "not mapped" : filed(revenue, mapper)) + " but the profit or loss's own "
                + row + " is " + filed(filed.amount(), mapper) + " in " + columnName(target) + ": revenue is that row alone, with "
                + "sign 1 (other income, finance income and gains are not revenue; they stay in operating_income or out of it "
                + "as the field says)" + (filed.breakdown() == null ? ""
                : ". The row is not tagged on the face in this column; the text shows it with the total of the revenue "
                + "breakdown (sheet " + filed.breakdown() + "), which counts in operating_income like a filed revenue"));
    }

    /** An identity that must hold when both sides are known (and every operand in {@code needed} was mapped). */
    private static void identity(List<String> issues, List<Check> checks, BigDecimal tolerance, FilingMapper mapper, String rule,
                                 BigDecimal left, BigDecimal right, BigDecimal... needed) {
        if (left == null || right == null) {
            return;
        }
        for (BigDecimal operand : needed) {
            if (operand == null) {
                return;
            }
        }
        if (left.subtract(right).abs().compareTo(tolerance) > 0) {
            issues.add(rule + " does not hold: " + filed(left, mapper) + " vs " + filed(right, mapper));
        } else {
            checks.add(Check.ok("full_text", rule));
        }
    }

    /** An amount as the workbook shows it (in its rounding unit), for messages to the model. */
    private static String filed(BigDecimal value, FilingMapper mapper) {
        return value.divide(mapper.unit().max(BigDecimal.ONE)).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = null;
        for (BigDecimal v : values) {
            if (v != null) {
                total = total == null ? v : total.add(v);
            }
        }
        return total;
    }

    /** 7925966000000.0 -> 7925966000000 (no trailing zeros, never an exponent). */
    private static BigDecimal plainScale(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static BigDecimal negate(BigDecimal value) {
        return value == null ? null : value.negate();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
