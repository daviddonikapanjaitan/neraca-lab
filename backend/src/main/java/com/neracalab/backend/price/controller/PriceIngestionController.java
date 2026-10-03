package com.neracalab.backend.price.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.price.PriceDailyRepository;
import com.neracalab.backend.price.PriceIngestionJob;
import com.neracalab.backend.price.PriceIngestionQueue;
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
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.company.CompanyService.CompanyNotFoundException;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.company.Tickers.InvalidTickerException;
import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceIngestionQueue.Submission;

/**
 * Daily price ingestion (price_daily + valuation refresh). Admin / job API: the provider is called
 * by a background worker, never inside a request, and the frontend reads prices from the database.
 * <ul>
 *   <li>{@code POST /api/v1/prices/ingestions?exchange=IDX&ticker=HRTA[&full=true]} - queue an ingestion;
 *       202 with the new job, or 200 with the job already queued / running for the company</li>
 *   <li>{@code GET /api/v1/prices/ingestions/{id}} - one job</li>
 *   <li>{@code GET /api/v1/prices/ingestions} - provider, queue length and recent jobs (most recent first)</li>
 * </ul>
 * Exchange and ticker codes are case-insensitive.
 */
@RestController
@RequestMapping(path = "/api/v1/prices/ingestions", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class PriceIngestionController {

    /** @param pending jobs waiting in the queue (excluding the running one) */
    public record QueueResponse(String provider, int pending, List<PriceIngestionJob.View> jobs) {
    }

    private final PriceIngestionQueue queue;
    private final PriceDailyRepository prices;

    public PriceIngestionController(PriceIngestionQueue queue, PriceDailyRepository prices) {
        this.queue = queue;
        this.prices = prices;
    }

    /**
     * @param full re-fetch the whole history instead of starting from the latest stored day
     * @param user recorded as the requester of the job ({@code ingestion_job.created_by})
     */
    @PostMapping
    public ResponseEntity<PriceIngestionJob.View> submit(@RequestParam("exchange") String exchange,
                                                         @RequestParam("ticker") String ticker,
                                                         @RequestParam(name = "full", defaultValue = "false") boolean full,
                                                         AuthenticatedUser user) {
        Exchange ex = Exchange.of(exchange);
        String normalized = Tickers.normalize(ticker);
        CompanyRef company = prices.company(ex, normalized)
                .orElseThrow(() -> new CompanyNotFoundException(ex.code(), normalized));
        Submission submission = queue.submit(company, full, Requester.of(user));
        PriceIngestionJob.View view = submission.job().view();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/prices/ingestions/" + view.id()))
                .body(view);
    }

    @GetMapping("/{id}")
    public PriceIngestionJob.View job(@PathVariable("id") UUID id) {
        return queue.job(id).map(PriceIngestionJob::view).orElseThrow(() -> new JobNotFoundException(id));
    }

    @GetMapping
    public QueueResponse jobs() {
        return new QueueResponse(queue.providerName(), queue.pendingCount(),
                queue.jobs().stream().map(PriceIngestionJob::view).toList());
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

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail jobNotFound(JobNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Ingestion job not found");
        return problem;
    }

    /** No job with this id (unknown, or forgotten after a restart / beyond job-history). */
    static class JobNotFoundException extends RuntimeException {

        JobNotFoundException(UUID id) {
            super("No price ingestion job " + id);
        }
    }
}
