package com.neracalab.backend.market;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.neracalab.backend.market.InstrumentRepository.Instrument;
import com.neracalab.backend.price.InstrumentIngestion;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.technical.YahooChartClient;
import com.neracalab.backend.technical.engine.Candle;

/**
 * The bars of a Forex pair or commodity into {@code instrument_daily}, run by the price queue (Ingestion › Price
 * Ingestion).
 * <ul>
 *   <li>Daily (Yahoo Finance): from two weeks before the latest stored day (the overlap gives the cleaning its
 *       neighbours) to the last completed day - the weekday before today in the market's time zone: a currency or future
 *       trades around the clock, so today's bar is unfinished. No request when that day is stored already. The first run,
 *       or {@code full}, fetches the whole history from the instrument's {@code history_from}. Bad prints are cleaned
 *       ({@link BarCleaner}, {@code max_spike}): a dropped day that was stored is deleted.</li>
 *   <li>Monthly (World Bank Pink Sheet, coal): the workbook holds the whole history, so every run reads it all; months
 *       up to the month before the current one are stored (open = high = low = close = the monthly average, dated on the
 *       1st). No request when that month is stored already.</li>
 *   <li>Upserts: a bar the source corrected is updated.</li>
 * </ul>
 */
@Service
public class InstrumentIngestionService implements InstrumentIngestion {

    private static final Logger log = LoggerFactory.getLogger(InstrumentIngestionService.class);

    /** Days before the latest stored bar an incremental fetch starts at (the cleaning needs neighbours). */
    static final int OVERLAP_DAYS = 14;

    private final InstrumentRepository repository;
    private final YahooChartClient yahoo;
    private final WorldBankPinkSheetClient worldBank;
    private final Clock clock;

    @Autowired
    public InstrumentIngestionService(InstrumentRepository repository, YahooChartClient yahoo,
                                      WorldBankPinkSheetClient worldBank) {
        this(repository, yahoo, worldBank, Clock.systemUTC());
    }

    InstrumentIngestionService(InstrumentRepository repository, YahooChartClient yahoo, WorldBankPinkSheetClient worldBank,
                               Clock clock) {
        this.repository = repository;
        this.yahoo = yahoo;
        this.worldBank = worldBank;
        this.clock = clock;
    }

    static InstrumentRef ref(Instrument i) {
        return new InstrumentRef(i.code(), i.market().code(), i.name(), i.symbol(), i.source(), i.frequency());
    }

    @Override
    public List<InstrumentRef> instruments(String market) {
        return repository.instruments(Market.of(market)).stream().map(InstrumentIngestionService::ref).toList();
    }

    @Override
    public Optional<InstrumentRef> instrument(String market, String code) {
        return repository.instrument(Market.of(market), code).map(InstrumentIngestionService::ref);
    }

    @Override
    public Stored stored(InstrumentRef instrument) {
        Instrument i = load(instrument);
        return new Stored(i.rows(), i.firstDate(), i.lastDate());
    }

    @Override
    public LocalDate lastCompleted(InstrumentRef instrument) {
        LocalDate today = LocalDate.now(clock.withZone(Market.of(instrument.market()).zone()));
        return instrument.monthly() ? Market.lastCompletedMonth(today) : Market.lastCompletedDay(today);
    }

    @Override
    public String sourceName(InstrumentRef instrument) {
        return instrument.source();
    }

    @Override
    public Result ingest(InstrumentRef ref, boolean full) {
        Instrument instrument = load(ref);
        Market market = instrument.market();
        LocalDate today = LocalDate.now(clock.withZone(market.zone()));
        LocalDate lastCompleted = lastCompleted(ref);
        Optional<LocalDate> latest = repository.latestDate(instrument.code());
        if (!full && latest.isPresent() && !latest.get().isBefore(lastCompleted)) {
            log.info("{} {}: up to date ({}), no request", market, instrument.code(), latest.get());
            return new Result(instrument.source(), 0, null, null, false, false, 0, 0, 0, 0, 0, latest.get(), null, null);
        }
        boolean fullHistory = full || latest.isEmpty();
        LocalDate from = fullHistory ? instrument.historyFrom()
                : instrument.monthly() ? latest.get() : latest.get().minusDays(OVERLAP_DAYS);
        List<Candle> received = switch (instrument.source()) {
            case "yahoo" -> yahoo.dailyBars(instrument.symbol(), market.zone(), from, today);
            case WorldBankPinkSheetClient.SOURCE -> worldBank.monthly(instrument.symbol()).stream()
                    .map(v -> Candle.daily(v.month(), v.value(), v.value(), v.value(), v.value(), v.value(), 0))
                    .toList();
            default -> throw new IllegalStateException("Unknown source '" + instrument.source() + "' of " + instrument.code());
        };
        // the whole workbook comes with every request: keep what is newer than the stored months (all of it in full)
        LocalDate keepFrom = from;
        List<Candle> window = received.stream()
                .filter(b -> !b.date().isBefore(keepFrom) && !b.date().isAfter(lastCompleted))
                .toList();
        List<Candle> bars = window;
        int deleted = 0;
        if (instrument.maxSpike() != null && !instrument.monthly()) {
            BarCleaner.Cleaned cleaned = BarCleaner.clean(window, instrument.maxSpike().doubleValue());
            bars = cleaned.bars();
            deleted = repository.delete(instrument.code(), cleaned.dropped());
            if (!cleaned.dropped().isEmpty() || cleaned.wicksCut() > 0) {
                log.info("{} {}: {} bad print(s) dropped {} ({} stored, deleted), {} wick(s) cut", market,
                        instrument.code(), cleaned.dropped().size(), cleaned.dropped(), deleted, cleaned.wicksCut());
            }
        }
        int inserted = 0;
        int changed = 0;
        if (!bars.isEmpty()) {
            Set<LocalDate> stored = repository.dates(instrument.code(), bars.getFirst().date(), bars.getLast().date());
            inserted = (int) bars.stream().filter(b -> !stored.contains(b.date())).count();
            changed = repository.upsert(instrument.code(), instrument.source(), bars);
        }
        LocalDate after = repository.latestDate(instrument.code()).orElse(null);
        // updated: changed bars and deleted bad prints
        Result result = new Result(instrument.source(), 1, from, instrument.monthly() ? lastCompleted : today, fullHistory,
                false, received.size(), received.size() - bars.size(), inserted, changed - inserted + deleted,
                bars.size() - changed, after, null, null);
        log.info("{} {}: {}", market, instrument.code(), result);
        return result;
    }

    private Instrument load(InstrumentRef ref) {
        return repository.instrument(Market.of(ref.market()), ref.code())
                .orElseThrow(() -> new IllegalArgumentException("No active instrument " + ref.code() + " in " + ref.market()));
    }
}
