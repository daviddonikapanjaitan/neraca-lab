package com.neracalab.backend.company.score;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.screening.data.YahooFundamentalsClient.AnnualFigures;
import com.neracalab.backend.screening.quant.CombinedProfile;
import com.neracalab.backend.screening.quant.CombinedProfile.Own;

/** Neraca Lab's own figures of a company for its Quant Score tab (read only). */
@Repository
public class ScorecardRepository {

    public record CompanyRow(long companyId, String ticker, String companyName, String currency, String sector,
                             String industry, String idxSector) {
    }

    /** financial_metric names of the latest fiscal year -> StockProfile keys. */
    private static final Map<String, String> YEAR_METRICS = Map.of(
            "roe_annualized", "roe", "roa_annualized", "roa", "gross_margin", "grossMargin",
            "operating_margin", "operatingMargin", "net_margin", "profitMargin", "interest_coverage", "interestCoverage");

    /** financial_metric names of the latest balance sheet -> StockProfile keys. */
    private static final Map<String, String> BALANCE_METRICS = Map.of(
            "debt_to_equity", "debtToEquity", "current_ratio", "currentRatio", "equity_to_assets", "equityToAssets");

    private final JdbcClient jdbc;

    public ScorecardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CompanyRow> company(String exchange, String ticker) {
        return jdbc.sql("""
                        SELECT company_id, ticker, company_name, currency, sector, industry, idx_sector
                        FROM company WHERE exchange = :e AND ticker = :t""")
                .param("e", exchange).param("t", ticker)
                .query((rs, i) -> new CompanyRow(rs.getLong("company_id"), rs.getString("ticker"),
                        rs.getString("company_name"), rs.getString("currency"), rs.getString("sector"),
                        rs.getString("industry"), rs.getString("idx_sector")))
                .optional();
    }

    /** Every company of an exchange (the company list's overall Quant Score), by ticker. */
    public List<CompanyRow> companies(String exchange) {
        return jdbc.sql("""
                        SELECT company_id, ticker, company_name, currency, sector, industry, idx_sector
                        FROM company WHERE exchange = :e ORDER BY ticker""")
                .param("e", exchange)
                .query((rs, i) -> new CompanyRow(rs.getLong("company_id"), rs.getString("ticker"),
                        rs.getString("company_name"), rs.getString("currency"), rs.getString("sector"),
                        rs.getString("industry"), rs.getString("idx_sector")))
                .list();
    }

    /**
     * A cheap sign (about 20 ms) that the data behind the scores changed: the filed periods, the valuation snapshots,
     * the companies, the screening snapshots and the exchange rates. Daily prices and metrics rewritten in place are
     * not in it (a scan of price_daily / financial_metric takes a second); {@link QuantScoreBoard}'s time limit covers
     * them.
     */
    public String fingerprint() {
        return jdbc.sql("""
                        SELECT concat_ws('|',
                            (SELECT count(*) || ':' || coalesce(max(created_at)::text, '') FROM reporting_period),
                            (SELECT count(*) || ':' || coalesce(max(created_at)::text, '') || ':'
                                    || coalesce(max(valuation_date)::text, '') FROM valuation_snapshot),
                            (SELECT coalesce(max(updated_at)::text, '') FROM company),
                            (SELECT coalesce(max(snapshot_date)::text, '') FROM fundamental_snapshot),
                            (SELECT coalesce(max(created_at)::text, '') FROM fx_rate_daily))""")
                .query(String.class).single();
    }

    /**
     * Every read of financial_metric and the statements names the company as well: their unique indexes lead with
     * company_id, so a read or join by period alone walks the whole table or index (BBCA: 18 ms instead of 0.5 ms for the
     * balance-sheet metrics, 3.9 ms for the fiscal years).
     *
     * @param listing          the currency the shares trade in
     * @param currency         the company's reporting currency (the stored prices)
     */
    public Own own(long companyId, String currency, String listing) {
        // ---- fiscal years: the latest with an income statement and the YEARS - 1 before it
        List<Map<String, Object>> fy = jdbc.sql("""
                        SELECT DISTINCT ON (rp.fiscal_year) rp.fiscal_year, rp.period_id,
                               i.revenue, COALESCE(i.net_income_to_parent, i.net_income) AS net_income,
                               i.operating_income, i.ebit, i.interest_expense,
                               b.total_assets, b.total_liabilities, b.shareholders_equity, b.current_assets,
                               b.current_liabilities, b.retained_earnings,
                               cf.capital_expenditure, cf.operating_cash_flow
                        FROM reporting_period rp
                        JOIN income_statement i ON i.company_id = rp.company_id AND i.period_id = rp.period_id
                        LEFT JOIN balance_sheet b ON b.company_id = rp.company_id AND b.period_id = rp.period_id
                        LEFT JOIN cash_flow_statement cf ON cf.company_id = rp.company_id AND cf.period_id = rp.period_id
                        WHERE rp.company_id = :c AND rp.period_type = 'FY'
                        ORDER BY rp.fiscal_year DESC, rp.period_end DESC, rp.period_start ASC NULLS LAST
                        LIMIT :n""")
                .param("c", companyId).param("n", CombinedProfile.YEARS)
                .query().listOfRows();
        AnnualFigures annual = AnnualFigures.empty();
        String fiscalYear = null;
        Long latestFyPeriod = null;
        if (!fy.isEmpty()) {
            int last = ((Number) fy.get(0).get("fiscal_year")).intValue();
            latestFyPeriod = ((Number) fy.get(0).get("period_id")).longValue();
            fiscalYear = "FY" + last;
            Map<Integer, Map<String, Object>> byYear = new HashMap<>();
            for (Map<String, Object> r : fy) {
                byYear.put(((Number) r.get("fiscal_year")).intValue(), r);
            }
            // consecutive years, oldest first, a missing year null (a CAGR counts the years between)
            List<String> years = new ArrayList<>();
            Map<String, List<Double>> values = new LinkedHashMap<>();
            for (String key : List.of("revenue", "netIncome", "operatingIncome", "ebit", "interestExpense",
                    "totalAssets", "totalLiabilities", "equity", "workingCapital", "retainedEarnings", "capex",
                    "freeCashFlow")) {
                values.put(key, new ArrayList<>());
            }
            for (int year = last - CombinedProfile.YEARS + 1; year <= last; year++) {
                Map<String, Object> r = byYear.get(year);
                if (r == null && years.isEmpty()) {
                    continue; // no gap before the first stored year
                }
                years.add(String.valueOf(year));
                values.get("revenue").add(num(r, "revenue"));
                values.get("netIncome").add(num(r, "net_income"));
                values.get("operatingIncome").add(num(r, "operating_income"));
                values.get("ebit").add(num(r, "ebit"));
                values.get("interestExpense").add(num(r, "interest_expense"));
                values.get("totalAssets").add(num(r, "total_assets"));
                values.get("totalLiabilities").add(num(r, "total_liabilities"));
                values.get("equity").add(num(r, "shareholders_equity"));
                Double ca = num(r, "current_assets");
                Double cl = num(r, "current_liabilities");
                values.get("workingCapital").add(ca == null || cl == null ? null : ca - cl);
                values.get("retainedEarnings").add(num(r, "retained_earnings"));
                Double capex = num(r, "capital_expenditure");
                Double ocf = num(r, "operating_cash_flow");
                values.get("capex").add(capex);
                values.get("freeCashFlow").add(ocf == null ? null : ocf + (capex == null ? 0 : capex));
            }
            annual = new AnnualFigures(years, values);
        }

        // ---- the latest fiscal year's metrics
        Map<String, Double> yearMetrics = new HashMap<>();
        if (latestFyPeriod != null) {
            jdbc.sql("""
                            SELECT metric_name, metric_value FROM financial_metric
                            WHERE company_id = :c AND period_id = :p AND metric_name IN (:names)
                              AND metric_value IS NOT NULL""")
                    .param("c", companyId).param("p", latestFyPeriod).param("names", List.copyOf(YEAR_METRICS.keySet()))
                    .query((ResultSet rs) -> {
                        yearMetrics.put(YEAR_METRICS.get(rs.getString(1)), rs.getDouble(2));
                    });
        }

        // ---- the latest balance sheet's metrics (any period type)
        Map<String, Double> balanceMetrics = new HashMap<>();
        String[] balancePeriod = {null};
        jdbc.sql("""
                        SELECT rp.fiscal_year || ' ' || rp.period_type AS period, fm.metric_name, fm.metric_value
                        FROM financial_metric fm JOIN reporting_period rp ON rp.period_id = fm.period_id
                        WHERE fm.company_id = :c AND fm.period_id = (
                            SELECT b.period_id FROM balance_sheet b JOIN reporting_period p ON p.period_id = b.period_id
                            WHERE b.company_id = :c ORDER BY p.period_end DESC, p.period_start ASC NULLS LAST LIMIT 1)
                          AND fm.metric_name IN (:names) AND fm.metric_value IS NOT NULL""")
                .param("c", companyId).param("names", List.copyOf(BALANCE_METRICS.keySet()))
                .query((ResultSet rs) -> {
                    balancePeriod[0] = rs.getString(1);
                    balanceMetrics.put(BALANCE_METRICS.get(rs.getString(2)), rs.getDouble(3));
                });

        // ---- the latest valuation snapshot
        Map<String, Object> v = jdbc.sql("""
                        SELECT valuation_date, pe_ratio, pb_ratio, ev_ebitda, fcf_yield, eps_ttm, market_cap
                        FROM valuation_snapshot WHERE company_id = :c ORDER BY valuation_date DESC LIMIT 1""")
                .param("c", companyId).query().listOfRows().stream().findFirst().orElse(Map.of());

        // ---- the daily prices: last close, 52 weeks, 3 months of traded value
        Map<String, Object> p = jdbc.sql("""
                        WITH last AS (SELECT max(trading_date) AS d FROM price_daily
                                      WHERE company_id = :c AND close_price IS NOT NULL),
                             y52 AS (SELECT p.* FROM price_daily p, last
                                      WHERE p.company_id = :c AND p.trading_date > last.d - 365
                                        AND p.trading_date <= last.d AND p.close_price IS NOT NULL),
                             recent AS (SELECT close_price * volume AS value FROM y52
                                        WHERE volume IS NOT NULL ORDER BY trading_date DESC LIMIT 63)
                        SELECT last.d AS last_date,
                               (SELECT close_price FROM y52 WHERE trading_date = last.d) AS last_close,
                               (SELECT max(COALESCE(high_price, close_price)) FROM y52) AS high52,
                               (SELECT min(COALESCE(low_price, close_price)) FROM y52) AS low52,
                               (SELECT avg(value) FROM recent) AS avg_value,
                               (SELECT count(*) FROM recent) AS recent_days
                        FROM last""")
                .param("c", companyId).query().listOfRows().stream().findFirst().orElse(Map.of());
        LocalDate lastDate = p.get("last_date") instanceof java.sql.Date d ? d.toLocalDate()
                : p.get("last_date") instanceof LocalDate d ? d : null;
        Double close = num(p, "last_close");
        Double avgValue = num(p, "avg_value");
        Double rate = currency.equals(listing) || lastDate == null ? 1.0 : rate(currency, listing, lastDate);
        Double price = close == null || rate == null ? null : close * rate;
        Double avgDailyValue = avgValue == null || rate == null || num(p, "recent_days") == null
                || num(p, "recent_days") < 20 ? null : avgValue * rate;

        LocalDate valuationDate = v.get("valuation_date") instanceof java.sql.Date d ? d.toLocalDate()
                : v.get("valuation_date") instanceof LocalDate d ? d : null;
        return new Own(annual, fiscalYear, yearMetrics, balanceMetrics, balancePeriod[0], valuationDate,
                num(v, "pe_ratio"), num(v, "pb_ratio"), num(v, "ev_ebitda"), num(v, "fcf_yield"), num(v, "eps_ttm"),
                num(v, "market_cap"), lastDate, price, num(p, "high52"), num(p, "low52"), close, avgDailyValue);
    }

    /** The last rate of the pair before the day (at most 7 days before), either direction; null without one. */
    private Double rate(String from, String to, LocalDate day) {
        List<Double> direct = jdbc.sql("""
                        SELECT rate FROM fx_rate_daily WHERE base_currency = :f AND quote_currency = :t
                          AND rate_date < :d AND rate_date >= :d - 7 AND rate > 0
                        ORDER BY rate_date DESC LIMIT 1""")
                .param("f", from).param("t", to).param("d", day).query(Double.class).list();
        if (!direct.isEmpty()) {
            return direct.get(0);
        }
        List<Double> inverse = jdbc.sql("""
                        SELECT rate FROM fx_rate_daily WHERE base_currency = :t AND quote_currency = :f
                          AND rate_date < :d AND rate_date >= :d - 7 AND rate > 0
                        ORDER BY rate_date DESC LIMIT 1""")
                .param("f", from).param("t", to).param("d", day).query(Double.class).list();
        return inverse.isEmpty() ? null : 1 / inverse.get(0);
    }

    private static Double num(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object v = row.get(key);
        if (v instanceof BigDecimal b) {
            return b.doubleValue();
        }
        return v instanceof Number n ? n.doubleValue() : null;
    }
}
