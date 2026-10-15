package com.neracalab.backend.technical.engine;

import java.util.Locale;

/** Bar interval of a chart: daily bars as stored, weekly and monthly aggregated from them. */
public enum Interval {

    D("daily", 252, 21),
    W("weekly", 52, 4),
    M("monthly", 12, 1);

    private final String label;
    /** bars of about one year and one month: 12-1 month momentum */
    private final int yearBars;
    private final int monthBars;

    Interval(String label, int yearBars, int monthBars) {
        this.label = label;
        this.yearBars = yearBars;
        this.monthBars = monthBars;
    }

    public String label() {
        return label;
    }

    public int yearBars() {
        return yearBars;
    }

    public int monthBars() {
        return monthBars;
    }

    /** D, W or M (case-insensitive); also "daily", "weekly", "monthly". */
    public static Interval of(String code) {
        String c = code == null ? "D" : code.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "", "D", "1D", "DAILY" -> D;
            case "W", "1W", "WEEKLY" -> W;
            case "M", "1M", "MONTHLY" -> M;
            default -> throw new IllegalArgumentException("Unknown interval '" + code + "': use D, W or M");
        };
    }
}
