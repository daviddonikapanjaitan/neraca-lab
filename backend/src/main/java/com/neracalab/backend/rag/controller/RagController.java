package com.neracalab.backend.rag.controller;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import com.neracalab.backend.macro.outlook.MacroOutlookService;
import com.neracalab.backend.rag.CompanyDocumentType;
import com.neracalab.backend.rag.EmbeddingClient;
import com.neracalab.backend.rag.MacroDocumentType;
import com.neracalab.backend.rag.PdfText;
import com.neracalab.backend.rag.RagIngestionService;
import com.neracalab.backend.rag.RagIngestionService.MacroMeta;
import com.neracalab.backend.rag.RagProperties;
import com.neracalab.backend.rag.RagQueue;
import com.neracalab.backend.rag.RagQueue.Submission;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.Company;
import com.neracalab.backend.rag.RagRepository.Counts;
import com.neracalab.backend.rag.RagRepository.DocumentPage;
import com.neracalab.backend.rag.RagRepository.Hit;
import com.neracalab.backend.rag.RagRepository.MacroDocumentPage;
import com.neracalab.backend.rag.RagRepository.MacroHit;
import com.neracalab.backend.rag.RagRepository.SourceType;
import com.neracalab.backend.registration.RegistrationParser.RegistrationFormatException;

/**
 * RAG vector store (pgvector): PDF documents and news articles of the companies, chunked, embedded and linked to
 * their company, and macro PDFs of the countries, linked to their country (read by the AI macro analysis). Ingestions
 * are asynchronous jobs ({@code GET /api/v1/ingestions/{id}}).
 * <ul>
 *   <li>{@code POST /api/v1/rag/pdf} (multipart {@code file}, {@code exchange}, {@code ticker}, optional
 *       {@code documentType}: FINANCIAL_STATEMENT, the default, or REGISTRATION) - queue a PDF; 202 with the new job,
 *       200 with the job already queued / running for the same file and company; 422 for a file that is not a
 *       readable text PDF, or, as a REGISTRATION, not a readable registration report of the company</li>
 *   <li>{@code POST /api/v1/rag/news?exchange=IDX&ticker=HRTA&from=2026-10-01&to=2026-10-08} - queue the news of a
 *       date range (inclusive, Jakarta time, at most 366 days, not in the future); 202 / 200 as above</li>
 *   <li>{@code GET /api/v1/rag/status} - settings (embedding model, chunking, news limits) and what is stored</li>
 *   <li>{@code GET /api/v1/rag/documents[?exchange&ticker&tickerPrefix&source=PDF|NEWS|XLSX|XBRL&documentType&limit&offset]} -
 *       one page of the stored documents, with the number matching the filters</li>
 *   <li>{@code GET /api/v1/rag/search?q=...[&exchange&ticker&source&limit]} - the closest chunks (cosine)</li>
 *   <li>{@code POST /api/v1/rag/macro/pdf} (multipart {@code file}, {@code country}, {@code documentType},
 *       {@code publishedOn}, optional {@code title} and {@code issuer}) - queue a macro PDF of a country (Indonesia;
 *       the United States and China later: 400); 202 / 200 / 422 as a company PDF</li>
 *   <li>{@code GET /api/v1/rag/macro/documents[?country&documentType&limit&offset]} - one page of the stored macro
 *       documents, latest publication first</li>
 *   <li>{@code GET /api/v1/rag/macro/search?q=...[&country=ID&limit]} - the closest chunks of the country's macro
 *       documents</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/rag", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class RagController {

    /** Longest news range of one job. */
    static final int MAX_RANGE_DAYS = 366;
    static final int MAX_QUERY_CHARS = 2000;

    /** Longest title of a macro document. */
    static final int MAX_TITLE_CHARS = 300;
    /** Longest issuer of a macro document. */
    static final int MAX_ISSUER_CHARS = 100;

    /**
     * @param pending            RAG jobs waiting in the queue (excluding the running one)
     * @param macroCountries     the countries of the macro documents (only the available ones take uploads)
     * @param macroDocumentTypes the types of a macro document
     * @param companyDocumentTypes the types of a company PDF (the first is the default)
     */
    public record Status(String embeddingModel, int embeddingDimensions, int chunkChars, int chunkOverlap,
                         int newsMaxArticles, int newsMaxPages, int maxRangeDays, int pending, Counts stored,
                         List<MacroCountry> macroCountries, List<DocumentTypeOption> macroDocumentTypes,
                         List<DocumentTypeOption> companyDocumentTypes) {
    }

    /** A country of the macro documents and how many are stored for it. */
    public record MacroCountry(String code, String name, boolean available, long documents) {
    }

    public record DocumentTypeOption(String code, String label, String examples) {
    }

    private final RagQueue queue;
    private final RagRepository repository;
    private final EmbeddingClient embeddings;
    private final RagProperties properties;

    public RagController(RagQueue queue, RagRepository repository, EmbeddingClient embeddings, RagProperties properties) {
        this.queue = queue;
        this.repository = repository;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Status status() {
        Map<String, Long> perCountry = repository.macroDocumentCounts();
        List<MacroCountry> countries = MacroOutlookService.COUNTRIES.stream()
                .map(c -> new MacroCountry(c.code(), c.name(), c.available(), perCountry.getOrDefault(c.code(), 0L)))
                .toList();
        List<DocumentTypeOption> types = Arrays.stream(MacroDocumentType.values())
                .map(t -> new DocumentTypeOption(t.name(), t.label(), t.examples())).toList();
        List<DocumentTypeOption> companyTypes = Arrays.stream(CompanyDocumentType.values())
                .map(t -> new DocumentTypeOption(t.name(), t.label(), t.examples())).toList();
        return new Status(embeddings.model(), properties.embeddingDimensions(), properties.chunkChars(),
                properties.chunkOverlap(), properties.newsMaxArticles(), properties.newsMaxPages(), MAX_RANGE_DAYS,
                queue.pendingCount(), repository.counts(), countries, types, companyTypes);
    }

    /**
     * A macro PDF of a country (Bank Indonesia, the Ministry of Finance, the IMF, ...): queued as a RAG_PDF job of
     * exchange MACRO. {@code publishedOn} is the publication date (the AI macro analysis reads only the documents
     * published by its as-of date); the title defaults to the file name.
     */
    @PostMapping(path = "/macro/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJob> macroPdf(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(name = "country", defaultValue = "ID") String country,
                                                 @RequestParam(name = "documentType", required = false) String documentType,
                                                 @RequestParam(name = "publishedOn", required = false) String publishedOn,
                                                 @RequestParam(name = "title", required = false) String title,
                                                 @RequestParam(name = "issuer", required = false) String issuer,
                                                 AuthenticatedUser user) throws IOException {
        MacroOutlookService.Country c = availableCountry(country);
        MacroDocumentType type = MacroDocumentType.of(documentType).orElseThrow(() -> new InvalidRequestException(
                documentType == null || documentType.isBlank() ? "Choose the type of the document"
                        : "Unknown document type '" + documentType + "'; use one of: " + String.join(", ",
                        Arrays.stream(MacroDocumentType.values()).map(Enum::name).toList())));
        LocalDate published = publishedOn(publishedOn);
        if (file.isEmpty()) {
            throw new InvalidRequestException("The uploaded file is empty");
        }
        String fileName = fileName(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new InvalidRequestException("Only .pdf files are accepted");
        }
        String name = title == null || title.isBlank() ? RagIngestionService.title(fileName) : compact(title);
        if (name.isEmpty() || name.length() > MAX_TITLE_CHARS) {
            throw new InvalidRequestException("The title must have 1 to " + MAX_TITLE_CHARS + " characters");
        }
        String by = issuer == null || issuer.isBlank() ? null : compact(issuer);
        if (by != null && by.length() > MAX_ISSUER_CHARS) {
            throw new InvalidRequestException("The issuer must have at most " + MAX_ISSUER_CHARS + " characters");
        }
        byte[] content = file.getBytes();
        if (!isPdf(content)) {
            throw new InvalidRequestException("The file is not a PDF (no %PDF header)");
        }
        PdfText.pages(content);    // rejects an unreadable, protected or scanned PDF before anything is stored
        MacroMeta meta = new MacroMeta(c.code(), c.name(), name, by, type, published);
        return response(queue.submitMacroPdf(meta, content, fileName, "application/pdf", Requester.of(user)));
    }

    @GetMapping("/macro/documents")
    public MacroDocumentPage macroDocuments(@RequestParam(name = "country", required = false) String country,
                                            @RequestParam(name = "documentType", required = false) String documentType,
                                            @RequestParam(name = "limit", defaultValue = "100") int limit,
                                            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        String code = country == null || country.isBlank() ? null : knownCountry(country).code();
        MacroDocumentType type = documentType == null || documentType.isBlank() ? null
                : MacroDocumentType.of(documentType).orElseThrow(() -> new InvalidRequestException(
                        "Unknown document type '" + documentType + "'"));
        return repository.macroDocuments(code, type, Math.clamp(limit, 1, 500), offset);
    }

    @GetMapping("/macro/search")
    public List<MacroHit> macroSearch(@RequestParam("q") String q,
                                      @RequestParam(name = "country", defaultValue = "ID") String country,
                                      @RequestParam(name = "limit", defaultValue = "8") int limit) {
        String query = q.trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_CHARS) {
            throw new InvalidRequestException("The query must have 1 to " + MAX_QUERY_CHARS + " characters");
        }
        String code = knownCountry(country).code();
        return repository.searchMacro(code, null, embeddings.embed(query), Math.clamp(limit, 1, 50));
    }

    /** A country of the list ("id" too), available or not. */
    private static MacroOutlookService.Country knownCountry(String code) {
        return MacroOutlookService.findCountry(code).orElseThrow(() -> new InvalidRequestException(
                "Unknown country '" + code + "'; use " + String.join(", ",
                        MacroOutlookService.COUNTRIES.stream().map(MacroOutlookService.Country::code).toList())));
    }

    /** A country that takes macro documents now (Indonesia; the United States and China later). */
    private static MacroOutlookService.Country availableCountry(String code) {
        MacroOutlookService.Country c = knownCountry(code);
        if (!c.available()) {
            throw new InvalidRequestException(c.name() + " is not available yet: only Indonesia (ID) for now");
        }
        return c;
    }

    /** The publication date: required, ISO (2026-08-20), not in the future (Jakarta time). */
    private static LocalDate publishedOn(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException("Give the publication date of the document");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("The publication date '" + value + "' is not a date (yyyy-mm-dd)");
        }
        LocalDate today = LocalDate.now(Exchange.IDX.zone());
        if (date.isAfter(today)) {
            throw new InvalidRequestException("The publication date " + date + " is in the future (today is " + today + ")");
        }
        if (date.getYear() < 1945) {
            throw new InvalidRequestException("The publication date " + date + " is before 1945");
        }
        return date;
    }

    private static String compact(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * A PDF of a company: a financial statement (the default) or a monthly registration report of securities holders.
     * A registration report is read here already, so a file that is not one, or is another company's, is refused
     * (422) before anything is stored.
     */
    @PostMapping(path = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJob> pdf(@RequestParam("file") MultipartFile file,
                                            @RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                            @RequestParam("ticker") String ticker,
                                            @RequestParam(name = "documentType", required = false) String documentType,
                                            AuthenticatedUser user) throws IOException {
        CompanyDocumentType type = companyDocumentType(documentType);
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
        if (type == CompanyDocumentType.REGISTRATION) {
            RagIngestionService.registrationOf(company, content);
        }
        return response(queue.submitPdf(company, type, content, fileName, "application/pdf", Requester.of(user)));
    }

    /** The type of a company PDF: FINANCIAL_STATEMENT when not given. */
    private static CompanyDocumentType companyDocumentType(String code) {
        if (code == null || code.isBlank()) {
            return CompanyDocumentType.FINANCIAL_STATEMENT;
        }
        return CompanyDocumentType.of(code).orElseThrow(() -> new InvalidRequestException("Unknown document type '"
                + code + "'; use one of: " + String.join(", ",
                Arrays.stream(CompanyDocumentType.values()).map(Enum::name).toList())));
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
    public DocumentPage documents(@RequestParam(name = "exchange", required = false) String exchange,
                                  @RequestParam(name = "ticker", required = false) String ticker,
                                  @RequestParam(name = "tickerPrefix", required = false) String tickerPrefix,
                                  @RequestParam(name = "source", required = false) String source,
                                  @RequestParam(name = "documentType", required = false) String documentType,
                                  @RequestParam(name = "limit", defaultValue = "100") int limit,
                                  @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        CompanyDocumentType type = documentType == null || documentType.isBlank() ? null
                : companyDocumentType(documentType);
        String ex = exchange == null || exchange.isBlank() ? null : Exchange.of(exchange).code();
        String t = ticker == null || ticker.isBlank() ? null : Tickers.normalize(ticker);
        // the beginning of a ticker: the same characters as a ticker (no LIKE wildcards)
        String prefix = tickerPrefix == null || tickerPrefix.isBlank() ? null : Tickers.normalize(tickerPrefix);
        return repository.documents(ex, t, prefix, sourceType(source), type, Math.clamp(limit, 1, 500), offset);
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
            throw new InvalidRequestException("Unknown source '" + source + "'; use PDF, NEWS, XLSX or XBRL");
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

    @ExceptionHandler(RegistrationFormatException.class)
    ProblemDetail notARegistrationReport(RegistrationFormatException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Not a readable registration report");
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
