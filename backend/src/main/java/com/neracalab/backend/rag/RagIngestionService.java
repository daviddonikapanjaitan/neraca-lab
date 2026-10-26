package com.neracalab.backend.rag;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import com.neracalab.backend.registration.RegistrationParser;
import com.neracalab.backend.registration.RegistrationParser.RegistrationFormatException;
import com.neracalab.backend.registration.RegistrationParser.Report;
import com.neracalab.backend.registration.RegistrationRepository;
import com.neracalab.backend.screening.news.Headline;
import com.neracalab.backend.screening.news.NewsHttpClient;
import com.neracalab.backend.screening.news.NewsParsers;
import com.neracalab.backend.screening.news.NewsParsers.ArticleText;

/**
 * Builds the RAG vector store: the text of a PDF or of news articles is chunked ({@link TextChunker}), embedded
 * ({@link EmbeddingClient}) and stored with its company, or a macro PDF with its country ({@link RagRepository}). A
 * company PDF of type {@link CompanyDocumentType#REGISTRATION} is also read into the shares of the directors and
 * commissioners ({@link RegistrationParser}, {@link RegistrationRepository}).
 */
@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);
    /** The publication date of a macro document is stored as 00:00 Jakarta time. */
    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    /**
     * Outcome of a PDF: what was stored.
     *
     * @param registration what was read from a registration report; null for a financial statement
     */
    public record PdfResult(long documentId, String fileName, CompanyDocumentType documentType, int pages,
                            int pagesWithText, int characters, int chunks, String embeddingModel,
                            RegistrationResult registration) {
    }

    /**
     * What a registration report held (the job result of a REGISTRATION PDF).
     *
     * @param period      "2026-06": the month the report closes
     * @param filedAt     filing time as printed (Jakarta time, "2026-08-10T15:14"); null when not printed
     * @param insiders    directors and commissioners in the report
     * @param changes     of them, those whose shares changed during the month
     * @param holders     shareholders of 5% or more in the report
     * @param outcome     STORED, REPLACED (the report of the month stored before), or KEPT_LATER_FILING (the report of
     *                    the month stored before was filed later, e.g. its correction: it stays)
     * @param keptFiledAt the filing time of the report kept (KEPT_LATER_FILING, Jakarta time)
     * @param warnings    what did not add up (a percentage that does not match its share count)
     */
    public record RegistrationResult(String period, String letterNumber, boolean correction, String filedAt,
                                     int insiders, int changes, int holders, RegistrationRepository.Outcome outcome,
                                     String keptFiledAt, List<String> warnings) {
    }

    /** One article of a news ingestion. */
    public record ArticleResult(String url, String source, String title, Instant publishedAt, String outcome, Integer chunks) {
    }

    public record NewsResult(LocalDate from, LocalDate to, List<NewsCollector.SourceResult> sources, int headlinesInRange,
                             int stored, int alreadyStored, int outOfRange, int failed, boolean truncated,
                             List<ArticleResult> articles, String embeddingModel) {
    }

    private final RagRepository repository;
    private final RegistrationRepository registrations;
    private final EmbeddingClient embeddings;
    private final NewsCollector collector;
    private final NewsHttpClient http;
    private final RagProperties properties;

    public RagIngestionService(RagRepository repository, RegistrationRepository registrations, EmbeddingClient embeddings,
                               NewsCollector collector, NewsHttpClient http, RagProperties properties) {
        this.repository = repository;
        this.registrations = registrations;
        this.embeddings = embeddings;
        this.collector = collector;
        this.http = http;
        this.properties = properties;
    }

    TextChunker chunker() {
        return new TextChunker(properties.chunkChars(), properties.chunkOverlap());
    }

    // ------------------------------------------------------------------ PDF

    /**
     * Chunks, embeds and stores a PDF of the company (the same file again replaces its document, with the type given
     * now). A registration report is read first (a file that is not one, or is another company's, stops the job before
     * anything is stored) and its directors' and commissioners' shares are stored after its chunks; a file stored as
     * a registration report before and now as a financial statement loses its report.
     *
     * @throws RegistrationFormatException a REGISTRATION PDF that is not a readable registration report of the company
     */
    public PdfResult ingestPdf(UUID jobId, Company company, CompanyDocumentType documentType, long fileId,
                               String fileName, String checksum, byte[] content, Consumer<String> progress) {
        Report report = null;
        if (documentType == CompanyDocumentType.REGISTRATION) {
            progress.accept("Reading the registration report " + fileName);
            report = registrationOf(company, content);
        }
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
        long id = repository.store(new NewDocument(company.companyId(), SourceType.PDF, documentType, checksum,
                title(fileName), "PDF upload", null, fileId, fileName, null, pages.size(), characters,
                embeddings.model(), jobId), chunks, vectors);
        RegistrationResult registration = null;
        if (report != null) {
            progress.accept("Storing the shares of " + report.insiders().size() + " directors and commissioners and "
                    + report.holders().size() + " shareholders of 5% or more (" + report.period() + ")");
            RegistrationRepository.Stored stored = registrations.store(company.companyId(), report, id, fileId, fileName, jobId);
            registration = new RegistrationResult(report.period().toString(), report.letterNumber(), report.correction(),
                    report.filedAt() == null ? null : report.filedAt().toString(), report.insiders().size(),
                    (int) report.insiders().stream().filter(i -> i.change() != null && i.change() != 0).count(),
                    report.holders().size(), stored.outcome(), stored.keptFiledAt() == null ? null
                    : LocalDateTime.ofInstant(stored.keptFiledAt(), JAKARTA).toString(), report.warnings());
        } else {
            registrations.deleteOfDocument(id);
        }
        int withText = (int) pages.stream().filter(p -> !p.text().isBlank()).count();
        return new PdfResult(id, fileName, documentType, pages.size(), withText, characters, chunks.size(),
                embeddings.model(), registration);
    }

    /**
     * The registration report in the PDF, which must be the company's (its Kode Emiten).
     *
     * @throws RegistrationFormatException not a readable registration report, or another company's
     */
    public static Report registrationOf(Company company, byte[] content) {
        Report report = RegistrationParser.parse(content);
        if (!report.ticker().equals(company.ticker())) {
            throw new RegistrationFormatException("The report is " + report.ticker() + "'s (Kode Emiten "
                    + report.ticker() + "), not " + company.ticker() + "'s: choose " + report.ticker()
                    + " as the company");
        }
        return report;
    }

    // ------------------------------------------------------------------ macro PDF of a country

    /**
     * What a macro PDF is, as given at the upload.
     *
     * @param publishedOn publication date of the document (not the period it covers)
     * @param issuer      who published it; null when not given
     */
    public record MacroMeta(String country, String countryName, String title, String issuer,
                            MacroDocumentType documentType, LocalDate publishedOn) {
    }

    /** Outcome of a macro PDF: what was stored (the job result). */
    public record MacroPdfResult(long documentId, String country, String countryName, String title, String issuer,
                                 MacroDocumentType documentType, LocalDate publishedOn, String fileName, int pages,
                                 int pagesWithText, int characters, int chunks, String embeddingModel) {
    }

    /**
     * Chunks, embeds and stores a macro PDF of a country (the same file again for the country replaces its document
     * and its details). Each chunk is embedded with the document's issuer, title and date in front of it, so a search
     * for "Bank Indonesia guidance" also finds a page that never names its publisher; the stored text is the chunk
     * alone.
     */
    public MacroPdfResult ingestMacroPdf(UUID jobId, MacroMeta meta, long fileId, String fileName, String checksum,
                                         byte[] content, Consumer<String> progress) {
        progress.accept("Reading the text of " + fileName);
        List<PdfText.Page> pages = PdfText.pages(content);
        List<TextChunker.Part> parts = pages.stream().map(p -> new TextChunker.Part(p.text(), p.number())).toList();
        List<TextChunker.Chunk> chunks = chunker().chunk(parts);
        if (chunks.isEmpty()) {
            throw new PdfText.PdfException("The PDF has no text to index");
        }
        int characters = chunks.stream().mapToInt(c -> c.text().length()).sum();
        progress.accept("Embedding " + chunks.size() + " chunks of " + pages.size() + " pages (" + embeddings.model() + ")");
        String header = macroHeader(meta);
        List<float[]> vectors = embeddings.embed(chunks.stream().map(c -> header + c.text()).toList());
        progress.accept("Storing " + chunks.size() + " chunks in the vector store (" + meta.countryName() + ")");
        Instant published = meta.publishedOn().atStartOfDay(JAKARTA).toInstant();
        long id = repository.storeMacro(new RagRepository.NewMacroDocument(meta.country(), checksum, meta.title(),
                meta.issuer(), meta.documentType(), published, fileId, fileName, pages.size(), characters,
                embeddings.model(), jobId), chunks, vectors);
        int withText = (int) pages.stream().filter(p -> !p.text().isBlank()).count();
        return new MacroPdfResult(id, meta.country(), meta.countryName(), meta.title(), meta.issuer(), meta.documentType(),
                meta.publishedOn(), fileName, pages.size(), withText, characters, chunks.size(), embeddings.model());
    }

    /** "Bank Indonesia - Monetary Policy Review August 2026 (monetary policy, Indonesia, 2026-08-20)\n" */
    static String macroHeader(MacroMeta meta) {
        StringBuilder b = new StringBuilder();
        if (meta.issuer() != null && !meta.issuer().isBlank()) {
            b.append(meta.issuer().trim()).append(" - ");
        }
        b.append(meta.title()).append(" (").append(meta.documentType().label().toLowerCase(java.util.Locale.ROOT))
                .append(", ").append(meta.countryName()).append(", ").append(meta.publishedOn()).append(")\n");
        return b.toString();
    }

    /** "FinancialStatement-2025-Tahunan-HRTA.pdf" -> "FinancialStatement-2025-Tahunan-HRTA" */
    public static String title(String fileName) {
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
            try {
                ArticleText article = read(h.url());
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
                repository.store(new NewDocument(company.companyId(), SourceType.NEWS, null, h.url(), title, h.source().label(),
                        h.url(), null, null, published, null, text.length(), embeddings.model(), jobId), chunks, vectors);
                stored++;
                articles.add(new ArticleResult(h.url(), h.source().label(), title, published, "STORED", chunks.size()));
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

    private ArticleText read(String url) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not a web URL");
        }
        // a passing failure (timeout, 429, 5xx, a 404 for a listed article) is retried
        NewsHttpClient.Page page = NewsRetry.get(http, uri, properties.newsRetries(), properties.newsRetryBackoff(), true);
        if (page.status() != 200 || page.body() == null) {
            throw new NewsHttpClient.NewsFetchException(uri.getHost() + " answered HTTP " + page.status()
                    + (NewsRetry.passing(page.status(), true) ? " (" + (properties.newsRetries() + 1) + " attempts)" : ""),
                    null);
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
