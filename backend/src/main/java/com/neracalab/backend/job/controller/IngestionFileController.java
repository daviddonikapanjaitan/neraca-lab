package com.neracalab.backend.job.controller;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobRepository.UploadedFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/ingestions/{id}/file}: downloads the workbook of an upload job, exactly as
 * stored in {@code ingestion_file}, under the name of that job's upload. 404 for an unknown job and
 * for a job without file (price ingestion).
 * <p>
 * A separate controller: {@link IngestionJobController} produces JSON only.
 */
@RestController
@RequiresPermission(Permission.INGESTION)
public class IngestionFileController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final IngestionJobRepository repository;

    public IngestionFileController(IngestionJobRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/v1/ingestions/{id}/file")
    public ResponseEntity<byte[]> download(@PathVariable("id") UUID id) {
        UploadedFile file = repository.uploadedFile(id).orElseThrow(() -> new FileNotFoundException(id));
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(file.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .eTag("\"" + file.checksumSha256() + "\"")
                .header("X-Checksum-SHA256", file.checksumSha256())
                .body(file.content());
    }

    @ExceptionHandler(FileNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(FileNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("File not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** No upload job with this id, or the job has no file. */
    static class FileNotFoundException extends RuntimeException {

        FileNotFoundException(UUID id) {
            super("No uploaded file for ingestion job " + id);
        }
    }
}
