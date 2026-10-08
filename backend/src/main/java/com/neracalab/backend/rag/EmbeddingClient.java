package com.neracalab.backend.rag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Embeddings from the OpenAI-compatible API the chat model uses ({@code POST <base-url>/embeddings}; OpenRouter:
 * openai/text-embedding-3-small, 1536 dimensions, about USD 0.02 per million tokens). Texts are sent in batches; a
 * timeout, network error, HTTP 429 or 5xx is retried twice; every vector is checked for its dimensions.
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final int RETRIES = 2;

    public static class EmbeddingException extends RuntimeException {

        public EmbeddingException(String message) {
            super(message);
        }
    }

    private final RagProperties properties;
    private final JsonMapper json;
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public EmbeddingClient(RagProperties properties, JsonMapper json,
                           @Value("${spring.ai.openai.base-url}") String baseUrl,
                           @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.properties = properties;
        this.json = json;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    public String model() {
        return properties.embeddingModel();
    }

    /** One embedding per text, in order. */
    public List<float[]> embed(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingException("No API key for embeddings (OPENAI_API_KEY)");
        }
        if (properties.embeddingDimensions() != RagProperties.STORE_DIMENSIONS) {
            throw new EmbeddingException("neracalab.rag.embedding-dimensions is " + properties.embeddingDimensions()
                    + " but the vector store holds " + RagProperties.STORE_DIMENSIONS + " dimensions");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        int batch = Math.max(1, properties.embeddingBatch());
        for (int from = 0; from < texts.size(); from += batch) {
            out.addAll(batch(texts.subList(from, Math.min(texts.size(), from + batch))));
        }
        return out;
    }

    public float[] embed(String text) {
        return embed(List.of(text)).getFirst();
    }

    private List<float[]> batch(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.embeddingModel());
        body.put("input", texts);
        body.put("encoding_format", "float");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        for (int attempt = 0; ; attempt++) {
            String failure;
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    return parse(response.body(), texts.size(), properties.embeddingDimensions());
                }
                failure = "HTTP " + status + ": " + abbreviate(response.body());
                if (status != 408 && status != 429 && status < 500) {
                    throw new EmbeddingException("The embedding API answered " + failure);
                }
            } catch (IOException e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException("Interrupted while embedding");
            }
            if (attempt >= RETRIES) {
                throw new EmbeddingException("The embedding API failed " + (RETRIES + 1) + " times: " + failure);
            }
            log.warn("embedding request failed ({}), retry {} of {}", failure, attempt + 1, RETRIES);
            try {
                Thread.sleep(2000L << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException("Interrupted while embedding");
            }
        }
    }

    /** The vectors of an embeddings response, ordered by their index; checks count and dimensions. */
    List<float[]> parse(String body, int expected, int dimensions) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new EmbeddingException("The embedding API answered with a body that is not JSON");
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            String error = root.path("error").path("message").asString(null);
            throw new EmbeddingException("The embedding API returned " + (data.isArray() ? data.size() : 0) + " vectors for "
                    + expected + " texts" + (error == null ? "" : ": " + error));
        }
        float[][] vectors = new float[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode values = item.path("embedding");
            if (index < 0 || index >= expected || vectors[index] != null || !values.isArray() || values.size() != dimensions) {
                throw new EmbeddingException("Unexpected embedding (index " + index + ", " + values.size()
                        + " dimensions, expected " + dimensions + ")");
            }
            float[] v = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                v[i] = (float) values.get(i).asDouble();
            }
            vectors[index] = v;
        }
        return List.of(vectors);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }
}
