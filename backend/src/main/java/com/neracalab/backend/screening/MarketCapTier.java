package com.neracalab.backend.screening;

import java.util.Arrays;
import java.util.Locale;

/**
 * Market capitalisation tier of a screening ({@code screening_run.market_cap_tier}). The bounds are
 * {@code neracalab.screening.large-cap-min} / {@code mid-cap-min} (IDX defaults: Rp 10T and Rp 1T).
 */
public enum MarketCapTier {

    LARGE("Large cap"),
    MID("Mid cap"),
    SMALL("Small cap");

    private final String label;

    MarketCapTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** The tier of a market cap. */
    public static MarketCapTier of(double marketCap, ScreeningProperties properties) {
        if (marketCap >= properties.largeCapMin()) {
            return LARGE;
        }
        return marketCap >= properties.midCapMin() ? MID : SMALL;
    }

    /** Lowest market cap of the tier (inclusive). */
    public double lowerBound(ScreeningProperties properties) {
        return switch (this) {
            case LARGE -> properties.largeCapMin();
            case MID -> properties.midCapMin();
            case SMALL -> 0;
        };
    }

    /** Highest market cap of the tier (exclusive; infinite for LARGE). */
    public double upperBound(ScreeningProperties properties) {
        return switch (this) {
            case LARGE -> Double.POSITIVE_INFINITY;
            case MID -> properties.largeCapMin();
            case SMALL -> properties.midCapMin();
        };
    }

    /** Case-insensitive; also accepts "medium" for MID. Null when unknown. */
    public static MarketCapTier parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace("_CAP", "").replace(" CAP", "");
        if (normalized.equals("MEDIUM")) {
            return MID;
        }
        return Arrays.stream(values()).filter(t -> t.name().equals(normalized)).findFirst().orElse(null);
    }
}
