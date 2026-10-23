package com.neracalab.backend.ingestion.agent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The full-text extraction that takes over when the standard extraction cannot store an uploaded workbook
 * ({@code neracalab.ingestion.fallback.*}), see {@link FullTextExtractor}.
 *
 * @param model            model of the full-text extraction (an OpenRouter id), by default a stronger one than the
 *                         standard extraction's: it has to keep a whole statement consistent in one answer; blank =
 *                         the standard extraction's model ({@code spring.ai.openai.chat.model})
 * @param enabled          false: an upload ends where the standard extraction ends (as before)
 * @param after            longest the standard extraction (the AI agent) may run before the full-text
 *                         extraction takes over; never more than {@code neracalab.ingestion.job-timeout}
 * @param timeout          longest the full-text extraction may run; the upload is stopped and FAILED after it
 * @param modelCallTimeout longest one of its model calls may take (its answer is one long JSON document, so
 *                         more than the {@code model-call-timeout} of a tool turn)
 * @param maxChars         most characters of workbook text sent to the model (statement sheets first)
 * @param rounds           answers the model may give: the first, then its own corrections of what does not add up;
 *                         the last one is stored
 */
@ConfigurationProperties("neracalab.ingestion.fallback")
public record FallbackProperties(
        @DefaultValue("anthropic/claude-sonnet-5.5") String model,
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3m") Duration after,
        @DefaultValue("5m") Duration timeout,
        @DefaultValue("150s") Duration modelCallTimeout,
        @DefaultValue("120000") int maxChars,
        @DefaultValue("3") int rounds) {

    public FallbackProperties {
        if (after.isNegative() || after.isZero() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("neracalab.ingestion.fallback: after and timeout must be positive");
        }
        if (rounds < 1 || maxChars < 10_000) {
            throw new IllegalArgumentException("neracalab.ingestion.fallback: need rounds >= 1 and max-chars >= 10000");
        }
    }
}
