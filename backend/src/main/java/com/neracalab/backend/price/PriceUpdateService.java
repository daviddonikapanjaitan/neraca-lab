package com.neracalab.backend.price;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.price.IndexIngestion.IndexRef;
import com.neracalab.backend.price.PriceDailyRepository.CompanyLatest;
import com.neracalab.backend.price.PriceIngestionQueue.Submission;

/**
 * Brings the prices of a whole exchange up to date (Ingestion › Price Ingestion, "Update all companies"; the evening
 * schedule): every active company whose latest stored day is before the last completed trading day is queued, and
 * only the days from its latest stored day to that day are fetched (a company without prices gets its whole history).
 * Companies already up to date are not queued. The exchange's index (IHSG) can be brought up to date with them.
 */
@Service
public class PriceUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PriceUpdateService.class);

    /**
     * What is stored for an exchange, against the last completed trading day.
     *
     * @param lastCompletedTradingDay the latest day whose bar is final (today after the session close cutoff, else the
     *                                trading day before; weekends step back to Friday)
     * @param companies               active companies of the exchange
     * @param upToDate                companies whose prices reach the last completed trading day
     * @param behind                  companies with prices that end before it
     * @param withoutPrices           companies without any stored price
     * @param oldestLatestDate        the earliest latest stored day among the companies with prices
     * @param newestLatestDate        the latest stored day of any company
     * @param index                   the exchange's index ({@code null}: none configured)
     * @param pending                 jobs waiting in the price queue
     */
    public record Status(String exchange, LocalDate lastCompletedTradingDay, int companies, int upToDate, int behind,
                         int withoutPrices, LocalDate oldestLatestDate, LocalDate newestLatestDate, IndexStatus index,
                         String provider, int pending) {
    }

    /**
     * The stored bars of an exchange's index against the last completed trading day.
     *
     * @param upToDate the index and the indexes stored with it ({@code indexes}) all reach the last completed day
     * @param indexes  every index its job stores (IHSG first, then LQ45), each with its own range and state
     */
    public record IndexStatus(String code, String symbol, String name, String source, int rows, LocalDate firstDate,
                              LocalDate lastDate, boolean upToDate, List<IndexEntry> indexes) {
    }

    /** One index of an index job: its stored bars against the last completed trading day. */
    public record IndexEntry(String code, String symbol, String name, int rows, LocalDate firstDate, LocalDate lastDate,
                             boolean upToDate) {
    }

    /**
     * Outcome of an update of a whole exchange.
     *
     * @param queued        companies queued now (their missing days only; a company without prices: its history)
     * @param alreadyActive companies that already had a queued or running job (it is kept)
     * @param upToDate      companies skipped: their prices reach the last completed trading day
     * @param withoutPrices of the queued, companies without prices (their whole history is fetched)
     * @param index         the index's outcome ({@code null}: not requested or none configured)
     * @param firstJob      the first job queued ({@code null}: none), for the jobs table to follow
     */
    public record Update(String exchange, LocalDate lastCompletedTradingDay, int companies, int queued,
                         int alreadyActive, int upToDate, int withoutPrices, IndexUpdate index,
                         PriceIngestionJob.View firstJob) {
    }

    /** What happened to the index: QUEUED, ALREADY_ACTIVE (its job is kept) or UP_TO_DATE (nothing queued). */
    public record IndexUpdate(String code, String outcome, PriceIngestionJob.View job) {
    }

    /** The exchange has no index configured. */
    public static class NoIndexException extends RuntimeException {
        public NoIndexException(Exchange exchange) {
            super("No index is configured for " + exchange.code());
        }
    }

    private final PriceIngestionQueue queue;
    private final PriceIngestionService service;
    private final PriceDailyRepository prices;
    private final IndexIngestion indexIngestion;

    public PriceUpdateService(PriceIngestionQueue queue, PriceIngestionService service, PriceDailyRepository prices,
                              IndexIngestion indexIngestion) {
        this.queue = queue;
        this.service = service;
        this.prices = prices;
        this.indexIngestion = indexIngestion;
    }

    public Status status(Exchange exchange) {
        LocalDate lastCompleted = service.lastCompletedTradingDay(exchange);
        List<CompanyLatest> companies = prices.activeCompaniesWithLatest(exchange);
        int upToDate = 0;
        int behind = 0;
        int without = 0;
        LocalDate oldest = null;
        LocalDate newest = null;
        for (CompanyLatest c : companies) {
            LocalDate latest = c.latestTradingDate();
            if (latest == null) {
                without++;
                continue;
            }
            if (latest.isBefore(lastCompleted)) {
                behind++;
            } else {
                upToDate++;
            }
            oldest = oldest == null || latest.isBefore(oldest) ? latest : oldest;
            newest = newest == null || latest.isAfter(newest) ? latest : newest;
        }
        IndexStatus index = indexIngestion.index(exchange).map(i -> indexStatus(i, lastCompleted)).orElse(null);
        return new Status(exchange.code(), lastCompleted, companies.size(), upToDate, behind, without, oldest, newest,
                index, queue.providerName(), queue.pendingCount());
    }

    private IndexStatus indexStatus(IndexRef index, LocalDate lastCompleted) {
        IndexIngestion.Stored stored = indexIngestion.stored(index);
        List<IndexEntry> indexes = new java.util.ArrayList<>();
        for (IndexRef ref : java.util.stream.Stream.concat(java.util.stream.Stream.of(index),
                indexIngestion.companions(index).stream()).toList()) {
            IndexIngestion.Stored s = ref == index ? stored : indexIngestion.stored(ref);
            indexes.add(new IndexEntry(ref.code(), ref.symbol(), ref.name(), s.rows(), s.firstDate(), s.lastDate(),
                    s.lastDate() != null && !s.lastDate().isBefore(lastCompleted)));
        }
        return new IndexStatus(index.code(), index.symbol(), index.name(), indexIngestion.sourceName(), stored.rows(),
                stored.firstDate(), stored.lastDate(), indexIngestion.upToDate(index, lastCompleted), List.copyOf(indexes));
    }

    /**
     * Queues every active company of the exchange whose prices end before the last completed trading day, and, when
     * {@code includeIndex}, the exchange's index unless it is up to date.
     *
     * @param requestedBy recorded as the requester of every job ({@code null}: scheduled run)
     */
    public Update updateAll(Exchange exchange, boolean includeIndex, Requester requestedBy) {
        LocalDate lastCompleted = service.lastCompletedTradingDay(exchange);
        List<CompanyLatest> companies = prices.activeCompaniesWithLatest(exchange);
        int queued = 0;
        int active = 0;
        int upToDate = 0;
        int without = 0;
        PriceIngestionJob.View first = null;
        for (CompanyLatest c : companies) {
            LocalDate latest = c.latestTradingDate();
            if (latest != null && !latest.isBefore(lastCompleted)) {
                upToDate++;
                continue;
            }
            Submission submission = queue.submit(c.company(), false, requestedBy);
            if (submission.created()) {
                queued++;
                if (latest == null) {
                    without++;
                }
                if (first == null) {
                    first = submission.job().view();
                }
            } else {
                active++;
            }
        }
        IndexUpdate index = null;
        if (includeIndex) {
            Optional<IndexRef> ref = indexIngestion.index(exchange);
            if (ref.isPresent()) {
                index = updateIndex(ref.get(), lastCompleted, requestedBy);
                if (first == null && index.job() != null && index.outcome().equals("QUEUED")) {
                    first = index.job();
                }
            }
        }
        log.info("Price update of {} up to {}: {} companies, {} queued ({} without prices), {} already active, "
                        + "{} up to date; index {}", exchange.code(), lastCompleted, companies.size(), queued, without,
                active, upToDate, index == null ? "not requested" : index.outcome());
        return new Update(exchange.code(), lastCompleted, companies.size(), queued, active, upToDate, without, index,
                first);
    }

    private IndexUpdate updateIndex(IndexRef index, LocalDate lastCompleted, Requester requestedBy) {
        if (indexIngestion.upToDate(index, lastCompleted)) {
            return new IndexUpdate(index.code(), "UP_TO_DATE", null);
        }
        Submission submission = queue.submitIndex(index, false, requestedBy);
        return new IndexUpdate(index.code(), submission.created() ? "QUEUED" : "ALREADY_ACTIVE", submission.job().view());
    }

    /** Queues an ingestion of the exchange's index (also when it is up to date: the job then makes no request). */
    public Submission submitIndex(Exchange exchange, boolean full, Requester requestedBy) {
        IndexRef index = indexIngestion.index(exchange).orElseThrow(() -> new NoIndexException(exchange));
        return queue.submitIndex(index, full, requestedBy);
    }
}
