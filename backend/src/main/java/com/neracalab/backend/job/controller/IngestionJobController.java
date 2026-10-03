package com.neracalab.backend.job.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobStatus;
import com.neracalab.backend.job.IngestionJobType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Progress of every ingestion process (financial statement uploads and price ingestions), from
 * {@code ingestion_job}.
 * <ul>
 *   <li>{@code GET /api/v1/ingestions[?type=PRICE][&status=QUEUED,RUNNING][&limit=50]} - jobs, most
 *       recent first (without results), plus the number of jobs per status</li>
 *   <li>{@code GET /api/v1/ingestions/{id}} - one job with its result</li>
 * </ul>
 * {@code type} and {@code status} are case-insensitive; {@code status} takes a comma-separated list.
 */
@RestController
@RequestMapping(path = "/api/v1/ingestions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class IngestionJobController {

    static final int MAX_LIMIT = 500;

    /**
     * @param counts jobs per status (all jobs, not only the listed ones; of {@code type} when given)
     * @param active jobs QUEUED, RUNNING or WAITING_RATE_LIMIT (from {@code counts})
     * @param jobs   most recent first, at most {@code limit}, without results
     */
    public record IngestionJobList(Map<IngestionJobStatus, Long> counts, long active, int limit,
                                   List<IngestionJob> jobs) {
    }

    private final IngestionJobRepository repository;

    public IngestionJobController(IngestionJobRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public IngestionJobList list(@RequestParam(name = "type", required = false) String type,
                                 @RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidParameterException("limit must be between 1 and " + MAX_LIMIT);
        }
        IngestionJobType jobType = type == null || type.isBlank() ? null : parse(IngestionJobType.class, "type", type);
        List<IngestionJobStatus> statuses = new ArrayList<>();
        if (status != null) {
            for (String value : status.split(",")) {
                if (!value.isBlank()) {
                    statuses.add(parse(IngestionJobStatus.class, "status", value));
                }
            }
        }
        Map<IngestionJobStatus, Long> counts = repository.countByStatus(jobType);
        long active = counts.entrySet().stream().filter(e -> e.getKey().active()).mapToLong(Map.Entry::getValue).sum();
        return new IngestionJobList(counts, active, limit, repository.list(jobType, statuses, limit));
    }

    @GetMapping("/{id}")
    public IngestionJob job(@PathVariable("id") UUID id) {
        return repository.find(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String parameter, String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        List<String> names = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(normalized)).findFirst()
                .orElseThrow(() -> new InvalidParameterException("Unknown " + parameter + " '" + value.trim()
                        + "'; supported: " + String.join(", ", names)));
    }

    @ExceptionHandler(InvalidParameterException.class)
    ProblemDetail invalidParameter(InvalidParameterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid parameter");
        return problem;
    }

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail jobNotFound(JobNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Ingestion job not found");
        return problem;
    }

    /** An unknown type / status or a limit out of range. */
    static class InvalidParameterException extends RuntimeException {

        InvalidParameterException(String message) {
            super(message);
        }
    }

    /** No job with this id. */
    static class JobNotFoundException extends RuntimeException {

        JobNotFoundException(UUID id) {
            super("No ingestion job " + id);
        }
    }
}
