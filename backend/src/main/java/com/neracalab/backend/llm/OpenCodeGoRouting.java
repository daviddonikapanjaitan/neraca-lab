package com.neracalab.backend.llm;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The DeepSeek calls of the ingestion and the screening go to OpenCode Go (a monthly subscription, OpenAI-compatible)
 * instead of OpenRouter; every other model (Claude of the full-text extraction and the synthesis) and the embeddings
 * stay on OpenRouter. On when {@code neracalab.llm.opencode-go.api-key} (env {@code OPENCODE_GO_API_KEY}) is set.
 * <p>
 * A call is routed by its model: {@code models} maps an OpenRouter id to OpenCode Go's
 * ({@code deepseek/deepseek-v4.1-flash-1017=deepseek-v4.1-flash}). The request is translated on the way:
 * <ul>
 *   <li>OpenRouter's own fields are left out: {@code provider} (routing) and {@code usage} (the reported cost; the
 *       cost of a Go call is estimated from the list prices, as for any call without one)</li>
 *   <li>{@code "reasoning": {"enabled": false}} becomes DeepSeek's {@code "thinking": {"type": "disabled"}}, and
 *       {@code "reasoning": {"effort": e}} the {@code reasoning_effort} e</li>
 *   <li>the header {@code x-opencode-session}, which OpenCode Go requires on every call</li>
 * </ul>
 */
@Component
public class OpenCodeGoRouting {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeGoRouting.class);

    /** Request fields only OpenRouter knows. */
    private static final Set<String> OPENROUTER_ONLY = Set.of("provider", "usage", "reasoning");

    /** OpenCode Go's model of an OpenRouter id; empty: nothing routed. */
    private final Map<String, String> models;
    /** OpenCode Go; {@code null}: off. */
    private final ChatModel go;
    private final String session = "neracalab-" + UUID.randomUUID();

    /**
     * @param apiKey  the OpenCode Go key; empty: off, every call stays on OpenRouter
     * @param baseUrl OpenCode Go's OpenAI-compatible endpoint
     * @param models  {@code openrouter-id=opencode-go-id} pairs
     */
    @Autowired
    public OpenCodeGoRouting(@Value("${neracalab.llm.opencode-go.api-key:}") String apiKey,
                             @Value("${neracalab.llm.opencode-go.base-url:https://opencode.ai/inference/go/openai/v1}") String baseUrl,
                             @Value("${neracalab.llm.opencode-go.models:}") List<String> models) {
        this(apiKey == null || apiKey.isBlank() ? null : OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().baseUrl(baseUrl).apiKey(apiKey.trim()).build())
                .build(), parse(models));
        if (go != null) {
            log.info("OpenCode Go: {} -> {}", this.models.keySet(), baseUrl);
        }
    }

    /** With a given OpenCode Go model (tests). */
    OpenCodeGoRouting(ChatModel go, Map<String, String> models) {
        this.go = go;
        this.models = go == null ? Map.of() : Map.copyOf(models);
    }

    static Map<String, String> parse(List<String> pairs) {
        Map<String, String> models = new HashMap<>();
        for (String pair : pairs == null ? List.<String>of() : pairs) {
            int eq = pair == null ? -1 : pair.indexOf('=');
            if (eq > 0 && eq < pair.length() - 1) {
                models.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return models;
    }

    /**
     * {@code openRouter} with the routed models sent to OpenCode Go; {@code openRouter} itself when off, and when it is
     * not the OpenRouter client (a test's scripted or mocked model: its calls must stay with it, never reach Go).
     */
    public ChatModel route(ChatModel openRouter) {
        return openRouter instanceof OpenAiChatModel ? wrap(openRouter) : openRouter;
    }

    ChatModel wrap(ChatModel openRouter) {
        if (go == null || models.isEmpty()) {
            return openRouter;
        }
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String target = prompt.getOptions() instanceof OpenAiChatOptions options && options.getModel() != null
                        ? models.get(options.getModel()) : null;
                if (target == null) {
                    return openRouter.call(prompt);
                }
                return go.call(new Prompt(prompt.getInstructions(), translate((OpenAiChatOptions) prompt.getOptions(), target)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return openRouter.getDefaultOptions();
            }
        };
    }

    /** The options of an OpenRouter call for OpenCode Go (see the class comment). */
    OpenAiChatOptions translate(OpenAiChatOptions options, String model) {
        OpenAiChatOptions.Builder builder = options.mutate().model(model);
        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> filed = options.getExtraBody() == null ? Map.of() : options.getExtraBody();
        filed.forEach((key, value) -> {
            if (!OPENROUTER_ONLY.contains(key)) {
                extra.put(key, value);
            }
        });
        if (filed.get("reasoning") instanceof Map<?, ?> reasoning) {
            if (Boolean.FALSE.equals(reasoning.get("enabled"))) {
                extra.put("thinking", Map.of("type", "disabled"));
            } else if (reasoning.get("effort") instanceof String effort) {
                builder.reasoningEffort(effort);
            }
        }
        builder.extraBody(extra);
        Map<String, String> headers = new LinkedHashMap<>();
        if (options.getCustomHeaders() != null) {
            headers.putAll(options.getCustomHeaders());
        }
        headers.put("x-opencode-session", session);
        builder.customHeaders(headers);
        return builder.build();
    }
}
