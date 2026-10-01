package com.neracalab.backend.ingestion;

import java.util.Locale;

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
 * Upload of an IDX XBRL financial statement workbook (.xlsx). The workbook is mapped in Java and
 * stored by the AI agent (Spring AI tool calling); the response contains the plan, every tool
 * call, the reviewer's reflection and the database verification.
 */
@RestController
@RequestMapping("/api/v1/financial-statements")
public class FinancialStatementController {

    private final IngestionService service;

    public FinancialStatementController(IngestionService service) {
        this.service = service;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestionResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IdxWorkbookException("The uploaded file is empty");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) {
            throw new IdxWorkbookException("Only .xlsx files are accepted (IDX XBRL FinancialStatement-<period>-<TICKER>.xlsx)");
        }
        IngestionResponse response = service.ingest(file);
        HttpStatus status = switch (response.status()) {
            case COMPLETED -> HttpStatus.OK;
            case INCOMPLETE -> HttpStatus.ACCEPTED;
            case FAILED -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(response);
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
