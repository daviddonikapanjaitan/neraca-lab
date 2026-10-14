package com.neracalab.backend.technical.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What every snapshot of an exchange shares: the benchmark index, the universe of listings (for breadth and the
 * relative-strength percentiles) and the macro series. Computed once per run and reused for every ticker.
 *
 * @param asOfDate          the latest index day (else the latest price day of the universe)
 * @param indexBars         daily bars of the benchmark index (IHSG), oldest first; empty when not loaded yet
 * @param universe          listing id -> its returns and trend at its latest bar on or before {@code asOfDate}
 * @param macro             series code -> observations, oldest first (BI_RATE, SBN_10Y, USD_IDR)
 */
public record MarketContext(String exchange, LocalDate asOfDate, String indexCode, List<Candle> indexBars,
                            Map<Long, Member> universe, Map<String, List<Observation>> macro) {

    /**
     * A listing of the universe at its latest bar on or before the as-of date.
     *
     * @param return6m  close / close 126 bars earlier - 1, in % (NaN with less history)
     * @param return12m close / close 252 bars earlier - 1, in %
     * @param above200  close above its SMA 200; null with less than 200 bars or no bar in the last 10 days
     */
    public record Member(long companyId, String ticker, String sector, LocalDate lastDate, double return6m,
                         double return12m, Boolean above200) {
    }

    public record Observation(LocalDate date, double value) {
    }

    /** % of the universe above its 200-day SMA (members without a verdict are not counted), NaN when none qualifies. */
    public double breadthAbove200Pct() {
        long counted = universe.values().stream().filter(m -> m.above200() != null).count();
        long above = universe.values().stream().filter(m -> Boolean.TRUE.equals(m.above200())).count();
        return counted == 0 ? Double.NaN : above * 100.0 / counted;
    }

    public long breadthCounted() {
        return universe.values().stream().filter(m -> m.above200() != null).count();
    }
}
