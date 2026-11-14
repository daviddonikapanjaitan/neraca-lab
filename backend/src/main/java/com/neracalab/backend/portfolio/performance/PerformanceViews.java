package com.neracalab.backend.portfolio.performance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

/**
 * Portfolio &gt; Performance, the sheet {@code tracking_performance} of {@code instruction/Position Portfolio.xlsx}:
 * a row per year, the daily snapshots saved at the end of each IDX session and the chart comparing the portfolio with
 * IHSG and LQ45. Amounts in IDR; percentages as fractions (0.2192 = +21.92%).
 */
public final class PerformanceViews {

    private PerformanceViews() {
    }

    /**
     * PUT /api/v1/portfolio/performance/years/{year}: a year as the sheet records it. Empty fields are allowed: an
     * empty start capital is taken from the year before (its market value + cash at the year end), empty year-end
     * figures from the year's last daily snapshot.
     *
     * @param startCapital  "Total Invest &lt;year before&gt;": the capital at the start of the year
     * @param newInvestment "Total Invest &lt;year&gt;": money put in during the year (negative: taken out); empty = 0
     * @param dividend      "Deviden": cash dividends received in the year
     * @param ihsgYtd       IHSG YTD as the sheet recorded it (0.2192 = +21.92%)
     */
    public record YearRequest(
            @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            BigDecimal startCapital,
            @Digits(integer = 18, fraction = 2) BigDecimal newInvestment,
            @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            BigDecimal dividend,
            @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            BigDecimal cash,
            @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            BigDecimal marketValue,
            @DecimalMin(value = "0", message = "must not be negative") @Digits(integer = 18, fraction = 2)
            BigDecimal averageValue,
            @DecimalMin(value = "-1", message = "must be -1 (-100%) or more")
            @DecimalMax(value = "100", message = "must be 100 (+10,000%) or less") @Digits(integer = 6, fraction = 6)
            BigDecimal ihsgYtd,
            @DecimalMin(value = "-1", message = "must be -1 (-100%) or more")
            @DecimalMax(value = "100", message = "must be 100 (+10,000%) or less") @Digits(integer = 6, fraction = 6)
            BigDecimal lq45Ytd,
            @Size(max = 500) String note) {
    }

    /**
     * A year of the table (the sheet's row), with what it is computed from.
     *
     * @param status         CLOSED (a past year), CURRENT (this year, to date) or FUTURE (a year of the range not
     *                       begun)
     * @param typed          the user saved a row for the year
     * @param startCapital   the capital at the start of the year
     * @param startFrom      TYPED (the row) or YEAR_BEFORE (market value + cash at the end of the year before); null
     *                       when unknown
     * @param endFrom        where the year-end (to-date) figures come from: LIVE (the portfolio now, current year),
     *                       SNAPSHOT (the year's last daily snapshot) or TYPED (the row); null when none
     * @param endDate        the day of those figures (a typed row of a past year: 31 December; null when unknown)
     * @param totalValue     marketValue + cash
     * @param profit         the sheet's POT G/L: totalValue - startCapital - newInvestment
     * @param profitPct      profit / startCapital (POT G/L %)
     * @param dividendPct    dividend / totalValue (Deviden %)
     * @param ihsg           IHSG over the year to endDate from {@code index_daily}: its close on endDate / the last
     *                       close of the year before - 1
     * @param ihsgRecorded   IHSG YTD as the sheet (or the snapshot) recorded it
     * @param row            the row as the user typed it (null: none), for the edit form
     */
    public record YearView(int year, String status, boolean typed, Double startCapital, String startFrom,
                           double newInvestment, Double cash, Double marketValue, Double averageValue,
                           String endFrom, LocalDate endDate, Double totalValue, Double profit, Double profitPct,
                           Double dividend, Double dividendPct, Double ihsg, Double lq45, Double ihsgRecorded,
                           Double lq45Recorded, Double portfolioVsIhsg, String note, YearRequest row) {
    }

    /**
     * A point of the chart: the change since the range's base (the last close of the year before the range) of the
     * portfolio, IHSG and LQ45; null where a series has no value that day.
     *
     * @param kind where the portfolio's value comes from: BASE, YEAR_END (a year row), SNAPSHOT, LIVE; null when the
     *             point has no portfolio value
     */
    public record Point(LocalDate date, Double portfolio, Double ihsg, Double lq45, String kind) {
    }

    /**
     * The chart of a range: "ALL" (the first year to the last) or one year ("2025").
     *
     * @param from      the range's first day (1 January)
     * @param to        its last day (31 December: the axis runs to the year end, also of a year under way)
     * @param base      the day the three series start from at 0% (the last trading day of the year before)
     * @param portfolio the portfolio's change over the whole range (to its latest point)
     */
    public record Chart(String range, LocalDate from, LocalDate to, LocalDate base, List<Point> points,
                        Double portfolio, Double ihsg, Double lq45, LocalDate latest, List<String> notes) {
    }

    /** A saved daily snapshot. */
    public record Snapshot(LocalDate date, OffsetDateTime capturedAt, String source, int positions, int priced,
                           int pricedToday, double cash, double marketValue, double averageValue, double totalValue,
                           double profitLoss, Double profitLossPct, Double dayGain, Double dayGainPct,
                           double totalInvestment, Double yearStartCapital, double yearNewInvestment,
                           Double ytdProfit, Double ytdProfitPct, Double ihsgClose, LocalDate ihsgDate,
                           Double ihsgChangePct, Double ihsgYtdPct, Double lq45Close, LocalDate lq45Date,
                           Double lq45ChangePct, Double lq45YtdPct) {
    }

    /**
     * When the snapshots are saved.
     *
     * @param scheduled  the schedule is on (every 15 minutes from saveFrom to 23:45 WIB on weekdays)
     * @param saveFrom   the earliest time of the day's snapshot (WIB): the end of the session
     * @param canSaveNow "Save today" is allowed now (a weekday at or after saveFrom)
     * @param savedToday today's snapshot is stored
     * @param next       when the next snapshot is due (WIB)
     * @param reason     why it cannot be saved now (null: it can)
     */
    public record Saving(boolean scheduled, String saveFrom, String cron, boolean canSaveNow, boolean savedToday,
                         OffsetDateTime next, LocalDate lastSaved, int saved, String reason) {
    }

    /** GET /api/v1/portfolio/performance */
    public record Performance(String currency, OffsetDateTime computedAt, int currentYear, List<String> ranges,
                              Chart chart, List<YearView> years, List<Snapshot> snapshots, Snapshot live,
                              Saving saving, List<String> notes) {
    }

    /** POST /api/v1/portfolio/performance/snapshots: what was saved and the page as it now stands. */
    public record Saved(Snapshot snapshot, String message, Performance performance) {
    }
}
