package com.neracalab.backend.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.json.JsonMapper;

/**
 * The research agent of an analysis: reads the company's own stored documents and writes the brief the investor
 * agents share.
 * <ul>
 *   <li><b>Tool Calling</b>: semantic search in the company's PDF documents and news, full statements of a period
 *       ({@link ContextTools}).</li>
 *   <li><b>ReAct</b>: Thought -> tool calls -> observations, at most {@code research-iterations} model turns, then a
 *       forced final answer.</li>
 *   <li><b>Conditional tool calling</b>: a tool is offered only when it can help (documents of that kind are
 *       stored) and until its limit is used.</li>
 *   <li><b>Reflexion within the run</b>: an unusable answer is retried once with the error as feedback; evidence
 *       citing an excerpt that was never retrieved is dropped.</li>
 * </ul>
 * Without stored documents no model is called: the investor agents then work from the statements alone.
 */
@Component
public class AnalysisResearchAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResearchAgent.class);
    static final String STAGE = "RESEARCH";
    private static final int TURN_TOKENS = 700;
    private static final int ANSWER_TOKENS = 1000;

    /** One ReAct turn: the thought and the tools it called. */
    public record Step(int iteration, String thought, List<String> tools) {
    }

    /**
     * @param modelUsed   a model call was made
     * @param droppedRefs evidence items dropped because their ref was not retrieved
     * @param note        why the brief was written without (or despite a failure of) the model
     */
    public record Result(ResearchBrief brief, List<Step> trace, List<ContextTools.Retrieved> retrieved,
                         boolean modelUsed, int droppedRefs, String note) {
    }

    /** What the agent is told about the company and its stored documents. */
    public record Subject(String ticker, String companyName, String sector, List<String> periods,
                          List<String> filingTitles, long filings, List<String> newsTitles, long news) {
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final AnalysisProperties properties;
    private final JsonReplies json;

    public AnalysisResearchAgent(LlmGateway llm, ScreeningProperties screening, AnalysisProperties properties,
                                 JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.properties = properties;
        this.json = new JsonReplies(mapper);
    }

    /** @param allowModel the budget allows the research calls */
    public Result research(UsageMeter meter, Subject subject, ContextTools tools, boolean allowModel) {
        if (!tools.hasFilings() && !tools.hasNews()) {
            return new Result(ResearchBrief.without("No PDF documents or news are stored for " + subject.ticker()
                    + "; the agents work from the financial statements alone."), List.of(), List.of(), false, 0,
                    "No stored documents");
        }
        if (!allowModel) {
            return new Result(ResearchBrief.without("Not researched: the cost budget did not allow it."), List.of(),
                    List.of(), false, 0, "Cost budget");
        }
        List<Step> trace = new ArrayList<>();
        try {
            ResearchBrief brief = react(meter, subject, tools, trace).normalized();
            ResearchBrief checked = brief.withKnownRefs(tools.refs());
            int dropped = brief.evidence().size() - checked.evidence().size();
            return new Result(checked, trace, tools.retrieved(), true, dropped, null);
        } catch (LlmException | InvalidReplyException e) {
            log.warn("analysis research of {} failed: {}", subject.ticker(), e.getMessage());
            return new Result(ResearchBrief.without("The research agent failed; the agents work from the financial "
                    + "statements alone."), trace, tools.retrieved(), true, 0, "Research agent failed: " + e.getMessage());
        }
    }

    private ResearchBrief react(UsageMeter meter, Subject subject, ContextTools tools, List<Step> trace) {
        Map<String, ToolCallback> catalog = new LinkedHashMap<>();
        for (ToolCallback cb : ToolCallbacks.from(tools)) {
            catalog.put(cb.getToolDefinition().name(), cb);
        }
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(AnalysisPrompts.RESEARCH));
        history.add(new UserMessage(subjectBlock(subject, properties)));
        Purpose purpose = new Purpose(STAGE, "RESEARCH", subject.ticker());

        for (int iteration = 1; iteration <= properties.researchIterations(); iteration++) {
            List<ToolCallback> offered = offered(catalog, tools);
            LlmGateway.Reply reply = llm.worker(meter, purpose, models.researchModel(), history, offered,
                    offered.isEmpty() ? ANSWER_TOKENS : TURN_TOKENS);
            AssistantMessage message = reply.message();
            history.add(message);
            List<String> called = message.getToolCalls().stream()
                    .map(c -> c.name() + "(" + Texts.clip(Texts.compact(c.arguments()), 120) + ")").toList();
            trace.add(new Step(iteration, thought(message.getText()), called));
            if (!message.hasToolCalls()) {
                return parse(meter, purpose, history, reply.text());
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), execute(catalog, call)));
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());
        }
        // ReAct guard: iteration limit reached, ask for the answer without tools
        history.add(new UserMessage(AnalysisPrompts.RESEARCH_FINAL));
        LlmGateway.Reply last = llm.worker(meter, purpose, models.researchModel(), history, List.of(), ANSWER_TOKENS);
        trace.add(new Step(properties.researchIterations() + 1, "(final answer requested)", List.of()));
        return parse(meter, purpose, history, last.text());
    }

    /** Conditional tool calling: only tools that can help, until their limit is used. */
    private List<ToolCallback> offered(Map<String, ToolCallback> catalog, ContextTools tools) {
        List<ToolCallback> offered = new ArrayList<>();
        boolean searchesLeft = tools.searches() < properties.maxSearches();
        if (tools.hasFilings() && searchesLeft) {
            offered.add(catalog.get("searchFilings"));
        }
        if (tools.hasNews() && searchesLeft) {
            offered.add(catalog.get("searchNews"));
        }
        if (tools.lookups() < properties.maxStatementLookups()) {
            offered.add(catalog.get("getStatement"));
        }
        return offered;
    }

    private static String execute(Map<String, ToolCallback> catalog, AssistantMessage.ToolCall call) {
        ToolCallback tool = catalog.get(call.name());
        if (tool == null) {
            return "{\"error\":\"Unknown tool " + call.name() + "\"}";
        }
        try {
            String args = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            String result = tool.call(args);
            return result.length() > 8000 ? result.substring(0, 8000) + "...(truncated)" : result;
        } catch (RuntimeException e) {
            String message = LlmGateway.rootMessage(e).replace("\\", "\\\\").replace("\"", "'");
            return "{\"error\":\"" + message + "\"}";
        }
    }

    /** Parses the brief; an unusable reply gets one retry with the error as feedback (Reflexion within the run). */
    private ResearchBrief parse(UsageMeter meter, Purpose purpose, List<Message> history, String text) {
        try {
            return json.parse(text, ResearchBrief.class);
        } catch (InvalidReplyException e) {
            history.add(new UserMessage("Your answer was not usable: " + e.getMessage()
                    + ". Reply again with the JSON brief only."));
            LlmGateway.Reply retry = llm.worker(meter, purpose, models.researchModel(), history, List.of(), ANSWER_TOKENS);
            return json.parse(retry.text(), ResearchBrief.class);
        }
    }

    static String subjectBlock(Subject s, AnalysisProperties properties) {
        StringBuilder b = new StringBuilder();
        b.append("Company: ").append(s.ticker()).append(" - ").append(s.companyName());
        if (s.sector() != null) {
            b.append(" (").append(s.sector()).append(')');
        }
        b.append("\nStored reporting periods (for getStatement): ")
                .append(s.periods().isEmpty() ? "none" : String.join(", ", s.periods())).append('\n');
        b.append("Stored PDF documents: ").append(s.filings());
        if (!s.filingTitles().isEmpty()) {
            b.append(" (").append(String.join("; ", s.filingTitles())).append(')');
        }
        b.append("\nStored news articles: ").append(s.news());
        if (!s.newsTitles().isEmpty()) {
            b.append(", latest:\n");
            s.newsTitles().forEach(t -> b.append("- ").append(t).append('\n'));
        } else {
            b.append('\n');
        }
        b.append("Limits: ").append(properties.maxSearches()).append(" searches, ")
                .append(properties.maxStatementLookups()).append(" statement lookups, ")
                .append(properties.researchIterations()).append(" turns.");
        return b.toString();
    }

    private static String thought(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Texts.clip(text, 300);
    }
}
