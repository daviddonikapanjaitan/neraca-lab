package com.neracalab.backend.technical;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.IndexIngestion;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.price.PriceProperties;
import com.neracalab.backend.technical.TechnicalRepository.IndexStats;
import com.neracalab.backend.technical.engine.Candle;

/**
 * The IHSG bars ({@code neracalab.technical.index}: Yahoo Finance {@code ^JKSE}) into {@code index_daily}: run by the
 * price queue (Ingestion › Price Ingestion, the update of all prices of an exchange) and by the technical refresh.
 * <ul>
 *   <li>From the latest stored day (re-fetched as the overlap) to the last completed trading day; no request when that
 *       day is stored already. The first run, or {@code full}, fetches the whole history ({@code history-from}).</li>
 *   <li>Today's bar only after the session close cutoff of the price ingestion
 *       ({@code neracalab.prices.session-close-cutoff}): before it, it is an unfinished intraday bar.</li>
 *   <li>Upserts: a day whose values the source corrected is updated.</li>
 * </ul>
 */
@Service
public class IndexIngestionService implements IndexIngestion {

    private static final Logger log = LoggerFactory.getLogger(IndexIngestionService.class);

    private final TechnicalRepository repository;
    private final TechnicalProperties properties;
    private final YahooChartClient yahoo;
    private final PriceProperties prices;
    private final Clock clock;

    @Autowired
    public IndexIngestionService(TechnicalRepository repository, TechnicalProperties properties, YahooChartClient yahoo,
                                 PriceProperties prices) {
        this(repository, properties, yahoo, prices, Clock.systemUTC());
    }

    IndexIngestionService(TechnicalRepository repository, TechnicalProperties properties, YahooChartClient yahoo,
                          PriceProperties prices, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.yahoo = yahoo;
        this.prices = prices;
        this.clock = clock;
    }

    @Override
    public Optional<IndexRef> index(Exchange exchange) {
        TechnicalProperties.Index index = properties.index();
        if (!Exchange.of(index.exchange()).equals(exchange)) {
            return Optional.empty();
        }
        return Optional.of(new IndexRef(index.code(), index.yahooSymbol(), TechnicalService.indexName(index.code()),
                exchange));
    }

    @Override
    public Stored stored(IndexRef index) {
        IndexStats stats = repository.indexStats(index.code());
        return new Stored(stats.rows(), stats.firstDate(), stats.lastDate());
    }

    @Override
    public String sourceName() {
        return YahooChartClient.SOURCE;
    }

    @Override
    public Result ingest(IndexRef index, boolean full) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(index.exchange().zone()));
        LocalDate today = now.toLocalDate();
        LocalDate lastCompleted = prices.lastCompletedTradingDay(now);
        Optional<LocalDate> latest = repository.latestIndexDate(index.code());
        if (!full && latest.isPresent() && !latest.get().isBefore(lastCompleted)) {
            log.info("{} {}: index up to date ({}), no request", index.exchange(), index.code(), latest.get());
            return new Result(YahooChartClient.SOURCE, 0, null, null, false, false, 0, 0, 0, 0, 0, latest.get(), null,
                    null);
        }
        boolean fullHistory = full || latest.isEmpty();
        LocalDate from = fullHistory ? properties.index().historyFrom() : latest.get();
        List<Candle> received = yahoo.dailyBars(index.symbol(), index.exchange().zone(), from, today);
        List<Candle> bars = received.stream()
                .filter(b -> !b.date().isBefore(from) && !b.date().isAfter(lastCompleted))
                .toList();
        int inserted = 0;
        int changed = 0;
        if (!bars.isEmpty()) {
            Set<LocalDate> stored = repository.indexDates(index.code(), bars.getFirst().date(), bars.getLast().date());
            inserted = (int) bars.stream().filter(b -> !stored.contains(b.date())).count();
            changed = repository.upsertIndexBars(index.code(), index.exchange().code(), YahooChartClient.SOURCE, bars);
        }
        LocalDate after = repository.latestIndexDate(index.code()).orElse(null);
        Result result = new Result(YahooChartClient.SOURCE, 1, from, today, fullHistory, false, received.size(),
                received.size() - bars.size(), inserted, changed - inserted, bars.size() - changed, after, null, null);
        log.info("{} {}: {}", index.exchange(), index.code(), result);
        return result;
    }
}
