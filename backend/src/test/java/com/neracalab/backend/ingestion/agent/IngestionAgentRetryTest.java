package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.openai.errors.OpenAIInvalidDataException;

/**
 * Model calls survive transient provider failures, e.g. GGRM 2024-Tahunan, whose first execution call
 * failed with "OpenAIInvalidDataException: Error reading response" (OkHttp read timeout) and failed
 * the whole ingestion. Other failures are not retried.
 */
class IngestionAgentRetryTest {

    /** Replies with the queued outcomes in order: a RuntimeException is thrown, a String answered. */
    static final class ScriptedModel implements ChatModel {
        final Deque<Object> outcomes = new ArrayDeque<>();
        int calls;

        ScriptedModel(Object... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            Object next = outcomes.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage((String) next))));
        }
    }

    private static IngestionAgent agent(ChatModel model) {
        return new IngestionAgent(model, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)), null,
                ConfiguredChatModel.name());
    }

    private static RuntimeException readTimeout() {
        return new OpenAIInvalidDataException("Error reading response", new InterruptedIOException("timeout"));
    }

    @Test
    void retriesAReadTimeoutAndCountsIt() {
        ScriptedModel model = new ScriptedModel(readTimeout(), readTimeout(), "done");
        AgentTrace trace = new AgentTrace();

        ChatResponse response = agent(model).call(new Prompt("x"), trace);

        assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
        assertThat(model.calls).isEqualTo(3);
        assertThat(trace.modelCalls()).isEqualTo(3);
        assertThat(trace.modelRetries()).isEqualTo(2);
    }

    @Test
    void givesUpAfterTheConfiguredRetries() {
        ScriptedModel model = new ScriptedModel(readTimeout(), readTimeout(), readTimeout(), "never");

        assertThatThrownBy(() -> agent(model).call(new Prompt("x"), new AgentTrace()))
                .isInstanceOf(OpenAIInvalidDataException.class);
        assertThat(model.calls).isEqualTo(3);
    }

    @Test
    void doesNotRetryAPermanentFailure() {
        ScriptedModel model = new ScriptedModel(new IllegalArgumentException("bad request"), "never");

        assertThatThrownBy(() -> agent(model).call(new Prompt("x"), new AgentTrace()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void classifiesFailures() {
        assertThat(IngestionAgent.isTransient(readTimeout())).isTrue();
        assertThat(IngestionAgent.isTransient(new RuntimeException(new java.net.SocketTimeoutException()))).isTrue();
        assertThat(IngestionAgent.isTransient(new OpenAIInvalidDataException("unparseable JSON"))).isFalse();
        assertThat(IngestionAgent.isTransient(new IllegalStateException("x"))).isFalse();
    }
}
