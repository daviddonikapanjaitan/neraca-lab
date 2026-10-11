package com.neracalab.backend.ingestion.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The ingestion's model calls never go to an excluded OpenRouter provider ({@code neracalab.ingestion.provider-ignore},
 * default OpenInference: its DeepSeek endpoint loops on long answers until OpenRouter resets the stream after 60 s,
 * which cost the full-text extraction two minutes per upload).
 */
class ProviderIgnoreTest {

    @Test
    void theExcludedProvidersAreSentWithEveryRequest() {
        IngestionAgent agent = new IngestionAgent(null, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)), null,
                "test/model", false, Duration.ofSeconds(45), List.of("OpenInference", " ", "Other"));

        Map<String, Object> extra = agent.options().build().getExtraBody();

        assertThat(extra.get("provider")).isEqualTo(Map.of("ignore", List.of("OpenInference", "Other")));
        assertThat(extra.get("reasoning")).isEqualTo(Map.of("enabled", false));
    }

    @Test
    void withoutExclusionsNoProviderRestrictionIsSent() {
        IngestionAgent agent = new IngestionAgent(null, null, null, new AgentProperties(30, 2, 0.0, 2, Duration.ofMillis(1)), null,
                "test/model", true, Duration.ofSeconds(45), List.of());

        // only the request for the call's cost (recorded per upload job) is left
        assertThat(agent.options().build().getExtraBody()).isEqualTo(Map.of("usage", Map.of("include", true)));
    }
}
