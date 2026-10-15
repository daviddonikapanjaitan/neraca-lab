package com.neracalab.backend.market;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.neracalab.backend.technical.engine.Candle;

/**
 * Cleans the daily bars of a currency pair or future from Yahoo Finance, whose history carries bad prints (USD/IDR
 * 888.11 between 8,923 and 8,906 on 2010-11-01; a stale 9,612.45 on several days of 2013; USD/JPY 110.09 between
 * 101.46 and 99.51 on 2008-10-08). Pure and deterministic.
 * <ul>
 *   <li>A spike: a run of one to three closes that all lie more than {@code maxSpike} (a fraction) away from the close
 *       before the run and from the close after it, on the same side, while those two agree within half of it - the
 *       price jumped and came straight back. The run is dropped; repeated until none is left. A real move does not come
 *       back (a level shift keeps the close after the run away from the one before), so it is kept.</li>
 *   <li>A wick: a high or low further than {@code maxSpike} from the bar's open and close is cut back to them, and an
 *       open that far from both the close and the previous close becomes the close.</li>
 * </ul>
 * The first and last bar have no neighbour on one side and are never judged a spike; the ingestion re-reads the last
 * days of the stored history, so the last bar is judged by the next run.
 */
public final class BarCleaner {

    /** The cleaned bars and the dates of the dropped ones. */
    public record Cleaned(List<Candle> bars, List<LocalDate> dropped, int wicksCut) {
    }

    private BarCleaner() {
    }

    public static Cleaned clean(List<Candle> input, double maxSpike) {
        List<Candle> bars = new ArrayList<>(input);
        List<LocalDate> dropped = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < bars.size() - 1 && !changed; i++) {
                for (int k = 1; k <= 3 && i + k < bars.size(); k++) {
                    double before = bars.get(i - 1).close();
                    double after = bars.get(i + k).close();
                    if (Math.abs(after / before - 1) >= maxSpike / 2) {
                        continue;
                    }
                    boolean up = true;
                    boolean down = true;
                    for (int j = i; j < i + k; j++) {
                        double c = bars.get(j).close();
                        up &= c / before - 1 > maxSpike && c / after - 1 > maxSpike;
                        down &= c / before - 1 < -maxSpike && c / after - 1 < -maxSpike;
                    }
                    if (up || down) {
                        for (int j = 0; j < k; j++) {
                            dropped.add(bars.remove(i).date());
                        }
                        changed = true;
                        break;
                    }
                }
            }
        }
        int wicks = 0;
        List<Candle> out = new ArrayList<>(bars.size());
        for (int i = 0; i < bars.size(); i++) {
            Candle c = bars.get(i);
            double open = c.open();
            if (i > 0) {
                double prev = bars.get(i - 1).close();
                if (Math.abs(open / c.close() - 1) > maxSpike && Math.abs(open / prev - 1) > maxSpike) {
                    open = c.close();
                }
            }
            double top = Math.max(open, c.close());
            double bottom = Math.min(open, c.close());
            double high = c.high() > top * (1 + maxSpike) ? top : Math.max(c.high(), top);
            double low = c.low() < bottom * (1 - maxSpike) ? bottom : Math.min(c.low(), bottom);
            if (open != c.open() || high != c.high() || low != c.low()) {
                wicks++;
                out.add(Candle.daily(c.date(), open, high, low, c.close(), c.adjClose(), c.volume()));
            } else {
                out.add(c);
            }
        }
        return new Cleaned(out, dropped, wicks);
    }
}
