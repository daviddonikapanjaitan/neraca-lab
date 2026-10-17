package com.neracalab.backend.technical.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * What every snapshot of an exchange shares: the benchmark index and the universe of listings (for breadth and the
 * relative-strength percentiles). Computed once per run and reused for every ticker. Technical only: no macro data.
 *
 * @param asOfDate          the latest index day (else the latest price day of the universe)
 * @param indexBars         daily bars of the benchmark index (IHSG), oldest first; empty when not loaded yet
 * @param universe          listing id -> its returns and trend at its latest bar on or before {@code asOfDate}
 */
public record MarketContext(String exchange, LocalDate asOfDate, String indexCode, List<Candle> indexBars,
                            Map<Long, Member> universe) {

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

    /**
     * A macro observation (the Macro page: {@link MacroSignals}, {@code macro.outlook.MacroOutlook}).
     *
     * @param date     the observation date (the 1st of the month for a monthly series)
     * @param released the day it became public; a snapshot as of an earlier day does not use it
     */
    public record Observation(LocalDate date, double value, LocalDate released) {

        /** An observation public on its own date (a market close). */
        public Observation(LocalDate date, double value) {
            this(date, value, date);
        }
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
