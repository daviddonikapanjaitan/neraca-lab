package com.neracalab.backend.screening.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.ScreeningProperties.ModelPrice;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.completions.CompletionUsage;

/**
 * Every model call of the screening goes through here (OpenRouter, OpenAI-compatible). It sets the
 * per-model options and records tokens and cost in the run's {@link UsageMeter}:
 * <ul>
 *   <li>DeepSeek (research, investor agents, critic): reasoning switched off (its reasoning tokens
 *       are billed as output), OpenRouter routed to the cheapest provider except the ignored ones
 *       ({@code provider-ignore}), low temperature, JSON replies where no tools are offered</li>
 *   <li>Opus (synthesis): reasoning effort {@code low}, no sampling parameters (Opus 5.5 takes none)</li>
 * </ul>
 * The cost is the one OpenRouter reports ({@code usage.cost}); without it, it is estimated from
 * {@code neracalab.screening.llm.prices}. A call that fails transiently (stream reset, timeout, network error,
 * HTTP 408 / 429 / 5xx) is retried {@code model-retries} times; every attempt is recorded.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    /** A failed model call (provider unreachable, refused, ...). */
    public static class LlmException extends RuntimeException {

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** What a call is for (recorded in {@code llm_usage}). */
    public record Purpose(String stage, String agent, String ticker) {
    }

    public record Reply(AssistantMessage message, UsageMeter.Usage usage) {

        public String text() {
            return message.getText() == null ? "" : message.getText();
        }
    }

    private final ChatModel chatModel;
    private final ScreeningProperties.Llm properties;

    public LlmGateway(ChatModel chatModel, ScreeningProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties.llm();
    }

    /**
     * One call of a cheap worker model (research agent, investor agents, critic).
     *
     * @param tools offered tools (empty: none, and the reply must be one JSON object)
     */
    public Reply worker(UsageMeter meter, Purpose purpose, String model, List<Message> messages,
                        List<ToolCallback> tools, int maxTokens) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reasoning", Map.of("enabled", false));
        extra.put("usage", Map.of("include", true));
        Map<String, Object> provider = provider(properties);
        if (!provider.isEmpty()) {
            extra.put("provider", provider);
        }
        if (tools.isEmpty()) {
            extra.put("response_format", Map.of("type", "json_object"));
        }
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.1)
                .maxTokens(maxTokens)
                .extraBody(extra);
        if (!tools.isEmpty()) {
            options.toolCallbacks(new ArrayList<>(tools)).parallelToolCalls(true);
        }
        return call(meter, purpose, model, messages, options.build());
    }

    /** The synthesis call (Opus): low reasoning effort, no sampling parameters. */
    public Reply synthesis(UsageMeter meter, Purpose purpose, List<Message> messages) {
        String model = properties.synthesisModel();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reasoning", Map.of("effort", properties.synthesisEffort()));
        extra.put("usage", Map.of("include", true));
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .maxTokens(properties.synthesisMaxTokens())
                .extraBody(extra)
                .build();
        return call(meter, purpose, model, messages, options);
    }

    /** OpenRouter provider routing of the worker calls: sort and ignored providers (empty: OpenRouter's default). */
    static Map<String, Object> provider(ScreeningProperties.Llm properties) {
        Map<String, Object> provider = new LinkedHashMap<>();
        if (properties.providerSort() != null && !properties.providerSort().isBlank()) {
            provider.put("sort", properties.providerSort());
        }
        if (!properties.providerIgnore().isEmpty()) {
            provider.put("ignore", properties.providerIgnore());
        }
        return provider;
    }

    private Reply call(UsageMeter meter, Purpose purpose, String model, List<Message> messages, OpenAiChatOptions options) {
        for (int attempt = 0; ; attempt++) {
            long start = System.nanoTime();
            ChatResponse response;
            try {
                response = chatModel.call(new Prompt(messages, options));
            } catch (RuntimeException e) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                String message = rootMessage(e);
                meter.record(new UsageMeter.Usage(purpose.stage(), purpose.agent(), purpose.ticker(), model, 0, 0, 0, 0,
                        0, false, ms, message.length() > 500 ? message.substring(0, 500) : message));
                if (attempt >= properties.modelRetries() || !isTransient(e)) {
                    throw new LlmException("Model call failed (" + model + "): " + message
                            + (attempt > 0 ? " (" + (attempt + 1) + " attempts)" : ""), e);
                }
                long pause = properties.retryBackoff().toMillis() << attempt;
                log.warn("{} {} {}: model call failed transiently ({}), retry {} of {} in {} ms", purpose.stage(),
                        purpose.agent(), purpose.ticker(), message, attempt + 1, properties.modelRetries(), pause);
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Model call failed (" + model + "): " + message, e);
                }
                continue;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            return reply(meter, purpose, model, response, ms);
        }
    }

    /**
     * Whether a failed call is worth repeating: an I/O error anywhere in the cause chain (stream reset, timeout,
     * dropped connection) or HTTP 408 / 429 / 5xx. A bad request or an authentication error is not.
     */
    static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.io.IOException || t instanceof OpenAIIoException) {
                return true;
            }
            if (t instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                return status == 408 || status == 429 || status >= 500;
            }
        }
        return false;
    }

    private Reply reply(UsageMeter meter, Purpose purpose, String model, ChatResponse response, long ms) {
        UsageMeter.Usage usage = usage(purpose, model, response, ms);
        meter.record(usage);
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LlmException("Model call returned no message (" + model + ")", null);
        }
        log.debug("{} {} {}: {} in / {} out tokens, ${} ({} ms)", purpose.stage(), purpose.agent(), purpose.ticker(),
                usage.promptTokens(), usage.completionTokens(), usage.costUsd(), ms);
        return new Reply(response.getResult().getOutput(), usage);
    }

    private UsageMeter.Usage usage(Purpose purpose, String model, ChatResponse response, long ms) {
        int prompt = 0;
        int completion = 0;
        int reasoning = 0;
        int cached = 0;
        Double cost = null;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            if (usage.getCacheReadInputTokens() != null) {
                cached = usage.getCacheReadInputTokens().intValue();
            }
            if (usage.getNativeUsage() instanceof CompletionUsage nativeUsage) {
                reasoning = nativeUsage.completionTokensDetails().flatMap(d -> d.reasoningTokens()).orElse(0L).intValue();
                if (cached == 0) {
                    cached = nativeUsage.promptTokensDetails().flatMap(d -> d.cachedTokens()).orElse(0L).intValue();
                }
                cost = reportedCost(nativeUsage);
            }
        }
        boolean estimated = cost == null;
        if (estimated) {
            ModelPrice price = properties.price(model);
            cost = price.cost(prompt, completion);
        }
        return new UsageMeter.Usage(purpose.stage(), purpose.agent(), purpose.ticker(), model, prompt, completion,
                reasoning, cached, cost, estimated, ms, null);
    }

    /** OpenRouter's {@code usage.cost} (USD), when present. */
    static Double reportedCost(CompletionUsage usage) {
        JsonValue value = usage._additionalProperties().get("cost");
        if (value == null) {
            return null;
        }
        try {
            Double cost = value.convert(Double.class);
            return cost == null || !Double.isFinite(cost) || cost < 0 ? null : cost;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The message of the innermost cause (what the provider or tool actually said). */
    public static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
