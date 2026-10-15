package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.neracalab.backend.macro.MacroSeries;

/**
 * Where the observations of a macro series come from ({@code macro_series_def.source}). An implementation returns what
 * the source holds, oldest first; it fails with a {@link com.neracalab.backend.crawl.SourceException} when the source
 * cannot be read or its layout is not the expected one. Nothing is guessed.
 */
public interface MacroSource {

    /** An observation as the source gives it: its date (the 1st for a month) and value in the series' unit. */
    record Observation(LocalDate date, BigDecimal value) {
    }

    /** The {@code macro_series_def.source} this implementation reads (fred, bi, ...). */
    String name();

    /**
     * The observations of {@code series} from {@code from} (inclusive) on; a source that always returns its whole history
     * (a workbook) may return older ones too.
     *
     * @param today the current date in Jakarta; an unfinished bar of today is left out
     */
    List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today);
}
