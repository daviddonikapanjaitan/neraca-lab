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
import com.neracalab.backend.screening.data.ListingPriceRepository;
import com.neracalab.backend.screening.data.ListingPricesQueue;
import com.neracalab.backend.technical.screening.TechnicalScreeningProperties;

/**
 * The price history of the screening universe (daily bars of every listing; the technical screening of a tier).
 * <ul>
 *   <li>{@code POST /api/v1/listing-prices/ingestions?exchange=IDX[&full=true]} - queue a run; 202 with the new job,
 *       or 200 with the run already active for the exchange (INGESTION)</li>
 *   <li>{@code GET /api/v1/listing-prices/status?exchange=IDX} - how much is stored and the active run (INGESTION or
 *       SCREENING)</li>
 * </ul>
 * Progress and result of a run: {@code GET /api/v1/ingestions/{id}} (type LISTING_PRICES).
 */
@RestController
@RequestMapping(path = "/api/v1/listing-prices", produces = MediaType.APPLICATION_JSON_VALUE)
public class ListingPricesController {

    /**
     * @param listings     active listings of the exchange
     * @param withBars     of them with stored bars
     * @param latestDate   the newest stored bar
     * @param rows         stored bars
     * @param historyYears how far back the first load of a listing reaches
     * @param activeJobId  the queued / running run of the exchange, if any
     */
    public record StatusResponse(String exchange, int listings, int withBars, String latestDate, long rows,
                                 int historyYears, UUID activeJobId) {
    }

    private final ListingPricesQueue queue;
    private final ListingPriceRepository repository;
    private final IngestionJobRepository jobs;
    private final TechnicalScreeningProperties properties;

    public ListingPricesController(ListingPricesQueue queue, ListingPriceRepository repository, IngestionJobRepository jobs,
                                   TechnicalScreeningProperties properties) {
        this.queue = queue;
        this.repository = repository;
        this.jobs = jobs;
        this.properties = properties;
    }

    /** @param full fetch the whole history window of every listing again */
    @PostMapping("/ingestions")
    @RequiresPermission(Permission.INGESTION)
    public ResponseEntity<IngestionJob> submit(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
                                               @RequestParam(name = "full", defaultValue = "false") boolean full,
                                               AuthenticatedUser user) {
        ListingPricesQueue.Submission submission = queue.submit(FundamentalsController.exchange(exchange), full,
                Requester.of(user));
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/ingestions/" + submission.job().id()))
                .body(submission.job());
    }

    @GetMapping("/status")
    @RequiresPermission({Permission.INGESTION, Permission.SCREENING})
    public StatusResponse status(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange) {
        Exchange ex = FundamentalsController.exchange(exchange);
        ListingPriceRepository.Status s = repository.status(ex);
        return new StatusResponse(ex.code(), s.listings(), s.withBars(),
                s.latestDate() == null ? null : s.latestDate().toString(), s.rows(), properties.priceHistory().years(),
                jobs.activeJobOf(IngestionJobType.LISTING_PRICES, ex.code()).orElse(null));
    }
}
