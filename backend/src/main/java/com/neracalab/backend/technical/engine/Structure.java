package com.neracalab.backend.technical.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.neracalab.backend.technical.TechnicalProperties;
import com.neracalab.backend.technical.idx.IdxTickSize;

/**
 * Price structure of a daily series (custom code, no library): swing points, support / resistance zones, the
 * volume-by-price profile, retracements of the last major swing, a base, 52-week and all-time levels. Every level is
 * rounded to a valid IDX price ({@link IdxTickSize}); the lower edge of a zone or bucket down, the upper edge up.
 */
public final class Structure {

    /** A swing high or low: the bar's high (low) is the extreme of {@code barsEachSide} bars on either side. */
    public record Pivot(LocalDate date, int index, double price, boolean high) {
    }

    /** A support / resistance zone: swing points within the tolerance of each other. */
    public record Zone(double low, double high, double mid, String type, int touches, LocalDate firstTouch,
                       LocalDate lastTouch, double distancePct) {
    }

    /** One price bucket of the volume profile. */
    public record ProfileBucket(double low, double high, double price, double volume, double sharePct) {
    }

    public record VolumeProfile(int days, LocalDate from, LocalDate to, List<ProfileBucket> buckets,
                                List<ProfileBucket> topNodes) {
    }

    /** A retracement level: {@code ratio} of the swing retraced from its end. */
    public record Level(double ratio, double price, double distancePct) {
    }

    /**
     * Retracements of the last major swing (at least {@code majorSwingAtr} x ATR 14).
     *
     * @param direction UP when the swing rose from {@code start} to {@code end}, DOWN when it fell
     */
    public record Retracement(String direction, Pivot start, Pivot end, double amplitude, double atr,
                              List<Level> levels) {
    }

    /** A base: a sideways range of {@code days} bars ending at the last bar, with contracting volatility. */
    public record Base(LocalDate start, LocalDate end, int days, double high, double low, double depthPct,
                       double atrPctStart, double atrPctEnd) {
    }

    public record Extremes(double week52High, LocalDate week52HighDate, double week52Low, LocalDate week52LowDate,
                           double distanceFromHighPct, double distanceFromLowPct, double allTimeHigh,
                           LocalDate allTimeHighDate, double drawdownPct) {
    }

    private final TechnicalProperties properties;
    private final IdxTickSize ticks;

    public Structure(TechnicalProperties properties, IdxTickSize ticks) {
        this.properties = properties;
        this.ticks = ticks;
    }

    // ------------------------------------------------------------------ pivots

    /**
     * Swing highs and lows (fractals): bar i is a swing high when its high is above the highs of the {@code k} bars
     * before it and not below the highs of the {@code k} bars after it (a flat top counts once, at its first bar); the
     * last {@code k} bars cannot be confirmed yet. Lows mirror this.
     */
    public List<Pivot> pivots(List<Candle> bars) {
        int k = properties.pivot().barsEachSide();
        List<Pivot> pivots = new ArrayList<>();
        for (int i = k; i < bars.size() - k; i++) {
            Candle bar = bars.get(i);
            boolean high = true;
            boolean low = true;
            for (int j = 1; j <= k; j++) {
                high &= bar.high() > bars.get(i - j).high() && bar.high() >= bars.get(i + j).high();
                low &= bar.low() < bars.get(i - j).low() && bar.low() <= bars.get(i + j).low();
            }
            if (high) {
                pivots.add(new Pivot(bar.date(), i, bar.high(), true));
            }
            if (low) {
                pivots.add(new Pivot(bar.date(), i, bar.low(), false));
            }
        }
        return pivots;
    }

    // ------------------------------------------------------------------ zones

    /**
     * Support / resistance zones from the swing points of the last {@code lookbackDays} bars: sorted by price, a point
     * joins the current cluster while it is within {@code tolerancePct} of the cluster's mean, else it starts a new one.
     * A zone spans its points (at least +/- {@code minWidthPct} around its mean); below the last close it is SUPPORT,
     * above it RESISTANCE, around it PIVOT. The strongest {@code maxZones} are returned (most touches, then most
     * recent), ordered by price from the top.
     */
    public List<Zone> zones(List<Candle> bars, List<Pivot> pivots) {
        if (bars.isEmpty()) {
            return List.of();
        }
        var cfg = properties.zones();
        int fromIndex = Math.max(0, bars.size() - cfg.lookbackDays());
        List<Pivot> recent = pivots.stream().filter(p -> p.index() >= fromIndex)
                .sorted(Comparator.comparingDouble(Pivot::price)).toList();
        double close = bars.getLast().close();
        List<List<Pivot>> clusters = new ArrayList<>();
        List<Pivot> current = new ArrayList<>();
        double sum = 0;
        for (Pivot p : recent) {
            if (!current.isEmpty() && Math.abs(p.price() - sum / current.size()) > sum / current.size() * cfg.tolerancePct()) {
                clusters.add(current);
                current = new ArrayList<>();
                sum = 0;
            }
            current.add(p);
            sum += p.price();
        }
        if (!current.isEmpty()) {
            clusters.add(current);
        }
        List<Zone> zones = new ArrayList<>();
        for (List<Pivot> cluster : clusters) {
            double mean = cluster.stream().mapToDouble(Pivot::price).average().orElseThrow();
            double low = Math.min(cluster.stream().mapToDouble(Pivot::price).min().orElseThrow(), mean * (1 - cfg.minWidthPct()));
            double high = Math.max(cluster.stream().mapToDouble(Pivot::price).max().orElseThrow(), mean * (1 + cfg.minWidthPct()));
            double lowTick = ticks.floor(low);
            double highTick = ticks.ceil(high);
            double mid = ticks.round(mean);
            String type = highTick < close ? "SUPPORT" : lowTick > close ? "RESISTANCE" : "PIVOT";
            LocalDate first = cluster.stream().map(Pivot::date).min(Comparator.naturalOrder()).orElseThrow();
            LocalDate last = cluster.stream().map(Pivot::date).max(Comparator.naturalOrder()).orElseThrow();
            zones.add(new Zone(lowTick, highTick, mid, type, cluster.size(), first, last, Stats.pctChange(mid, close)));
        }
        return zones.stream()
                .sorted(Comparator.comparingInt(Zone::touches).reversed().thenComparing(Zone::lastTouch, Comparator.reverseOrder()))
                .limit(cfg.maxZones())
                .sorted(Comparator.comparingDouble(Zone::mid).reversed())
                .toList();
    }

    // ------------------------------------------------------------------ volume profile

    /**
     * Volume by price over the last {@code days} bars: the range from the lowest low to the highest high is split into
     * {@code buckets} equal buckets, and each bar's volume is spread evenly over the part of its high-low range in each
     * bucket (a bar without range puts all of it at its close). The top nodes are the buckets with the most volume;
     * their price is the bucket's middle, rounded to a valid price.
     */
    public VolumeProfile volumeProfile(List<Candle> bars) {
        var cfg = properties.volumeProfile();
        if (bars.isEmpty()) {
            return new VolumeProfile(0, null, null, List.of(), List.of());
        }
        List<Candle> window = bars.subList(Math.max(0, bars.size() - cfg.days()), bars.size());
        double min = window.stream().mapToDouble(Candle::low).min().orElseThrow();
        double max = window.stream().mapToDouble(Candle::high).max().orElseThrow();
        int count = cfg.buckets();
        double[] volume = new double[count];
        double size = (max - min) / count;
        for (Candle bar : window) {
            if (size <= 0 || bar.high() <= bar.low()) {
                volume[bucket(bar.close(), min, size, count)] += bar.volume();
                continue;
            }
            double range = bar.high() - bar.low();
            for (int b = 0; b < count; b++) {
                double lo = min + b * size;
                double hi = lo + size;
                double overlap = Math.min(hi, bar.high()) - Math.max(lo, bar.low());
                if (overlap > 0) {
                    volume[b] += bar.volume() * overlap / range;
                }
            }
        }
        double total = 0;
        for (double v : volume) {
            total += v;
        }
        List<ProfileBucket> buckets = new ArrayList<>();
        for (int b = 0; b < count; b++) {
            double lo = min + b * size;
            double hi = b == count - 1 ? max : lo + size;
            buckets.add(new ProfileBucket(lo, hi, ticks.round((lo + hi) / 2), volume[b], total > 0 ? volume[b] / total * 100 : 0));
        }
        // without volume (a currency pair) there is no node: every bucket is empty
        List<ProfileBucket> top = total <= 0 ? List.of() : buckets.stream()
                .sorted(Comparator.comparingDouble(ProfileBucket::volume).reversed())
                .limit(cfg.topNodes()).toList();
        return new VolumeProfile(window.size(), window.getFirst().date(), window.getLast().date(), buckets, top);
    }

    private static int bucket(double price, double min, double size, int count) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(count - 1, (int) ((price - min) / size)));
    }

    // ------------------------------------------------------------------ retracements

    /**
     * Retracements of the last major swing. Swings come from a zigzag over the bars: a turn is confirmed when price moves
     * against the running extreme by at least {@code majorSwingAtr} x the latest ATR(14) (highs for an up leg, lows for
     * a down leg). The last leg is the one from the last confirmed turn to the extreme since then, when it is that large
     * itself; else the last confirmed leg. Levels are measured back from the swing's end: for an up swing end - r x
     * (end - start), for a down swing end + r x (start - end).
     *
     * @return null when there is no such swing (too little history or no ATR)
     */
    public Retracement retracement(List<Candle> bars, double atr) {
        if (bars.size() < 3 || !(atr > 0)) {
            return null;
        }
        double threshold = properties.retracement().majorSwingAtr() * atr;
        List<Pivot> turns = new ArrayList<>();
        int dir = 0;                     // 1 up leg, -1 down leg, 0 unknown
        int extIdx = 0;
        double extPrice = bars.getFirst().close();
        int startIdx = 0;
        double startHigh = bars.getFirst().high();
        double startLow = bars.getFirst().low();
        int hiIdx = 0;
        int loIdx = 0;
        for (int i = 1; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            if (dir == 0) {
                if (bar.high() > startHigh) {
                    startHigh = bar.high();
                    hiIdx = i;
                }
                if (bar.low() < startLow) {
                    startLow = bar.low();
                    loIdx = i;
                }
                if (startHigh - startLow >= threshold) {
                    // the first leg runs from the earlier extreme to the later one
                    if (loIdx < hiIdx) {
                        turns.add(new Pivot(bars.get(loIdx).date(), loIdx, startLow, false));
                        dir = 1;
                        extIdx = hiIdx;
                        extPrice = startHigh;
                    } else {
                        turns.add(new Pivot(bars.get(hiIdx).date(), hiIdx, startHigh, true));
                        dir = -1;
                        extIdx = loIdx;
                        extPrice = startLow;
                    }
                }
                continue;
            }
            if (dir == 1) {
                if (bar.high() >= extPrice) {
                    extPrice = bar.high();
                    extIdx = i;
                } else if (extPrice - bar.low() >= threshold) {
                    turns.add(new Pivot(bars.get(extIdx).date(), extIdx, extPrice, true));
                    dir = -1;
                    extPrice = bar.low();
                    extIdx = i;
                }
            } else {
                if (bar.low() <= extPrice) {
                    extPrice = bar.low();
                    extIdx = i;
                } else if (bar.high() - extPrice >= threshold) {
                    turns.add(new Pivot(bars.get(extIdx).date(), extIdx, extPrice, false));
                    dir = 1;
                    extPrice = bar.high();
                    extIdx = i;
                }
            }
        }
        if (turns.isEmpty()) {
            return null;
        }
        Pivot last = turns.getLast();
        Pivot running = new Pivot(bars.get(extIdx).date(), extIdx, extPrice, dir == 1);
        Pivot start;
        Pivot end;
        if (Math.abs(running.price() - last.price()) >= threshold) {
            start = last;
            end = running;
        } else if (turns.size() >= 2) {
            start = turns.get(turns.size() - 2);
            end = last;
        } else {
            return null;
        }
        boolean up = end.price() > start.price();
        double amplitude = Math.abs(end.price() - start.price());
        double close = bars.getLast().close();
        List<Level> levels = new ArrayList<>();
        for (double r : properties.retracement().ratios()) {
            double price = up ? end.price() - r * amplitude : end.price() + r * amplitude;
            double rounded = ticks.round(price);
            levels.add(new Level(r, rounded, Stats.pctChange(rounded, close)));
        }
        return new Retracement(up ? "UP" : "DOWN", roundPivot(start), roundPivot(end), amplitude, atr, levels);
    }

    private Pivot roundPivot(Pivot p) {
        return new Pivot(p.date(), p.index(), ticks.round(p.price()), p.high());
    }

    // ------------------------------------------------------------------ base

    /**
     * The longest base ending at the last bar ({@link TechnicalProperties.Base}): {@code null} when no window of
     * {@code minDays} .. {@code maxDays} bars qualifies.
     */
    public Base base(List<Candle> bars, double[] atrPct) {
        var cfg = properties.base();
        int n = bars.size();
        for (int days = Math.min(cfg.maxDays(), n); days >= cfg.minDays(); days--) {
            int from = n - days;
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (int i = from; i < n; i++) {
                high = Math.max(high, bars.get(i).high());
                low = Math.min(low, bars.get(i).low());
            }
            double depth = (high - low) / low;
            if (depth > cfg.maxDepthPct()) {
                continue;
            }
            int third = days / 3;
            double start = Stats.mean(atrPct, from, from + third - 1);
            double end = Stats.mean(atrPct, n - third, n - 1);
            if (Double.isNaN(start) || Double.isNaN(end) || end > start * cfg.maxAtrContraction()) {
                continue;
            }
            return new Base(bars.get(from).date(), bars.getLast().date(), days, ticks.ceil(high), ticks.floor(low),
                    depth * 100, start, end);
        }
        return null;
    }

    // ------------------------------------------------------------------ extremes

    /** 52-week (last 252 bars) high / low and the all-time high, with the close's distance from them. */
    public Extremes extremes(List<Candle> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        int from = Math.max(0, bars.size() - 252);
        Candle hi = bars.get(from);
        Candle lo = bars.get(from);
        for (int i = from; i < bars.size(); i++) {
            if (bars.get(i).high() > hi.high()) {
                hi = bars.get(i);
            }
            if (bars.get(i).low() < lo.low()) {
                lo = bars.get(i);
            }
        }
        Candle ath = bars.getFirst();
        for (Candle bar : bars) {
            if (bar.high() > ath.high()) {
                ath = bar;
            }
        }
        double close = bars.getLast().close();
        double high52 = ticks.round(hi.high());
        double low52 = ticks.round(lo.low());
        double athPrice = ticks.round(ath.high());
        return new Extremes(high52, hi.date(), low52, lo.date(), Stats.pctChange(close, high52), Stats.pctChange(close, low52),
                athPrice, ath.date(), Stats.pctChange(close, athPrice));
    }
}
