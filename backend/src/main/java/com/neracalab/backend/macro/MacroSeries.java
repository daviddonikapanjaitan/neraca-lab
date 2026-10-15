package com.neracalab.backend.macro;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * A macro series of the catalog ({@code macro_series_def}, seeded by V1.0.26__data_markets_macro.sql).
 *
 * @param sourceRef      FRED id, Yahoo / CNBC symbol, CFTC contract code, BI page URL or SEKI {@code TABLE.xls#row label}
 * @param releaseLagDays days after the end of the period the value is public
 * @param minValue       a value below is rejected (the job fails, nothing is stored)
 * @param maxValue       a value above is rejected
 * @param maxChange      a larger move between two observations is reported as a warning ({@code null}: no check)
 */
public record MacroSeries(String code, String name, String country, String category, String unit, String frequency,
                          String source, String sourceRef, String sourceUrl, int releaseLagDays, int priority,
                          String usedBy, BigDecimal minValue, BigDecimal maxValue, BigDecimal maxChange,
                          LocalDate historyFrom, String description) {

    public boolean manual() {
        return "manual".equals(source);
    }

    public boolean monthly() {
        return "MONTHLY".equals(frequency);
    }

    /** The last day of the period an observation dated {@code obsDate} describes (a month: its last day). */
    public LocalDate periodEnd(LocalDate obsDate) {
        return switch (frequency) {
            case "MONTHLY" -> obsDate.with(TemporalAdjusters.lastDayOfMonth());
            case "QUARTERLY" -> obsDate.withMonth(((obsDate.getMonthValue() - 1) / 3) * 3 + 3)
                    .with(TemporalAdjusters.lastDayOfMonth());
            default -> obsDate;
        };
    }

    /** The day an observation became public: the end of its period plus the publication lag. */
    public LocalDate releaseDate(LocalDate obsDate) {
        return periodEnd(obsDate).plusDays(releaseLagDays);
    }

    /**
     * The latest observation date that should be public by {@code today}: a series whose latest stored observation is
     * older is behind ("Update all" queues it). Weekends are not counted for a daily series; an event series (the BI
     * rate, set at a monthly board meeting) is due again 31 days after its latest decision.
     */
    public LocalDate expectedLatest(LocalDate today) {
        return switch (frequency) {
            case "DAILY" -> {
                LocalDate d = today.minusDays(releaseLagDays + 1L);
                while (d.getDayOfWeek().getValue() >= 6) {
                    d = d.minusDays(1);
                }
                yield d;
            }
            // the latest week whose release (week end + lag) has passed
            case "WEEKLY" -> today.minusDays(7L + releaseLagDays);
            // the latest month whose release (month end + lag) has passed
            case "MONTHLY" -> {
                LocalDate month = today.withDayOfMonth(1).minusMonths(1);
                while (month.with(TemporalAdjusters.lastDayOfMonth()).plusDays(releaseLagDays).isAfter(today)) {
                    month = month.minusMonths(1);
                }
                yield month;
            }
            case "QUARTERLY" -> {
                LocalDate quarter = today.withDayOfMonth(1).withMonth(((today.getMonthValue() - 1) / 3) * 3 + 1).minusMonths(3);
                while (periodEnd(quarter).plusDays(releaseLagDays).isAfter(today)) {
                    quarter = quarter.minusMonths(3);
                }
                yield quarter;
            }
            default -> today.minusDays(31);
        };
    }

    /** Whether a series whose latest observation is {@code latest} is up to date at {@code today}. */
    public boolean upToDate(LocalDate latest, LocalDate today) {
        return latest != null && !latest.isBefore(expectedLatest(today));
    }
}
