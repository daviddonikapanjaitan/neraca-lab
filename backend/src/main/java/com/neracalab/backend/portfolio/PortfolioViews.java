package com.neracalab.backend.portfolio;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Portfolio &gt; Position, the columns of {@code instruction/Position Portfolio.xlsx} (sheet Position). Amounts in the
 * listing currency (IDR); the company amounts (EBITDA, market cap, debt, cash, EV, OP, share count) in billions, as the
 * sheet's "(M)" columns.
 */
public final class PortfolioViews {

    private PortfolioViews() {
    }

    /** POST /api/v1/portfolio/positions */
    public record PositionRequest(
            @NotBlank @Size(max = 16) String exchange,
            @NotBlank @Size(max = 20) String ticker,
            @NotNull @DecimalMin(value = "0.0001", message = "must be greater than 0") @Digits(integer = 15, fraction = 4)
            java.math.BigDecimal averagePrice,
            @NotNull @DecimalMin(value = "1", message = "must be at least 1") @Digits(integer = 20, fraction = 0,
                    message = "must be a whole number of shares") java.math.BigDecimal shares,
            LocalDate dateIn) {
    }

    /** PUT /api/v1/portfolio/positions/{id}: the company of a position does not change */
    public record PositionUpdate(
            @NotNull @DecimalMin(value = "0.0001", message = "must be greater than 0") @Digits(integer = 15, fraction = 4)
            java.math.BigDecimal averagePrice,
            @NotNull @DecimalMin(value = "1", message = "must be at least 1") @Digits(integer = 20, fraction = 0,
                    message = "must be a whole number of shares") java.math.BigDecimal shares,
            LocalDate dateIn) {
    }

    /** PUT /api/v1/portfolio/settings */
    public record SettingsRequest(
            @NotNull @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            java.math.BigDecimal cash,
            @NotNull @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            java.math.BigDecimal totalInvestment) {
    }

    /**
     * One row of the sheet.
     *
     * @param lastPrice          the latest price (listing currency): the intraday price of a session newer than the
     *                           stored daily close (as the sheet's GOOGLEFINANCE price), else the last daily close
     * @param change             lastPrice - the close of the session before
     * @param changePct          change / the close of the session before
     * @param priceSource        INTRADAY or DAILY: where lastPrice comes from
     * @param priceTime          the time of the last trade of an intraday price
     * @param intraday           the latest intraday session stored for the stock (null: none yet)
     * @param dayGain            change x shares: the position's gain or loss of the day
     * @param averageValue       averagePrice x shares ("AVG VALUE")
     * @param marketValue        lastPrice x shares ("MARKET VALUE")
     * @param gainLoss           marketValue - averageValue ("G/L")
     * @param gainLossPct        gainLoss / averageValue ("%POT G/L")
     * @param portfolioAveragePct averageValue / (averageTotal + cash)
     * @param portfolioMarketPct marketValue / (marketTotal + cash) ("Port Mar. %")
     * @param bookValuePerShare  equity attributable to the parent / shares, latest filing
     * @param forwardEps         the latest filing's profit to the parent made a year (Q1 x 4, H1 x 2, 9M x 4/3, FY x 1)
     *                           / shares: the valuation's forward EPS
     * @param debtToEquity       interest-bearing debt / equity (Metrics tab)
     * @param evEbitda           ev / ebitdaForward ("EV/Ebitda")
     * @param per                lastPrice / forwardEps
     * @param pbv                lastPrice / bookValuePerShare
     * @param dividend           cash dividends per share with an ex date in {@code dividendYear} (this year, else the
     *                           year before), Profile tab, on today's share basis
     * @param dividendYield      dividend / lastPrice
     * @param lastFiling         "9M 2026" ("Lapkeu")
     * @param ebitdaForward      EBITDA of the latest filing made a year, billions
     * @param sharesOutstanding  billions
     * @param marketCap          lastPrice x sharesOutstanding, billions
     * @param totalDebt          total liabilities of the latest filing, billions (the sheet's "Total Debt")
     * @param cash               cash and equivalents of the latest filing, billions
     * @param ev                 totalDebt + marketCap - cash, billions (the sheet's EV)
     * @param operatingProfit    operating income of the latest filing made a year, billions ("OP")
     * @param evOp               ev / operatingProfit
     * @param convertedFrom      the reporting currency the company figures were converted from (USD), else null
     */
    public record Position(long positionId, String exchange, String ticker, String companyName, String sector,
                           Double lastPrice, Double change, Double changePct, LocalDate priceDate,
                           String priceSource, OffsetDateTime priceTime, Intraday intraday, Double dayGain,
                           double averagePrice, double shares, double averageValue, Double marketValue,
                           Double gainLoss, Double gainLossPct, Double portfolioAveragePct, Double portfolioMarketPct,
                           Double bookValuePerShare, Double forwardEps, Double debtToEquity, Double evEbitda,
                           Double per, Double pbv, Double dividend, Integer dividendYear, Double dividendYield,
                           String lastFiling, LocalDate periodEnd, Double ebitdaForward, Double sharesOutstanding,
                           Double marketCap, Double totalDebt, Double cash, Double ev, Double operatingProfit,
                           Double evOp, LocalDate dateIn, String convertedFrom, List<String> notes) {
    }

    /**
     * The latest intraday session of a stock (Yahoo Finance 5-minute bars, listing currency).
     *
     * @param points     [epoch seconds, close] of each bar, oldest first: the chart of the day
     * @param marketTime the last trade
     * @param fetchedAt  when it was read
     */
    public record Intraday(LocalDate sessionDate, Double previousClose, Double open, Double high, Double low,
                           Long volume, OffsetDateTime marketTime, OffsetDateTime fetchedAt, List<double[]> points) {
    }

    /**
     * The trading day as the page shows it.
     *
     * @param phase        the IDX phase now: PRE_OPENING, SESSION_1, BREAK, SESSION_2, PRE_CLOSING, POST_TRADING, CLOSED
     * @param trading      prices can change now
     * @param sessionDate  the latest intraday session stored for the portfolio's stocks
     * @param updatedAt    the latest intraday fetch of the portfolio's stocks
     * @param everyMinutes the schedule's interval (null: unknown)
     * @param scheduled    the schedule is on
     */
    public record Live(String phase, String phaseLabel, boolean trading, LocalDate sessionDate,
                       OffsetDateTime updatedAt, Integer everyMinutes, boolean scheduled) {
    }

    /**
     * The metrics of the page.
     *
     * @param averageTotal    sum of the positions' averageValue ("Port Total")
     * @param marketTotal     sum of the positions' marketValue
     * @param profitMarket    marketTotal - averageTotal
     * @param profitMarketPct profitMarket / averageTotal
     * @param totalProfit     marketTotal + cash - totalInvestment
     * @param totalProfitPct  totalProfit / totalInvestment
     * @param totalValue      marketTotal + cash
     * @param dayGain         sum of the positions' dayGain: the stocks' gain or loss of the day
     * @param dayGainPct      dayGain / the stocks' value at the closes of the session before
     */
    public record Summary(double cash, double totalInvestment, double averageTotal, double marketTotal,
                          double profitMarket, Double profitMarketPct, double totalProfit, Double totalProfitPct,
                          double totalValue, int positions, int priced, Double dayGain, Double dayGainPct) {
    }

    /** A slice of the sector chart: a sector's market value (or the cash) and its share of marketTotal + cash. */
    public record Slice(String name, boolean cash, double value, Double share, List<String> tickers) {
    }

    public record Portfolio(String currency, OffsetDateTime computedAt, Summary summary, List<Position> positions,
                            List<Slice> allocation, Live live, List<String> notes) {
    }
}
