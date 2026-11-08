package com.neracalab.backend.company.profile;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.company.profile.DividendHistory.FiscalYear;
import com.neracalab.backend.company.profile.DividendHistory.Rate;
import com.neracalab.backend.company.profile.DividendHistory.Split;
import com.neracalab.backend.company.profile.DividendHistory.YahooDividend;

/** Read-only queries of the Profile tab of a company. */
@Repository
public class CompanyProfileReadRepository {

    public record Row(long companyId, String ticker, String companyName, String legalName, String idxName,
                      String currency, LocalDate ipoDate, String listingBoard, String idxSector, String idxSubSector,
                      String idxIndustry, String idxSubIndustry, String businessActivity, String shareRegistrar,
                      String sector, String industry, String country, LocalDate fiscalYearEnd,
                      LocalDate firstPriceDate, LocalDate yahooFirstTradeDate, OffsetDateTime profileUpdatedAt) {
    }

    private final JdbcClient jdbc;

    public CompanyProfileReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> company(String exchange, String ticker) {
        return jdbc.sql("""
                        SELECT c.company_id, c.ticker, c.company_name, c.legal_name, c.idx_name, c.currency, c.ipo_date,
                               c.listing_board, c.idx_sector, c.idx_sub_sector, c.idx_industry, c.idx_sub_industry,
                               c.business_activity, c.share_registrar, c.sector, c.industry, c.country,
                               c.fiscal_year_end, c.profile_updated_at,
                               (SELECT min(p.trading_date) FROM price_daily p WHERE p.company_id = c.company_id)
                                   AS first_price_date,
                               (SELECT l.first_trade_date FROM stock_listing l
                                WHERE l.exchange = c.exchange AND l.ticker = c.ticker) AS yahoo_first_trade
                        FROM company c WHERE c.exchange = :e AND c.ticker = :t""")
                .param("e", exchange).param("t", ticker)
                .query((rs, i) -> new Row(rs.getLong("company_id"), rs.getString("ticker"), rs.getString("company_name"),
                        rs.getString("legal_name"), rs.getString("idx_name"), rs.getString("currency"),
                        date(rs, "ipo_date"), rs.getString("listing_board"), rs.getString("idx_sector"),
                        rs.getString("idx_sub_sector"), rs.getString("idx_industry"), rs.getString("idx_sub_industry"),
                        rs.getString("business_activity"), rs.getString("share_registrar"), rs.getString("sector"),
                        rs.getString("industry"), rs.getString("country"), date(rs, "fiscal_year_end"),
                        date(rs, "first_price_date"), date(rs, "yahoo_first_trade"),
                        rs.getObject("profile_updated_at", OffsetDateTime.class)))
                .optional();
    }

    /** Stored closes by trading day (split-adjusted, in the reporting currency). */
    public NavigableMap<LocalDate, BigDecimal> closes(long companyId) {
        NavigableMap<LocalDate, BigDecimal> closes = new TreeMap<>();
        jdbc.sql("""
                        SELECT trading_date, close_price FROM price_daily
                        WHERE company_id = :c AND close_price IS NOT NULL AND close_price > 0""")
                .param("c", companyId)
                .query((ResultSet rs) -> {
                    closes.put(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2));
                });
        return closes;
    }

    /** Yahoo's splits (new shares per old share), oldest first. */
    public List<Split> splits(long companyId) {
        return jdbc.sql("""
                        SELECT event_date, value FROM corporate_event
                        WHERE company_id = :c AND event_type IN ('STOCK_SPLIT', 'REVERSE_SPLIT') AND source = 'yahoo'
                          AND value > 0
                        ORDER BY event_date""")
                .param("c", companyId)
                .query((rs, i) -> new Split(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
    }

    /** Yahoo's cash dividends (ex date, amount per share on today's basis in the listing currency). */
    public List<YahooDividend> yahooDividends(long companyId) {
        return jdbc.sql("""
                        SELECT event_date, value FROM corporate_event
                        WHERE company_id = :c AND event_type = 'DIVIDEND' AND value > 0
                        ORDER BY event_date""")
                .param("c", companyId)
                .query((rs, i) -> new YahooDividend(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .list();
    }

    /**
     * Fiscal years with an income statement or a cash flow statement: profit to the owners, basic EPS and the dividends
     * paid; one per fiscal year (the longest period ending last when there are several).
     */
    public List<FiscalYear> fiscalYears(long companyId) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (rp.fiscal_year) rp.fiscal_year, rp.period_end,
                               COALESCE(i.net_income_to_parent, i.net_income) AS net_income_to_parent,
                               COALESCE(i.basic_eps, i.diluted_eps) AS basic_eps, cf.dividends_paid
                        FROM reporting_period rp
                        LEFT JOIN income_statement i ON i.period_id = rp.period_id
                        LEFT JOIN cash_flow_statement cf ON cf.period_id = rp.period_id
                        WHERE rp.company_id = :c AND rp.period_type = 'FY'
                          AND (i.period_id IS NOT NULL OR cf.period_id IS NOT NULL)
                        ORDER BY rp.fiscal_year DESC, rp.period_end DESC, rp.period_start ASC NULLS LAST""")
                .param("c", companyId)
                .query((rs, i) -> new FiscalYear(rs.getInt("fiscal_year"), date(rs, "period_end"),
                        rs.getBigDecimal("net_income_to_parent"), rs.getBigDecimal("basic_eps"),
                        rs.getBigDecimal("dividends_paid")))
                .list();
    }

    /** The trailing EPS of the latest valuation snapshot (reporting currency) and its date; empty without one. */
    public Optional<Map.Entry<LocalDate, BigDecimal>> latestEpsTtm(long companyId) {
        return jdbc.sql("""
                        SELECT valuation_date, eps_ttm FROM valuation_snapshot
                        WHERE company_id = :c AND eps_ttm IS NOT NULL
                        ORDER BY valuation_date DESC LIMIT 1""")
                .param("c", companyId)
                .query((rs, i) -> Map.entry(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .optional();
    }

    /**
     * FX rates between the reporting currency, the listing currency and the currencies of the company's IDX dividends
     * (both directions as stored).
     */
    public List<Rate> rates(String currency, String listing, long companyId) {
        return jdbc.sql("""
                        WITH cur AS (
                            SELECT :cur AS code UNION SELECT :listing
                            UNION SELECT currency FROM company_dividend WHERE company_id = :c AND currency IS NOT NULL
                            UNION SELECT total_currency FROM company_dividend
                                  WHERE company_id = :c AND total_currency IS NOT NULL)
                        SELECT base_currency, quote_currency, rate_date, rate FROM fx_rate_daily
                        WHERE base_currency IN (SELECT code FROM cur) AND quote_currency IN (SELECT code FROM cur)
                          AND base_currency <> quote_currency AND rate > 0""")
                .param("cur", currency).param("listing", listing).param("c", companyId)
                .query((rs, i) -> new Rate(rs.getString(1).trim(), rs.getString(2).trim(),
                        rs.getObject(3, LocalDate.class), rs.getBigDecimal(4)))
                .list();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}
