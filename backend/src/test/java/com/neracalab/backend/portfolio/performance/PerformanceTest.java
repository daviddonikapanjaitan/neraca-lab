package com.neracalab.backend.portfolio.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.neracalab.backend.auth.Permission;
import com.neracalab.backend.auth.TestAccounts;
import com.neracalab.backend.auth.TestLogins;
import com.neracalab.backend.company.Exchange;
import com.neracalab.backend.portfolio.PortfolioService;
import com.neracalab.backend.portfolio.PortfolioViews.PositionRequest;
import com.neracalab.backend.portfolio.PortfolioViews.SettingsRequest;
import com.neracalab.backend.portfolio.intraday.IdxSession;
import com.neracalab.backend.portfolio.intraday.IntradayService;
import com.neracalab.backend.portfolio.performance.PerformanceService.Capture;
import com.neracalab.backend.portfolio.performance.PerformanceService.Outcome;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Chart;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Performance;
import com.neracalab.backend.portfolio.performance.PerformanceViews.Point;
import com.neracalab.backend.portfolio.performance.PerformanceViews.YearRequest;
import com.neracalab.backend.portfolio.performance.PerformanceViews.YearView;
import com.neracalab.backend.price.IndexIngestion;
import com.neracalab.backend.price.PriceIngestionService.Result;
import com.neracalab.backend.web.ApiException;

/**
 * Portfolio &gt; Performance against the Docker Postgres (IHSG / LQ45 of {@code index_daily}), with users made for the
 * test (their rows go with them): the sheet tracking_performance's formulas, the chart, the 17:00 snapshot and its
 * refusals, the API. The service under test reads no Yahoo: the index ingestion is a counting stub and the intraday
 * refresh a mock.
 */
@SpringBootTest
class PerformanceTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PerformanceRepository repository;

    @Autowired
    private PortfolioService portfolios;

    @Autowired
    private JdbcClient jdbc;

    private TestAccounts accounts;
    private long owner;
    private long other;
    private long noAccess;
    private final AtomicInteger ingested = new AtomicInteger();
    private IntradayService intraday;

    @BeforeEach
    void users() {
        accounts = new TestAccounts(context);
        long role = accounts.role("performance", Permission.COMPANIES);
        long ingestion = accounts.role("no-companies", Permission.INGESTION);
        owner = accounts.user("owner", role);
        other = accounts.user("other", role);
        noAccess = accounts.user("none", ingestion);
        intraday = mock(IntradayService.class);
    }

    @AfterEach
    void cleanup() {
        accounts.cleanup();
    }

    /** The service at a fixed time (WIB), without Yahoo. */
    private PerformanceService at(String isoLocal) {
        IndexIngestion stub = new IndexIngestion() {
            @Override
            public Optional<IndexRef> index(Exchange exchange) {
                return Optional.of(new IndexRef("IHSG", "^JKSE", "IHSG", exchange));
            }

            @Override
            public Stored stored(IndexRef index) {
                return new Stored(0, null, null);
            }

            @Override
            public String sourceName() {
                return "stub";
            }

            @Override
            public Result ingest(IndexRef index, boolean full) {
                ingested.incrementAndGet();
                return null;
            }
        };
        PerformanceService s = new PerformanceService(repository, portfolios, intraday, stub, LocalTime.of(17, 0),
                2022, 2026, true, "0 0/15 17-23 * * MON-FRI");
        s.clock(Clock.fixed(ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), IdxSession.ZONE).toInstant(),
                IdxSession.ZONE));
        return s;
    }

    /** The rows of the sheet tracking_performance (instruction/Position Portfolio.xlsx). */
    private void sheet(PerformanceService s, long user) {
        s.saveYear(user, 2022, row("31000000", "58000000", null, "1889981", "102135000", "103136939", "0.0278", null),
                null);
        s.saveYear(user, 2023, row("104024981", "40000000", "5987241", "507629", "135201200", "146464995", "0.0629",
                "0.0377"), null);
        s.saveYear(user, 2024, row("135708829", "15000000", "5253531", "21566579", "127408800", "145126892",
                "-0.0259", "-0.1473"), null);
        s.saveYear(user, 2025, row("148975379", "0", "6029988", "27474186", "291955200", "145126892", "0.2192",
                "0.0219"), null);
        s.saveYear(user, 2026, row("319429386", "0", "8900427", "4852232", "268333600", "187959947", "-0.4093",
                "-0.3788"), null);
    }

    private static YearRequest row(String start, String invest, String dividend, String cash, String market,
                                   String average, String ihsg, String lq45) {
        return new YearRequest(bd(start), bd(invest), bd(dividend), bd(cash), bd(market), bd(average), bd(ihsg),
                bd(lq45), null);
    }

    private static BigDecimal bd(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static YearView year(Performance p, int y) {
        return p.years().stream().filter(v -> v.year() == y).findFirst().orElseThrow();
    }

    private double close(String index, String day) {
        return jdbc.sql("SELECT close_price FROM index_daily WHERE index_code = :i AND trading_date = :d")
                .param("i", index).param("d", LocalDate.parse(day)).query(Double.class).single();
    }

    @Test
    void theSheetsYears() {
        PerformanceService s = at("2026-11-14T10:00");   // a Saturday
        sheet(s, owner);
        Performance p = s.performance(owner, null);

        assertThat(p.ranges()).containsExactly("ALL", "2022", "2023", "2024", "2025", "2026");
        assertThat(p.years()).extracting(YearView::year).containsExactly(2022, 2023, 2024, 2025, 2026);
        // POT G/L and POT G/L % as the sheet computes them: (F - D - C) + E and / C
        YearView y22 = year(p, 2022);
        assertThat(y22.status()).isEqualTo("CLOSED");
        assertThat(y22.endFrom()).isEqualTo("TYPED");
        assertThat(y22.profit()).isCloseTo(15_024_981, within(1e-6));
        assertThat(y22.profitPct()).isCloseTo(0.48467680645161293, within(1e-12));
        assertThat(year(p, 2023).profitPct()).isCloseTo(-0.07994379734613939, within(1e-12));
        assertThat(year(p, 2024).profitPct()).isCloseTo(-0.01277330305458608, within(1e-12));
        assertThat(year(p, 2025).profitPct()).isCloseTo(1.1441756895949902, within(1e-12));
        // Deviden % = dividend / (market value + cash)
        assertThat(year(p, 2023).dividendPct()).isCloseTo(0.044118286511778834, within(1e-12));
        assertThat(year(p, 2025).dividendPct()).isCloseTo(0.018877374043476388, within(1e-12));
        assertThat(y22.dividendPct()).isNull();
        // IHSG / LQ45 of the calendar year from index_daily, the sheet's figures beside them
        assertThat(y22.ihsg()).isCloseTo(close("IHSG", "2022-12-30") / close("IHSG", "2021-12-30") - 1, within(1e-12));
        assertThat(year(p, 2024).lq45()).isCloseTo(close("LQ45", "2024-12-30") / close("LQ45", "2023-12-29") - 1,
                within(1e-12));
        assertThat(year(p, 2025).ihsgRecorded()).isEqualTo(0.2192);
        assertThat(y22.lq45Recorded()).isNull();
        assertThat(y22.portfolioVsIhsg()).isCloseTo(y22.profitPct() - y22.ihsg(), within(1e-12));

        // without a portfolio the current year keeps the typed figures (of an unknown day)
        YearView y26 = year(p, 2026);
        assertThat(y26.status()).isEqualTo("CURRENT");
        assertThat(y26.endFrom()).isEqualTo("TYPED");
        assertThat(y26.endDate()).isNull();
        assertThat(y26.profitPct()).isCloseTo(-0.14476925426015752, within(1e-12));
        assertThat(p.live()).isNull();

        // an empty start capital is the market value + cash of the year before
        s.saveYear(owner, 2023, row(null, "40000000", "5987241", "507629", "135201200", "146464995", null, null),
                null);
        YearView y23 = year(s.performance(owner, null), 2023);
        assertThat(y23.startFrom()).isEqualTo("YEAR_BEFORE");
        assertThat(y23.startCapital()).isCloseTo(102_135_000 + 1_889_981, within(1e-6));
        assertThat(y23.profitPct()).isCloseTo(-0.07994379734613939, within(1e-12));

        // another user sees none of it
        assertThat(s.performance(other, null).years()).allMatch(v -> !v.typed() && v.profitPct() == null);
    }

    @Test
    void theChart() {
        PerformanceService s = at("2026-11-14T10:00");
        sheet(s, owner);
        Chart c = s.performance(owner, "all").chart();

        assertThat(c.from()).isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(c.to()).isEqualTo(LocalDate.of(2026, 12, 31));   // to the end of 2026
        assertThat(c.base()).isEqualTo(LocalDate.of(2021, 12, 30));  // the last close of 2021
        Point base = c.points().getFirst();
        assertThat(base.date()).isEqualTo(c.base());
        assertThat(base.portfolio()).isZero();
        assertThat(base.ihsg()).isZero();
        assertThat(base.lq45()).isZero();
        assertThat(base.kind()).isEqualTo("BASE");

        // IHSG daily against the base close
        Point ihsg = c.points().stream().filter(x -> x.date().equals(LocalDate.of(2024, 12, 30))).findFirst()
                .orElseThrow();
        assertThat(ihsg.ihsg()).isCloseTo(close("IHSG", "2024-12-30") / close("IHSG", "2021-12-30") - 1,
                within(1e-12));
        // the portfolio: the years' POT G/L % chained, a point each 31 December
        double f = 1.48467680645161293;
        Point y22 = point(c, "2022-12-31");
        assertThat(y22.kind()).isEqualTo("YEAR_END");
        assertThat(y22.portfolio()).isCloseTo(f - 1, within(1e-12));
        assertThat(y22.ihsg()).isNull();
        f *= 1 - 0.07994379734613939;
        f *= 1 - 0.01277330305458608;
        f *= 1 + 1.1441756895949902;
        assertThat(point(c, "2025-12-31").portfolio()).isCloseTo(f - 1, within(1e-12));
        // 2026 has no snapshot and no live portfolio (the test user holds nothing): the line ends at 2025
        assertThat(c.portfolio()).isCloseTo(f - 1, within(1e-12));
        assertThat(c.notes()).anyMatch(n -> n.contains("No snapshot of 2026"));
        assertThat(c.points()).extracting(Point::date).isSorted();
        assertThat(c.ihsg()).isCloseTo(close("IHSG", "2026-11-13") / close("IHSG", "2021-12-30") - 1, within(1e-12));

        // one year: from the last close of the year before
        Chart y25 = s.performance(owner, "2025").chart();
        assertThat(y25.base()).isEqualTo(LocalDate.of(2024, 12, 30));
        assertThat(y25.portfolio()).isCloseTo(1.1441756895949902, within(1e-12));
        assertThat(y25.ihsg()).isCloseTo(close("IHSG", "2025-12-30") / close("IHSG", "2024-12-30") - 1, within(1e-12));

        assertThatThrownBy(() -> s.performance(owner, "2019")).isInstanceOf(ApiException.class);
    }

    private static Point point(Chart c, String day) {
        return c.points().stream().filter(x -> x.date().equals(LocalDate.parse(day))).findFirst().orElseThrow();
    }

    @Test
    void theSnapshotAtTheEndOfTheSession() {
        portfolios.saveSettings(owner, new SettingsRequest(new BigDecimal("4852232"), new BigDecimal("150000000")));
        portfolios.add(owner, new PositionRequest("IDX", "HRTA", new BigDecimal("317.11"), new BigDecimal("80600"),
                null));
        PerformanceService before = at("2026-11-13T16:59");
        sheet(before, owner);

        // before 17:00, on a weekend, on an exchange holiday: nothing is saved
        assertThat(before.capture(owner, "MANUAL", true).outcome()).isEqualTo(Outcome.NOT_YET);
        assertThat(at("2026-11-14T18:00").capture(owner, "MANUAL", true).outcome()).isEqualTo(Outcome.WEEKEND);
        Capture holiday = at("2025-12-25T17:30").capture(owner, "SCHEDULE", false);
        assertThat(holiday.outcome()).isEqualTo(Outcome.NO_SESSION);
        assertThat(ingested.get()).isEqualTo(1);   // the index bars were asked for first
        verify(intraday, never()).refreshUser(owner);
        assertThatThrownBy(() -> before.saveToday(owner, null)).isInstanceOf(ApiException.class)
                .hasMessageContaining("17:00");
        assertThat(repository.countSnapshots(owner)).isZero();
        assertThat(before.performance(owner, null).saving().canSaveNow()).isFalse();
        assertThat(before.performance(owner, null).saving().next().toLocalDateTime())
                .isEqualTo(java.time.LocalDateTime.parse("2026-11-13T17:00"));

        // a trading day at 17:30: saved once, with the portfolio as Position shows it and IHSG / LQ45 of the day
        PerformanceService s = at("2026-11-13T17:30");
        Capture c = s.captureScheduled(owner);
        assertThat(c.outcome()).isEqualTo(Outcome.SAVED);
        verify(intraday).refreshUser(owner);
        assertThat(s.captureScheduled(owner)).isNull();   // the schedule saves a day once
        var snap = c.snapshot();
        var sum = portfolios.portfolio(owner).summary();
        assertThat(snap.date()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(snap.source()).isEqualTo("SCHEDULE");
        assertThat(snap.positions()).isEqualTo(1);
        assertThat(snap.cash()).isEqualTo(4_852_232);
        assertThat(snap.marketValue()).isCloseTo(sum.marketTotal(), within(0.01));
        assertThat(snap.averageValue()).isCloseTo(317.11 * 80600, within(0.01));
        assertThat(snap.profitLoss()).isCloseTo(snap.marketValue() - snap.averageValue(), within(0.02));
        assertThat(snap.profitLossPct()).isCloseTo(snap.profitLoss() / snap.averageValue(), within(1e-6));
        assertThat(snap.yearStartCapital()).isEqualTo(319_429_386);
        assertThat(snap.ytdProfit()).isCloseTo(snap.marketValue() + snap.cash() - 319_429_386, within(0.02));
        assertThat(snap.ytdProfitPct()).isCloseTo(snap.ytdProfit() / 319_429_386, within(1e-6));
        assertThat(snap.ihsgDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(snap.ihsgClose()).isCloseTo(close("IHSG", "2026-11-13"), within(1e-8));
        assertThat(snap.ihsgChangePct()).isCloseTo(close("IHSG", "2026-11-13") / close("IHSG", "2026-11-12") - 1,
                within(1e-6));
        assertThat(snap.ihsgYtdPct()).isCloseTo(close("IHSG", "2026-11-13") / close("IHSG", "2025-12-30") - 1,
                within(1e-6));
        assertThat(snap.lq45YtdPct()).isCloseTo(close("LQ45", "2026-11-13") / close("LQ45", "2025-12-30") - 1,
                within(1e-6));

        // "Save today" again replaces it; the page shows it
        var saved = s.saveToday(owner, "2026");
        assertThat(saved.snapshot().source()).isEqualTo("MANUAL");
        assertThat(repository.countSnapshots(owner)).isEqualTo(1);
        Performance p = saved.performance();
        assertThat(p.snapshots()).hasSize(1);
        assertThat(p.saving().savedToday()).isTrue();
        assertThat(p.saving().next().toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-11-16T17:00"));
        // the current year to date and the chart's last point: the live portfolio
        YearView y26 = year(p, 2026);
        assertThat(y26.endFrom()).isEqualTo("LIVE");
        assertThat(y26.profitPct()).isCloseTo(snap.ytdProfitPct(), within(1e-6));
        Point last = p.chart().points().getLast();
        assertThat(last.date()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(last.kind()).isEqualTo("LIVE");
        assertThat(last.portfolio()).isCloseTo(snap.ytdProfitPct(), within(1e-6));
        assertThat(p.chart().to()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void theApi() throws Exception {
        MockMvc mvc = TestLogins.mockMvc(context, accounts.token(owner));
        mvc.perform(get("/api/v1/portfolio/performance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ranges[0]").value("ALL"))
                .andExpect(jsonPath("$.chart.range").value("ALL"))
                .andExpect(jsonPath("$.chart.to").value(org.hamcrest.Matchers.endsWith("-12-31")))
                .andExpect(jsonPath("$.saving.saveFrom").value("17:00"));
        mvc.perform(get("/api/v1/portfolio/performance?range=1990")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/portfolio/performance/years/2025").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startCapital\":148975379,\"newInvestment\":0,\"dividend\":6029988,"
                                + "\"cash\":27474186,\"marketValue\":291955200,\"averageValue\":145126892,"
                                + "\"ihsgYtd\":0.2192,\"lq45Ytd\":0.0219}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.years[?(@.year == 2025)].typed").value(true))
                .andExpect(jsonPath("$.years[?(@.year == 2025)].profit").value(170454007.0));
        mvc.perform(put("/api/v1/portfolio/performance/years/2025").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cash\":-1,\"ihsgYtd\":-2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cash").exists())
                .andExpect(jsonPath("$.errors.ihsgYtd").exists());
        mvc.perform(put("/api/v1/portfolio/performance/years/1999").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());

        // another user cannot touch it, a user without COMPANIES cannot open the page
        MockMvc stranger = TestLogins.mockMvc(context, accounts.token(other));
        stranger.perform(delete("/api/v1/portfolio/performance/years/2025")).andExpect(status().isNotFound());
        TestLogins.mockMvc(context, accounts.token(noAccess)).perform(get("/api/v1/portfolio/performance"))
                .andExpect(status().isForbidden());
        TestLogins.mockMvc(context, accounts.token(noAccess)).perform(post("/api/v1/portfolio/performance/snapshots"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/v1/portfolio/performance/years/2025")).andExpect(status().isOk())
                .andExpect(jsonPath("$.years[?(@.year == 2025)].typed").value(false));
        mvc.perform(delete("/api/v1/portfolio/performance/years/2025")).andExpect(status().isNotFound());
        assertThat(List.of(repository.years(owner).size())).containsExactly(0);
    }
}
