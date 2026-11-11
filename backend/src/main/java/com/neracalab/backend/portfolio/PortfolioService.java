package com.neracalab.backend.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.company.Tickers;
import com.neracalab.backend.company.profile.CompanyProfileService;
import com.neracalab.backend.company.profile.CompanyProfileViews.DividendRow;
import com.neracalab.backend.portfolio.PortfolioRepository.Close;
import com.neracalab.backend.portfolio.PortfolioRepository.Fundamentals;
import com.neracalab.backend.portfolio.PortfolioRepository.Settings;
import com.neracalab.backend.portfolio.PortfolioViews.Intraday;
import com.neracalab.backend.portfolio.PortfolioViews.Live;
import com.neracalab.backend.portfolio.intraday.IdxSession;
import com.neracalab.backend.portfolio.intraday.IntradayRepository;
import com.neracalab.backend.portfolio.intraday.IntradayService;
import com.neracalab.backend.portfolio.PortfolioViews.Portfolio;
import com.neracalab.backend.portfolio.PortfolioViews.Position;
import com.neracalab.backend.portfolio.PortfolioViews.PositionRequest;
import com.neracalab.backend.portfolio.PortfolioViews.PositionUpdate;
import com.neracalab.backend.portfolio.PortfolioViews.SettingsRequest;
import com.neracalab.backend.portfolio.PortfolioViews.Slice;
import com.neracalab.backend.portfolio.PortfolioViews.Summary;
import com.neracalab.backend.web.ApiException;

/**
 * Portfolio &gt; Position of the signed-in user: the sheet Position of {@code instruction/Position Portfolio.xlsx}. The
 * user keeps the average price, the shares and the date of the first buy of each stock, the cash and the total
 * investment; every other column comes from Companies &gt; Fundamentals on request (nothing is copied):
 * <ul>
 *   <li>the last two closes (Market &amp; shares);</li>
 *   <li>the latest filing's key metrics (Metrics tab, {@code v_key_metrics}): BVPS, the forward EPS of the Valuation
 *       tab (profit to the parent made a year / shares), debt to equity, EBITDA and operating profit made a year;</li>
 *   <li>its balance sheet: total liabilities (the sheet's "Total Debt") and cash;</li>
 *   <li>the share count of the latest market snapshot;</li>
 *   <li>the cash dividends of the Profile tab with an ex date this year, else last year.</li>
 * </ul>
 * The sheet's formulas: market cap = price x shares; EV = total debt + market cap - cash; EV/EBITDA, EV/OP, PER =
 * price / EPS, PBV = price / BVPS, dividend yield = dividend / price. A company reporting in USD has its figures
 * converted into IDR as the Fundamentals' USD | IDR switch does: the balance sheet and BVPS at the period-end rate,
 * EBITDA, OP and EPS at the period's average rate.
 */
@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);
    private static final double BILLION = 1e9;
    /** The exchange's day: "this year" of the dividends. */
    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");

    private final PortfolioRepository repository;
    private final CompanyProfileService profiles;
    private final IntradayRepository intraday;
    private final IntradayService intradayService;

    public PortfolioService(PortfolioRepository repository, CompanyProfileService profiles, IntradayRepository intraday,
                            IntradayService intradayService) {
        this.repository = repository;
        this.profiles = profiles;
        this.intraday = intraday;
        this.intradayService = intradayService;
    }

    // ------------------------------------------------------------------ read

    public Portfolio portfolio(long userId) {
        Settings settings = repository.settings(userId);
        double cash = settings.cash().doubleValue();
        List<Row> rows = new ArrayList<>();
        for (PortfolioRepository.Position p : repository.positions(userId)) {
            rows.add(row(p));
        }

        double averageTotal = 0;
        double marketTotal = 0;
        int priced = 0;
        double dayGain = 0;
        double dayBase = 0;
        int dayCounted = 0;
        for (Row r : rows) {
            averageTotal += r.averageValue;
            if (r.marketValue != null) {
                marketTotal += r.marketValue;
                priced++;
            }
            if (r.change != null && r.lastPrice != null) {
                double shares = r.p.shares().doubleValue();
                dayGain += r.change * shares;
                dayBase += (r.lastPrice - r.change) * shares;
                dayCounted++;
            }
        }
        double investment = settings.totalInvestment().doubleValue();
        double profitMarket = marketTotal - averageTotal;
        double totalProfit = marketTotal + cash - investment;
        Summary summary = new Summary(cash, investment, averageTotal, marketTotal, profitMarket,
                averageTotal > 0 ? profitMarket / averageTotal : null, totalProfit,
                investment > 0 ? totalProfit / investment : null, marketTotal + cash, rows.size(), priced,
                dayCounted == 0 ? null : dayGain, dayBase > 0 ? dayGain / dayBase : null);

        List<Position> positions = new ArrayList<>();
        for (Row r : rows) {
            positions.add(r.view(averageTotal + cash, marketTotal + cash));
        }

        List<String> notes = new ArrayList<>();
        if (priced < rows.size()) {
            notes.add((rows.size() - priced) + " position(s) without a stored price are left out of the market total.");
        }
        return new Portfolio("IDR", OffsetDateTime.now(ZoneOffset.UTC), summary, positions,
                allocation(positions, cash), live(positions), notes);
    }

    /** The IDX phase now and how fresh the portfolio's intraday prices are. */
    private Live live(List<Position> positions) {
        IdxSession.Phase phase = intradayService.phase();
        LocalDate session = null;
        OffsetDateTime updated = null;
        for (Position p : positions) {
            Intraday i = p.intraday();
            if (i == null) {
                continue;
            }
            if (session == null || i.sessionDate().isAfter(session)) {
                session = i.sessionDate();
            }
            if (updated == null || i.fetchedAt().isAfter(updated)) {
                updated = i.fetchedAt();
            }
        }
        return new Live(phase.name(), phase.label(), phase.trading(), session, updated,
                intradayService.everyMinutes(), intradayService.scheduled());
    }

    /** The pie: each sector's market value and the cash, largest first, the cash last. */
    static List<Slice> allocation(List<Position> positions, double cash) {
        Map<String, double[]> values = new TreeMap<>();
        Map<String, List<String>> tickers = new LinkedHashMap<>();
        for (Position p : positions) {
            if (p.marketValue() == null || p.marketValue() <= 0) {
                continue;
            }
            String sector = p.sector() == null || p.sector().isBlank() ? "Unclassified" : p.sector();
            values.computeIfAbsent(sector, k -> new double[1])[0] += p.marketValue();
            tickers.computeIfAbsent(sector, k -> new ArrayList<>()).add(p.ticker());
        }
        double total = cash + values.values().stream().mapToDouble(v -> v[0]).sum();
        List<Slice> out = new ArrayList<>();
        values.forEach((sector, v) -> out.add(new Slice(sector, false, v[0], total > 0 ? v[0] / total : null,
                List.copyOf(tickers.get(sector)))));
        out.sort(Comparator.comparingDouble(Slice::value).reversed().thenComparing(Slice::name));
        if (cash > 0) {
            out.add(new Slice("Cash", true, cash, total > 0 ? cash / total : null, List.of()));
        }
        return out;
    }

    /** A position with what it is shown with, before the portfolio totals are known. */
    private record Row(PortfolioRepository.Position p, String companyName, String sector, Double lastPrice,
                       Double change, Double changePct, LocalDate priceDate, String priceSource,
                       OffsetDateTime priceTime, Intraday intraday, double averageValue, Double marketValue,
                       Double bvps, Double eps, Double debtToEquity, Double dividend, Integer dividendYear,
                       String lastFiling, LocalDate periodEnd, Double ebitda, Double shares, Double totalDebt,
                       Double cash, Double op, String convertedFrom, List<String> notes) {

        Position view(double averageBase, double marketBase) {
            Double marketCap = lastPrice == null || shares == null ? null : lastPrice * shares;
            Double ev = marketCap == null || totalDebt == null || cash == null ? null : totalDebt + marketCap - cash;
            Double gainLoss = marketValue == null ? null : marketValue - averageValue;
            return new Position(p.positionId(), p.exchange(), p.ticker(), companyName, sector, lastPrice, change,
                    changePct, priceDate, priceSource, priceTime, intraday,
                    change == null ? null : change * p.shares().doubleValue(), p.averagePrice().doubleValue(), p.shares().doubleValue(), averageValue,
                    marketValue, gainLoss, gainLoss == null || averageValue == 0 ? null : gainLoss / averageValue,
                    averageBase > 0 ? averageValue / averageBase : null,
                    marketValue == null || marketBase <= 0 ? null : marketValue / marketBase,
                    bvps, eps, debtToEquity, ratio(ev, ebitda), ratio(lastPrice, eps), ratio(lastPrice, bvps),
                    dividend, dividendYear, dividend == null ? null : ratio(dividend, lastPrice), lastFiling,
                    periodEnd, ebitda, shares, marketCap, totalDebt, cash, ev, op, ratio(ev, op), p.dateIn(),
                    convertedFrom, notes);
        }
    }

    private Row row(PortfolioRepository.Position p) {
        String listing = Exchange.of(p.exchange()).currency();
        List<String> notes = new ArrayList<>();

        List<Close> closes = repository.closes(p.companyId(), listing, 2);
        Double last = closes.isEmpty() ? null : closes.get(0).price().doubleValue();
        Double before = closes.size() < 2 ? null : closes.get(1).price().doubleValue();
        LocalDate priceDate = closes.isEmpty() ? null : closes.get(0).date();
        String priceSource = last == null ? null : "DAILY";
        OffsetDateTime priceTime = null;

        // the intraday price of a session newer than the stored daily close is the last price (as the sheet's
        // GOOGLEFINANCE price); its change is against the close of the session before
        Intraday day = intraday(p.companyId());
        if (day != null && (priceDate == null || day.sessionDate().isAfter(priceDate))) {
            IntradayRepository.Quote q = intraday.quote(p.companyId()).orElseThrow();
            last = q.price().doubleValue();
            before = q.previousClose() != null ? q.previousClose().doubleValue()
                    : closes.isEmpty() ? null : closes.get(0).price().doubleValue();
            priceDate = q.sessionDate();
            priceSource = "INTRADAY";
            priceTime = OffsetDateTime.ofInstant(q.marketTime(), JAKARTA);
        }
        Double change = last == null || before == null ? null : last - before;
        if (last == null) {
            notes.add("No stored price: run the price ingestion.");
        }
        double averageValue = p.averagePrice().doubleValue() * p.shares().doubleValue();
        Double marketValue = last == null ? null : last * p.shares().doubleValue();

        Fundamentals f = repository.fundamentals(p.companyId(), listing).orElse(null);
        String companyName = f == null ? p.ticker() : f.companyName();
        String sector = f == null ? null : f.sector();
        Double bvps = null, eps = null, de = null, ebitda = null, shares = null, debt = null, cash = null, op = null;
        String lastFiling = null;
        LocalDate periodEnd = null;
        String convertedFrom = null;
        if (f != null && f.periodEnd() != null) {
            lastFiling = f.periodType() + " " + f.fiscalYear();
            periodEnd = f.periodEnd();
            // a company reporting in another currency than its listing (USD): balance sheet at the period-end rate,
            // flows at the period's average rate (as the Fundamentals' USD | IDR switch)
            double closing = 1;
            double average = 1;
            boolean foreign = f.currency() != null && !f.currency().equalsIgnoreCase(listing);
            if (foreign) {
                convertedFrom = f.currency().toUpperCase(java.util.Locale.ROOT);
                if (f.closingRate() == null || f.averageRate() == null) {
                    notes.add("Reports in " + convertedFrom + " and no " + convertedFrom + "/" + listing
                            + " rate is stored for " + lastFiling + ": its company figures are left empty.");
                    closing = Double.NaN;
                    average = Double.NaN;
                } else {
                    closing = f.closingRate().doubleValue();
                    average = f.averageRate().doubleValue();
                }
            }
            bvps = times(f.bookValuePerShare(), closing, 1);
            Double profit = times(f.netIncomeToParentAnnualized(), average, 1);
            eps = profit == null || f.periodShares() == null || f.periodShares().signum() == 0 ? null
                    : profit / f.periodShares().doubleValue();
            de = f.debtToEquity() == null ? null : f.debtToEquity().doubleValue();
            ebitda = times(f.ebitdaAnnualized(), average, BILLION);
            op = times(f.operatingIncomeAnnualized(), average, BILLION);
            debt = times(f.totalLiabilities(), closing, BILLION);
            cash = times(f.cash(), closing, BILLION);
        } else {
            notes.add("No financial statements stored.");
        }
        if (f != null && f.sharesOutstanding() != null) {
            shares = f.sharesOutstanding().doubleValue() / BILLION;
        }

        Double dividend = null;
        Integer dividendYear = null;
        try {
            Dividend d = dividend(p.exchange(), p.ticker());
            if (d != null) {
                dividend = d.perShare();
                dividendYear = d.year();
                notes.addAll(d.notes());
            }
        } catch (RuntimeException e) {
            // the dividends never hide the rest of the row
            log.warn("Dividends of {} {} for the portfolio failed: {}", p.exchange(), p.ticker(), e.toString());
            notes.add("The dividends could not be read.");
        }

        return new Row(p, companyName, sector, last, change, change == null || before == 0 ? null : change / before,
                priceDate, priceSource, priceTime, day, averageValue, marketValue, bvps, eps, de, dividend,
                dividendYear, lastFiling, periodEnd, ebitda, shares, debt, cash, op, convertedFrom, List.copyOf(notes));
    }

    /**
     * The dividends of a position: the sum per share, the year of their ex dates and what was corrected.
     */
    record Dividend(double perShare, int year, List<String> notes) {
    }

    /** IDX's and Yahoo's amounts of one dividend further apart than this factor: Yahoo's is taken. */
    static final double DIVIDEND_MISMATCH = 3;

    /**
     * Cash dividends per share with an ex date in this year (Asia/Jakarta), else in the year before, from the Profile
     * tab's dividend table (IDX and Yahoo, listing currency) on today's share basis; null when neither year has one.
     * When IDX's amount of a dividend Yahoo knows too is more than {@link #DIVIDEND_MISMATCH} times off Yahoo's (a
     * rupiah amount stored as USD: INDY's Rp 10 of 2026-06-03 became Rp 178,350), Yahoo's amount is taken and noted.
     */
    Dividend dividend(String exchange, String ticker) {
        return dividend(profiles.companyProfile(exchange, ticker).dividends(), LocalDate.now(JAKARTA).getYear());
    }

    static Dividend dividend(List<DividendRow> rows, int year) {
        for (int y : new int[] {year, year - 1}) {
            double sum = 0;
            int count = 0;
            List<String> notes = new ArrayList<>();
            for (DividendRow r : rows) {
                LocalDate day = r.exDate() != null ? r.exDate() : r.cumDate() != null ? r.cumDate().plusDays(1)
                        : r.paymentDate();
                if (day == null || day.getYear() != y || r.perShareListing() == null) {
                    continue;
                }
                BigDecimal factor = r.splitFactor() == null || r.splitFactor().signum() <= 0 ? BigDecimal.ONE
                        : r.splitFactor();
                double perShare = r.perShareListing().doubleValue() / factor.doubleValue();
                if (r.yahooPerShare() != null && r.yahooPerShare().signum() > 0 && perShare > 0) {
                    // Yahoo's amount is on today's share basis already
                    double yahoo = r.yahooPerShare().doubleValue();
                    double ratio = perShare / yahoo;
                    if (ratio > DIVIDEND_MISMATCH || ratio < 1 / DIVIDEND_MISMATCH) {
                        notes.add(String.format(java.util.Locale.ROOT, "Dividend of %s: IDX's %s %s is %.0f times "
                                + "Yahoo's %.2f, Yahoo's amount is used.", day, r.cashPerShare() == null ? "?"
                                : r.cashPerShare().stripTrailingZeros().toPlainString(), r.currency(), ratio, yahoo));
                        perShare = yahoo;
                    }
                }
                sum += perShare;
                count++;
            }
            if (count > 0) {
                return new Dividend(Math.round(sum * 10_000) / 10_000.0, y, List.copyOf(notes));
            }
        }
        return null;
    }

    /** The latest intraday session stored for a company, with the closes of its bars; null when none. */
    private Intraday intraday(long companyId) {
        return intraday.quote(companyId).map(q -> new Intraday(q.sessionDate(), num(q.previousClose()),
                num(q.dayOpen()), num(q.dayHigh()), num(q.dayLow()), q.volume(),
                OffsetDateTime.ofInstant(q.marketTime(), JAKARTA), OffsetDateTime.ofInstant(q.fetchedAt(), JAKARTA),
                intraday.points(companyId, q.sessionDate()).stream()
                        .map(x -> new double[] {x.time().getEpochSecond(), x.close().doubleValue()}).toList()))
                .orElse(null);
    }

    private static Double num(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static Double times(BigDecimal value, double rate, double divisor) {
        if (value == null || Double.isNaN(rate)) {
            return null;
        }
        return value.doubleValue() * rate / divisor;
    }

    private static Double ratio(Double a, Double b) {
        if (a == null || b == null || b <= 0 || Double.isNaN(a) || Double.isNaN(b)) {
            return null;
        }
        return a / b;
    }

    // ------------------------------------------------------------------ write

    @Transactional
    public Portfolio saveSettings(long userId, SettingsRequest request) {
        repository.saveSettings(userId, request.cash(), request.totalInvestment());
        return portfolio(userId);
    }

    @Transactional
    public Portfolio add(long userId, PositionRequest request) {
        Exchange exchange = Exchange.of(request.exchange());
        String ticker = Tickers.normalize(request.ticker());
        long companyId = repository.companyId(exchange.code(), ticker).orElseThrow(() -> ApiException.notFound(
                "Company not found", "No company " + ticker + " on " + exchange.code()));
        checkDate(request.dateIn());
        if (repository.holds(userId, companyId)) {
            throw ApiException.conflict(ticker + " is already in the portfolio: edit its position instead.");
        }
        try {
            repository.insert(userId, companyId, request.averagePrice(), request.shares(), request.dateIn());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(ticker + " is already in the portfolio: edit its position instead.");
        }
        return portfolio(userId);
    }

    @Transactional
    public Portfolio update(long userId, long positionId, PositionUpdate request) {
        repository.position(userId, positionId).orElseThrow(PortfolioService::notFound);
        checkDate(request.dateIn());
        repository.update(userId, positionId, request.averagePrice(), request.shares(), request.dateIn());
        return portfolio(userId);
    }

    @Transactional
    public Portfolio delete(long userId, long positionId) {
        if (!repository.delete(userId, positionId)) {
            throw notFound();
        }
        return portfolio(userId);
    }

    private static void checkDate(LocalDate dateIn) {
        if (dateIn != null && dateIn.isAfter(LocalDate.now(JAKARTA))) {
            throw ApiException.badRequest("dateIn: the date of the first buy cannot be in the future");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("Position not found", "No such position in your portfolio");
    }
}
