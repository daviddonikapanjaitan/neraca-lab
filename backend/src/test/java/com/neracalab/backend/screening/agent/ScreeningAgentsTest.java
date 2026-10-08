package com.neracalab.backend.screening.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.TestScreeningProperties;
import com.neracalab.backend.screening.agent.ReflectionValidator.Issue;
import com.neracalab.backend.screening.agent.SynthesisAgent.Row;
import com.neracalab.backend.screening.agent.SynthesisAgent.Synthesis;
import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsParsers.ArticleText;
import com.neracalab.backend.screening.news.NewsRepository;
import com.neracalab.backend.screening.news.NewsService;
import com.neracalab.backend.screening.news.NewsSource;
import com.neracalab.backend.screening.quant.QuantScorer;
import com.neracalab.backend.screening.quant.QuantScorer.AgentScore;
import com.neracalab.backend.screening.quant.StockProfile;
import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import tools.jackson.databind.json.JsonMapper;

/** The Stage 2 agents with a scripted model: options, metering, ReAct, Reflection, synthesis rules. */
class ScreeningAgentsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ScreeningProperties properties = TestScreeningProperties.create();

    /** Answers the queued replies in order and records every prompt. */
    static final class ScriptedModel implements ChatModel {
        final Deque<AssistantMessage> replies = new ArrayDeque<>();
        final Deque<RuntimeException> failures = new ArrayDeque<>();
        final List<Prompt> prompts = new ArrayList<>();
        Double cost = 0.0005;

        ScriptedModel fail(RuntimeException e) {
            failures.add(e);
            return this;
        }

        ScriptedModel reply(String text) {
            replies.add(new AssistantMessage(text));
            return this;
        }

        ScriptedModel toolCall(String thought, String tool, String arguments) {
            replies.add(AssistantMessage.builder().content(thought)
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + replies.size(), "function", tool, arguments)))
                    .build());
            return this;
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            RuntimeException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
            AssistantMessage message = replies.poll();
            if (message == null) {
                throw new IllegalStateException("no scripted reply left");
            }
            CompletionUsage.Builder usage = CompletionUsage.builder().promptTokens(1000).completionTokens(100).totalTokens(1100);
            if (cost != null) {
                usage.putAdditionalProperty("cost", JsonValue.from(cost));
            }
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(1000, 100, 1100, usage.build()))
                    .build();
            return new ChatResponse(List.of(new Generation(message)), metadata);
        }
    }

    private UsageMeter meter(double budget, List<UsageMeter.Usage> sink) {
        return new UsageMeter(UUID.randomUUID(), budget, (run, usage) -> sink.add(usage));
    }

    // ------------------------------------------------------------------ gateway

    @Test
    void workerCallsSwitchReasoningOffAndRecordTheReportedCost() {
        ScriptedModel model = new ScriptedModel().reply("{\"ok\":true}");
        List<UsageMeter.Usage> usages = new ArrayList<>();
        UsageMeter meter = meter(1, usages);
        new LlmGateway(model, properties).worker(meter, new LlmGateway.Purpose("AGENT", "BUFFETT", "BBCA"),
                "deepseek/deepseek-v4-flash-0731", List.of(new org.springframework.ai.chat.messages.UserMessage("x")),
                List.of(), 300);

        OpenAiChatOptions options = (OpenAiChatOptions) model.prompts.get(0).getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek/deepseek-v4-flash-0731");
        assertThat(options.getMaxTokens()).isEqualTo(300);
        assertThat(options.getExtraBody()).containsEntry("reasoning", Map.of("enabled", false))
                .containsEntry("provider", Map.of("sort", "price", "ignore", List.of("OpenInference")))
                .containsEntry("response_format", Map.of("type", "json_object"));
        assertThat(usages).singleElement().satisfies(u -> {
            assertThat(u.costUsd()).isEqualTo(0.0005);
            assertThat(u.costEstimated()).isFalse();
            assertThat(u.promptTokens()).isEqualTo(1000);
            assertThat(u.agent()).isEqualTo("BUFFETT");
        });
        assertThat(meter.spentUsd()).isEqualTo(0.0005);
    }

    @Test
    void aStreamResetIsRetriedAndEveryAttemptRecorded() {
        // HRTA, 2026-10-08: OpenRouter reset responses still streaming after 60 s; the agent lost its answer
        ScriptedModel model = new ScriptedModel()
                .fail(new IllegalStateException("call failed", new java.io.IOException("stream was reset: CANCEL")))
                .reply("{\"ok\":true}");
        List<UsageMeter.Usage> usages = new ArrayList<>();
        LlmGateway.Reply reply = new LlmGateway(model, properties).worker(meter(1, usages),
                new LlmGateway.Purpose("AGENT", "MUNGER", "HRTA"), "deepseek/deepseek-v4-flash-0731",
                List.of(new org.springframework.ai.chat.messages.UserMessage("x")), List.of(), 300);

        assertThat(reply.text()).isEqualTo("{\"ok\":true}");
        assertThat(model.prompts).hasSize(2);
        assertThat(usages).hasSize(2);
        assertThat(usages.get(0).error()).isEqualTo("stream was reset: CANCEL");
        assertThat(usages.get(1).error()).isNull();
    }

    @Test
    void transientFailuresStopAfterTheRetriesAndOtherFailuresAreNotRetried() {
        ScriptedModel resets = new ScriptedModel();
        for (int i = 0; i < 3; i++) {
            resets.fail(new IllegalStateException("x", new java.io.IOException("stream was reset: CANCEL")));
        }
        LlmGateway gateway = new LlmGateway(resets, properties);
        List<org.springframework.ai.chat.messages.Message> x = List.of(new org.springframework.ai.chat.messages.UserMessage("x"));
        assertThatThrownBy(() -> gateway.worker(meter(1, new ArrayList<>()), new LlmGateway.Purpose("AGENT", "GILL", "HRTA"),
                "deepseek/deepseek-v4-flash-0731", x, List.of(), 300))
                .isInstanceOf(LlmGateway.LlmException.class).hasMessageContaining("(3 attempts)");
        assertThat(resets.prompts).hasSize(3);           // the first call and model-retries (2) more

        ScriptedModel refused = new ScriptedModel().fail(new IllegalArgumentException("invalid model id"));
        assertThatThrownBy(() -> new LlmGateway(refused, properties).worker(meter(1, new ArrayList<>()),
                new LlmGateway.Purpose("AGENT", "GILL", "HRTA"), "deepseek/deepseek-v4-flash-0731", x, List.of(), 300))
                .isInstanceOf(LlmGateway.LlmException.class).hasMessageNotContaining("attempts");
        assertThat(refused.prompts).hasSize(1);
    }

    @Test
    void anAnswerWithoutStrengthsAndConcernsIsAskedAgain() {
        // HRTA, 2026-10-08: a degraded provider wrote other keys (e.g. "valuation") and no strengths or concerns
        ScriptedModel model = new ScriptedModel()
                .reply("{\"score\":22,\"verdict\":\"REJECT\",\"thesis\":\"Thin margins\",\"valuation\":{\"pe\":7.2}}")
                .reply("{\"score\":35,\"verdict\":\"WEAK\",\"thesis\":\"Thin margins\",\"strengths\":[\"High ROE\"],"
                        + "\"concerns\":[\"Gross margin 3.8%\"],\"metricsUsed\":[\"roe\"]}");
        InvestorPanel panel = new InvestorPanel(new LlmGateway(model, properties), properties, mapper);
        StockProfile p = profile(0.15, 0.5, 1.0);
        InvestorPanel.Outcome o = panel.assess(meter(1, new ArrayList<>()), "HRTA", "STOCK DATA {}", InvestorAgent.MUNGER,
                QuantScorer.score(InvestorAgent.MUNGER, p), p, List.of());

        assertThat(o.original().score()).isEqualTo(35.0);
        assertThat(o.original().strengths()).containsExactly("High ROE");
        List<Message> retry = model.prompts.get(1).getInstructions();
        assertThat(retry.getLast().getText()).startsWith("Your answer was not usable: the reply has no strengths and no concerns");
    }

    @Test
    void synthesisUsesLowEffortWithoutSamplingAndCostIsEstimatedWhenNotReported() {
        ScriptedModel model = new ScriptedModel().reply("{}");
        model.cost = null;
        List<UsageMeter.Usage> usages = new ArrayList<>();
        new LlmGateway(model, properties).synthesis(meter(1, usages), new LlmGateway.Purpose("SYNTHESIS", null, null),
                List.of(new org.springframework.ai.chat.messages.UserMessage("x")));
        OpenAiChatOptions options = (OpenAiChatOptions) model.prompts.get(0).getOptions();
        assertThat(options.getModel()).isEqualTo("anthropic/claude-opus-5.5");
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getExtraBody()).containsEntry("reasoning", Map.of("effort", "low"));
        UsageMeter.Usage u = usages.get(0);
        assertThat(u.costEstimated()).isTrue();
        assertThat(u.costUsd()).isEqualTo((1000 * 4 + 100 * 20) / 1_000_000d);
    }

    @Test
    void budgetKeepsTheReserve() {
        UsageMeter meter = meter(0.10, new ArrayList<>());
        assertThat(meter.allows(0.04, 0.05)).isTrue();
        meter.record(new UsageMeter.Usage("AGENT", null, null, "m", 1, 1, 0, 0, 0.03, false, 1, null));
        assertThat(meter.allows(0.04, 0.05)).isFalse();
        assertThat(meter.byStage()).containsKey("AGENT|m");
    }

    // ------------------------------------------------------------------ replies and validation

    @Test
    void lenientJsonReplies() {
        JsonReplies json = new JsonReplies(mapper);
        Assessment a = json.parse("```json\n{\"score\": 105, \"verdict\": \"strong fit\", \"thesis\": \"t\", \"extra\": 1}\n```",
                Assessment.class).normalized();
        assertThat(a.score()).isEqualTo(100.0);
        assertThat(a.verdict()).isEqualTo("STRONG_FIT");
        assertThat(json.parse("{\"score\": 50, \"verdict\": \"maybe\"}", Assessment.class).normalized().verdict())
                .isEqualTo("NEUTRAL");
        assertThatThrownBy(() -> json.parse("no json here", Assessment.class))
                .isInstanceOf(JsonReplies.InvalidReplyException.class);
    }

    @Test
    void validatorFindsDivergenceVerdictRulesAndUnknownMetrics() {
        StockProfile weak = profile(0.06, 2.0, 0.5);
        Assessment a = new Assessment(90.0, "WEAK", "Great company", List.of(), List.of(), List.of("roe", "moat"), null);
        List<Issue> issues = ReflectionValidator.validate(InvestorAgent.BUFFETT, a, 30, weak,
                InvestorPanel.metricKeys(weak));
        assertThat(issues).extracting(Issue::code).containsExactly("DIVERGENCE", "VERDICT", "RULE", "UNKNOWN_METRIC");
        assertThat(issues.get(2).message()).contains("ROE");

        StockProfile strong = profile(0.25, 0.2, 1.0);
        Assessment fine = new Assessment(75.0, "FIT", "Durable franchise", List.of(), List.of(), List.of("roe", "pe"), null);
        assertThat(ReflectionValidator.validate(InvestorAgent.BUFFETT, fine, 70, strong, InvestorPanel.metricKeys(strong)))
                .isEmpty();
    }

    @Test
    void reflexionLessonsComeFromRepeatedIssues() {
        assertThat(ReflexionMemory.lesson(InvestorAgent.LYNCH, "RULE")).contains("PEG");
        assertThat(ReflexionMemory.lesson(InvestorAgent.RISK, "DIVERGENCE")).contains("35 points");
        assertThat(ReflexionMemory.lesson(InvestorAgent.RISK, "SOMETHING_ELSE")).isNull();
    }

    // ------------------------------------------------------------------ investor panel (assess + reflection)

    @Test
    void flaggedAnswerIsReviewedByTheCritic() {
        ScriptedModel model = new ScriptedModel()
                .reply("{\"score\":92,\"verdict\":\"STRONG_FIT\",\"thesis\":\"Wonderful\",\"strengths\":[\"brand\"],"
                        + "\"concerns\":[],\"metricsUsed\":[\"roe\"]}")
                .reply("{\"score\":48,\"verdict\":\"NEUTRAL\",\"thesis\":\"ROE too low for Buffett\",\"strengths\":[],"
                        + "\"concerns\":[\"ROE 6%\"],\"metricsUsed\":[\"roe\"],\"note\":\"Lowered: ROE below 10%\"}");
        LlmGateway llm = new LlmGateway(model, properties);
        InvestorPanel panel = new InvestorPanel(llm, properties, mapper);
        StockProfile weak = profile(0.06, 0.4, 1.0);
        AgentScore quant = QuantScorer.score(InvestorAgent.BUFFETT, weak);
        UsageMeter meter = meter(1, new ArrayList<>());

        InvestorPanel.Outcome first = panel.assess(meter, "WEAK", "STOCK DATA {}", InvestorAgent.BUFFETT, quant, weak, List.of());
        assertThat(first.original().score()).isEqualTo(92.0);
        assertThat(first.issues()).extracting(Issue::code).contains("RULE");

        InvestorPanel.Outcome reviewed = panel.reflect(meter, "WEAK", "STOCK DATA {}", quant, weak, List.of(), first);
        assertThat(reviewed.revised().score()).isEqualTo(48.0);
        assertThat(reviewed.effective().note()).contains("ROE");
        // the critic sees the original answer and the validator's findings after the shared prefix
        List<Message> review = model.prompts.get(1).getInstructions();
        assertThat(review.get(0).getText()).isEqualTo(ScreeningPrompts.ANALYST);
        assertThat(review.get(1).getText()).isEqualTo("STOCK DATA {}");
        assertThat(review.get(3)).isInstanceOf(AssistantMessage.class);
        assertThat(review.get(4).getText()).startsWith("REFLECTION REVIEW").contains("ROE");
        assertThat(meter.total().calls()).isEqualTo(2);
    }

    @Test
    void invalidJsonIsRetriedOnceWithFeedback() {
        ScriptedModel model = new ScriptedModel().reply("I think it is good").reply("{\"score\":61,\"verdict\":\"NEUTRAL\",\"thesis\":\"ok\",\"strengths\":[\"brand\"]}");
        InvestorPanel panel = new InvestorPanel(new LlmGateway(model, properties), properties, mapper);
        StockProfile p = profile(0.15, 0.5, 1.0);
        InvestorPanel.Outcome o = panel.assess(meter(1, new ArrayList<>()), "OK", "STOCK DATA {}", InvestorAgent.MUNGER,
                QuantScorer.score(InvestorAgent.MUNGER, p), p, List.of("lesson one"));
        assertThat(o.original().score()).isEqualTo(61.0);
        assertThat(model.prompts).hasSize(2);
        List<Message> retry = model.prompts.get(1).getInstructions();
        assertThat(retry.get(retry.size() - 1).getText()).startsWith("Your answer was not usable: the reply contains no JSON object");
        assertThat(retry).noneMatch(m -> m instanceof AssistantMessage);     // the unusable reply is not sent back
        assertThat(model.prompts.get(0).getInstructions().get(2).getText()).contains("lesson one").contains("PERSONA: Charlie Munger");
    }

    // ------------------------------------------------------------------ research agent (ReAct + tools)

    @Test
    void researchAgentReadsAnArticleThenAnswers() {
        String url = "https://www.idxchannel.com/market-news/bbca-laba-naik";
        Headline headline = new Headline(url, NewsSource.IDXCHANNEL, "Laba BBCA Naik 10 Persen", null,
                Instant.parse("2026-10-01T09:00:00Z"));
        NewsService news = mock(NewsService.class);
        NewsRepository repository = mock(NewsRepository.class);
        when(repository.brief(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(news.gather(eq("IDX"), eq("BBCA"), anyString())).thenReturn(new NewsService.Gathered(List.of(headline), List.of()));
        when(news.searchEnabled()).thenReturn(false);
        when(news.read(eq(url), anyList())).thenReturn(new ArticleText("Laba BBCA Naik", "lead", null, "Laba bersih naik 10%."));
        ScriptedModel model = new ScriptedModel()
                .toolCall("Thought: the earnings headline is material, read it", "readArticle", "{\"url\":\"" + url + "\"}")
                .reply("{\"sentiment\":\"POSITIVE\",\"summary\":\"Net profit up 10%.\",\"catalysts\":[\"Earnings growth\"],"
                        + "\"risks\":[],\"sources\":[\"" + url + "\"]}");
        ResearchAgent agent = new ResearchAgent(new LlmGateway(model, properties), news, repository, properties, mapper);

        ResearchAgent.Result r = agent.research(meter(1, new ArrayList<>()), "IDX", "BBCA", "PT Bank Central Asia Tbk",
                "Financial Services", LocalDate.of(2026, 10, 2), true, List.of());

        assertThat(r.brief().sentiment()).isEqualTo("POSITIVE");
        assertThat(r.brief().sources()).containsExactly(url);
        assertThat(r.trace()).hasSize(2);
        assertThat(r.trace().get(0).tools()).containsExactly("readArticle");
        assertThat(r.modelUsed()).isTrue();
        verify(news).read(eq(url), anyList());
        verify(repository).saveBrief(eq("IDX"), eq("BBCA"), eq(LocalDate.of(2026, 10, 2)), anyString(), anyString());
        // first turn offers readArticle only (no Tavily), the observation goes back as a tool response
        OpenAiChatOptions firstOptions = (OpenAiChatOptions) model.prompts.get(0).getOptions();
        assertThat(firstOptions.getToolCallbacks()).extracting(cb -> cb.getToolDefinition().name()).containsExactly("readArticle");
        assertThat(model.prompts.get(1).getInstructions()).anySatisfy(m -> assertThat(m).isInstanceOf(ToolResponseMessage.class));
    }

    @Test
    void noHeadlinesAndNoSearchMeansNoModelCall() {
        NewsService news = mock(NewsService.class);
        NewsRepository repository = mock(NewsRepository.class);
        when(repository.brief(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(news.gather(anyString(), anyString(), anyString())).thenReturn(new NewsService.Gathered(List.of(), List.of()));
        ScriptedModel model = new ScriptedModel();
        ResearchAgent agent = new ResearchAgent(new LlmGateway(model, properties), news, repository, properties, mapper);
        ResearchAgent.Result r = agent.research(meter(1, new ArrayList<>()), "IDX", "ABCD", "PT Abcd Tbk", null,
                LocalDate.of(2026, 10, 2), true, List.of());
        assertThat(r.modelUsed()).isFalse();
        assertThat(r.brief().sentiment()).isEqualTo("NEUTRAL");
        assertThat(model.prompts).isEmpty();
    }

    // ------------------------------------------------------------------ synthesis

    @Test
    void synthesisKeepsKnownTickersAndClampsAdjustments() {
        List<Row> rows = List.of(row(1, "AAAA", 80), row(2, "BBBB", 60));
        Synthesis raw = new Synthesis("Summary", List.of("n1", "n2"), List.of(
                new SynthesisAgent.StockView("aaaa", "high", "Thesis A", 9.0, "reason"),
                new SynthesisAgent.StockView("ZZZZ", "LOW", "unknown", 3.0, "x")), null, null);
        Synthesis s = SynthesisAgent.normalize(raw, rows, "anthropic/claude-opus-5.5");
        assertThat(s.stocks()).extracting(SynthesisAgent.StockView::ticker).containsExactly("AAAA", "BBBB");
        assertThat(s.stocks().get(0).adjustment()).isEqualTo(5.0);
        assertThat(s.stocks().get(0).conviction()).isEqualTo("HIGH");
        assertThat(s.stocks().get(1).conviction()).isEqualTo("MEDIUM");   // filled in from the score
        assertThat(s.stocks().get(1).adjustment()).isZero();

        // a stock without a conviction gets one from its score (no exception)
        Synthesis missing = SynthesisAgent.normalize(new Synthesis("S", List.of(), List.of(
                new SynthesisAgent.StockView("AAAA", null, null, null, null)), null, null), rows, "m");
        assertThat(missing.stocks().get(0).conviction()).isEqualTo("HIGH");
    }

    @Test
    void synthesisFallbackWithoutModel() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("topN", 2);
        Synthesis s = new SynthesisAgent(new LlmGateway(new ScriptedModel(), properties), properties, mapper)
                .synthesize(meter(1, new ArrayList<>()), context, List.of(row(1, "AAAA", 80), row(2, "BBBB", 60)), false);
        assertThat(s.model()).isNull();
        assertThat(s.fallback()).contains("budget");
        assertThat(s.executiveSummary()).contains("AAAA").contains("without the synthesis model");
    }

    // ------------------------------------------------------------------ helpers

    private static Row row(int rank, String ticker, double overall) {
        return new Row(rank, ticker, "PT " + ticker, "Industrials", overall, Map.of("BUFFETT", overall), Map.of(),
                Map.of(), "NEUTRAL", "No news", List.of());
    }

    private static StockProfile profile(double roe, double debtToEquity, double positiveYears) {
        return new StockProfile(false, 2e12, 1000.0, 5e9, 12.0, 1.5, 80.0, roe, 0.1, 0.4, 0.2, 0.12, debtToEquity, 1.5,
                0.08, 0.1, 7.0, 0.03, 0.9, 0.3, roe, positiveYears, 0.08, 0.1, 0.01, 0.05, 0.9, 0.06, 1.2, 0.2, 0.6, 8.0,
                3.2, 0.5, 4);
    }
}
