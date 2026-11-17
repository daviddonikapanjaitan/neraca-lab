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

    /**
     * A fetch that stopped early with what it read so far (oldest first, complete as far as it goes): the ingestion
     * stores those observations and the queue asks again after {@code continueAfter} - a long history read in chunks
     * ({@code Duration.ZERO}: at the end of the queue), or a server that answered HTTP 429 (later). The request that
     * was refused is not retried.
     */
    final class PartialFetch extends RuntimeException {

        private final transient List<Observation> observations;
        private final LocalDate resumeFrom;
        private final java.time.Duration continueAfter;

        /**
         * @param resumeFrom    the first date not read: the next fetch starts there
         * @param continueAfter when to ask again ({@code Duration.ZERO}: at the end of the queue)
         */
        public PartialFetch(String reason, List<Observation> observations, LocalDate resumeFrom,
                            java.time.Duration continueAfter) {
            super(reason);
            this.observations = List.copyOf(observations);
            this.resumeFrom = resumeFrom;
            this.continueAfter = continueAfter;
        }

        public List<Observation> observations() {
            return observations;
        }

        public LocalDate resumeFrom() {
            return resumeFrom;
        }

        public java.time.Duration continueAfter() {
            return continueAfter;
        }
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
