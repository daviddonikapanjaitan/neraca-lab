package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroRepository;
import com.neracalab.backend.macro.MacroSeries;

/**
 * A drought index derived from a stored rainfall series ({@code source = spi}; {@code source_ref} the rainfall series'
 * code, e.g. {@code ID_RAIN_KAL_PALM}): its 3-month {@link StandardizedPrecipitation} index, no request. Its rows sort
 * after the rainfall series, so "Update all macro data" fetches the rainfall first (one worker, catalog order). The fit
 * needs the rainfall of 1991 - 2020: until that history is stored (Fetch the rainfall series in full once), the job
 * fails and says so.
 */
@Component
public class SpiSource implements MacroSource {

    static final int MONTHS = 3;

    private final MacroRepository repository;

    public SpiSource(MacroRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "spi";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String base = series.sourceRef() == null ? "" : series.sourceRef().trim();
        List<MacroRepository.Row> rows = repository.observations(base, null);
        if (rows.isEmpty()) {
            throw new SourceException(series.code() + ": its rainfall series " + base + " has no observation: fetch "
                    + base + " (Full) first");
        }
        Map<YearMonth, Double> rainfall = new TreeMap<>();
        for (MacroRepository.Row r : rows) {
            rainfall.put(YearMonth.from(r.obsDate()), r.value().doubleValue());
        }
        List<Integer> unfitted = StandardizedPrecipitation.unfitted(rainfall, MONTHS);
        if (!unfitted.isEmpty()) {
            throw new SourceException(series.code() + ": the " + StandardizedPrecipitation.BASE_FROM + " - "
                    + StandardizedPrecipitation.BASE_TO + " rainfall of " + base + " is incomplete for calendar months "
                    + unfitted + ": fetch " + base + " in full first");
        }
        NavigableMap<YearMonth, Double> spi = StandardizedPrecipitation.compute(rainfall, MONTHS);
        List<Observation> out = new ArrayList<>();
        for (Map.Entry<YearMonth, Double> e : spi.tailMap(YearMonth.from(from), true).entrySet()) {
            out.add(new Observation(e.getKey().atDay(1), BigDecimal.valueOf(e.getValue()).setScale(3, RoundingMode.HALF_UP)));
        }
        return out;
    }
}
