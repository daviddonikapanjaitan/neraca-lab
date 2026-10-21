package com.neracalab.backend.macro.analysis;

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

import com.neracalab.backend.analysis.Texts;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.agent.JsonReplies;
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.json.JsonMapper;

/**
 * The research agent of a macro analysis: reads the history behind the rules' reading and writes the brief the macro
 * agents share.
 * <ul>
 *   <li><b>Tool Calling</b>: month-end histories and correlations of the stored series ({@link MacroTools}).</li>
 *   <li><b>ReAct</b>: Thought -> tool calls -> observations, at most {@code research-iterations} model turns, then a
 *       forced final answer.</li>
 *   <li><b>Conditional tool calling</b>: the tools are offered only until their limit is used.</li>
 *   <li><b>Reflexion within the run</b>: an unusable answer is retried once with the error as feedback; evidence citing
 *       a result that was never returned is dropped.</li>
 * </ul>
 */
@Component
public class MacroResearchAgent {

    private static final Logger log = LoggerFactory.getLogger(MacroResearchAgent.class);
    static final String STAGE = "RESEARCH";
    private static final int TURN_TOKENS = 700;
    private static final int ANSWER_TOKENS = 1000;

    /** One ReAct turn: the thought and the tools it called. */
    public record Step(int iteration, String thought, List<String> tools) {
    }

    /**
     * @param modelUsed   a model call was made
     * @param droppedRefs evidence items dropped because their ref was never returned
     * @param note        why the brief was written without (or despite a failure of) the model
     */
    public record Result(MacroBrief brief, List<Step> trace, List<MacroTools.Retrieved> retrieved, boolean modelUsed,
                         int droppedRefs, String note) {
    }

    private final LlmGateway llm;
    private final ScreeningProperties.Llm models;
    private final MacroAnalysisProperties properties;
    private final JsonReplies json;

    public MacroResearchAgent(LlmGateway llm, ScreeningProperties screening, MacroAnalysisProperties properties,
                              JsonMapper mapper) {
        this.llm = llm;
        this.models = screening.llm();
        this.properties = properties;
        this.json = new JsonReplies(mapper);
    }

    /**
     * @param subject    e.g. "Indonesia (IHSG)"
     * @param summary    what the agent is told about the dossier: the as-of date, the regimes and the rules' score
     * @param allowModel the budget allows the research calls
     */
    public Result research(UsageMeter meter, String subject, String summary, MacroTools tools, boolean allowModel) {
        if (!allowModel) {
            return new Result(MacroBrief.without("Not researched: the cost budget did not allow it."), List.of(),
                    List.of(), false, 0, "Cost budget");
        }
        List<Step> trace = new ArrayList<>();
        try {
            MacroBrief brief = react(meter, subject, summary, tools, trace).normalized();
            MacroBrief checked = brief.withKnownRefs(tools.refs());
            int dropped = brief.evidence().size() - checked.evidence().size();
            return new Result(checked, trace, tools.retrieved(), true, dropped, null);
        } catch (LlmException | InvalidReplyException e) {
            log.warn("macro research of {} failed: {}", subject, e.getMessage());
            return new Result(MacroBrief.without("The research agent failed; the agents work from the dossier alone."),
                    trace, tools.retrieved(), true, 0, "Research agent failed: " + e.getMessage());
        }
    }

    private MacroBrief react(UsageMeter meter, String subject, String summary, MacroTools tools, List<Step> trace) {
        Map<String, ToolCallback> catalog = new LinkedHashMap<>();
        for (ToolCallback cb : ToolCallbacks.from(tools)) {
            catalog.put(cb.getToolDefinition().name(), cb);
        }
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(MacroAnalysisPrompts.RESEARCH));
        history.add(new UserMessage(subjectBlock(subject, summary, tools, properties)));
        Purpose purpose = new Purpose(STAGE, "RESEARCH", subject);

        for (int iteration = 1; iteration <= properties.researchIterations(); iteration++) {
            List<ToolCallback> offered = tools.calls() < properties.maxToolCalls() ? List.copyOf(catalog.values()) : List.of();
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
        history.add(new UserMessage(MacroAnalysisPrompts.RESEARCH_FINAL));
        LlmGateway.Reply last = llm.worker(meter, purpose, models.researchModel(), history, List.of(), ANSWER_TOKENS);
        trace.add(new Step(properties.researchIterations() + 1, "(final answer requested)", List.of()));
        return parse(meter, purpose, history, last.text());
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

    /** Parses the brief; an unusable reply gets one retry with the error as feedback. */
    private MacroBrief parse(UsageMeter meter, Purpose purpose, List<Message> history, String text) {
        try {
            return json.parse(text, MacroBrief.class);
        } catch (InvalidReplyException e) {
            history.add(new UserMessage("Your answer was not usable: " + e.getMessage()
                    + ". Reply again with the JSON brief only."));
            LlmGateway.Reply retry = llm.worker(meter, purpose, models.researchModel(), history, List.of(), ANSWER_TOKENS);
            return json.parse(retry.text(), MacroBrief.class);
        }
    }

    static String subjectBlock(String subject, String summary, MacroTools tools, MacroAnalysisProperties properties) {
        StringBuilder b = new StringBuilder();
        b.append("Country and index: ").append(subject).append('\n');
        b.append(summary).append('\n');
        b.append("Codes the tools know (code: name, unit):\n");
        tools.catalog().forEach((code, meta) -> b.append("- ").append(code).append(": ").append(meta.name())
                .append(meta.unit() == null ? "" : ", " + meta.unit()).append('\n'));
        b.append("Limits: ").append(properties.maxToolCalls()).append(" tool calls, ").append(properties.historyMonths())
                .append(" months of history, ").append(properties.researchIterations()).append(" turns.");
        return b.toString();
    }

    private static String thought(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Texts.clip(text, 300);
    }
}
