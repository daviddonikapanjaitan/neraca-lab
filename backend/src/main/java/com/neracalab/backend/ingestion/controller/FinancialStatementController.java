package com.neracalab.backend.ingestion.controller;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.ingestion.FinancialStatementQueue;
import com.neracalab.backend.ingestion.FinancialStatementQueue.Submission;
import com.neracalab.backend.ingestion.IngestionService;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.Requester;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.neracalab.backend.ingestion.xlsx.IdxWorkbookException;

/**
 * Upload of an IDX XBRL financial statement workbook (.xlsx). Asynchronous: the request checks the
 * workbook (about a second), stores it in {@code ingestion_file} (once per SHA-256 checksum; a file
 * that is already stored is reused) and queues the ingestion, recorded as started by the logged-in
 * user ({@code ingestion_job.created_by}); the AI agent then stores it in the background. Follow the
 * job with {@code GET /api/v1/ingestions/{id}}, whose result is the agent's full audit trail (plan,
 * tool calls, reflection, database verification).
 * <ul>
 *   <li>202 - new job (QUEUED), {@code Location: /api/v1/ingestions/{id}}</li>
 *   <li>200 - the same file is already queued / being stored: that job</li>
 *   <li>422 - not an .xlsx / not an IDX XBRL workbook / unsupported template; 413 - larger than 20 MB</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/financial-statements")
@RequiresPermission(Permission.INGESTION)
public class FinancialStatementController {

    private final IngestionService service;
    private final FinancialStatementQueue queue;

    public FinancialStatementController(IngestionService service, FinancialStatementQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestionJob> upload(@RequestParam("file") MultipartFile file, AuthenticatedUser user)
            throws IOException {
        if (file.isEmpty()) {
            throw new IdxWorkbookException("The uploaded file is empty");
        }
        String fileName = fileName(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IdxWorkbookException("Only .xlsx files are accepted (IDX XBRL FinancialStatement-<period>-<TICKER>.xlsx)");
        }
        byte[] content = file.getBytes();
        service.prepare(content, fileName);   // rejects a wrong workbook before anything is stored
        Submission submission = queue.submit(content, fileName, file.getContentType(), Requester.of(user));
        IngestionJob job = submission.job();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/ingestions/" + job.id()))
                .body(job);
    }

    /** The bare file name (some clients send a path). */
    private static String fileName(String original) {
        if (original == null) {
            return "";
        }
        String name = original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash < 0 ? name : name.substring(slash + 1);
    }

    @ExceptionHandler(IdxWorkbookException.class)
    ProblemDetail invalidWorkbook(IdxWorkbookException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Invalid financial statement workbook");
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The file exceeds the upload limit");
        problem.setTitle("File too large");
        return problem;
    }
}
