package com.neracalab.backend.technical;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Technical analysis ({@code neracalab.technical.*}): every threshold of the indicator engine, the IDX trading rules and
 * the nightly job. Defaults are in {@code application.yaml}; see docs/v1_docs/TECHNICAL_DOCS.md.
 *
 * @param index           the benchmark index of relative strength and the market regime
 * @param minHistoryDays  fewer trading days than this: "limited history" (snapshot {@code history.limited})
 * @param cacheSize       chart series (ticker x interval x price basis) kept in memory
 * @param cacheTtl        how long a cached chart series lives at most (the nightly job clears the cache too)
 * @param tickSizes       IDX price fractions (fraksi harga): tick of a price below {@code below}, the last band open
 * @param autoRejection   IDX auto-rejection limits (ARA / ARB) by effective date and price band
 * @param lotSize         shares per lot
 * @param pivot           swing high / low detection
 * @param zones           support / resistance zones clustered from the swing points
 * @param volumeProfile   volume-by-price profile
 * @param retracement     retracements of the last major swing
 * @param base            base (consolidation) detection
 * @param volume          volume spike threshold and windows
 * @param events          corporate events from Yahoo Finance
 * @param schedule        nightly job: index, events, snapshots
 */
@ConfigurationProperties("neracalab.technical")
public record TechnicalProperties(
        @DefaultValue Index index,
        @DefaultValue("250") int minHistoryDays,
        @DefaultValue("200") int cacheSize,
        @DefaultValue("12h") Duration cacheTtl,
        List<TickBand> tickSizes,
        List<AutoRejectionRule> autoRejection,
        @DefaultValue("100") int lotSize,
        @DefaultValue Pivot pivot,
        @DefaultValue Zones zones,
        @DefaultValue VolumeProfile volumeProfile,
        @DefaultValue Retracement retracement,
        @DefaultValue Base base,
        @DefaultValue Volume volume,
        @DefaultValue Events events,
        @DefaultValue Schedule schedule) {

    public TechnicalProperties {
        tickSizes = tickSizes == null || tickSizes.isEmpty() ? defaultTickSizes() : List.copyOf(tickSizes);
        autoRejection = autoRejection == null || autoRejection.isEmpty() ? defaultAutoRejection()
                : autoRejection.stream().sorted(Comparator.comparing(AutoRejectionRule::from)).toList();
        if (tickSizes.getLast().below() != null) {
            throw new IllegalArgumentException("neracalab.technical.tick-sizes: the last band must have no 'below' (open-ended)");
        }
        if (lotSize <= 0 || minHistoryDays <= 0) {
            throw new IllegalArgumentException("neracalab.technical: lot-size and min-history-days must be positive");
        }
    }

    /** IDX price fractions in force since 2016 (Rp 1 below 200 .. Rp 25 from 5,000). */
    static List<TickBand> defaultTickSizes() {
        return List.of(new TickBand(new BigDecimal("200"), BigDecimal.ONE),
                new TickBand(new BigDecimal("500"), new BigDecimal("2")),
                new TickBand(new BigDecimal("2000"), new BigDecimal("5")),
                new TickBand(new BigDecimal("5000"), new BigDecimal("10")),
                new TickBand(null, new BigDecimal("25")));
    }

    /** ARA 35 / 25 / 20 % by price band; ARB as set by IDX over time (see application.yaml). */
    static List<AutoRejectionRule> defaultAutoRejection() {
        List<LimitBand> ara = List.of(new LimitBand(new BigDecimal("200"), new BigDecimal("0.35")),
                new LimitBand(new BigDecimal("5000"), new BigDecimal("0.25")), new LimitBand(null, new BigDecimal("0.20")));
        return List.of(new AutoRejectionRule(LocalDate.of(1990, 1, 1), ara, ara, null),
                new AutoRejectionRule(LocalDate.of(2020, 3, 10), ara, List.of(new LimitBand(null, new BigDecimal("0.10"))), null),
                new AutoRejectionRule(LocalDate.of(2020, 3, 13), ara, List.of(new LimitBand(null, new BigDecimal("0.07"))), null),
                new AutoRejectionRule(LocalDate.of(2023, 6, 5), ara, List.of(new LimitBand(null, new BigDecimal("0.15"))), null),
                new AutoRejectionRule(LocalDate.of(2023, 9, 4), ara, ara, null),
                new AutoRejectionRule(LocalDate.of(2025, 4, 8), ara, List.of(new LimitBand(null, new BigDecimal("0.15"))), null));
    }

    /** The auto-rejection rule in force on {@code date}. */
    public AutoRejectionRule autoRejectionOn(LocalDate date) {
        AutoRejectionRule rule = autoRejection.getFirst();
        for (AutoRejectionRule r : autoRejection) {
            if (!r.from().isAfter(date)) {
                rule = r;
            }
        }
        return rule;
    }

    /**
     * @param code         code in index_daily and the API, e.g. IHSG
     * @param yahooSymbol  Yahoo Finance symbol, e.g. ^JKSE
     * @param exchange     exchange the index belongs to
     * @param historyFrom  start of the first fetch
     */
    public record Index(@DefaultValue("IHSG") String code, @DefaultValue("^JKSE") String yahooSymbol,
                        @DefaultValue("IDX") String exchange, @DefaultValue("1990-01-01") LocalDate historyFrom) {
    }

    /** Prices below {@code below} (exclusive) trade in steps of {@code tick}; {@code below} null = the last band. */
    public record TickBand(BigDecimal below, BigDecimal tick) {
    }

    /** Limit {@code pct} (0.25 = 25 %) for a previous close up to {@code upTo} (inclusive); null = the last band. */
    public record LimitBand(BigDecimal upTo, BigDecimal pct) {
    }

    /**
     * Auto-rejection limits in force from {@code from}.
     *
     * @param minPrice lowest price of the regular market: no ARB below it
     */
    public record AutoRejectionRule(LocalDate from, List<LimitBand> ara, List<LimitBand> arb, BigDecimal minPrice) {

        public AutoRejectionRule {
            minPrice = minPrice == null ? new BigDecimal("50") : minPrice;
        }
    }

    /** A swing high is a bar whose high is the highest of {@code barsEachSide} bars before and after it (5-bar fractal: 2). */
    public record Pivot(@DefaultValue("2") int barsEachSide) {
    }

    /**
     * @param tolerancePct swing points within this distance (2 % = 0.02) of a zone's mean join it
     * @param lookbackDays swing points of this many recent bars are clustered
     * @param maxZones     zones returned (strongest first, by touches then recency)
     * @param minWidthPct  half-width of a zone made of one swing point
     */
    public record Zones(@DefaultValue("0.02") double tolerancePct, @DefaultValue("250") int lookbackDays,
                        @DefaultValue("8") int maxZones, @DefaultValue("0.005") double minWidthPct) {
    }

    /** Volume-by-price over the last {@code days} bars in {@code buckets} equal price buckets; {@code topNodes} returned. */
    public record VolumeProfile(@DefaultValue("120") int days, @DefaultValue("24") int buckets,
                                @DefaultValue("3") int topNodes) {
    }

    /**
     * @param majorSwingAtr a swing is major when it spans at least this many ATR(14)
     * @param ratios        retracement ratios of the last major swing
     */
    public record Retracement(@DefaultValue("3") double majorSwingAtr,
                              @DefaultValue({"0.33", "0.382", "0.5", "0.618", "0.66"}) List<Double> ratios) {
    }

    /**
     * A base: the longest window of {@code minDays} .. {@code maxDays} bars ending at the last bar whose range (high -
     * low) / low is at most {@code maxDepthPct}, with ATR% contracting: mean ATR% of its last third at most
     * {@code maxAtrContraction} x that of its first third.
     */
    public record Base(@DefaultValue("60") int minDays, @DefaultValue("120") int maxDays,
                       @DefaultValue("0.25") double maxDepthPct, @DefaultValue("0.9") double maxAtrContraction) {
    }

    /** A volume spike: at least {@code spikeMultiple} x the 50-day average volume (of the 50 bars before it). */
    public record Volume(@DefaultValue("2.0") double spikeMultiple) {
    }

    /**
     * @param refreshAfter    events of a company are fetched again after this long
     * @param upcomingDays    "upcoming" events: within this many days after the as-of date
     */
    public record Events(@DefaultValue("7d") Duration refreshAfter, @DefaultValue("60") int upcomingDays) {
    }

    /** The nightly job: refresh the index, fetch due events, recompute the snapshots of every active company. */
    public record Schedule(@DefaultValue("true") boolean enabled, @DefaultValue("0 0 19 * * MON-FRI") String cron,
                           @DefaultValue("Asia/Jakarta") String zone, @DefaultValue("IDX") String exchange) {
    }
}
