package com.neracalab.backend.price.controller;

import java.net.URI;

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

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Exchange.UnsupportedExchangeException;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.price.PriceIngestionJob;
import com.neracalab.backend.price.PriceIngestionQueue.Submission;
import com.neracalab.backend.price.PriceUpdateService;
import com.neracalab.backend.price.PriceUpdateService.NoIndexException;

/**
 * Prices of a whole exchange and its index (Ingestion › Price Ingestion, right-hand cards). The jobs run in the price
 * queue like the ingestion of one company ({@code /api/v1/prices/ingestions}) and are listed with them.
 * <ul>
 *   <li>{@code GET /api/v1/prices/status?exchange=IDX} - how far the stored prices and the index reach, against the
 *       last completed trading day</li>
 *   <li>{@code POST /api/v1/prices/updates?exchange=IDX[&index=true]} - queue every active company whose prices end
 *       before the last completed trading day (only the missing days are fetched), and the index (202)</li>
 *   <li>{@code POST /api/v1/prices/index-ingestions?exchange=IDX[&full=true]} - queue the index alone (IHSG from Yahoo
 *       Finance); 202 with the new job, or 200 with the job already queued / running</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/prices", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class PriceUpdateController {

    private final PriceUpdateService updates;

    public PriceUpdateController(PriceUpdateService updates) {
        this.updates = updates;
    }

    @GetMapping("/status")
    public PriceUpdateService.Status status(@RequestParam(name = "exchange", defaultValue = "IDX") String exchange) {
        return updates.status(Exchange.of(exchange));
    }

    /** @param index also bring the exchange's index (IHSG) up to date */
    @PostMapping("/updates")
    public ResponseEntity<PriceUpdateService.Update> updateAll(
            @RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
            @RequestParam(name = "index", defaultValue = "true") boolean index,
            AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(updates.updateAll(Exchange.of(exchange), index, Requester.of(user)));
    }

    /** @param full re-fetch the whole history instead of starting from the latest stored day */
    @PostMapping("/index-ingestions")
    public ResponseEntity<PriceIngestionJob.View> ingestIndex(
            @RequestParam(name = "exchange", defaultValue = "IDX") String exchange,
            @RequestParam(name = "full", defaultValue = "false") boolean full,
            AuthenticatedUser user) {
        Submission submission = updates.submitIndex(Exchange.of(exchange), full, Requester.of(user));
        PriceIngestionJob.View view = submission.job().view();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/prices/ingestions/" + view.id()))
                .body(view);
    }

    @ExceptionHandler(UnsupportedExchangeException.class)
    ProblemDetail unsupportedExchange(UnsupportedExchangeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported exchange");
        problem.setProperty("supportedExchanges", e.supported());
        return problem;
    }

    @ExceptionHandler(NoIndexException.class)
    ProblemDetail noIndex(NoIndexException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("No index");
        return problem;
    }
}
