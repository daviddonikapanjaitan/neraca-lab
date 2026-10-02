package com.neracalab.backend.price;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.price.PriceDailyRepository.CompanyRef;
import com.neracalab.backend.price.PriceDailyRepository.StoredClose;
import com.neracalab.backend.price.ValuationRepository.Refreshed;
import com.neracalab.backend.price.provider.DailyBar;
import com.neracalab.backend.price.provider.PriceHistory;
import com.neracalab.backend.price.provider.PriceProvider;
import com.neracalab.backend.price.provider.PriceProviderException;

/**
 * Ingests the daily prices of one company and recalculates its valuation. Called only by the
 * {@link PriceIngestionQueue} worker, never from a web request.
 * <ol>
 *   <li>Range: from the latest stored trading day (MAX(trading_date), re-fetched as an overlap check)
 *       to today; the full history ({@code full-history-from}) when nothing is stored or
 *       {@code full=true}. No request at all when the latest completed trading day is already stored.</li>
 *   <li>One provider request. Bars are cleaned: no unfinished bar of today (before
 *       {@code session-close-cutoff}), no holiday placeholders (no close), no zero-volume rows that
 *       only repeat a price, no rows the price_daily checks would reject; one bar per date.</li>
 *   <li>Overlap check: when close or adjusted close of the re-fetched latest day differ from the stored
 *       ones, the provider has re-adjusted its history (dividend or split after that day), so the full
 *       history is fetched once more (one extra request) and every stored day is corrected.</li>
 *   <li>One transaction: upsert price_daily, then {@link ValuationRepository#refresh} (market_snapshot,
 *       valuation_snapshot, VALUATION metrics of the company).</li>
 * </ol>
 */
@Service
public class PriceIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PriceIngestionService.class);

    private final PriceProvider provider;
    private final PriceDailyRepository prices;
    private final ValuationRepository valuations;
    private final TransactionTemplate transaction;
    private final PriceProperties properties;
    private final Clock clock;

    @Autowired
    public PriceIngestionService(PriceProvider provider, PriceDailyRepository prices, ValuationRepository valuations,
                                 TransactionTemplate transaction, PriceProperties properties) {
        this(provider, prices, valuations, transaction, properties, Clock.systemUTC());
    }

    PriceIngestionService(PriceProvider provider, PriceDailyRepository prices, ValuationRepository valuations,
                          TransactionTemplate transaction, PriceProperties properties, Clock clock) {
        this.provider = provider;
        this.prices = prices;
        this.valuations = valuations;
        this.transaction = transaction;
        this.properties = properties;
        this.clock = clock;
    }

    public String providerName() {
        return provider.name();
    }

    /**
     * Outcome of one ingestion.
     *
     * @param requests          provider requests made (0 = already up to date, 2 = history was re-fetched)
     * @param requestedFrom     first date requested from the provider ({@code null} without request)
     * @param requestedTo       last date requested
     * @param fullHistory       the full history was fetched (first ingestion, full=true or re-adjusted history)
     * @param reAdjusted        the overlap check found re-adjusted prices, so the full history was re-fetched
     * @param barsReceived      bars in the provider response(s) used
     * @param barsSkipped       bars dropped by the cleaning rules
     * @param inserted          new price_daily rows
     * @param updated           changed price_daily rows
     * @param unchanged         bars equal to the stored row
     * @param latestTradingDate MAX(trading_date) after the ingestion
     * @param valuation         rows written by the valuation refresh
     */
    public record Result(String provider, int requests, LocalDate requestedFrom, LocalDate requestedTo,
                         boolean fullHistory, boolean reAdjusted, int barsReceived, int barsSkipped,
                         int inserted, int updated, int unchanged, LocalDate latestTradingDate,
                         Refreshed valuation) {
    }

    public Result ingest(CompanyRef company, boolean full) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(company.exchange().zone()));
        LocalDate today = now.toLocalDate();
        LocalDate lastCompletedDay = lastCompletedTradingDay(now);
        Optional<LocalDate> latestStored = prices.latestTradingDate(company.companyId());

        if (!full && latestStored.isPresent() && !latestStored.get().isBefore(lastCompletedDay)) {
            log.info("{} {}: prices up to date ({}), no request", company.exchange(), company.ticker(),
                    latestStored.get());
            return write(company, new Fetch(0, null, null, false, false, 0, 0, List.of()));
        }

        boolean fullHistory = full || latestStored.isEmpty();
        LocalDate from = fullHistory ? properties.fullHistoryFromDate() : latestStored.get();
        Fetch fetch = fetch(company, from, today, lastCompletedDay, 1, fullHistory, false);

        if (!fullHistory && isReAdjusted(company, latestStored.get(), fetch.bars)) {
            log.info("{} {}: provider re-adjusted prices on or before {}, re-fetching the full history",
                    company.exchange(), company.ticker(), latestStored.get());
            fetch = fetch(company, properties.fullHistoryFromDate(), today, lastCompletedDay, 2, true, true);
        }
        return write(company, fetch);
    }

    /** Bars of one or two provider requests plus what the result reports about them. */
    private record Fetch(int requests, LocalDate from, LocalDate to, boolean fullHistory, boolean reAdjusted,
                         int received, int skipped, List<DailyBar> bars) {
    }

    private Fetch fetch(CompanyRef company, LocalDate from, LocalDate to, LocalDate lastCompletedDay,
                        int requests, boolean fullHistory, boolean reAdjusted) {
        PriceHistory history = provider.fetch(company.exchange(), company.ticker(), from, to);
        if (history.currency() != null && company.currency() != null
                && !history.currency().equalsIgnoreCase(company.currency())) {
            throw new PriceProviderException(history.symbol() + " is quoted in " + history.currency()
                    + " but " + company.ticker() + " reports in " + company.currency() + "; nothing stored");
        }
        List<DailyBar> bars = clean(history.bars(), from, lastCompletedDay);
        return new Fetch(requests, from, to, fullHistory, reAdjusted, history.bars().size(),
                history.bars().size() - bars.size(), bars);
    }

    private Result write(CompanyRef company, Fetch fetch) {
        List<DailyBar> bars = fetch.bars;
        return transaction.execute(status -> {
            long companyId = company.companyId();
            int inserted = 0;
            int changed = 0;
            if (!bars.isEmpty()) {
                Set<LocalDate> stored = prices.tradingDates(companyId, bars.getFirst().date(), bars.getLast().date());
                inserted = (int) bars.stream().filter(b -> !stored.contains(b.date())).count();
                changed = prices.upsert(companyId, bars);
            }
            Refreshed valuation = valuations.refresh(companyId);
            LocalDate latest = prices.latestTradingDate(companyId).orElse(null);
            Result result = new Result(provider.name(), fetch.requests, fetch.from, fetch.to, fetch.fullHistory,
                    fetch.reAdjusted, fetch.received, fetch.skipped, inserted, changed - inserted,
                    bars.size() - changed, latest, valuation);
            log.info("{} {}: {}", company.exchange(), company.ticker(), result);
            return result;
        });
    }

    /**
     * The overlap day (latest stored day) came back with a different close or adjusted close:
     * the provider has re-based its history. A missing overlap bar proves nothing.
     */
    private boolean isReAdjusted(CompanyRef company, LocalDate overlapDay, List<DailyBar> bars) {
        Optional<DailyBar> fetched = bars.stream().filter(b -> b.date().equals(overlapDay)).findFirst();
        Optional<StoredClose> stored = prices.storedClose(company.companyId(), overlapDay);
        if (fetched.isEmpty() || stored.isEmpty()) {
            return false;
        }
        return differs(fetched.get().close(), stored.get().close())
                || differs(fetched.get().adjustedClose(), stored.get().adjustedClose());
    }

    /** Compares numerically (2280.0000 equals 2280.00000000); a value that was not delivered is not a change. */
    static boolean differs(BigDecimal fetched, BigDecimal stored) {
        if (fetched == null) {
            return false;
        }
        return stored == null || fetched.compareTo(stored) != 0;
    }

    /**
     * Today when the session has closed (exchange-local time at or after the cutoff), otherwise the
     * previous day; weekends step back to Friday. Exchange holidays are not known here: a holiday
     * simply returns no bar.
     */
    LocalDate lastCompletedTradingDay(ZonedDateTime now) {
        LocalDate day = now.toLocalTime().isBefore(properties.sessionCloseCutoffTime())
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return day;
    }

    /**
     * Keeps the bars worth storing, oldest first, one per date (the last one wins).
     * Dropped: dates outside {@code from..lastCompletedDay} (unfinished bar of today included),
     * no close (holiday placeholder), zero volume with open = high = low = close (holiday row carrying
     * the previous close), and rows the price_daily checks reject (close &lt;= 0, high &lt; low, volume &lt; 0).
     */
    static List<DailyBar> clean(List<DailyBar> bars, LocalDate from, LocalDate lastCompletedDay) {
        Map<LocalDate, DailyBar> byDate = new LinkedHashMap<>();
        for (DailyBar bar : bars) {
            if (bar.date() == null || bar.date().isBefore(from) || bar.date().isAfter(lastCompletedDay)) {
                continue;
            }
            if (bar.close() == null || bar.close().signum() <= 0) {
                continue;
            }
            if (bar.high() != null && bar.low() != null && bar.high().compareTo(bar.low()) < 0) {
                continue;
            }
            if (bar.volume() != null && bar.volume() < 0) {
                continue;
            }
            if (bar.volume() != null && bar.volume() == 0 && isFlat(bar)) {
                continue;
            }
            byDate.remove(bar.date());
            byDate.put(bar.date(), bar);
        }
        List<DailyBar> cleaned = new ArrayList<>(byDate.values());
        cleaned.sort((a, b) -> a.date().compareTo(b.date()));
        return cleaned;
    }

    private static boolean isFlat(DailyBar bar) {
        BigDecimal close = bar.close();
        return (bar.open() == null || bar.open().compareTo(close) == 0)
                && (bar.high() == null || bar.high().compareTo(close) == 0)
                && (bar.low() == null || bar.low().compareTo(close) == 0);
    }
}
