package com.neracalab.backend.technical.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Relative strength of a listing against an index: the ratio of their closes on the days both traded. */
public final class RelativeStrength {

    private RelativeStrength() {
    }

    /** Stock close / index close on each common date, oldest first. */
    public record Ratio(List<LocalDate> dates, double[] values) {
    }

    public static Ratio ratio(List<Candle> stock, List<Candle> index) {
        Map<LocalDate, Double> indexClose = new HashMap<>();
        for (Candle c : index) {
            indexClose.put(c.date(), c.close());
        }
        List<LocalDate> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Candle c : stock) {
            Double ix = indexClose.get(c.date());
            if (ix != null && ix > 0) {
                dates.add(c.date());
                values.add(c.close() / ix);
            }
        }
        return new Ratio(dates, values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /**
     * The index on the stock's dates (the last index close on or before each), oldest first; NaN before the index
     * starts. Used to draw the index rebased onto the stock's chart.
     */
    public static double[] indexOn(List<Candle> stock, List<Candle> index) {
        double[] out = new double[stock.size()];
        int j = 0;
        double last = Double.NaN;
        for (int i = 0; i < stock.size(); i++) {
            LocalDate d = stock.get(i).date();
            while (j < index.size() && !index.get(j).date().isAfter(d)) {
                last = index.get(j).close();
                j++;
            }
            out[i] = last;
        }
        return out;
    }
}
