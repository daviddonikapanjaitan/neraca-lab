package com.neracalab.backend.screening.agent;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.neracalab.backend.screening.agent.JsonReplies.InvalidReplyException;
import com.neracalab.backend.screening.agent.LlmGateway.LlmException;
import com.neracalab.backend.screening.agent.LlmGateway.Purpose;
import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsRepository;
import com.neracalab.backend.screening.news.NewsService;
import com.neracalab.backend.screening.news.NewsService.SourceResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * The research agent (the seventh agent): turns a stock's news into a brief for the investor agents.
 * <ul>
 *   <li><b>Tool Calling</b>: the crawlers (EmitenNews, Pasardana, IDX Channel, Investor.id) and Tavily
 *       gather the headlines; the model can read articles and search ({@link ResearchTools}).</li>
 *   <li><b>ReAct</b>: Thought -> tool calls -> observations, at most
 *       {@code neracalab.screening.llm.research-iterations} model turns, then a forced final answer.</li>
 *   <li><b>Conditional tool calling</b>: a tool is withdrawn once its limit is used.</li>
 * </ul>
 * Cost control: the brief of a stock is made once per day and reused by later runs; without
 * headlines (and no search) no model is called.
 */
@Component
public class ResearchAgent {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgent.class);
    static final String STAGE = "RESEARCH";

    /** One ReAct turn: the thought and the tools it called. */
    public record Step(int iteration, String thought, List<String> tools) {
    }

    /**
     * @param modelUsed   a model call was made for this brief (false: cached, or no news)
     * @param fromCache   today's brief of an earlier run was reused
     */
    public record Result(NewsBrief brief, List<Headline> headlines, List<SourceResult> sources, List<Step> trace,
                         boolean modelUsed, boolean fromCache, String note) {
    }

    private final LlmGateway llm;
    private final NewsService news;
    private final NewsRepository repository;
    private final ScreeningProperties.Llm properties;
    private final JsonReplies json;

    public ResearchAgent(LlmGateway llm, NewsService news, NewsRepository repository, ScreeningProperties properties,
                         JsonMapper mapper) {
        this.llm = llm;
        this.news = news;
        this.repository = repository;
        this.properties = properties.llm();
        this.json = new JsonReplies(mapper);
    }

    /**
     * @param allowModel the budget allows a model call
     * @param lessons    Reflexion lessons for the research agent
     */
    public Result research(UsageMeter meter, String exchange, String ticker, String companyName, String sector,
                           LocalDate today, boolean allowModel, List<String> lessons) {
        Optional<String> cached = repository.brief(exchange, ticker, today);
        if (cached.isPresent()) {
            try {
                NewsBrief brief = json.parse(cached.get(), NewsBrief.class).normalized();
                return new Result(brief, news.headlines(exchange, ticker), List.of(), List.of(), false, true,
                        "Today's brief of an earlier run reused");
            } catch (InvalidReplyException e) {
                log.warn("stored brief of {} unreadable, researching again", ticker);
            }
        }
        NewsService.Gathered gathered = news.gather(exchange, ticker, companyName);
        List<Headline> headlines = gathered.headlines();
        if (headlines.isEmpty() && !news.searchEnabled()) {
            NewsBrief brief = NewsBrief.without("No news about the company in the last months on the crawled sites.");
            return new Result(brief, headlines, gathered.sources(), List.of(), false, false, "No headlines");
        }
        if (!allowModel) {
            return new Result(headlinesOnly(headlines), headlines, gathered.sources(), List.of(), false, false,
                    "Budget: headlines listed without analysis");
        }
        try {
            ReactOutcome outcome = react(meter, ticker, companyName, sector, headlines, lessons);
            repository.saveBrief(exchange, ticker, today, json.write(outcome.brief()), properties.researchModel());
            return new Result(outcome.brief(), headlines, gathered.sources(), outcome.trace(), true, false, null);
        } catch (LlmException | InvalidReplyException e) {
            log.warn("research agent failed for {}: {}", ticker, e.getMessage());
            return new Result(headlinesOnly(headlines), headlines, gathered.sources(), List.of(), true, false,
                    "Research agent failed: " + e.getMessage());
        }
    }

    private record ReactOutcome(NewsBrief brief, List<Step> trace) {
    }

    private ReactOutcome react(UsageMeter meter, String ticker, String companyName, String sector,
                               List<Headline> headlines, List<String> lessons) {
        ResearchTools tools = new ResearchTools(news, headlines);
        Map<String, ToolCallback> catalog = new LinkedHashMap<>();
        for (ToolCallback cb : ToolCallbacks.from(tools)) {
            catalog.put(cb.getToolDefinition().name(), cb);
        }
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(ScreeningPrompts.RESEARCH + lessonsBlock(lessons)));
        history.add(new UserMessage(stockBlock(ticker, companyName, sector, headlines)));
        List<Step> trace = new ArrayList<>();
        Purpose purpose = new Purpose(STAGE, "RESEARCH", ticker);

        for (int iteration = 1; iteration <= properties.researchIterations(); iteration++) {
            List<ToolCallback> offered = offered(catalog, tools);
            LlmGateway.Reply reply = llm.worker(meter, purpose, properties.researchModel(), history, offered,
                    offered.isEmpty() ? 500 : 700);
            AssistantMessage message = reply.message();
            history.add(message);
            List<String> called = message.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList();
            trace.add(new Step(iteration, thought(message.getText()), called));
            if (!message.hasToolCalls()) {
                return new ReactOutcome(parseBrief(meter, purpose, history, reply.text()), trace);
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), execute(catalog, call)));
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());
        }
        // ReAct guard: iteration limit reached, ask for the answer without tools
        history.add(new UserMessage(ScreeningPrompts.RESEARCH_FINAL));
        LlmGateway.Reply last = llm.worker(meter, purpose, properties.researchModel(), history, List.of(), 500);
        trace.add(new Step(properties.researchIterations() + 1, "(final answer requested)", List.of()));
        return new ReactOutcome(parseBrief(meter, purpose, history, last.text()), trace);
    }

    /** Conditional tool calling: tools disappear once their limit is used. */
    private List<ToolCallback> offered(Map<String, ToolCallback> catalog, ResearchTools tools) {
        List<ToolCallback> offered = new ArrayList<>();
        if (tools.reads() < ResearchTools.MAX_READS) {
            offered.add(catalog.get("readArticle"));
        }
        if (news.searchEnabled() && tools.searches() < ResearchTools.MAX_SEARCHES) {
            offered.add(catalog.get("searchNews"));
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
            return result.length() > 6000 ? result.substring(0, 6000) + "...(truncated)" : result;
        } catch (RuntimeException e) {
            String message = LlmGateway.rootMessage(e).replace("\\", "\\\\").replace("\"", "'");
            return "{\"error\":\"" + message + "\"}";
        }
    }

    /** Parses the brief; an invalid reply gets one retry with the parse error as feedback (Reflexion within the run). */
    private NewsBrief parseBrief(UsageMeter meter, Purpose purpose, List<Message> history, String text) {
        try {
            return json.parse(text, NewsBrief.class).normalized();
        } catch (InvalidReplyException e) {
            history.add(new UserMessage("Your answer was not usable: " + e.getMessage()
                    + ". Reply again with the JSON brief only."));
            LlmGateway.Reply retry = llm.worker(meter, purpose, properties.researchModel(), history, List.of(), 500);
            return json.parse(retry.text(), NewsBrief.class).normalized();
        }
    }

    static String stockBlock(String ticker, String companyName, String sector, List<Headline> headlines) {
        StringBuilder b = new StringBuilder();
        b.append("Stock: ").append(ticker).append(" - ").append(companyName);
        if (sector != null) {
            b.append(" (").append(sector).append(')');
        }
        b.append("\nHeadlines (newest first):\n");
        if (headlines.isEmpty()) {
            b.append("(none found)\n");
        }
        int i = 1;
        for (Headline h : headlines) {
            b.append(i++).append(". ");
            if (h.publishedAt() != null) {
                b.append('[').append(h.publishedAt().atOffset(ZoneOffset.ofHours(7)).toLocalDate()).append("] ");
            }
            b.append('[').append(h.source().label()).append("] ").append(h.title());
            if (h.description() != null) {
                String d = h.description();
                b.append(" - ").append(d.length() > 220 ? d.substring(0, 220) + "…" : d);
            }
            b.append(" <").append(h.url()).append(">\n");
        }
        return b.toString();
    }

    static String lessonsBlock(List<String> lessons) {
        if (lessons == null || lessons.isEmpty()) {
            return "";
        }
        return "\n\nLessons from earlier runs (Reflexion memory):\n- " + String.join("\n- ", lessons);
    }

    private static NewsBrief headlinesOnly(List<Headline> headlines) {
        if (headlines.isEmpty()) {
            return NewsBrief.without("No news found.");
        }
        List<String> titles = headlines.stream().limit(3).map(Headline::title).toList();
        return new NewsBrief("NEUTRAL", "Not analysed. Latest headlines: " + String.join(" | ", titles), List.of(),
                List.of(), headlines.stream().limit(3).map(Headline::url).toList()).normalized();
    }

    private static String thought(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
