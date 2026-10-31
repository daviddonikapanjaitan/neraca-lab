package com.neracalab.backend.company;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything stored for one company ({@code GET /api/v1/companies/{exchange}/{ticker}}), read in
 * one consistent database snapshot. Amounts are in full units of {@code company.currency} with the
 * database sign conventions (income-statement expenses positive, cash outflows negative);
 * {@code null} = not reported.
 * <p>
 * Daily series are summarised: {@code coverage} gives the price range, {@code latestPrice} and
 * {@code latestMarketSnapshot} the most recent day.
 *
 * @param coverage   row counts and date ranges per table
 * @param periods    reporting periods, most recent first, each with its statements, segment figures and metrics
 * @param segments   the company's segments / revenue lines
 * @param valuations valuation snapshots, most recent first
 */
public record CompanyDetailResponse(
        Company company,
        Coverage coverage,
        List<Period> periods,
        List<Segment> segments,
        List<ShareSnapshot> shareSnapshots,
        Price latestPrice,
        MarketSnapshot latestMarketSnapshot,
        List<Valuation> valuations,
        List<CorporateAction> corporateActions) {

    public record Company(long companyId, String ticker, String exchange, String exchangeName, String cik,
                          String companyName, String legalName, String sector, String industry, String country,
                          String currency, LocalDate fiscalYearEnd, LocalDate ipoDate, boolean active,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record Coverage(long periods, LocalDate firstPeriodEnd, LocalDate latestPeriodEnd,
                           long incomeStatements, long balanceSheets, long cashFlowStatements,
                           long segments, long segmentFinancials,
                           long priceDays, LocalDate firstPriceDate, LocalDate latestPriceDate,
                           long shareSnapshots, long marketSnapshots, long valuationSnapshots,
                           long financialMetrics, long corporateActions) {
    }

    /**
     * One reporting period. A statement is {@code null} when it is not stored for the period
     * (e.g. no balance sheet for a prior-year interim comparative).
     *
     * @param period   e.g. "2026 H1" (H1 / 9M are year-to-date, see reporting_period.period_type)
     * @param segments segment figures of the period (revenue breakdown)
     * @param metrics  fundamental metrics of the period by name (financial_metric with metric_date = period end,
     *                 valuation metrics excluded: they are in {@link CompanyDetailResponse#valuations()})
     */
    public record Period(long periodId, String period, int fiscalYear, Integer fiscalQuarter, String periodType,
                         LocalDate periodStart, LocalDate periodEnd, LocalDate filingDate, String sourceFiling,
                         Boolean audited, IncomeStatement incomeStatement, BalanceSheet balanceSheet,
                         CashFlowStatement cashFlowStatement, List<SegmentFigures> segments,
                         Map<String, Metric> metrics) {
    }

    public record IncomeStatement(BigDecimal revenue, BigDecimal costOfRevenue, BigDecimal grossProfit,
                                  BigDecimal operatingExpenses, BigDecimal sgaExpense, BigDecimal rdExpense,
                                  BigDecimal depreciation, BigDecimal amortization,
                                  BigDecimal operatingIncome, BigDecimal ebit, BigDecimal ebitda,
                                  BigDecimal interestIncome, BigDecimal interestExpense,
                                  BigDecimal pretaxIncome, BigDecimal incomeTax,
                                  BigDecimal netIncome, BigDecimal netIncomeToParent,
                                  BigDecimal basicEps, BigDecimal dilutedEps,
                                  BigDecimal basicShares, BigDecimal dilutedShares) {
    }

    public record BalanceSheet(BigDecimal cashAndEquivalents, BigDecimal marketableSecurities,
                               BigDecimal accountsReceivable, BigDecimal inventory,
                               BigDecimal currentAssets, BigDecimal totalAssets,
                               BigDecimal accountsPayable, BigDecimal deferredRevenue,
                               BigDecimal currentLiabilities, BigDecimal totalLiabilities,
                               BigDecimal temporarySyirkahFunds,
                               BigDecimal shortTermDebt, BigDecimal longTermDebt, BigDecimal leaseLiabilities,
                               BigDecimal shareholdersEquity, BigDecimal nonControllingInterest,
                               BigDecimal totalEquity, BigDecimal retainedEarnings,
                               BigDecimal goodwill, BigDecimal intangibleAssets,
                               BigDecimal sharesOutstanding) {
    }

    public record CashFlowStatement(BigDecimal operatingCashFlow, BigDecimal capitalExpenditure,
                                    BigDecimal investingCashFlow, BigDecimal financingCashFlow,
                                    BigDecimal acquisitions, BigDecimal shareBuybacks, BigDecimal stockIssuance,
                                    BigDecimal dividendsPaid, BigDecimal debtIssued, BigDecimal debtRepaid,
                                    BigDecimal leasePayments, BigDecimal cashChange, BigDecimal endingCash) {
    }

    public record SegmentFigures(long segmentId, String segmentType, String segmentName, String segmentNameEn,
                                 BigDecimal revenue, BigDecimal costOfRevenue, BigDecimal grossProfit,
                                 BigDecimal operatingIncome, BigDecimal totalAssets) {
    }

    /** @param unit ratio (fraction, 0.25 = 25%), x (multiple), days, or a currency amount (IDR, IDR/share) */
    public record Metric(String category, BigDecimal value, String unit) {
    }

    public record Segment(long segmentId, String segmentType, String segmentName, String segmentNameEn,
                          String description, boolean active) {
    }

    public record ShareSnapshot(LocalDate snapshotDate, BigDecimal basicShares, BigDecimal dilutedShares,
                                BigDecimal sharesOutstanding, BigDecimal publicFloat, BigDecimal treasuryShares) {
    }

    /**
     * The latest daily prices, in {@code company.currency} like every amount. A company that reports in another
     * currency than its listing trades in (CUAN, DSSA, INDY: USD reporters quoted in IDR) has its prices stored
     * converted; {@code quote} gives them as quoted on the exchange.
     */
    public record Price(LocalDate tradingDate, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                        BigDecimal closePrice, BigDecimal adjustedClose, Long volume, Quote quote) {
    }

    /**
     * Prices as quoted on the exchange, for a company whose prices are stored converted into its reporting currency:
     * the stored price x the rate the price ingestion converted it with (the last FX day before the trading day),
     * rounded to the exchange's 2 decimals (CUAN 2026-10-16: USD 0.03009362 x 17,944 = Rp 540.00).
     *
     * @param currency the exchange's currency (IDR)
     * @param fxRate   exchange currency per reporting-currency unit (IDR per USD)
     */
    public record Quote(String currency, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice,
                        BigDecimal closePrice, BigDecimal fxRate, LocalDate fxDate) {
    }

    /** @param quotePrice the share price as quoted on the exchange ({@link Quote}); {@code null} when it is sharePrice */
    public record MarketSnapshot(LocalDate snapshotDate, BigDecimal sharePrice, BigDecimal sharesOutstanding,
                                 BigDecimal marketCap, BigDecimal enterpriseValue, String quoteCurrency,
                                 BigDecimal quotePrice) {
    }

    /**
     * @param period     reporting period whose trailing-twelve-month figures and balance sheet are used, e.g. "2026 H1"
     * @param quotePrice the share price as quoted on the exchange ({@link Quote}); {@code null} when it is sharePrice
     * @param forwardEps the period's profit to the parent made a year (Q1 x 4, H1 x 2, 9M x 4/3, FY x 1) / shares
     * @param bookValuePerShare equity attributable to the parent / shares
     */
    public record Valuation(LocalDate valuationDate, Long periodId, String period, BigDecimal sharePrice,
                            BigDecimal marketCap, BigDecimal enterpriseValue, BigDecimal epsTtm,
                            BigDecimal revenueTtm, BigDecimal ebitdaTtm, BigDecimal operatingIncomeTtm,
                            BigDecimal fcfTtm, BigDecimal bookValue, BigDecimal peRatio, BigDecimal psRatio,
                            BigDecimal pbRatio, BigDecimal evEbitda, BigDecimal evSales, BigDecimal evOp,
                            BigDecimal fcfYield, BigDecimal earningsYield, String quoteCurrency, BigDecimal quotePrice,
                            String ebitdaBasis, BigDecimal forwardEps, BigDecimal bookValuePerShare) {
    }

    public record CorporateAction(LocalDate actionDate, String actionType, BigDecimal ratioFrom, BigDecimal ratioTo,
                                  BigDecimal sharesIssued, BigDecimal cashRaised, String description) {
    }
}
