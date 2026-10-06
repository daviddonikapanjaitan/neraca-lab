package com.neracalab.backend.ingestion.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.neracalab.backend.ingestion.persistence.IngestionRepository;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;

import tools.jackson.databind.json.JsonMapper;

/**
 * The ingestion agent. Patterns and where they live:
 * <ul>
 *   <li><b>Tool Calling Pattern</b>: {@link IngestionTools} methods exposed to the model as tools.</li>
 *   <li><b>Plan-and-Execute</b>: {@link #plan} asks the model for a structured plan first; the
 *       execution loop then follows it.</li>
 *   <li><b>Tool Calling Loop</b>: {@link #execute} drives model -&gt; tools -&gt; model until the model
 *       answers without tool calls (bounded by {@code maxIterations}).</li>
 *   <li><b>ReAct</b>: the executor writes a "Thought" before acting and reads each observation.</li>
 *   <li><b>Sequential / Parallel Tool Calling</b>: {@link ToolExecutor} runs independent read calls of
 *       one turn concurrently and writes one after another.</li>
 *   <li><b>Conditional Tool Calling</b>: {@link #offeredTools} changes the tool set with the session
 *       state (e.g. registerCompany only for an unknown company).</li>
 *   <li><b>Reflection</b>: {@link #reflect} reviews the result against a deterministic verification and
 *       sends the executor back with concrete feedback.</li>
 * </ul>
 */
@Component
public class IngestionAgent {

    private static final Logger log = LoggerFactory.getLogger(IngestionAgent.class);
    private static final Set<String> WRITE_TOOLS = Set.of("saveStatements", "saveRevenueSegments",
            "saveShareSnapshots", "refreshDerivedData");

    private final ChatModel chatModel;
    private final IngestionRepository repository;
    private final IngestionVerifier verifier;
    private final AgentProperties properties;
    private final JsonMapper json;

    public IngestionAgent(ChatModel chatModel, IngestionRepository repository, IngestionVerifier verifier,
                          AgentProperties properties, JsonMapper json) {
        this.chatModel = chatModel;
        this.repository = repository;
        this.verifier = verifier;
        this.properties = properties;
        this.json = json;
    }

    // ------------------------------------------------------------------ structured outputs

    public record PlanStep(int step, String action, List<String> tools, boolean parallel, String condition) {
    }

    public record IngestionPlan(String objective, List<PlanStep> steps) {
    }

    public record Reflection(boolean complete, String assessment, List<String> issues, List<String> nextActions) {
    }

    public record Round(int round, int iterations, String finalMessage, IngestionVerifier.Verification verification,
                        Reflection reflection) {
    }

    public record Outcome(IngestionPlan plan, boolean planFromModel, List<Round> rounds, AgentTrace trace,
                          IngestionVerifier.Verification verification, List<String> notes) {
    }

    // ------------------------------------------------------------------ run

    public Outcome run(IngestionSession session) {
        AgentTrace trace = new AgentTrace();
        IngestionTools tools = new IngestionTools(session, repository, verifier);
        Map<String, ToolCallback> catalog = new LinkedHashMap<>();
        for (ToolCallback cb : ToolCallbacks.from(tools)) {
            catalog.put(cb.getToolDefinition().name(), cb);
        }
        String overview = toJson(tools.getFilingOverview());

        // ---- 1. Plan
        IngestionPlan plan;
        boolean planFromModel = true;
        try {
            plan = plan(overview, catalog, trace);
        } catch (RuntimeException e) {
            log.warn("Planner output unusable, using the default plan: {}", e.getMessage());
            session.note("Planner output could not be parsed (" + ToolExecutor.rootMessage(e) + "); default plan used");
            plan = defaultPlan();
            planFromModel = false;
        }

        // ---- 2. Execute, 3. Reflect (repeat while the reviewer or the verification finds work)
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(Prompts.EXECUTOR));
        history.add(new UserMessage("""
                Plan to execute:
                %s

                Filing overview:
                %s

                Start with step 1.""".formatted(toJson(plan), overview)));
        List<Round> rounds = new ArrayList<>();
        int maxRounds = 1 + properties.reflectionRounds();
        for (int round = 1; round <= maxRounds; round++) {
            int before = trace.steps().size();
            String finalMessage = execute(history, session, catalog, trace, round);
            int iterations = trace.steps().size() - before;
            IngestionVerifier.Verification verification = verifier.verify(session);
            Reflection reflection = reflect(plan, trace, finalMessage, verification, round);
            rounds.add(new Round(round, iterations, finalMessage, verification, reflection));
            log.info("round {}: verification complete={} pending={} problems={}; reviewer complete={} issues={}",
                    round, verification.complete(), verification.pending(), verification.problems(),
                    reflection.complete(), reflection.issues());

            boolean reviewerHasWork = !reflection.complete() && reflection.nextActions() != null
                    && !reflection.nextActions().isEmpty();
            if ((verification.complete() && !reviewerHasWork) || round == maxRounds) {
                break;
            }
            history.add(new UserMessage("""
                    Reviewer feedback (round %d). The work is not finished.
                    Pending (from the database read-back): %s
                    Problems: %s
                    Reviewer issues: %s
                    Next actions: %s
                    Continue: write a Thought, then call the tools needed.""".formatted(round,
                    verification.pending(), verification.problems(), reflection.issues(), reflection.nextActions())));
        }

        // ---- safety net: derived data must reflect the final state, whatever the model did
        if (session.hasWrites() && !session.isDerivedCurrent()) {
            repository.refreshDerivedData();
            session.derivedRefreshed();
            session.note("Derived data was refreshed by the safety net (the agent did not refresh after its last save)");
        }
        return new Outcome(plan, planFromModel, rounds, trace, verifier.verify(session), session.notes());
    }

    // ------------------------------------------------------------------ plan

    private IngestionPlan plan(String overview, Map<String, ToolCallback> catalog, AgentTrace trace) {
        BeanOutputConverter<IngestionPlan> converter = new BeanOutputConverter<>(IngestionPlan.class);
        StringBuilder tools = new StringBuilder();
        catalog.values().forEach(cb -> tools.append("- ").append(cb.getToolDefinition().name()).append(": ")
                .append(cb.getToolDefinition().description().replaceAll("\\s+", " ")).append('\n'));
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(Prompts.PLANNER),
                new UserMessage("Filing overview:\n" + overview + "\n\nTool catalog:\n" + tools
                        + "\n" + converter.getFormat())),
                OpenAiChatOptions.builder().temperature(properties.temperature()).build());
        String text = call(prompt, trace).getResult().getOutput().getText();
        IngestionPlan plan = converter.convert(text);
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            throw new IllegalStateException("empty plan");
        }
        for (PlanStep step : plan.steps()) {
            for (String tool : step.tools() == null ? List.<String>of() : step.tools()) {
                if (!catalog.containsKey(tool)) {
                    throw new IllegalStateException("plan uses unknown tool '" + tool + "'");
                }
            }
        }
        log.info("plan: {}", toJson(plan));
        return plan;
    }

    static IngestionPlan defaultPlan() {
        return new IngestionPlan("Store every statement, segment and share count of the filing, then refresh and verify", List.of(
                new PlanStep(1, "Look up the company", List.of("findCompany"), false, "always"),
                new PlanStep(2, "Register the company", List.of("registerCompany"), false, "only if findCompany found=false"),
                new PlanStep(3, "Extract all statement columns", List.of("extractStatements"), true, "every column in the overview"),
                new PlanStep(4, "Classify unknown income lines", List.of("classifyIncomeLines"), false, "only if unclassified lines are reported"),
                new PlanStep(5, "Save every valid column", List.of("saveStatements"), false, "only columns that are ready to save"),
                new PlanStep(6, "Extract and save revenue segments", List.of("extractRevenueSegments", "saveRevenueSegments"), true,
                        "only columns with segments, after their statements are saved"),
                new PlanStep(7, "Save share counts", List.of("saveShareSnapshots"), false, "only if the par value is resolvable"),
                new PlanStep(8, "Refresh derived data", List.of("refreshDerivedData"), false, "after the last save"),
                new PlanStep(9, "Verify", List.of("verifyStoredData"), false, "always, last")));
    }

    // ------------------------------------------------------------------ execute (Tool Calling Loop + ReAct)

    private String execute(List<Message> history, IngestionSession session, Map<String, ToolCallback> catalog,
                           AgentTrace trace, int round) {
        for (int iteration = 1; iteration <= properties.maxIterations(); iteration++) {
            Map<String, ToolCallback> offered = offeredTools(session, catalog);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .toolCallbacks(new ArrayList<>(offered.values()))
                    .parallelToolCalls(true)
                    .temperature(properties.temperature())
                    .build();
            ChatResponse response = call(new Prompt(history, options), trace);
            AssistantMessage message = response.getResult().getOutput();
            history.add(message);
            List<String> requested = message.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList();
            trace.step(new AgentTrace.Step(round, iteration, message.getText(), requested, List.copyOf(offered.keySet())));
            log.info("round {} iteration {}: {} -> {}", round, iteration,
                    ToolExecutor.abbreviate(message.getText(), 300), requested);
            if (!message.hasToolCalls()) {
                return message.getText();
            }
            var responses = ToolExecutor.execute(message.getToolCalls(), offered, trace, round, iteration);
            history.add(ToolResponseMessage.builder().responses(responses).build());
        }
        session.note("Round " + round + " stopped at the iteration limit (" + properties.maxIterations() + ")");
        return "(iteration limit reached)";
    }

    /** Conditional Tool Calling: the tool set offered to the model depends on what has happened so far. */
    static Map<String, ToolCallback> offeredTools(IngestionSession session, Map<String, ToolCallback> catalog) {
        Map<String, ToolCallback> offered = new LinkedHashMap<>(catalog);
        boolean companyKnown = session.company().isPresent();
        if (companyKnown || !session.isCompanyLookedUp()) {
            offered.remove("registerCompany");          // only after findCompany reported it missing
        }
        if (!companyKnown) {
            WRITE_TOOLS.forEach(offered::remove);       // nothing can be saved without the company
        }
        if (!session.hasUnclassifiedLines()) {
            offered.remove("classifyIncomeLines");      // only while unknown income lines exist
        }
        if (!session.hasWrites()) {
            offered.remove("refreshDerivedData");       // nothing to recalculate yet
        }
        return offered;
    }

    // ------------------------------------------------------------------ reflect

    private Reflection reflect(IngestionPlan plan, AgentTrace trace, String finalMessage,
                               IngestionVerifier.Verification verification, int round) {
        BeanOutputConverter<Reflection> converter = new BeanOutputConverter<>(Reflection.class);
        List<String> actions = trace.invocations().stream()
                .map(i -> "r" + i.round() + "/i" + i.iteration() + " " + i.tool() + " " + i.arguments()
                        + (i.error() ? " -> ERROR " + i.result() : " -> ok"))
                .toList();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(Prompts.REVIEWER),
                new UserMessage("""
                        Review round %d.

                        Plan:
                        %s

                        Tool calls so far:
                        %s

                        Executor final message:
                        %s

                        Verification read back from the database:
                        %s

                        %s""".formatted(round, toJson(plan), String.join("\n", actions), finalMessage,
                        toJson(verification), converter.getFormat()))),
                OpenAiChatOptions.builder().temperature(properties.temperature()).build());
        try {
            Reflection reflection = converter.convert(call(prompt, trace).getResult().getOutput().getText());
            if (reflection == null) {
                throw new IllegalStateException("empty reflection");
            }
            return new Reflection(reflection.complete(), reflection.assessment(),
                    reflection.issues() == null ? List.of() : reflection.issues(),
                    reflection.nextActions() == null ? List.of() : reflection.nextActions());
        } catch (RuntimeException e) {
            log.warn("Reviewer output unusable: {}", e.getMessage());
            // fall back to the deterministic verification alone
            return new Reflection(verification.complete(), "Reviewer output unusable: " + ToolExecutor.rootMessage(e),
                    verification.problems(), verification.pending());
        }
    }

    // ------------------------------------------------------------------ model calls

    /**
     * One model call, retried on transient provider failures (a stalled response read, a dropped
     * connection, HTTP 408 / 429 / 5xx): a single hiccup of the provider must not fail an ingestion
     * whose work so far is intact. Other failures (bad request, authentication) are thrown at once.
     */
    ChatResponse call(Prompt prompt, AgentTrace trace) {
        for (int attempt = 0; ; attempt++) {
            trace.modelCall();
            try {
                return chatModel.call(prompt);
            } catch (RuntimeException e) {
                if (attempt >= properties.modelRetries() || !isTransient(e)) {
                    throw e;
                }
                long pause = properties.retryBackoff().toMillis() << attempt;
                trace.modelRetry();
                log.warn("model call failed transiently ({}), retry {} of {} in {} ms", ToolExecutor.rootMessage(e),
                        attempt + 1, properties.modelRetries(), pause);
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Timeouts and I/O errors anywhere in the cause chain, or a retryable HTTP status. */
    static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof OpenAIIoException) {
                return true;
            }
            if (t instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                return status == 408 || status == 429 || status >= 500;
            }
        }
        return false;
    }

    private String toJson(Object value) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }
}
