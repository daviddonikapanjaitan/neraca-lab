package com.neracalab.backend.rag;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.NewDocument;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsHttpClient;
import com.neracalab.backend.screening.news.NewsParsers;
import com.neracalab.backend.screening.news.NewsParsers.ArticleText;

/**
 * Builds the RAG vector store: the text of a PDF or of news articles is chunked ({@link TextChunker}), embedded
 * ({@link EmbeddingClient}) and stored with its company ({@link RagRepository}).
 */
@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);

    /** Outcome of a PDF: what was stored. */
    public record PdfResult(long documentId, String fileName, int pages, int pagesWithText, int characters, int chunks,
                            String embeddingModel) {
    }

    /** One article of a news ingestion. */
    public record ArticleResult(String url, String source, String title, Instant publishedAt, String outcome, Integer chunks) {
    }

    public record NewsResult(LocalDate from, LocalDate to, List<NewsCollector.SourceResult> sources, int headlinesInRange,
                             int stored, int alreadyStored, int outOfRange, int failed, boolean truncated,
                             List<ArticleResult> articles, String embeddingModel) {
    }

    private final RagRepository repository;
    private final EmbeddingClient embeddings;
    private final NewsCollector collector;
    private final NewsHttpClient http;
    private final RagProperties properties;

    public RagIngestionService(RagRepository repository, EmbeddingClient embeddings, NewsCollector collector,
                               NewsHttpClient http, RagProperties properties) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.collector = collector;
        this.http = http;
        this.properties = properties;
    }

    TextChunker chunker() {
        return new TextChunker(properties.chunkChars(), properties.chunkOverlap());
    }

    // ------------------------------------------------------------------ PDF

    /** Chunks, embeds and stores a PDF of the company (the same file again replaces its document). */
    public PdfResult ingestPdf(UUID jobId, Company company, long fileId, String fileName, String checksum, byte[] content,
                               Consumer<String> progress) {
        progress.accept("Reading the text of " + fileName);
        List<PdfText.Page> pages = PdfText.pages(content);
        List<TextChunker.Part> parts = pages.stream().map(p -> new TextChunker.Part(p.text(), p.number())).toList();
        List<TextChunker.Chunk> chunks = chunker().chunk(parts);
        if (chunks.isEmpty()) {
            throw new PdfText.PdfException("The PDF has no text to index");
        }
        int characters = chunks.stream().mapToInt(c -> c.text().length()).sum();
        progress.accept("Embedding " + chunks.size() + " chunks of " + pages.size() + " pages (" + embeddings.model() + ")");
        List<float[]> vectors = embeddings.embed(chunks.stream().map(TextChunker.Chunk::text).toList());
        progress.accept("Storing " + chunks.size() + " chunks in the vector store");
        long id = repository.store(new NewDocument(company.companyId(), SourceType.PDF, checksum, title(fileName), "PDF upload",
                null, fileId, fileName, null, pages.size(), characters, embeddings.model(), jobId), chunks, vectors);
        int withText = (int) pages.stream().filter(p -> !p.text().isBlank()).count();
        return new PdfResult(id, fileName, pages.size(), withText, characters, chunks.size(), embeddings.model());
    }

    /** "FinancialStatement-2025-Tahunan-HRTA.pdf" -> "FinancialStatement-2025-Tahunan-HRTA" */
    static String title(String fileName) {
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    // ------------------------------------------------------------------ news

    /**
     * Collects the company's news published from {@code from} to {@code to} (inclusive, Jakarta time), reads every
     * article not stored yet, and stores it in the vector store; at most {@code news-max-articles} (newest first).
     */
    public NewsResult ingestNews(UUID jobId, Company company, LocalDate from, LocalDate to, Consumer<String> progress) {
        NewsCollector.Collected collected = collector.collect(company.ticker(), company.companyName(), from, to, progress);
        List<ArticleResult> articles = new ArrayList<>();
        int stored = 0;
        int already = 0;
        int outOfRange = 0;
        int failed = 0;
        List<Headline> headlines = collected.headlines();
        boolean truncated = headlines.size() > properties.newsMaxArticles();
        List<Headline> todo = truncated ? headlines.subList(0, properties.newsMaxArticles()) : headlines;
        for (int i = 0; i < todo.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            Headline h = todo.get(i);
            if (repository.hasDocument(company.companyId(), SourceType.NEWS, h.url())) {
                already++;
                articles.add(new ArticleResult(h.url(), h.source().label(), h.title(), h.publishedAt(), "ALREADY_STORED", null));
                continue;
            }
            progress.accept("Article " + (i + 1) + " of " + todo.size() + ": " + abbreviate(h.title(), 120));
            String fallback = usable(collected.searchText().get(h.url()));
            try {
                ArticleText article;
                boolean fromSearch = false;
                try {
                    article = read(h.url());
                    if ((article.body() == null || article.body().isBlank()) && fallback != null) {
                        article = searchArticle(h, fallback);
                        fromSearch = true;
                    }
                } catch (NewsHttpClient.NewsFetchException e) {
                    if (fallback == null) {
                        throw e;
                    }
                    // the site refuses our request (HTTP 403, ...): the text the search engine read
                    article = searchArticle(h, fallback);
                    fromSearch = true;
                }
                Instant published = h.publishedAt() != null ? h.publishedAt() : article.publishedAt();
                if (published == null || !NewsCollector.inRange(published, from, to)) {
                    outOfRange++;
                    articles.add(new ArticleResult(h.url(), h.source().label(), h.title(), published,
                            published == null ? "NO_DATE" : "OUT_OF_RANGE", null));
                    continue;
                }
                String title = article.title() != null && !article.title().isBlank() ? article.title() : h.title();
                String text = newsText(title, article.description() != null ? article.description() : h.description(), article.body());
                List<TextChunker.Chunk> chunks = chunker().chunk(List.of(new TextChunker.Part(text, null)));
                if (article.body() == null || article.body().isBlank() || chunks.isEmpty()) {
                    failed++;
                    articles.add(new ArticleResult(h.url(), h.source().label(), title, published, "NO_TEXT", null));
                    continue;
                }
                List<float[]> vectors = embeddings.embed(chunks.stream().map(TextChunker.Chunk::text).toList());
                repository.store(new NewDocument(company.companyId(), SourceType.NEWS, h.url(), title, h.source().label(),
                        h.url(), null, null, published, null, text.length(), embeddings.model(), jobId), chunks, vectors);
                stored++;
                articles.add(new ArticleResult(h.url(), h.source().label(), title, published,
                        fromSearch ? "STORED_FROM_SEARCH_TEXT" : "STORED", chunks.size()));
            } catch (EmbeddingClient.EmbeddingException e) {
                throw e;     // the store cannot be filled at all: fail the job
            } catch (RuntimeException e) {
                log.info("news article {} not stored: {}", h.url(), e.getMessage());
                failed++;
                articles.add(new ArticleResult(h.url(), h.source().label(), h.title(), h.publishedAt(),
                        "FAILED: " + abbreviate(e.getMessage(), 200), null));
            }
        }
        return new NewsResult(from, to, collected.sources(), headlines.size(), stored, already, outOfRange, failed, truncated,
                articles, embeddings.model());
    }

    /** Shortest search-engine page text used in place of an article. */
    static final int MIN_SEARCH_TEXT = 300;

    /** The search text, cleaned and cut to the article limit; null when too short to be an article. */
    private String usable(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replace((char) 0xA0, ' ').replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*\\n\\s*", "\n\n").trim();
        if (t.length() < MIN_SEARCH_TEXT) {
            return null;
        }
        return t.length() <= properties.articleChars() ? t : t.substring(0, properties.articleChars());
    }

    private static ArticleText searchArticle(Headline h, String text) {
        return new ArticleText(h.title(), h.description(), h.publishedAt(), text);
    }

    private ArticleText read(String url) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not a web URL");
        }
        NewsHttpClient.Page page = http.get(uri);
        if (page.status() != 200 || page.body() == null) {
            throw new NewsHttpClient.NewsFetchException(uri.getHost() + " answered HTTP " + page.status(), null);
        }
        return NewsParsers.article(page.body(), url, properties.articleChars());
    }

    /** What is embedded for an article: title, lead and body. */
    static String newsText(String title, String description, String body) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("title", title);
        parts.put("description", description);
        parts.put("body", body);
        StringBuilder sb = new StringBuilder();
        for (String part : parts.values()) {
            if (part != null && !part.isBlank() && sb.indexOf(part.trim()) < 0) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
