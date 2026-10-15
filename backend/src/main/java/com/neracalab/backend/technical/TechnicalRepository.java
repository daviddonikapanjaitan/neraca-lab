package com.neracalab.backend.technical;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.technical.engine.Candle;

/**
 * Reads and writes of the technical analysis: bars from {@code price_daily} and {@code index_daily}, events, macro
 * series, snapshots and chart settings.
 * <p>
 * Bars are in the exchange's currency. A company that reports in another currency has its prices stored converted
 * ({@code price_daily} is in {@code company.currency}: INDY's IDR quote / the USD/IDR rate of the last FX day before
 * the trading day); they are converted back here with the same rate, so the chart shows the price as quoted on the
 * exchange and the levels are valid IDX prices.
 */
@Repository
public class TechnicalRepository {

    /** A listing with what the ticker search shows. */
    public record Listing(long companyId, String exchange, String ticker, String companyName, String sector,
                          String industry, String currency, boolean active, Integer tradingDays, LocalDate firstDate,
                          LocalDate lastDate) {
    }

    /** A stored corporate event (Yahoo dividends / splits, splits detected in the filings). */
    public record Event(LocalDate date, String type, BigDecimal value, String description, String source,
                        String sourceUrl) {
    }

    /** A macro observation: its date, its value and the day it became public. */
    public record Observation(LocalDate date, double value, LocalDate released) {
    }

    /** A macro series of the catalog ({@code macro_series_def}). */
    public record MacroDef(String code, String name, String country, String category, String unit, String frequency,
                           String source) {
    }

    /** The stored snapshot of a company. */
    public record StoredSnapshot(LocalDate asOfDate, int schemaVersion, String json, String inputHash,
                                 OffsetDateTime computedAt) {
    }

    private static final String BARS = """
            SELECT p.trading_date, p.open_price, p.high_price, p.low_price, p.close_price, p.adjusted_close, p.volume,
                   CASE WHEN c.currency = :quote THEN 1
                        ELSE (SELECT fx.rate FROM fx_rate_daily fx
                              WHERE fx.base_currency = c.currency AND fx.quote_currency = :quote
                                AND fx.rate_date < p.trading_date AND fx.rate_date >= p.trading_date - 7
                              ORDER BY fx.rate_date DESC LIMIT 1) END AS factor
            FROM price_daily p JOIN company c ON c.company_id = p.company_id
            WHERE p.company_id = :id AND p.close_price IS NOT NULL
            ORDER BY p.trading_date""";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public TechnicalRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ listings

    /** Listings of an exchange whose ticker or name contains {@code query} (all when blank), tickers first. */
    public List<Listing> search(Exchange exchange, String query, int limit) {
        String q = query == null ? "" : query.trim().toUpperCase();
        return jdbc.sql("""
                        SELECT c.company_id, c.exchange, c.ticker, c.company_name, c.sector, c.industry, c.currency, c.active,
                               s.days, s.first_date, s.last_date
                        FROM company c
                        LEFT JOIN (SELECT company_id, count(*) AS days, min(trading_date) AS first_date,
                                          max(trading_date) AS last_date
                                   FROM price_daily WHERE close_price IS NOT NULL GROUP BY company_id) s
                               ON s.company_id = c.company_id
                        WHERE c.exchange = :exchange
                          AND (:q = '' OR upper(c.ticker) LIKE '%' || :q || '%' OR upper(c.company_name) LIKE '%' || :q || '%'
                               OR upper(coalesce(c.legal_name, '')) LIKE '%' || :q || '%')
                        ORDER BY CASE WHEN upper(c.ticker) = :q THEN 0 WHEN upper(c.ticker) LIKE :q || '%' THEN 1 ELSE 2 END,
                                 c.ticker
                        LIMIT :limit""")
                .param("exchange", exchange.code()).param("q", q).param("limit", limit)
                .query((rs, i) -> new Listing(rs.getLong("company_id"), rs.getString("exchange"), rs.getString("ticker"),
                        rs.getString("company_name"), rs.getString("sector"), rs.getString("industry"),
                        rs.getString("currency"), rs.getBoolean("active"), rs.getObject("days") == null ? null : rs.getInt("days"),
                        rs.getObject("first_date", LocalDate.class), rs.getObject("last_date", LocalDate.class)))
                .list();
    }

    public Optional<Listing> listing(Exchange exchange, String ticker) {
        return search(exchange, ticker, 50).stream().filter(l -> l.ticker().equalsIgnoreCase(ticker)).findFirst();
    }

    /** Active listings of an exchange with prices: the universe of breadth and relative-strength percentiles. */
    public List<Listing> universe(Exchange exchange) {
        return search(exchange, "", 100_000).stream().filter(l -> l.active() && l.tradingDays() != null && l.tradingDays() > 0)
                .toList();
    }

    // ------------------------------------------------------------------ bars

    /**
     * Daily bars of a company in the exchange currency ({@code quoteCurrency}), oldest first. A bar of a converted
     * company without the FX rate it was converted with is left out (it cannot be stored either).
     */
    public List<Candle> bars(long companyId, String quoteCurrency) {
        return jdbc.sql(BARS).param("id", companyId).param("quote", quoteCurrency)
                .query((rs, i) -> {
                    BigDecimal factor = rs.getBigDecimal("factor");
                    if (factor == null) {
                        return null;
                    }
                    double f = factor.doubleValue();
                    double close = rs.getBigDecimal("close_price").doubleValue() * f;
                    double open = orElse(rs.getBigDecimal("open_price"), f, close);
                    double high = orElse(rs.getBigDecimal("high_price"), f, close);
                    double low = orElse(rs.getBigDecimal("low_price"), f, close);
                    double adj = orElse(rs.getBigDecimal("adjusted_close"), f, close);
                    long volume = rs.getLong("volume");
                    // a provider bar can carry an open / close outside its high / low by a rounding; keep the bar sane
                    high = Math.max(high, Math.max(open, close));
                    low = Math.min(low, Math.min(open, close));
                    return Candle.daily(rs.getObject("trading_date", LocalDate.class), round4(open), round4(high),
                            round4(low), round4(close), round4(adj), volume);
                })
                .list().stream().filter(java.util.Objects::nonNull).toList();
    }

    private static double orElse(BigDecimal value, double factor, double fallback) {
        return value == null || value.signum() <= 0 ? fallback : value.doubleValue() * factor;
    }

    /** IDX quotes are whole rupiah for most bars; converted prices are brought back to 4 decimals (the provider's). */
    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    /** Daily bars of an index, oldest first. */
    public List<Candle> indexBars(String indexCode) {
        return jdbc.sql("""
                        SELECT trading_date, open_price, high_price, low_price, close_price, volume
                        FROM index_daily WHERE index_code = :code AND close_price IS NOT NULL ORDER BY trading_date""")
                .param("code", indexCode)
                .query((rs, i) -> {
                    double close = rs.getBigDecimal("close_price").doubleValue();
                    double open = orElse(rs.getBigDecimal("open_price"), 1, close);
                    double high = Math.max(orElse(rs.getBigDecimal("high_price"), 1, close), Math.max(open, close));
                    double low = Math.min(orElse(rs.getBigDecimal("low_price"), 1, close), Math.min(open, close));
                    return Candle.daily(rs.getObject("trading_date", LocalDate.class), open, high, low, close, close,
                            rs.getLong("volume"));
                })
                .list();
    }

    public Optional<LocalDate> latestIndexDate(String indexCode) {
        return Optional.ofNullable(indexStats(indexCode).lastDate());
    }

    /** Stored bars of an index: count, first and last day ({@code null} when none). */
    public record IndexStats(int rows, LocalDate firstDate, LocalDate lastDate) {
    }

    /** Count, first and last day of the stored bars of an index. */
    public IndexStats indexStats(String indexCode) {
        return jdbc.sql("SELECT count(*), min(trading_date), max(trading_date) FROM index_daily WHERE index_code = :code")
                .param("code", indexCode)
                .query((rs, i) -> new IndexStats(rs.getInt(1), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class)))
                .single();
    }

    /** Stored days of an index between {@code from} and {@code to} (inclusive). */
    public java.util.Set<LocalDate> indexDates(String indexCode, LocalDate from, LocalDate to) {
        return new java.util.HashSet<>(jdbc.sql("""
                        SELECT trading_date FROM index_daily
                        WHERE index_code = :code AND trading_date BETWEEN :from AND :to""")
                .param("code", indexCode).param("from", from).param("to", to)
                .query(LocalDate.class).list());
    }

    /** Upserts index bars; returns the rows inserted or changed. */
    @Transactional
    public int upsertIndexBars(String indexCode, String exchange, String source, List<Candle> bars) {
        int[][] counts = jdbcTemplate.batchUpdate("""
                        INSERT INTO index_daily (index_code, exchange, trading_date, open_price, high_price, low_price,
                                                 close_price, volume, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT ON CONSTRAINT uq_index_daily DO UPDATE SET
                            open_price = EXCLUDED.open_price, high_price = EXCLUDED.high_price,
                            low_price = EXCLUDED.low_price, close_price = EXCLUDED.close_price,
                            volume = EXCLUDED.volume, source = EXCLUDED.source
                        WHERE (index_daily.open_price, index_daily.high_price, index_daily.low_price,
                               index_daily.close_price, index_daily.volume)
                              IS DISTINCT FROM (EXCLUDED.open_price, EXCLUDED.high_price, EXCLUDED.low_price,
                                                EXCLUDED.close_price, EXCLUDED.volume)""",
                bars, 500, (ps, bar) -> {
                    ps.setString(1, indexCode);
                    ps.setString(2, exchange);
                    ps.setObject(3, bar.date());
                    ps.setBigDecimal(4, BigDecimal.valueOf(bar.open()));
                    ps.setBigDecimal(5, BigDecimal.valueOf(bar.high()));
                    ps.setBigDecimal(6, BigDecimal.valueOf(bar.low()));
                    ps.setBigDecimal(7, BigDecimal.valueOf(bar.close()));
                    ps.setObject(8, (long) bar.volume(), Types.BIGINT);
                    ps.setString(9, source);
                });
        int changed = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                changed += Math.max(c, 0);
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ events

    /**
     * Events of a company, oldest first: the stored events and the splits detected in the filings or recorded by a
     * data script ({@code corporate_action}); a split known from both is listed once (the corporate action, which has
     * the exact ratio).
     */
    public List<Event> events(long companyId) {
        List<Event> events = new ArrayList<>(jdbc.sql("""
                        SELECT event_date, event_type, value, description, source, source_url
                        FROM corporate_event WHERE company_id = :id ORDER BY event_date""")
                .param("id", companyId)
                .query((rs, i) -> new Event(rs.getObject("event_date", LocalDate.class), rs.getString("event_type"),
                        rs.getBigDecimal("value"), rs.getString("description"), rs.getString("source"),
                        rs.getString("source_url")))
                .list());
        List<Event> actions = jdbc.sql("""
                        SELECT action_date, action_type, ratio_from, ratio_to, description
                        FROM corporate_action WHERE company_id = :id
                          AND action_type IN ('STOCK_SPLIT', 'REVERSE_SPLIT', 'RIGHTS_ISSUE', 'PRIVATE_PLACEMENT')
                        ORDER BY action_date""")
                .param("id", companyId)
                .query((rs, i) -> {
                    BigDecimal from = rs.getBigDecimal("ratio_from");
                    BigDecimal to = rs.getBigDecimal("ratio_to");
                    BigDecimal ratio = from != null && to != null && from.signum() > 0
                            ? to.divide(from, 8, java.math.RoundingMode.HALF_UP).stripTrailingZeros() : null;
                    return new Event(rs.getObject("action_date", LocalDate.class), rs.getString("action_type"), ratio,
                            rs.getString("description"), "corporate_action", null);
                })
                .list();
        for (Event action : actions) {
            // a split Yahoo also reports within a few days of the recorded one is the same split
            events.removeIf(e -> e.type().equals(action.type()) && !"corporate_action".equals(e.source())
                    && Math.abs(e.date().toEpochDay() - action.date().toEpochDay()) <= 31);
            events.add(action);
        }
        events.sort(java.util.Comparator.comparing(Event::date));
        return events;
    }

    /** Replaces the events of {@code source} for a company and records the fetch. */
    @Transactional
    public void replaceEvents(long companyId, String source, List<Event> events) {
        jdbc.sql("DELETE FROM corporate_event WHERE company_id = :id AND source = :source")
                .param("id", companyId).param("source", source).update();
        for (Event e : events) {
            jdbc.sql("""
                            INSERT INTO corporate_event (company_id, event_type, event_date, value, description, source, source_url)
                            VALUES (:id, :type, :date, :value, :description, :source, :url)
                            ON CONFLICT ON CONSTRAINT uq_corporate_event DO UPDATE SET
                                value = EXCLUDED.value, description = EXCLUDED.description, source_url = EXCLUDED.source_url""")
                    .param("id", companyId).param("type", e.type()).param("date", e.date())
                    .param("value", e.value(), Types.NUMERIC).param("description", e.description())
                    .param("source", source).param("url", e.sourceUrl())
                    .update();
        }
        jdbc.sql("""
                        INSERT INTO corporate_event_fetch (company_id, source, fetched_at, events) VALUES (:id, :source, now(), :n)
                        ON CONFLICT ON CONSTRAINT pk_corporate_event_fetch DO UPDATE SET fetched_at = now(), events = EXCLUDED.events""")
                .param("id", companyId).param("source", source).param("n", events.size()).update();
    }

    public Optional<OffsetDateTime> eventsFetchedAt(long companyId, String source) {
        return jdbc.sql("SELECT fetched_at FROM corporate_event_fetch WHERE company_id = :id AND source = :source")
                .param("id", companyId).param("source", source).query(OffsetDateTime.class).optional();
    }

    // ------------------------------------------------------------------ macro

    /**
     * Observations of a macro series, oldest first, with the day each became public (a series without a stored
     * release date: its observation date). {@code USD_IDR} reads the USD/IDR pair of the Forex price ingestion (Yahoo
     * Finance, London close) when it is stored, else the ECB reference rates of fx_rate_daily (16:00 CET); both close
     * after the IDX session, so a rate is public in Jakarta the day after.
     */
    public List<Observation> macro(String seriesCode) {
        if ("USD_IDR".equals(seriesCode)) {
            List<Observation> pair = jdbc.sql("""
                            SELECT trading_date, close_price FROM instrument_daily
                            WHERE instrument_code = 'USDIDR' ORDER BY trading_date""")
                    .query((rs, i) -> nextDay(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2).doubleValue()))
                    .list();
            if (!pair.isEmpty()) {
                return pair;
            }
            return jdbc.sql("""
                            SELECT rate_date, rate FROM fx_rate_daily
                            WHERE base_currency = 'USD' AND quote_currency = 'IDR' ORDER BY rate_date""")
                    .query((rs, i) -> nextDay(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2).doubleValue()))
                    .list();
        }
        return jdbc.sql("""
                        SELECT obs_date, value, coalesce(release_date, obs_date) AS released
                        FROM macro_series WHERE series_code = :code ORDER BY obs_date""")
                .param("code", seriesCode)
                .query((rs, i) -> new Observation(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2).doubleValue(),
                        rs.getObject(3, LocalDate.class)))
                .list();
    }

    /** Where USD_IDR comes from: the Forex pair when stored, else the ECB reference rates. */
    public String usdIdrSource() {
        Integer rows = jdbc.sql("SELECT count(*) FROM instrument_daily WHERE instrument_code = 'USDIDR'")
                .query(Integer.class).single();
        return rows != null && rows > 0 ? "Yahoo Finance IDR=X (Forex price ingestion)" : "ECB reference rates (fx_rate_daily)";
    }

    private static Observation nextDay(LocalDate date, double value) {
        return new Observation(date, value, date.plusDays(1));
    }

    /** The active macro series of the catalog, in display order. */
    public List<MacroDef> macroDefs() {
        return jdbc.sql("""
                        SELECT series_code, name, country, category, unit, frequency, source
                        FROM macro_series_def WHERE active ORDER BY sort_order, series_code""")
                .query((rs, i) -> new MacroDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)))
                .list();
    }

    // ------------------------------------------------------------------ snapshots

    public Optional<StoredSnapshot> snapshot(long companyId) {
        return jdbc.sql("""
                        SELECT as_of_date, schema_version, snapshot_json::text AS json, input_hash, computed_at
                        FROM indicator_snapshot WHERE company_id = :id""")
                .param("id", companyId)
                .query((rs, i) -> new StoredSnapshot(rs.getObject("as_of_date", LocalDate.class), rs.getInt("schema_version"),
                        rs.getString("json"), rs.getString("input_hash"), rs.getObject("computed_at", OffsetDateTime.class)))
                .optional();
    }

    public void saveSnapshot(long companyId, LocalDate asOf, int schemaVersion, String json, String inputHash) {
        jdbc.sql("""
                        INSERT INTO indicator_snapshot (company_id, as_of_date, schema_version, snapshot_json, input_hash, computed_at)
                        VALUES (:id, :asOf, :version, CAST(:json AS jsonb), :hash, now())
                        ON CONFLICT (company_id) DO UPDATE SET as_of_date = EXCLUDED.as_of_date,
                            schema_version = EXCLUDED.schema_version, snapshot_json = EXCLUDED.snapshot_json,
                            input_hash = EXCLUDED.input_hash, computed_at = now()""")
                .param("id", companyId).param("asOf", asOf).param("version", schemaVersion).param("json", json)
                .param("hash", inputHash).update();
    }

    // ------------------------------------------------------------------ chart settings

    public Optional<String> chartSettings(long userId) {
        return jdbc.sql("SELECT settings_json::text FROM chart_setting WHERE user_id = :id").param("id", userId)
                .query(String.class).optional();
    }

    public void saveChartSettings(long userId, String json) {
        jdbc.sql("""
                        INSERT INTO chart_setting (user_id, settings_json, updated_at) VALUES (:id, CAST(:json AS jsonb), now())
                        ON CONFLICT (user_id) DO UPDATE SET settings_json = EXCLUDED.settings_json, updated_at = now()""")
                .param("id", userId).param("json", json).update();
    }

    /** Sector of every listing of an exchange (null when unknown). */
    public Map<Long, String> sectors(Exchange exchange) {
        Map<Long, String> sectors = new LinkedHashMap<>();
        jdbc.sql("SELECT company_id, sector FROM company WHERE exchange = :exchange").param("exchange", exchange.code())
                .query((rs, i) -> sectors.put(rs.getLong(1), rs.getString(2))).list();
        return sectors;
    }
}
