package com.neracalab.backend.market;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.technical.engine.Candle;

/** {@code market_instrument} (the catalog of Forex pairs and commodities) and their bars in {@code instrument_daily}. */
@Repository
public class InstrumentRepository {

    /**
     * An instrument with what is stored of it.
     *
     * @param id        {@code market_instrument.instrument_id} (the chart gives it a listing id of its own)
     * @param tickSize  rounding of chart levels and preset prices
     * @param maxSpike  the cleaning threshold of the daily bars ({@link BarCleaner}); {@code null}: no cleaning
     * @param rows      stored bars
     * @param firstDate first stored bar ({@code null}: none)
     * @param lastDate  latest stored bar ({@code null}: none)
     */
    public record Instrument(String code, int id, Market market, String name, String source, String symbol,
                             String quoteCurrency, String unit, String frequency, BigDecimal tickSize,
                             BigDecimal maxSpike, LocalDate historyFrom, String description, int rows,
                             LocalDate firstDate, LocalDate lastDate) {

        public boolean monthly() {
            return "MONTHLY".equals(frequency);
        }
    }

    private static final String SELECT = """
            SELECT i.instrument_code, i.instrument_id, i.market, i.name, i.source, i.source_symbol, i.quote_currency,
                   i.unit, i.frequency, i.tick_size, i.max_spike, i.history_from, i.description,
                   coalesce(s.rows, 0) AS rows, s.first_date, s.last_date
            FROM market_instrument i
            LEFT JOIN (SELECT instrument_code, count(*) AS rows, min(trading_date) AS first_date,
                              max(trading_date) AS last_date
                       FROM instrument_daily GROUP BY instrument_code) s ON s.instrument_code = i.instrument_code
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public InstrumentRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The active instruments of a market in display order. */
    public List<Instrument> instruments(Market market) {
        return jdbc.sql(SELECT + " WHERE i.market = :market AND i.active ORDER BY i.sort_order, i.instrument_code")
                .param("market", market.code())
                .query(InstrumentRepository::instrument)
                .list();
    }

    /** An active instrument of a market by code, case-insensitively. */
    public Optional<Instrument> instrument(Market market, String code) {
        return jdbc.sql(SELECT + " WHERE i.market = :market AND i.active AND i.instrument_code = upper(trim(:code))")
                .param("market", market.code())
                .param("code", code == null ? "" : code)
                .query(InstrumentRepository::instrument)
                .optional();
    }

    private static Instrument instrument(ResultSet rs, int i) throws SQLException {
        return new Instrument(rs.getString("instrument_code"), rs.getInt("instrument_id"),
                Market.of(rs.getString("market")), rs.getString("name"), rs.getString("source"),
                rs.getString("source_symbol"), rs.getString("quote_currency"), rs.getString("unit"),
                rs.getString("frequency"), rs.getBigDecimal("tick_size").stripTrailingZeros(),
                rs.getBigDecimal("max_spike"), rs.getObject("history_from", LocalDate.class), rs.getString("description"), rs.getInt("rows"),
                rs.getObject("first_date", LocalDate.class), rs.getObject("last_date", LocalDate.class));
    }

    /** Bars of an instrument, oldest first (adjusted close = close: nothing to adjust). */
    public List<Candle> bars(String code) {
        return jdbc.sql("""
                        SELECT trading_date, open_price, high_price, low_price, close_price, volume
                        FROM instrument_daily WHERE instrument_code = :code ORDER BY trading_date""")
                .param("code", code)
                .query((rs, i) -> {
                    double close = rs.getBigDecimal("close_price").doubleValue();
                    double open = positiveOr(rs.getBigDecimal("open_price"), close);
                    double high = Math.max(positiveOr(rs.getBigDecimal("high_price"), close), Math.max(open, close));
                    double low = Math.min(positiveOr(rs.getBigDecimal("low_price"), close), Math.min(open, close));
                    return Candle.daily(rs.getObject("trading_date", LocalDate.class), open, high, low, close, close,
                            rs.getLong("volume"));
                })
                .list();
    }

    /** Fingerprint of the stored bars (count, last day, sum of the closes): the chart cache key. */
    public String fingerprint(String code) {
        return jdbc.sql("""
                        SELECT count(*) || ':' || coalesce(max(trading_date)::text, '') || ':' || coalesce(sum(close_price), 0)
                        FROM instrument_daily WHERE instrument_code = :code""")
                .param("code", code).query(String.class).single();
    }

    public Optional<LocalDate> latestDate(String code) {
        return jdbc.sql("SELECT max(trading_date) FROM instrument_daily WHERE instrument_code = :code")
                .param("code", code).query(LocalDate.class).optional();
    }

    /** Stored days of an instrument between {@code from} and {@code to} (inclusive). */
    public Set<LocalDate> dates(String code, LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("""
                        SELECT trading_date FROM instrument_daily
                        WHERE instrument_code = :code AND trading_date BETWEEN :from AND :to""")
                .param("code", code).param("from", from).param("to", to)
                .query(LocalDate.class).list());
    }

    /** Deletes the bars of these days (bad prints found by the cleaning after they were stored); returns the rows. */
    public int delete(String code, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM instrument_daily WHERE instrument_code = :code AND trading_date IN (:dates)")
                .param("code", code).param("dates", dates).update();
    }

    /** Upserts bars; returns the rows inserted or changed (an unchanged bar is not written). */
    @Transactional
    public int upsert(String code, String source, List<Candle> bars) {
        int[][] counts = jdbcTemplate.batchUpdate("""
                        INSERT INTO instrument_daily (instrument_code, trading_date, open_price, high_price, low_price,
                                                      close_price, volume, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT ON CONSTRAINT uq_instrument_daily DO UPDATE SET
                            open_price = EXCLUDED.open_price, high_price = EXCLUDED.high_price,
                            low_price = EXCLUDED.low_price, close_price = EXCLUDED.close_price,
                            volume = EXCLUDED.volume, source = EXCLUDED.source, updated_at = now()
                        WHERE (instrument_daily.open_price, instrument_daily.high_price, instrument_daily.low_price,
                               instrument_daily.close_price, instrument_daily.volume)
                              IS DISTINCT FROM (EXCLUDED.open_price, EXCLUDED.high_price, EXCLUDED.low_price,
                                                EXCLUDED.close_price, EXCLUDED.volume)""",
                bars, 500, (ps, bar) -> {
                    ps.setString(1, code);
                    ps.setObject(2, bar.date());
                    ps.setBigDecimal(3, BigDecimal.valueOf(bar.open()));
                    ps.setBigDecimal(4, BigDecimal.valueOf(bar.high()));
                    ps.setBigDecimal(5, BigDecimal.valueOf(bar.low()));
                    ps.setBigDecimal(6, BigDecimal.valueOf(bar.close()));
                    ps.setObject(7, (long) bar.volume(), Types.BIGINT);
                    ps.setString(8, source);
                });
        int changed = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                changed += c > 0 ? 1 : 0;
            }
        }
        return changed;
    }

    private static double positiveOr(BigDecimal value, double fallback) {
        return value == null || value.signum() <= 0 ? fallback : value.doubleValue();
    }
}
