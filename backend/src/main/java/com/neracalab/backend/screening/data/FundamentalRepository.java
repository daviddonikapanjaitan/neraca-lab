package com.neracalab.backend.screening.data;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.Fundamentals;
import com.neracalab.backend.screening.data.YahooFundamentalsClient.ListingQuote;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code stock_listing} and {@code fundamental_snapshot}: the screening universe and its daily
 * snapshot. Market data is written for every listing on every ETL run; fundamentals are refreshed
 * less often and carried forward from the previous snapshot in between.
 */
@Repository
public class FundamentalRepository {

    private static final Logger log = LoggerFactory.getLogger(FundamentalRepository.class);
    private static final int ERROR_LENGTH = 500;

    /** Fundamental columns of {@code fundamental_snapshot}, carried forward between refreshes. */
    private static final List<String> FUNDAMENTAL_COLUMNS = List.of("revenue_ttm", "net_income_ttm", "ebitda_ttm",
            "gross_margin", "operating_margin", "profit_margin", "ebitda_margin", "return_on_equity", "return_on_assets",
            "debt_to_equity", "current_ratio", "quick_ratio", "total_cash", "total_debt", "free_cash_flow",
            "operating_cash_flow", "revenue_growth", "earnings_growth", "enterprise_value", "ev_to_ebitda",
            "ev_to_revenue", "peg_ratio", "beta", "insider_ownership", "institution_ownership", "annual",
            "fundamentals_fetched_at");

    /** One listing with its latest snapshot (market data and, when loaded, fundamentals). */
    public record StockSnapshot(long listingId, long snapshotId, String exchange, String ticker, String companyName,
                                String sector, String industry, String board, LocalDate snapshotDate, Double price,
                                Double marketCap, Double sharesOutstanding, Double avgVolume3m, Double avgDailyValue3m,
                                Double fiftyTwoWeekHigh, Double fiftyTwoWeekLow, Double trailingPe, Double forwardPe,
                                Double priceToBook, Double epsTtm, Double bookValuePerShare, Double dividendYield,
                                Instant lastTradeAt, Fundamentals fundamentals, Instant fundamentalsFetchedAt) {

        public boolean hasFundamentals() {
            return fundamentals != null;
        }
    }

    /** Counts shown on the screening page. */
    public record DataStatus(int listings, LocalDate latestSnapshotDate, int withFundamentals,
                             Instant oldestFundamentalsAt, Instant lastSyncAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public FundamentalRepository(JdbcClient jdbc, JsonMapper json, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
    }

    // ------------------------------------------------------------------ universe and market data

    /**
     * Upserts every listing of a complete screener result and its market data of {@code date} (one
     * transaction). Listings missing from the result become inactive, unless the result is much
     * smaller than the stored universe (a partial answer must not deactivate the exchange).
     * Fundamentals of the new rows are carried forward from each listing's previous snapshot.
     *
     * @return listing id per ticker
     */
    public Map<String, Long> saveUniverse(Exchange exchange, List<ListingQuote> quotes, LocalDate date) {
        Instant syncedAt = Instant.now();
        return transaction.execute(status -> {
            Map<String, Long> ids = new HashMap<>();
            for (ListingQuote q : quotes) {
                long id = jdbc.sql("""
                                INSERT INTO stock_listing (exchange, ticker, symbol, company_name, currency, first_trade_date,
                                                           active, last_seen_at)
                                VALUES (:exchange, :ticker, :symbol, :name, :currency, :firstTrade, TRUE, :seen)
                                ON CONFLICT (exchange, ticker) DO UPDATE SET
                                    symbol = EXCLUDED.symbol,
                                    company_name = EXCLUDED.company_name,
                                    currency = COALESCE(EXCLUDED.currency, stock_listing.currency),
                                    first_trade_date = COALESCE(EXCLUDED.first_trade_date, stock_listing.first_trade_date),
                                    active = TRUE,
                                    last_seen_at = EXCLUDED.last_seen_at,
                                    updated_at = now()
                                RETURNING listing_id""")
                        .param("exchange", exchange.code())
                        .param("ticker", q.ticker())
                        .param("symbol", q.symbol())
                        .param("name", truncate(q.name(), 255))
                        .param("currency", q.currency() != null && q.currency().matches("[A-Z]{3}") ? q.currency() : null,
                                Types.CHAR)
                        .param("firstTrade", q.firstTradeDate(), Types.DATE)
                        .param("seen", timestamp(syncedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                        .query(Long.class).single();
                ids.put(q.ticker(), id);
                upsertMarketData(id, date, q);
            }
            int active = jdbc.sql("SELECT count(*) FROM stock_listing WHERE exchange = :e AND active")
                    .param("e", exchange.code()).query(Integer.class).single();
            if (quotes.size() >= active * 0.8) {
                int deactivated = jdbc.sql("""
                                UPDATE stock_listing SET active = FALSE, updated_at = now()
                                WHERE exchange = :e AND active AND last_seen_at < :seen""")
                        .param("e", exchange.code())
                        .param("seen", timestamp(syncedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                        .update();
                if (deactivated > 0) {
                    log.info("{} {} listings missing from the screener are now inactive", deactivated, exchange.code());
                }
            } else {
                log.warn("Yahoo screener returned {} of {} active {} listings; none deactivated", quotes.size(), active,
                        exchange.code());
            }
            carryForwardFundamentals(exchange, date);
            return ids;
        });
    }

    private void upsertMarketData(long listingId, LocalDate date, ListingQuote q) {
        Double avgValue = q.avgVolume3m() != null && q.price() != null ? q.avgVolume3m() * q.price() : null;
        jdbc.sql("""
                        INSERT INTO fundamental_snapshot (listing_id, snapshot_date, price, market_cap, shares_outstanding,
                            avg_volume_3m, avg_volume_10d, avg_daily_value_3m, fifty_two_week_high, fifty_two_week_low,
                            trailing_pe, forward_pe, price_to_book, eps_ttm, book_value_per_share, dividend_yield,
                            last_trade_at)
                        VALUES (:listing, :date, :price, :mcap, :shares, :vol3m, :vol10d, :value3m, :high, :low,
                                :pe, :fpe, :pb, :eps, :bvps, :dy, :lastTrade)
                        ON CONFLICT (listing_id, snapshot_date) DO UPDATE SET
                            price = EXCLUDED.price, market_cap = EXCLUDED.market_cap,
                            shares_outstanding = EXCLUDED.shares_outstanding, avg_volume_3m = EXCLUDED.avg_volume_3m,
                            avg_volume_10d = EXCLUDED.avg_volume_10d, avg_daily_value_3m = EXCLUDED.avg_daily_value_3m,
                            fifty_two_week_high = EXCLUDED.fifty_two_week_high,
                            fifty_two_week_low = EXCLUDED.fifty_two_week_low, trailing_pe = EXCLUDED.trailing_pe,
                            forward_pe = EXCLUDED.forward_pe, price_to_book = EXCLUDED.price_to_book,
                            eps_ttm = EXCLUDED.eps_ttm, book_value_per_share = EXCLUDED.book_value_per_share,
                            dividend_yield = EXCLUDED.dividend_yield, last_trade_at = EXCLUDED.last_trade_at,
                            updated_at = now()""")
                .param("listing", listingId)
                .param("date", date)
                .param("price", q.price(), Types.NUMERIC)
                .param("mcap", q.marketCap(), Types.NUMERIC)
                .param("shares", q.sharesOutstanding(), Types.NUMERIC)
                .param("vol3m", q.avgVolume3m(), Types.NUMERIC)
                .param("vol10d", q.avgVolume10d(), Types.NUMERIC)
                .param("value3m", avgValue, Types.NUMERIC)
                .param("high", q.fiftyTwoWeekHigh(), Types.NUMERIC)
                .param("low", q.fiftyTwoWeekLow(), Types.NUMERIC)
                .param("pe", q.trailingPe(), Types.NUMERIC)
                .param("fpe", q.forwardPe(), Types.NUMERIC)
                .param("pb", q.priceToBook(), Types.NUMERIC)
                .param("eps", q.epsTtm(), Types.NUMERIC)
                .param("bvps", q.bookValuePerShare(), Types.NUMERIC)
                .param("dy", q.dividendYield(), Types.NUMERIC)
                .param("lastTrade", timestamp(q.lastTradeAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /** Rows of {@code date} without fundamentals get those of the listing's latest earlier snapshot that has them. */
    private void carryForwardFundamentals(Exchange exchange, LocalDate date) {
        StringBuilder set = new StringBuilder();
        for (String column : FUNDAMENTAL_COLUMNS) {
            if (!set.isEmpty()) {
                set.append(", ");
            }
            set.append(column).append(" = prev.").append(column);
        }
        int carried = jdbc.sql("""
                        UPDATE fundamental_snapshot fs SET %s, updated_at = now()
                        FROM (
                            SELECT DISTINCT ON (p.listing_id) p.*
                            FROM fundamental_snapshot p
                            JOIN stock_listing l ON l.listing_id = p.listing_id AND l.exchange = :exchange
                            WHERE p.snapshot_date < :date AND p.fundamentals_fetched_at IS NOT NULL
                            ORDER BY p.listing_id, p.snapshot_date DESC
                        ) prev
                        WHERE fs.listing_id = prev.listing_id AND fs.snapshot_date = :date
                          AND fs.fundamentals_fetched_at IS NULL""".formatted(set))
                .param("exchange", exchange.code())
                .param("date", date)
                .update();
        if (carried > 0) {
            log.debug("carried the fundamentals of {} listings forward to {}", carried, date);
        }
    }

    // ------------------------------------------------------------------ fundamentals

    /** A snapshot whose fundamentals should be refreshed. */
    public record StaleListing(long listingId, long snapshotId, String ticker, Double marketCap) {
    }

    /**
     * Latest snapshots of active listings whose fundamentals are missing or older than
     * {@code fetchedBefore}, largest market cap first. {@code tickers} null = all.
     */
    public List<StaleListing> staleFundamentals(Exchange exchange, Instant fetchedBefore, List<String> tickers) {
        String filter = tickers == null ? "" : " AND l.ticker IN (:tickers)";
        if (tickers != null && tickers.isEmpty()) {
            return List.of();
        }
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        SELECT * FROM (
                            SELECT DISTINCT ON (l.listing_id) l.listing_id, s.snapshot_id, l.ticker, s.market_cap,
                                   s.fundamentals_fetched_at
                            FROM stock_listing l
                            JOIN fundamental_snapshot s ON s.listing_id = l.listing_id
                            WHERE l.exchange = :exchange AND l.active%s
                            ORDER BY l.listing_id, s.snapshot_date DESC
                        ) latest
                        WHERE fundamentals_fetched_at IS NULL OR fundamentals_fetched_at < :before
                        ORDER BY market_cap DESC NULLS LAST, ticker""".formatted(filter))
                .param("exchange", exchange.code())
                .param("before", timestamp(fetchedBefore), Types.TIMESTAMP_WITH_TIMEZONE);
        if (tickers != null) {
            spec = spec.param("tickers", tickers);
        }
        return spec.query((rs, i) -> new StaleListing(rs.getLong("listing_id"), rs.getLong("snapshot_id"),
                rs.getString("ticker"), dbl(rs, "market_cap"))).list();
    }

    /** Stores fresh fundamentals in a snapshot row and the sector / industry in the listing. */
    public void saveFundamentals(StaleListing listing, Fundamentals f) {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                            UPDATE fundamental_snapshot SET
                                revenue_ttm = :revenue, net_income_ttm = :netIncome, ebitda_ttm = :ebitda,
                                gross_margin = :gm, operating_margin = :om, profit_margin = :pm, ebitda_margin = :em,
                                return_on_equity = :roe, return_on_assets = :roa, debt_to_equity = :de,
                                current_ratio = :cr, quick_ratio = :qr, total_cash = :cash, total_debt = :debt,
                                free_cash_flow = :fcf, operating_cash_flow = :ocf, revenue_growth = :rg,
                                earnings_growth = :eg, enterprise_value = :ev, ev_to_ebitda = :evEbitda,
                                ev_to_revenue = :evRevenue, peg_ratio = :peg, beta = :beta,
                                insider_ownership = :insiders, institution_ownership = :institutions,
                                annual = CAST(:annual AS jsonb), fundamentals_fetched_at = now(),
                                fundamentals_error = NULL, updated_at = now()
                            WHERE snapshot_id = :id""")
                    .param("id", listing.snapshotId())
                    .param("revenue", f.revenueTtm(), Types.NUMERIC)
                    .param("netIncome", f.netIncomeTtm(), Types.NUMERIC)
                    .param("ebitda", f.ebitdaTtm(), Types.NUMERIC)
                    .param("gm", f.grossMargin(), Types.NUMERIC)
                    .param("om", f.operatingMargin(), Types.NUMERIC)
                    .param("pm", f.profitMargin(), Types.NUMERIC)
                    .param("em", f.ebitdaMargin(), Types.NUMERIC)
                    .param("roe", f.returnOnEquity(), Types.NUMERIC)
                    .param("roa", f.returnOnAssets(), Types.NUMERIC)
                    .param("de", f.debtToEquity(), Types.NUMERIC)
                    .param("cr", f.currentRatio(), Types.NUMERIC)
                    .param("qr", f.quickRatio(), Types.NUMERIC)
                    .param("cash", f.totalCash(), Types.NUMERIC)
                    .param("debt", f.totalDebt(), Types.NUMERIC)
                    .param("fcf", f.freeCashFlow(), Types.NUMERIC)
                    .param("ocf", f.operatingCashFlow(), Types.NUMERIC)
                    .param("rg", f.revenueGrowth(), Types.NUMERIC)
                    .param("eg", f.earningsGrowth(), Types.NUMERIC)
                    .param("ev", f.enterpriseValue(), Types.NUMERIC)
                    .param("evEbitda", f.evToEbitda(), Types.NUMERIC)
                    .param("evRevenue", f.evToRevenue(), Types.NUMERIC)
                    .param("peg", f.pegRatio(), Types.NUMERIC)
                    .param("beta", f.beta(), Types.NUMERIC)
                    .param("insiders", f.insiderOwnership(), Types.NUMERIC)
                    .param("institutions", f.institutionOwnership(), Types.NUMERIC)
                    .param("annual", toJson(f.annual()), Types.VARCHAR)
                    .update();
            jdbc.sql("""
                            UPDATE stock_listing SET sector = COALESCE(:sector, sector),
                                                     industry = COALESCE(:industry, industry), updated_at = now()
                            WHERE listing_id = :id""")
                    .param("id", listing.listingId())
                    .param("sector", truncate(f.sector(), 150), Types.VARCHAR)
                    .param("industry", truncate(f.industry(), 150), Types.VARCHAR)
                    .update();
        });
    }

    /** Records why a refresh failed; the previous fundamentals of the row stay. */
    public void fundamentalsFailed(StaleListing listing, String error) {
        jdbc.sql("UPDATE fundamental_snapshot SET fundamentals_error = :e, updated_at = now() WHERE snapshot_id = :id")
                .param("id", listing.snapshotId())
                .param("e", truncate(error, ERROR_LENGTH), Types.VARCHAR)
                .update();
    }

    // ------------------------------------------------------------------ reads

    private static final String LATEST = """
            SELECT DISTINCT ON (l.listing_id) l.listing_id, l.exchange, l.ticker, l.company_name, l.sector, l.industry,
                   l.board, s.*
            FROM stock_listing l
            JOIN fundamental_snapshot s ON s.listing_id = l.listing_id
            WHERE l.exchange = :exchange AND l.active%s
            ORDER BY l.listing_id, s.snapshot_date DESC""";

    /** The latest snapshot of every active listing of the exchange. */
    public List<StockSnapshot> latestSnapshots(Exchange exchange) {
        return jdbc.sql(LATEST.formatted(""))
                .param("exchange", exchange.code())
                .query((rs, i) -> snapshot(rs))
                .list();
    }

    /** The latest snapshots of some tickers (fresh after a fundamentals refresh). */
    public List<StockSnapshot> latestSnapshots(Exchange exchange, List<String> tickers) {
        if (tickers.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(LATEST.formatted(" AND l.ticker IN (:tickers)"))
                .param("exchange", exchange.code())
                .param("tickers", tickers)
                .query((rs, i) -> snapshot(rs))
                .list();
    }

    /** Date of the newest market data of the exchange. */
    public Optional<LocalDate> latestSnapshotDate(Exchange exchange) {
        return jdbc.sql("""
                        SELECT max(s.snapshot_date) FROM fundamental_snapshot s
                        JOIN stock_listing l ON l.listing_id = s.listing_id WHERE l.exchange = :e""")
                .param("e", exchange.code())
                .query((rs, i) -> rs.getObject(1, LocalDate.class))
                .optional();
    }

    public DataStatus status(Exchange exchange) {
        return jdbc.sql("""
                        SELECT count(*) AS listings, max(snapshot_date) AS latest,
                               count(*) FILTER (WHERE fundamentals_fetched_at IS NOT NULL) AS with_fundamentals,
                               min(fundamentals_fetched_at) AS oldest_fundamentals, max(last_seen_at) AS last_sync
                        FROM (
                            SELECT DISTINCT ON (l.listing_id) l.last_seen_at, s.snapshot_date, s.fundamentals_fetched_at
                            FROM stock_listing l
                            JOIN fundamental_snapshot s ON s.listing_id = l.listing_id
                            WHERE l.exchange = :e AND l.active
                            ORDER BY l.listing_id, s.snapshot_date DESC
                        ) latest""")
                .param("e", exchange.code())
                .query((rs, i) -> new DataStatus(rs.getInt("listings"), rs.getObject("latest", LocalDate.class),
                        rs.getInt("with_fundamentals"), instant(rs, "oldest_fundamentals"), instant(rs, "last_sync")))
                .single();
    }

    private StockSnapshot snapshot(ResultSet rs) throws SQLException {
        Instant fetchedAt = instant(rs, "fundamentals_fetched_at");
        Fundamentals fundamentals = fetchedAt == null ? null : new Fundamentals(rs.getString("sector"),
                rs.getString("industry"), dbl(rs, "revenue_ttm"), dbl(rs, "net_income_ttm"), dbl(rs, "ebitda_ttm"),
                dbl(rs, "gross_margin"), dbl(rs, "operating_margin"), dbl(rs, "profit_margin"), dbl(rs, "ebitda_margin"),
                dbl(rs, "return_on_equity"), dbl(rs, "return_on_assets"), dbl(rs, "debt_to_equity"),
                dbl(rs, "current_ratio"), dbl(rs, "quick_ratio"), dbl(rs, "total_cash"), dbl(rs, "total_debt"),
                dbl(rs, "free_cash_flow"), dbl(rs, "operating_cash_flow"), dbl(rs, "revenue_growth"),
                dbl(rs, "earnings_growth"), dbl(rs, "enterprise_value"), dbl(rs, "ev_to_ebitda"),
                dbl(rs, "ev_to_revenue"), dbl(rs, "peg_ratio"), dbl(rs, "beta"), dbl(rs, "insider_ownership"),
                dbl(rs, "institution_ownership"), annual(rs.getString("annual")));
        return new StockSnapshot(rs.getLong("listing_id"), rs.getLong("snapshot_id"), rs.getString("exchange"),
                rs.getString("ticker"), rs.getString("company_name"), rs.getString("sector"), rs.getString("industry"),
                rs.getString("board"), rs.getObject("snapshot_date", LocalDate.class), dbl(rs, "price"),
                dbl(rs, "market_cap"), dbl(rs, "shares_outstanding"), dbl(rs, "avg_volume_3m"),
                dbl(rs, "avg_daily_value_3m"), dbl(rs, "fifty_two_week_high"), dbl(rs, "fifty_two_week_low"),
                dbl(rs, "trailing_pe"), dbl(rs, "forward_pe"), dbl(rs, "price_to_book"), dbl(rs, "eps_ttm"),
                dbl(rs, "book_value_per_share"), dbl(rs, "dividend_yield"), instant(rs, "last_trade_at"),
                fundamentals, fetchedAt);
    }

    private AnnualFigures annual(String text) {
        if (text == null) {
            return AnnualFigures.empty();
        }
        try {
            return json.readValue(text, AnnualFigures.class);
        } catch (JacksonException e) {
            log.warn("stored annual figures are not readable: {}", e.getMessage());
            return AnnualFigures.empty();
        }
    }

    private String toJson(AnnualFigures annual) {
        return json.writeValueAsString(annual == null ? AnnualFigures.empty() : annual);
    }

    // ------------------------------------------------------------------ helpers

    static Double dbl(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
