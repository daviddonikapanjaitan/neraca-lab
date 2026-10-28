package com.neracalab.backend.rag.controller;

import java.io.IOException;
import java.net.URI;
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
import com.neracalab.backend.rag.ExcelDocumentType;
import com.neracalab.backend.rag.RagRepository;

/**
 * Ingestion > Excel Documents (RAG) (permission INGESTION): Excel workbooks of an exchange, read into the database
 * and the RAG vector store. For IDX the document type Ownership (>=5%) is KSEI's daily list of the shareholders of
 * more than 5% (Companies > Ownership (>=5%)); Ownership (>1%) is a later feature.
 * <ul>
 *   <li>{@code POST /api/v1/rag/xlsx} (multipart {@code file}, {@code exchange} = IDX, {@code documentType} =
 *       OWNERSHIP_5_PERCENT) - the workbook is read at once (422 when it is not the list, nothing stored) and queued:
 *       202 with the new job, 200 with the job already queued / running for the same file</li>
 *   <li>{@code GET /api/v1/rag/xlsx/status} - the exchanges and their document types, the lists stored, the queue</li>
 *   <li>{@code GET /api/v1/rag/xlsx/reports[?exchange=IDX&documentType=OWNERSHIP_5_PERCENT&limit=100&offset=0]} - the
 *       stored lists, latest day first, with the movements of each</li>
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
     * @param reports         lists stored (Ownership (>=5%), IDX)
     * @param latestDate      the latest of them; null when none
     * @param documents       the companies' ownership documents in the vector store
     * @param pending         uploads waiting in the queue (excluding the running one)
     */
    public record Status(List<ExchangeOption> exchanges, long reports, String latestDate, long documents, int pending) {
    }

    private final OwnershipQueue queue;
    private final OwnershipRepository ownership;
    private final RagRepository rag;

    public RagExcelController(OwnershipQueue queue, OwnershipRepository ownership, RagRepository rag) {
        this.queue = queue;
        this.ownership = ownership;
        this.rag = rag;
    }

    @GetMapping("/status")
    public Status status() {
        List<DocumentTypeOption> types = Arrays.stream(ExcelDocumentType.values())
                .map(t -> new DocumentTypeOption(t.name(), t.label(), t.available(), t.examples())).toList();
        List<ExchangeOption> exchanges = Arrays.stream(Exchange.values())
                .map(e -> new ExchangeOption(e.code(), e.displayName(), e.code() + " - " + e.displayName(), types))
                .toList();
        List<java.time.LocalDate> dates = ownership.dates(Exchange.IDX.code(), ExcelDocumentType.OWNERSHIP_5_PERCENT);
        return new Status(exchanges, dates.size(), dates.isEmpty() ? null : dates.getFirst().toString(),
                rag.counts().xlsxDocuments(), queue.pendingCount());
    }

    @GetMapping("/reports")
    public ReportPage reports(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                              @RequestParam(name = "documentType", defaultValue = "OWNERSHIP_5_PERCENT") String documentType,
                              @RequestParam(name = "limit", defaultValue = "100") int limit,
                              @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (offset < 0) {
            throw new InvalidRequestException("offset must be 0 or more");
        }
        return ownership.reports(Exchange.of(exchange).code(), availableType(documentType), Math.clamp(limit, 1, 500),
                offset);
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
        OwnershipParser.parse(content);     // refuses what is not the list before anything is stored
        Submission submission = queue.submit(ex.code(), type, content, fileName, Requester.of(user));
        IngestionJob job = submission.job();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/ingestions/" + job.id()))
                .body(job);
    }

    /** A type that takes uploads now (Ownership (>=5%)); the later ones and unknown codes are refused. */
    private static ExcelDocumentType availableType(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidRequestException("Choose the type of the document");
        }
        ExcelDocumentType type = ExcelDocumentType.of(code).orElseThrow(() -> new InvalidRequestException(
                "Unknown document type '" + code + "'; use one of: " + String.join(", ",
                        Arrays.stream(ExcelDocumentType.values()).filter(ExcelDocumentType::available)
                                .map(Enum::name).toList())));
        if (!type.available()) {
            throw new InvalidRequestException(type.label() + " is not available yet: only "
                    + ExcelDocumentType.OWNERSHIP_5_PERCENT.label() + " for now");
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
