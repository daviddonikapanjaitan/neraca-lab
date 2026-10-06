package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Every ingestion request names the configured model. Spring AI 2.0's OpenAiChatOptions fills a missing
 * model with "gpt-5-mini", and per-request options override the configured default: options built
 * without a model sent every ingestion call to openai/gpt-5-mini instead of OPENAI_MODEL.
 */
class IngestionAgentModelTest {

    /** OPENAI_MODEL from the environment or backend/.env, else the application.yaml default. */
    private static final String CONFIGURED = ConfiguredChatModel.name();

    @Test
    void springAiFillsAMissingModelWithItsOwnDefault() {
        // the trap this guards against; if Spring AI changes it, this test documents the new behaviour
        assertThat(OpenAiChatOptions.builder().temperature(0.0).build().getModel()).isEqualTo("gpt-5-mini");
    }

    @Test
    void everyRequestNamesTheConfiguredModelAndTheResponseModelIsRecorded() {
        List<Prompt> prompts = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().model(CONFIGURED).build());
            }
        };
        IngestionAgent agent = new IngestionAgent(model, null, null,
                new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)), null, CONFIGURED);
        AgentTrace trace = new AgentTrace();

        assertThat(agent.options().build().getModel()).isEqualTo(CONFIGURED);
        agent.call(new Prompt("x", agent.options().build()), trace);

        assertThat(prompts).singleElement().satisfies(p -> assertThat(p.getOptions().getModel()).isEqualTo(CONFIGURED));
        assertThat(trace.modelsUsed()).containsExactly(CONFIGURED);
    }
}
