package com.neracalab.backend.portfolio.intraday;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.portfolio.intraday.YahooIntradayClient.Bar;
import com.neracalab.backend.portfolio.intraday.YahooIntradayClient.Session;

/**
 * {@code intraday_quote} and {@code intraday_bar} (V1.0.54). Writes only these two tables: the daily prices and the
 * portfolios are read, never written.
 */
@Repository
public class IntradayRepository {

    /** A company held in at least one portfolio. */
    public record Held(long companyId, String exchange, String ticker) {
    }

    /** The stored quote of a company. */
    public record Quote(long companyId, LocalDate sessionDate, BigDecimal price, BigDecimal previousClose,
                        BigDecimal dayOpen, BigDecimal dayHigh, BigDecimal dayLow, Long volume, Instant marketTime,
                        Instant fetchedAt) {
    }

    /** A bar's start and close. */
    public record Point(Instant time, BigDecimal close) {
    }

    private final JdbcClient jdbc;

    public IntradayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every company held in a portfolio, once (all users). */
    public List<Held> held() {
        return jdbc.sql("""
                        SELECT DISTINCT c.company_id, c.exchange, c.ticker
                        FROM portfolio_position p JOIN company c ON c.company_id = p.company_id
                        ORDER BY c.ticker""")
                .query((rs, i) -> new Held(rs.getLong("company_id"), rs.getString("exchange"), rs.getString("ticker")))
                .list();
    }

    /** The companies of one user's portfolio. */
    public List<Held> held(long userId) {
        return jdbc.sql("""
                        SELECT c.company_id, c.exchange, c.ticker
                        FROM portfolio_position p JOIN company c ON c.company_id = p.company_id
                        WHERE p.user_id = :u ORDER BY c.ticker""")
                .param("u", userId)
                .query((rs, i) -> new Held(rs.getLong("company_id"), rs.getString("exchange"), rs.getString("ticker")))
                .list();
    }

    public Optional<Quote> quote(long companyId) {
        return jdbc.sql("""
                        SELECT company_id, session_date, price, previous_close, day_open, day_high, day_low, volume,
                               market_time, fetched_at
                        FROM intraday_quote WHERE company_id = :c""")
                .param("c", companyId)
                .query((rs, i) -> new Quote(rs.getLong("company_id"), rs.getObject("session_date", LocalDate.class),
                        rs.getBigDecimal("price"), rs.getBigDecimal("previous_close"), rs.getBigDecimal("day_open"),
                        rs.getBigDecimal("day_high"), rs.getBigDecimal("day_low"), rs.getObject("volume", Long.class),
                        rs.getObject("market_time", OffsetDateTime.class).toInstant(),
                        rs.getObject("fetched_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** The closes of a company's bars of one session, oldest first. */
    public List<Point> points(long companyId, LocalDate sessionDate) {
        return jdbc.sql("""
                        SELECT bar_time, close_price FROM intraday_bar
                        WHERE company_id = :c AND session_date = :d ORDER BY bar_time""")
                .param("c", companyId).param("d", sessionDate)
                .query((rs, i) -> new Point(rs.getObject("bar_time", OffsetDateTime.class).toInstant(),
                        rs.getBigDecimal("close_price")))
                .list();
    }

    /** When the quote of a company was last fetched (null: never). */
    public Instant fetchedAt(long companyId) {
        return jdbc.sql("SELECT fetched_at FROM intraday_quote WHERE company_id = :c")
                .param("c", companyId).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    /**
     * Stores a session: the quote replaces the company's (unless the stored one is of a later trade), the bars are
     * upserted (a bar still forming is rewritten at the next fetch).
     */
    @Transactional
    public void save(long companyId, Session s) {
        jdbc.sql("""
                        INSERT INTO intraday_quote (company_id, session_date, price, previous_close, day_open, day_high,
                                                    day_low, volume, market_time, session_start, session_end, source,
                                                    fetched_at)
                        VALUES (:c, :d, :price, :prev, :open, :high, :low, :volume, :time, :start, :end, 'yahoo', now())
                        ON CONFLICT (company_id) DO UPDATE SET
                            session_date = EXCLUDED.session_date, price = EXCLUDED.price,
                            previous_close = EXCLUDED.previous_close, day_open = EXCLUDED.day_open,
                            day_high = EXCLUDED.day_high, day_low = EXCLUDED.day_low, volume = EXCLUDED.volume,
                            market_time = EXCLUDED.market_time, session_start = EXCLUDED.session_start,
                            session_end = EXCLUDED.session_end, fetched_at = now()
                        WHERE intraday_quote.market_time <= EXCLUDED.market_time""")
                .param("c", companyId).param("d", s.sessionDate()).param("price", s.price())
                .param("prev", s.previousClose()).param("open", s.dayOpen()).param("high", s.dayHigh())
                .param("low", s.dayLow()).param("volume", s.volume()).param("time", ts(s.marketTime()))
                .param("start", ts(s.periodStart())).param("end", ts(s.periodEnd()))
                .update();
        // the stored quote is of a later trade: still record that it was looked at
        jdbc.sql("UPDATE intraday_quote SET fetched_at = now() WHERE company_id = :c").param("c", companyId).update();
        for (Bar b : s.bars()) {
            jdbc.sql("""
                            INSERT INTO intraday_bar (company_id, bar_time, session_date, open_price, high_price,
                                                      low_price, close_price, volume)
                            VALUES (:c, :t, :d, :o, :h, :l, :close, :v)
                            ON CONFLICT (company_id, bar_time) DO UPDATE SET open_price = EXCLUDED.open_price,
                                high_price = EXCLUDED.high_price, low_price = EXCLUDED.low_price,
                                close_price = EXCLUDED.close_price, volume = EXCLUDED.volume""")
                    .param("c", companyId).param("t", ts(b.time())).param("d", s.sessionDate()).param("o", b.open())
                    .param("h", b.high()).param("l", b.low()).param("close", b.close()).param("v", b.volume())
                    .update();
        }
    }

    /** Deletes the bars of sessions before {@code before}; returns how many. */
    public int deleteBarsBefore(LocalDate before) {
        return jdbc.sql("DELETE FROM intraday_bar WHERE session_date < :d").param("d", before).update();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
