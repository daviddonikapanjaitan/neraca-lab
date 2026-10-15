package com.neracalab.backend.technical.idx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.TechnicalProperties.AutoRejectionRule;
import com.neracalab.backend.technical.TechnicalProperties.LimitBand;
import com.neracalab.backend.technical.engine.Candle;

/**
 * Days that hit IDX's auto-rejection limits, detected from daily bars: the upper limit (ARA, auto rejection atas) or
 * the lower limit (ARB, auto rejection bawah). The limit of a day is the previous close +/- its percentage, which depends
 * on the previous close's price band and on the rule in force that day (IDX changed the ARB repeatedly: 7 % from March
 * 2020, 15 % from June 2023, symmetric with ARA from September 2023, 15 % again from April 2025 - all configurable).
 * <p>
 * A day is an ARA (ARB) hit when it closes at its high (low) and the close is within one tick of the limit price or
 * beyond it. The tick tolerance covers the rounding of the limit to a valid price; the exchange's own limit price is not
 * stored, so this is an estimate from the bars.
 */
public final class AutoRejection {

    /** A detected hit. */
    public record Hit(LocalDate date, Type type, double close, double changePct) {
    }

    public enum Type { ARA, ARB }

    private final TechnicalProperties properties;
    private final IdxTickSize ticks;

    public AutoRejection(TechnicalProperties properties, IdxTickSize ticks) {
        this.properties = properties;
        this.ticks = ticks;
    }

    /** Every hit in {@code bars} (daily, oldest first). */
    public List<Hit> hits(List<Candle> bars) {
        List<Hit> hits = new ArrayList<>();
        for (int i = 1; i < bars.size(); i++) {
            Hit hit = hit(bars.get(i - 1), bars.get(i));
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /** The hit of {@code day}, or null. */
    public Hit hit(Candle previous, Candle day) {
        double prev = previous.close();
        if (!(prev > 0)) {
            return null;
        }
        AutoRejectionRule rule = properties.autoRejectionOn(day.date());
        double change = day.close() / prev - 1;
        double araLimit = prev * (1 + pct(rule.ara(), prev));
        double tolerance = ticks.tick(araLimit) * 1.0001;
        if (day.close() >= araLimit - tolerance && day.close() >= day.high() - 1e-9 && change > 0) {
            return new Hit(day.date(), Type.ARA, day.close(), change * 100);
        }
        if (prev > rule.minPrice().doubleValue()) {
            double arbLimit = prev * (1 - pct(rule.arb(), prev));
            double arbTolerance = ticks.tick(arbLimit) * 1.0001;
            if (day.close() <= arbLimit + arbTolerance && day.close() <= day.low() + 1e-9 && change < 0) {
                return new Hit(day.date(), Type.ARB, day.close(), change * 100);
            }
        }
        return null;
    }

    /** The limit of a previous close: the band whose upper bound is at or above it. */
    static double pct(List<LimitBand> bands, double previousClose) {
        BigDecimal prev = BigDecimal.valueOf(previousClose);
        for (LimitBand band : bands) {
            if (band.upTo() == null || prev.compareTo(band.upTo()) <= 0) {
                return band.pct().doubleValue();
            }
        }
        return bands.getLast().pct().doubleValue();
    }
}
