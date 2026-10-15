package com.neracalab.backend.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.job.Requester;
import com.neracalab.backend.market.InstrumentRepository.Instrument;
import com.neracalab.backend.price.InstrumentIngestion;
import com.neracalab.backend.price.InstrumentIngestion.InstrumentRef;
import com.neracalab.backend.price.PriceIngestionJob;
import com.neracalab.backend.price.PriceIngestionQueue;
import com.neracalab.backend.price.PriceIngestionQueue.Submission;

/**
 * Prices of the Forex pairs and commodities (Ingestion › Price Ingestion with the market Forex or Commodities): what is
 * stored, the ingestion of one instrument, and "Update all to today" - every instrument whose bars end before its last
 * completed day is queued and only its missing days are fetched. The jobs run in the price queue beside the companies.
 */
@Service
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);

    /**
     * What is stored of one instrument.
     *
     * @param lastCompleted the latest bar that is final now (the weekday before today; a monthly one: the month before)
     * @param upToDate      the stored bars reach {@code lastCompleted}
     */
    public record InstrumentStatus(String code, String name, String symbol, String source, String unit,
                                   String quoteCurrency, String frequency, String description, int rows,
                                   LocalDate firstDate, LocalDate lastDate, LocalDate lastCompleted, boolean upToDate) {
    }

    /**
     * A market with its instruments.
     *
     * @param itemLabel what an instrument is called in a dropdown: Currency, Commodity
     * @param upToDate  instruments whose bars reach their last completed day
     * @param behind    instruments with bars that end before it
     * @param pending   jobs waiting in the price queue (all kinds)
     */
    public record Status(String market, String name, String itemLabel, String itemsLabel, int instruments, int upToDate,
                         int behind, int withoutPrices, List<InstrumentStatus> items, int pending) {
    }

    /**
     * Outcome of "Update all" of a market.
     *
     * @param queued        instruments queued now (their missing days; one without bars: its history)
     * @param alreadyActive instruments that already had a queued or running job (it is kept)
     * @param upToDate      instruments skipped: their bars reach their last completed day
     * @param withoutPrices of the queued, instruments without bars
     * @param firstJob      the first job queued ({@code null}: none), for the jobs table to follow
     */
    public record Update(String market, int instruments, int queued, int alreadyActive, int upToDate, int withoutPrices,
                         PriceIngestionJob.View firstJob) {
    }

    /** The market has no instrument of that code. */
    public static class InstrumentNotFoundException extends RuntimeException {
        public InstrumentNotFoundException(Market market, String code) {
            super("No instrument '" + code + "' in " + market.displayName());
        }
    }

    private final InstrumentRepository repository;
    private final InstrumentIngestion ingestion;
    private final PriceIngestionQueue queue;

    public MarketService(InstrumentRepository repository, InstrumentIngestion ingestion, PriceIngestionQueue queue) {
        this.repository = repository;
        this.ingestion = ingestion;
        this.queue = queue;
    }

    /** Every market with its instruments. */
    public List<Status> markets() {
        return Arrays.stream(Market.values()).map(this::status).toList();
    }

    public Status status(Market market) {
        List<InstrumentStatus> items = new ArrayList<>();
        int upToDate = 0;
        int behind = 0;
        int without = 0;
        for (Instrument i : repository.instruments(market)) {
            LocalDate lastCompleted = ingestion.lastCompleted(InstrumentIngestionService.ref(i));
            boolean current = i.lastDate() != null && !i.lastDate().isBefore(lastCompleted);
            if (i.lastDate() == null) {
                without++;
            } else if (current) {
                upToDate++;
            } else {
                behind++;
            }
            items.add(new InstrumentStatus(i.code(), i.name(), i.symbol(), i.source(), i.unit(), i.quoteCurrency(),
                    i.frequency(), i.description(), i.rows(), i.firstDate(), i.lastDate(), lastCompleted, current));
        }
        return new Status(market.code(), market.displayName(), market.itemLabel(), market.itemsLabel(), items.size(),
                upToDate, behind, without, items, queue.pendingCount());
    }

    /** Queues the ingestion of one instrument (also when it is up to date: the job then makes no request). */
    public Submission submit(Market market, String code, boolean full, Requester requestedBy) {
        String normalized = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT).replace("/", "");
        InstrumentRef ref = ingestion.instrument(market.code(), normalized)
                .orElseThrow(() -> new InstrumentNotFoundException(market, normalized));
        return queue.submitInstrument(ref, full, requestedBy);
    }

    /**
     * Queues every instrument of the market whose bars end before its last completed day.
     *
     * @param requestedBy recorded as the requester of every job ({@code null}: scheduled run)
     */
    public Update updateAll(Market market, Requester requestedBy) {
        int queued = 0;
        int active = 0;
        int upToDate = 0;
        int without = 0;
        PriceIngestionJob.View first = null;
        List<Instrument> instruments = repository.instruments(market);
        for (Instrument i : instruments) {
            InstrumentRef ref = InstrumentIngestionService.ref(i);
            if (i.lastDate() != null && !i.lastDate().isBefore(ingestion.lastCompleted(ref))) {
                upToDate++;
                continue;
            }
            Submission submission = queue.submitInstrument(ref, false, requestedBy);
            if (submission.created()) {
                queued++;
                if (i.lastDate() == null) {
                    without++;
                }
                if (first == null) {
                    first = submission.job().view();
                }
            } else {
                active++;
            }
        }
        log.info("Price update of {}: {} instruments, {} queued ({} without prices), {} already active, {} up to date",
                market, instruments.size(), queued, without, active, upToDate);
        return new Update(market.code(), instruments.size(), queued, active, upToDate, without, first);
    }
}
