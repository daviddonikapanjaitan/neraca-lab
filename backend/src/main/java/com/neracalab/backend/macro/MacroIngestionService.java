package com.neracalab.backend.macro;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.macro.MacroRepository.Row;
import com.neracalab.backend.macro.source.MacroSource;
import com.neracalab.backend.macro.source.MacroSource.Observation;

/**
 * Fetches one macro series from its source into {@code macro_series}:
 * <ul>
 *   <li>Incremental: from {@code overlap-days} before the latest stored observation (recent revisions are picked up);
 *       the first run, or {@code full}, from the series' {@code history_from}.</li>
 *   <li>Checked before anything is stored: a value outside the series' range, a date twice or a date in the future
 *       fails the job and nothing is written. A move larger than {@code max_change} between two observations is a
 *       warning in the result.</li>
 *   <li>Each observation gets the end of its period and its release date (period end + the publication lag); a changed
 *       value is a revision (counted, the first release date kept).</li>
 * </ul>
 * A manual series has no source: its observations come from a CSV upload ({@link #store}).
 */
@Service
public class MacroIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MacroIngestionService.class);
    static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    /**
     * Outcome of one ingestion.
     *
     * @param requests      requests to the source (0: a manual upload)
     * @param received      observations the source returned
     * @param kept          of those, the ones in the fetched window (on or after {@code requestedFrom})
     * @param inserted      new observations
     * @param revised       observations whose value changed
     * @param unchanged     observations equal to the stored ones
     * @param latestDate    the latest observation after the ingestion
     * @param latestRelease its release date
     * @param warnings      large moves between two observations (at most 20)
     * @param revisions     "2026-08-01: 3.10 -> 3.12" (at most 20)
     */
    public record Result(String source, int requests, LocalDate requestedFrom, boolean fullHistory, int received,
                         int kept, int inserted, int revised, int unchanged, LocalDate latestDate,
                         LocalDate latestRelease, List<String> warnings, List<String> revisions) {
    }

    private final MacroRepository repository;
    private final Map<String, MacroSource> sources;
    private final MacroProperties properties;
    private final Clock clock;

    @Autowired
    public MacroIngestionService(MacroRepository repository, List<MacroSource> sources, MacroProperties properties) {
        this(repository, sources, properties, Clock.system(JAKARTA));
    }

    MacroIngestionService(MacroRepository repository, List<MacroSource> sources, MacroProperties properties, Clock clock) {
        this.repository = repository;
        this.sources = sources.stream().collect(Collectors.toMap(MacroSource::name, Function.identity()));
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(JAKARTA));
    }

    /** Fetches a series from its source and stores what is new or revised. */
    public Result ingest(MacroSeries series, boolean full) {
        if (series.manual()) {
            throw new IllegalArgumentException(series.code() + " has no source a program may read: upload a CSV");
        }
        MacroSource source = sources.get(series.source());
        if (source == null) {
            throw new IllegalStateException("No macro source '" + series.source() + "' for " + series.code());
        }
        LocalDate today = today();
        Optional<LocalDate> latest = repository.latestDate(series.code());
        boolean fullHistory = full || latest.isEmpty();
        LocalDate from = fullHistory ? series.historyFrom()
                : max(series.historyFrom(), latest.get().minusDays(properties.overlapDays()));
        List<Observation> received = source.fetch(series, from, today);
        List<Observation> kept = received.stream().filter(o -> !o.date().isBefore(from)).toList();
        Result result = store(series, kept, series.source(), 1, from, fullHistory, received.size());
        log.info("macro {}: {}", series.code(), result);
        return result;
    }

    /**
     * Checks and stores observations (a fetch, or a manual upload with {@code requests} 0).
     *
     * @throws SourceException when a value is out of range, a date is twice or in the future: nothing is stored
     */
    public Result store(MacroSeries series, List<Observation> observations, String source, int requests,
                        LocalDate requestedFrom, boolean fullHistory, int received) {
        LocalDate today = today();
        Set<LocalDate> seen = new HashSet<>();
        for (Observation o : observations) {
            if (!seen.add(o.date())) {
                throw new SourceException(series.code() + ": " + o.date() + " is given twice");
            }
            if (o.date().isAfter(today)) {
                throw new SourceException(series.code() + ": " + o.date() + " is in the future");
            }
            if (o.value().compareTo(series.minValue()) < 0 || o.value().compareTo(series.maxValue()) > 0) {
                throw new SourceException(series.code() + ": " + o.value().stripTrailingZeros().toPlainString() + " on "
                        + o.date() + " is outside the range " + plain(series.minValue()) + " .. " + plain(series.maxValue())
                        + " " + series.unit() + "; nothing was stored");
            }
        }
        List<Observation> sorted = observations.stream()
                .sorted(java.util.Comparator.comparing(Observation::date)).toList();
        List<String> warnings = new ArrayList<>();
        if (series.maxChange() != null) {
            for (int i = 1; i < sorted.size() && warnings.size() < 20; i++) {
                BigDecimal move = sorted.get(i).value().subtract(sorted.get(i - 1).value()).abs();
                if (move.compareTo(series.maxChange()) > 0) {
                    warnings.add(sorted.get(i).date() + ": moved " + plain(move) + " " + series.unit() + " from "
                            + plain(sorted.get(i - 1).value()) + " to " + plain(sorted.get(i).value()));
                }
            }
        }
        int inserted = 0;
        int revised = 0;
        int unchanged = 0;
        List<String> revisions = new ArrayList<>();
        if (!sorted.isEmpty()) {
            Map<LocalDate, BigDecimal> stored = repository.values(series.code(), sorted.getFirst().date(),
                    sorted.getLast().date());
            List<Row> rows = new ArrayList<>(sorted.size());
            for (Observation o : sorted) {
                BigDecimal before = stored.get(o.date());
                if (before == null) {
                    inserted++;
                } else if (before.compareTo(o.value()) != 0) {
                    revised++;
                    if (revisions.size() < 20) {
                        revisions.add(o.date() + ": " + plain(before) + " -> " + plain(o.value()));
                    }
                } else {
                    unchanged++;
                }
                rows.add(new Row(o.date(), o.value(), series.periodEnd(o.date()), series.releaseDate(o.date())));
            }
            repository.upsert(series.code(), source, rows);
        }
        LocalDate latest = repository.latestDate(series.code()).orElse(null);
        return new Result(source, requests, requestedFrom, fullHistory, received, sorted.size(), inserted, revised,
                unchanged, latest, latest == null ? null : series.releaseDate(latest), List.copyOf(warnings),
                List.copyOf(revisions));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
