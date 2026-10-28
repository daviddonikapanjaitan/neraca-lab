package com.neracalab.backend.rag.controller;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.ownership.OwnershipParser;
import com.neracalab.backend.ownership.OwnershipParser.OwnershipFormatException;
import com.neracalab.backend.ownership.OwnershipQueue;
import com.neracalab.backend.ownership.OwnershipQueue.Submission;
import com.neracalab.backend.ownership.OwnershipRepository;
import com.neracalab.backend.ownership.OwnershipRepository.ReportPage;
import com.neracalab.backend.ownership.onepercent.OnePercentParser;
import com.neracalab.backend.ownership.onepercent.OnePercentParser.OnePercentFormatException;
import com.neracalab.backend.ownership.onepercent.OnePercentRepository;
import com.neracalab.backend.rag.ExcelDocumentType;
import com.neracalab.backend.rag.RagRepository;
import com.neracalab.backend.rag.RagRepository.SourceType;

/**
 * Ingestion > Excel Documents (RAG) (permission INGESTION): Excel workbooks of an exchange, read into the database
 * and the RAG vector store. For IDX the document type Ownership (>=5%) is KSEI's daily list of the shareholders of
 * more than 5% (Companies > Ownership (>=5%)), Ownership (>=1%) its monthly list of the shareholders of more than 1%
 * (Companies > Ownership (>=1%)).
 * <ul>
 *   <li>{@code POST /api/v1/rag/xlsx} (multipart {@code file}, {@code exchange} = IDX, {@code documentType} =
 *       OWNERSHIP_5_PERCENT or OWNERSHIP_1_PERCENT) - the workbook is read at once (422 when it is not the type's list,
 *       nothing stored) and queued: 202 with the new job, 200 with the job already queued / running for the same
 *       file</li>
 *   <li>{@code GET /api/v1/rag/xlsx/status} - the exchanges and their document types, the lists stored of each type,
 *       the queue</li>
 *   <li>{@code GET /api/v1/rag/xlsx/reports[?exchange=IDX&documentType=OWNERSHIP_5_PERCENT&limit=100&offset=0]} - the
 *       stored lists of the type, latest first, with the movements of each</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/rag/xlsx", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class RagExcelController {

    public record DocumentTypeOption(String code, String label, boolean available, String examples) {
    }

    /** An exchange and the Excel documents it takes ("IDX - Indonesia Stock Exchange"). */
    public record ExchangeOption(String code, String name, String label, List<DocumentTypeOption> documentTypes) {
    }

    /**
     * The lists stored of one document type (IDX).
     *
     * @param latestDate the latest of them; null when none
     * @param documents  the companies' documents of the type in the vector store
     */
    public record StoredType(String documentType, String label, long reports, String latestDate, long documents) {
    }

    /**
     * @param reports         lists stored (Ownership (>=5%), IDX)
     * @param latestDate      the latest of them; null when none
     * @param documents       the companies' ownership documents in the vector store (both types)
     * @param pending         uploads waiting in the queue (excluding the running one)
     * @param stored          the lists stored of each type
     */
    public record Status(List<ExchangeOption> exchanges, long reports, String latestDate, long documents, int pending,
                         List<StoredType> stored) {
    }

    private final OwnershipQueue queue;
    private final OwnershipRepository ownership;
    private final OnePercentRepository onePercent;
    private final RagRepository rag;

    public RagExcelController(OwnershipQueue queue, OwnershipRepository ownership, OnePercentRepository onePercent,
                              RagRepository rag) {
        this.queue = queue;
        this.ownership = ownership;
        this.onePercent = onePercent;
        this.rag = rag;
    }

    @GetMapping("/status")
    public Status status() {
        List<DocumentTypeOption> types = Arrays.stream(ExcelDocumentType.values())
                .map(t -> new DocumentTypeOption(t.name(), t.label(), t.available(), t.examples())).toList();
        List<ExchangeOption> exchanges = Arrays.stream(Exchange.values())
                .map(e -> new ExchangeOption(e.code(), e.displayName(), e.code() + " - " + e.displayName(), types))
                .toList();
        String idx = Exchange.IDX.code();
        List<LocalDate> dates = ownership.dates(idx, ExcelDocumentType.OWNERSHIP_5_PERCENT);
        List<StoredType> stored = List.of(stored(ExcelDocumentType.OWNERSHIP_5_PERCENT, dates),
                stored(ExcelDocumentType.OWNERSHIP_1_PERCENT, onePercent.dates(idx)));
        return new Status(exchanges, dates.size(), dates.isEmpty() ? null : dates.getFirst().toString(),
                rag.counts().xlsxDocuments(), queue.pendingCount(), stored);
    }

    private StoredType stored(ExcelDocumentType type, List<LocalDate> dates) {
        return new StoredType(type.name(), type.label(), dates.size(),
                dates.isEmpty() ? null : dates.getFirst().toString(), rag.documentCount(SourceType.XLSX, type.name()));
    }

    /**
     * The stored lists of a type: a {@link ReportPage} of Ownership (>=5%), a {@link OnePercentRepository.ReportPage}
     * of Ownership (>=1%).
     */
    @GetMapping("/reports")
    public Object reports(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                          @RequestParam(name = "documentType", defaultValue = "OWNERSHIP_5_PERCENT") String documentType,
                          @RequestParam(name = "limit", defaultValue = "100") int limit,
                          @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        String code = Exchange.of(exchange).code();
        ExcelDocumentType type = availableType(documentType);
        if (type == ExcelDocumentType.OWNERSHIP_1_PERCENT) {
            return onePercent.reports(code, Math.clamp(limit, 1, 500), offset);
        }
        return ownership.reports(code, type, Math.clamp(limit, 1, 500), offset);
    }

    /**
     * An Excel document of the exchange: read here already, so a workbook that is not the document type's (or not a
     * workbook) is refused (422) before anything is stored.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionJob> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                               @RequestParam(name = "documentType", required = false) String documentType,
                                               AuthenticatedUser user) throws IOException {
        Exchange ex = Exchange.of(exchange);
        ExcelDocumentType type = availableType(documentType);
        if (file.isEmpty()) {
            throw new InvalidRequestException("The uploaded file is empty");
        }
        String fileName = RagController.fileName(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new InvalidRequestException("Only .xlsx workbooks are accepted");
        }
        byte[] content = file.getBytes();
        if (!isZip(content)) {
            throw new InvalidRequestException("The file is not an .xlsx workbook (no ZIP header)");
        }
        // refuses what is not the type's list before anything is stored
        if (type == ExcelDocumentType.OWNERSHIP_1_PERCENT) {
            OnePercentParser.parse(content);
        } else {
            OwnershipParser.parse(content);
        }
        Submission submission = queue.submit(ex.code(), type, content, fileName, Requester.of(user));
        IngestionJob job = submission.job();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/ingestions/" + job.id()))
                .body(job);
    }

    /** A type that takes uploads now; a later one and unknown codes are refused. */
    private static ExcelDocumentType availableType(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidRequestException("Choose the type of the document");
        }
        ExcelDocumentType type = ExcelDocumentType.of(code).orElseThrow(() -> new InvalidRequestException(
                "Unknown document type '" + code + "'; use one of: " + String.join(", ",
                        Arrays.stream(ExcelDocumentType.values()).filter(ExcelDocumentType::available)
                                .map(Enum::name).toList())));
        if (!type.available()) {
            throw new InvalidRequestException(type.label() + " is not available yet: use one of " + String.join(", ",
                    Arrays.stream(ExcelDocumentType.values()).filter(ExcelDocumentType::available)
                            .map(ExcelDocumentType::label).toList()));
        }
        return type;
    }

    /** "PK\3\4": an .xlsx is a ZIP archive. */
    static boolean isZip(byte[] content) {
        return content.length >= 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4;
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

    @ExceptionHandler(OwnershipFormatException.class)
    ProblemDetail notTheList(OwnershipFormatException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Not a readable ownership list");
        return problem;
    }

    @ExceptionHandler(OnePercentFormatException.class)
    ProblemDetail notTheMonthlyList(OnePercentFormatException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Not a readable ownership list");
        return problem;
    }

    @ExceptionHandler(UnsupportedExchangeException.class)
    ProblemDetail unsupportedExchange(UnsupportedExchangeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported exchange");
        problem.setProperty("supportedExchanges", e.supported());
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The file exceeds the upload limit");
        problem.setTitle("File too large");
        return problem;
    }
}
