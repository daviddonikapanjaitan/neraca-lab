package com.neracalab.backend.macro.outlook;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalRepository;
import com.neracalab.backend.technical.TechnicalService;
import com.neracalab.backend.technical.engine.Candle;
import com.neracalab.backend.technical.engine.MarketContext.Observation;

/**
 * The Macro page: the countries and their indexes, and {@link MacroOutlook} of the chosen index. Indonesia (IDX: IHSG
 * and the other indexes of {@code neracalab.technical.other-indexes}, LQ45) is available; the United States and China
 * are listed as coming later. Reads {@code index_daily} and the macro series ({@code macro_series}, USD/IDR); nothing is
 * stored.
 */
@Service
public class MacroOutlookService {

    /** A country of the page's dropdown; {@code exchange} null: not available yet. */
    record Country(String code, String name, String exchange) {
    }

    static final List<Country> COUNTRIES = List.of(new Country("ID", "Indonesia", "IDX"),
            new Country("US", "United States", null), new Country("CN", "China", null));

    private final TechnicalRepository repository;
    private final TechnicalProperties properties;

    public MacroOutlookService(TechnicalRepository repository, TechnicalProperties properties) {
        this.repository = repository;
        this.properties = properties;
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
        String code = indexCode == null || indexCode.isBlank() ? indexes.getFirst().code()
                : indexCode.trim().toUpperCase(Locale.ROOT);
        TechnicalProperties.Index index = indexes.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown index '" + indexCode + "' for " + country.name()
                        + ": use " + String.join(" or ", indexes.stream().map(TechnicalProperties.Index::code).toList())));
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
        return out;
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

    private static Country country(String code) {
        String c = code == null || code.isBlank() ? "ID" : code.trim().toUpperCase(Locale.ROOT);
        Country country = COUNTRIES.stream().filter(x -> x.code().equals(c)).findFirst()
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
