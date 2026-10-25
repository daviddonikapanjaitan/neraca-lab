package com.neracalab.backend.screening.data;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.provider.DailyBar;
import com.neracalab.backend.technical.engine.Candle;

/**
 * {@code listing_price_daily}: the daily bars of every listing of the screening universe ({@code stock_listing}), the
 * price movements of the technical screening of a market-cap tier. Bars are split-adjusted as traded, in the listing
 * currency (IDX: IDR).
 */
@Repository
public class ListingPriceRepository {

    /** A listing of the universe with how far its stored bars reach. */
    public record ListingBars(long listingId, String ticker, String companyName, String sector, int bars,
                              LocalDate firstDate, LocalDate lastDate) {
    }

    /**
     * What is stored for an exchange.
     *
     * @param listings   active listings of the universe
     * @param withBars   active listings with at least one bar
     * @param latestDate the newest bar of any listing
     * @param rows       stored bars of the active listings
     */
    public record Status(int listings, int withBars, LocalDate latestDate, long rows) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;

    public ListingPriceRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = transaction;
    }

    /** The active listings of the exchange with their stored bars, by ticker. */
    public List<ListingBars> listings(Exchange exchange) {
        return jdbc.sql("""
                        SELECT l.listing_id, l.ticker, l.company_name, l.sector, coalesce(s.bars, 0) AS bars,
                               s.first_date, s.last_date
                        FROM stock_listing l
                        LEFT JOIN (SELECT listing_id, count(*) AS bars, min(trading_date) AS first_date,
                                          max(trading_date) AS last_date
                                   FROM listing_price_daily GROUP BY listing_id) s ON s.listing_id = l.listing_id
                        WHERE l.exchange = :exchange AND l.active
                        ORDER BY l.ticker""")
                .param("exchange", exchange.code())
                .query((rs, i) -> new ListingBars(rs.getLong("listing_id"), rs.getString("ticker"),
                        rs.getString("company_name"), rs.getString("sector"), rs.getInt("bars"),
                        rs.getObject("first_date", LocalDate.class), rs.getObject("last_date", LocalDate.class)))
                .list();
    }

    public Status status(Exchange exchange) {
        return jdbc.sql("""
                        SELECT count(*) AS listings, count(s.listing_id) AS with_bars, max(s.last_date) AS latest,
                               coalesce(sum(s.bars), 0) AS rows
                        FROM stock_listing l
                        LEFT JOIN (SELECT listing_id, count(*) AS bars, max(trading_date) AS last_date
                                   FROM listing_price_daily GROUP BY listing_id) s ON s.listing_id = l.listing_id
                        WHERE l.exchange = :exchange AND l.active""")
                .param("exchange", exchange.code())
                .query((rs, i) -> new Status(rs.getInt("listings"), rs.getInt("with_bars"),
                        rs.getObject("latest", LocalDate.class), rs.getLong("rows")))
                .single();
    }

    /** The newest stored bar of a listing. */
    public Optional<LocalDate> lastDate(long listingId) {
        return jdbc.sql("SELECT max(trading_date) FROM listing_price_daily WHERE listing_id = :id")
                .param("id", listingId)
                .query((rs, i) -> rs.getObject(1, LocalDate.class))
                .optional();
    }

    /** The stored closes of a listing from {@code from} on (the overlap that tells a provider re-adjustment). */
    public Map<LocalDate, BigDecimal> closes(long listingId, LocalDate from) {
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT trading_date, close_price FROM listing_price_daily
                        WHERE listing_id = :id AND trading_date >= :from ORDER BY trading_date""")
                .param("id", listingId)
                .param("from", from, Types.DATE)
                .query((RowCallbackHandler) rs -> out.put(rs.getObject("trading_date", LocalDate.class),
                        rs.getBigDecimal("close_price")));
        return out;
    }

    /** Daily bars of a listing, oldest first. */
    public List<Candle> bars(long listingId) {
        return bars(List.of(listingId), null).getOrDefault(listingId, List.of());
    }

    /**
     * Daily bars of several listings, oldest first (one query).
     *
     * @param from first trading day (null: all)
     */
    public Map<Long, List<Candle>> bars(Collection<Long> listingIds, LocalDate from) {
        Map<Long, List<Candle>> out = new LinkedHashMap<>();
        if (listingIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT listing_id, trading_date, open_price, high_price, low_price, close_price, adjusted_close, volume
                        FROM listing_price_daily
                        WHERE listing_id IN (:ids) AND (CAST(:from AS date) IS NULL OR trading_date >= :from)
                        ORDER BY listing_id, trading_date""")
                .param("ids", listingIds)
                .param("from", from, Types.DATE)
                .query((RowCallbackHandler) rs -> out.computeIfAbsent(rs.getLong("listing_id"), k -> new ArrayList<>())
                        .add(candle(rs)));
        return out;
    }

    private static Candle candle(ResultSet rs) throws SQLException {
        double close = rs.getBigDecimal("close_price").doubleValue();
        double open = orElse(rs.getBigDecimal("open_price"), close);
        double high = orElse(rs.getBigDecimal("high_price"), close);
        double low = orElse(rs.getBigDecimal("low_price"), close);
        double adj = orElse(rs.getBigDecimal("adjusted_close"), close);
        // a provider bar can carry an open / close outside its high / low by a rounding; keep the bar sane
        high = Math.max(high, Math.max(open, close));
        low = Math.min(low, Math.min(open, close));
        return Candle.daily(rs.getObject("trading_date", LocalDate.class), open, high, low, close, adj, rs.getLong("volume"));
    }

    private static double orElse(BigDecimal value, double fallback) {
        return value == null || value.signum() <= 0 ? fallback : value.doubleValue();
    }

    /**
     * Stores bars of a listing (inserted, or updated where they differ); with {@code replace} the stored bars are deleted
     * first (a provider re-adjustment: every older bar changed). One transaction.
     *
     * @return bars inserted or changed
     */
    public int write(long listingId, List<DailyBar> bars, boolean replace) {
        Integer written = transaction.execute(status -> {
            if (replace) {
                jdbc.sql("DELETE FROM listing_price_daily WHERE listing_id = :id").param("id", listingId).update();
            }
            if (bars.isEmpty()) {
                return 0;
            }
            int[][] counts = jdbcTemplate.batchUpdate("""
                            INSERT INTO listing_price_daily (listing_id, trading_date, open_price, high_price, low_price,
                                                             close_price, adjusted_close, volume)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (listing_id, trading_date) DO UPDATE SET
                                open_price = EXCLUDED.open_price, high_price = EXCLUDED.high_price,
                                low_price = EXCLUDED.low_price, close_price = EXCLUDED.close_price,
                                adjusted_close = EXCLUDED.adjusted_close, volume = EXCLUDED.volume, updated_at = now()
                            WHERE (listing_price_daily.open_price, listing_price_daily.high_price,
                                   listing_price_daily.low_price, listing_price_daily.close_price,
                                   listing_price_daily.adjusted_close, listing_price_daily.volume)
                                  IS DISTINCT FROM (EXCLUDED.open_price, EXCLUDED.high_price, EXCLUDED.low_price,
                                                    EXCLUDED.close_price, EXCLUDED.adjusted_close, EXCLUDED.volume)""",
                    bars, 500, (ps, bar) -> {
                        ps.setLong(1, listingId);
                        ps.setDate(2, Date.valueOf(bar.date()));
                        ps.setBigDecimal(3, bar.open());
                        ps.setBigDecimal(4, bar.high());
                        ps.setBigDecimal(5, bar.low());
                        ps.setBigDecimal(6, bar.close());
                        ps.setBigDecimal(7, bar.adjustedClose());
                        if (bar.volume() == null) {
                            ps.setNull(8, Types.BIGINT);
                        } else {
                            ps.setLong(8, bar.volume());
                        }
                    });
            int n = 0;
            for (int[] batch : counts) {
                for (int c : batch) {
                    n += Math.max(0, c);
                }
            }
            return n;
        });
        return written == null ? 0 : written;
    }
}
