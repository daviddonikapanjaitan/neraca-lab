package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.neracalab.backend.ingestion.mapping.FilingMapper;
import com.neracalab.backend.ingestion.xlsx.IdxWorkbookReader;
import com.openai.errors.OpenAIInvalidDataException;

/** The job time limit inside the agent: model calls, retries, tools and the planner stop at it. */
class JobDeadlineTest {

    /** A model that answers after {@code delay} (or fails first, then answers). */
    static final class SlowModel implements ChatModel {

        final Duration delay;
        final RuntimeException firstFailure;
        final AtomicInteger calls = new AtomicInteger();

        SlowModel(Duration delay, RuntimeException firstFailure) {
            this.delay = delay;
            this.firstFailure = firstFailure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int n = calls.incrementAndGet();
            if (n == 1 && firstFailure != null) {
                throw firstFailure;
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }
    }

    private static IngestionAgent agent(ChatModel model, Duration retryBackoff) {
        return new IngestionAgent(model, null, null, new AgentProperties(30, 2, 0.0, 2, retryBackoff), null,
                ConfiguredChatModel.name());
    }

    @Test
    void aSlowModelCallStopsAtTheDeadline() {
        SlowModel model = new SlowModel(Duration.ofSeconds(5), null);
        AgentTrace trace = new AgentTrace(JobDeadline.after(Duration.ofMillis(300)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> agent(model, Duration.ofMillis(1)).call(new Prompt("x"), trace))
                .isInstanceOf(JobDeadline.JobTimeoutException.class)
                .hasMessageContaining("time limit");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aModelCallWithinTheDeadlineAnswers() {
        SlowModel model = new SlowModel(Duration.ofMillis(10), null);
        AgentTrace trace = new AgentTrace(JobDeadline.after(Duration.ofSeconds(10)));

        assertThat(agent(model, Duration.ofMillis(1)).call(new Prompt("x"), trace).getResult().getOutput().getText())
                .isEqualTo("done");
    }

    /** A retry whose pause would end after the deadline is not waited for. */
    @Test
    void noRetryPastTheDeadline() {
        SlowModel model = new SlowModel(Duration.ZERO,
                new OpenAIInvalidDataException("Error reading response", new InterruptedIOException("timeout")));
        AgentTrace trace = new AgentTrace(JobDeadline.after(Duration.ofMillis(500)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> agent(model, Duration.ofSeconds(10)).call(new Prompt("x"), trace))
                .isInstanceOf(JobDeadline.JobTimeoutException.class);
        assertThat(model.calls.get()).isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void noModelCallAndNoToolAfterTheDeadline() throws Exception {
        SlowModel model = new SlowModel(Duration.ZERO, null);
        JobDeadline passed = JobDeadline.after(Duration.ofMillis(1));
        Thread.sleep(20);

        assertThatThrownBy(() -> agent(model, Duration.ofMillis(1)).call(new Prompt("x"), new AgentTrace(passed)))
                .isInstanceOf(JobDeadline.JobTimeoutException.class);
        assertThat(model.calls.get()).isZero();
        var call = new AssistantMessage.ToolCall("1", "function", "saveStatements", "{}");
        assertThatThrownBy(() -> ToolExecutor.execute(List.of(call), Map.of(), new AgentTrace(passed), 1, 1))
                .isInstanceOf(JobDeadline.JobTimeoutException.class)
                .hasMessageContaining("saveStatements");
    }

    /** The planner's fallback (default plan on a bad answer) must not swallow the time limit. */
    @Test
    void thePlannerDoesNotSwallowTheTimeLimit() throws Exception {
        Path file = Path.of("..", "data", "HRTA", "xlsx", "FinancialStatement-2026-II-HRTA.xlsx");
        assumeTrue(Files.exists(file), "HRTA source data not available");
        IngestionSession session;
        try (InputStream in = Files.newInputStream(file)) {
            session = new IngestionSession(new FilingMapper(new IdxWorkbookReader().read(in, file.getFileName().toString())));
        }
        session.deadline(JobDeadline.after(Duration.ofMillis(1)));
        Thread.sleep(20);
        SlowModel model = new SlowModel(Duration.ZERO, null);

        assertThatThrownBy(() -> new IngestionAgent(model, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)),
                tools.jackson.databind.json.JsonMapper.builder().build(), ConfiguredChatModel.name()).run(session))
                .isInstanceOf(JobDeadline.JobTimeoutException.class);
        assertThat(model.calls.get()).isZero();
        assertThat(session.hasWrites()).isFalse();
    }

    @Test
    void describesTheLimit() {
        assertThat(JobDeadline.describe(Duration.ofMinutes(5))).isEqualTo("5 minutes");
        assertThat(JobDeadline.describe(Duration.ofMinutes(1))).isEqualTo("1 minute");
        assertThat(JobDeadline.describe(Duration.ofSeconds(1))).isEqualTo("1 second");
        assertThat(JobDeadline.describe(Duration.ofSeconds(90))).isEqualTo("90 seconds");
        assertThat(JobDeadline.NONE.passed()).isFalse();
        assertThat(JobDeadline.NONE.remaining()).isNull();
    }
}
