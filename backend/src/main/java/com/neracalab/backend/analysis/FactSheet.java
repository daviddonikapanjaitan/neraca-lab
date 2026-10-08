package com.neracalab.backend.analysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.neracalab.backend.company.CompanyDetailResponse;
import com.neracalab.backend.company.CompanyDetailResponse.BalanceSheet;
import com.neracalab.backend.company.CompanyDetailResponse.CashFlowStatement;
import com.neracalab.backend.company.CompanyDetailResponse.IncomeStatement;
import com.neracalab.backend.company.CompanyDetailResponse.Metric;
import com.neracalab.backend.company.CompanyDetailResponse.Period;
import com.neracalab.backend.company.CompanyDetailResponse.Valuation;

/**
 * The company data of an analysis, from what the database stores for it (the IDX filings, prices and
 * valuations): a compact overview every agent sees, and the full statements of one period for the research agent's
 * {@code getStatement} tool.
 * <p>
 * Amounts are in the reporting currency, scaled to keep the prompt short: IDR in billions, other currencies in
 * millions (per-share amounts unscaled); ratios are fractions (0.25 = 25%). H1 and 9M periods are year-to-date.
 * The overview holds the latest {@code annualPeriods} fiscal years and, when newer, the latest interim period with
 * its prior-year comparative.
 */
public final class FactSheet {

    /** Metrics of a period shown in the overview (financial_metric names). */
    static final List<String> OVERVIEW_METRICS = List.of("gross_margin", "operating_margin", "net_margin",
            "ebitda_margin", "roe_annualized", "roa_annualized", "roic_annualized", "debt_to_equity",
            "net_debt_to_equity", "equity_to_assets", "current_ratio", "interest_coverage", "fcf_margin",
            "ocf_to_net_income", "capex_to_revenue", "free_cash_flow", "net_debt", "eps", "book_value_per_share");

    private final CompanyDetailResponse detail;
    private final String currency;
    private final double scale;
    private final String amountUnit;
    private final List<Period> overviewPeriods;

    private FactSheet(CompanyDetailResponse detail, int annualPeriods) {
        this.detail = detail;
        this.currency = detail.company().currency() == null ? "IDR" : detail.company().currency().toUpperCase(Locale.ROOT);
        this.scale = currency.equals("IDR") ? 1e9 : 1e6;
        this.amountUnit = currency + (currency.equals("IDR") ? " billions" : " millions");
        this.overviewPeriods = select(detail.periods(), annualPeriods);
    }

    public static FactSheet of(CompanyDetailResponse detail, int annualPeriods) {
        return new FactSheet(detail, annualPeriods);
    }

    /** Fiscal years (most recent first, at most {@code annual}), preceded by a newer interim period and its comparative. */
    static List<Period> select(List<Period> periods, int annual) {
        List<Period> fiscalYears = periods.stream().filter(p -> "FY".equals(p.periodType())).limit(annual).toList();
        List<Period> out = new ArrayList<>();
        if (!periods.isEmpty()) {
            Period latest = periods.getFirst();
            LocalDate latestFy = fiscalYears.isEmpty() ? null : fiscalYears.getFirst().periodEnd();
            if (!"FY".equals(latest.periodType()) && (latestFy == null || latest.periodEnd().isAfter(latestFy))) {
                out.add(latest);
                periods.stream()
                        .filter(p -> p.periodType().equals(latest.periodType()) && p.fiscalYear() == latest.fiscalYear() - 1)
                        .findFirst().ifPresent(out::add);
            }
        }
        out.addAll(fiscalYears);
        return out;
    }

    public String currency() {
        return currency;
    }

    public String amountUnit() {
        return amountUnit;
    }

    public CompanyDetailResponse detail() {
        return detail;
    }

    /** Names of every stored period, most recent first ("2026 H1", "2025 FY", ...). */
    public List<String> periodNames() {
        return detail.periods().stream().map(Period::period).toList();
    }

    /** The overview: company, the selected periods (statements and metrics), the latest valuation and price. */
    public Map<String, Object> overview() {
        CompanyDetailResponse.Company c = detail.company();
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("ticker", c.ticker());
        company.put("name", c.companyName());
        put(company, "sector", c.sector());
        put(company, "industry", c.industry());
        company.put("reportingCurrency", currency);
        m.put("company", company);
        m.put("amountUnit", amountUnit + " (per-share amounts in " + currency + "); ratios as fractions; H1 / 9M are year-to-date");
        List<Map<String, Object>> periods = new ArrayList<>();
        for (Period p : overviewPeriods) {
            periods.add(period(p, false));
        }
        m.put("periods", periods);
        valuation().ifPresent(v -> m.put("latestValuation", v));
        if (detail.latestPrice() != null) {
            Map<String, Object> price = new LinkedHashMap<>();
            price.put("date", detail.latestPrice().tradingDate().toString());
            put(price, "close", plain(detail.latestPrice().closePrice(), 4));
            if (detail.coverage() != null && detail.coverage().firstPriceDate() != null) {
                price.put("historyFrom", detail.coverage().firstPriceDate().toString());
            }
            m.put("latestPrice", price);
        }
        return m;
    }

    /** Every stored line of one period ("2024 FY"), for the research agent; empty when no such period is stored. */
    public Optional<Map<String, Object>> statement(String name) {
        String wanted = name == null ? "" : name.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return detail.periods().stream().filter(p -> p.period().toUpperCase(Locale.ROOT).equals(wanted)).findFirst()
                .map(p -> period(p, true));
    }

    private Map<String, Object> period(Period p, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", p.period());
        m.put("periodEnd", p.periodEnd().toString());
        if (p.audited() != null) {
            m.put("audited", p.audited());
        }
        if (p.incomeStatement() != null) {
            m.put("income", income(p.incomeStatement(), full));
        }
        if (p.balanceSheet() != null) {
            m.put("balance", balance(p.balanceSheet(), full));
        }
        if (p.cashFlowStatement() != null) {
            m.put("cashFlow", cashFlow(p.cashFlowStatement(), full));
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (full) {
            p.metrics().forEach((key, value) -> put(metrics, key, metric(value)));
        } else {
            for (String key : OVERVIEW_METRICS) {
                Metric value = p.metrics().get(key);
                if (value != null) {
                    put(metrics, key, metric(value));
                }
            }
        }
        if (!metrics.isEmpty()) {
            m.put("metrics", metrics);
        }
        if (full && p.segments() != null && !p.segments().isEmpty()) {
            List<Map<String, Object>> segments = new ArrayList<>();
            for (CompanyDetailResponse.SegmentFigures s : p.segments()) {
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("segment", s.segmentNameEn() != null ? s.segmentNameEn() : s.segmentName());
                put(seg, "revenue", amount(s.revenue()));
                put(seg, "grossProfit", amount(s.grossProfit()));
                put(seg, "operatingIncome", amount(s.operatingIncome()));
                segments.add(seg);
            }
            m.put("segments", segments);
        }
        return m;
    }

    private Map<String, Object> income(IncomeStatement s, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "revenue", amount(s.revenue()));
        put(m, "costOfRevenue", amount(s.costOfRevenue()));
        put(m, "grossProfit", amount(s.grossProfit()));
        put(m, "operatingExpenses", amount(s.operatingExpenses()));
        put(m, "operatingIncome", amount(s.operatingIncome()));
        put(m, "ebitda", amount(s.ebitda()));
        put(m, "interestExpense", amount(s.interestExpense()));
        put(m, "pretaxIncome", amount(s.pretaxIncome()));
        put(m, "netIncome", amount(s.netIncome()));
        put(m, "netIncomeToParent", amount(s.netIncomeToParent()));
        put(m, "basicEps", plain(s.basicEps(), 4));
        if (full) {
            put(m, "sgaExpense", amount(s.sgaExpense()));
            put(m, "rdExpense", amount(s.rdExpense()));
            put(m, "depreciation", amount(s.depreciation()));
            put(m, "amortization", amount(s.amortization()));
            put(m, "ebit", amount(s.ebit()));
            put(m, "interestIncome", amount(s.interestIncome()));
            put(m, "incomeTax", amount(s.incomeTax()));
            put(m, "dilutedEps", plain(s.dilutedEps(), 4));
            put(m, "basicSharesMillions", millions(s.basicShares()));
        }
        return m;
    }

    private Map<String, Object> balance(BalanceSheet s, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "cashAndEquivalents", amount(s.cashAndEquivalents()));
        put(m, "currentAssets", amount(s.currentAssets()));
        put(m, "totalAssets", amount(s.totalAssets()));
        put(m, "currentLiabilities", amount(s.currentLiabilities()));
        put(m, "totalLiabilities", amount(s.totalLiabilities()));
        put(m, "shortTermDebt", amount(s.shortTermDebt()));
        put(m, "longTermDebt", amount(s.longTermDebt()));
        put(m, "totalEquity", amount(s.totalEquity()));
        put(m, "equityToParent", amount(s.shareholdersEquity()));
        if (full) {
            put(m, "marketableSecurities", amount(s.marketableSecurities()));
            put(m, "accountsReceivable", amount(s.accountsReceivable()));
            put(m, "inventory", amount(s.inventory()));
            put(m, "accountsPayable", amount(s.accountsPayable()));
            put(m, "deferredRevenue", amount(s.deferredRevenue()));
            put(m, "leaseLiabilities", amount(s.leaseLiabilities()));
            put(m, "nonControllingInterest", amount(s.nonControllingInterest()));
            put(m, "retainedEarnings", amount(s.retainedEarnings()));
            put(m, "goodwill", amount(s.goodwill()));
            put(m, "intangibleAssets", amount(s.intangibleAssets()));
            put(m, "sharesOutstandingMillions", millions(s.sharesOutstanding()));
        }
        return m;
    }

    private Map<String, Object> cashFlow(CashFlowStatement s, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "operatingCashFlow", amount(s.operatingCashFlow()));
        put(m, "capitalExpenditure", amount(s.capitalExpenditure()));
        put(m, "dividendsPaid", amount(s.dividendsPaid()));
        if (full) {
            put(m, "investingCashFlow", amount(s.investingCashFlow()));
            put(m, "financingCashFlow", amount(s.financingCashFlow()));
            put(m, "acquisitions", amount(s.acquisitions()));
            put(m, "shareBuybacks", amount(s.shareBuybacks()));
            put(m, "stockIssuance", amount(s.stockIssuance()));
            put(m, "debtIssued", amount(s.debtIssued()));
            put(m, "debtRepaid", amount(s.debtRepaid()));
            put(m, "leasePayments", amount(s.leasePayments()));
            put(m, "endingCash", amount(s.endingCash()));
        }
        return m;
    }

    /** The most recent valuation snapshot (price, market cap, multiples from trailing figures). */
    private Optional<Map<String, Object>> valuation() {
        if (detail.valuations() == null || detail.valuations().isEmpty()) {
            return Optional.empty();
        }
        Valuation v = detail.valuations().getFirst();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", v.valuationDate().toString());
        put(m, "basedOnPeriod", v.period());
        put(m, "sharePrice", plain(v.sharePrice(), 4));
        put(m, "marketCap", amount(v.marketCap()));
        put(m, "enterpriseValue", amount(v.enterpriseValue()));
        put(m, "epsTtm", plain(v.epsTtm(), 4));
        put(m, "pe", plain(v.peRatio(), 2));
        put(m, "pb", plain(v.pbRatio(), 2));
        put(m, "ps", plain(v.psRatio(), 2));
        put(m, "evEbitda", plain(v.evEbitda(), 2));
        put(m, "evSales", plain(v.evSales(), 2));
        put(m, "fcfYield", plain(v.fcfYield(), 4));
        put(m, "earningsYield", plain(v.earningsYield(), 4));
        return Optional.of(m);
    }

    /** A metric scaled by its unit: currency amounts like the statements, per-share and ratios unscaled. */
    private Object metric(Metric metric) {
        if (metric == null || metric.value() == null) {
            return null;
        }
        String unit = metric.unit() == null ? "" : metric.unit();
        if (unit.equals(currency)) {
            return amount(metric.value());
        }
        if (unit.endsWith("/share")) {
            return plain(metric.value(), 4);
        }
        return plain(metric.value(), unit.equals("days") ? 1 : 4);
    }

    private Double amount(BigDecimal value) {
        return scaled(value, v -> v / scale);
    }

    private static Double millions(BigDecimal value) {
        return scaled(value, v -> v / 1e6);
    }

    private static Double scaled(BigDecimal value, Function<Double, Double> f) {
        if (value == null) {
            return null;
        }
        double v = f.apply(value.doubleValue());
        double abs = Math.abs(v);
        double factor = abs >= 100 ? 10 : abs >= 1 ? 100 : 1000;
        return Math.round(v * factor) / factor;
    }

    private static Double plain(BigDecimal value, int decimals) {
        if (value == null) {
            return null;
        }
        double factor = Math.pow(10, decimals);
        return Math.round(value.doubleValue() * factor) / factor;
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
