package com.neracalab.backend.market.controller;

import java.net.URI;
import java.util.List;

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

import com.neracalab.backend.auth.AuthenticatedUser;
import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.RequiresPermission;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.market.Market;
import com.neracalab.backend.market.Market.UnsupportedMarketException;
import com.neracalab.backend.market.MarketService;
import com.neracalab.backend.market.MarketService.InstrumentNotFoundException;
import com.neracalab.backend.price.PriceIngestionJob;
import com.neracalab.backend.price.PriceIngestionQueue.Submission;

/**
 * Forex and commodity prices (Ingestion › Price Ingestion with the market Forex or Commodities). The jobs run in the
 * price queue beside the companies and are listed with them ({@code ingestion_job} type PRICE, exchange = the market).
 * <ul>
 *   <li>{@code GET /api/v1/markets} - every market with its instruments and how far their bars reach</li>
 *   <li>{@code GET /api/v1/markets/{market}} - one market (FOREX, COMMODITY)</li>
 *   <li>{@code POST /api/v1/markets/{market}/ingestions?code=USDIDR[&full=true]} - queue one instrument; 202 with the
 *       new job, or 200 with the job already queued / running</li>
 *   <li>{@code POST /api/v1/markets/{market}/updates} - queue every instrument whose bars end before its last completed
 *       day (only the missing days are fetched) (202)</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1/markets", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresPermission(Permission.INGESTION)
public class MarketController {

    private final MarketService markets;

    public MarketController(MarketService markets) {
        this.markets = markets;
    }

    @GetMapping
    public List<MarketService.Status> markets() {
        return markets.markets();
    }

    @GetMapping("/{market}")
    public MarketService.Status market(@PathVariable("market") String market) {
        return markets.status(Market.of(market));
    }

    /** @param full re-fetch the whole history instead of starting from the latest stored day */
    @PostMapping("/{market}/ingestions")
    public ResponseEntity<PriceIngestionJob.View> ingest(@PathVariable("market") String market,
                                                         @RequestParam(name = "code") String code,
                                                         @RequestParam(name = "full", defaultValue = "false") boolean full,
                                                         AuthenticatedUser user) {
        Submission submission = markets.submit(Market.of(market), code, full, Requester.of(user));
        PriceIngestionJob.View view = submission.job().view();
        return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/prices/ingestions/" + view.id()))
                .body(view);
    }

    @PostMapping("/{market}/updates")
    public ResponseEntity<MarketService.Update> updateAll(@PathVariable("market") String market, AuthenticatedUser user) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(markets.updateAll(Market.of(market), Requester.of(user)));
    }

    @ExceptionHandler(UnsupportedMarketException.class)
    ProblemDetail unsupportedMarket(UnsupportedMarketException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Unsupported market");
        problem.setProperty("supportedMarkets", Market.codes());
        return problem;
    }

    @ExceptionHandler(InstrumentNotFoundException.class)
    ProblemDetail notFound(InstrumentNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Instrument not found");
        return problem;
    }
}
