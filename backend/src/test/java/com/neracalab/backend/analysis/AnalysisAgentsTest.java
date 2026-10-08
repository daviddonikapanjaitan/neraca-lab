package com.neracalab.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.neracalab.backend.company.CompanyDetailResponse.Period;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.RagProperties;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Hit;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.screening.InvestorAgent;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.TestScreeningProperties;
import com.neracalab.backend.screening.agent.InvestorPanel;
import com.neracalab.backend.screening.agent.LlmGateway;
import com.neracalab.backend.screening.agent.UsageMeter;

import tools.jackson.databind.json.JsonMapper;

/** The analysis agents with a scripted model, stub retrieval and no database. */
class AnalysisAgentsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ScreeningProperties screening = TestScreeningProperties.create();
    private final AnalysisProperties properties = new AnalysisProperties(0.20, 0.10, 4, 6, 3, 4, 800, 4, 0.02, 1000);

    /** Replies in order with their completion tokens (default 120); records every prompt. */
    static final class Scripted implements ChatModel {
        final List<AssistantMessage> replies = new ArrayList<>();
        final List<Integer> completionTokens = new ArrayList<>();
        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            AssistantMessage message = replies.removeFirst();
            int completion = completionTokens.isEmpty() ? 120 : completionTokens.removeFirst();
            return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(800, completion, 800 + completion)).build());
        }
    }

    private UsageMeter meter(List<UsageMeter.Usage> sink) {
        return new UsageMeter(UUID.randomUUID(), 1, (run, usage) -> sink.add(usage));
    }

    private ContextTools tools(UsageMeter meter, boolean filings, boolean news, RagRepository rag, EmbeddingClient e) {
        return new ContextTools(1, "TEST", FactSheet.of(TestFacts.detail(), 4), e, rag, meter, properties, filings, news);
    }

    private static AnalysisResearchAgent.Subject subject() {
        return new AnalysisResearchAgent.Subject("TEST", "PT Test Tbk", "Industrials", List.of("2025 FY"), List.of("FS-2025.pdf"),
                1, List.of(), 0);
    }

    @Test
    void noStoredDocumentsMeansNoResearchCall() {
        Scripted model = new Scripted();
        AnalysisResearchAgent agent = new AnalysisResearchAgent(new LlmGateway(model, screening), screening, properties, mapper);
        List<UsageMeter.Usage> usages = new ArrayList<>();
        UsageMeter meter = meter(usages);
        AnalysisResearchAgent.Result r = agent.research(meter, subject(), tools(meter, false, false, null, null), true);
        assertThat(model.prompts).isEmpty();
        assertThat(r.modelUsed()).isFalse();
        assertThat(r.brief().business()).contains("No PDF documents or news");
        assertThat(r.brief().newsSentiment()).isEqualTo("NONE");
    }

    @Test
    void reactSearchesTheFilingsAndKeepsOnlyRetrievedEvidence() {
        RagRepository rag = mock(RagRepository.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyString())).thenReturn(new float[RagProperties.STORE_DIMENSIONS]);
        when(embeddings.model()).thenReturn("stub-embedding");
        when(rag.search(anyLong(), any(), any(), anyInt())).thenReturn(List.of(new Hit(11, 5, "TEST", SourceType.PDF,
                "FS 2025", null, "FS-2025.pdf", null, 12, 13, "Revenue   by segment:\n jewellery 80%", 0.21)));
        Scripted model = new Scripted();
        model.replies.add(AssistantMessage.builder().content("Thought: segments")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "searchFilings",
                        "{\"query\":\"revenue by segment\"}"))).build());
        model.replies.add(new AssistantMessage("""
                {"business":"Jewellery","risks":["Gold"],"newsSentiment":"weird",\
                "evidence":[{"ref":"F1","fact":"80% jewellery"},{"ref":"f7","fact":"invented"},{"ref":"F1","fact":""}]}"""));
        AnalysisResearchAgent agent = new AnalysisResearchAgent(new LlmGateway(model, screening), screening, properties, mapper);
        List<UsageMeter.Usage> usages = new ArrayList<>();
        UsageMeter meter = meter(usages);

        AnalysisResearchAgent.Result r = agent.research(meter, subject(), tools(meter, true, false, rag, embeddings), true);

        // only the tools that can help are offered: no news stored, so no searchNews
        List<String> offered = ((OpenAiChatOptions) model.prompts.getFirst().getOptions()).getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
        assertThat(offered).containsExactlyInAnyOrder("searchFilings", "getStatement");
        assertThat(r.retrieved()).singleElement().satisfies(x -> {
            assertThat(x.ref()).isEqualTo("F1");
            assertThat(x.where()).isEqualTo("pp. 12-13");
            assertThat(x.query()).isEqualTo("revenue by segment");
        });
        assertThat(r.brief().evidence()).extracting(ResearchBrief.Evidence::ref).containsExactly("F1");
        assertThat(r.droppedRefs()).isEqualTo(1);
        assertThat(r.brief().newsSentiment()).isEqualTo("NONE");
        assertThat(r.trace().getFirst().tools()).singleElement().asString().startsWith("searchFilings(");
        // the excerpt came back to the model as the tool's observation, its whitespace collapsed
        assertThat(model.prompts.get(1).getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.ToolResponseMessage)
                .map(m -> ((org.springframework.ai.chat.messages.ToolResponseMessage) m).getResponses().getFirst().responseData())
                .findFirst().orElseThrow()).contains("Revenue by segment: jewellery 80%").contains("\"ref\":\"F1\"");
        assertThat(usages).extracting(UsageMeter.Usage::stage).containsExactly("RESEARCH", "RETRIEVAL", "RESEARCH");
        assertThat(usages.get(1).costEstimated()).isTrue();
    }

    @Test
    void toolsRefuseRepeatedQueriesUnknownPeriodsAndMissingDocuments() {
        RagRepository rag = mock(RagRepository.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(anyString())).thenReturn(new float[RagProperties.STORE_DIMENSIONS]);
        when(rag.search(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        ContextTools tools = tools(meter(new ArrayList<>()), true, false, rag, embeddings);
        assertThat(tools.searchFilings("dividend policy")).isEmpty();
        assertThatThrownBy(() -> tools.searchFilings("Dividend  policy")).hasMessageContaining("searched already");
        assertThatThrownBy(() -> tools.searchNews("dividend")).hasMessageContaining("No news articles");
        assertThatThrownBy(() -> tools.getStatement("1999 FY")).hasMessageContaining("stored: 2025 FY");
        assertThat(tools.getStatement("2025 fy")).containsEntry("period", "2025 FY");

        ContextTools none = tools(meter(new ArrayList<>()), false, false, rag, embeddings);
        assertThatThrownBy(() -> none.searchFilings("x")).hasMessageContaining("No PDF documents");
    }

    @Test
    void synthesisIsBoundedAndHasAFallback() {
        AnalysisSynthesisAgent.Synthesis s = AnalysisSynthesisAgent.normalize(new AnalysisSynthesisAgent.Synthesis(
                "Summary", "very high", null, List.of("a", "b", "c", "d", "e"), null, null, null, null, -12.4, "Why", null,
                null), 72.0, "anthropic/claude-opus-5.5");
        assertThat(s.conviction()).isEqualTo("HIGH");     // unknown conviction: from the score
        assertThat(s.adjustment()).isEqualTo(-5.0);
        assertThat(s.bullCase()).hasSize(4);
        assertThat(s.bearCase()).isEmpty();
        assertThat(s.model()).isEqualTo("anthropic/claude-opus-5.5");

        AnalysisSynthesisAgent.Synthesis empty = AnalysisSynthesisAgent.normalize(new AnalysisSynthesisAgent.Synthesis(
                null, null, null, null, null, null, null, null, 4.0, "x", null, null), null, "m");
        assertThat(empty.adjustment()).isZero();     // no overall: no adjustment
        assertThat(empty.conviction()).isNull();     // no conviction given and no score: none
        assertThat(AnalysisSynthesisAgent.normalize(new AnalysisSynthesisAgent.Synthesis("S", null, null, null, null, null,
                null, null, null, null, null, null), 58.0, "m").conviction()).isEqualTo("MEDIUM");

        List<AnalysisSynthesisAgent.AgentRow> rows = List.of(
                new AnalysisSynthesisAgent.AgentRow("BUFFETT", "Warren Buffett", 60.0, 70.0, 64.0, "FIT", "t",
                        List.of("High ROE"), List.of("Pricey"), List.of()),
                new AnalysisSynthesisAgent.AgentRow("RISK", "Risk Agent", null, null, null, null, null, List.of(), List.of(),
                        List.of()));
        AnalysisSynthesisAgent.Synthesis fallback = AnalysisSynthesisAgent.fallback("TEST", rows,
                new ResearchBrief("Makes jewellery.", null, null, null, List.of("Gold price"), List.of("Exports"), "NONE",
                        null, List.of()), 64.0, "budget");
        assertThat(fallback.executiveSummary()).contains("overall score 64/100").contains("Warren Buffett 64 (FIT)")
                .contains("Makes jewellery.").contains("without the synthesis model");
        assertThat(fallback.bullCase()).containsExactly("Warren Buffett: High ROE");
        assertThat(fallback.keyRisks()).containsExactly("Gold price");
        assertThat(fallback.fallback()).isEqualTo("budget");
        assertThat(AnalysisSynthesisAgent.fallback("TEST", List.of(), null, null, "x").conviction()).isNull();
    }

    @Test
    void anAgentWithoutAQuantitativePriorIsToldSoAndNotCheckedAgainstOne() {
        Scripted model = new Scripted();
        model.replies.add(new AssistantMessage("""
                {"score":92,"verdict":"STRONG_FIT","thesis":"Great","strengths":["Growth"],"concerns":[],"metricsUsed":["revenue","madeUp"]}"""));
        InvestorPanel panel = new InvestorPanel(new LlmGateway(model, screening), screening, mapper);
        InvestorPanel.Outcome o = panel.assess(meter(new ArrayList<>()), "TEST", AnalysisPrompts.ANALYST, "COMPANY DOSSIER {}",
                InvestorAgent.BUFFETT, null, null, List.of(), Set.of("revenue"), 1000);
        assertThat(model.prompts.getFirst().getInstructions().getFirst().getText()).isEqualTo(AnalysisPrompts.ANALYST);
        assertThat(model.prompts.getFirst().getInstructions().getLast().getText())
                .contains("Quantitative scorecard: not available");
        // no divergence or philosophy rule without data; the unknown metric is still caught
        assertThat(o.issues()).extracting(i -> i.code()).containsExactly("UNKNOWN_METRIC");
    }

    @Test
    void anAnswerCutOffAtTheOutputLimitIsAskedAgainFromScratch() {
        // HRTA, 2026-10-08: four agents stopped at the limit mid-object; continuing the fragment gave no score
        Scripted model = new Scripted();
        model.replies.add(new AssistantMessage("{\"score\":58,\"verdict\":\"NEUTRAL\",\"thesis\":\"A long thesis that"));
        model.completionTokens.add(1000);
        model.replies.add(new AssistantMessage("""
                {"score":58,"verdict":"NEUTRAL","thesis":"Fair business (F1)","strengths":["Growth"],"concerns":["Debt"],\
                "metricsUsed":["revenue"]}"""));
        InvestorPanel panel = new InvestorPanel(new LlmGateway(model, screening), screening, mapper);
        InvestorPanel.Outcome o = panel.assess(meter(new ArrayList<>()), "HRTA", AnalysisPrompts.ANALYST,
                "COMPANY DOSSIER {}", InvestorAgent.LYNCH, null, null, List.of(), Set.of("revenue"), 1000);

        assertThat(o.original().score()).isEqualTo(58.0);
        assertThat(((OpenAiChatOptions) model.prompts.getFirst().getOptions()).getMaxTokens()).isEqualTo(1000);
        List<org.springframework.ai.chat.messages.Message> retry = model.prompts.get(1).getInstructions();
        // the fragment is not sent back (it would be continued); the feedback says it was cut off
        assertThat(retry).noneMatch(m -> m instanceof AssistantMessage);
        assertThat(retry.getLast().getText()).startsWith("Your answer was not usable: it was cut off at the output "
                + "limit of 1000 tokens").contains("starting with {");
    }

    @Test
    void factSheetShowsTheLatestInterimWithItsComparativeThenTheFiscalYears() {
        Period h1 = TestFacts.period(1, "2026 H1", 2026, "H1", LocalDate.of(2026, 6, 30));
        Period h1Before = TestFacts.period(2, "2025 H1", 2025, "H1", LocalDate.of(2025, 6, 30));
        Period q1 = TestFacts.period(3, "2026 Q1", 2026, "Q1", LocalDate.of(2026, 3, 31));
        List<Period> fys = new ArrayList<>();
        for (int y = 2025; y >= 2020; y--) {
            fys.add(TestFacts.period(10 + y, y + " FY", y, "FY", LocalDate.of(y, 12, 31)));
        }
        List<Period> all = new ArrayList<>(List.of(h1, q1));
        all.add(fys.getFirst());
        all.add(h1Before);
        all.addAll(fys.subList(1, fys.size()));
        assertThat(FactSheet.select(all, 3)).extracting(Period::period)
                .containsExactly("2026 H1", "2025 H1", "2025 FY", "2024 FY", "2023 FY");
        // the latest period is a fiscal year: no interim period
        assertThat(FactSheet.select(fys, 2)).extracting(Period::period).containsExactly("2025 FY", "2024 FY");
        assertThat(FactSheet.select(List.of(), 2)).isEmpty();
    }

    @Test
    void factSheetScalesAmountsByCurrency() {
        FactSheet idr = FactSheet.of(TestFacts.detail(), 4);
        Map<String, Object> idrOverview = idr.overview();
        assertThat(idrOverview.get("amountUnit").toString()).startsWith("IDR billions");
        @SuppressWarnings("unchecked")
        Map<String, Object> income = (Map<String, Object>) ((List<Map<String, Object>>) idrOverview.get("periods"))
                .getFirst().get("income");
        assertThat(income).containsEntry("revenue", 1234.6).containsEntry("basicEps", 85.5);
        assertThat(FactSheet.of(TestFacts.detail("USD"), 4).amountUnit()).isEqualTo("USD millions");
    }
}
