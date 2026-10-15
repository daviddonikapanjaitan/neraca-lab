package com.neracalab.backend.macro;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.neracalab.backend.crawl.SourceException;
import com.neracalab.backend.job.Requester;
import com.neracalab.backend.macro.MacroIngestionQueue.Submission;
import com.neracalab.backend.macro.MacroRepository.LastJob;
import com.neracalab.backend.macro.MacroRepository.Stats;
import com.neracalab.backend.macro.source.MacroSource.Observation;

/**
 * Ingestion › Macro: what is stored of every macro series, the ingestion of one series, "Update all macro data" (every
 * series with a source whose latest observation is older than the one that should be public by now) and the CSV upload
 * of the series without a source a program may read.
 */
@Service
public class MacroService {

    private static final Logger log = LoggerFactory.getLogger(MacroService.class);

    /** Largest CSV upload. */
    static final int MAX_UPLOAD_BYTES = 2_000_000;

    /**
     * A series with what is stored of it.
     *
     * @param expectedLatest the latest observation that should be public today (older: BEHIND)
     * @param state          UP_TO_DATE, BEHIND, NEVER_FETCHED, FAILING (its last job failed and it is not up to date) or
     *                       MANUAL (no source a program may read: CSV upload)
     */
    public record SeriesStatus(String code, String name, String country, String category, String unit, String frequency,
                               String source, String sourceLabel, String sourceUrl, int releaseLagDays, int priority,
                               List<String> usedBy, String description, int rows, LocalDate firstDate,
                               LocalDate lastDate, BigDecimal lastValue, LocalDate lastRelease,
                               OffsetDateTime updatedAt, LocalDate expectedLatest, String state, String lastJobStatus,
                               String lastJobMessage) {
    }

    public record Status(LocalDate today, int series, int upToDate, int behind, int neverFetched, int failing,
                         int manual, int pending, List<SeriesStatus> items) {
    }

    /**
     * Outcome of "Update all macro data".
     *
     * @param queued        series queued now
     * @param alreadyActive series that already had a queued or running job
     * @param upToDate      series skipped: up to date
     * @param manual        series without a source (CSV upload)
     * @param firstJob      the first job queued ({@code null}: none)
     */
    public record Update(int series, int queued, int alreadyActive, int upToDate, int manual, MacroJob.View firstJob) {
    }

    /** The observations of one series for its chart. */
    public record History(SeriesStatus series, List<List<Object>> points) {
    }

    /** No active series of that code. */
    public static class SeriesNotFoundException extends RuntimeException {
        public SeriesNotFoundException(String code) {
            super("No macro series '" + code + "'");
        }
    }

    private final MacroRepository repository;
    private final MacroIngestionService ingestion;
    private final MacroIngestionQueue queue;
    private final MacroIngestionTracker tracker;

    public MacroService(MacroRepository repository, MacroIngestionService ingestion, MacroIngestionQueue queue,
                        MacroIngestionTracker tracker) {
        this.repository = repository;
        this.ingestion = ingestion;
        this.queue = queue;
        this.tracker = tracker;
    }

    public Status status() {
        LocalDate today = ingestion.today();
        Map<String, Stats> stats = repository.stats();
        Map<String, LastJob> jobs = repository.lastJobs();
        List<SeriesStatus> items = new ArrayList<>();
        int upToDate = 0;
        int behind = 0;
        int never = 0;
        int failing = 0;
        int manual = 0;
        for (MacroSeries s : repository.series()) {
            SeriesStatus item = status(s, stats.get(s.code()), jobs.get(s.code()), today);
            switch (item.state()) {
                case "UP_TO_DATE" -> upToDate++;
                case "BEHIND" -> behind++;
                case "NEVER_FETCHED" -> never++;
                case "FAILING" -> failing++;
                default -> manual++;
            }
            items.add(item);
        }
        return new Status(today, items.size(), upToDate, behind, never, failing, manual, queue.pendingCount(), items);
    }

    private static SeriesStatus status(MacroSeries s, Stats stats, LastJob job, LocalDate today) {
        Stats st = stats == null ? new Stats(0, null, null, null, null, null) : stats;
        boolean current = s.upToDate(st.lastDate(), today);
        String state;
        if (s.manual()) {
            state = "MANUAL";
        } else if (job != null && "FAILED".equals(job.status()) && !current) {
            state = "FAILING";
        } else if (st.rows() == 0) {
            state = "NEVER_FETCHED";
        } else {
            state = current ? "UP_TO_DATE" : "BEHIND";
        }
        return new SeriesStatus(s.code(), s.name(), s.country(), s.category(), s.unit(), s.frequency(), s.source(),
                MacroIngestionTracker.sourceLabel(s.source()), s.sourceUrl(), s.releaseLagDays(), s.priority(),
                Arrays.stream(s.usedBy().split(",")).map(String::trim).filter(u -> !u.isEmpty()).toList(),
                s.description(), st.rows(), st.firstDate(), st.lastDate(), st.lastValue(), st.lastRelease(),
                st.updatedAt(), s.expectedLatest(today), state, job == null ? null : job.status(),
                job == null ? null : job.message());
    }

    public History history(String code, LocalDate from) {
        MacroSeries s = series(code);
        List<List<Object>> points = new ArrayList<>();
        for (MacroRepository.Row r : repository.observations(s.code(), from)) {
            points.add(List.of(r.obsDate().toString(), r.value(),
                    r.releaseDate() == null ? r.obsDate().toString() : r.releaseDate().toString()));
        }
        SeriesStatus status = status(s, repository.stats(s.code()), repository.lastJobs().get(s.code()), ingestion.today());
        return new History(status, points);
    }

    /** Queues one series (also when it is up to date: the source is asked again). */
    public Submission submit(String code, boolean full, Requester requestedBy) {
        MacroSeries s = series(code);
        if (s.manual()) {
            throw new IllegalArgumentException(s.name() + " has no source a program may read: upload a CSV");
        }
        return queue.submit(s, full, requestedBy);
    }

    /** Queues every series with a source that is not up to date. */
    public Update updateAll(Requester requestedBy) {
        LocalDate today = ingestion.today();
        Map<String, Stats> stats = repository.stats();
        int queued = 0;
        int active = 0;
        int upToDate = 0;
        int manual = 0;
        MacroJob.View first = null;
        List<MacroSeries> all = repository.series();
        for (MacroSeries s : all) {
            if (s.manual()) {
                manual++;
                continue;
            }
            Stats st = stats.get(s.code());
            if (st != null && s.upToDate(st.lastDate(), today)) {
                upToDate++;
                continue;
            }
            Submission submission = queue.submit(s, false, requestedBy);
            if (submission.created()) {
                queued++;
                if (first == null) {
                    first = submission.job().view();
                }
            } else {
                active++;
            }
        }
        log.info("Macro update: {} series, {} queued, {} already active, {} up to date, {} manual", all.size(), queued,
                active, upToDate, manual);
        return new Update(all.size(), queued, active, upToDate, manual, first);
    }

    /**
     * Stores the observations of a CSV ({@code obs_date,value} per line, ISO dates, a header line is allowed; a monthly
     * series is dated on the 1st), checked like a fetch; recorded as a MACRO job. Observations already stored on other
     * dates are kept.
     */
    public MacroIngestionService.Result upload(String code, byte[] csv, Requester requestedBy) {
        MacroSeries s = series(code);
        if (csv == null || csv.length == 0) {
            throw new IllegalArgumentException("The CSV file is empty");
        }
        if (csv.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("The CSV file is larger than " + MAX_UPLOAD_BYTES / 1_000_000 + " MB");
        }
        MacroJob job = new MacroJob(s, false, requestedBy);
        job.running();
        try {
            List<Observation> observations = parseCsv(new String(csv, java.nio.charset.StandardCharsets.UTF_8), s);
            MacroIngestionService.Result result = ingestion.store(s, observations, "manual", 0,
                    observations.isEmpty() ? null : observations.getFirst().date(), false, observations.size());
            job.succeeded(result);
            return result;
        } catch (RuntimeException e) {
            job.failed(e.getMessage());
            throw e;
        } finally {
            tracker.changed(job.view());
        }
    }

    static List<Observation> parseCsv(String text, MacroSeries series) {
        List<Observation> out = new ArrayList<>();
        String[] lines = text.replace("﻿", "").replace("\r", "").split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split("[,;\t]");
            if (i == 0 && !Character.isDigit(cells[0].trim().isEmpty() ? 'x' : cells[0].trim().charAt(0))) {
                continue;   // header
            }
            if (cells.length < 2) {
                throw new SourceException("CSV line " + (i + 1) + ": expected obs_date,value, got '" + line + "'");
            }
            LocalDate date;
            BigDecimal value;
            try {
                date = LocalDate.parse(cells[0].trim());
                value = new BigDecimal(cells[1].trim());
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new SourceException("CSV line " + (i + 1) + ": expected an ISO date (2026-10-15) and a number with a "
                        + "decimal point, got '" + line + "'");
            }
            if (series.monthly() && date.getDayOfMonth() != 1) {
                throw new SourceException("CSV line " + (i + 1) + ": " + series.name() + " is monthly: date a month on "
                        + "its 1st (" + date.withDayOfMonth(1) + "), not " + date);
            }
            out.add(new Observation(date, value));
        }
        if (out.isEmpty()) {
            throw new SourceException("The CSV has no observation (expected lines obs_date,value)");
        }
        return out;
    }

    private MacroSeries series(String code) {
        return repository.series(code).orElseThrow(() -> new SeriesNotFoundException(code));
    }
}
