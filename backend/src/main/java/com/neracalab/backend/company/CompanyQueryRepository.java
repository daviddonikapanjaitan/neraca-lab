package com.neracalab.backend.company;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.neracalab.backend.company.CompanyDetailResponse.BalanceSheet;
import com.neracalab.backend.company.CompanyDetailResponse.CashFlowStatement;
import com.neracalab.backend.company.CompanyDetailResponse.Company;
import com.neracalab.backend.company.CompanyDetailResponse.CorporateAction;
import com.neracalab.backend.company.CompanyDetailResponse.Coverage;
import com.neracalab.backend.company.CompanyDetailResponse.IncomeStatement;
import com.neracalab.backend.company.CompanyDetailResponse.MarketSnapshot;
import com.neracalab.backend.company.CompanyDetailResponse.Metric;
import com.neracalab.backend.company.CompanyDetailResponse.Price;
import com.neracalab.backend.company.CompanyDetailResponse.Segment;
import com.neracalab.backend.company.CompanyDetailResponse.SegmentFigures;
import com.neracalab.backend.company.CompanyDetailResponse.ShareSnapshot;
import com.neracalab.backend.company.CompanyDetailResponse.Valuation;
import com.neracalab.backend.company.CompanyListResponse.CompanySummary;

/** Read-only queries behind the company APIs. Every query is scoped to one company or exchange. */
@Repository
public class CompanyQueryRepository {

    /** Reporting periods, most recent first; the longest period first when several end on the same day. */
    private static final String PERIOD_ORDER = "rp.period_end DESC, rp.period_start ASC NULLS LAST, rp.period_id";

    private final JdbcClient jdbc;

    public CompanyQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ list

    public List<CompanySummary> companies(String exchange) {
        return jdbc.sql("""
                        SELECT c.company_id, c.ticker, c.exchange, c.company_name, c.legal_name, c.sector,
                               c.industry, c.country, c.currency, c.fiscal_year_end, c.active,
                               p.period_count, p.first_period_end,
                               lp.latest_period, lp.latest_period_end,
                               px.latest_price_date
                        FROM company c
                        CROSS JOIN LATERAL (
                            SELECT count(*) AS period_count, min(rp.period_end) AS first_period_end
                            FROM reporting_period rp WHERE rp.company_id = c.company_id) p
                        LEFT JOIN LATERAL (
                            SELECT rp.fiscal_year || ' ' || rp.period_type AS latest_period,
                                   rp.period_end AS latest_period_end
                            FROM reporting_period rp WHERE rp.company_id = c.company_id
                            ORDER BY %s
                            LIMIT 1) lp ON TRUE
                        CROSS JOIN LATERAL (
                            SELECT max(pd.trading_date) AS latest_price_date
                            FROM price_daily pd WHERE pd.company_id = c.company_id) px
                        WHERE c.exchange = :exchange
                        ORDER BY c.ticker""".formatted(PERIOD_ORDER))
                .param("exchange", exchange)
                .query((rs, i) -> new CompanySummary(rs.getLong("company_id"), rs.getString("ticker"),
                        rs.getString("exchange"), rs.getString("company_name"), rs.getString("legal_name"),
                        rs.getString("sector"), rs.getString("industry"), rs.getString("country"),
                        rs.getString("currency"), date(rs, "fiscal_year_end"), rs.getBoolean("active"),
                        rs.getLong("period_count"), date(rs, "first_period_end"), rs.getString("latest_period"),
                        date(rs, "latest_period_end"), date(rs, "latest_price_date")))
                .list();
    }

    // ------------------------------------------------------------------ detail

    public Optional<Company> company(Exchange exchange, String ticker) {
        return jdbc.sql("""
                        SELECT company_id, ticker, exchange, cik, company_name, legal_name, sector, industry,
                               country, currency, fiscal_year_end, ipo_date, active, created_at, updated_at
                        FROM company WHERE exchange = :exchange AND ticker = :ticker""")
                .param("exchange", exchange.code()).param("ticker", ticker)
                .query((rs, i) -> new Company(rs.getLong("company_id"), rs.getString("ticker"),
                        rs.getString("exchange"), exchange.displayName(), rs.getString("cik"),
                        rs.getString("company_name"), rs.getString("legal_name"), rs.getString("sector"),
                        rs.getString("industry"), rs.getString("country"), rs.getString("currency"),
                        date(rs, "fiscal_year_end"), date(rs, "ipo_date"), rs.getBoolean("active"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional();
    }

    public Coverage coverage(long companyId) {
        return jdbc.sql("""
                        SELECT
                            (SELECT count(*)        FROM reporting_period    WHERE company_id = :c) AS periods,
                            (SELECT min(period_end) FROM reporting_period    WHERE company_id = :c) AS first_period_end,
                            (SELECT max(period_end) FROM reporting_period    WHERE company_id = :c) AS latest_period_end,
                            (SELECT count(*)        FROM income_statement    WHERE company_id = :c) AS income_statements,
                            (SELECT count(*)        FROM balance_sheet       WHERE company_id = :c) AS balance_sheets,
                            (SELECT count(*)        FROM cash_flow_statement WHERE company_id = :c) AS cash_flow_statements,
                            (SELECT count(*)        FROM segment             WHERE company_id = :c) AS segments,
                            (SELECT count(*)        FROM segment_financial   WHERE company_id = :c) AS segment_financials,
                            (SELECT count(*)          FROM price_daily       WHERE company_id = :c) AS price_days,
                            (SELECT min(trading_date) FROM price_daily       WHERE company_id = :c) AS first_price_date,
                            (SELECT max(trading_date) FROM price_daily       WHERE company_id = :c) AS latest_price_date,
                            (SELECT count(*)        FROM share_snapshot      WHERE company_id = :c) AS share_snapshots,
                            (SELECT count(*)        FROM market_snapshot     WHERE company_id = :c) AS market_snapshots,
                            (SELECT count(*)        FROM valuation_snapshot  WHERE company_id = :c) AS valuation_snapshots,
                            (SELECT count(*)        FROM financial_metric    WHERE company_id = :c) AS financial_metrics,
                            (SELECT count(*)        FROM corporate_action    WHERE company_id = :c) AS corporate_actions""")
                .param("c", companyId)
                .query((rs, i) -> new Coverage(rs.getLong("periods"), date(rs, "first_period_end"),
                        date(rs, "latest_period_end"), rs.getLong("income_statements"), rs.getLong("balance_sheets"),
                        rs.getLong("cash_flow_statements"), rs.getLong("segments"), rs.getLong("segment_financials"),
                        rs.getLong("price_days"), date(rs, "first_price_date"), date(rs, "latest_price_date"),
                        rs.getLong("share_snapshots"), rs.getLong("market_snapshots"),
                        rs.getLong("valuation_snapshots"), rs.getLong("financial_metrics"),
                        rs.getLong("corporate_actions")))
                .single();
    }

    /** A reporting period with its statements; a statement is null when no row exists for the period. */
    public record PeriodRow(long periodId, String period, int fiscalYear, Integer fiscalQuarter, String periodType,
                            LocalDate periodStart, LocalDate periodEnd, LocalDate filingDate, String sourceFiling,
                            Boolean audited, IncomeStatement incomeStatement, BalanceSheet balanceSheet,
                            CashFlowStatement cashFlowStatement) {
    }

    public List<PeriodRow> periods(long companyId) {
        return jdbc.sql("""
                        SELECT rp.period_id, rp.fiscal_year || ' ' || rp.period_type AS period,
                               rp.fiscal_year, rp.fiscal_quarter, rp.period_type, rp.period_start, rp.period_end,
                               rp.filing_date, rp.source_filing, rp.audited,
                               i.period_id  IS NOT NULL AS has_income_statement,
                               i.revenue, i.cost_of_revenue, i.gross_profit, i.operating_expenses, i.sga_expense,
                               i.rd_expense, i.depreciation, i.amortization, i.operating_income, i.ebit, i.ebitda,
                               i.interest_income, i.interest_expense, i.pretax_income, i.income_tax, i.net_income,
                               i.net_income_to_parent, i.basic_eps, i.diluted_eps, i.basic_shares, i.diluted_shares,
                               b.period_id  IS NOT NULL AS has_balance_sheet,
                               b.cash_and_equivalents, b.marketable_securities, b.accounts_receivable, b.inventory,
                               b.current_assets, b.total_assets, b.accounts_payable, b.deferred_revenue,
                               b.current_liabilities, b.total_liabilities, b.temporary_syirkah_funds,
                               b.short_term_debt, b.long_term_debt,
                               b.lease_liabilities, b.shareholders_equity, b.non_controlling_interest,
                               b.total_equity, b.retained_earnings, b.goodwill, b.intangible_assets,
                               b.shares_outstanding,
                               cf.period_id IS NOT NULL AS has_cash_flow_statement,
                               cf.operating_cash_flow, cf.capital_expenditure, cf.investing_cash_flow,
                               cf.financing_cash_flow, cf.acquisitions, cf.share_buybacks, cf.stock_issuance,
                               cf.dividends_paid, cf.debt_issued, cf.debt_repaid, cf.lease_payments,
                               cf.cash_change, cf.ending_cash
                        FROM reporting_period rp
                        LEFT JOIN income_statement i     ON i.period_id  = rp.period_id
                        LEFT JOIN balance_sheet b        ON b.period_id  = rp.period_id
                        LEFT JOIN cash_flow_statement cf ON cf.period_id = rp.period_id
                        WHERE rp.company_id = :c
                        ORDER BY %s""".formatted(PERIOD_ORDER))
                .param("c", companyId)
                .query((rs, i) -> new PeriodRow(rs.getLong("period_id"), rs.getString("period"),
                        rs.getInt("fiscal_year"), rs.getObject("fiscal_quarter", Integer.class),
                        rs.getString("period_type"), date(rs, "period_start"), date(rs, "period_end"),
                        date(rs, "filing_date"), rs.getString("source_filing"),
                        rs.getObject("audited", Boolean.class),
                        rs.getBoolean("has_income_statement") ? incomeStatement(rs) : null,
                        rs.getBoolean("has_balance_sheet") ? balanceSheet(rs) : null,
                        rs.getBoolean("has_cash_flow_statement") ? cashFlowStatement(rs) : null))
                .list();
    }

    private static IncomeStatement incomeStatement(ResultSet rs) throws SQLException {
        return new IncomeStatement(rs.getBigDecimal("revenue"), rs.getBigDecimal("cost_of_revenue"),
                rs.getBigDecimal("gross_profit"), rs.getBigDecimal("operating_expenses"),
                rs.getBigDecimal("sga_expense"), rs.getBigDecimal("rd_expense"), rs.getBigDecimal("depreciation"),
                rs.getBigDecimal("amortization"), rs.getBigDecimal("operating_income"), rs.getBigDecimal("ebit"),
                rs.getBigDecimal("ebitda"), rs.getBigDecimal("interest_income"), rs.getBigDecimal("interest_expense"),
                rs.getBigDecimal("pretax_income"), rs.getBigDecimal("income_tax"), rs.getBigDecimal("net_income"),
                rs.getBigDecimal("net_income_to_parent"), rs.getBigDecimal("basic_eps"),
                rs.getBigDecimal("diluted_eps"), rs.getBigDecimal("basic_shares"), rs.getBigDecimal("diluted_shares"));
    }

    private static BalanceSheet balanceSheet(ResultSet rs) throws SQLException {
        return new BalanceSheet(rs.getBigDecimal("cash_and_equivalents"), rs.getBigDecimal("marketable_securities"),
                rs.getBigDecimal("accounts_receivable"), rs.getBigDecimal("inventory"),
                rs.getBigDecimal("current_assets"), rs.getBigDecimal("total_assets"),
                rs.getBigDecimal("accounts_payable"), rs.getBigDecimal("deferred_revenue"),
                rs.getBigDecimal("current_liabilities"), rs.getBigDecimal("total_liabilities"),
                rs.getBigDecimal("temporary_syirkah_funds"), rs.getBigDecimal("short_term_debt"), rs.getBigDecimal("long_term_debt"),
                rs.getBigDecimal("lease_liabilities"), rs.getBigDecimal("shareholders_equity"),
                rs.getBigDecimal("non_controlling_interest"), rs.getBigDecimal("total_equity"),
                rs.getBigDecimal("retained_earnings"), rs.getBigDecimal("goodwill"),
                rs.getBigDecimal("intangible_assets"), rs.getBigDecimal("shares_outstanding"));
    }

    private static CashFlowStatement cashFlowStatement(ResultSet rs) throws SQLException {
        return new CashFlowStatement(rs.getBigDecimal("operating_cash_flow"), rs.getBigDecimal("capital_expenditure"),
                rs.getBigDecimal("investing_cash_flow"), rs.getBigDecimal("financing_cash_flow"),
                rs.getBigDecimal("acquisitions"), rs.getBigDecimal("share_buybacks"),
                rs.getBigDecimal("stock_issuance"), rs.getBigDecimal("dividends_paid"),
                rs.getBigDecimal("debt_issued"), rs.getBigDecimal("debt_repaid"), rs.getBigDecimal("lease_payments"),
                rs.getBigDecimal("cash_change"), rs.getBigDecimal("ending_cash"));
    }

    public record PeriodSegment(long periodId, SegmentFigures figures) {
    }

    public List<PeriodSegment> segmentFigures(long companyId) {
        return jdbc.sql("""
                        SELECT sf.period_id, s.segment_id, s.segment_type, s.segment_name, s.segment_name_en,
                               sf.revenue, sf.cost_of_revenue, sf.gross_profit, sf.operating_income, sf.total_assets
                        FROM segment_financial sf
                        JOIN segment s ON s.segment_id = sf.segment_id
                        WHERE sf.company_id = :c
                        ORDER BY sf.period_id, sf.revenue DESC NULLS LAST, s.segment_name""")
                .param("c", companyId)
                .query((rs, i) -> new PeriodSegment(rs.getLong("period_id"), new SegmentFigures(
                        rs.getLong("segment_id"), rs.getString("segment_type"), rs.getString("segment_name"),
                        rs.getString("segment_name_en"), rs.getBigDecimal("revenue"),
                        rs.getBigDecimal("cost_of_revenue"), rs.getBigDecimal("gross_profit"),
                        rs.getBigDecimal("operating_income"), rs.getBigDecimal("total_assets"))))
                .list();
    }

    public record PeriodMetric(long periodId, String name, Metric metric) {
    }

    /** Fundamental metrics of each period (metric_date = period end); valuation metrics are excluded. */
    public List<PeriodMetric> periodMetrics(long companyId) {
        return jdbc.sql("""
                        SELECT fm.period_id, fm.metric_name, fm.metric_category, fm.metric_value, fm.unit
                        FROM financial_metric fm
                        JOIN reporting_period rp ON rp.period_id = fm.period_id
                        WHERE fm.company_id = :c
                          AND fm.metric_date = rp.period_end
                          AND fm.metric_category IS DISTINCT FROM 'VALUATION'
                        ORDER BY fm.period_id, fm.metric_category, fm.metric_name""")
                .param("c", companyId)
                .query((rs, i) -> new PeriodMetric(rs.getLong("period_id"), rs.getString("metric_name"),
                        new Metric(rs.getString("metric_category"), rs.getBigDecimal("metric_value"),
                                rs.getString("unit"))))
                .list();
    }

    public List<Segment> segments(long companyId) {
        return jdbc.sql("""
                        SELECT segment_id, segment_type, segment_name, segment_name_en, description, active
                        FROM segment WHERE company_id = :c ORDER BY segment_type, segment_name""")
                .param("c", companyId)
                .query((rs, i) -> new Segment(rs.getLong("segment_id"), rs.getString("segment_type"),
                        rs.getString("segment_name"), rs.getString("segment_name_en"), rs.getString("description"),
                        rs.getBoolean("active")))
                .list();
    }

    public List<ShareSnapshot> shareSnapshots(long companyId) {
        return jdbc.sql("""
                        SELECT snapshot_date, basic_shares, diluted_shares, shares_outstanding, public_float,
                               treasury_shares
                        FROM share_snapshot WHERE company_id = :c ORDER BY snapshot_date DESC""")
                .param("c", companyId)
                .query((rs, i) -> new ShareSnapshot(date(rs, "snapshot_date"), rs.getBigDecimal("basic_shares"),
                        rs.getBigDecimal("diluted_shares"), rs.getBigDecimal("shares_outstanding"),
                        rs.getBigDecimal("public_float"), rs.getBigDecimal("treasury_shares")))
                .list();
    }

    public Optional<Price> latestPrice(long companyId) {
        return jdbc.sql("""
                        SELECT trading_date, open_price, high_price, low_price, close_price, adjusted_close, volume
                        FROM price_daily WHERE company_id = :c ORDER BY trading_date DESC LIMIT 1""")
                .param("c", companyId)
                .query((rs, i) -> new Price(date(rs, "trading_date"), rs.getBigDecimal("open_price"),
                        rs.getBigDecimal("high_price"), rs.getBigDecimal("low_price"),
                        rs.getBigDecimal("close_price"), rs.getBigDecimal("adjusted_close"),
                        rs.getObject("volume", Long.class)))
                .optional();
    }

    public Optional<MarketSnapshot> latestMarketSnapshot(long companyId) {
        return jdbc.sql("""
                        SELECT snapshot_date, share_price, shares_outstanding, market_cap, enterprise_value
                        FROM market_snapshot WHERE company_id = :c ORDER BY snapshot_date DESC LIMIT 1""")
                .param("c", companyId)
                .query((rs, i) -> new MarketSnapshot(date(rs, "snapshot_date"), rs.getBigDecimal("share_price"),
                        rs.getBigDecimal("shares_outstanding"), rs.getBigDecimal("market_cap"),
                        rs.getBigDecimal("enterprise_value")))
                .optional();
    }

    public List<Valuation> valuations(long companyId) {
        return jdbc.sql("""
                        SELECT vs.valuation_date, vs.period_id, rp.fiscal_year || ' ' || rp.period_type AS period,
                               vs.share_price, vs.market_cap, vs.enterprise_value, vs.eps_ttm, vs.revenue_ttm,
                               vs.ebitda_ttm, vs.operating_income_ttm, vs.fcf_ttm, vs.book_value, vs.pe_ratio,
                               vs.ps_ratio, vs.pb_ratio, vs.ev_ebitda, vs.ev_sales, vs.ev_op, vs.fcf_yield,
                               vs.earnings_yield
                        FROM valuation_snapshot vs
                        LEFT JOIN reporting_period rp ON rp.period_id = vs.period_id
                        WHERE vs.company_id = :c
                        ORDER BY vs.valuation_date DESC""")
                .param("c", companyId)
                .query((rs, i) -> new Valuation(date(rs, "valuation_date"), rs.getObject("period_id", Long.class),
                        rs.getString("period"), rs.getBigDecimal("share_price"), rs.getBigDecimal("market_cap"),
                        rs.getBigDecimal("enterprise_value"), rs.getBigDecimal("eps_ttm"),
                        rs.getBigDecimal("revenue_ttm"), rs.getBigDecimal("ebitda_ttm"),
                        rs.getBigDecimal("operating_income_ttm"), rs.getBigDecimal("fcf_ttm"),
                        rs.getBigDecimal("book_value"), rs.getBigDecimal("pe_ratio"), rs.getBigDecimal("ps_ratio"),
                        rs.getBigDecimal("pb_ratio"), rs.getBigDecimal("ev_ebitda"), rs.getBigDecimal("ev_sales"),
                        rs.getBigDecimal("ev_op"), rs.getBigDecimal("fcf_yield"), rs.getBigDecimal("earnings_yield")))
                .list();
    }

    public List<CorporateAction> corporateActions(long companyId) {
        return jdbc.sql("""
                        SELECT action_date, action_type, ratio_from, ratio_to, shares_issued, cash_raised, description
                        FROM corporate_action WHERE company_id = :c ORDER BY action_date DESC, corporate_action_id""")
                .param("c", companyId)
                .query((rs, i) -> new CorporateAction(date(rs, "action_date"), rs.getString("action_type"),
                        rs.getBigDecimal("ratio_from"), rs.getBigDecimal("ratio_to"),
                        rs.getBigDecimal("shares_issued"), rs.getBigDecimal("cash_raised"),
                        rs.getString("description")))
                .list();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}
