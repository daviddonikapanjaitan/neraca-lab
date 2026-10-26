package com.neracalab.backend.screening.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.PriceIngestionService;
import com.neracalab.backend.price.PriceProperties;
import com.neracalab.backend.price.provider.DailyBar;
import com.neracalab.backend.price.provider.PriceHistory;
import com.neracalab.backend.price.provider.PriceProvider;
import com.neracalab.backend.price.provider.PriceProviderException;
import com.neracalab.backend.price.provider.RateLimitedException;
import com.neracalab.backend.price.provider.SymbolNotFoundException;
import com.neracalab.backend.technical.screening.TechnicalScreeningProperties;

/**
 * Loads the daily bars of the screening universe into {@code listing_price_daily}: one request per listing to the price
 * provider (Yahoo Finance by default, {@code neracalab.prices.provider}), paced like every other provider request.
 * <ul>
 *   <li>A listing without bars gets {@code price-history.years} of history; a stored one the days since its last bar
 *       (from 10 days before it, so late corrections are picked up). A listing whose bars reach the last completed
 *       trading day is skipped without a request.</li>
 *   <li>The bars are cleaned as the company prices are ({@link PriceIngestionService#clean}): no unfinished bar of
 *       today, no holiday placeholders.</li>
 *   <li>When the provider's close of an already stored day differs by more than 0.5% (a split or other re-adjustment
 *       of the history), the whole window is fetched again and replaces the stored bars.</li>
 *   <li>HTTP 429 stops the update (the rest waits for the next run); a listing the provider does not know is counted
 *       and skipped.</li>
 * </ul>
 */
@Service
public class ListingPriceService {

    private static final Logger log = LoggerFactory.getLogger(ListingPriceService.class);
    private static final int OVERLAP_DAYS = 10;
    private static final BigDecimal READJUSTED = new BigDecimal("0.005");
    private static final int MAX_ERRORS = 20;

    /** A listing to update. */
    public record Target(long listingId, String ticker) {
    }

    /** Progress callback: {@code done} listings of {@code total}, the one being loaded (null when finished). */
    @FunctionalInterface
    public interface Progress {
        void update(int done, int total, String ticker);
    }

    /**
     * What an update did.
     *
     * @param requested  listings looked at
     * @param loaded     listings fetched and stored
     * @param upToDate   listings whose bars already reach the last completed trading day (no request)
     * @param notFound   listings the provider does not know
     * @param failed     listings that failed otherwise
     * @param reAdjusted listings whose history was replaced after a provider re-adjustment
     * @param barsWritten bars inserted or changed
     */
    public record Result(int requested, int loaded, int upToDate, int notFound, int failed, int reAdjusted,
                         long barsWritten, boolean rateLimited, boolean interrupted, LocalDate lastCompletedDay,
                         List<String> errors) {
    }

    private final PriceProvider provider;
    private final PriceProperties prices;
    private final ListingPriceRepository repository;
    private final TechnicalScreeningProperties properties;

    public ListingPriceService(PriceProvider provider, PriceProperties prices, ListingPriceRepository repository,
                               TechnicalScreeningProperties properties) {
        this.provider = provider;
        this.prices = prices;
        this.repository = repository;
        this.properties = properties;
    }

    /** Every active listing of the exchange, by ticker. */
    public List<Target> targets(Exchange exchange) {
        return repository.listings(exchange).stream().map(l -> new Target(l.listingId(), l.ticker())).toList();
    }

    /** The last trading day whose bar is final now (today after the session close cutoff). */
    public LocalDate lastCompletedTradingDay(Exchange exchange) {
        return prices.lastCompletedTradingDay(ZonedDateTime.now(exchange.zone()));
    }

    /**
     * Brings the bars of {@code targets} up to the last completed trading day.
     *
     * @param full fetch the whole history window of every listing again (replacing what is stored)
     */
    public Result update(Exchange exchange, List<Target> targets, boolean full, Progress progress) {
        LocalDate today = LocalDate.now(exchange.zone());
        LocalDate lastCompleted = lastCompletedTradingDay(exchange);
        LocalDate historyFrom = today.minusYears(properties.priceHistory().years());
        int loaded = 0;
        int upToDate = 0;
        int notFound = 0;
        int failed = 0;
        int reAdjusted = 0;
        long written = 0;
        boolean rateLimited = false;
        boolean interrupted = false;
        List<String> errors = new ArrayList<>();
        int done = 0;
        for (Target target : targets) {
            if (Thread.currentThread().isInterrupted()) {
                interrupted = true;
                break;
            }
            Optional<LocalDate> last = repository.lastDate(target.listingId());
            if (!full && last.isPresent() && !last.get().isBefore(lastCompleted)) {
                upToDate++;
                done++;
                continue;
            }
            progress.update(done, targets.size(), target.ticker());
            try {
                boolean whole = full || last.isEmpty();
                LocalDate from = whole ? historyFrom : last.get().minusDays(OVERLAP_DAYS);
                List<DailyBar> bars = fetch(exchange, target.ticker(), from, today, lastCompleted);
                boolean replace = whole;
                if (!whole && reAdjusted(target.listingId(), from, bars)) {
                    log.info("{} {}: provider re-adjusted the history, fetching {} years again", exchange.code(),
                            target.ticker(), properties.priceHistory().years());
                    bars = fetch(exchange, target.ticker(), historyFrom, today, lastCompleted);
                    replace = true;
                    reAdjusted++;
                }
                written += repository.write(target.listingId(), bars, replace && !bars.isEmpty());
                loaded++;
            } catch (RateLimitedException e) {
                rateLimited = true;
                errors.add("Rate limit after " + loaded + " listings: " + e.getMessage());
                break;
            } catch (SymbolNotFoundException e) {
                notFound++;
                if (errors.size() < MAX_ERRORS) {
                    errors.add(target.ticker() + ": " + e.getMessage());
                }
            } catch (PriceProviderException e) {
                if (Thread.currentThread().isInterrupted()) {
                    interrupted = true;
                    break;
                }
                failed++;
                if (errors.size() < MAX_ERRORS) {
                    errors.add(target.ticker() + ": " + e.getMessage());
                }
            }
            done++;
        }
        progress.update(done, targets.size(), null);
        log.info("listing prices {}: {} listings, {} loaded, {} up to date, {} not found, {} failed, {} re-adjusted, "
                        + "{} bars written{}{}", exchange.code(), targets.size(), loaded, upToDate, notFound, failed,
                reAdjusted, written, rateLimited ? ", rate limited" : "", interrupted ? ", interrupted" : "");
        return new Result(targets.size(), loaded, upToDate, notFound, failed, reAdjusted, written, rateLimited, interrupted,
                lastCompleted, List.copyOf(errors));
    }

    private List<DailyBar> fetch(Exchange exchange, String ticker, LocalDate from, LocalDate to, LocalDate lastCompleted) {
        PriceHistory history = provider.fetch(exchange, ticker, from, to);
        return PriceIngestionService.clean(history.bars(), from, lastCompleted);
    }

    /** Whether a fetched close of a stored day differs from the stored one by more than 0.5%. */
    private boolean reAdjusted(long listingId, LocalDate from, List<DailyBar> bars) {
        Map<LocalDate, BigDecimal> stored = repository.closes(listingId, from);
        for (DailyBar bar : bars) {
            BigDecimal before = stored.get(bar.date());
            if (before != null && before.signum() > 0 && bar.close() != null
                    && bar.close().subtract(before).abs().divide(before, 6, RoundingMode.HALF_UP).compareTo(READJUSTED) > 0) {
                return true;
            }
        }
        return false;
    }
}
