package com.neracalab.backend.devcontext;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.devcontext.DevContextRepository.Entry;
import com.neracalab.backend.devcontext.DevContextRepository.Fields;
import com.neracalab.backend.devcontext.DevContextRepository.Hit;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.web.ApiException;

/**
 * The development context of Claude Code ({@code dev_context}): validates what is written, embeds it with the RAG
 * store's model and searches by meaning and keywords. An embedding that cannot be computed (no key, provider down)
 * never loses the row: it is stored without one, keyword search finds it, and {@link #reembed} fills it later.
 */
@Service
public class DevContextService {

    private static final Logger log = LoggerFactory.getLogger(DevContextService.class);

    static final Set<String> KINDS = Set.of("ISSUE", "FIX", "DATA_GAP", "DECISION", "PROCEDURE", "NOTE");
    static final Set<String> STATUSES = Set.of("OPEN", "RESOLVED", "WONT_FIX", "INFO");
    static final int MAX_LIMIT = 100;

    /** What a client sends; {@code status} defaults to INFO, {@code tags} and {@code refs} to none. */
    public record Request(String kind, String status, String ticker, String period, String title, String body,
                          List<String> tags, List<String> refs) {
    }

    private final DevContextRepository repository;
    private final EmbeddingClient embeddings;

    public DevContextService(DevContextRepository repository, EmbeddingClient embeddings) {
        this.repository = repository;
        this.embeddings = embeddings;
    }

    public Entry create(Request request, String createdBy) {
        Fields fields = fields(request);
        long id = repository.insert(fields, embed(fields.text()), embeddings.model(), createdBy);
        return repository.find(id).orElseThrow();
    }

    public Entry update(long id, Request request) {
        Fields fields = fields(request);
        if (!repository.update(id, fields, embed(fields.text()), embeddings.model())) {
            throw notFound(id);
        }
        return repository.find(id).orElseThrow();
    }

    public Entry get(long id) {
        return repository.find(id).orElseThrow(() -> notFound(id));
    }

    public void delete(long id) {
        if (!repository.delete(id)) {
            throw notFound(id);
        }
    }

    public List<Entry> list(String ticker, String kind, String status, String tag, Integer limit) {
        return repository.list(ticker(ticker), option(kind, KINDS, "kind"), option(status, STATUSES, "status"),
                blankToNull(tag), limit(limit, 50));
    }

    public List<Hit> search(String query, String ticker, String kind, String status, String tag, Integer limit) {
        if (query == null || query.isBlank()) {
            throw ApiException.badRequest("q is required");
        }
        return repository.search(embed(query.strip()), query.strip(), ticker(ticker), option(kind, KINDS, "kind"),
                option(status, STATUSES, "status"), blankToNull(tag), limit(limit, 10));
    }

    /** Computes the missing embeddings; @return how many rows got one. */
    public int reembed() {
        int done = 0;
        for (long id : repository.withoutEmbedding(MAX_LIMIT)) {
            Entry e = repository.find(id).orElse(null);
            if (e == null) {
                continue;
            }
            float[] v = embed(new Fields(e.kind(), e.status(), e.ticker(), e.period(), e.title(), e.body(), e.tags(),
                    e.refs()).text());
            if (v == null) {
                break;      // the provider fails: the rest stays for the next call
            }
            repository.setEmbedding(id, v, embeddings.model());
            done++;
        }
        return done;
    }

    private float[] embed(String text) {
        try {
            return embeddings.embed(text);
        } catch (RuntimeException e) {
            log.warn("dev_context: no embedding ({}); stored for keyword search only", e.getMessage());
            return null;
        }
    }

    static Fields fields(Request r) {
        if (r == null) {
            throw ApiException.badRequest("The request body is missing");
        }
        String kind = option(r.kind(), KINDS, "kind");
        if (kind == null) {
            throw ApiException.badRequest("kind is required: one of " + KINDS);
        }
        String status = option(r.status(), STATUSES, "status");
        String title = blankToNull(r.title());
        String body = blankToNull(r.body());
        if (title == null || body == null) {
            throw ApiException.badRequest("title and body are required");
        }
        if (title.length() > 500) {
            throw ApiException.badRequest("title is longer than 500 characters");
        }
        String period = blankToNull(r.period());
        if (period != null && period.length() > 20) {
            throw ApiException.badRequest("period is longer than 20 characters (e.g. 2025 FY, 2026 H1)");
        }
        return new Fields(kind, status == null ? "INFO" : status, ticker(r.ticker()), period, title, body,
                clean(r.tags()), clean(r.refs()));
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip)
                .map(v -> v.replace('\u001f', ' ')).distinct().toList();
    }

    private static String ticker(String ticker) {
        String t = blankToNull(ticker);
        if (t == null) {
            return null;
        }
        t = t.toUpperCase(Locale.ROOT);
        if (t.length() > 20) {
            throw ApiException.badRequest("ticker is longer than 20 characters");
        }
        return t;
    }

    private static String option(String value, Set<String> allowed, String name) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        v = v.toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw ApiException.badRequest(name + " must be one of " + allowed);
        }
        return v;
    }

    private static int limit(Integer limit, int fallback) {
        return limit == null ? fallback : Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static ApiException notFound(long id) {
        return ApiException.notFound("Not found", "No development context " + id);
    }
}
