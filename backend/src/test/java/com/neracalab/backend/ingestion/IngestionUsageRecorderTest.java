package com.neracalab.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.neracalab.backend.ingestion.agent.AgentProperties;
import com.neracalab.backend.ingestion.agent.AgentTrace;
import com.neracalab.backend.ingestion.agent.IngestionAgent;
import com.neracalab.backend.ingestion.agent.JobDeadline;
import com.neracalab.backend.ingestion.agent.ModelCallListener;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.screening.TestScreeningProperties;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cost of an upload job: every model call of the ingestion - the standard extraction's DeepSeek calls and the
 * full-text extraction's Haiku calls - is recorded for the job with its stage, the model asked, its tokens and the
 * cost OpenRouter reports. An OpenAI-compatible server on localhost answers the calls, so the tokens and the cost
 * are read from a real response.
 */
class IngestionUsageRecorderTest {

    private static final String DEEPSEEK = "deepseek/deepseek-v4-flash-0731";
    private static final String HAIKU = "anthropic/claude-haiku-5.5";

    /** OpenRouter's usage of a call: 1,200 prompt tokens (200 from the cache), 300 completion tokens, USD 0.00042. */
    private static final String USAGE_WITH_COST = """
            {"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500,"cost":0.00042,
             "prompt_tokens_details":{"cached_tokens":200},"completion_tokens_details":{"reasoning_tokens":25}}""";
    private static final String USAGE_WITHOUT_COST = """
            {"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500}""";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final IngestionJobRepository jobs = mock(IngestionJobRepository.class);
    private final IngestionUsageRecorder recorder = new IngestionUsageRecorder(jobs, TestScreeningProperties.create());
    private final UUID job = UUID.randomUUID();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void everyCallIsRecordedWithItsStageModelTokensAndTheReportedCost() throws Exception {
        IngestionAgent agent = agent(model(startServer(USAGE_WITH_COST)));
        AgentTrace trace = new AgentTrace(JobDeadline.NONE, recorder.forJob(job, "NSSS"));

        // the standard extraction asks the configured model, the full-text extraction its own
        trace.stage(ModelCallListener.PLAN);
        agent.call(new Prompt("plan", agent.options().build()), trace, Duration.ofSeconds(30));
        trace.stage(ModelCallListener.FULL_TEXT);
        agent.call(new Prompt("map", agent.options().model(HAIKU).build()), trace, Duration.ofSeconds(30));

        List<IngestionJob.ModelCall> calls = recorded(2);
        assertThat(calls).extracting(IngestionJob.ModelCall::stage).containsExactly("PLAN", "FULL_TEXT");
        assertThat(calls).extracting(IngestionJob.ModelCall::model).containsExactly(DEEPSEEK, HAIKU);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.ticker()).isEqualTo("NSSS");
            assertThat(call.promptTokens()).isEqualTo(1200);
            assertThat(call.completionTokens()).isEqualTo(300);
            assertThat(call.cachedTokens()).isEqualTo(200);
            assertThat(call.reasoningTokens()).isEqualTo(25);
            assertThat(call.costUsd()).isEqualTo(0.00042, within(1e-12));
            assertThat(call.costEstimated()).isFalse();
            assertThat(call.error()).isNull();
            assertThat(call.durationMs()).isGreaterThanOrEqualTo(0);
        });
        // the cost is asked for on the wire, and each request names its own model
        assertThat(bodies).hasSize(2);
        for (String body : bodies) {
            assertThat(json.readTree(body).path("usage").path("include").asBoolean()).isTrue();
        }
        assertThat(json.readTree(bodies.get(0)).path("model").asString()).isEqualTo(DEEPSEEK);
        assertThat(json.readTree(bodies.get(1)).path("model").asString()).isEqualTo(HAIKU);
    }

    @Test
    void aCallWithoutAReportedCostIsPricedFromTheListPricesAndFlagged() throws Exception {
        IngestionAgent agent = agent(model(startServer(USAGE_WITHOUT_COST)));
        AgentTrace trace = new AgentTrace(JobDeadline.NONE, recorder.forJob(job, "NSSS"));
        trace.stage(ModelCallListener.FULL_TEXT);

        agent.call(new Prompt("map", agent.options().model(HAIKU).build()), trace, Duration.ofSeconds(30));

        IngestionJob.ModelCall call = recorded(1).getFirst();
        // Haiku 5.5: USD 0.10 / 0.50 per million tokens -> 1,200 x 0.10 + 300 x 0.50 = 270 millionths
        assertThat(call.costUsd()).isEqualTo(0.00027, within(1e-12));
        assertThat(call.costEstimated()).isTrue();
        assertThat(call.promptTokens()).isEqualTo(1200);
    }

    @Test
    void aFailedAttemptIsRecordedWithItsErrorAndTheRetryWithItsCost() throws Exception {
        ChatModel answering = model(startServer(USAGE_WITH_COST));
        AtomicInteger attempts = new AtomicInteger();
        ChatModel firstFails = prompt -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("call failed", new IOException("stream was reset: CANCEL"));
            }
            return answering.call(prompt);
        };
        IngestionAgent agent = agent(firstFails);
        AgentTrace trace = new AgentTrace(JobDeadline.NONE, recorder.forJob(job, null));
        trace.stage(ModelCallListener.EXECUTE);

        agent.call(new Prompt("x", agent.options().build()), trace, Duration.ofSeconds(30));

        List<IngestionJob.ModelCall> calls = recorded(2);
        assertThat(trace.modelRetries()).isEqualTo(1);
        IngestionJob.ModelCall failed = calls.get(0);
        assertThat(failed.error()).isEqualTo("stream was reset: CANCEL");
        assertThat(failed.stage()).isEqualTo("EXECUTE");
        assertThat(failed.model()).isEqualTo(DEEPSEEK);
        assertThat(failed.promptTokens()).isZero();
        assertThat(failed.costUsd()).isZero();
        assertThat(failed.ticker()).isNull();
        IngestionJob.ModelCall answered = calls.get(1);
        assertThat(answered.error()).isNull();
        assertThat(answered.costUsd()).isEqualTo(0.00042, within(1e-12));
    }

    @Test
    void aCallThatCannotBeRecordedDoesNotFailTheIngestion() throws Exception {
        doThrow(new IllegalStateException("database down")).when(jobs).recordModelCall(any(), any());
        IngestionAgent agent = agent(model(startServer(USAGE_WITH_COST)));
        AgentTrace trace = new AgentTrace(JobDeadline.NONE, recorder.forJob(job, "NSSS"));

        assertThat(agent.call(new Prompt("x", agent.options().build()), trace, Duration.ofSeconds(30))
                .getResult().getOutput().getText()).isEqualTo("done");
        assertThat(trace.modelRetries()).isZero();      // the answer is used as it is, not asked for again
    }

    @Test
    void anIngestionOutsideAJobRecordsNothing() throws Exception {
        IngestionAgent agent = agent(model(startServer(USAGE_WITH_COST)));

        agent.call(new Prompt("x", agent.options().build()), new AgentTrace(), Duration.ofSeconds(30));

        verify(jobs, times(0)).recordModelCall(any(), any());
    }

    // ------------------------------------------------------------------ helpers

    private List<IngestionJob.ModelCall> recorded(int calls) {
        ArgumentCaptor<IngestionJob.ModelCall> captor = ArgumentCaptor.forClass(IngestionJob.ModelCall.class);
        verify(jobs, times(calls)).recordModelCall(eq(job), captor.capture());
        return captor.getAllValues();
    }

    private IngestionAgent agent(ChatModel model) {
        return new IngestionAgent(model, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)), json,
                DEEPSEEK, false, Duration.ofSeconds(30));
    }

    private static ChatModel model(String baseUrl) {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().baseUrl(baseUrl).apiKey("test").model("m").build())
                .build();
    }

    /** An OpenAI-compatible server answering every call with "done" and the given usage. */
    private String startServer(String usage) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            JsonNode model = json.readTree(request).path("model");
            byte[] out = """
                    {"id":"x","object":"chat.completion","created":1,"model":"%s",
                     "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}],
                     "usage":%s}""".formatted(model.asString(), usage).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
}
