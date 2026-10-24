package com.neracalab.backend.macro;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.market.MarketDataChanged;

/** {@code macro_series_def} (the catalog) and {@code macro_series} (the observations). */
@Repository
public class MacroRepository {

    /** An observation to store, with the period it describes and the day it became public. */
    public record Row(LocalDate obsDate, BigDecimal value, LocalDate periodEnd, LocalDate releaseDate) {
    }

    /**
     * What is stored of a series.
     *
     * @param lastRelease the release date of the latest observation
     * @param updatedAt   when an observation was last written
     */
    public record Stats(int rows, LocalDate firstDate, LocalDate lastDate, BigDecimal lastValue, LocalDate lastRelease,
                        OffsetDateTime updatedAt) {
    }

    /** The latest job of a series (Ingestion › Macro shows a failing source). */
    public record LastJob(String status, String message, OffsetDateTime finishedAt) {
    }

    private static final String DEF = """
            SELECT series_code, name, country, category, unit, frequency, source, source_ref, source_url, release_lag_days,
                   priority, used_by, min_value, max_value, max_change, history_from, description
            FROM macro_series_def""";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher events;

    public MacroRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.events = events;
    }

    /** The active series in display order. */
    public List<MacroSeries> series() {
        return jdbc.sql(DEF + " WHERE active ORDER BY sort_order, series_code").query(MacroRepository::mapSeries).list();
    }

    /** An active series by code, case-insensitively. */
    public Optional<MacroSeries> series(String code) {
        return jdbc.sql(DEF + " WHERE active AND series_code = upper(trim(:code))")
                .param("code", code == null ? "" : code)
                .query(MacroRepository::mapSeries).optional();
    }

    private static MacroSeries mapSeries(ResultSet rs, int i) throws SQLException {
        return new MacroSeries(rs.getString("series_code"), rs.getString("name"), rs.getString("country"),
                rs.getString("category"), rs.getString("unit"), rs.getString("frequency"), rs.getString("source"),
                rs.getString("source_ref"), rs.getString("source_url"), rs.getInt("release_lag_days"),
                rs.getInt("priority"), rs.getString("used_by"), rs.getBigDecimal("min_value"),
                rs.getBigDecimal("max_value"), rs.getBigDecimal("max_change"),
                rs.getObject("history_from", LocalDate.class), rs.getString("description"));
    }

    /** What is stored of every series. */
    public Map<String, Stats> stats() {
        Map<String, Stats> out = new HashMap<>();
        jdbc.sql("""
                        SELECT s.series_code, s.rows, s.first_date, s.last_date, l.value, l.release_date, s.updated_at
                        FROM (SELECT series_code, count(*) AS rows, min(obs_date) AS first_date, max(obs_date) AS last_date,
                                     max(updated_at) AS updated_at
                              FROM macro_series GROUP BY series_code) s
                        JOIN macro_series l ON l.series_code = s.series_code AND l.obs_date = s.last_date""")
                .query((rs, i) -> out.put(rs.getString(1), new Stats(rs.getInt(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class), rs.getBigDecimal(5), rs.getObject(6, LocalDate.class),
                        rs.getObject(7, OffsetDateTime.class))))
                .list();
        return out;
    }

    public Stats stats(String code) {
        return stats().getOrDefault(code, new Stats(0, null, null, null, null, null));
    }

    /** The latest MACRO job of every series. */
    public Map<String, LastJob> lastJobs() {
        Map<String, LastJob> out = new HashMap<>();
        jdbc.sql("""
                        SELECT DISTINCT ON (ticker) ticker, status, message, finished_at
                        FROM ingestion_job WHERE job_type = 'MACRO'
                        ORDER BY ticker, requested_at DESC""")
                .query((rs, i) -> out.put(rs.getString(1), new LastJob(rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class))))
                .list();
        return out;
    }

    /** Observations of a series from {@code from} (null: all), oldest first: date, value, period end, release date. */
    public List<Row> observations(String code, LocalDate from) {
        return jdbc.sql("""
                        SELECT obs_date, value, period_end, release_date FROM macro_series
                        WHERE series_code = :code AND (CAST(:from AS date) IS NULL OR obs_date >= CAST(:from AS date))
                        ORDER BY obs_date""")
                .param("code", code)
                .param("from", from, java.sql.Types.DATE)
                .query((rs, i) -> new Row(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2),
                        rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class)))
                .list();
    }

    public Optional<LocalDate> latestDate(String code) {
        return jdbc.sql("SELECT max(obs_date) FROM macro_series WHERE series_code = :code")
                .param("code", code).query(LocalDate.class).optional();
    }

    /** The stored value of every observation of a series between {@code from} and {@code to}. */
    public Map<LocalDate, BigDecimal> values(String code, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        jdbc.sql("SELECT obs_date, value FROM macro_series WHERE series_code = :code AND obs_date BETWEEN :from AND :to")
                .param("code", code).param("from", from).param("to", to)
                .query((rs, i) -> out.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
        return out;
    }

    /**
     * Upserts observations. A changed value is a revision: the value is replaced and {@code revision} counts up; the
     * release date of the first publication is kept (no vintages are stored). An unchanged observation is not written.
     */
    @Transactional
    public void upsert(String code, String source, List<Row> rows) {
        int[][] counts = jdbcTemplate.batchUpdate("""
                        INSERT INTO macro_series (series_code, obs_date, value, source, period_end, release_date, revision,
                                                  updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 0, now())
                        ON CONFLICT ON CONSTRAINT uq_macro_series DO UPDATE SET
                            value = EXCLUDED.value,
                            source = EXCLUDED.source,
                            period_end = EXCLUDED.period_end,
                            release_date = coalesce(macro_series.release_date, EXCLUDED.release_date),
                            revision = macro_series.revision
                                       + CASE WHEN macro_series.value <> EXCLUDED.value THEN 1 ELSE 0 END,
                            updated_at = now()
                        WHERE macro_series.value <> EXCLUDED.value
                           OR macro_series.release_date IS NULL
                           OR macro_series.period_end IS DISTINCT FROM EXCLUDED.period_end""",
                rows, 500, (ps, row) -> {
                    ps.setString(1, code);
                    ps.setObject(2, row.obsDate());
                    ps.setBigDecimal(3, row.value());
                    ps.setString(4, source);
                    ps.setObject(5, row.periodEnd());
                    ps.setObject(6, row.releaseDate());
                });
        if (MarketDataChanged.written(counts)) {
            events.publishEvent(new MarketDataChanged("macro_series"));
        }
    }
}
