package com.neracalab.backend.rag.controller;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.CompanyService.CompanyNotFoundException;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.PdfText;
import com.neracalab.backend.rag.RagProperties;
import com.neracalab.backend.rag.RagQueue;
import com.neracalab.backend.rag.RagQueue.Submission;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.Counts;
import com.neracalab.backend.rag.RagRepository.DocumentRow;
import com.neracalab.backend.rag.RagRepository.Hit;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.screening.news.TavilyClient;

/**
 * RAG vector store of the companies (pgvector): PDF documents and news articles, chunked, embedded and linked to
 * their company. Ingestions are asynchronous jobs ({@code GET /api/v1/ingestions/{id}}).
 * <ul>
 *   <li>{@code POST /api/v1/rag/pdf} (multipart {@code file}, {@code exchange}, {@code ticker}) - queue a PDF; 202
 *       with the new job, 200 with the job already queued / running for the same file and company; 422 for a file
 *       that is not a readable text PDF</li>
 *   <li>{@code POST /api/v1/rag/news?exchange=IDX&ticker=HRTA&from=2026-10-01&to=2026-10-08} - queue the news of a
 *       date range (inclusive, Jakarta time, at most 366 days, not in the future); 202 / 200 as above</li>
 *   <li>{@code GET /api/v1/rag/status} - settings (embedding model, chunking, news limits) and what is stored</li>
 *   <li>{@code GET /api/v1/rag/documents[?exchange&ticker&source=PDF|NEWS&limit]} - stored documents</li>
 *   <li>{@code GET /api/v1/rag/search?q=...[&exchange&ticker&source&limit]} - the closest chunks (cosine)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/rag", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class RagController {

    /** Longest news range of one job. */
    static final int MAX_RANGE_DAYS = 366;
    static final int MAX_QUERY_CHARS = 2000;

    /**
     * @param pending        RAG jobs waiting in the queue (excluding the running one)
     * @param tavilyEnabled  the news ingestion also searches Tavily by date range
     */
    public record Status(String embeddingModel, int embeddingDimensions, int chunkChars, int chunkOverlap,
                         int newsMaxArticles, int newsMaxPages, int maxRangeDays, boolean tavilyEnabled, int pending,
                         Counts stored) {
    }

    private final RagQueue queue;
    private final RagRepository repository;
    private final EmbeddingClient embeddings;
    private final RagProperties properties;
    private final TavilyClient tavily;

    public RagController(RagQueue queue, RagRepository repository, EmbeddingClient embeddings, RagProperties properties,
                         TavilyClient tavily) {
        this.queue = queue;
        this.repository = repository;
        this.embeddings = embeddings;
        this.properties = properties;
        this.tavily = tavily;
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(embeddings.model(), properties.embeddingDimensions(), properties.chunkChars(),
                properties.chunkOverlap(), properties.newsMaxArticles(), properties.newsMaxPages(), MAX_RANGE_DAYS,
                tavily.enabled(), queue.pendingCount(), repository.counts());
    }

    @PostMapping(path = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJob> pdf(@RequestParam("file") MultipartFile file,
                                            @RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                            @RequestParam("ticker") String ticker,
                                            AuthenticatedUser user) throws IOException {
        Company company = company(exchange, ticker);
        if (file.isEmpty()) {
            throw new InvalidRequestException("The uploaded file is empty");
        }
        String fileName = fileName(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new InvalidRequestException("Only .pdf files are accepted");
        }
        byte[] content = file.getBytes();
        if (!isPdf(content)) {
            throw new InvalidRequestException("The file is not a PDF (no %PDF header)");
        }
        PdfText.pages(content);    // rejects an unreadable, protected or scanned PDF before anything is stored
        return response(queue.submitPdf(company, content, fileName, "application/pdf", Requester.of(user)));
    }

    @PostMapping("/news")
    public ResponseEntity<IngestionJob> news(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                             @RequestParam("ticker") String ticker,
                                             @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             AuthenticatedUser user) {
        Company company = company(exchange, ticker);
        LocalDate today = LocalDate.now(Exchange.IDX.zone());
        if (from.isAfter(to)) {
            throw new InvalidRequestException("The start date " + from + " is after the end date " + to);
        }
        if (to.isAfter(today)) {
            throw new InvalidRequestException("The end date " + to + " is in the future (today is " + today + ")");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new InvalidRequestException("The range is longer than " + MAX_RANGE_DAYS + " days");
        }
        return response(queue.submitNews(company, from, to, Requester.of(user)));
    }

    @GetMapping("/documents")
    public List<DocumentRow> documents(@RequestParam(name = "exchange", required = false) String exchange,
                                       @RequestParam(name = "ticker", required = false) String ticker,
                                       @RequestParam(name = "source", required = false) String source,
                                       @RequestParam(name = "limit", defaultValue = "100") int limit) {
        String ex = exchange == null || exchange.isBlank() ? null : Exchange.of(exchange).code();
        String t = ticker == null || ticker.isBlank() ? null : Tickers.normalize(ticker);
        return repository.documents(ex, t, sourceType(source), Math.clamp(limit, 1, 500));
    }

    @GetMapping("/search")
    public List<Hit> search(@RequestParam("q") String q,
                            @RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                            @RequestParam(name = "ticker", required = false) String ticker,
                            @RequestParam(name = "source", required = false) String source,
                            @RequestParam(name = "limit", defaultValue = "8") int limit) {
        String query = q.trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            throw new InvalidRequestException("The query must have 1 to " + MAX_QUERY_CHARS + " characters");
        }
        Long companyId = ticker == null || ticker.isBlank() ? null : company(exchange, ticker).companyId();
        return repository.search(companyId, sourceType(source), embeddings.embed(query), Math.clamp(limit, 1, 50));
    }

    private Company company(String exchange, String ticker) {
        Exchange ex = Exchange.of(exchange);
        String normalized = Tickers.normalize(ticker);
        return repository.company(ex.code(), normalized)
                .orElseThrow(() -> new CompanyNotFoundException(ex.code(), normalized));
    }

    private static ResponseEntity<IngestionJob> response(Submission submission) {
        IngestionJob job = submission.job();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/ingestions/" + job.id()))
                .body(job);
    }

    private static SourceType sourceType(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return SourceType.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Unknown source '" + source + "'; use PDF or NEWS");
        }
    }

    /** "%PDF-" within the first 1024 bytes (the PDF specification allows leading bytes). */
    public static boolean isPdf(byte[] content) {
        int limit = Math.min(content.length, 1024) - 5;
        for (int i = 0; i <= limit; i++) {
            if (content[i] == '%' && content[i + 1] == 'P' && content[i + 2] == 'D' && content[i + 3] == 'F'
                    && content[i + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    /** The bare file name (some clients send a path). */
    static String fileName(String original) {
        if (original == null) {
            return "";
        }
        String name = original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash < 0 ? name : name.substring(slash + 1);
    }

    // ------------------------------------------------------------------ errors

    static class InvalidRequestException extends RuntimeException {

        InvalidRequestException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    @ExceptionHandler(PdfText.PdfException.class)
    ProblemDetail invalidPdf(PdfText.PdfException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Invalid PDF");
        return problem;
    }

    @ExceptionHandler(EmbeddingClient.EmbeddingException.class)
    ProblemDetail embeddingFailed(EmbeddingClient.EmbeddingException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setTitle("Embedding failed");
        return problem;
    }

    @ExceptionHandler(UnsupportedExchangeException.class)
    ProblemDetail unsupportedExchange(UnsupportedExchangeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported exchange");
        problem.setProperty("supportedExchanges", e.supported());
        return problem;
    }

    @ExceptionHandler(InvalidTickerException.class)
    ProblemDetail invalidTicker(InvalidTickerException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid ticker");
        return problem;
    }

    @ExceptionHandler(CompanyNotFoundException.class)
    ProblemDetail companyNotFound(CompanyNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Company not found");
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The file exceeds the upload limit");
        problem.setTitle("File too large");
        return problem;
    }
}
