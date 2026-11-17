package com.neracalab.backend.macro.source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.neracalab.backend.crawl.PoliteHttpClient;
import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroProperties;
import com.neracalab.backend.macro.MacroSeries;

/**
 * CHIRPS v3.0 monthly rainfall of a region ({@code source = chirps}; {@code source_ref} a key of {@link #REGIONS}), from
 * the global monthly cloud-optimized GeoTIFFs of the UCSB Climate Hazards Center
 * ({@code chirps-v3.0.<yyyy>.<mm>.cog}, mm per month, 0.05 degree, land only between 60 S and 60 N).
 * <ul>
 *   <li>The mean of the land pixels whose centre lies in one of the region's boxes, weighted by the cosine of the
 *       latitude; the sea is NoData in CHIRPS, so a box may cross the coast. The boxes avoid foreign land (Sumatra's
 *       boxes leave out Peninsular Malaysia across the Malacca Strait; Kalimantan's stay south of Sarawak).</li>
 *   <li>Only the full-resolution tiles the regions touch are read (HTTP range requests, adjacent tiles in one; about 4
 *       requests a month for every region together). The overviews are not used: they are resampled across the sea
 *       mask and are wrong on the coasts.</li>
 *   <li>One month gives every region, and is kept in memory for {@link #CACHE_TTL}: after the first rainfall series,
 *       the other regions of the same months need no request.</li>
 *   <li>A month is published about two weeks after it ends; a file missing for one of the last three months is not
 *       published yet (the fetch stops there), one missing further back fails the job.</li>
 *   <li>The server throttles a bulk download (HTTP 429 after ~85 requests at 2 s): its host is paced at
 *       {@code neracalab.crawl.host-delays}, a fetch reads at most {@link #MAX_MONTHS_PER_FETCH} months, and a 429
 *       ends the fetch with what it read ({@link MacroSource.PartialFetch}): stored, and asked again later.</li>
 * </ul>
 */
@Component
public class ChirpsSource implements MacroSource {

    /** A box: longitudes {@code lon0 .. lon1} (east positive), latitudes {@code lat0 .. lat1} (north positive). */
    public record Box(double lon0, double lon1, double lat0, double lat1) {
    }

    /** The regions, by {@code source_ref}. */
    public static final Map<String, List<Box>> REGIONS;

    static {
        Map<String, List<Box>> r = new LinkedHashMap<>();
        // Indonesia
        r.put("ID_SUM_PALM", List.of(new Box(98.5, 100.8, 1.0, 4.0), new Box(100.0, 102.4, -1.0, 2.3),
                new Box(102.4, 104.0, -1.0, 1.15), new Box(101.0, 106.0, -5.0, -1.0)));
        r.put("ID_KAL_PALM", List.of(new Box(108.8, 117.5, -4.2, 0.9), new Box(115.8, 118.5, 0.9, 3.5)));
        r.put("ID_KAL_COAL", List.of(new Box(114.3, 116.6, -4.2, -1.3), new Box(115.5, 118.0, -1.3, 2.5)));
        r.put("ID_JAVA_RICE", List.of(new Box(105.0, 114.4, -8.9, -5.8)));
        // the other producers
        r.put("MY_PALM", List.of(new Box(102.4, 104.4, 1.3, 2.8), new Box(101.3, 103.6, 2.8, 4.2),
                new Box(100.3, 103.4, 4.2, 6.2), new Box(115.6, 119.3, 4.4, 7.0), new Box(110.0, 113.5, 1.7, 3.5),
                new Box(113.5, 115.0, 3.0, 4.9)));
        r.put("AU_COAL", List.of(new Box(147.0, 150.0, -25.5, -21.0), new Box(150.3, 151.8, -33.0, -32.0)));
        r.put("IN_MONSOON", List.of(new Box(72.5, 88.0, 10.0, 26.0)));
        REGIONS = java.util.Collections.unmodifiableMap(r);
    }

    static final Duration CACHE_TTL = Duration.ofHours(12);
    /**
     * Months read over the network in one fetch: the first load (1981 - now, about 550 months) is read in chunks of
     * this size, each stored at once and followed by the next fetch at the end of the macro queue, so other series are
     * not held up for hours and a refusal never loses what was read.
     */
    static final int MAX_MONTHS_PER_FETCH = 60;
    /** After an HTTP 429 the server is asked again this much later (the refused request is not retried at once). */
    static final Duration AFTER_RATE_LIMIT = Duration.ofMinutes(15);
    /** The first month CHIRPS covers. */
    static final YearMonth FIRST_MONTH = YearMonth.of(1981, 1);

    private record Cached(Map<String, Double> values, Instant at) {
    }

    private final PoliteHttpClient http;
    private final MacroProperties properties;
    private final Map<YearMonth, Cached> cache = new ConcurrentHashMap<>();

    public ChirpsSource(PoliteHttpClient http, MacroProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "chirps";
    }

    @Override
    public List<Observation> fetch(MacroSeries series, LocalDate from, LocalDate today) {
        String region = series.sourceRef() == null ? "" : series.sourceRef().trim().toUpperCase(Locale.ROOT);
        if (!REGIONS.containsKey(region)) {
            throw new SourceException("CHIRPS: unknown region '" + series.sourceRef() + "' of " + series.code()
                    + " (known: " + String.join(", ", REGIONS.keySet()) + ")");
        }
        YearMonth start = YearMonth.from(from).isBefore(FIRST_MONTH) ? FIRST_MONTH : YearMonth.from(from);
        YearMonth last = YearMonth.from(today).minusMonths(1);
        YearMonth recent = YearMonth.from(today).minusMonths(3);
        List<Observation> out = new ArrayList<>();
        int read = 0;
        for (YearMonth m = start; !m.isAfter(last); m = m.plusMonths(1)) {
            boolean cached = cached(m);
            if (!cached && read >= MAX_MONTHS_PER_FETCH) {
                throw new PartialFetch("CHIRPS: read " + read + " months, up to " + m.minusMonths(1)
                        + "; the next fetch continues from " + m, out, m.atDay(1), Duration.ZERO);
            }
            Map<String, Double> values;
            try {
                values = month(m);
            } catch (SourceException.RateLimited e) {
                throw new PartialFetch("CHIRPS answered HTTP 429 at " + m + " (" + out.size() + " months read before it); "
                        + "asked again in " + AFTER_RATE_LIMIT.toMinutes() + " minutes", out, m.atDay(1), AFTER_RATE_LIMIT);
            }
            if (!cached) {
                read++;
            }
            if (values == null) {
                if (m.isAfter(recent)) {
                    break;  // not published yet
                }
                throw new SourceException("CHIRPS: no file for " + m + " (" + PoliteHttpClient.describe(uri(m)) + ")");
            }
            Double v = values.get(region);
            if (v == null) {
                throw new SourceException("CHIRPS " + m + ": no land pixel with rainfall in region " + region);
            }
            out.add(new Observation(m.atDay(1), BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP)));
        }
        return out;
    }

    private URI uri(YearMonth m) {
        return URI.create(properties.chirpsBaseUrl() + "/chirps-v3.0." + m.getYear() + "."
                + String.format(Locale.ROOT, "%02d", m.getMonthValue()) + ".cog");
    }

    private boolean cached(YearMonth m) {
        Cached c = cache.get(m);
        return c != null && c.at().plus(CACHE_TTL).isAfter(Instant.now());
    }

    /** The mean rainfall of every region in a month (cached); null: the month's file is not published. */
    private Map<String, Double> month(YearMonth m) {
        Instant now = Instant.now();
        Cached c = cache.get(m);
        if (c != null && c.at().plus(CACHE_TTL).isAfter(now)) {
            return c.values();
        }
        cache.entrySet().removeIf(e -> e.getValue().at().plus(CACHE_TTL).isBefore(now));
        CogReader reader = CogReader.open(http, uri(m));
        if (reader == null) {
            return null;
        }
        Map<String, Double> values = regionMeans(reader);
        cache.put(m, new Cached(values, now));
        return values;
    }

    /** The cos(latitude)-weighted mean of the valid pixels of every region (null: none valid). */
    static Map<String, Double> regionMeans(CogReader reader) {
        Map<String, Set<Long>> pixels = new LinkedHashMap<>();
        TreeSet<Integer> tiles = new TreeSet<>();
        for (Map.Entry<String, List<Box>> region : REGIONS.entrySet()) {
            Set<Long> set = new HashSet<>();
            for (Box b : region.getValue()) {
                int c0 = Math.max(0, reader.column(b.lon0()));
                int c1 = Math.min(reader.width - 1, reader.column(b.lon1()));
                int r0 = Math.max(0, reader.row(b.lat1()));
                int r1 = Math.min(reader.height - 1, reader.row(b.lat0()));
                for (int row = r0; row <= r1; row++) {
                    double lat = reader.lat(row);
                    if (lat < b.lat0() || lat > b.lat1()) {
                        continue;
                    }
                    for (int col = c0; col <= c1; col++) {
                        double lon = reader.lon(col);
                        if (lon >= b.lon0() && lon <= b.lon1()) {
                            set.add((long) row * reader.width + col);
                            tiles.add(reader.tileOf(row, col));
                        }
                    }
                }
            }
            pixels.put(region.getKey(), set);
        }
        reader.load(tiles);
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Long>> region : pixels.entrySet()) {
            double sum = 0;
            double weights = 0;
            for (long p : region.getValue()) {
                int row = (int) (p / reader.width);
                int col = (int) (p % reader.width);
                float v = reader.value(row, col);
                if (Float.isNaN(v) || v < 0 || v > 10_000) {
                    continue;   // NoData (the sea: -9999)
                }
                double w = Math.cos(Math.toRadians(reader.lat(row)));
                sum += v * w;
                weights += w;
            }
            out.put(region.getKey(), weights == 0 ? null : sum / weights);
        }
        return out;
    }
}
