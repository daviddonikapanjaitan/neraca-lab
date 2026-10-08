package com.neracalab.backend.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The RAG vector store ({@code neracalab.rag}).
 *
 * @param embeddingModel      embedding model of the OpenAI-compatible API (OpenRouter): openai/text-embedding-3-small
 * @param embeddingDimensions dimensions of its vectors; must match {@code rag_chunk.embedding vector(1536)}
 * @param embeddingBatch      texts per embedding request
 * @param chunkChars          target length of a text chunk (characters)
 * @param chunkOverlap        characters a chunk repeats from the end of the previous one
 * @param newsMaxPages        tag pages per news site walked back to reach the start of the date range
 * @param newsMaxArticles     articles read and stored per news ingestion
 * @param articleChars        longest article text kept
 */
@ConfigurationProperties("neracalab.rag")
public record RagProperties(
        @DefaultValue("openai/text-embedding-3-small") String embeddingModel,
        @DefaultValue("1536") int embeddingDimensions,
        @DefaultValue("64") int embeddingBatch,
        @DefaultValue("1500") int chunkChars,
        @DefaultValue("200") int chunkOverlap,
        @DefaultValue("10") int newsMaxPages,
        @DefaultValue("60") int newsMaxArticles,
        @DefaultValue("30000") int articleChars) {

    /** The dimensions of {@code rag_chunk.embedding}. */
    public static final int STORE_DIMENSIONS = 1536;
}
