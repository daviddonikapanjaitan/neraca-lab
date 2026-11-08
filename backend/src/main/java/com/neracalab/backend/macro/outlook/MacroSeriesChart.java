package com.neracalab.backend.macro.outlook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.neracalab.backend.macro.MacroRepository.Row;

/**
 * The chart of one macro series on Macro › Data (pure: no database). The window of a range ends at the series' latest
 * observation, so a series published late (a monthly figure, a manual CSV) still shows its range; the page shows that
 * date.
 * <ul>
 *   <li>LEVEL (an index, an amount): changes in percent and in units</li>
 *   <li>RATE (unit %, bps or points: a rate, a yield, a spread): changes in units (points) only</li>
 *   <li>FLOW (net foreign buying on IDX: one session's flow): a period is the sum of its observations</li>
 * </ul>
 * More than {@link #MAX_POINTS} observations in the window are drawn weekly (the last observation of each week; a
 * flow: the week's sum); the statistics always read every observation of the window.
 */
public final class MacroSeriesChart {

    /** Above this many observations in the window the chart is drawn weekly. */
    public static final int MAX_POINTS = 2_000;

    /** Units read in points. */
    static final Set<String> RATE_UNITS = Set.of("%", "bps", "points");

    public enum Kind { LEVEL, RATE, FLOW }

    /** One observation: date, value, release date (the day it was public). */
    public record Point(LocalDate date, BigDecimal value, LocalDate released) {
    }

    /**
     * @param change    last - first of the window (a flow: the sum of the window)
     * @param changePct (last - first) / |first| in percent; LEVEL only
     */
    public record Stats(int observations, Point first, Point last, Point high, Point low, BigDecimal average,
                        BigDecimal change, BigDecimal changePct) {
    }

    /**
     * A change to the latest observation over a period (a flow: the sum over it).
     *
     * @param from     the observation it is measured from (on or before the start); null for a flow
     * @param change   in units (a flow: the sum)
     * @param pct      in percent; LEVEL only
     */
    public record Change(String period, LocalDate from, BigDecimal change, BigDecimal pct) {
    }

    /** One of the latest observations with its change to the one before (a flow: none). */
    public record Recent(LocalDate date, BigDecimal value, LocalDate released, BigDecimal change) {
    }

    /**
     * @param from    the window's first day (null: MAX)
     * @param to      the window's last day: the series' latest observation
     * @param sampled "WEEKLY" when the chart is drawn weekly, else null
     */
    public record Chart(String range, Kind kind, LocalDate from, LocalDate to, String sampled, List<Point> points,
                        Stats stats, List<Change> changes, List<Recent> recent) {
    }

    private MacroSeriesChart() {
    }

    public static Kind kind(String code, String unit) {
        if (MacroOutlookService.FLOW_SERIES.contains(code)) {
            return Kind.FLOW;
        }
        return unit != null && RATE_UNITS.contains(unit) ? Kind.RATE : Kind.LEVEL;
    }

    /**
     * @param rows  every observation of the series, oldest first
     * @param range 6M, 1Y, 3Y, 5Y, 10Y or MAX
     * @throws IllegalArgumentException an unknown range
     */
    public static Chart of(List<Row> rows, Kind kind, String range) {
        String r = MacroOutlookService.range(range);
        List<Point> all = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (row.value() != null) {
                all.add(new Point(row.obsDate(), row.value(),
                        row.releaseDate() == null ? row.obsDate() : row.releaseDate()));
            }
        }
        if (all.isEmpty()) {
            return new Chart(r, kind, null, null, null, List.of(), null, List.of(), List.of());
        }
        LocalDate to = all.getLast().date();
        LocalDate from = MacroOutlookService.rangeStart(r, to);
        // a level's window starts with the observation of its first day (the base of its change); a flow's sum is of
        // the period after it, as its 1M / 1Y changes, so the net over 1Y equals the 1Y change
        List<Point> window = from == null ? all : all.stream()
                .filter(p -> kind == Kind.FLOW ? p.date().isAfter(from) : !p.date().isBefore(from)).toList();

        String sampled = null;
        List<Point> points = window;
        if (window.size() > MAX_POINTS) {
            sampled = "WEEKLY";
            points = weekly(window, kind == Kind.FLOW);
        }
        return new Chart(r, kind, from, to, sampled, points, stats(window, kind), changes(all, kind),
                recent(all, kind));
    }

    /** The last observation of each week (Monday to Sunday), dated on it; a flow: the week's sum, dated on its last day. */
    static List<Point> weekly(List<Point> points, boolean sum) {
        List<Point> out = new ArrayList<>();
        LocalDate week = null;
        BigDecimal total = BigDecimal.ZERO;
        Point last = null;
        for (Point p : points) {
            LocalDate monday = p.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (week != null && !monday.equals(week)) {
                out.add(sum ? new Point(last.date(), total, last.released()) : last);
                total = BigDecimal.ZERO;
            }
            week = monday;
            total = total.add(p.value());
            last = p;
        }
        if (last != null) {
            out.add(sum ? new Point(last.date(), total, last.released()) : last);
        }
        return out;
    }

    static Stats stats(List<Point> window, Kind kind) {
        if (window.isEmpty()) {
            return null;
        }
        Point first = window.getFirst();
        Point last = window.getLast();
        Point high = first;
        Point low = first;
        BigDecimal sum = BigDecimal.ZERO;
        for (Point p : window) {
            // the earliest of equal extremes
            if (p.value().compareTo(high.value()) > 0) {
                high = p;
            }
            if (p.value().compareTo(low.value()) < 0) {
                low = p;
            }
            sum = sum.add(p.value());
        }
        BigDecimal average = sum.divide(BigDecimal.valueOf(window.size()), 6, RoundingMode.HALF_UP);
        BigDecimal change = kind == Kind.FLOW ? sum : last.value().subtract(first.value());
        BigDecimal pct = kind == Kind.LEVEL ? pct(first.value(), last.value()) : null;
        return new Stats(window.size(), first, last, high, low, average, change, pct);
    }

    /** 1M, 3M, 6M, YTD, 1Y, 3Y and 5Y to the latest observation; a period the series does not reach back to is left out. */
    static List<Change> changes(List<Point> all, Kind kind) {
        Point last = all.getLast();
        LocalDate to = last.date();
        List<Change> out = new ArrayList<>();
        Object[][] periods = {
                {"1M", to.minusMonths(1)}, {"3M", to.minusMonths(3)}, {"6M", to.minusMonths(6)},
                {"YTD", to.withDayOfYear(1).minusDays(1)}, {"1Y", to.minusYears(1)}, {"3Y", to.minusYears(3)},
                {"5Y", to.minusYears(5)}};
        LocalDate firstDate = all.getFirst().date();
        for (Object[] period : periods) {
            String name = (String) period[0];
            LocalDate start = (LocalDate) period[1];
            if (start.isBefore(firstDate)) {
                continue; // the series does not reach back that far
            }
            if (kind == Kind.FLOW) {
                BigDecimal sum = BigDecimal.ZERO;
                for (Point p : all) {
                    if (p.date().isAfter(start)) {
                        sum = sum.add(p.value());
                    }
                }
                out.add(new Change(name, null, sum, null));
                continue;
            }
            Point base = null;
            for (Point p : all) {
                if (p.date().isAfter(start)) {
                    break;
                }
                base = p;
            }
            if (base == null || base.date().equals(to)) {
                continue;
            }
            out.add(new Change(name, base.date(), last.value().subtract(base.value()),
                    kind == Kind.LEVEL ? pct(base.value(), last.value()) : null));
        }
        return out;
    }

    /** The 12 latest observations, newest first. */
    static List<Recent> recent(List<Point> all, Kind kind) {
        List<Recent> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < 12; i--) {
            Point p = all.get(i);
            BigDecimal change = kind == Kind.FLOW || i == 0 ? null : p.value().subtract(all.get(i - 1).value());
            out.add(new Recent(p.date(), p.value(), p.released(), change));
        }
        return out;
    }

    private static BigDecimal pct(BigDecimal from, BigDecimal to) {
        if (from.signum() == 0) {
            return null;
        }
        return to.subtract(from).multiply(BigDecimal.valueOf(100)).divide(from.abs(), 4, RoundingMode.HALF_UP);
    }
}
