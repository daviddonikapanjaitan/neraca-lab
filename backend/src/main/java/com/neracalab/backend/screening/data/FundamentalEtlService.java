package com.neracalab.backend.screening.data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.screening.ScreeningProperties;
import com.neracalab.backend.screening.data.FundamentalRepository.StaleListing;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.ListingQuote;

/**
 * Daily ETL of the screening data ([Daily ETL] -> PostgreSQL): the market data of every listing of
 * the exchange (Yahoo screener, a few requests) and the fundamentals of the listings whose stored
 * fundamentals are older than {@code neracalab.screening.etl.fundamentals-max-age} (two requests
 * per stock). No AI involved.
 */
@Service
public class FundamentalEtlService {

    private static final Logger log = LoggerFactory.getLogger(FundamentalEtlService.class);
    private static final int MAX_ERRORS = 20;

    /** Progress of a fundamentals refresh: {@code done} of {@code total} stocks, the current one. */
    @FunctionalInterface
    public interface Progress {
        void update(int done, int total, String ticker);

        Progress NONE = (done, total, ticker) -> {
        };
    }

    /**
     * Outcome of a refresh.
     *
     * @param rateLimited Yahoo answered HTTP 429; the remaining stocks were not refreshed
     * @param interrupted the thread was interrupted (shutdown); the remaining stocks were not refreshed
     */
    public record RefreshResult(int requested, int refreshed, int failed, boolean rateLimited, boolean interrupted,
                                List<String> errors) {

        public boolean complete() {
            return !rateLimited && !interrupted && failed == 0;
        }
    }

    public record UniverseResult(Exchange exchange, LocalDate snapshotDate, int listings) {
    }

    private final YahooFundamentalsClient yahoo;
    private final FundamentalRepository repository;
    private final ScreeningProperties properties;

    public FundamentalEtlService(YahooFundamentalsClient yahoo, FundamentalRepository repository,
                                 ScreeningProperties properties) {
        this.yahoo = yahoo;
        this.repository = repository;
        this.properties = properties;
    }

    /** Market data of every listing, stored as today's snapshot (exchange time zone). */
    public UniverseResult syncUniverse(Exchange exchange) {
        List<ListingQuote> quotes = yahoo.universe(exchange);
        if (quotes.isEmpty()) {
            throw new PriceProviderException("Yahoo Finance returned no " + exchange.code() + " listings");
        }
        LocalDate today = LocalDate.now(exchange.zone());
        repository.saveUniverse(exchange, quotes, today);
        return new UniverseResult(exchange, today, quotes.size());
    }

    /** Fundamentals older than this are refreshed. */
    public java.time.Duration fundamentalsMaxAge() {
        return properties.etl().fundamentalsMaxAge();
    }

    /** Whether today's market data of the exchange is stored already. */
    public boolean universeIsCurrent(Exchange exchange) {
        LocalDate today = LocalDate.now(exchange.zone());
        return repository.latestSnapshotDate(exchange).map(d -> !d.isBefore(today)).orElse(false);
    }

    /**
     * Refreshes the fundamentals older than the configured maximum age, largest companies first.
     *
     * @param tickers only these tickers (null: every active listing)
     */
    public RefreshResult refreshFundamentals(Exchange exchange, List<String> tickers, Progress progress) {
        return refreshFundamentals(exchange, tickers, Instant.now().minus(properties.etl().fundamentalsMaxAge()), progress);
    }

    /**
     * Refreshes the fundamentals fetched before {@code fetchedBefore} (now: all), largest companies first.
     *
     * @param tickers only these tickers (null: every active listing)
     */
    public RefreshResult refreshFundamentals(Exchange exchange, List<String> tickers, Instant fetchedBefore,
                                             Progress progress) {
        List<StaleListing> stale = repository.staleFundamentals(exchange, fetchedBefore, tickers);
        int refreshed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < stale.size(); i++) {
            StaleListing listing = stale.get(i);
            if (Thread.currentThread().isInterrupted()) {
                return new RefreshResult(stale.size(), refreshed, failed, false, true, errors);
            }
            progress.update(i, stale.size(), listing.ticker());
            try {
                repository.saveFundamentals(listing, yahoo.fundamentals(exchange, listing.ticker()));
                refreshed++;
            } catch (RateLimitedException e) {
                log.warn("Fundamentals refresh stopped by a Yahoo rate limit after {} of {} stocks", i, stale.size());
                errors.add(listing.ticker() + ": " + e.getMessage());
                return new RefreshResult(stale.size(), refreshed, failed, true, false, errors);
            } catch (PriceProviderException e) {
                if (Thread.currentThread().isInterrupted()) {
                    return new RefreshResult(stale.size(), refreshed, failed, false, true, errors);
                }
                failed++;
                repository.fundamentalsFailed(listing, e.getMessage());
                if (errors.size() < MAX_ERRORS) {
                    errors.add(listing.ticker() + ": " + e.getMessage());
                }
                log.info("fundamentals of {} not refreshed: {}", listing.ticker(), e.getMessage());
            }
        }
        progress.update(stale.size(), stale.size(), null);
        return new RefreshResult(stale.size(), refreshed, failed, false, false, errors);
    }
}
