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

import com.neracalab.backend.rag.CompanyDocumentType;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Hit;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.screening.agent.UsageMeter;

/**
 * Tools of the analysis research agent for one company (Tool Calling Pattern), all reading the database:
 * semantic search in the company's stored financial statement PDFs, its monthly registration reports of securities
 * holders, its ownership documents (KSEI's daily lists of the shareholders of more than 5%, its monthly lists of the
 * shareholders of more than 1%) and its news (pgvector, {@link RagRepository#search}) and the full statements of one
 * stored period ({@link FactSheet#statement}). Searches are limited to the company itself, so the model cannot read
 * other companies' documents. Every retrieved excerpt gets a stable reference (F1 = filing, R1 = registration report,
 * O1 = 5% ownership list, M1 = monthly 1% ownership list, N1 = news) that the brief cites; the embedding of each
 * query is metered.
 */
public class ContextTools {

    static final String STAGE = "RETRIEVAL";
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    /** An excerpt returned to the model. */
    public record Excerpt(String ref, String title, String where, String text) {
    }

    /**
     * An excerpt the agent retrieved (for the report).
     *
     * @param source PDF (a financial statement), REGISTRATION (a monthly registration report), OWNERSHIP (the
     *               KSEI 5% ownership document), OWNERSHIP_1_PERCENT (the KSEI 1% ownership document) or NEWS
     */
    public record Retrieved(String ref, String source, String title, String where, String url, double distance,
                            String query) {
    }

    /** What a search reads: its source, its document type (or source key), the letter of its refs. */
    private enum Kind {
        FILING(SourceType.PDF, CompanyDocumentType.FINANCIAL_STATEMENT, null, "F", "PDF", "financial statement PDFs"),
        REGISTRATION(SourceType.PDF, CompanyDocumentType.REGISTRATION, null, "R", "REGISTRATION",
                "monthly registration reports"),
        OWNERSHIP(SourceType.XLSX, null, "OWNERSHIP_5_PERCENT", "O", "OWNERSHIP",
                "KSEI lists of the shareholders of more than 5%"),
        ONE_PERCENT(SourceType.XLSX, null, "OWNERSHIP_1_PERCENT", "M", "OWNERSHIP_1_PERCENT",
                "KSEI monthly lists of the shareholders of more than 1%"),
        NEWS(SourceType.NEWS, null, null, "N", "NEWS", "news articles");

        final SourceType source;
        final CompanyDocumentType documentType;
        final String sourceKey;
        final String prefix;
        final String label;
        final String what;

        Kind(SourceType source, CompanyDocumentType documentType, String sourceKey, String prefix, String label,
             String what) {
            this.source = source;
            this.documentType = documentType;
            this.sourceKey = sourceKey;
            this.prefix = prefix;
            this.label = label;
            this.what = what;
        }
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
    private final boolean hasRegistrations;
    private final boolean hasOwnership;
    private final boolean hasOnePercent;
    private final AtomicInteger searches = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final Map<Long, String> refs = new HashMap<>();
    private final Map<Kind, Integer> counters = new HashMap<>();
    private final List<Retrieved> retrieved = new ArrayList<>();
    private final Set<String> queries = new HashSet<>();

    public ContextTools(long companyId, String ticker, FactSheet facts, EmbeddingClient embeddings, RagRepository rag,
                        UsageMeter meter, AnalysisProperties properties, boolean hasFilings, boolean hasNews) {
        this(companyId, ticker, facts, embeddings, rag, meter, properties, hasFilings, hasNews, false);
    }

    public ContextTools(long companyId, String ticker, FactSheet facts, EmbeddingClient embeddings, RagRepository rag,
                        UsageMeter meter, AnalysisProperties properties, boolean hasFilings, boolean hasNews,
                        boolean hasRegistrations) {
        this(companyId, ticker, facts, embeddings, rag, meter, properties, hasFilings, hasNews, hasRegistrations, false);
    }

    public ContextTools(long companyId, String ticker, FactSheet facts, EmbeddingClient embeddings, RagRepository rag,
                        UsageMeter meter, AnalysisProperties properties, boolean hasFilings, boolean hasNews,
                        boolean hasRegistrations, boolean hasOwnership) {
        this(companyId, ticker, facts, embeddings, rag, meter, properties, hasFilings, hasNews, hasRegistrations,
                hasOwnership, false);
    }

    /**
     * @param hasFilings       financial statement PDFs are stored
     * @param hasNews          news articles are stored
     * @param hasRegistrations monthly registration reports (REGISTRATION PDFs) are stored
     * @param hasOwnership     the company's ownership document (KSEI's daily lists of the shareholders of more than 5%)
     *                         is stored
     * @param hasOnePercent    the company's 1% ownership document (KSEI's monthly lists of the shareholders of more
     *                         than 1%) is stored
     */
    public ContextTools(long companyId, String ticker, FactSheet facts, EmbeddingClient embeddings, RagRepository rag,
                        UsageMeter meter, AnalysisProperties properties, boolean hasFilings, boolean hasNews,
                        boolean hasRegistrations, boolean hasOwnership, boolean hasOnePercent) {
        this.companyId = companyId;
        this.ticker = ticker;
        this.facts = facts;
        this.embeddings = embeddings;
        this.rag = rag;
        this.meter = meter;
        this.properties = properties;
        this.hasFilings = hasFilings;
        this.hasNews = hasNews;
        this.hasRegistrations = hasRegistrations;
        this.hasOwnership = hasOwnership;
        this.hasOnePercent = hasOnePercent;
    }

    @Tool(description = """
            Semantic search in the company's stored PDF documents (financial statements with notes, annual reports). \
            Returns the closest excerpts with a ref (F1, F2, ...), document and pages. Use short, specific queries.""")
    public List<Excerpt> searchFilings(@ToolParam(description = "what to look for, e.g. 'segment revenue and customers'")
                                       String query) {
        return search(query, Kind.FILING);
    }

    @Tool(description = """
            Semantic search in the company's stored monthly registration reports of securities holders (Laporan \
            Bulanan Registrasi Pemegang Efek): shareholders of 5% or more and the controlling shareholder, the shares \
            of the directors and commissioners, free float, investor types, beneficial owner. Returns excerpts with a \
            ref (R1, R2, ...), report and pages.""")
    public List<Excerpt> searchRegistrations(@ToolParam(description = "what to look for, e.g. 'pemegang saham 5% pengendali' or 'free float'")
                                             String query) {
        return search(query, Kind.REGISTRATION);
    }

    @Tool(description = """
            Semantic search in the company's ownership document built from KSEI's daily lists of the shareholders of \
            more than 5% (by investor SID): each holder's shares, percentage, local or foreign status, the brokers and \
            custodians of its accounts, every day's buys and sells, investors crossing 5% or falling below it. Returns \
            excerpts with a ref (O1, O2, ...) and the date of the latest list.""")
    public List<Excerpt> searchOwnership(@ToolParam(description = "what to look for, e.g. 'largest shareholder foreign' or 'sold shares fell below 5%'")
                                         String query) {
        return search(query, Kind.OWNERSHIP);
    }

    @Tool(description = """
            Semantic search in the company's ownership document built from KSEI's monthly lists of the shareholders \
            of more than 1%: each holder's shares and percentage at the end of every stored month, its type \
            (individual, corporate, mutual fund, pension fund, bank, insurance, government ...), local or foreign, \
            shares held as scrip, the monthly buys and sells, holders new above 1% or no longer above it, names that \
            changed. Returns excerpts with a ref (M1, M2, ...) and the date of the latest list.""")
    public List<Excerpt> searchShareholdersAbove1Percent(@ToolParam(description = "what to look for, e.g. 'mutual funds and pension funds holding' or 'foreign investors bought'")
                                                         String query) {
        return search(query, Kind.ONE_PERCENT);
    }

    @Tool(description = """
            Semantic search in the company's stored news articles. Returns the closest excerpts with a ref (N1, N2, ...), \
            date, title and URL.""")
    public List<Excerpt> searchNews(@ToolParam(description = "what to look for, e.g. 'dividend' or 'new contract'")
                                    String query) {
        return search(query, Kind.NEWS);
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

    private List<Excerpt> search(String query, Kind kind) {
        String q = query == null ? "" : Texts.compact(query);
        if (q.isEmpty() || q.length() > 300) {
            throw new IllegalArgumentException("The query must have 1 to 300 characters");
        }
        boolean stored = switch (kind) {
            case FILING -> hasFilings;
            case REGISTRATION -> hasRegistrations;
            case OWNERSHIP -> hasOwnership;
            case ONE_PERCENT -> hasOnePercent;
            case NEWS -> hasNews;
        };
        if (!stored) {
            throw new IllegalStateException("No " + kind.what + " are stored for " + ticker);
        }
        synchronized (queries) {
            if (!queries.add(kind + "|" + q.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("This query was searched already; use its results");
            }
        }
        if (searches.incrementAndGet() > properties.maxSearches()) {
            throw new IllegalStateException("Search limit reached (" + properties.maxSearches() + ")");
        }
        float[] vector = embed(q);
        List<Hit> hits = kind.sourceKey == null
                ? rag.search(companyId, kind.source, kind.documentType, vector, properties.searchResults())
                : rag.search(companyId, kind.source, kind.documentType, kind.sourceKey, vector,
                        properties.searchResults());
        List<Excerpt> out = new ArrayList<>();
        for (Hit hit : hits) {
            String ref = ref(hit, kind);
            String where = kind.source == SourceType.PDF ? pages(hit) : date(hit);
            String title = kind.source == SourceType.PDF && hit.fileName() != null ? hit.fileName() : hit.title();
            out.add(new Excerpt(ref, title, where, Texts.clip(Texts.compact(hit.content()), properties.excerptChars())));
            synchronized (retrieved) {
                if (retrieved.stream().noneMatch(r -> r.ref().equals(ref))) {
                    retrieved.add(new Retrieved(ref, kind.label, title, where, hit.sourceUrl(),
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

    /**
     * The same chunk keeps its ref across searches: F1, F2 ... (filings), R1 ... (registration reports), O1 ...
     * (5% ownership), M1 ... (1% ownership), N1 ... (news).
     */
    private synchronized String ref(Hit hit, Kind kind) {
        return refs.computeIfAbsent(hit.chunkId(), id -> kind.prefix + counters.merge(kind, 1, Integer::sum));
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

    boolean hasRegistrations() {
        return hasRegistrations;
    }

    boolean hasOwnership() {
        return hasOwnership;
    }

    boolean hasOnePercent() {
        return hasOnePercent;
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

    /** The refs retrieved (F1, R1, N2, ...). */
    public Set<String> refs() {
        Set<String> out = new LinkedHashSet<>();
        retrieved().forEach(r -> out.add(r.ref()));
        return out;
    }
}
