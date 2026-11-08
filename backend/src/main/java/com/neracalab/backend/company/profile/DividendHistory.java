package com.neracalab.backend.company.profile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

import com.neracalab.backend.company.profile.CompanyProfileViews.DividendRow;
import com.neracalab.backend.company.profile.CompanyProfileViews.DividendSummary;
import com.neracalab.backend.company.profile.CompanyProfileViews.DividendYear;
import com.neracalab.backend.company.profile.IdxProfileParser.Dividend;

/**
 * The dividend table of a company from what is stored: IDX's dividends ({@code company_dividend}), Yahoo's
 * ({@code corporate_event}), the daily closes, Yahoo's splits, the FX rates and the fiscal years' statements.
 * No model, no estimate: a value that cannot be derived is null.
 * <p>
 * Share basis. Yahoo stores prices and dividends split-adjusted (today's basis); IDX announces dividends on the basis
 * of their date. Every row is therefore shown on the basis of its own date: Yahoo's amount and the close times the
 * product of the splits after the date ({@code splitFactor}), so a dividend's yield is the same before and after a
 * later split, and an IDX amount can be set against the close directly.
 * <p>
 * Currencies. The closes of a company that reports in another currency than its listing trades in are stored converted
 * (USD); they are converted back at the rate the price ingestion used (last FX day before the trading day, at most 7
 * days before). A dividend declared in another currency than the listing's is converted at the same rate.
 */
public final class DividendHistory {

    private static final MathContext MC = MathContext.DECIMAL64;
    /** IDX and Yahoo know the same dividend when their ex dates are at most this many days apart. */
    static final int MATCH_DAYS = 3;
    /** A rate older than this many days before the day is not used. */
    static final int RATE_DAYS = 7;

    /** A Yahoo dividend: ex date and amount per share (today's basis, listing currency). */
    public record YahooDividend(LocalDate exDate, BigDecimal perShare) {
    }

    /** A Yahoo split: effective date and new shares per old share. */
    public record Split(LocalDate date, BigDecimal ratio) {
    }

    /** A fiscal year of the statements. */
    public record FiscalYear(int fiscalYear, LocalDate periodEnd, BigDecimal netIncomeToParent, BigDecimal basicEps,
                             BigDecimal dividendsPaid) {
    }

    /** An FX rate: {@code quote} per one {@code base}. */
    public record Rate(String base, String quote, LocalDate date, BigDecimal rate) {
    }

    /**
     * @param currency        reporting currency (statements; the stored closes)
     * @param listingCurrency currency the shares trade in
     * @param closes          stored closes by trading day (split-adjusted, in {@code currency})
     * @param epsTtm          trailing EPS of the latest valuation snapshot (reporting currency, today's basis); null
     * @param epsDate         its date
     */
    public record Input(String currency, String listingCurrency, NavigableMap<LocalDate, BigDecimal> closes,
                        List<Split> splits, List<YahooDividend> yahoo, List<Dividend> idx, List<FiscalYear> years,
                        List<Rate> rates, BigDecimal epsTtm, LocalDate epsDate) {

        /** Without a trailing EPS. */
        public Input(String currency, String listingCurrency, NavigableMap<LocalDate, BigDecimal> closes,
                     List<Split> splits, List<YahooDividend> yahoo, List<Dividend> idx, List<FiscalYear> years,
                     List<Rate> rates) {
            this(currency, listingCurrency, closes, splits, yahoo, idx, years, rates, null, null);
        }
    }

    public record Result(DividendSummary summary, List<DividendRow> rows, List<DividendYear> years) {
    }

    private final Input in;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> rates = new HashMap<>();

    private DividendHistory(Input in) {
        this.in = in;
        for (Rate r : in.rates()) {
            rates.computeIfAbsent(r.base() + r.quote(), k -> new TreeMap<>()).put(r.date(), r.rate());
        }
    }

    public static Result of(Input in) {
        return new DividendHistory(in).compute();
    }

    private Result compute() {
        List<DividendRow> rows = rows();
        List<DividendYear> years = years();
        return new Result(summary(rows, years), rows, years);
    }

    // ------------------------------------------------------------------ rows

    private List<DividendRow> rows() {
        List<YahooDividend> unmatched = new ArrayList<>(in.yahoo());
        List<DividendRow> rows = new ArrayList<>();
        for (Dividend d : in.idx()) {
            LocalDate ex = d.exDate() != null ? d.exDate() : d.cumDate() != null ? d.cumDate().plusDays(1) : null;
            YahooDividend match = null;
            if (ex != null) {
                match = unmatched.stream()
                        .filter(y -> Math.abs(y.exDate().toEpochDay() - ex.toEpochDay()) <= MATCH_DAYS)
                        .min(Comparator.comparingLong(y -> Math.abs(y.exDate().toEpochDay() - ex.toEpochDay())))
                        .orElse(null);
            }
            if (match != null) {
                unmatched.remove(match);
            }
            // the close of the cum date (the last day with the right), else of the last day before the ex date
            LocalDate priceDay = d.cumDate() != null ? floorDay(d.cumDate()) : ex != null ? floorDay(ex.minusDays(1))
                    : d.paymentDate() != null ? floorDay(d.paymentDate()) : null;
            BigDecimal price = listingPrice(priceDay);
            BigDecimal perShareListing = convert(d.cashPerShare(), d.currency() == null ? in.listingCurrency()
                    : d.currency(), in.listingCurrency(), priceDay);
            BigDecimal factor = ex == null ? BigDecimal.ONE : splitFactorAfter(ex.minusDays(1));
            rows.add(new DividendRow(d.exDate(), d.cumDate(), d.recordingDate(), d.paymentDate(), d.fiscalYear(),
                    d.type(), match == null ? "IDX" : "IDX_YAHOO", d.cashPerShare(),
                    d.currency() == null ? in.listingCurrency() : d.currency(),
                    match == null ? null : match.perShare(), factor, priceDay, round(price, 4),
                    round(perShareListing, 4), yieldOf(perShareListing, price), d.totalAmount(), d.totalCurrency()));
        }
        for (YahooDividend y : unmatched) {
            LocalDate priceDay = floorDay(y.exDate().minusDays(1));
            BigDecimal price = listingPrice(priceDay);
            BigDecimal factor = splitFactorAfter(y.exDate().minusDays(1));
            BigDecimal perShare = y.perShare().multiply(factor, MC);
            rows.add(new DividendRow(y.exDate(), null, null, null, null, null, "YAHOO", round(perShare, 4),
                    in.listingCurrency(), y.perShare(), factor, priceDay, round(price, 4), round(perShare, 4),
                    yieldOf(perShare, price), null, null));
        }
        rows.sort(Comparator.comparing(DividendHistory::rowDate, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    private static LocalDate rowDate(DividendRow r) {
        return r.exDate() != null ? r.exDate() : r.cumDate() != null ? r.cumDate() : r.paymentDate();
    }

    /** The trading day of the last stored close on or before {@code day}. */
    private LocalDate floorDay(LocalDate day) {
        Map.Entry<LocalDate, BigDecimal> e = in.closes().floorEntry(day);
        // a close more than two weeks before the day is not the cum-date close (a suspension, missing history)
        return e == null || e.getKey().isBefore(day.minusDays(14)) ? null : e.getKey();
    }

    /** The close of a trading day in the listing currency, on the share basis of that day. */
    private BigDecimal listingPrice(LocalDate day) {
        if (day == null) {
            return null;
        }
        BigDecimal close = in.closes().get(day);
        if (close == null || close.signum() <= 0) {
            return null;
        }
        BigDecimal quoted = convert(close, in.currency(), in.listingCurrency(), day);
        return quoted == null ? null : quoted.multiply(splitFactorAfter(day), MC);
    }

    /** New shares per old share of the splits effective after {@code day}. */
    BigDecimal splitFactorAfter(LocalDate day) {
        BigDecimal f = BigDecimal.ONE;
        for (Split s : in.splits()) {
            if (s.date().isAfter(day) && s.ratio() != null && s.ratio().signum() > 0) {
                f = f.multiply(s.ratio(), MC);
            }
        }
        return f.stripTrailingZeros();
    }

    /**
     * {@code amount} from one currency into another at the last rate before {@code day} (at most {@link #RATE_DAYS}
     * days before; the price ingestion's rule); either direction of the pair. Null without a rate.
     */
    BigDecimal convert(BigDecimal amount, String from, String to, LocalDate day) {
        if (amount == null) {
            return null;
        }
        if (from == null || to == null || from.equals(to)) {
            return amount;
        }
        if (day == null) {
            return null;
        }
        BigDecimal direct = rate(from + to, day);
        if (direct != null) {
            return amount.multiply(direct, MC);
        }
        BigDecimal inverse = rate(to + from, day);
        return inverse == null || inverse.signum() == 0 ? null : amount.divide(inverse, MC);
    }

    private BigDecimal rate(String pair, LocalDate day) {
        NavigableMap<LocalDate, BigDecimal> series = rates.get(pair);
        if (series == null) {
            return null;
        }
        Map.Entry<LocalDate, BigDecimal> e = series.lowerEntry(day);
        return e == null || e.getKey().isBefore(day.minusDays(RATE_DAYS)) ? null : e.getValue();
    }

    // ------------------------------------------------------------------ summary

    private DividendSummary summary(List<DividendRow> rows, List<DividendYear> years) {
        Map.Entry<LocalDate, BigDecimal> last = in.closes().lastEntry();
        LocalDate priceDate = last == null ? null : last.getKey();
        BigDecimal price = priceDate == null ? null : listingPrice(priceDate);
        BigDecimal trailing = null;
        int count = 0;
        if (priceDate != null) {
            LocalDate from = priceDate.minusYears(1);
            for (DividendRow r : rows) {
                LocalDate ex = rowDate(r);
                if (ex == null || !ex.isAfter(from) || ex.isAfter(priceDate) || r.perShareListing() == null) {
                    continue;
                }
                // onto today's basis, as the last close
                BigDecimal today = r.perShareListing().divide(r.splitFactor(), MC);
                trailing = trailing == null ? today : trailing.add(today, MC);
                count++;
            }
        }
        DividendYear latest = years.stream().filter(y -> y.cashPayout() != null).findFirst().orElse(null);
        List<BigDecimal> recent = years.stream().filter(y -> y.cashPayout() != null).limit(5)
                .map(DividendYear::cashPayout).toList();
        BigDecimal average = recent.isEmpty() ? null
                : recent.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(recent.size()), MC);
        int idx = (int) rows.stream().filter(r -> r.source().startsWith("IDX")).count();
        int yahoo = (int) rows.stream().filter(r -> r.source().endsWith("YAHOO")).count();
        // the trailing EPS in the listing currency (a USD reporter's at the rate before the snapshot's date)
        BigDecimal eps = in.epsTtm() == null ? null
                : convert(in.epsTtm(), in.currency(), in.listingCurrency(),
                        in.epsDate() == null ? priceDate : in.epsDate().plusDays(1));
        BigDecimal payout = eps == null || eps.signum() <= 0 || priceDate == null ? null
                : round((trailing == null ? BigDecimal.ZERO : trailing).divide(eps, MC), 6);
        return new DividendSummary(round(trailing, 4), count, priceDate, round(price, 4),
                trailing == null ? (priceDate == null ? null : price == null ? null : BigDecimal.ZERO)
                        : yieldOf(trailing, price),
                latest == null ? null : latest.cashPayout(), latest == null ? null : latest.fiscalYear(),
                round(average, 6), recent.size(), idx, yahoo, round(eps, 4), in.epsDate(), payout);
    }

    // ------------------------------------------------------------------ fiscal years

    private List<DividendYear> years() {
        Map<Integer, List<Dividend>> declared = new LinkedHashMap<>();
        for (Dividend d : in.idx()) {
            if (d.fiscalYear() != null) {
                declared.computeIfAbsent(d.fiscalYear(), k -> new ArrayList<>()).add(d);
            }
        }
        List<DividendYear> out = new ArrayList<>();
        for (FiscalYear y : in.years().stream().sorted(Comparator.comparingInt(FiscalYear::fiscalYear).reversed()).toList()) {
            BigDecimal ni = y.netIncomeToParent();
            BigDecimal paid = y.dividendsPaid() == null ? null : y.dividendsPaid().abs();
            BigDecimal cashPayout = paid == null || ni == null || ni.signum() <= 0 ? null : round(paid.divide(ni, MC), 6);

            List<Dividend> ds = declared.getOrDefault(y.fiscalYear(), List.of());
            BigDecimal perShare = null;
            String perShareCurrency = null;
            BigDecimal total = null;
            BigDecimal payout = null;
            if (!ds.isEmpty()) {
                String currency = ds.get(0).currency();
                boolean oneCurrency = ds.stream().allMatch(d -> Objects.equals(d.currency(), currency));
                if (oneCurrency) {
                    perShare = ds.stream().map(Dividend::cashPerShare).reduce(BigDecimal.ZERO, BigDecimal::add);
                    perShareCurrency = currency == null ? in.listingCurrency() : currency;
                }
                total = BigDecimal.ZERO;
                for (Dividend d : ds) {
                    LocalDate day = d.exDate() != null ? d.exDate() : d.paymentDate() != null ? d.paymentDate()
                            : d.cumDate();
                    BigDecimal t = d.totalAmount() == null ? null : convert(d.totalAmount(),
                            d.totalCurrency() == null ? d.currency() : d.totalCurrency(), in.currency(),
                            day == null ? null : day.plusDays(1));
                    if (t == null) {
                        total = null;
                        break;
                    }
                    total = total.add(t, MC);
                }
                if (total != null && ni != null && ni.signum() > 0) {
                    payout = round(total.divide(ni, MC), 6);
                } else if (perShare != null && y.basicEps() != null && y.basicEps().signum() > 0
                        && Objects.equals(perShareCurrency, in.currency())) {
                    // the EPS is stored on today's share basis: the announced amounts onto it too
                    BigDecimal today = BigDecimal.ZERO;
                    for (Dividend d : ds) {
                        LocalDate ex = d.exDate() != null ? d.exDate() : d.cumDate() != null ? d.cumDate().plusDays(1)
                                : d.paymentDate();
                        BigDecimal f = ex == null ? BigDecimal.ONE : splitFactorAfter(ex.minusDays(1));
                        today = today.add(d.cashPerShare().divide(f, MC), MC);
                    }
                    payout = round(today.divide(y.basicEps(), MC), 6);
                }
            }
            out.add(new DividendYear(y.fiscalYear(), y.periodEnd(), ni, y.basicEps(), paid, cashPayout,
                    perShare == null ? null : perShare.stripTrailingZeros(), perShareCurrency,
                    total == null ? null : round(total, 2), payout, ds.size()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal yieldOf(BigDecimal perShare, BigDecimal price) {
        return perShare == null || price == null || price.signum() <= 0 ? null : round(perShare.divide(price, MC), 6);
    }

    private static BigDecimal round(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
