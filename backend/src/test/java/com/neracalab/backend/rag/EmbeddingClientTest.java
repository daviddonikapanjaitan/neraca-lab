package com.neracalab.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.neracalab.backend.rag.EmbeddingClient.EmbeddingException;

import tools.jackson.databind.json.JsonMapper;

class EmbeddingClientTest {

    static RagProperties properties() {
        return new RagProperties("openai/text-embedding-3-small", 1536, 64, 1500, 200, 10, 60, 30000);
    }

    private final EmbeddingClient client = new EmbeddingClient(properties(), JsonMapper.builder().build(),
            "https://openrouter.ai/api/v1/", "");

    @Test
    void ordersVectorsByIndex() {
        List<float[]> vectors = client.parse("""
                {"data":[{"index":1,"embedding":[0.5,-1.0,0.25]},{"index":0,"embedding":[1,2,3]}],"model":"x"}""", 2, 3);
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1f, 2f, 3f);
        assertThat(vectors.get(1)).containsExactly(0.5f, -1f, 0.25f);
    }

    @Test
    void rejectsAWrongCountOrDimensions() {
        assertThatThrownBy(() -> client.parse("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}", 2, 3))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("1 vectors for 2 texts");
        assertThatThrownBy(() -> client.parse("{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}", 1, 3))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("2 dimensions, expected 3");
        assertThatThrownBy(() -> client.parse("{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[2]}]}", 2, 1))
                .isInstanceOf(EmbeddingException.class);
        assertThatThrownBy(() -> client.parse("{\"error\":{\"message\":\"model not found\"}}", 1, 3))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("model not found");
        assertThatThrownBy(() -> client.parse("<html>", 1, 3)).isInstanceOf(EmbeddingException.class);
    }

    @Test
    void needsAnApiKey() {
        assertThatThrownBy(() -> client.embed("laba bersih"))
                .isInstanceOf(EmbeddingException.class).hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void pgvectorTextForm() {
        assertThat(RagRepository.vector(new float[] {0.5f, -1f, 0f})).isEqualTo("[0.5,-1.0,0.0]");
        assertThatThrownBy(() -> RagRepository.vector(new float[] {Float.NaN})).isInstanceOf(IllegalArgumentException.class);
    }
}
