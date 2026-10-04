package com.neracalab.backend.screening.data.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.IngestionJob;
import com.neracalab.backend.job.IngestionJobRepository;
import com.neracalab.backend.job.IngestionJobType;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.screening.data.FundamentalRepository;
import com.neracalab.backend.screening.data.FundamentalRepository.DataStatus;
import com.neracalab.backend.screening.data.FundamentalsQueue;
import com.neracalab.backend.web.ApiException;

/**
 * The screening data ETL (Yahoo Finance market data and fundamentals of every listing).
 * <ul>
 *   <li>{@code POST /api/v1/fundamentals/ingestions?exchange=IDX[&full=true]} - queue a run; 202 with the
 *       new job, or 200 with the run already active for the exchange (INGESTION)</li>
 *   <li>{@code GET /api/v1/fundamentals/status?exchange=IDX} - how much data is stored and the active run
 *       (INGESTION or SCREENING)</li>
 * </ul>
 * Progress and result of a run: {@code GET /api/v1/ingestions/{id}} (type FUNDAMENTALS).
 */
@RestController
@RequestMapping(path = "/api/v1/fundamentals", produces = MediaType.APPLICATION_JSON_VALUE)
public class FundamentalsController {

    /**
     * @param activeJobId the queued / running ETL run of the exchange, if any
     */
    public record StatusResponse(String exchange, int listings, String latestSnapshotDate, int withFundamentals,
                                 String oldestFundamentalsAt, String lastSyncAt, UUID activeJobId) {
    }

    private final FundamentalsQueue queue;
    private final FundamentalRepository repository;
    private final IngestionJobRepository jobs;

    public FundamentalsController(FundamentalsQueue queue, FundamentalRepository repository, IngestionJobRepository jobs) {
        this.queue = queue;
        this.repository = repository;
        this.jobs = jobs;
    }

    /** @param full refresh the fundamentals of every listing, not only those due */
    @PostMapping("/ingestions")
    @RequiresPermission(Permission.INGESTION)
    public ResponseEntity<IngestionJob> submit(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                               @RequestParam(name = "full", defaultValue = "false") boolean full,
                                               AuthenticatedUser user) {
        FundamentalsQueue.Submission submission = queue.submit(exchange(exchange), full, Requester.of(user));
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/ingestions/" + submission.job().id()))
                .body(submission.job());
    }

    @GetMapping("/status")
    @RequiresPermission({Permission.INGESTION, Permission.SCREENING})
    public StatusResponse status(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange) {
        Exchange ex = exchange(exchange);
        DataStatus s = repository.status(ex);
        return new StatusResponse(ex.code(), s.listings(),
                s.latestSnapshotDate() == null ? null : s.latestSnapshotDate().toString(), s.withFundamentals(),
                s.oldestFundamentalsAt() == null ? null : s.oldestFundamentalsAt().toString(),
                s.lastSyncAt() == null ? null : s.lastSyncAt().toString(),
                jobs.activeJobOf(IngestionJobType.FUNDAMENTALS, ex.code()).orElse(null));
    }

    static Exchange exchange(String code) {
        try {
            return Exchange.of(code);
        } catch (Exchange.UnsupportedExchangeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported exchange", e.getMessage());
        }
    }
}
