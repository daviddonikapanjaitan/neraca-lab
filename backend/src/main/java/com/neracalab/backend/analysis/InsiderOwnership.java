package com.neracalab.backend.analysis;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.neracalab.backend.registration.RegistrationRepository.InsiderRow;
import com.neracalab.backend.registration.RegistrationRepository.ReportRow;

/**
 * The shares of the company's directors and commissioners from its monthly registration reports of securities
 * holders (Companies > Registration), for the analysis dossier: computed in Java, no model. The latest
 * {@link #MONTHS} months stored: who holds how many shares at the end of the latest month and what share of the
 * listed shares that is, what each one bought (+) or sold (-) month by month, and a one-line reading.
 */
final class InsiderOwnership {

    /** Months of reports read (the latest stored). */
    static final int MONTHS = 12;
    /** Transactions listed (the latest first). */
    static final int TRANSACTIONS = 20;

    private InsiderOwnership() {
    }

    /**
     * The section of the dossier, or null when no report is stored.
     *
     * @param reports the company's reports, latest month first (at most {@link #MONTHS} are read)
     * @param rows    the holdings of those months, latest month first, in the order of each report
     */
    static Map<String, Object> of(List<ReportRow> reports, List<InsiderRow> rows) {
        if (reports.isEmpty()) {
            return null;
        }
        List<ReportRow> read = reports.subList(0, Math.min(MONTHS, reports.size()));
        TreeSet<String> periods = new TreeSet<>();
        read.forEach(r -> periods.add(r.period()));
        List<InsiderRow> inRange = rows.stream().filter(r -> periods.contains(r.period())).toList();
        ReportRow latest = read.getFirst();
        Long listed = latest.listedSharesCurrent();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "monthly registration reports of securities holders (Laporan Bulanan Registrasi Pemegang "
                + "Efek): the shares of the directors and commissioners at the end of each month; change = shares "
                + "bought (+) or sold (-) during the month; ownership is a fraction of the shares listed");
        out.put("months", List.copyOf(periods));
        out.put("latestMonth", latest.period());
        if (periods.size() < monthsBetween(periods)) {
            out.put("missingMonths", missing(periods));
        }
        out.put("listedShares", listed);

        // the holders of the latest month, in the order of its report
        List<InsiderRow> current = inRange.stream().filter(r -> r.period().equals(latest.period())).toList();
        List<Map<String, Object>> insiders = new ArrayList<>();
        long totalShares = 0;
        boolean allKnown = true;
        for (InsiderRow r : current) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("name", r.holderName());
            i.put("role", r.role().name());
            i.put("position", r.position());
            i.put("shares", r.sharesCurrent());
            i.put("ownership", ownership(r.sharesCurrent(), r.percentCurrent(), listed));
            i.put("changeLatestMonth", r.change());
            i.put("netChange", netChange(inRange, r.holderName()));
            if (r.controller()) {
                i.put("controller", true);
            }
            if (r.controllerAffiliate()) {
                i.put("controllerAffiliate", true);
            }
            insiders.add(i);
            if (r.sharesCurrent() == null) {
                allKnown = false;
            } else {
                totalShares += r.sharesCurrent();
            }
        }
        out.put("insiders", insiders);
        Set<String> now = new LinkedHashSet<>();
        current.forEach(r -> now.add(key(r.holderName())));
        Set<String> gone = new LinkedHashSet<>();
        inRange.stream().filter(r -> !now.contains(key(r.holderName()))).forEach(r -> gone.add(r.holderName()));
        if (!gone.isEmpty()) {
            out.put("notInLatestReport", List.copyOf(gone));
        }

        long bought = 0;
        long sold = 0;
        int buys = 0;
        int sells = 0;
        List<Map<String, Object>> transactions = new ArrayList<>();
        for (InsiderRow r : inRange) {
            if (r.change() == null || r.change() == 0) {
                continue;
            }
            if (r.change() > 0) {
                bought += r.change();
                buys++;
            } else {
                sold -= r.change();
                sells++;
            }
            if (transactions.size() < TRANSACTIONS) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("month", r.period());
                t.put("name", r.holderName());
                t.put("role", r.role().name());
                t.put("change", r.change());
                t.put("sharesAfter", r.sharesCurrent());
                transactions.add(t);
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("insiders", current.size());
        totals.put("shares", allKnown ? totalShares : null);
        totals.put("ownership", allKnown && listed != null && listed > 0 ? round6((double) totalShares / listed) : null);
        totals.put("sharesBought", bought);
        totals.put("sharesSold", sold);
        totals.put("netChange", bought - sold);
        totals.put("buys", buys);
        totals.put("sells", sells);
        out.put("totals", totals);
        out.put("transactions", transactions);
        if (buys + sells > TRANSACTIONS) {
            out.put("transactionsNote", "the latest " + TRANSACTIONS + " of " + (buys + sells) + " are listed");
        }
        long warnings = read.stream().filter(r -> !r.warnings().isEmpty()).count();
        if (warnings > 0) {
            out.put("reportWarnings", warnings + " reports print a percentage that does not match its share count");
        }
        out.put("reading", reading(periods, current.size(), allKnown ? totalShares : null,
                (Double) totals.get("ownership"), bought, sold, buys, sells));
        return out;
    }

    /** "No director or commissioner bought or sold shares in May 2026 - Aug 2026; 4 hold 25,102,200 shares (0.55%)." */
    static String reading(TreeSet<String> periods, int insiders, Long shares, Double ownership, long bought, long sold,
                          int buys, int sells) {
        String range = label(periods.first()) + (periods.size() > 1 ? " - " + label(periods.last()) : "");
        StringBuilder b = new StringBuilder();
        if (buys == 0 && sells == 0) {
            b.append("No director or commissioner bought or sold shares in ").append(range);
        } else {
            b.append("Directors and commissioners ");
            if (buys > 0) {
                b.append("bought ").append(String.format(Locale.ROOT, "%,d", bought)).append(" shares (")
                        .append(buys).append(buys == 1 ? " purchase)" : " purchases)");
            }
            if (sells > 0) {
                b.append(buys > 0 ? " and " : "").append("sold ").append(String.format(Locale.ROOT, "%,d", sold))
                        .append(" shares (").append(sells).append(sells == 1 ? " sale)" : " sales)");
            }
            b.append(" in ").append(range).append(", net ")
                    .append(String.format(Locale.ROOT, "%+,d", bought - sold));
        }
        if (shares != null) {
            b.append("; ").append(insiders).append(insiders == 1 ? " holds " : " hold ")
                    .append(String.format(Locale.ROOT, "%,d", shares)).append(" shares");
            if (ownership != null) {
                b.append(" (").append(String.format(Locale.ROOT, "%.2f%%", ownership * 100)).append(" of the listed shares)");
            }
        }
        return b.append('.').toString();
    }

    /** The holding as a fraction of the shares listed (the printed percentage when the listed shares are unknown). */
    private static Double ownership(Long shares, BigDecimal percent, Long listed) {
        if (shares != null && listed != null && listed > 0) {
            return round6((double) shares / listed);
        }
        return percent == null ? null : round6(percent.doubleValue() / 100);
    }

    private static Long netChange(List<InsiderRow> rows, String name) {
        long sum = 0;
        boolean any = false;
        for (InsiderRow r : rows) {
            if (key(r.holderName()).equals(key(name)) && r.change() != null) {
                sum += r.change();
                any = true;
            }
        }
        return any ? sum : null;
    }

    private static String key(String name) {
        return name.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static int monthsBetween(TreeSet<String> periods) {
        return (int) (YearMonth.parse(periods.first()).until(YearMonth.parse(periods.last()), ChronoUnit.MONTHS) + 1);
    }

    private static List<String> missing(TreeSet<String> periods) {
        List<String> out = new ArrayList<>();
        YearMonth m = YearMonth.parse(periods.first());
        YearMonth last = YearMonth.parse(periods.last());
        while (!m.isAfter(last)) {
            if (!periods.contains(m.toString())) {
                out.add(m.toString());
            }
            m = m.plusMonths(1);
        }
        return out;
    }

    /** "2026-05" -> "May 2026" */
    static String label(String period) {
        YearMonth m = YearMonth.parse(period);
        return m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + m.getYear();
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }
}
