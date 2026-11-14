package com.neracalab.backend.portfolio.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.portfolio.PortfolioService;
import com.neracalab.backend.portfolio.PortfolioViews.Portfolio;
import com.neracalab.backend.portfolio.PortfolioViews.Position;
import com.neracalab.backend.portfolio.intraday.IdxSession;
import com.neracalab.backend.portfolio.intraday.IntradayService;
import com.neracalab.backend.portfolio.performance.PerformanceRepository.Close;
import com.neracalab.backend.portfolio.performance.PerformanceRepository.Year;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Chart;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Performance;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Point;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Saved;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Saving;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Snapshot;
import com.neracalab.backend.portfolio.performance.PerformanceViews.YearRequest;
import com.neracalab.backend.portfolio.performance.PerformanceViews.YearView;
import com.neracalab.backend.price.IndexIngestion;
import com.neracalab.backend.web.ApiException;

/**
 * Portfolio &gt; Performance of the signed-in user: the sheet {@code tracking_performance} of
 * {@code instruction/Position Portfolio.xlsx}.
 * <ul>
 *   <li><b>Years</b> - a row per year as the sheet keeps it: the capital at the start of the year (the market value +
 *       cash at the end of the year before), the money put in during the year, the cash, market value and average
 *       value at the year end, the dividends. POT G/L = market value + cash - start capital - new investment;
 *       POT G/L % = POT G/L / start capital; Deviden % = dividend / (market value + cash). IHSG and LQ45 over the same
 *       year come from {@code index_daily} (their close at the year end / the last close of the year before - 1).</li>
 *   <li><b>Daily snapshots</b> - at or after 17:00 WIB (the end of the IDX session; {@code neracalab.performance.
 *       save-from}) of every IDX trading day the portfolio as Portfolio &gt; Position shows it (the stocks' latest
 *       prices are read first), the year's POT G/L so far and IHSG / LQ45 with their change of the day and of the
 *       year are saved once ({@link PerformanceSchedule}, or the page's "Save today"). A weekday without an IHSG bar
 *       nor an intraday session of the day (an exchange holiday) is not saved.</li>
 *   <li><b>Chart</b> - the change of the portfolio, IHSG and LQ45 since the last close of the year before the range:
 *       the indexes daily; the portfolio from its year rows (each year's POT G/L % chained) and, within a year, from
 *       its daily snapshots (and today's live value when today is not saved yet). The axis runs to the end of the
 *       range's last year (2026 at least).</li>
 * </ul>
 */
@Service
public class PerformanceService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceService.class);

    public static final String IHSG = "IHSG";
    public static final String LQ45 = "LQ45";
    public static final String ALL = "ALL";
    /** Latest snapshots the page lists. */
    static final int SNAPSHOTS_SHOWN = 60;

    private final PerformanceRepository repository;
    private final PortfolioService portfolios;
    private final IntradayService intraday;
    private final IndexIngestion indexes;
    private final LocalTime saveFrom;
    private final int firstYear;
    private final int lastYear;
    private final boolean scheduled;
    private final String cron;
    private final ReentrantLock saving = new ReentrantLock();
    private Clock clock = Clock.system(IdxSession.ZONE);

    @Autowired
    public PerformanceService(PerformanceRepository repository, PortfolioService portfolios, IntradayService intraday,
                              IndexIngestion indexes,
                              @Value("${neracalab.performance.save-from:17:00}") LocalTime saveFrom,
                              @Value("${neracalab.performance.first-year:2022}") int firstYear,
                              @Value("${neracalab.performance.last-year:2026}") int lastYear,
                              @Value("${neracalab.performance.schedule.enabled:true}") boolean scheduled,
                              @Value("${neracalab.performance.schedule.cron:0 0/15 17-23 * * MON-FRI}") String cron) {
        this.repository = repository;
        this.portfolios = portfolios;
        this.intraday = intraday;
        this.indexes = indexes;
        this.saveFrom = saveFrom;
        this.firstYear = firstYear;
        this.lastYear = lastYear;
        this.scheduled = scheduled;
        this.cron = cron;
    }

    /** For tests: a fixed time. */
    void clock(Clock clock) {
        this.clock = clock;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock).withZoneSameInstant(IdxSession.ZONE);
    }

    // ------------------------------------------------------------------ the page

    public Performance performance(long userId, String range) {
        ZonedDateTime now = now();
        LocalDate today = now.toLocalDate();
        int current = today.getYear();
        List<Year> typed = repository.years(userId);
        int first = Math.min(firstYear, typed.stream().mapToInt(Year::year).min().orElse(firstYear));
        int last = Math.max(Math.max(lastYear, current), typed.stream().mapToInt(Year::year).max().orElse(lastYear));
        List<String> ranges = new ArrayList<>();
        ranges.add(ALL);
        for (int y = first; y <= last; y++) {
            ranges.add(String.valueOf(y));
        }
        String wanted = range == null || range.isBlank() ? ALL : range.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ranges.contains(wanted)) {
            throw ApiException.badRequest("range: one of " + String.join(", ", ranges));
        }

        // today's portfolio, not saved: the current year's figures to date and the chart's last point
        Snapshot live = null;
        List<String> notes = new ArrayList<>();
        try {
            live = compute(userId, portfolios.portfolio(userId), today, "LIVE", now.toOffsetDateTime());
        } catch (RuntimeException e) {
            log.warn("Performance of user {}: the live portfolio failed: {}", userId, e.toString());
            notes.add("The portfolio's current value could not be computed.");
        }
        Snapshot shownLive = live != null && live.positions() > 0 ? live : null;

        Years years = new Years(userId, typed, current, shownLive);
        List<YearView> views = new ArrayList<>();
        for (int y = first; y <= last; y++) {
            views.add(years.view(y));
        }

        int from = ALL.equals(wanted) ? first : Integer.parseInt(wanted);
        int to = ALL.equals(wanted) ? last : Integer.parseInt(wanted);
        Chart chart = chart(userId, wanted, from, to, years, shownLive, today);

        List<Snapshot> snapshots = repository.latestSnapshots(userId, SNAPSHOTS_SHOWN).stream()
                .map(PerformanceService::view).toList();
        return new Performance("IDR", OffsetDateTime.now(clock).withOffsetSameInstant(java.time.ZoneOffset.UTC),
                current, ranges, chart, views, snapshots, shownLive, saving(userId, now), notes);
    }

    /** When the snapshots are saved, and whether "Save today" is allowed now. */
    Saving saving(long userId, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        boolean weekday = weekday(today);
        boolean late = !now.toLocalTime().isBefore(saveFrom);
        boolean savedToday = repository.hasSnapshot(userId, today);
        String reason = !weekday ? "IDX does not trade on weekends."
                : !late ? "Today's snapshot can be saved from " + saveFrom + " WIB, after the session closes."
                : null;
        ZonedDateTime next;
        if (weekday && !savedToday) {
            next = late ? now : today.atTime(saveFrom).atZone(IdxSession.ZONE);
        } else {
            LocalDate d = today.plusDays(1);
            while (!weekday(d)) {
                d = d.plusDays(1);
            }
            next = d.atTime(saveFrom).atZone(IdxSession.ZONE);
        }
        List<PerformanceRepository.Snapshot> latest = repository.latestSnapshots(userId, 1);
        return new Saving(scheduled, saveFrom.toString(), cron, weekday && late, savedToday, next.toOffsetDateTime(),
                latest.isEmpty() ? null : latest.getFirst().date(), repository.countSnapshots(userId), reason);
    }

    private static boolean weekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    // ------------------------------------------------------------------ years

    /** The years of a user: the typed rows, the snapshots and the live portfolio. */
    private final class Years {
        private final long userId;
        private final Map<Integer, Year> typed = new TreeMap<>();
        private final int current;
        private final Snapshot live;
        private final Map<Integer, Optional<End>> ends = new TreeMap<>();

        Years(long userId, List<Year> rows, int current, Snapshot live) {
            this.userId = userId;
            rows.forEach(r -> typed.put(r.year(), r));
            this.current = current;
            this.live = live;
        }

        /** The year-end (to-date) figures of a year. */
        Optional<End> end(int year) {
            return ends.computeIfAbsent(year, this::findEnd);
        }

        private Optional<End> findEnd(int year) {
            if (year > current) {
                return Optional.empty();
            }
            if (year == current && live != null) {
                return Optional.of(new End("LIVE", live.date(), live.cash(), live.marketValue(), live.averageValue(),
                        live.ihsgYtdPct(), live.lq45YtdPct()));
            }
            Optional<PerformanceRepository.Snapshot> s = repository.lastSnapshotOfYear(userId, year);
            if (s.isPresent()) {
                PerformanceRepository.Snapshot x = s.get();
                return Optional.of(new End("SNAPSHOT", x.date(), x.cash().doubleValue(),
                        x.marketValue().doubleValue(), x.averageValue().doubleValue(), num(x.ihsgYtdPct()),
                        num(x.lq45YtdPct())));
            }
            Year t = typed.get(year);
            if (t != null && t.marketValue() != null) {
                // a typed row of a past year is its year end; the current year's typed figures are of an unknown day
                return Optional.of(new End("TYPED", year < current ? LocalDate.of(year, 12, 31) : null,
                        t.cash() == null ? 0 : t.cash().doubleValue(), t.marketValue().doubleValue(),
                        num(t.averageValue()), num(t.ihsgYtd()), num(t.lq45Ytd())));
            }
            return Optional.empty();
        }

        /** The capital at the start of a year and where it comes from. */
        Start start(int year) {
            Year t = typed.get(year);
            if (t != null && t.startCapital() != null) {
                return new Start(t.startCapital().doubleValue(), "TYPED");
            }
            Optional<End> before = end(year - 1);
            return before.map(e -> new Start(e.cash() + e.marketValue(), "YEAR_BEFORE")).orElse(new Start(null, null));
        }

        double newInvestment(int year) {
            Year t = typed.get(year);
            return t == null || t.newInvestment() == null ? 0 : t.newInvestment().doubleValue();
        }

        /** POT G/L % of a year (to date for the current year); null when its figures are missing. */
        Double profitPct(int year) {
            Start s = start(year);
            Optional<End> e = end(year);
            if (s.capital() == null || s.capital() <= 0 || e.isEmpty()) {
                return null;
            }
            return (e.get().cash() + e.get().marketValue() - s.capital() - newInvestment(year)) / s.capital();
        }

        YearView view(int year) {
            Year t = typed.get(year);
            String status = year < current ? "CLOSED" : year == current ? "CURRENT" : "FUTURE";
            Start s = start(year);
            Optional<End> e = end(year);
            double invest = newInvestment(year);
            Double cash = e.map(End::cash).orElse(null);
            Double market = e.map(End::marketValue).orElse(null);
            Double total = e.map(x -> x.cash() + x.marketValue()).orElse(null);
            Double profit = total == null || s.capital() == null ? null : total - s.capital() - invest;
            Double profitPct = profit == null || s.capital() <= 0 ? null : profit / s.capital();
            Double dividend = t == null ? null : num(t.dividend());
            Double dividendPct = dividend == null || total == null || total <= 0 ? null : dividend / total;
            LocalDate endDate = e.map(End::date).orElse(null);
            LocalDate indexEnd = status.equals("FUTURE") ? null
                    : endDate != null ? endDate : year < current ? LocalDate.of(year, 12, 31) : null;
            Double ihsg = indexEnd == null ? null : indexChange(IHSG, year, indexEnd);
            Double lq45 = indexEnd == null ? null : indexChange(LQ45, year, indexEnd);
            Double ihsgRecorded = t != null && t.ihsgYtd() != null ? num(t.ihsgYtd()) : e.map(End::ihsgYtd).orElse(null);
            Double lq45Recorded = t != null && t.lq45Ytd() != null ? num(t.lq45Ytd()) : e.map(End::lq45Ytd).orElse(null);
            return new YearView(year, status, t != null, s.capital(), s.from(), invest, cash, market,
                    e.map(End::averageValue).orElse(null), e.map(End::source).orElse(null), endDate, total, profit,
                    profitPct, dividend, dividendPct, ihsg, lq45, ihsgRecorded, lq45Recorded,
                    profitPct == null || ihsg == null ? null : profitPct - ihsg, t == null ? null : t.note(),
                    t == null ? null : new YearRequest(t.startCapital(), t.newInvestment(), t.dividend(), t.cash(),
                            t.marketValue(), t.averageValue(), t.ihsgYtd(), t.lq45Ytd(), t.note()));
        }
    }

    private record End(String source, LocalDate date, double cash, double marketValue, Double averageValue,
                       Double ihsgYtd, Double lq45Ytd) {
    }

    private record Start(Double capital, String from) {
    }

    /** An index's change over a year to {@code end}: its close on or before end / the last close of the year before. */
    private Double indexChange(String index, int year, LocalDate end) {
        Optional<Close> base = repository.closeOnOrBefore(index, LocalDate.of(year - 1, 12, 31));
        Optional<Close> close = repository.closeOnOrBefore(index, end);
        if (base.isEmpty() || close.isEmpty() || close.get().date().getYear() != year) {
            return null;
        }
        return close.get().close().doubleValue() / base.get().close().doubleValue() - 1;
    }

    @org.springframework.transaction.annotation.Transactional
    public Performance saveYear(long userId, int year, YearRequest r, String range) {
        checkYear(year);
        repository.saveYear(userId, new Year(year, r.startCapital(), r.newInvestment(), r.dividend(), r.cash(),
                r.marketValue(), r.averageValue(), r.ihsgYtd(), r.lq45Ytd(),
                r.note() == null || r.note().isBlank() ? null : r.note().trim(), null));
        return performance(userId, range);
    }

    @org.springframework.transaction.annotation.Transactional
    public Performance deleteYear(long userId, int year, String range) {
        checkYear(year);
        if (!repository.deleteYear(userId, year)) {
            throw ApiException.notFound("Year not found", "No row for " + year + " in your performance");
        }
        return performance(userId, range);
    }

    private void checkYear(int year) {
        int current = now().toLocalDate().getYear();
        if (year < 2000 || year > Math.max(lastYear, current) + 1) {
            throw ApiException.badRequest("year: between 2000 and " + (Math.max(lastYear, current) + 1));
        }
    }

    // ------------------------------------------------------------------ chart

    private Chart chart(long userId, String range, int fromYear, int toYear, Years years, Snapshot live,
                        LocalDate today) {
        LocalDate from = LocalDate.of(fromYear, 1, 1);
        LocalDate to = LocalDate.of(toYear, 12, 31);
        LocalDate baseDay = from.minusDays(1);
        Optional<Close> ihsgBase = repository.closeOnOrBefore(IHSG, baseDay);
        Optional<Close> lqBase = repository.closeOnOrBefore(LQ45, baseDay);
        LocalDate base = ihsgBase.map(Close::date).orElse(baseDay);
        List<String> notes = new ArrayList<>();

        TreeMap<LocalDate, double[]> values = new TreeMap<>();   // [portfolio, ihsg, lq45], NaN = none
        TreeMap<LocalDate, String> kinds = new TreeMap<>();
        java.util.function.Function<LocalDate, double[]> at = d -> values.computeIfAbsent(d,
                k -> new double[] {Double.NaN, Double.NaN, Double.NaN});

        double[] start = at.apply(base);
        start[1] = ihsgBase.isPresent() ? 0 : Double.NaN;
        start[2] = lqBase.isPresent() ? 0 : Double.NaN;
        LocalDate end = to.isBefore(today) ? to : today;
        Double ihsgLast = index(values, at, IHSG, ihsgBase, from, end, 1);
        Double lqLast = index(values, at, LQ45, lqBase, from, end, 2);
        if (ihsgBase.isEmpty()) {
            notes.add("No IHSG close stored before " + from + ".");
        }
        if (lqBase.isEmpty()) {
            notes.add("No LQ45 close stored before " + from + ".");
        }

        // the portfolio: each year's POT G/L % chained; within a year its snapshots (and today's live value)
        // (no 0% start point when the range's first year has no start capital: nothing to draw from it)
        Start first = years.start(fromYear);
        if (first.capital() != null && first.capital() > 0 && fromYear <= today.getYear()) {
            start[0] = 0;
            kinds.put(base, "BASE");
        }
        double factor = 1;
        Double portfolioLast = null;
        LocalDate latest = null;
        for (int y = fromYear; y <= toYear && y <= today.getYear(); y++) {
            Start st = years.start(y);
            if (st.capital() == null || st.capital() <= 0) {
                notes.add("The portfolio's line stops at " + (y - 1) + ": no start capital for " + y
                        + " (type the year's row, or the market value and cash of " + (y - 1) + ").");
                break;
            }
            double capital = st.capital();
            double invest = years.newInvestment(y);
            boolean drawn = false;
            for (PerformanceRepository.Snapshot x : repository.snapshots(userId, LocalDate.of(y, 1, 1),
                    LocalDate.of(y, 12, 31))) {
                if (y == today.getYear() && live != null && x.date().equals(today)) {
                    continue;   // the live value is today's latest
                }
                double ytd = (x.cash().doubleValue() + x.marketValue().doubleValue() - capital - invest) / capital;
                at.apply(x.date())[0] = factor * (1 + ytd) - 1;
                kinds.put(x.date(), "SNAPSHOT");
                drawn = true;
                latest = x.date();
            }
            if (y == today.getYear() && live != null) {
                double ytd = (live.cash() + live.marketValue() - capital - invest) / capital;
                at.apply(today)[0] = factor * (1 + ytd) - 1;
                kinds.put(today, "LIVE");
                drawn = true;
                latest = today;
            }
            Double r = years.profitPct(y);
            if (y < today.getYear()) {
                if (r == null) {
                    notes.add("The portfolio's line stops at " + (y - 1) + ": no year-end market value for " + y
                            + ".");
                    break;
                }
                if (!drawn || !years.end(y).map(e -> "SNAPSHOT".equals(e.source())).orElse(false)) {
                    // a typed year end: a point on 31 December
                    LocalDate yearEnd = LocalDate.of(y, 12, 31);
                    at.apply(yearEnd)[0] = factor * (1 + r) - 1;
                    kinds.put(yearEnd, "YEAR_END");
                    latest = yearEnd;
                }
                factor *= 1 + r;
                portfolioLast = factor - 1;
            } else if (drawn && latest != null) {
                portfolioLast = values.get(latest)[0];
            } else {
                portfolioLast = factor - 1;
                notes.add("No snapshot of " + y + " yet: the first is saved after the next session (17:00 WIB).");
            }
        }

        List<Point> points = new ArrayList<>();
        values.forEach((d, v) -> points.add(new Point(d, nan(v[0]), nan(v[1]), nan(v[2]),
                Double.isNaN(v[0]) ? null : kinds.get(d))));
        return new Chart(range, from, to, base, points, portfolioLast, ihsgLast, lqLast, latest, notes);
    }

    /** The index's change since its base close, day by day; the last change (null without a base). */
    private Double index(TreeMap<LocalDate, double[]> values, java.util.function.Function<LocalDate, double[]> at,
                         String code, Optional<Close> base, LocalDate from, LocalDate to, int slot) {
        if (base.isEmpty() || to.isBefore(from)) {
            return base.isEmpty() ? null : 0.0;
        }
        double b = base.get().close().doubleValue();
        Double last = 0.0;
        for (Close c : repository.closes(code, from, to)) {
            double change = c.close().doubleValue() / b - 1;
            at.apply(c.date())[slot] = change;
            last = change;
        }
        return last;
    }

    private static Double nan(double v) {
        return Double.isNaN(v) ? null : v;
    }

    // ------------------------------------------------------------------ snapshots

    /** What a capture did. */
    public enum Outcome { SAVED, NOT_YET, WEEKEND, NO_SESSION, BUSY }

    public record Capture(Outcome outcome, Snapshot snapshot, String message) {
    }

    /** "Save today": today's snapshot now (at or after 17:00 WIB on a trading day), replacing one saved earlier. */
    public Saved saveToday(long userId, String range) {
        Capture c = capture(userId, "MANUAL", true);
        if (c.outcome() != Outcome.SAVED) {
            throw ApiException.conflict(c.message());
        }
        return new Saved(c.snapshot(), c.message(), performance(userId, range));
    }

    /** The schedule's round for one user: today's snapshot unless it is saved already. */
    public Capture captureScheduled(long userId) {
        if (repository.hasSnapshot(userId, now().toLocalDate())) {
            return null;
        }
        return capture(userId, "SCHEDULE", false);
    }

    /**
     * Saves today's snapshot of a user: at or after {@code save-from} on a weekday that IDX traded (an IHSG bar of the
     * day, after the index ingestion, or an intraday session of the day). The index bars and the stocks' prices are
     * read first, so the snapshot holds the session's closes.
     */
    Capture capture(long userId, String source, boolean replace) {
        ZonedDateTime now = now();
        LocalDate today = now.toLocalDate();
        if (!weekday(today)) {
            return new Capture(Outcome.WEEKEND, null, "IDX does not trade on weekends: nothing is saved today.");
        }
        if (now.toLocalTime().isBefore(saveFrom)) {
            return new Capture(Outcome.NOT_YET, null, "Today's snapshot can be saved from " + saveFrom
                    + " WIB, after the session closes.");
        }
        if (!saving.tryLock()) {
            return new Capture(Outcome.BUSY, null, "A snapshot is being saved right now: try again in a moment.");
        }
        try {
            if (!replace && repository.hasSnapshot(userId, today)) {
                return null;
            }
            // today's IHSG / LQ45 bars (final after the session close cutoff, 17:00)
            try {
                indexes.index(Exchange.of("IDX")).ifPresent(ref -> indexes.ingest(ref, false));
            } catch (RuntimeException e) {
                log.warn("Performance snapshot: the IHSG / LQ45 ingestion failed: {}", e.toString());
            }
            boolean traded = repository.closeOnOrBefore(IHSG, today).map(c -> c.date().equals(today)).orElse(false)
                    || repository.intradaySessionOf(userId, today);
            if (!traded) {
                return new Capture(Outcome.NO_SESSION, null, "No IDX session today (no IHSG close and no intraday "
                        + "price of " + today + "): an exchange holiday, or the closes are not published yet.");
            }
            // the stocks' closing prices of the session
            try {
                intraday.refreshUser(userId);
            } catch (RuntimeException e) {
                log.warn("Performance snapshot of user {}: the intraday refresh failed: {}", userId, e.toString());
            }
            Portfolio p = portfolios.portfolio(userId);
            Snapshot s = compute(userId, p, today, source, now.toOffsetDateTime());
            repository.saveSnapshot(userId, record(s));
            String message = "Saved the snapshot of " + today + ": market value " + rupiah(s.marketValue())
                    + ", " + s.pricedToday() + " of " + s.positions() + " stocks priced at today's session.";
            log.info("Performance snapshot of user {} ({}): {}", userId, source, message);
            return new Capture(Outcome.SAVED, view(repository.latestSnapshots(userId, 1).getFirst()), message);
        } finally {
            saving.unlock();
        }
    }

    /** A snapshot of the portfolio as it stands, with the year's start figures and IHSG / LQ45 of the day. */
    Snapshot compute(long userId, Portfolio p, LocalDate today, String source, OffsetDateTime at) {
        var sum = p.summary();
        int pricedToday = (int) p.positions().stream().map(Position::priceDate)
                .filter(d -> d != null && d.equals(today)).count();
        Years years = new Years(userId, repository.years(userId), today.getYear(), null);
        Start start = years.start(today.getYear());
        double invest = years.newInvestment(today.getYear());
        double total = sum.marketTotal() + sum.cash();
        Double ytd = start.capital() == null ? null : total - start.capital() - invest;
        Double ytdPct = ytd == null || start.capital() <= 0 ? null : ytd / start.capital();
        IndexDay ihsg = indexDay(IHSG, today);
        IndexDay lq45 = indexDay(LQ45, today);
        return new Snapshot(today, at, source, sum.positions(), sum.priced(), pricedToday, sum.cash(),
                sum.marketTotal(), sum.averageTotal(), total, sum.profitMarket(), sum.profitMarketPct(),
                sum.dayGain(), sum.dayGainPct(), sum.totalInvestment(), start.capital(), invest, ytd, ytdPct,
                ihsg.close(), ihsg.date(), ihsg.change(), ihsg.ytd(), lq45.close(), lq45.date(), lq45.change(),
                lq45.ytd());
    }

    private record IndexDay(Double close, LocalDate date, Double change, Double ytd) {
    }

    /** An index on {@code day} (its latest close on or before it): the change of the day and of the year. */
    private IndexDay indexDay(String code, LocalDate day) {
        Optional<Close> c = repository.closeOnOrBefore(code, day);
        if (c.isEmpty()) {
            return new IndexDay(null, null, null, null);
        }
        double close = c.get().close().doubleValue();
        Double change = repository.closeBefore(code, c.get().date())
                .map(b -> close / b.close().doubleValue() - 1).orElse(null);
        Double ytd = repository.closeOnOrBefore(code, LocalDate.of(c.get().date().getYear() - 1, 12, 31))
                .map(b -> close / b.close().doubleValue() - 1).orElse(null);
        return new IndexDay(close, c.get().date(), change, ytd);
    }

    private static PerformanceRepository.Snapshot record(Snapshot s) {
        return new PerformanceRepository.Snapshot(s.date(), s.capturedAt().toInstant(), s.source(), s.positions(),
                s.priced(), s.pricedToday(), money(s.cash()), money(s.marketValue()), money(s.averageValue()),
                money(s.profitLoss()), ratio(s.profitLossPct()), money(s.dayGain()), ratio(s.dayGainPct()),
                money(s.totalInvestment()), money(s.yearStartCapital()), money(s.yearNewInvestment()),
                money(s.ytdProfit()), ratio(s.ytdProfitPct()), index(s.ihsgClose()), s.ihsgDate(),
                ratio(s.ihsgChangePct()), ratio(s.ihsgYtdPct()), index(s.lq45Close()), s.lq45Date(),
                ratio(s.lq45ChangePct()), ratio(s.lq45YtdPct()));
    }

    static Snapshot view(PerformanceRepository.Snapshot s) {
        double cash = s.cash().doubleValue();
        double market = s.marketValue().doubleValue();
        return new Snapshot(s.date(), OffsetDateTime.ofInstant(s.capturedAt(), IdxSession.ZONE), s.source(),
                s.positions(), s.priced(), s.pricedToday(), cash, market, s.averageValue().doubleValue(),
                cash + market, s.profitLoss().doubleValue(), num(s.profitLossPct()), num(s.dayGain()),
                num(s.dayGainPct()), s.totalInvestment().doubleValue(), num(s.yearStartCapital()),
                s.yearNewInvestment() == null ? 0 : s.yearNewInvestment().doubleValue(), num(s.ytdProfit()),
                num(s.ytdProfitPct()), num(s.ihsgClose()), s.ihsgDate(), num(s.ihsgChangePct()), num(s.ihsgYtdPct()),
                num(s.lq45Close()), s.lq45Date(), num(s.lq45ChangePct()), num(s.lq45YtdPct()));
    }

    private static BigDecimal money(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(Double v) {
        return v == null || v.isNaN() || v.isInfinite() ? null : BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal index(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }

    private static Double num(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private static String rupiah(double v) {
        return String.format(java.util.Locale.US, "Rp %,.0f", v);
    }

    /** The users with a portfolio: the schedule's round. */
    public List<Long> portfolioUsers() {
        return repository.portfolioUsers();
    }
}
