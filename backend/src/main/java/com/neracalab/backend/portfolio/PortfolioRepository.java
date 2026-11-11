package com.neracalab.backend.portfolio;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Portfolio > Position: the user's stored rows ({@code portfolio}, {@code portfolio_position}, V1.0.53) and the figures
 * of Companies &gt; Fundamentals each position is shown with (read only).
 */
@Repository
public class PortfolioRepository {

    /** Cash and total investment of a user (zero until saved). */
    public record Settings(BigDecimal cash, BigDecimal totalInvestment) {
    }

    /** A stored position. */
    public record Position(long positionId, long companyId, String exchange, String ticker, BigDecimal averagePrice,
                           BigDecimal shares, LocalDate dateIn) {
    }

    /**
     * The Fundamentals figures of a company: its latest reporting period ({@code v_key_metrics}, reporting currency) and
     * the rates to the listing currency.
     *
     * @param netIncomeToParentAnnualized the period's profit to the parent made a year (Q1 x 4, H1 x 2, 9M x 4/3)
     * @param periodShares     shares of the period (the forward EPS and BVPS basis)
     * @param sharesOutstanding latest market snapshot's share count (Market &amp; shares), else the period's
     * @param closingRate      listing currency per reporting-currency unit at the period end (balance sheet); null
     *                         when the company reports in the listing currency
     * @param averageRate      the mean rate over the period (income statement)
     */
    public record Fundamentals(long companyId, String companyName, String sector, String currency, Integer fiscalYear,
                               String periodType, LocalDate periodEnd, BigDecimal bookValuePerShare,
                               BigDecimal netIncomeToParentAnnualized, BigDecimal periodShares,
                               BigDecimal debtToEquity, BigDecimal ebitdaAnnualized,
                               BigDecimal operatingIncomeAnnualized, BigDecimal totalLiabilities, BigDecimal cash,
                               BigDecimal sharesOutstanding, BigDecimal closingRate, BigDecimal averageRate) {
    }

    /** A close in the listing currency (a converted company: stored close x the rate the price ingestion used). */
    public record Close(LocalDate date, BigDecimal price) {
    }

    private final JdbcClient jdbc;

    public PortfolioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ the user's rows

    public Settings settings(long userId) {
        return jdbc.sql("SELECT cash, total_investment FROM portfolio WHERE user_id = :u")
                .param("u", userId)
                .query((rs, i) -> new Settings(rs.getBigDecimal("cash"), rs.getBigDecimal("total_investment")))
                .optional().orElse(new Settings(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    public void saveSettings(long userId, BigDecimal cash, BigDecimal totalInvestment) {
        jdbc.sql("""
                        INSERT INTO portfolio (user_id, cash, total_investment, updated_at)
                        VALUES (:u, :cash, :investment, now())
                        ON CONFLICT (user_id) DO UPDATE SET cash = EXCLUDED.cash,
                            total_investment = EXCLUDED.total_investment, updated_at = now()""")
                .param("u", userId).param("cash", cash).param("investment", totalInvestment)
                .update();
    }

    private static final String POSITIONS = """
            SELECT p.position_id, p.company_id, c.exchange, c.ticker, p.average_price, p.shares, p.date_in
            FROM portfolio_position p JOIN company c ON c.company_id = p.company_id
            WHERE p.user_id = :u""";

    public List<Position> positions(long userId) {
        return jdbc.sql(POSITIONS + " ORDER BY c.ticker").param("u", userId).query(this::position).list();
    }

    public Optional<Position> position(long userId, long positionId) {
        return jdbc.sql(POSITIONS + " AND p.position_id = :id").param("u", userId).param("id", positionId)
                .query(this::position).optional();
    }

    public boolean holds(long userId, long companyId) {
        return jdbc.sql("SELECT count(*) FROM portfolio_position WHERE user_id = :u AND company_id = :c")
                .param("u", userId).param("c", companyId).query(Integer.class).single() > 0;
    }

    public long insert(long userId, long companyId, BigDecimal averagePrice, BigDecimal shares, LocalDate dateIn) {
        return jdbc.sql("""
                        INSERT INTO portfolio_position (user_id, company_id, average_price, shares, date_in)
                        VALUES (:u, :c, :price, :shares, :dateIn) RETURNING position_id""")
                .param("u", userId).param("c", companyId).param("price", averagePrice).param("shares", shares)
                .param("dateIn", dateIn)
                .query(Long.class).single();
    }

    public void update(long userId, long positionId, BigDecimal averagePrice, BigDecimal shares, LocalDate dateIn) {
        jdbc.sql("""
                        UPDATE portfolio_position SET average_price = :price, shares = :shares, date_in = :dateIn,
                            updated_at = now()
                        WHERE user_id = :u AND position_id = :id""")
                .param("u", userId).param("id", positionId).param("price", averagePrice).param("shares", shares)
                .param("dateIn", dateIn)
                .update();
    }

    public boolean delete(long userId, long positionId) {
        return jdbc.sql("DELETE FROM portfolio_position WHERE user_id = :u AND position_id = :id")
                .param("u", userId).param("id", positionId).update() > 0;
    }

    private Position position(ResultSet rs, int i) throws SQLException {
        return new Position(rs.getLong("position_id"), rs.getLong("company_id"), rs.getString("exchange"),
                rs.getString("ticker"), rs.getBigDecimal("average_price"), rs.getBigDecimal("shares"),
                rs.getObject("date_in", LocalDate.class));
    }

    // ------------------------------------------------------------------ Companies > Fundamentals

    /** The company id of a listing, when stored. */
    public Optional<Long> companyId(String exchange, String ticker) {
        return jdbc.sql("SELECT company_id FROM company WHERE exchange = :e AND ticker = :t")
                .param("e", exchange).param("t", ticker).query(Long.class).optional();
    }

    /**
     * The latest reporting period of a company with its key metrics (as the Metrics tab and the valuation's forward EPS
     * read them), its balance sheet, the latest share count and the rates to {@code listing}.
     */
    public Optional<Fundamentals> fundamentals(long companyId, String listing) {
        return jdbc.sql("""
                        SELECT c.company_id, c.company_name, coalesce(c.sector, c.idx_sector) AS sector, c.currency,
                               km.fiscal_year, km.period_type, km.period_end, km.book_value_per_share,
                               km.net_income_to_parent_annualized, km.shares AS period_shares, km.debt_to_equity,
                               km.ebitda_annualized, km.operating_income_annualized, bs.total_liabilities,
                               bs.cash_and_equivalents, coalesce(ms.shares_outstanding, km.shares) AS shares_outstanding,
                               cl.close_price AS closing_rate, av.average_rate
                        FROM company c
                        LEFT JOIN LATERAL (
                            SELECT * FROM v_key_metrics k WHERE k.company_id = c.company_id
                            ORDER BY k.period_end DESC LIMIT 1) km ON TRUE
                        LEFT JOIN balance_sheet bs ON bs.period_id = km.period_id
                        LEFT JOIN LATERAL (
                            SELECT m.shares_outstanding FROM market_snapshot m
                            WHERE m.company_id = c.company_id AND m.shares_outstanding IS NOT NULL
                            ORDER BY m.snapshot_date DESC LIMIT 1) ms ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT d.close_price FROM instrument_daily d
                            WHERE d.instrument_code = c.currency || :listing AND d.trading_date <= km.period_end
                              AND d.trading_date > km.period_end - 10
                            ORDER BY d.trading_date DESC LIMIT 1) cl ON c.currency <> :listing
                        LEFT JOIN LATERAL (
                            SELECT round(avg(d.close_price), 8) AS average_rate FROM instrument_daily d
                            WHERE d.instrument_code = c.currency || :listing
                              AND d.trading_date BETWEEN km.period_start AND km.period_end) av ON c.currency <> :listing
                        WHERE c.company_id = :c""")
                .param("c", companyId).param("listing", listing)
                .query((rs, i) -> new Fundamentals(rs.getLong("company_id"), rs.getString("company_name"),
                        rs.getString("sector"), rs.getString("currency"), rs.getObject("fiscal_year", Integer.class),
                        rs.getString("period_type"), rs.getObject("period_end", LocalDate.class),
                        rs.getBigDecimal("book_value_per_share"), rs.getBigDecimal("net_income_to_parent_annualized"),
                        rs.getBigDecimal("period_shares"), rs.getBigDecimal("debt_to_equity"),
                        rs.getBigDecimal("ebitda_annualized"), rs.getBigDecimal("operating_income_annualized"),
                        rs.getBigDecimal("total_liabilities"), rs.getBigDecimal("cash_and_equivalents"),
                        rs.getBigDecimal("shares_outstanding"), rs.getBigDecimal("closing_rate"),
                        rs.getBigDecimal("average_rate")))
                .optional();
    }

    /**
     * The last {@code n} closes in the listing currency, newest first. A company whose prices are stored converted
     * (a USD reporter) is quoted back at the rate the price ingestion used (the last FX day before the trading day, at
     * most 7 days before), to 2 decimals, as Companies &gt; Fundamentals shows it; without that rate the close is left
     * out.
     */
    public List<Close> closes(long companyId, String listing, int n) {
        return jdbc.sql("""
                        SELECT p.trading_date,
                               CASE WHEN c.currency = :listing THEN p.close_price
                                    ELSE round(p.close_price * fx.rate, 2) END AS price
                        FROM price_daily p
                        JOIN company c ON c.company_id = p.company_id
                        LEFT JOIN LATERAL (
                            SELECT fx.rate FROM fx_rate_daily fx
                            WHERE fx.base_currency = c.currency AND fx.quote_currency = :listing
                              AND fx.rate_date < p.trading_date AND fx.rate_date >= p.trading_date - 7
                            ORDER BY fx.rate_date DESC LIMIT 1) fx ON c.currency <> :listing
                        WHERE p.company_id = :c AND p.close_price IS NOT NULL
                        ORDER BY p.trading_date DESC LIMIT :n""")
                .param("c", companyId).param("listing", listing).param("n", n)
                .query((rs, i) -> new Close(rs.getObject("trading_date", LocalDate.class), rs.getBigDecimal("price")))
                .list().stream().filter(c -> c.price() != null).toList();
    }
}
