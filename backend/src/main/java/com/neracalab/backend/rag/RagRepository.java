package com.neracalab.backend.rag;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The RAG vector store: {@code rag_document} (one per PDF or news article of a company) and {@code rag_chunk}
 * (text chunks with their embeddings, {@code vector(1536)}). Similarity search orders by cosine distance
 * ({@code <=>}, HNSW index).
 */
@Repository
public class RagRepository {

    public enum SourceType { PDF, NEWS }

    public record Company(long companyId, String exchange, String ticker, String companyName) {
    }

    /** A document to store; {@code fileId} / {@code fileName} for a PDF, {@code sourceUrl} / {@code publishedAt} for news. */
    public record NewDocument(long companyId, SourceType sourceType, String sourceKey, String title, String sourceName,
                              String sourceUrl, Long fileId, String fileName, Instant publishedAt, Integer pages,
                              int characters, String embeddingModel, UUID jobId) {
    }

    public record DocumentRow(long documentId, String exchange, String ticker, String companyName, SourceType sourceType,
                              String title, String sourceName, String sourceUrl, String fileName, Instant publishedAt,
                              Integer pages, int characters, int chunks, String embeddingModel, Instant updatedAt) {
    }

    /** Documents and chunks stored per source type. */
    public record Counts(long pdfDocuments, long newsDocuments, long chunks) {
    }

    public record Hit(long chunkId, long documentId, String ticker, SourceType sourceType, String title, String sourceUrl,
                      String fileName, Instant publishedAt, Integer pageFrom, Integer pageTo, String content,
                      double distance) {
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
                        INSERT INTO rag_document (company_id, source_type, source_key, title, source_name, source_url,
                                                  file_id, file_name, published_at, pages, characters, chunks,
                                                  embedding_model, job_id)
                        VALUES (:company, :type, :key, :title, :sourceName, :url, :fileId, :fileName, :published, :pages,
                                :characters, :chunks, :model, :job)
                        ON CONFLICT ON CONSTRAINT uq_rag_document_source DO UPDATE SET
                            title = EXCLUDED.title, source_name = EXCLUDED.source_name, source_url = EXCLUDED.source_url,
                            file_id = EXCLUDED.file_id, file_name = EXCLUDED.file_name,
                            published_at = EXCLUDED.published_at, pages = EXCLUDED.pages,
                            characters = EXCLUDED.characters, chunks = EXCLUDED.chunks,
                            embedding_model = EXCLUDED.embedding_model, job_id = EXCLUDED.job_id, updated_at = now()
                        RETURNING document_id""")
                .param("company", doc.companyId())
                .param("type", doc.sourceType().name())
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
        jdbc.sql("DELETE FROM rag_chunk WHERE document_id = :d").param("d", id).update();
        List<Object[]> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.Chunk c = chunks.get(i);
            rows.add(new Object[] {id, doc.companyId(), c.index(), c.pageFrom(), c.pageTo(), c.text(), vector(embeddings.get(i))});
        }
        template.batchUpdate("""
                INSERT INTO rag_chunk (document_id, company_id, chunk_index, page_from, page_to, content, embedding)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector))""", rows,
                new int[] {Types.BIGINT, Types.BIGINT, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR});
        return id;
    }

    /** Stored documents, most recently updated first; exchange / ticker / type narrow the list when given. */
    public List<DocumentRow> documents(String exchange, String ticker, SourceType type, int limit) {
        return jdbc.sql("""
                        SELECT d.document_id, c.exchange, c.ticker, c.company_name, d.source_type, d.title, d.source_name,
                               d.source_url, d.file_name, d.published_at, d.pages, d.characters, d.chunks,
                               d.embedding_model, d.updated_at
                        FROM rag_document d JOIN company c ON c.company_id = d.company_id
                        WHERE (CAST(:exchange AS varchar) IS NULL OR c.exchange = :exchange)
                          AND (CAST(:ticker AS varchar) IS NULL OR c.ticker = :ticker)
                          AND (CAST(:type AS varchar) IS NULL OR d.source_type = :type)
                        ORDER BY d.updated_at DESC, d.document_id DESC
                        LIMIT :limit""")
                .param("exchange", exchange, Types.VARCHAR)
                .param("ticker", ticker, Types.VARCHAR)
                .param("type", type == null ? null : type.name(), Types.VARCHAR)
                .param("limit", limit)
                .query((rs, i) -> new DocumentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        SourceType.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), instant(rs.getTimestamp(10)), (Integer) rs.getObject(11), rs.getInt(12),
                        rs.getInt(13), rs.getString(14), instant(rs.getTimestamp(15))))
                .list();
    }

    /**
     * The chunks closest to {@code query} (cosine distance), of one company when given, of one source type when
     * given. The HNSW scan continues until enough rows pass the filters (pgvector iterative scan).
     */
    @Transactional
    public List<Hit> search(Long companyId, SourceType type, float[] query, int limit) {
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
                        ORDER BY k.embedding <=> CAST(:query AS vector)
                        LIMIT :limit""")
                .param("query", vector(query))
                .param("company", companyId, Types.BIGINT)
                .param("type", type == null ? null : type.name(), Types.VARCHAR)
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
                        SELECT (SELECT count(*) FROM rag_document WHERE source_type = 'PDF'),
                               (SELECT count(*) FROM rag_document WHERE source_type = 'NEWS'),
                               (SELECT count(*) FROM rag_chunk)""")
                .query((rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
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
