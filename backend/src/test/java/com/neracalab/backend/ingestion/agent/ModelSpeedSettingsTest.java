package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The settings that keep an ingestion fast: reasoning off on the wire (an OpenAI-compatible server on localhost
 * captures the request body) and a per-call timeout that retries a stalled response.
 */
class ModelSpeedSettingsTest {

    private static final String COMPLETION = """
            {"id":"x","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"done"}}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = COMPLETION.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private IngestionAgent agent(String baseUrl, boolean reasoning) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().baseUrl(baseUrl).apiKey("test").model("m").build())
                .build();
        return new IngestionAgent(model, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)), json,
                "test/model", reasoning, Duration.ofSeconds(30));
    }

    @Test
    void reasoningIsSwitchedOffOnTheWire() throws Exception {
        IngestionAgent agent = agent(startServer(), false);

        ChatResponse response = agent.call(new Prompt("hi", agent.options().build()), new AgentTrace());

        assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
        JsonNode body = json.readTree(bodies.getFirst());
        assertThat(body.path("model").asString()).isEqualTo("test/model");
        assertThat(body.path("reasoning").path("enabled").isBoolean()).isTrue();
        assertThat(body.path("reasoning").path("enabled").asBoolean()).isFalse();
    }

    @Test
    void reasoningCanBeSwitchedOn() throws Exception {
        IngestionAgent agent = agent(startServer(), true);

        agent.call(new Prompt("hi", agent.options().build()), new AgentTrace());

        assertThat(json.readTree(bodies.getFirst()).has("reasoning")).isFalse();
    }

    /** A call slower than the per-call timeout counts as stalled: retried, and a fast second answer is used. */
    @Test
    void aStalledCallIsRetriedQuickly() {
        JobDeadlineTest.SlowModel stalled = new JobDeadlineTest.SlowModel(Duration.ofSeconds(5), null);
        JobDeadlineTest.SlowModel fast = new JobDeadlineTest.SlowModel(Duration.ZERO, null);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.ai.chat.model.ChatModel firstStallsThenAnswers =
                prompt -> calls.incrementAndGet() == 1 ? stalled.call(prompt) : fast.call(prompt);
        IngestionAgent agent = new IngestionAgent(firstStallsThenAnswers, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)),
                json, "test/model", false, Duration.ofMillis(300));
        AgentTrace trace = new AgentTrace();
        long start = System.nanoTime();

        assertThat(agent.call(new Prompt("x"), trace).getResult().getOutput().getText()).isEqualTo("done");
        assertThat(trace.modelRetries()).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aCallThatAlwaysStallsGivesUpAfterTheRetries() {
        JobDeadlineTest.SlowModel slow = new JobDeadlineTest.SlowModel(Duration.ofSeconds(5), null);
        IngestionAgent agent = new IngestionAgent(slow, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)),
                json, "test/model", false, Duration.ofMillis(200));
        long start = System.nanoTime();

        assertThatThrownBy(() -> agent.call(new Prompt("x"), new AgentTrace()))
                .hasMessageContaining("model call took longer than")
                .satisfies(e -> assertThat(IngestionAgent.isTransient(e)).isTrue());
        assertThat(slow.calls.get()).isEqualTo(3);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }
}
