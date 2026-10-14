package com.neracalab.backend.technical.idx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.neracalab.backend.technical.TechnicalProperties.TickBand;

/**
 * IDX price fractions (fraksi harga): a price below 200 moves in steps of Rp 1, from 200 of 2, from 500 of 5, from 2,000
 * of 10 and from 5,000 of 25 (the default bands, configurable). Every computed level shown or stored (support /
 * resistance, retracements, volume nodes, 52-week and all-time levels) is a price a buyer or seller can actually
 * quote, so it is rounded to the tick of its band. The band boundaries are multiples of the larger tick, so rounding
 * across a boundary still lands on a valid price.
 */
public final class IdxTickSize {

    private final List<TickBand> bands;

    public IdxTickSize(List<TickBand> bands) {
        if (bands == null || bands.isEmpty() || bands.getLast().below() != null) {
            throw new IllegalArgumentException("tick bands must end with an open band");
        }
        this.bands = List.copyOf(bands);
    }

    /** The tick of a price: the band whose upper bound ({@code below}) is above it. */
    public BigDecimal tick(BigDecimal price) {
        for (TickBand band : bands) {
            if (band.below() == null || price.compareTo(band.below()) < 0) {
                return band.tick();
            }
        }
        return bands.getLast().tick();
    }

    public double tick(double price) {
        return tick(BigDecimal.valueOf(price)).doubleValue();
    }

    /** The nearest valid price (half up); never below the smallest tick. */
    public BigDecimal round(BigDecimal price) {
        return round(price, RoundingMode.HALF_UP);
    }

    /** The valid price at or below {@code price} (the lower edge of a zone). */
    public BigDecimal floor(BigDecimal price) {
        return round(price, RoundingMode.FLOOR);
    }

    /** The valid price at or above {@code price} (the upper edge of a zone). */
    public BigDecimal ceil(BigDecimal price) {
        return round(price, RoundingMode.CEILING);
    }

    public double round(double price) {
        return round(BigDecimal.valueOf(price)).doubleValue();
    }

    public double floor(double price) {
        return floor(BigDecimal.valueOf(price)).doubleValue();
    }

    public double ceil(double price) {
        return ceil(BigDecimal.valueOf(price)).doubleValue();
    }

    /** Whether {@code price} is a valid IDX price (a multiple of the tick of its band). */
    public boolean isValid(BigDecimal price) {
        return price.signum() > 0 && price.remainder(tick(price)).signum() == 0;
    }

    private BigDecimal round(BigDecimal price, RoundingMode mode) {
        if (price.signum() <= 0) {
            return bands.getFirst().tick();
        }
        BigDecimal tick = tick(price);
        BigDecimal rounded = price.divide(tick, 0, mode).multiply(tick);
        // rounding up across a band boundary lands on the boundary (a multiple of the next tick); rounding down below
        // a boundary uses the lower band's tick, which the result is a multiple of
        if (!isValid(rounded)) {
            rounded = rounded.divide(tick(rounded), 0, mode).multiply(tick(rounded));
        }
        if (rounded.signum() <= 0) {
            return bands.getFirst().tick();
        }
        BigDecimal plain = rounded.stripTrailingZeros();
        return plain.scale() < 0 ? plain.setScale(0) : plain;
    }
}
