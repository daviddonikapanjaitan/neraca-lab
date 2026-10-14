package com.neracalab.backend.technical.engine;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;

/**
 * Weekly and monthly bars from daily bars: open of the first day, highest high, lowest low, close and adjusted close of
 * the last day, summed volume and value. A week is an ISO week (Monday .. Sunday), a month a calendar month; the bar is
 * dated on its first trading day. The current, unfinished week or month is included as it stands.
 */
public final class Aggregation {

    private Aggregation() {
    }

    public static List<Candle> aggregate(List<Candle> daily, Interval interval) {
        if (interval == Interval.D) {
            return daily;
        }
        List<Candle> out = new ArrayList<>();
        Candle open = null;
        double high = 0;
        double low = 0;
        double volume = 0;
        double value = 0;
        Candle last = null;
        long key = Long.MIN_VALUE;
        for (Candle c : daily) {
            long k = key(c.date(), interval);
            if (open != null && k != key) {
                out.add(new Candle(open.date(), open.open(), high, low, last.close(), last.adjClose(), volume, value));
                open = null;
            }
            if (open == null) {
                open = c;
                high = c.high();
                low = c.low();
                volume = 0;
                value = 0;
                key = k;
            }
            high = Math.max(high, c.high());
            low = Math.min(low, c.low());
            volume += c.volume();
            value += c.value();
            last = c;
        }
        if (open != null) {
            out.add(new Candle(open.date(), open.open(), high, low, last.close(), last.adjClose(), volume, value));
        }
        return out;
    }

    static long key(LocalDate date, Interval interval) {
        return switch (interval) {
            case W -> date.get(IsoFields.WEEK_BASED_YEAR) * 100L + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case M -> date.getYear() * 100L + date.getMonthValue();
            case D -> date.toEpochDay();
        };
    }
}
