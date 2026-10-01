package com.neracalab.backend.ingestion.mapping;

import java.time.LocalDate;

/**
 * A reporting period as stored in {@code reporting_period}. Year-to-date periods: Q1 (3 months),
 * H1 (6), 9M (9), FY (12). {@code fiscalQuarter} is the quarter the period ends in, null for FY.
 */
public record PeriodRef(int fiscalYear, Integer fiscalQuarter, String periodType, LocalDate start, LocalDate end) {

    public boolean isFullYear() {
        return "FY".equals(periodType);
    }

    public String key() {
        return fiscalYear + " " + periodType;
    }

    /** Period from start/end dates of a year-to-date report starting at the fiscal year start. */
    public static PeriodRef yearToDate(LocalDate start, LocalDate end) {
        long months = java.time.Period.between(start, end.plusDays(1)).toTotalMonths();
        String type = switch ((int) months) {
            case 3 -> "Q1";
            case 6 -> "H1";
            case 9 -> "9M";
            case 12 -> "FY";
            default -> throw new IllegalArgumentException(
                    "Unsupported period " + start + " .. " + end + " (" + months + " months); expected 3, 6, 9 or 12");
        };
        Integer quarter = months == 12 ? null : (int) (months / 3);
        // fiscal year = calendar year in which the fiscal year (starting at 'start') ends
        int fiscalYear = start.plusYears(1).minusDays(1).getYear();
        return new PeriodRef(fiscalYear, quarter, type, start, end);
    }

    /** Full fiscal year ending on {@code end}. */
    public static PeriodRef fullYearEnding(LocalDate end) {
        return new PeriodRef(end.getYear(), null, "FY", end.minusYears(1).plusDays(1), end);
    }
}
