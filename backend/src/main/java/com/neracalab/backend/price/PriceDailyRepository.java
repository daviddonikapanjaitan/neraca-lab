package com.neracalab.backend.price;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.price.provider.DailyBar;
import com.neracalab.backend.price.provider.FxRateProvider.FxRate;

/** Companies and price_daily rows for the price ingestion. */
@Repository
public class PriceDailyRepository {

    /** A listed company as the ingestion needs it. */
    public record CompanyRef(long companyId, Exchange exchange, String ticker, String currency) {
    }

    /** close / adjusted_close of a stored day. */
    public record StoredClose(BigDecimal close, BigDecimal adjustedClose) {
    }

    /** Same upsert as V1.0.5: unchanged rows are not rewritten (update count 0). */
    private static final String UPSERT = """
            INSERT INTO price_daily (
                company_id, trading_date, open_price, high_price, low_price,
                close_price, adjusted_close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_price_daily DO UPDATE SET
                open_price     = EXCLUDED.open_price,
                high_price     = EXCLUDED.high_price,
                low_price      = EXCLUDED.low_price,
                close_price    = EXCLUDED.close_price,
                adjusted_close = EXCLUDED.adjusted_close,
                volume         = EXCLUDED.volume
            WHERE (price_daily.open_price, price_daily.high_price, price_daily.low_price,
                   price_daily.close_price, price_daily.adjusted_close, price_daily.volume)
                  IS DISTINCT FROM
                  (EXCLUDED.open_price, EXCLUDED.high_price, EXCLUDED.low_price,
                   EXCLUDED.close_price, EXCLUDED.adjusted_close, EXCLUDED.volume)""";

    private static final int[] UPSERT_TYPES = {Types.BIGINT, Types.DATE, Types.NUMERIC, Types.NUMERIC,
            Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.BIGINT};

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public PriceDailyRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<CompanyRef> company(Exchange exchange, String ticker) {
        return jdbc.sql("SELECT company_id, ticker, currency FROM company WHERE exchange = :exchange AND ticker = :ticker")
                .param("exchange", exchange.code()).param("ticker", ticker)
                .query((rs, i) -> new CompanyRef(rs.getLong("company_id"), exchange, rs.getString("ticker"),
                        rs.getString("currency")))
                .optional();
    }

    public List<CompanyRef> activeCompanies(Exchange exchange) {
        return jdbc.sql("SELECT company_id, ticker, currency FROM company WHERE exchange = :exchange AND active ORDER BY ticker")
                .param("exchange", exchange.code())
                .query((rs, i) -> new CompanyRef(rs.getLong("company_id"), exchange, rs.getString("ticker"),
                        rs.getString("currency")))
                .list();
    }

    /** Empty when no price is stored (a max() aggregate would return one NULL row, which single() rejects). */
    public Optional<LocalDate> latestTradingDate(long companyId) {
        return jdbc.sql("""
                        SELECT trading_date FROM price_daily WHERE company_id = :c
                        ORDER BY trading_date DESC LIMIT 1""")
                .param("c", companyId)
                .query(LocalDate.class)
                .optional();
    }

    public Optional<StoredClose> storedClose(long companyId, LocalDate tradingDate) {
        return jdbc.sql("SELECT close_price, adjusted_close FROM price_daily WHERE company_id = :c AND trading_date = :d")
                .param("c", companyId).param("d", tradingDate)
                .query((rs, i) -> new StoredClose(rs.getBigDecimal("close_price"), rs.getBigDecimal("adjusted_close")))
                .optional();
    }

    public Set<LocalDate> tradingDates(long companyId, LocalDate from, LocalDate to) {
        return new HashSet<>(jdbc.sql("""
                        SELECT trading_date FROM price_daily
                        WHERE company_id = :c AND trading_date BETWEEN :from AND :to""")
                .param("c", companyId).param("from", from).param("to", to)
                .query(LocalDate.class)
                .list());
    }

    /** Upserts the bars; returns the number of rows inserted or changed (unchanged rows count 0). */
    public int upsert(long companyId, List<DailyBar> bars) {
        if (bars.isEmpty()) {
            return 0;
        }
        List<Object[]> args = bars.stream()
                .map(b -> new Object[] {companyId, b.date(), b.open(), b.high(), b.low(), b.close(),
                        b.adjustedClose(), b.volume()})
                .toList();
        int changed = 0;
        for (int count : jdbcTemplate.batchUpdate(UPSERT, args, UPSERT_TYPES)) {
            changed += Math.max(count, 0);
        }
        return changed;
    }

    // ------------------------------------------------------------------ exchange rates

    private static final String FX_UPSERT = """
            INSERT INTO fx_rate_daily (base_currency, quote_currency, rate_date, rate, source)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_fx_rate_daily DO UPDATE SET
                rate = EXCLUDED.rate, source = EXCLUDED.source, updated_at = now()
            WHERE fx_rate_daily.rate IS DISTINCT FROM EXCLUDED.rate""";

    private static final int[] FX_UPSERT_TYPES = {Types.CHAR, Types.CHAR, Types.DATE, Types.NUMERIC, Types.VARCHAR};

    /** Upserts daily rates (quote units per 1 base unit) of {@code source}; non-positive rates are ignored. */
    public void upsertFxRates(String base, String quote, String source, List<FxRate> rates) {
        List<Object[]> args = rates.stream()
                .filter(r -> r.date() != null && r.rate() != null && r.rate().signum() > 0)
                .map(r -> new Object[] {base, quote, r.date(), r.rate(), source})
                .toList();
        if (!args.isEmpty()) {
            jdbcTemplate.batchUpdate(FX_UPSERT, args, FX_UPSERT_TYPES);
        }
    }

    /** Stored rates of base/quote delivered by {@code source}, from {@code from} to {@code to} (inclusive), by date. */
    public NavigableMap<LocalDate, BigDecimal> fxRates(String base, String quote, String source, LocalDate from, LocalDate to) {
        NavigableMap<LocalDate, BigDecimal> rates = new TreeMap<>();
        jdbc.sql("""
                        SELECT rate_date, rate FROM fx_rate_daily
                        WHERE base_currency = :b AND quote_currency = :q AND source = :s
                          AND rate_date BETWEEN :from AND :to""")
                .param("b", base).param("q", quote).param("s", source).param("from", from).param("to", to)
                .query((rs, i) -> rates.put(rs.getObject("rate_date", LocalDate.class), rs.getBigDecimal("rate")))
                .list();
        return rates;
    }
}
