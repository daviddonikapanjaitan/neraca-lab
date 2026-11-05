package com.neracalab.backend.market;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.neracalab.backend.macro.source.FredSource;
import com.neracalab.backend.market.InstrumentRepository.Instrument;
import com.neracalab.backend.price.InstrumentIngestion;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.price.provider.FxRateProvider;
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
 *   <li>A product of two Yahoo Finance symbols ({@code "CPO=F*MYR=X"}, palm oil in ringgit): the bars of the first
 *       times the close of the second on the same day (or the last one up to {@link #RATE_GAP_DAYS} before it); a day
 *       without a rate is not stored.</li>
 *   <li>Monthly (World Bank Pink Sheet: coal, nickel, tin, rubber, LNG, urea, DAP, potash; IMF PCPS: cobalt; FRED:
 *       the wood pulp price index): the World Bank and the IMF send the whole history, so every run reads it all (FRED
 *       from the month asked for); months
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
    /** Separates the two Yahoo Finance symbols of a product ({@code "CPO=F*MYR=X"}). */
    static final String PRODUCT = "*";
    /** Days a product's rate may be older than its price (a holiday of the currency market). */
    static final int RATE_GAP_DAYS = 5;

    private final InstrumentRepository repository;
    private final YahooChartClient yahoo;
    private final WorldBankPinkSheetClient worldBank;
    private final ImfPcpsClient imf;
    private final FredSource fred;
    /** ECB reference rates (Frankfurter): a Forex day Yahoo sends inconsistent is filled with them */
    private final FxRateProvider fx;
    private final Clock clock;

    @Autowired
    public InstrumentIngestionService(InstrumentRepository repository, YahooChartClient yahoo,
                                      WorldBankPinkSheetClient worldBank, ImfPcpsClient imf, FredSource fred,
                                      FxRateProvider fx) {
        this(repository, yahoo, worldBank, imf, fred, fx, Clock.systemUTC());
    }

    InstrumentIngestionService(InstrumentRepository repository, YahooChartClient yahoo, WorldBankPinkSheetClient worldBank,
                               ImfPcpsClient imf, FredSource fred, FxRateProvider fx, Clock clock) {
        this.repository = repository;
        this.yahoo = yahoo;
        this.worldBank = worldBank;
        this.imf = imf;
        this.fred = fred;
        this.fx = fx;
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
        // a currency pair's bars Yahoo sent with a close outside their own high-low range
        // (YahooChartClient.dailyBarsChecked): not stored, and a stored copy deleted (USD/IDR 2026-11-03 / 04);
        // a future's close (the settlement) may lie outside its session's range: not checked
        List<LocalDate> inconsistent = new ArrayList<>();
        List<Candle> received = switch (instrument.source()) {
            case "yahoo" -> {
                if (instrument.symbol().contains(PRODUCT)) {
                    yield product(instrument.symbol(), market.zone(), from, today);
                }
                if (market != Market.FOREX) {
                    yield yahoo.dailyBars(instrument.symbol(), market.zone(), from, today);
                }
                YahooChartClient.Bars checked = yahoo.dailyBarsChecked(instrument.symbol(), market.zone(), from, today);
                inconsistent.addAll(checked.inconsistent());
                yield checked.bars();
            }
            case WorldBankPinkSheetClient.SOURCE -> monthlyBars(worldBank.monthly(instrument.symbol()));
            case ImfPcpsClient.SOURCE -> monthlyBars(imf.monthly(instrument.symbol()));
            // a monthly FRED series is dated on the 1st of its month
            case "fred" -> fred.observations(instrument.symbol(), from).stream()
                    .map(o -> new WorldBankPinkSheetClient.MonthlyValue(o.date().withDayOfMonth(1), o.value().doubleValue()))
                    .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),
                            InstrumentIngestionService::monthlyBars));
            default -> throw new IllegalStateException("Unknown source '" + instrument.source() + "' of " + instrument.code());
        };
        // the whole workbook comes with every request: keep what is newer than the stored months (all of it in full)
        LocalDate keepFrom = from;
        List<Candle> window = received.stream()
                .filter(b -> !b.date().isBefore(keepFrom) && !b.date().isAfter(lastCompleted))
                .toList();
        List<Candle> bars = window;
        // the days Yahoo sent inconsistent, within the window, get the ECB reference rate of the day instead (stored
        // as source 'ecb'); a day without one is deleted - no close is known for it
        List<LocalDate> unusable = inconsistent.stream()
                .filter(d -> !d.isBefore(keepFrom) && !d.isAfter(lastCompleted)).toList();
        List<Candle> referenceBars = referenceBars(instrument, unusable);
        List<LocalDate> filled = referenceBars.stream().map(Candle::date).toList();
        List<LocalDate> unfilled = unusable.stream().filter(d -> !filled.contains(d)).toList();
        int deleted = unfilled.isEmpty() ? 0 : repository.delete(instrument.code(), unfilled);
        if (!inconsistent.isEmpty()) {
            log.info("{} {}: {} bar(s) with a close outside their range dropped {}; {} filled with the {} reference "
                            + "rate {}, {} stored without one deleted", market, instrument.code(), inconsistent.size(),
                    inconsistent, filled.size(), fx.name(), filled, deleted);
        }
        if (instrument.maxSpike() != null && !instrument.monthly()) {
            BarCleaner.Cleaned cleaned = BarCleaner.clean(window, instrument.maxSpike().doubleValue());
            bars = cleaned.bars();
            deleted += repository.delete(instrument.code(), cleaned.dropped());
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
        if (!referenceBars.isEmpty()) {
            Set<LocalDate> stored = repository.dates(instrument.code(), referenceBars.getFirst().date(),
                    referenceBars.getLast().date());
            inserted += (int) referenceBars.stream().filter(b -> !stored.contains(b.date())).count();
            changed += repository.upsert(instrument.code(), fx.name(), referenceBars);
        }
        LocalDate after = repository.latestDate(instrument.code()).orElse(null);
        // updated: changed bars and deleted bad prints
        Result result = new Result(instrument.source(), 1, from, instrument.monthly() ? lastCompleted : today, fullHistory,
                false, received.size(), received.size() - bars.size(), inserted, changed - inserted + deleted,
                bars.size() + referenceBars.size() - changed, after, null, null);
        log.info("{} {}: {}", market, instrument.code(), result);
        return result;
    }

    /**
     * The ECB reference rate (FxRateProvider, Frankfurter) of the days a currency pair's Yahoo bar was unusable, as one-
     * value bars (open = high = low = close, as the monthly series), oldest first. USD/IDR 2026-11-03 / 04: Yahoo's closes
     * 17,472.20 / 17,536.10 lie under their own lows; the ECB fixed 18,137 / 18,129. Only rates of exactly those days
     * (the ECB fixes at about 16:00 CET, Yahoo's close is London's end of day: a reference of the same day, not the
     * same minute). Empty for a code that is no currency pair of six letters, and when the ECB has no rate or does not
     * answer - the day then stays empty (never a failed ingestion).
     */
    List<Candle> referenceBars(Instrument instrument, List<LocalDate> days) {
        String code = instrument.code();
        if (days.isEmpty() || instrument.market() != Market.FOREX || code.length() != 6) {
            return List.of();
        }
        List<LocalDate> sorted = days.stream().sorted().toList();
        try {
            Set<LocalDate> wanted = Set.copyOf(sorted);
            return fx.fetch(code.substring(0, 3), code.substring(3), sorted.getFirst(), sorted.getLast()).stream()
                    .filter(r -> r.date() != null && wanted.contains(r.date()) && r.rate() != null && r.rate().signum() > 0)
                    .map(r -> {
                        double rate = r.rate().doubleValue();
                        return Candle.daily(r.date(), rate, rate, rate, rate, rate, 0);
                    })
                    .sorted(java.util.Comparator.comparing(Candle::date))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("{}: no {} reference rate for {}: {}", code, fx.name(), sorted, e.getMessage());
            return List.of();
        }
    }

    /**
     * The bars of {@code "PRICE*RATE"}: open, high, low and close of PRICE times the close of RATE on that day, or on the
     * last day before it with a rate (up to {@link #RATE_GAP_DAYS}); the volume is PRICE's. CPO=F*MYR=X: the CME USD
     * Malaysian crude palm oil future (cash-settled on Bursa Malaysia's FCPO) in ringgit per tonne.
     */
    List<Candle> product(String symbol, ZoneId zone, LocalDate from, LocalDate to) {
        String[] parts = symbol.split(java.util.regex.Pattern.quote(PRODUCT), 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalStateException("A product symbol is PRICE" + PRODUCT + "RATE, not '" + symbol + "'");
        }
        List<Candle> prices = yahoo.dailyBars(parts[0].trim(), zone, from, to);
        if (prices.isEmpty()) {
            return prices;
        }
        TreeMap<LocalDate, Double> rates = new TreeMap<>();
        yahoo.dailyBars(parts[1].trim(), zone, from.minusDays(RATE_GAP_DAYS), to).forEach(b -> rates.put(b.date(), b.close()));
        List<Candle> out = new ArrayList<>();
        for (Candle p : prices) {
            Map.Entry<LocalDate, Double> rate = rates.floorEntry(p.date());
            if (rate == null || rate.getKey().isBefore(p.date().minusDays(RATE_GAP_DAYS))) {
                continue;
            }
            double k = rate.getValue();
            out.add(Candle.daily(p.date(), round4(p.open() * k), round4(p.high() * k), round4(p.low() * k),
                    round4(p.close() * k), round4(p.close() * k), p.volume()));
        }
        return out;
    }

    /** One bar per month, dated on the 1st: open = high = low = close = the monthly value. */
    private static List<Candle> monthlyBars(List<WorldBankPinkSheetClient.MonthlyValue> months) {
        return months.stream()
                .map(v -> Candle.daily(v.month(), v.value(), v.value(), v.value(), v.value(), v.value(), 0))
                .toList();
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    private Instrument load(InstrumentRef ref) {
        return repository.instrument(Market.of(ref.market()), ref.code())
                .orElseThrow(() -> new IllegalArgumentException("No active instrument " + ref.code() + " in " + ref.market()));
    }
}
