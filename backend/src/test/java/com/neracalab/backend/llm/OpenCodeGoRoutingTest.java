package com.neracalab.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** The DeepSeek calls go to OpenCode Go, translated; every other model stays on OpenRouter. */
class OpenCodeGoRoutingTest {

    private static final String DEEPSEEK = "deepseek/deepseek-v4.1-flash-1017";

    static final class Recording implements ChatModel {

        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
    }

    private static Prompt prompt(String model, Map<String, Object> extra) {
        return new Prompt(List.of(new UserMessage("x")),
                OpenAiChatOptions.builder().model(model).temperature(0.0).extraBody(extra).build());
    }

    @Test
    void deepSeekGoesToOpenCodeGoWithoutTheOpenRouterFields() {
        Recording openRouter = new Recording();
        Recording go = new Recording();
        ChatModel routed = new OpenCodeGoRouting(go, Map.of(DEEPSEEK, "deepseek-v4.1-flash")).wrap(openRouter);

        routed.call(prompt(DEEPSEEK, Map.of("reasoning", Map.of("enabled", false),
                "provider", Map.of("ignore", List.of("OpenInference")), "usage", Map.of("include", true),
                "response_format", Map.of("type", "json_object"))));

        assertThat(openRouter.prompts).isEmpty();
        OpenAiChatOptions sent = (OpenAiChatOptions) go.prompts.get(0).getOptions();
        assertThat(sent.getModel()).isEqualTo("deepseek-v4.1-flash");
        assertThat(sent.getTemperature()).isEqualTo(0.0);
        assertThat(sent.getExtraBody()).containsOnlyKeys("thinking", "response_format")
                .containsEntry("thinking", Map.of("type", "disabled"));
        assertThat(sent.getCustomHeaders()).containsKey("x-opencode-session");
    }

    @Test
    void aReasoningEffortBecomesTheReasoningEffort() {
        Recording go = new Recording();
        new OpenCodeGoRouting(go, Map.of(DEEPSEEK, "deepseek-v4.1-flash")).wrap(new Recording())
                .call(prompt(DEEPSEEK, Map.of("reasoning", Map.of("effort", "low"))));

        OpenAiChatOptions sent = (OpenAiChatOptions) go.prompts.get(0).getOptions();
        assertThat(sent.getReasoningEffort()).isEqualTo("low");
        assertThat(sent.getExtraBody()).isEmpty();
    }

    @Test
    void otherModelsStayOnOpenRouterUnchanged() {
        Recording openRouter = new Recording();
        Recording go = new Recording();
        Prompt claude = prompt("anthropic/claude-sonnet-5.5", Map.of("usage", Map.of("include", true)));
        new OpenCodeGoRouting(go, Map.of(DEEPSEEK, "deepseek-v4.1-flash")).wrap(openRouter).call(claude);

        assertThat(go.prompts).isEmpty();
        assertThat(openRouter.prompts).containsExactly(claude);
    }

    /**
     * A model that is not the OpenRouter client - a test's scripted or mocked one - is never routed: with the key in
     * backend/.env, the controller tests' DeepSeek calls had reached OpenCode Go instead of their script.
     */
    @Test
    void onlyTheOpenRouterClientIsRouted() {
        Recording scripted = new Recording();
        Recording go = new Recording();
        assertThat(new OpenCodeGoRouting(go, Map.of(DEEPSEEK, "deepseek-v4.1-flash")).route(scripted)).isSameAs(scripted);
    }

    @Test
    void withoutAKeyNothingIsRouted() {
        Recording openRouter = new Recording();
        assertThat(new OpenCodeGoRouting("", "https://x", List.of(DEEPSEEK + "=deepseek-v4.1-flash")).wrap(openRouter))
                .isSameAs(openRouter);
        assertThat(OpenCodeGoRouting.parse(List.of(" a = b ", "broken", "=c")))
                .isEqualTo(Map.of("a", "b"));
    }
}
