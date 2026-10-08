package com.neracalab.backend.analysis;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Hit;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.screening.agent.UsageMeter;

/**
 * Tools of the analysis research agent for one company (Tool Calling Pattern), all reading the database:
 * semantic search in the company's stored PDF documents and news (pgvector, {@link RagRepository#search}) and the
 * full statements of one stored period ({@link FactSheet#statement}). Searches are limited to the company itself,
 * so the model cannot read other companies' documents. Every retrieved excerpt gets a stable reference (F1, N1,
 * ...) that the brief cites; the embedding of each query is metered.
 */
public class ContextTools {

    static final String STAGE = "RETRIEVAL";
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    /** An excerpt returned to the model. */
    public record Excerpt(String ref, String title, String where, String text) {
    }

    /** An excerpt the agent retrieved (for the report). */
    public record Retrieved(String ref, String source, String title, String where, String url, double distance,
                            String query) {
    }

    private final long companyId;
    private final String ticker;
    private final FactSheet facts;
    private final EmbeddingClient embeddings;
    private final RagRepository rag;
    private final UsageMeter meter;
    private final AnalysisProperties properties;
    private final boolean hasFilings;
    private final boolean hasNews;
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final Map<Long, String> refs = new HashMap<>();
    private final List<Retrieved> retrieved = new ArrayList<>();
    private final Set<String> queries = new HashSet<>();
    private int filingRefs;
    private int newsRefs;

    public ContextTools(long companyId, String ticker, FactSheet facts, EmbeddingClient embeddings, RagRepository rag,
                        UsageMeter meter, AnalysisProperties properties, boolean hasFilings, boolean hasNews) {
        this.companyId = companyId;
        this.ticker = ticker;
        this.facts = facts;
        this.embeddings = embeddings;
        this.rag = rag;
        this.meter = meter;
        this.properties = properties;
        this.hasFilings = hasFilings;
        this.hasNews = hasNews;
    }

    @Tool(description = """
            Semantic search in the company's stored PDF documents (financial statements with notes, annual reports). \
            Returns the closest excerpts with a ref (F1, F2, ...), document and pages. Use short, specific queries.""")
    public List<Excerpt> searchFilings(@ToolParam(description = "what to look for, e.g. 'segment revenue and customers'")
                                       String query) {
        return search(query, SourceType.PDF);
    }

    @Tool(description = """
            Semantic search in the company's stored news articles. Returns the closest excerpts with a ref (N1, N2, ...), \
            date, title and URL.""")
    public List<Excerpt> searchNews(@ToolParam(description = "what to look for, e.g. 'dividend' or 'new contract'")
                                    String query) {
        return search(query, SourceType.NEWS);
    }

    @Tool(description = """
            Every stored statement line and metric of one reporting period of the company, e.g. '2024 FY' or '2026 H1' \
            (amounts scaled as in the overview). Only for figures the overview does not show.""")
    public Map<String, Object> getStatement(@ToolParam(description = "period exactly as listed, e.g. '2024 FY'")
                                            String period) {
        if (lookups.incrementAndGet() > properties.maxStatementLookups()) {
            throw new IllegalStateException("Statement lookup limit reached (" + properties.maxStatementLookups() + ")");
        }
        return facts.statement(period).orElseThrow(() -> new IllegalArgumentException(
                "No stored period '" + period + "'; stored: " + String.join(", ", facts.periodNames())));
    }

    private List<Excerpt> search(String query, SourceType type) {
        String q = query == null ? "" : Texts.compact(query);
        if (q.isEmpty() || q.length() > 300) {
            throw new IllegalArgumentException("The query must have 1 to 300 characters");
        }
        if (type == SourceType.PDF ? !hasFilings : !hasNews) {
            throw new IllegalStateException("No " + (type == SourceType.PDF ? "PDF documents" : "news articles")
                    + " are stored for " + ticker);
        }
        synchronized (queries) {
            if (!queries.add(type + "|" + q.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("This query was searched already; use its results");
            }
        }
        if (searches.incrementAndGet() > properties.maxSearches()) {
            throw new IllegalStateException("Search limit reached (" + properties.maxSearches() + ")");
        }
        float[] vector = embed(q);
        List<Hit> hits = rag.search(companyId, type, vector, properties.searchResults());
        List<Excerpt> out = new ArrayList<>();
        for (Hit hit : hits) {
            String ref = ref(hit);
            String where = type == SourceType.PDF ? pages(hit) : date(hit);
            String title = type == SourceType.PDF && hit.fileName() != null ? hit.fileName() : hit.title();
            out.add(new Excerpt(ref, title, where, Texts.clip(Texts.compact(hit.content()), properties.excerptChars())));
            synchronized (retrieved) {
                if (retrieved.stream().noneMatch(r -> r.ref().equals(ref))) {
                    retrieved.add(new Retrieved(ref, type.name(), title, where, hit.sourceUrl(),
                            Math.round(hit.distance() * 1000) / 1000.0, q));
                }
            }
        }
        return out;
    }

    /** The query's embedding; the call is metered (cost estimated: the embedding API reports none). */
    private float[] embed(String query) {
        int tokens = Math.max(1, (query.length() + 3) / 4);
        double cost = tokens * properties.embeddingPricePerMillion() / 1_000_000d;
        long start = System.nanoTime();
        try {
            float[] vector = embeddings.embed(query);
            meter.record(new UsageMeter.Usage(STAGE, "RESEARCH", ticker, embeddings.model(), tokens, 0, 0, 0, cost, true,
                    (System.nanoTime() - start) / 1_000_000, null));
            return vector;
        } catch (EmbeddingClient.EmbeddingException e) {
            String message = e.getMessage() == null ? "embedding failed" : e.getMessage();
            meter.record(new UsageMeter.Usage(STAGE, "RESEARCH", ticker, embeddings.model(), 0, 0, 0, 0, 0, true,
                    (System.nanoTime() - start) / 1_000_000, message.length() > 500 ? message.substring(0, 500) : message));
            throw e;
        }
    }

    /** The same chunk keeps its ref across searches. */
    private synchronized String ref(Hit hit) {
        return refs.computeIfAbsent(hit.chunkId(), id -> hit.sourceType() == SourceType.PDF ? "F" + (++filingRefs)
                : "N" + (++newsRefs));
    }

    private static String pages(Hit hit) {
        if (hit.pageFrom() == null) {
            return null;
        }
        return hit.pageTo() == null || hit.pageTo().equals(hit.pageFrom()) ? "p. " + hit.pageFrom()
                : "pp. " + hit.pageFrom() + "-" + hit.pageTo();
    }

    private static String date(Hit hit) {
        return hit.publishedAt() == null ? null : hit.publishedAt().atZone(JAKARTA).toLocalDate().toString();
    }

    boolean hasFilings() {
        return hasFilings;
    }

    boolean hasNews() {
        return hasNews;
    }

    int searches() {
        return searches.get();
    }

    int lookups() {
        return lookups.get();
    }

    /** Every excerpt retrieved, in order of retrieval. */
    public List<Retrieved> retrieved() {
        synchronized (retrieved) {
            return List.copyOf(retrieved);
        }
    }

    /** The refs retrieved (F1, N2, ...). */
    public Set<String> refs() {
        Set<String> out = new LinkedHashSet<>();
        retrieved().forEach(r -> out.add(r.ref()));
        return out;
    }
}
