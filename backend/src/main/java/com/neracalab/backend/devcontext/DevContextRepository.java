package com.neracalab.backend.devcontext;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code dev_context}: the development context of Claude Code (V1.0.40). A row is found by its ticker, kind, status
 * and tags, by keywords ({@code search}, a full-text vector of the simple configuration) and by meaning ({@code
 * embedding}, cosine distance on the HNSW index). {@link #search} merges the two: a row either finds is a hit, ranked
 * by the better of its similarity and its keyword rank.
 */
@Repository
public class DevContextRepository {

    /** One row; {@code embedded} says whether it has an embedding. */
    public record Entry(long id, String kind, String status, String ticker, String period, String title, String body,
                        List<String> tags, List<String> refs, boolean embedded, String createdBy, Instant createdAt,
                        Instant updatedAt) {
    }

    /** A search hit: {@code similarity} 1 - cosine distance (null without embeddings), {@code keywordRank} ts_rank. */
    public record Hit(Entry entry, Double similarity, Double keywordRank, double score) {
    }

    /** What a row is made of (for insert and update). */
    public record Fields(String kind, String status, String ticker, String period, String title, String body,
                         List<String> tags, List<String> refs) {

        /** The text that is embedded and indexed: ticker, period, title, body and tags. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            if (ticker != null) {
                sb.append(ticker).append(' ');
            }
            if (period != null) {
                sb.append(period).append(' ');
            }
            sb.append(title).append('\n').append(body);
            if (!tags.isEmpty()) {
                sb.append('\n').append(String.join(" ", tags));
            }
            return sb.toString();
        }
    }

    private static final String COLUMNS = """
            context_id, kind, status, ticker, period, title, body, tags, refs, embedding IS NOT NULL AS embedded,
            created_by, created_at, updated_at""";

    private final JdbcClient jdbc;

    public DevContextRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Fields f, float[] embedding, String model, String createdBy) {
        return jdbc.sql("""
                        INSERT INTO dev_context (kind, status, ticker, period, title, body, tags, refs, embedding,
                                                 embedding_model, search, created_by)
                        VALUES (:kind, :status, :ticker, :period, :title, :body, string_to_array(:tags, chr(31)),
                                string_to_array(:refs, chr(31)), CAST(:embedding AS vector), :model,
                                to_tsvector('simple', :text), :createdBy)
                        RETURNING context_id""")
                .params(params(f, embedding, model))
                .param("createdBy", createdBy)
                .query(Long.class).single();
    }

    /** @return whether the row exists */
    public boolean update(long id, Fields f, float[] embedding, String model) {
        return jdbc.sql("""
                        UPDATE dev_context SET kind = :kind, status = :status, ticker = :ticker, period = :period,
                               title = :title, body = :body, tags = string_to_array(:tags, chr(31)),
                               refs = string_to_array(:refs, chr(31)), embedding = CAST(:embedding AS vector),
                               embedding_model = :model, search = to_tsvector('simple', :text), updated_at = now()
                        WHERE context_id = :id""")
                .params(params(f, embedding, model))
                .param("id", id)
                .update() > 0;
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM dev_context WHERE context_id = :id").param("id", id).update() > 0;
    }

    public Optional<Entry> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dev_context WHERE context_id = :id")
                .param("id", id).query(DevContextRepository::entry).optional();
    }

    /** Rows matching every filter given (null: any), newest first. */
    public List<Entry> list(String ticker, String kind, String status, String tag, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dev_context " + where(ticker, kind, status, tag)
                        + " ORDER BY updated_at DESC, context_id DESC LIMIT :limit")
                .params(filters(ticker, kind, status, tag))
                .param("limit", limit)
                .query(DevContextRepository::entry).list();
    }

    /**
     * Rows by meaning and by keywords, within the filters. The nearest {@code limit} by embedding and the best
     * {@code limit} by keyword rank are merged; the score is the larger of the similarity and the keyword rank
     * scaled to 0 - 1 (rank / (rank + 0.1)), so an exact word match ranks high even without an embedding.
     *
     * @param query     the query's embedding; null: keywords only
     * @param keywords  the query text
     */
    public List<Hit> search(float[] query, String keywords, String ticker, String kind, String status, String tag,
                            int limit) {
        Map<Long, Hit> hits = new LinkedHashMap<>();
        String where = where(ticker, kind, status, tag);
        if (query != null) {
            jdbc.sql("SELECT " + COLUMNS + ", 1 - (embedding <=> CAST(:query AS vector)) AS similarity FROM dev_context "
                            + where + (where.isEmpty() ? "WHERE" : " AND") + " embedding IS NOT NULL"
                            + " ORDER BY embedding <=> CAST(:query AS vector) LIMIT :limit")
                    .params(filters(ticker, kind, status, tag))
                    .param("query", vector(query))
                    .param("limit", limit)
                    .query((rs, i) -> {
                        double similarity = rs.getDouble("similarity");
                        hits.put(rs.getLong("context_id"), new Hit(entry(rs, i), similarity, null, similarity));
                        return null;
                    }).list();
        }
        jdbc.sql("SELECT " + COLUMNS + ", ts_rank(search, websearch_to_tsquery('simple', :keywords)) AS rank FROM dev_context "
                        + where + (where.isEmpty() ? "WHERE" : " AND") + " search @@ websearch_to_tsquery('simple', :keywords)"
                        + " ORDER BY rank DESC LIMIT :limit")
                .params(filters(ticker, kind, status, tag))
                .param("keywords", keywords)
                .param("limit", limit)
                .query((rs, i) -> {
                    double rank = rs.getDouble("rank");
                    double scaled = rank / (rank + 0.1);
                    long id = rs.getLong("context_id");
                    Hit semantic = hits.get(id);
                    hits.put(id, semantic == null ? new Hit(entry(rs, i), null, rank, scaled)
                            : new Hit(semantic.entry(), semantic.similarity(), rank, Math.max(semantic.score(), scaled)));
                    return null;
                }).list();
        List<Hit> result = new ArrayList<>(hits.values());
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    /** Rows without an embedding (to compute it later), oldest first. */
    public List<Long> withoutEmbedding(int limit) {
        return jdbc.sql("SELECT context_id FROM dev_context WHERE embedding IS NULL ORDER BY context_id LIMIT :limit")
                .param("limit", limit).query(Long.class).list();
    }

    public void setEmbedding(long id, float[] embedding, String model) {
        jdbc.sql("UPDATE dev_context SET embedding = CAST(:embedding AS vector), embedding_model = :model WHERE context_id = :id")
                .param("embedding", vector(embedding))
                .param("model", model)
                .param("id", id)
                .update();
    }

    private static Map<String, Object> params(Fields f, float[] embedding, String model) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", f.kind());
        p.put("status", f.status());
        p.put("ticker", f.ticker());
        p.put("period", f.period());
        p.put("title", f.title());
        p.put("body", f.body());
        // chr(31) (unit separator) cannot occur in a tag or a reference: the arrays travel as one string each
        p.put("tags", String.join("\u001f", f.tags()));
        p.put("refs", String.join("\u001f", f.refs()));
        p.put("embedding", embedding == null ? null : vector(embedding));
        p.put("model", embedding == null ? null : model);
        p.put("text", f.text());
        return p;
    }

    private static String where(String ticker, String kind, String status, String tag) {
        List<String> conditions = new ArrayList<>();
        if (ticker != null) {
            conditions.add("ticker = :ticker");
        }
        if (kind != null) {
            conditions.add("kind = :kind");
        }
        if (status != null) {
            conditions.add("status = :status");
        }
        if (tag != null) {
            conditions.add(":tag = ANY(tags)");
        }
        return conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
    }

    private static Map<String, Object> filters(String ticker, String kind, String status, String tag) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (ticker != null) {
            p.put("ticker", ticker);
        }
        if (kind != null) {
            p.put("kind", kind);
        }
        if (status != null) {
            p.put("status", status);
        }
        if (tag != null) {
            p.put("tag", tag);
        }
        return p;
    }

    private static Entry entry(ResultSet rs, int i) throws SQLException {
        return new Entry(rs.getLong("context_id"), rs.getString("kind"), rs.getString("status"), rs.getString("ticker"),
                rs.getString("period"), rs.getString("title"), rs.getString("body"), strings(rs.getArray("tags")),
                strings(rs.getArray("refs")), rs.getBoolean("embedded"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
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
}
