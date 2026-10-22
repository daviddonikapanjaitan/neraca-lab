package com.neracalab.backend.macro.outlook;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.market.InstrumentRepository;
import com.neracalab.backend.market.Market;
import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalRepository;
import com.neracalab.backend.technical.TechnicalService;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * The Macro page: the countries and their indexes, and {@link MacroOutlook} of the chosen index. Indonesia (IDX: IHSG
 * and the other indexes of {@code neracalab.technical.other-indexes}, LQ45) is available; the United States and China
 * are listed as coming later. Reads {@code index_daily}, the macro series ({@code macro_series}, USD/IDR) and the
 * commodities and currency pairs of the price ingestion ({@code instrument_daily}, {@link MarketBoard}); nothing is
 * stored.
 */
@Service
public class MacroOutlookService {

    /** A country of the page's dropdown; {@code exchange} null: not available yet. */
    public record Country(String code, String name, String exchange) {

        public boolean available() {
            return exchange != null;
        }
    }

    public static final List<Country> COUNTRIES = List.of(new Country("ID", "Indonesia", "IDX"),
            new Country("US", "United States", null), new Country("CN", "China", null));

    /** A country of {@link #COUNTRIES} by its code ("id" too), available or not; empty for an unknown code. */
    public static Optional<Country> findCountry(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return COUNTRIES.stream().filter(x -> x.code().equals(c)).findFirst();
    }

    private final TechnicalRepository repository;
    private final TechnicalProperties properties;
    private final InstrumentRepository instruments;

    public MacroOutlookService(TechnicalRepository repository, TechnicalProperties properties,
                               InstrumentRepository instruments) {
        this.repository = repository;
        this.properties = properties;
        this.instruments = instruments;
    }

    /** The countries with their indexes (code, name, stored days) for the dropdowns. */
    public List<Map<String, Object>> countries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Country c : COUNTRIES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", c.code());
            m.put("name", c.name());
            m.put("available", c.exchange() != null);
            List<Map<String, Object>> indexes = new ArrayList<>();
            if (c.exchange() != null) {
                for (TechnicalProperties.Index index : indexes(c.exchange())) {
                    TechnicalRepository.IndexStats stats = repository.indexStats(index.code());
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("code", index.code());
                    im.put("name", TechnicalService.indexName(index.code()));
                    im.put("rows", stats.rows());
                    im.put("lastDate", stats.lastDate() == null ? null : stats.lastDate().toString());
                    indexes.add(im);
                }
            }
            m.put("indexes", indexes);
            m.put("note", c.exchange() == null ? "Coming later" : null);
            out.add(m);
        }
        return out;
    }

    /**
     * The outlook of a country's index.
     *
     * @param range 6M, 1Y, 3Y, 5Y, 10Y or MAX: the window of the charts (the rules always read the whole history)
     * @throws IllegalArgumentException an unknown or not yet available country, an unknown index or range
     */
    public Map<String, Object> outlook(String countryCode, String indexCode, String range) {
        Country country = country(countryCode);
        List<TechnicalProperties.Index> indexes = indexes(country.exchange());
        TechnicalProperties.Index index = index(country, indexes, indexCode);
        Exchange exchange = Exchange.of(country.exchange());
        List<Candle> bars = repository.indexBars(index.code());
        // as of today: every observation stored and public by now is read (the latest Forex close of USD/IDR as soon
        // as the price ingestion stored it), not only those up to the index's last trading day
        LocalDate today = LocalDate.now(exchange.zone());
        LocalDate asOf = bars.isEmpty() || bars.getLast().date().isBefore(today) ? today : bars.getLast().date();
        LocalDate from = rangeStart(range, asOf);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", Map.of("code", country.code(), "name", country.name()));
        out.put("range", range == null || range.isBlank() ? "1Y" : range.trim().toUpperCase(Locale.ROOT));
        out.put("countries", countries());
        out.putAll(MacroOutlook.compute(index.code(), TechnicalService.indexName(index.code()), bars, macro(), asOf, from,
                sources()));
        out.put("indexes", indexBoard(indexes, asOf));
        out.put("markets", markets(asOf));
        return out;
    }

    /**
     * Every index of the country side by side (IHSG and LQ45: the broad market against its 45 most liquid stocks): close,
     * changes, the 200-day average and its slope.
     */
    List<Map<String, Object>> indexBoard(List<TechnicalProperties.Index> indexes, LocalDate asOf) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TechnicalProperties.Index index : indexes) {
            MacroOutlook.IndexRead read = MacroOutlook.IndexRead.of(repository.indexBars(index.code()), asOf);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", index.code());
            m.put("name", TechnicalService.indexName(index.code()));
            m.put("date", read.date() == null ? null : read.date().toString());
            m.put("close", MacroOutlook.round(read.close(), 2));
            m.put("change1mPct", MacroOutlook.round(read.change1mPct(), 2));
            m.put("change3mPct", MacroOutlook.round(read.change3mPct(), 2));
            m.put("ytdPct", MacroOutlook.round(read.ytdPct(), 2));
            m.put("change1yPct", MacroOutlook.round(read.change1yPct(), 2));
            m.put("fromHighPct", MacroOutlook.round(read.fromHighPct(), 2));
            m.put("vsSma200Pct", MacroOutlook.round(read.vsSma200Pct(), 2));
            m.put("aboveSma200", read.aboveSma200());
            m.put("sma200Slope", read.sma200Slope());
            out.add(m);
        }
        return out;
    }

    /**
     * What a stored code is (the AI macro analysis' tools).
     *
     * @param rate its changes are read in points (a rate, a yield, a spread), else in percent (a price, an index)
     * @param flow its observations are flows of a session (net foreign buying on IDX): a month is their sum, not its
     *             last observation
     */
    public record SeriesInfo(String name, String unit, boolean rate, boolean flow) {
    }

    /** Series whose observations are flows of one session, not levels (a month of them is their sum). */
    static final java.util.Set<String> FLOW_SERIES = java.util.Set.of("ID_FOREIGN_NET_BUY");

    /**
     * Every stored history of a country's view, for the AI macro analysis: the macro series (with their release dates),
     * the country's indexes and the commodities and currency pairs, oldest first, and what each code is.
     */
    public record History(Map<String, List<Observation>> macro, Map<String, List<Candle>> prices,
                          Map<String, SeriesInfo> info) {
    }

    /** {@link History} of a country (an available one; its indexes, the macro series, commodities and currency pairs). */
    public History history(String countryCode) {
        Country country = country(countryCode);
        Map<String, List<Observation>> macro = new LinkedHashMap<>();
        Map<String, SeriesInfo> info = new LinkedHashMap<>();
        Map<String, TechnicalRepository.MacroDef> defs = new LinkedHashMap<>();
        repository.macroDefs().forEach(d -> defs.put(d.code(), d));
        macro().forEach((code, observations) -> {
            if (observations.isEmpty()) {
                return;
            }
            macro.put(code, observations);
            TechnicalRepository.MacroDef def = defs.get(code);
            if ("USD_IDR".equals(code)) {
                info.put(code, new SeriesInfo("USD/IDR (" + repository.usdIdrSource() + ")", "IDR per USD", false, false));
            } else {
                String unit = def == null ? null : def.unit();
                info.put(code, new SeriesInfo(def == null ? code : def.name(), unit,
                        unit != null && List.of("%", "bps", "points").contains(unit), FLOW_SERIES.contains(code)));
            }
        });
        Map<String, List<Candle>> prices = new LinkedHashMap<>();
        for (TechnicalProperties.Index index : indexes(country.exchange())) {
            List<Candle> bars = repository.indexBars(index.code());
            if (!bars.isEmpty()) {
                prices.put(index.code(), bars);
                info.put(index.code(), new SeriesInfo(TechnicalService.indexName(index.code()), "index points", false, false));
            }
        }
        for (Market market : List.of(Market.COMMODITY, Market.FOREX)) {
            for (InstrumentRepository.Instrument i : instruments.instruments(market)) {
                if (i.rows() > 0 && !prices.containsKey(i.code())) {
                    prices.put(i.code(), instruments.bars(i.code()));
                    info.put(i.code(), new SeriesInfo(i.name(), i.unit(), false, false));
                }
            }
        }
        return new History(macro, prices, info);
    }

    /** The commodities and currency pairs of the price ingestion as of a day ({@link MarketBoard}). */
    public Map<String, Object> markets(LocalDate asOf) {
        List<MarketBoard.Input> inputs = new ArrayList<>();
        for (Market market : List.of(Market.COMMODITY, Market.FOREX)) {
            for (InstrumentRepository.Instrument i : instruments.instruments(market)) {
                if (i.rows() > 0) {
                    inputs.add(new MarketBoard.Input(i.code(), i.name(), market.code(), i.unit(), i.frequency(),
                            instruments.bars(i.code())));
                }
            }
        }
        return MarketBoard.compute(inputs, asOf);
    }

    /** Where each series comes from, as the charts name it. */
    private Map<String, String> sources() {
        Map<String, String> out = new LinkedHashMap<>();
        for (TechnicalRepository.MacroDef def : repository.macroDefs()) {
            out.put(def.code(), switch (def.source() == null ? "" : def.source()) {
                case "fred" -> "FRED";
                case "bi" -> "Bank Indonesia";
                case "bi_seki" -> "Bank Indonesia SEKI";
                case "cnbc" -> "CNBC";
                case "yahoo" -> "Yahoo Finance";
                case "cftc" -> "CFTC";
                case "manual" -> "CSV upload (Ingestion > Macro)";
                default -> def.source();
            });
        }
        out.put("USD_IDR", repository.usdIdrSource());
        return out;
    }

    /**
     * A country and one of its indexes, checked (the AI macro analysis' request).
     *
     * @param exchange the country's exchange (IDX)
     */
    public record Choice(String country, String countryName, String exchange, String index, String indexName) {
    }

    /**
     * @param indexCode null or blank: the country's benchmark index
     * @throws IllegalArgumentException an unknown or not yet available country, an unknown index
     */
    public Choice choice(String countryCode, String indexCode) {
        Country country = country(countryCode);
        TechnicalProperties.Index index = index(country, indexes(country.exchange()), indexCode);
        return new Choice(country.code(), country.name(), country.exchange(), index.code(),
                TechnicalService.indexName(index.code()));
    }

    private static TechnicalProperties.Index index(Country country, List<TechnicalProperties.Index> indexes,
                                                   String indexCode) {
        String code = indexCode == null || indexCode.isBlank() ? indexes.getFirst().code()
                : indexCode.trim().toUpperCase(Locale.ROOT);
        return indexes.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown index '" + indexCode + "' for " + country.name()
                        + ": use " + String.join(" or ", indexes.stream().map(TechnicalProperties.Index::code).toList())));
    }

    private static Country country(String code) {
        Country country = findCountry(code == null || code.isBlank() ? "ID" : code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown country '" + code + "': use ID (Indonesia)"));
        if (country.exchange() == null) {
            throw new IllegalArgumentException(country.name() + " is not available yet: only Indonesia (ID) for now");
        }
        return country;
    }

    /** The benchmark index of an exchange first, then its other indexes. */
    private List<TechnicalProperties.Index> indexes(String exchange) {
        List<TechnicalProperties.Index> out = new ArrayList<>();
        if (properties.index().exchange().equalsIgnoreCase(exchange)) {
            out.add(properties.index());
        }
        properties.otherIndexes().stream().filter(i -> i.exchange().equalsIgnoreCase(exchange)).forEach(out::add);
        return out;
    }

    /** Every stored macro series (and USD/IDR) with its release dates, oldest first. */
    private Map<String, List<Observation>> macro() {
        Map<String, List<Observation>> macro = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>(List.of("BI_RATE", "SBN_10Y", "USD_IDR"));
        repository.macroDefs().stream().map(TechnicalRepository.MacroDef::code).filter(c -> !codes.contains(c))
                .forEach(codes::add);
        for (String code : codes) {
            macro.put(code, repository.macro(code).stream()
                    .map(o -> new Observation(o.date(), o.value(), o.released())).toList());
        }
        return macro;
    }

    static LocalDate rangeStart(String range, LocalDate end) {
        String r = range == null || range.isBlank() ? "1Y" : range.trim().toUpperCase(Locale.ROOT);
        return switch (r) {
            case "6M" -> end.minusMonths(6);
            case "1Y" -> end.minusYears(1);
            case "3Y" -> end.minusYears(3);
            case "5Y" -> end.minusYears(5);
            case "10Y" -> end.minusYears(10);
            case "MAX" -> null;
            default -> throw new IllegalArgumentException("Unknown range '" + range + "': use 6M, 1Y, 3Y, 5Y, 10Y or MAX");
        };
    }
}
