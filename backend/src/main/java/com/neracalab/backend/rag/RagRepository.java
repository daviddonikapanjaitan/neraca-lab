package com.neracalab.backend.rag;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The RAG vector store: {@code rag_document} (one per PDF or news article of a company, or per macro PDF of a
 * country) and {@code rag_chunk} (text chunks with their embeddings, {@code vector(1536)}). Similarity search orders
 * by cosine distance ({@code <=>}, HNSW index). The company queries ({@link #documents}, {@link #search}) never return
 * macro documents and the macro queries never return company documents.
 */
@Repository
public class RagRepository {

    /** PDF: an uploaded PDF; NEWS: a news article; XLSX: a document built from uploaded Excel lists (ownership). */
    public enum SourceType { PDF, NEWS, XLSX }

    public record Company(long companyId, String exchange, String ticker, String companyName) {
    }

    /**
     * A document to store; {@code fileId} / {@code fileName} and {@code documentType} for a PDF (null for news),
     * {@code sourceUrl} / {@code publishedAt} for news; an Excel document has the latest workbook as its file, no
     * type and the date of its latest list as {@code publishedAt}.
     */
    public record NewDocument(long companyId, SourceType sourceType, CompanyDocumentType documentType, String sourceKey,
                              String title, String sourceName, String sourceUrl, Long fileId, String fileName,
                              Instant publishedAt, Integer pages, int characters, String embeddingModel, UUID jobId) {
    }

    /** A stored document of a company; {@code documentType} is null for news. */
    public record DocumentRow(long documentId, String exchange, String ticker, String companyName, SourceType sourceType,
                              CompanyDocumentType documentType, String title, String sourceName, String sourceUrl,
                              String fileName, Instant publishedAt, Integer pages, int characters, int chunks,
                              String embeddingModel, Instant updatedAt) {
    }

    /**
     * One page of stored documents.
     *
     * @param total     documents matching the filters (every page together)
     * @param documents most recently stored first, at most {@code limit} from {@code offset}
     */
    public record DocumentPage(long total, int limit, int offset, List<DocumentRow> documents) {
    }

    /**
     * Documents and chunks stored: PDFs, news and Excel documents of the companies, macro PDFs of the countries.
     *
     * @param xlsxDocuments the companies' documents built from Excel lists (their holders of 5% or more)
     * @param chunks        every chunk (companies and countries)
     */
    public record Counts(long pdfDocuments, long newsDocuments, long macroDocuments, long chunks, long xlsxDocuments) {
    }

    public record Hit(long chunkId, long documentId, String ticker, SourceType sourceType, String title, String sourceUrl,
                      String fileName, Instant publishedAt, Integer pageFrom, Integer pageTo, String content,
                      double distance) {
    }

    /** A macro PDF of a country to store ({@code publishedAt}: its publication date, required). */
    public record NewMacroDocument(String country, String sourceKey, String title, String issuer,
                                   MacroDocumentType documentType, Instant publishedAt, long fileId, String fileName,
                                   Integer pages, int characters, String embeddingModel, UUID jobId) {
    }

    public record MacroDocumentRow(long documentId, String country, String title, String issuer,
                                   MacroDocumentType documentType, Instant publishedAt, String fileName, Integer pages,
                                   int characters, int chunks, String embeddingModel, Instant updatedAt) {
    }

    /**
     * One page of stored macro documents.
     *
     * @param total     documents matching the filters (every page together)
     * @param documents latest publication first, at most {@code limit} from {@code offset}
     */
    public record MacroDocumentPage(long total, int limit, int offset, List<MacroDocumentRow> documents) {
    }

    /** A chunk of a macro document found by {@link #searchMacro}. */
    public record MacroHit(long chunkId, long documentId, String country, String title, String issuer,
                           MacroDocumentType documentType, Instant publishedAt, String fileName, Integer pageFrom,
                           Integer pageTo, String content, double distance) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public RagRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public Optional<Company> company(String exchange, String ticker) {
        return jdbc.sql("SELECT company_id, exchange, ticker, company_name FROM company WHERE exchange = :e AND ticker = :t")
                .param("e", exchange).param("t", ticker)
                .query((rs, i) -> new Company(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional();
    }

    public boolean hasDocument(long companyId, SourceType type, String sourceKey) {
        return jdbc.sql("""
                        SELECT count(*) FROM rag_document
                        WHERE company_id = :c AND source_type = :t AND source_key = :k AND chunks > 0""")
                .param("c", companyId).param("t", type.name()).param("k", sourceKey)
                .query(Long.class).single() > 0;
    }

    /**
     * Stores a document and replaces its chunks, in one transaction: a document is never left with half of its
     * chunks, and storing the same source again (same file, same URL) replaces it.
     *
     * @return the document id
     */
    @Transactional
    public long store(NewDocument doc, List<TextChunker.Chunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(chunks.size() + " chunks but " + embeddings.size() + " embeddings");
        }
        long id = jdbc.sql("""
                        INSERT INTO rag_document (company_id, source_type, document_type, source_key, title, source_name,
                                                  source_url, file_id, file_name, published_at, pages, characters, chunks,
                                                  embedding_model, job_id)
                        VALUES (:company, :type, :documentType, :key, :title, :sourceName, :url, :fileId, :fileName,
                                :published, :pages, :characters, :chunks, :model, :job)
                        ON CONFLICT ON CONSTRAINT uq_rag_document_source DO UPDATE SET
                            document_type = EXCLUDED.document_type,
                            title = EXCLUDED.title, source_name = EXCLUDED.source_name, source_url = EXCLUDED.source_url,
                            file_id = EXCLUDED.file_id, file_name = EXCLUDED.file_name,
                            published_at = EXCLUDED.published_at, pages = EXCLUDED.pages,
                            characters = EXCLUDED.characters, chunks = EXCLUDED.chunks,
                            embedding_model = EXCLUDED.embedding_model, job_id = EXCLUDED.job_id, updated_at = now()
                        RETURNING document_id""")
                .param("company", doc.companyId())
                .param("type", doc.sourceType().name())
                .param("documentType", doc.documentType() == null ? null : doc.documentType().name(), Types.VARCHAR)
                .param("key", truncate(doc.sourceKey(), 1000))
                .param("title", truncate(doc.title(), 1000))
                .param("sourceName", truncate(doc.sourceName(), 100), Types.VARCHAR)
                .param("url", truncate(doc.sourceUrl(), 2000), Types.VARCHAR)
                .param("fileId", doc.fileId(), Types.BIGINT)
                .param("fileName", truncate(doc.fileName(), 255), Types.VARCHAR)
                .param("published", doc.publishedAt() == null ? null : doc.publishedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pages", doc.pages(), Types.INTEGER)
                .param("characters", doc.characters())
                .param("chunks", chunks.size())
                .param("model", doc.embeddingModel())
                .param("job", doc.jobId())
                .query(Long.class).single();
        replaceChunks(id, doc.companyId(), null, chunks, embeddings);
        return id;
    }

    /**
     * Stores a macro document of a country and replaces its chunks, in one transaction (as {@link #store}): the same
     * file again for the same country replaces it, with the title, issuer, type and date given now.
     *
     * @return the document id
     */
    @Transactional
    public long storeMacro(NewMacroDocument doc, List<TextChunker.Chunk> chunks, List<float[]> embeddings) {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(chunks.size() + " chunks but " + embeddings.size() + " embeddings");
        }
        long id = jdbc.sql("""
                        INSERT INTO rag_document (country, source_type, source_key, title, issuer, document_type,
                                                  source_name, file_id, file_name, published_at, pages, characters,
                                                  chunks, embedding_model, job_id)
                        VALUES (:country, 'PDF', :key, :title, :issuer, :type, 'Macro PDF upload', :fileId, :fileName,
                                :published, :pages, :characters, :chunks, :model, :job)
                        ON CONFLICT (country, source_type, source_key) WHERE country IS NOT NULL DO UPDATE SET
                            title = EXCLUDED.title, issuer = EXCLUDED.issuer, document_type = EXCLUDED.document_type,
                            file_id = EXCLUDED.file_id, file_name = EXCLUDED.file_name,
                            published_at = EXCLUDED.published_at, pages = EXCLUDED.pages,
                            characters = EXCLUDED.characters, chunks = EXCLUDED.chunks,
                            embedding_model = EXCLUDED.embedding_model, job_id = EXCLUDED.job_id, updated_at = now()
                        RETURNING document_id""")
                .param("country", doc.country())
                .param("key", truncate(doc.sourceKey(), 1000))
                .param("title", truncate(doc.title(), 1000))
                .param("issuer", truncate(doc.issuer(), 100), Types.VARCHAR)
                .param("type", doc.documentType().name())
                .param("fileId", doc.fileId())
                .param("fileName", truncate(doc.fileName(), 255), Types.VARCHAR)
                .param("published", doc.publishedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pages", doc.pages(), Types.INTEGER)
                .param("characters", doc.characters())
                .param("chunks", chunks.size())
                .param("model", doc.embeddingModel())
                .param("job", doc.jobId())
                .query(Long.class).single();
        replaceChunks(id, null, doc.country(), chunks, embeddings);
        return id;
    }

    /** The chunks of a document, replaced: of a company ({@code companyId}) or a country ({@code country}). */
    private void replaceChunks(long documentId, Long companyId, String country, List<TextChunker.Chunk> chunks,
                               List<float[]> embeddings) {
        jdbc.sql("DELETE FROM rag_chunk WHERE document_id = :d").param("d", documentId).update();
        List<Object[]> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.Chunk c = chunks.get(i);
            rows.add(new Object[] {documentId, companyId, country, c.index(), c.pageFrom(), c.pageTo(), c.text(),
                    vector(embeddings.get(i))});
        }
        template.batchUpdate("""
                INSERT INTO rag_chunk (document_id, company_id, country, chunk_index, page_from, page_to, content, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))""", rows,
                new int[] {Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER,
                        Types.VARCHAR, Types.VARCHAR});
    }

    /** Filters of {@link #documents}: rows match those given ({@code null} = any). */
    private static final String DOCUMENT_FILTER = """
            FROM rag_document d JOIN company c ON c.company_id = d.company_id
            WHERE (CAST(:exchange AS varchar) IS NULL OR c.exchange = :exchange)
              AND (CAST(:ticker AS varchar) IS NULL OR c.ticker = :ticker)
              AND (CAST(:prefix AS varchar) IS NULL OR c.ticker LIKE :prefix || '%')
              AND (CAST(:type AS varchar) IS NULL OR d.source_type = :type)
              AND (CAST(:documentType AS varchar) IS NULL OR d.document_type = :documentType)
            """;

    /**
     * One page of stored documents, most recently stored first: {@code limit} documents after skipping
     * {@code offset}, of the exchange, the ticker, the tickers starting with {@code tickerPrefix} (a normalized
     * ticker or its beginning: letters, digits, '.' and '-', no LIKE wildcards), the source type and the document
     * type given ({@code null} = any).
     */
    @Transactional(readOnly = true)
    public DocumentPage documents(String exchange, String ticker, String tickerPrefix, SourceType type,
                                  CompanyDocumentType documentType, int limit, int offset) {
        long total = documentQuery("SELECT count(*) " + DOCUMENT_FILTER, exchange, ticker, tickerPrefix, type, documentType)
                .query(Long.class).single();
        List<DocumentRow> rows = documentQuery("""
                        SELECT d.document_id, c.exchange, c.ticker, c.company_name, d.source_type, d.document_type,
                               d.title, d.source_name, d.source_url, d.file_name, d.published_at, d.pages, d.characters,
                               d.chunks, d.embedding_model, d.updated_at
                        """ + DOCUMENT_FILTER + """
                        ORDER BY d.updated_at DESC, d.document_id DESC
                        LIMIT :limit OFFSET :offset""", exchange, ticker, tickerPrefix, type, documentType)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new DocumentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        SourceType.valueOf(rs.getString(5)),
                        rs.getString(6) == null ? null : CompanyDocumentType.valueOf(rs.getString(6)), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), instant(rs.getTimestamp(11)),
                        (Integer) rs.getObject(12), rs.getInt(13), rs.getInt(14), rs.getString(15),
                        instant(rs.getTimestamp(16))))
                .list();
        return new DocumentPage(total, limit, offset, rows);
    }

    private JdbcClient.StatementSpec documentQuery(String sql, String exchange, String ticker, String tickerPrefix,
                                                   SourceType type, CompanyDocumentType documentType) {
        return jdbc.sql(sql)
                .param("exchange", exchange, Types.VARCHAR)
                .param("ticker", ticker, Types.VARCHAR)
                .param("prefix", tickerPrefix, Types.VARCHAR)
                .param("type", type == null ? null : type.name(), Types.VARCHAR)
                .param("documentType", documentType == null ? null : documentType.name(), Types.VARCHAR);
    }

    /**
     * The chunks closest to {@code query} (cosine distance), of one company when given, of one source type when
     * given. The HNSW scan continues until enough rows pass the filters (pgvector iterative scan).
     */
    @Transactional
    public List<Hit> search(Long companyId, SourceType type, float[] query, int limit) {
        return search(companyId, type, null, query, limit);
    }

    /**
     * As {@link #search(Long, SourceType, float[], int)}, of the company PDFs of one document type when given (the
     * financial statements, or the monthly registration reports).
     */
    @Transactional
    public List<Hit> search(Long companyId, SourceType type, CompanyDocumentType documentType, float[] query, int limit) {
        jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
        List<Hit> hits = new ArrayList<>(jdbc.sql("""
                        SELECT k.chunk_id, k.document_id, c.ticker, d.source_type, d.title, d.source_url, d.file_name,
                               d.published_at, k.page_from, k.page_to, k.content,
                               k.embedding <=> CAST(:query AS vector) AS distance
                        FROM rag_chunk k
                        JOIN rag_document d ON d.document_id = k.document_id
                        JOIN company c ON c.company_id = k.company_id
                        WHERE (CAST(:company AS bigint) IS NULL OR k.company_id = :company)
                          AND (CAST(:type AS varchar) IS NULL OR d.source_type = :type)
                          AND (CAST(:documentType AS varchar) IS NULL OR d.document_type = :documentType)
                        ORDER BY k.embedding <=> CAST(:query AS vector)
                        LIMIT :limit""")
                .param("query", vector(query))
                .param("company", companyId, Types.BIGINT)
                .param("type", type == null ? null : type.name(), Types.VARCHAR)
                .param("documentType", documentType == null ? null : documentType.name(), Types.VARCHAR)
                .param("limit", limit)
                .query((rs, i) -> new Hit(rs.getLong(1), rs.getLong(2), rs.getString(3), SourceType.valueOf(rs.getString(4)),
                        rs.getString(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8)),
                        (Integer) rs.getObject(9), (Integer) rs.getObject(10), rs.getString(11), rs.getDouble(12)))
                .list());
        hits.sort(Comparator.comparingDouble(Hit::distance));     // relaxed order: re-sort exactly
        return hits;
    }

    public Counts counts() {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM rag_document WHERE source_type = 'PDF' AND company_id IS NOT NULL),
                               (SELECT count(*) FROM rag_document WHERE source_type = 'NEWS'),
                               (SELECT count(*) FROM rag_document WHERE country IS NOT NULL),
                               (SELECT count(*) FROM rag_chunk),
                               (SELECT count(*) FROM rag_document WHERE source_type = 'XLSX')""")
                .query((rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)))
                .single();
    }

    // ------------------------------------------------------------------ macro documents of a country

    /**
     * One page of the stored macro documents, latest publication first: of the country and the type given
     * ({@code null} = any).
     */
    @Transactional(readOnly = true)
    public MacroDocumentPage macroDocuments(String country, MacroDocumentType type, int limit, int offset) {
        String filter = """
                FROM rag_document d
                WHERE d.country IS NOT NULL
                  AND (CAST(:country AS varchar) IS NULL OR d.country = :country)
                  AND (CAST(:type AS varchar) IS NULL OR d.document_type = :type)
                """;
        long total = jdbc.sql("SELECT count(*) " + filter)
                .param("country", country, Types.VARCHAR)
                .param("type", type == null ? null : type.name(), Types.VARCHAR)
                .query(Long.class).single();
        List<MacroDocumentRow> rows = jdbc.sql("""
                        SELECT d.document_id, d.country, d.title, d.issuer, d.document_type, d.published_at, d.file_name,
                               d.pages, d.characters, d.chunks, d.embedding_model, d.updated_at
                        """ + filter + """
                        ORDER BY d.published_at DESC, d.document_id DESC
                        LIMIT :limit OFFSET :offset""")
                .param("country", country, Types.VARCHAR)
                .param("type", type == null ? null : type.name(), Types.VARCHAR)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new MacroDocumentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        MacroDocumentType.valueOf(rs.getString(5)), instant(rs.getTimestamp(6)), rs.getString(7),
                        (Integer) rs.getObject(8), rs.getInt(9), rs.getInt(10), rs.getString(11),
                        instant(rs.getTimestamp(12))))
                .list();
        return new MacroDocumentPage(total, limit, offset, rows);
    }

    /**
     * Macro documents of the country with chunks, published before {@code publishedBefore} ({@code null} = any time),
     * latest publication first (what the AI macro analysis can read as of a date).
     */
    public List<MacroDocumentRow> macroDocumentsPublishedBefore(String country, Instant publishedBefore, int limit) {
        return jdbc.sql("""
                        SELECT d.document_id, d.country, d.title, d.issuer, d.document_type, d.published_at, d.file_name,
                               d.pages, d.characters, d.chunks, d.embedding_model, d.updated_at
                        FROM rag_document d
                        WHERE d.country = :country AND d.chunks > 0
                          AND (CAST(:before AS timestamptz) IS NULL OR d.published_at < :before)
                        ORDER BY d.published_at DESC, d.document_id DESC
                        LIMIT :limit""")
                .param("country", country)
                .param("before", publishedBefore == null ? null : publishedBefore.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, i) -> new MacroDocumentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        MacroDocumentType.valueOf(rs.getString(5)), instant(rs.getTimestamp(6)), rs.getString(7),
                        (Integer) rs.getObject(8), rs.getInt(9), rs.getInt(10), rs.getString(11),
                        instant(rs.getTimestamp(12))))
                .list();
    }

    /** Macro documents stored per country (code -> count; countries without any are absent). */
    public Map<String, Long> macroDocumentCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.sql("SELECT country, count(*) FROM rag_document WHERE country IS NOT NULL GROUP BY country ORDER BY country")
                .query(rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    /**
     * The chunks of the country's macro documents closest to {@code query} (cosine distance), of the documents
     * published before {@code publishedBefore} only ({@code null} = any time: point in time for an analysis as of a
     * date). The HNSW scan continues until enough rows pass the filters (pgvector iterative scan).
     */
    @Transactional
    public List<MacroHit> searchMacro(String country, Instant publishedBefore, float[] query, int limit) {
        jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
        List<MacroHit> hits = new ArrayList<>(jdbc.sql("""
                        SELECT k.chunk_id, k.document_id, d.country, d.title, d.issuer, d.document_type, d.published_at,
                               d.file_name, k.page_from, k.page_to, k.content,
                               k.embedding <=> CAST(:query AS vector) AS distance
                        FROM rag_chunk k
                        JOIN rag_document d ON d.document_id = k.document_id
                        WHERE k.country = :country
                          AND (CAST(:before AS timestamptz) IS NULL OR d.published_at < :before)
                        ORDER BY k.embedding <=> CAST(:query AS vector)
                        LIMIT :limit""")
                .param("query", vector(query))
                .param("country", country)
                .param("before", publishedBefore == null ? null : publishedBefore.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query((rs, i) -> new MacroHit(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), MacroDocumentType.valueOf(rs.getString(6)), instant(rs.getTimestamp(7)),
                        rs.getString(8), (Integer) rs.getObject(9), (Integer) rs.getObject(10), rs.getString(11),
                        rs.getDouble(12)))
                .list());
        hits.sort(Comparator.comparingDouble(MacroHit::distance));     // relaxed order: re-sort exactly
        return hits;
    }

    /** pgvector text form "[0.1,-0.2,...]". */
    static String vector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i])) {
                throw new IllegalArgumentException("Embedding value " + i + " is not finite");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
