package com.neracalab.backend.macro.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.MacroDocumentRow;
import com.neracalab.backend.rag.RagRepository.MacroHit;
import com.neracalab.backend.screening.agent.UsageMeter;

/**
 * The country's macro documents in the RAG vector store (Ingestion > PDF Documents (RAG), macro documents) as the
 * research agent of one analysis may search them: point in time - only the documents published by the as-of date
 * (on or before it, Jakarta time) - and metered: the embedding of every query is recorded on the analysis' meter
 * (cost estimated from the query length: the embedding API reports none).
 */
public class MacroDocumentSearch implements MacroTools.DocumentSearch {

    static final String STAGE = "RETRIEVAL";
    /** Most documents listed to the research agent (and kept in the report); the search reads all of them. */
    static final int LISTED = 200;
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private final String country;
    private final Instant publishedBefore;
    private final EmbeddingClient embeddings;
    private final RagRepository rag;
    private final UsageMeter meter;
    private final MacroAnalysisProperties properties;
    private final List<MacroDocumentRow> documents;

    public MacroDocumentSearch(String country, LocalDate asOf, EmbeddingClient embeddings, RagRepository rag,
                               UsageMeter meter, MacroAnalysisProperties properties) {
        this.country = country;
        this.publishedBefore = publishedBefore(asOf);
        this.embeddings = embeddings;
        this.rag = rag;
        this.meter = meter;
        this.properties = properties;
        this.documents = rag.macroDocumentsPublishedBefore(country, publishedBefore, LISTED);
    }

    /** The first instant after the as-of date in Jakarta: documents published before it can be read. */
    static Instant publishedBefore(LocalDate asOf) {
        return asOf.plusDays(1).atStartOfDay(JAKARTA).toInstant();
    }

    @Override
    public List<MacroDocumentRow> documents() {
        return documents;
    }

    @Override
    public List<MacroHit> search(String query) {
        return rag.searchMacro(country, publishedBefore, embed(query), properties.documentResults());
    }

    /** The query's embedding; the call is metered. */
    private float[] embed(String query) {
        int tokens = Math.max(1, (query.length() + 3) / 4);
        double cost = tokens * properties.embeddingPricePerMillion() / 1_000_000d;
        long start = System.nanoTime();
        try {
            float[] vector = embeddings.embed(query);
            if (vector == null) {
                throw new EmbeddingClient.EmbeddingException("The embedding model returned no vector");
            }
            meter.record(new UsageMeter.Usage(STAGE, "RESEARCH", country, embeddings.model(), tokens, 0, 0, 0, cost, true,
                    (System.nanoTime() - start) / 1_000_000, null));
            return vector;
        } catch (EmbeddingClient.EmbeddingException e) {
            String message = e.getMessage() == null ? "embedding failed" : e.getMessage();
            meter.record(new UsageMeter.Usage(STAGE, "RESEARCH", country, embeddings.model(), 0, 0, 0, 0, 0, true,
                    (System.nanoTime() - start) / 1_000_000, message.length() > 500 ? message.substring(0, 500) : message));
            throw e;
        }
    }
}
