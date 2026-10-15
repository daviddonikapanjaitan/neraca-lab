package com.neracalab.backend.macro.controller;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.macro.MacroIngestionQueue.Submission;
import com.neracalab.backend.macro.MacroIngestionService;
import com.neracalab.backend.macro.MacroJob;
import com.neracalab.backend.macro.MacroService;
import com.neracalab.backend.macro.MacroService.SeriesNotFoundException;

/**
 * Macro data of the technical analysis (Ingestion › Macro; macro.md). The fetches run in the macro queue and are listed
 * with the other jobs ({@code ingestion_job} type MACRO, ticker = the series code).
 * <ul>
 *   <li>{@code GET /api/v1/macro/series} - every series with what is stored and its state</li>
 *   <li>{@code GET /api/v1/macro/series/{code}?from=2025-01-01} - one series with its observations
 *       ({@code [date, value, released]})</li>
 *   <li>{@code POST /api/v1/macro/ingestions?code=US_10Y[&full=true]} - queue one series; 202 with the new job, or 200
 *       with the job already queued / running</li>
 *   <li>{@code POST /api/v1/macro/updates} - queue every series that is behind (202)</li>
 *   <li>{@code POST /api/v1/macro/series/{code}/upload} (multipart {@code file}) - store a CSV of
 *       {@code obs_date,value}</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/macro", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class MacroController {

    private final MacroService macro;

    public MacroController(MacroService macro) {
        this.macro = macro;
    }

    @GetMapping("/series")
    public MacroService.Status series() {
        return macro.status();
    }

    @GetMapping("/series/{code}")
    public MacroService.History history(@PathVariable("code") String code,
                                        @RequestParam(name = "from", required = false) String from) {
        LocalDate start;
        try {
            start = from == null || from.isBlank() ? null : LocalDate.parse(from.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("from must be a date like 2025-01-31, not '" + from + "'");
        }
        return macro.history(code, start);
    }

    /** @param full re-fetch the whole history instead of the recent observations */
    @PostMapping("/ingestions")
    public ResponseEntity<MacroJob.View> ingest(@RequestParam(name = "code") String code,
                                                @RequestParam(name = "full", defaultValue = "false") boolean full,
                                                AuthenticatedUser user) {
        Submission submission = macro.submit(code, full, Requester.of(user));
        MacroJob.View view = submission.job().view();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/ingestions/" + view.id()))
                .body(view);
    }

    @PostMapping("/updates")
    public ResponseEntity<MacroService.Update> updateAll(AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(macro.updateAll(Requester.of(user)));
    }

    @PostMapping(path = "/series/{code}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MacroIngestionService.Result upload(@PathVariable("code") String code,
                                               @RequestPart("file") MultipartFile file,
                                               AuthenticatedUser user) throws IOException {
        return macro.upload(code, file.getBytes(), Requester.of(user));
    }

    @ExceptionHandler(SeriesNotFoundException.class)
    ProblemDetail notFound(SeriesNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Macro series not found");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid request");
        return problem;
    }

    /** A CSV that does not pass the checks (format, range, dates). */
    @ExceptionHandler(SourceException.class)
    ProblemDetail rejected(SourceException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("Rejected");
        return problem;
    }
}
