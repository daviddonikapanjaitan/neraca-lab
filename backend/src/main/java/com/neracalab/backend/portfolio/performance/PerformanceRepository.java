package com.neracalab.backend.portfolio.performance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Portfolio &gt; Performance: the user's years ({@code portfolio_performance_year}), the daily snapshots
 * ({@code portfolio_performance_daily}, V1.0.59) and the IHSG / LQ45 closes of {@code index_daily} (read only).
 */
@Repository
public class PerformanceRepository {

    /** A year as the user typed it (the sheet's row). Null fields were left empty. */
    public record Year(int year, BigDecimal startCapital, BigDecimal newInvestment, BigDecimal dividend,
                       BigDecimal cash, BigDecimal marketValue, BigDecimal averageValue, BigDecimal ihsgYtd,
                       BigDecimal lq45Ytd, String note, Instant updatedAt) {
    }

    /** A stored daily snapshot. */
    public record Snapshot(LocalDate date, Instant capturedAt, String source, int positions, int priced,
                           int pricedToday, BigDecimal cash, BigDecimal marketValue, BigDecimal averageValue,
                           BigDecimal profitLoss, BigDecimal profitLossPct, BigDecimal dayGain, BigDecimal dayGainPct,
                           BigDecimal totalInvestment, BigDecimal yearStartCapital, BigDecimal yearNewInvestment,
                           BigDecimal ytdProfit, BigDecimal ytdProfitPct, BigDecimal ihsgClose, LocalDate ihsgDate,
                           BigDecimal ihsgChangePct, BigDecimal ihsgYtdPct, BigDecimal lq45Close, LocalDate lq45Date,
                           BigDecimal lq45ChangePct, BigDecimal lq45YtdPct) {
    }

    /** An index close. */
    public record Close(LocalDate date, BigDecimal close) {
    }

    private final JdbcClient jdbc;

    public PerformanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ years

    public List<Year> years(long userId) {
        return jdbc.sql("SELECT * FROM portfolio_performance_year WHERE user_id = :u ORDER BY year")
                .param("u", userId).query(this::year).list();
    }

    public Optional<Year> year(long userId, int year) {
        return jdbc.sql("SELECT * FROM portfolio_performance_year WHERE user_id = :u AND year = :y")
                .param("u", userId).param("y", year).query(this::year).optional();
    }

    public void saveYear(long userId, Year y) {
        jdbc.sql("""
                        INSERT INTO portfolio_performance_year (user_id, year, start_capital, new_investment, dividend,
                            cash, market_value, average_value, ihsg_ytd, lq45_ytd, note, updated_at)
                        VALUES (:u, :y, :start, :new, :dividend, :cash, :mv, :avg, :ihsg, :lq45, :note, now())
                        ON CONFLICT (user_id, year) DO UPDATE SET start_capital = EXCLUDED.start_capital,
                            new_investment = EXCLUDED.new_investment, dividend = EXCLUDED.dividend,
                            cash = EXCLUDED.cash, market_value = EXCLUDED.market_value,
                            average_value = EXCLUDED.average_value, ihsg_ytd = EXCLUDED.ihsg_ytd,
                            lq45_ytd = EXCLUDED.lq45_ytd, note = EXCLUDED.note, updated_at = now()""")
                .param("u", userId).param("y", y.year()).param("start", y.startCapital())
                .param("new", y.newInvestment() == null ? BigDecimal.ZERO : y.newInvestment())
                .param("dividend", y.dividend()).param("cash", y.cash()).param("mv", y.marketValue())
                .param("avg", y.averageValue()).param("ihsg", y.ihsgYtd()).param("lq45", y.lq45Ytd())
                .param("note", y.note())
                .update();
    }

    public boolean deleteYear(long userId, int year) {
        return jdbc.sql("DELETE FROM portfolio_performance_year WHERE user_id = :u AND year = :y")
                .param("u", userId).param("y", year).update() > 0;
    }

    private Year year(ResultSet rs, int i) throws SQLException {
        return new Year(rs.getInt("year"), rs.getBigDecimal("start_capital"), rs.getBigDecimal("new_investment"),
                rs.getBigDecimal("dividend"), rs.getBigDecimal("cash"), rs.getBigDecimal("market_value"),
                rs.getBigDecimal("average_value"), rs.getBigDecimal("ihsg_ytd"), rs.getBigDecimal("lq45_ytd"),
                rs.getString("note"), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    // ------------------------------------------------------------------ snapshots

    /** The user's snapshots from {@code from} to {@code to} (inclusive), oldest first. */
    public List<Snapshot> snapshots(long userId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT * FROM portfolio_performance_daily
                        WHERE user_id = :u AND snapshot_date BETWEEN :from AND :to ORDER BY snapshot_date""")
                .param("u", userId).param("from", from).param("to", to).query(this::snapshot).list();
    }

    /** The user's latest {@code n} snapshots, newest first. */
    public List<Snapshot> latestSnapshots(long userId, int n) {
        return jdbc.sql("""
                        SELECT * FROM portfolio_performance_daily WHERE user_id = :u
                        ORDER BY snapshot_date DESC LIMIT :n""")
                .param("u", userId).param("n", n).query(this::snapshot).list();
    }

    public int countSnapshots(long userId) {
        return jdbc.sql("SELECT count(*) FROM portfolio_performance_daily WHERE user_id = :u")
                .param("u", userId).query(Integer.class).single();
    }

    /** The last snapshot of a year. */
    public Optional<Snapshot> lastSnapshotOfYear(long userId, int year) {
        return jdbc.sql("""
                        SELECT * FROM portfolio_performance_daily
                        WHERE user_id = :u AND snapshot_date BETWEEN :from AND :to
                        ORDER BY snapshot_date DESC LIMIT 1""")
                .param("u", userId).param("from", LocalDate.of(year, 1, 1)).param("to", LocalDate.of(year, 12, 31))
                .query(this::snapshot).optional();
    }

    public boolean hasSnapshot(long userId, LocalDate day) {
        return jdbc.sql("SELECT count(*) FROM portfolio_performance_daily WHERE user_id = :u AND snapshot_date = :d")
                .param("u", userId).param("d", day).query(Integer.class).single() > 0;
    }

    /** Saves the day's snapshot; a second save of the same day replaces it. */
    public void saveSnapshot(long userId, Snapshot s) {
        jdbc.sql("""
                        INSERT INTO portfolio_performance_daily (user_id, snapshot_date, captured_at, source, positions,
                            priced, priced_today, cash, market_value, average_value, profit_loss, profit_loss_pct,
                            day_gain, day_gain_pct, total_investment, year_start_capital, year_new_investment,
                            ytd_profit, ytd_profit_pct, ihsg_close, ihsg_date, ihsg_change_pct, ihsg_ytd_pct,
                            lq45_close, lq45_date, lq45_change_pct, lq45_ytd_pct)
                        VALUES (:u, :d, :at, :source, :positions, :priced, :pricedToday, :cash, :mv, :avg, :pl, :plPct,
                            :dayGain, :dayGainPct, :investment, :start, :new, :ytd, :ytdPct, :ihsg, :ihsgDate,
                            :ihsgChange, :ihsgYtd, :lq45, :lq45Date, :lq45Change, :lq45Ytd)
                        ON CONFLICT (user_id, snapshot_date) DO UPDATE SET captured_at = EXCLUDED.captured_at,
                            source = EXCLUDED.source, positions = EXCLUDED.positions, priced = EXCLUDED.priced,
                            priced_today = EXCLUDED.priced_today, cash = EXCLUDED.cash,
                            market_value = EXCLUDED.market_value, average_value = EXCLUDED.average_value,
                            profit_loss = EXCLUDED.profit_loss, profit_loss_pct = EXCLUDED.profit_loss_pct,
                            day_gain = EXCLUDED.day_gain, day_gain_pct = EXCLUDED.day_gain_pct,
                            total_investment = EXCLUDED.total_investment,
                            year_start_capital = EXCLUDED.year_start_capital,
                            year_new_investment = EXCLUDED.year_new_investment, ytd_profit = EXCLUDED.ytd_profit,
                            ytd_profit_pct = EXCLUDED.ytd_profit_pct, ihsg_close = EXCLUDED.ihsg_close,
                            ihsg_date = EXCLUDED.ihsg_date, ihsg_change_pct = EXCLUDED.ihsg_change_pct,
                            ihsg_ytd_pct = EXCLUDED.ihsg_ytd_pct, lq45_close = EXCLUDED.lq45_close,
                            lq45_date = EXCLUDED.lq45_date, lq45_change_pct = EXCLUDED.lq45_change_pct,
                            lq45_ytd_pct = EXCLUDED.lq45_ytd_pct""")
                .param("u", userId).param("d", s.date())
                .param("at", OffsetDateTime.ofInstant(s.capturedAt(), java.time.ZoneOffset.UTC))
                .param("source", s.source()).param("positions", s.positions()).param("priced", s.priced())
                .param("pricedToday", s.pricedToday()).param("cash", s.cash()).param("mv", s.marketValue())
                .param("avg", s.averageValue()).param("pl", s.profitLoss()).param("plPct", s.profitLossPct())
                .param("dayGain", s.dayGain()).param("dayGainPct", s.dayGainPct())
                .param("investment", s.totalInvestment()).param("start", s.yearStartCapital())
                .param("new", s.yearNewInvestment()).param("ytd", s.ytdProfit()).param("ytdPct", s.ytdProfitPct())
                .param("ihsg", s.ihsgClose()).param("ihsgDate", s.ihsgDate()).param("ihsgChange", s.ihsgChangePct())
                .param("ihsgYtd", s.ihsgYtdPct()).param("lq45", s.lq45Close()).param("lq45Date", s.lq45Date())
                .param("lq45Change", s.lq45ChangePct()).param("lq45Ytd", s.lq45YtdPct())
                .update();
    }

    private Snapshot snapshot(ResultSet rs, int i) throws SQLException {
        return new Snapshot(rs.getObject("snapshot_date", LocalDate.class),
                rs.getObject("captured_at", OffsetDateTime.class).toInstant(), rs.getString("source"),
                rs.getInt("positions"), rs.getInt("priced"), rs.getInt("priced_today"), rs.getBigDecimal("cash"),
                rs.getBigDecimal("market_value"), rs.getBigDecimal("average_value"), rs.getBigDecimal("profit_loss"),
                rs.getBigDecimal("profit_loss_pct"), rs.getBigDecimal("day_gain"), rs.getBigDecimal("day_gain_pct"),
                rs.getBigDecimal("total_investment"), rs.getBigDecimal("year_start_capital"),
                rs.getBigDecimal("year_new_investment"), rs.getBigDecimal("ytd_profit"),
                rs.getBigDecimal("ytd_profit_pct"), rs.getBigDecimal("ihsg_close"),
                rs.getObject("ihsg_date", LocalDate.class), rs.getBigDecimal("ihsg_change_pct"),
                rs.getBigDecimal("ihsg_ytd_pct"), rs.getBigDecimal("lq45_close"),
                rs.getObject("lq45_date", LocalDate.class), rs.getBigDecimal("lq45_change_pct"),
                rs.getBigDecimal("lq45_ytd_pct"));
    }

    /** The users with a portfolio (settings or a position): the schedule's round. */
    public List<Long> portfolioUsers() {
        return jdbc.sql("""
                        SELECT user_id FROM portfolio
                        UNION SELECT user_id FROM portfolio_position ORDER BY 1""")
                .query(Long.class).list();
    }

    // ------------------------------------------------------------------ IHSG / LQ45

    /** The closes of an index from {@code from} to {@code to} (inclusive), oldest first. */
    public List<Close> closes(String index, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT trading_date, close_price FROM index_daily
                        WHERE index_code = :i AND trading_date BETWEEN :from AND :to AND close_price IS NOT NULL
                        ORDER BY trading_date""")
                .param("i", index).param("from", from).param("to", to)
                .query((rs, i) -> new Close(rs.getObject("trading_date", LocalDate.class),
                        rs.getBigDecimal("close_price")))
                .list();
    }

    /** The last close of an index on or before {@code day}. */
    public Optional<Close> closeOnOrBefore(String index, LocalDate day) {
        return jdbc.sql("""
                        SELECT trading_date, close_price FROM index_daily
                        WHERE index_code = :i AND trading_date <= :d AND close_price IS NOT NULL
                        ORDER BY trading_date DESC LIMIT 1""")
                .param("i", index).param("d", day)
                .query((rs, i) -> new Close(rs.getObject("trading_date", LocalDate.class),
                        rs.getBigDecimal("close_price")))
                .optional();
    }

    /** The last close of an index before {@code day}. */
    public Optional<Close> closeBefore(String index, LocalDate day) {
        return closeOnOrBefore(index, day.minusDays(1));
    }

    /** Whether any stock of the user's portfolio has an intraday session of {@code day} (the day was traded). */
    public boolean intradaySessionOf(long userId, LocalDate day) {
        return jdbc.sql("""
                        SELECT count(*) FROM intraday_quote q
                        JOIN portfolio_position p ON p.company_id = q.company_id
                        WHERE p.user_id = :u AND q.session_date = :d""")
                .param("u", userId).param("d", day).query(Integer.class).single() > 0;
    }
}
